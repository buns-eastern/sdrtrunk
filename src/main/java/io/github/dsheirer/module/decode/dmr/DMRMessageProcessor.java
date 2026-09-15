/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.edac.CRCDMR;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.channel.ITimeslotFrequencyReceiver;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.CACH;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessageWithLinkControl;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.IDLEMessage;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Aloha;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.Preamble;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.announcement.Announcement;
import io.github.dsheirer.module.decode.dmr.message.data.header.MBCHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.PacketSequenceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.ProprietaryDataHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FLCAssembler;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.FullLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.TalkerAliasAssembler;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.SLCAssembler;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.ShortLCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.mbc.MBCAssembler;
import io.github.dsheirer.module.decode.dmr.message.data.mbc.MBCContinuationBlock;
import io.github.dsheirer.module.decode.dmr.message.data.packet.PacketSequenceAssembler;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.voice.EMB;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceSuperFrameProcessor;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes DMR messages and performs re-assembly of link control fragments
 */
public class DMRMessageProcessor implements Listener<IMessage>
{
    private final static Logger mLog = LoggerFactory.getLogger(DMRMessageProcessor.class);
    private final DecodeConfigDMR mConfigDMR;
    private final VoiceSuperFrameProcessor mSuperFrameProcessor1 = new VoiceSuperFrameProcessor();
    private final VoiceSuperFrameProcessor mSuperFrameProcessor2 = new VoiceSuperFrameProcessor();
    private final FLCAssembler mFLCAssemblerTimeslot1;
    private final FLCAssembler mFLCAssemblerTimeslot2;
    private final MBCAssembler mMBCAssembler;
    private final PacketSequenceAssembler mPacketSequenceAssembler;
    private final SLCAssembler mSLCAssembler = new SLCAssembler();
    private final TalkerAliasAssembler mTalkerAliasAssembler = new TalkerAliasAssembler();
    private Listener<IMessage> mMessageListener;
    private final Map<Integer,TimeslotFrequency> mTimeslotFrequencyMap = new TreeMap<>();
    private final DMRCrcMaskManager mCrcMaskManager;

    /**
     * Constructs an instance
     * @param config for the DMR decoder
     * @param crcMaskManager for message error detection and correction.
     */
    public DMRMessageProcessor(DecodeConfigDMR config, DMRCrcMaskManager crcMaskManager)
    {
        mConfigDMR = config;
        mCrcMaskManager = crcMaskManager;
        mMBCAssembler = new MBCAssembler(crcMaskManager.isIgnoreCRCChecksums());
        mFLCAssemblerTimeslot1 = new FLCAssembler(1, crcMaskManager);
        mFLCAssemblerTimeslot2 = new FLCAssembler(2, crcMaskManager);

        for(TimeslotFrequency timeslotFrequency: config.getTimeslotMap())
        {
            mTimeslotFrequencyMap.put(timeslotFrequency.getNumber(), timeslotFrequency);
        }

        mPacketSequenceAssembler = new PacketSequenceAssembler();
    }

    /**
     * Indicates if the message is valid or if the Ignore CRC Checksums feature is enabled.
     *
     * @param message to check
     * @return true if ignore CRC checksums or if the message is valid.
     */
    //Color code filter latch state, indexed by timeslot (1-2).  Index 0 is unused.
    private static final int CC_UNKNOWN = 0;
    private static final int CC_ACCEPTED = 1;
    private static final int CC_REJECTED = 2;
    private final int[] mColorCodeState = new int[3];

    /**
     * Resets the color code filter decision for both timeslots so that the next burst is evaluated fresh.  Called on
     * sync loss - resetting to unknown always errs toward allowing traffic through.
     */
    private void resetColorCodeFilter()
    {
        mColorCodeState[1] = CC_UNKNOWN;
        mColorCodeState[2] = CC_UNKNOWN;
    }

    /**
     * Extracts the color code from a burst when it carries one that passed its CRC check.  Data bursts carry the
     * color code in the slot type and voice frames B-F carry it in the EMB.  Voice frame A carries no color code.
     *
     * @param burst to inspect.
     * @return color code, or null when this burst carries no readable color code.
     */
    private Integer getColorCode(DMRBurst burst)
    {
        if(burst instanceof DataMessage dataMessage)
        {
            SlotType slotType = dataMessage.getSlotType();

            if(slotType != null && slotType.isValid())
            {
                return slotType.getColorCode();
            }
        }
        else if(burst instanceof VoiceEMBMessage voiceMessage)
        {
            EMB emb = voiceMessage.getEMB();

            if(emb != null && emb.isValid())
            {
                return emb.getColorCode();
            }
        }

        return null;
    }

