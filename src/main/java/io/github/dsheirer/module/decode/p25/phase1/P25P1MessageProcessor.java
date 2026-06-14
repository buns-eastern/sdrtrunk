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
package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.message.AbstractMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandPreloadDataContent;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBandReceiver;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.IExtendedSourceMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.HarrisTalkerAliasAssembler;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasBase;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSBlock1;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSBlock2;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSComplete;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaTalkerAliasAssembler;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSourceIDExtension;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.sample.Listener;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APCO25 Phase 1 Message Processor.
 *
 * Performs post-message creation processing and enrichment before the message is sent downstream.
 */
public class P25P1MessageProcessor implements Listener<IMessage>
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P1MessageProcessor.class);

    /**
     * Downstream message listener
     */
    private Listener<IMessage> mMessageListener;

    /**
     * Map of up to 16 band identifiers per RFSS.  These identifier update messages are inserted into any message that
     * conveys channel information so that the uplink/downlink frequencies can be calculated
     */
    private Map<Integer,IFrequencyBand> mFrequencyBandMap = new TreeMap<Integer,IFrequencyBand>();

    /**
     * Temporary holding for an extended source link control message while it awaits the extension message to arrive.
     */
    private TDULCMessage mHeldTDULCMessage;
    private HDUMessage mHeldHDUMessage;
    private LDU1Message mHeldLDU1Message;
    private LDU2Message mHeldLDU2Message;
    private LCHarrisTalkerGPSBlock1 mHeldHarrisGPSBlock1;
    private long mHeldHarrisGPSBlock1Timestamp;

    //=== ISSIPROBE instrumentation (TEMPORARY, log-only — remove after diagnosis) =====================
    //Counts every LDU1/LDU2 voice frame entering the processor vs. actually dispatched downstream.
    //If, on quiet, lduIn != lduDispatched, the hold mechanism lost audio (DROP). If they reconcile, it's DELAY.
    private long mProbeLduIn = 0;
    private long mProbeLduDispatched = 0;
    private long mProbeHeldWallNanos = 0;
    private int mProbeHeldCount = 0;
    //=================================================================================================

    /**
     * Motorola talker alias assembler for link control header and data blocks.
     */
    private LCMotorolaTalkerAliasAssembler mMotorolaTalkerAliasAssembler = new LCMotorolaTalkerAliasAssembler();

    /**
     * Harris talker alias assembler for link control talker alias blocks
     */
    private HarrisTalkerAliasAssembler mHarrisTalkerAliasAssembler = new HarrisTalkerAliasAssembler();

    /**
     * Constructs an instance
     */
    public P25P1MessageProcessor()
    {
    }

    /**
     * Preloads frequency band information from the control channel.
     * @param content to preload
     */
    public void preload(P25FrequencyBandPreloadDataContent content)
    {
        if(content.hasData())
        {
            for(IFrequencyBand frequencyBand: content.getData())
            {
                mFrequencyBandMap.put(frequencyBand.getIdentifier(), frequencyBand);
            }
        }
    }

    /**
     * Processes the message for enrichment or reassembly of fragments and sends the enriched message and any additional
     * messages that were created during the enrichment to the registered message listener.
     * @param message to process
     */
    @Override
    public void receive(IMessage message)
    {
        if(message.isValid())
        {
            if(message instanceof LDU1Message || message instanceof LDU2Message) { mProbeLduIn++; }   //ISSIPROBE
            //Reassemble extended source link control messages.
            if(message instanceof LDU1Message ldu1)
            {
                LinkControlWord lcw = ldu1.getLinkControlWord();;

                if(lcw instanceof IExtendedSourceMessage esm)
                {
                    if(lcw instanceof LCSourceIDExtension extension)
                    {
                        processSourceIDExtension(extension);
                    }
                    else if(esm.isExtensionRequired())
                    {
                        processSourceIDExtension(null);
                        mHeldLDU1Message = ldu1;
                        //ISSIPROBE: audio starts being withheld here, awaiting the SOURCE_ID_EXTENSION message.
                        mProbeHeldWallNanos = System.nanoTime();
                        mProbeHeldCount++;
                        try { mLog.info("ISSIPROBE HOLD  opcode={} airTs={} heldCount={} lduIn={} dispatched={} (audio withheld, awaiting SOURCE_ID_EXTENSION)",
                                lcw.getOpcode(), ldu1.getTimestamp(), mProbeHeldCount, mProbeLduIn, mProbeLduDispatched); } catch(Exception e) {}
                        return;
                    }
                }
                else if(lcw instanceof LCHarrisTalkerAliasBase harrisTalkerAlias)
                {
                    //Send the LCW to the harris talker alias assembler
                    dispatch(mHarrisTalkerAliasAssembler.process(harrisTalkerAlias, ldu1.getTimestamp()));
                }

                //Flush any held messages
                processSourceIDExtension(null);

                dispatch(ldu1);

                //Process Harris Talker GPS messages
                if(lcw instanceof LCHarrisTalkerGPSBlock1 block1)
                {
                    mHeldHarrisGPSBlock1 = block1;
                    mHeldHarrisGPSBlock1Timestamp = ldu1.getTimestamp();
                }
                else if(lcw instanceof LCHarrisTalkerGPSBlock2 block2 && mHeldHarrisGPSBlock1 != null)
                {
                    //Ensure block 1 and block 2 are within 2 seconds of each other
                    if(Math.abs(ldu1.getTimestamp() - mHeldHarrisGPSBlock1Timestamp) < 2000)
                    {
                        LCHarrisTalkerGPSComplete gps = LCHarrisTalkerGPSComplete.create(mHeldHarrisGPSBlock1, block2,
                                ldu1.getTimestamp());
                        dispatch(gps);
                    }

                    mHeldHarrisGPSBlock1 = null;
                    mHeldHarrisGPSBlock1Timestamp = 0;
                }
            }
            else if(message instanceof LDU2Message ldu2)
            {
                //If we held onto an LDU1 awaiting a source ID extension, then also hold onto this LDU2 and flush them in sequence.
                if(mHeldLDU1Message != null)
                {
                    //ISSIPROBE: an already-held LDU2 being overwritten = ~9 IMBE frames (~180ms) silently LOST.
                    if(mHeldLDU2Message != null)
                    {
                        try { mLog.warn("ISSIPROBE DROP  held LDU2 OVERWRITTEN before flush — ~9 IMBE frames (~180ms) LOST. lduIn={} dispatched={}",
                                mProbeLduIn, mProbeLduDispatched); } catch(Exception e) {}
                    }
                    mHeldLDU2Message = ldu2;
                }
                else
                {
                    dispatch(ldu2);
                }
            }
            else if(message instanceof TDULCMessage tdulc)
            {
                boolean motorolaTalkerAliasComplete = false;
                LinkControlWord lcw = tdulc.getLinkControlWord();

                if(lcw instanceof IExtendedSourceMessage esm)
                {
                    if(lcw instanceof LCSourceIDExtension sourceIDExtension)
                    {
                        processSourceIDExtension(sourceIDExtension);
                    }
                    else if(esm.isExtensionRequired())
                    {
                        processSourceIDExtension(null);
                        mHeldTDULCMessage = tdulc;
                        return;
                    }
                }

                //Motorola carries the talker alias in the TDULC
                else if(mMotorolaTalkerAliasAssembler.add(lcw, message.getTimestamp()))
                {
                    motorolaTalkerAliasComplete = true;
                }

                //Harris carries the talker alias in the LDU1 link control messages, so reset the assembler on TDULC.
                mHarrisTalkerAliasAssembler.reset();

                dispatch(tdulc);

                // Dispatch the complete talker alias after the TDULC to ensure correct log sequencing.
                if(motorolaTalkerAliasComplete)
                {
                    dispatch(mMotorolaTalkerAliasAssembler.assemble());
                }
            }
            else if(message instanceof HDUMessage hdu && mHeldTDULCMessage != null)
            {
                //If the last TDULC message was held because it needs an extension that can arrive in the first LDU1,
                //hold onto the intermediate HDU message and send everything (TDU, HDU, LDU) in correct sequence.
                mHeldHDUMessage = hdu;
            }
            else
            {
                //flush any held messages
                processSourceIDExtension(null);
                dispatch(message);
            }
        }
        else
        {
            dispatch(message);
        }
    }

    /**
     * Processes the source extension message and attaches it to any held TDULC or LDU1 with LC messages and flushes any
     * held messages.
     * @param extension to attach to held message(s) or null extension to flush all held messages.
     */
    private void processSourceIDExtension(LCSourceIDExtension extension)
    {
        if(mHeldTDULCMessage != null && mHeldTDULCMessage.getLinkControlWord() instanceof IExtendedSourceMessage esm)
        {
            esm.setSourceIDExtension(extension);
            dispatch(mHeldTDULCMessage);
            mHeldTDULCMessage = null;
            dispatch(mHeldHDUMessage);
            mHeldHDUMessage = null;
        }

        if(mHeldLDU1Message != null && mHeldLDU1Message.getLinkControlWord() instanceof IExtendedSourceMessage esm)
        {
            //ISSIPROBE: capture the UN-ENRICHED identifiers (exactly what the decoder state WOULD see if we dispatched
            //the audio early, before the extension arrives) so we can tell whether an early dispatch would attach a
            //partial/WRONG FROM (mis-attribution risk) or NO from at all (safe). Read-only — real behavior is unchanged.
            String probeUnenriched = "";
            long probeHeldWallMs = 0;
            try {
                probeHeldWallMs = (System.nanoTime() - mProbeHeldWallNanos) / 1_000_000L;
                probeUnenriched = String.valueOf(mHeldLDU1Message.getLinkControlWord().getIdentifiers());
            } catch(Exception e) {}

            esm.setSourceIDExtension(extension);

            //ISSIPROBE: enriched (correct, full ISSI) identifiers, logged beside the un-enriched ones for direct compare.
            //If unenrichedIDs has the same FROM as enrichedIDs minus qualification => safe. If it shows a different/bare
            //radio FROM => an early dispatch would briefly mis-attribute (drives whether the fix needs FROM suppression).
            try {
                mLog.info("ISSIPROBE FLUSH heldFor={}ms extensionPresent={} heldLDU2Present={} lduIn={} dispatched={} unenrichedIDs={} enrichedIDs={}",
                        probeHeldWallMs, (extension != null), (mHeldLDU2Message != null), mProbeLduIn, mProbeLduDispatched,
                        probeUnenriched, mHeldLDU1Message.getLinkControlWord().getIdentifiers());
            } catch(Exception e) {}
            mProbeHeldCount = 0;

            dispatch(mHeldLDU1Message);
            mHeldLDU1Message = null;
            dispatch(mHeldLDU2Message);
            mHeldLDU2Message = null;
        }
    }

    /**
     * Post-process the message for frequency band details.
     * @param message to post process and dispatch
     */
    private void dispatch(IMessage message)
    {
        if(message == null)
        {
            return;
        }

        if(message instanceof LDU1Message || message instanceof LDU2Message) { mProbeLduDispatched++; }   //ISSIPROBE

        processForFrequencyBands(message);

        //Also process the link control messages for frequency bands.
        if(message instanceof LDU1Message ldu1 && ldu1.getLinkControlWord() instanceof LinkControlWord lcw && lcw.isValid())
        {
            processForFrequencyBands(lcw);
        }
        else if(message instanceof TDULCMessage tdulc && tdulc.getLinkControlWord() instanceof LinkControlWord lcw && lcw.isValid())
        {
            processForFrequencyBands(lcw);
        }

        if(mMessageListener != null)
        {
            mMessageListener.receive(message);
        }
    }

    /**
     * Captures IDEN_UPDATE messages and attaches them to messages with channel descriptors.
     * @param message to process for frequency band assembly.
     */
    private void processForFrequencyBands(IMessage message)
    {
        if(message instanceof AbstractMessage am)
        {
            processForFrequencyBands(am);
        }
    }

    /**
     * Captures IDEN_UPDATE messages and attaches them to messages with channel descriptors.
     * @param message to process for frequency band assembly.
     */
    private void processForFrequencyBands(AbstractMessage message)
    {
        //Insert frequency band identifier update messages into channel-type messages */
        if(message instanceof IFrequencyBandReceiver)
        {
            IFrequencyBandReceiver receiver = (IFrequencyBandReceiver)message;

            List<IChannelDescriptor> channels = receiver.getChannels();

            for(IChannelDescriptor channel : channels)
            {
                int[] frequencyBandIdentifiers = channel.getFrequencyBandIdentifiers();

                for(int id : frequencyBandIdentifiers)
                {
                    if(mFrequencyBandMap.containsKey(id))
                    {
                        channel.setFrequencyBand(mFrequencyBandMap.get(id));
                    }
                }
            }
        }

        //Store band identifiers so that they can be injected into channel type messages
        if(message instanceof IFrequencyBand)
        {
            IFrequencyBand bandIdentifier = (IFrequencyBand)message;

            //Only store the frequency band if it's new so we don't hold on to more than one instance of the
            //frequency band message.  Otherwise, we'll hold on to several instances of each message as they get
            //injected into other messages with channel information.
            if(!mFrequencyBandMap.containsKey(bandIdentifier.getIdentifier()))
            {
                mFrequencyBandMap.put(bandIdentifier.getIdentifier(), bandIdentifier);
            }
        }
    }

    /**
     * Prepares for disposal of this instance.
     */
    public void dispose()
    {
        mFrequencyBandMap.clear();
        mMessageListener = null;
    }

    /**
     * Sets the message listener to receive the output from this processor.
     * @param listener to receive output messages
     */
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    /**
     * Clears a registered message listener.
     */
    public void removeMessageListener()
    {
        mMessageListener = null;
    }
}