    /**
     * Applies the channel's color code filter to a burst.
     *
     * A burst carrying a readable color code sets the decision for its timeslot: allowed color codes accept the
     * timeslot and any other color code rejects it.  A burst with no readable color code, such as voice frame A,
     * inherits the current decision for its timeslot, so a rejected call stays rejected for its whole duration
     * rather than leaking one voice frame in every six.  The decision resets on sync loss, and any allowed color
     * code immediately clears a rejection so that recovery is instant when the foreign traffic stops.
     *
     * @param burst to evaluate.
     * @return true when the burst should be rejected.
     */
    private boolean isColorCodeRejected(DMRBurst burst)
    {
        int timeslot = burst.getTimeslot();

        if(timeslot < 1 || timeslot > 2)
        {
            //Can't track a decision for this burst - allow it through.
            return false;
        }

        Integer colorCode = getColorCode(burst);

        if(colorCode != null)
        {
            boolean allowed = mConfigDMR.isColorCodeAllowed(colorCode);
            mColorCodeState[timeslot] = allowed ? CC_ACCEPTED : CC_REJECTED;
            return !allowed;
        }

        return mColorCodeState[timeslot] == CC_REJECTED;
    }

    private boolean isValid(IMessage message)
    {
        return mCrcMaskManager.isIgnoreCRCChecksums() || message.isValid();
    }

    /**
     * Primary message processing
     */
    @Override
    public void receive(IMessage message)
    {
        if(message == null)
        {
            return;
        }

        if(message instanceof SyncLossMessage)
        {
            mSLCAssembler.reset();
            mFLCAssemblerTimeslot1.reset();
            mFLCAssemblerTimeslot2.reset();
            resetColorCodeFilter();
        }

        //Color code filter.  A rejected burst is still dispatched so that it stays visible in the message activity
        //view, but it is not fed to the link control assemblers or superframe processors here, and the decoder state
        //and audio modules ignore it, so it produces no decode events, no audio, and no recording or streaming.
        if(message instanceof DMRBurst colorCodeCandidate && mConfigDMR.hasColorCodeFilter() &&
           isColorCodeRejected(colorCodeCandidate))
        {
            colorCodeCandidate.setColorCodeRejected(true);
            dispatch(colorCodeCandidate);
            return;
        }

        //Detect and correct messages employing an alternate CRC mask pattern (ie RAS) when ignore CRC is disabled
        if(message instanceof CSBKMessage csbk && !message.isValid())
        {
            int opcode = csbk.getOpcode().getValue();
            int residual = CRCDMR.calculateResidual(csbk.getMessage(), 0, 80);
            csbk.setValid(mCrcMaskManager.isValidCSBK(opcode, residual, csbk.getTimestamp()));
        }

        if(message instanceof FullLCMessage flc)
        {
            if(flc.getTimeslot() == 1)
            {
                mSuperFrameProcessor1.process(flc);
            }
            else
            {
                mSuperFrameProcessor2.process(flc);
            }
        }
        else if(message instanceof VoiceMessage voiceMessage)
        {
            if(voiceMessage.getTimeslot() == 1)
            {
                mSuperFrameProcessor1.process(voiceMessage);
            }
            else
            {
                mSuperFrameProcessor2.process(voiceMessage);
            }
        }
        else if(message instanceof DMRBurst dmrBurst && isValid(dmrBurst))
        {
            if(dmrBurst.getTimeslot() == 1)
            {
                mSuperFrameProcessor1.reset();
            }
            else
            {
                mSuperFrameProcessor2.reset();
            }
        }

        //Process data messages carrying a link control payload so that the LC payload can be processed/enriched
        if(message instanceof DataMessageWithLinkControl linkControlCarrier)
        {
            enrich(linkControlCarrier.getLCMessage());
        }

        //Enrich messages that carry DMR Logical Channel Numbers with LCN to frequency mappings
        enrich(message);

        //Now that the message has been (potentially) enriched, dispatch it to the modules
        dispatch(message);

        //Extract the Full Link Control message fragment from the Voice with embedded signalling message
        if(message instanceof VoiceEMBMessage voice)
        {
            if(message.getTimeslot() == 1)
            {
                FullLCMessage flco = mFLCAssemblerTimeslot1.process(voice.getEMB().getLCSS(),
                    voice.getFLCFragment(), message.getTimestamp());
                receive(flco);
            }
            else
            {
                FullLCMessage flco = mFLCAssemblerTimeslot2.process(voice.getEMB().getLCSS(),
                    voice.getFLCFragment(), message.getTimestamp());
                receive(flco);
            }

            if(voice.hasCACH())
            {
                CACH cach = voice.getCACH();
                ShortLCMessage slco = mSLCAssembler.process(cach.getLCSS(), cach.getPayload(), message.getTimestamp());
                receive(slco);
            }
        }
        //Extract the Short Link Control message fragment from the DMR burst message when it has one
        else if(message instanceof DMRBurst dmrBurst)
        {
            if(dmrBurst.hasCACH())
            {
                CACH cach = dmrBurst.getCACH();
                ShortLCMessage slco = mSLCAssembler.process(cach.getLCSS(), cach.getPayload(), message.getTimestamp());
                receive(slco);
            }

            //Multi-Block CSBK Reassembly
            if(message instanceof MBCHeader)
            {
                mMBCAssembler.process((MBCHeader)message);
            }
            else if(message instanceof MBCContinuationBlock)
            {
                //Returns either a fully reassembled MultiCSBK or null
                receive(mMBCAssembler.process((MBCContinuationBlock)message));
            }
            else
            {
                mMBCAssembler.reset(message.getTimeslot());
            }

            //Packet Sequence Message Assembly ...
            if(message instanceof Preamble preamble)
            {
                mPacketSequenceAssembler.process(preamble);
            }
            else if(message instanceof PacketSequenceHeader header)
            {
                mPacketSequenceAssembler.process(header);
            }
            else if(message instanceof ProprietaryDataHeader header)
            {
                mPacketSequenceAssembler.process(header);
            }
            else if(message instanceof DataBlock dataBlock)
            {
                mPacketSequenceAssembler.process(dataBlock);
            }
            else if(message instanceof UDTHeader header)
            {
                mPacketSequenceAssembler.process(header);
            }
            else if((message instanceof IDLEMessage || message instanceof Aloha || message instanceof Announcement) &&
                    message.getTimeslot() != 0)
            {
                mPacketSequenceAssembler.dispatchPacketSequence(message.getTimeslot());
            }

            //Reset talker alias assembler on Idle or Terminator
            if(isValid(message) && (message instanceof IDLEMessage || message instanceof Terminator))
            {
                mTalkerAliasAssembler.reset(message.getTimeslot());
            }
        }

        //Assemble Talker Alias from FLC message fragments (header & blocks 1-3)
        if(message instanceof FullLCMessage flc && flc.getOpcode().isTalkerAliasOpcode() && isValid(message))
        {
            dispatch(mTalkerAliasAssembler.process(flc));
        }
    }

    /**
     * Enrich messages that carry DMR Logical Channel Numbers with LCN to frequency mappings
     * @param message to be enriched
     */
    private void enrich(IMessage message)
    {
        if(message instanceof ITimeslotFrequencyReceiver receiver)
        {
            int[] lcns = receiver.getLogicalChannelNumbers();

            List<TimeslotFrequency> timeslotFrequencies = new ArrayList<>();

            for(int lcn: lcns)
            {
                if(mTimeslotFrequencyMap.containsKey(lcn))
                {
                    timeslotFrequencies.add(mTimeslotFrequencyMap.get(lcn));
                }
            }

            if(!timeslotFrequencies.isEmpty())
            {
                receiver.apply(timeslotFrequencies);
            }
        }
    }

    /**
     * Dispatches the non-null message to the registered listener
     */
    private void dispatch(IMessage message)
    {
        if(mMessageListener != null && message != null)
        {
            //Configure compressed talkgroups for the DMR talkgroup identifiers in the message.
            if(mConfigDMR.isUseCompressedTalkgroups())
            {
                try
                {
                    for(Identifier identifier: message.getIdentifiers())
                    {
                        if(identifier instanceof DMRTalkgroup talkgroup)
                        {
                            talkgroup.setCompressed(true);
                        }
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error applying compressed talkgroup setting to identifier in message: {} [{}]",
                            message, message.getClass());
                }
            }

            mMessageListener.receive(message);
        }
    }

    /**
     * Prepares for disposal
     */
    public void dispose()
    {
        mMessageListener = null;
    }

    /**
     * Registers the listener to receive messages from this processor
     */
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
        mPacketSequenceAssembler.setMessageListener(listener);
    }

    /**
     * Removes the listener from receiving messages from this processor
     */
    public void removeMessageListener()
    {
        mMessageListener = null;
        mPacketSequenceAssembler.setMessageListener(null);
    }
}
