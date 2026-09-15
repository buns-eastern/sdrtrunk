/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.util.ArrayList;
import java.util.List;

/**
 * DMR Decoder Configuration
 */
public class DecodeConfigDMR extends DecodeConfiguration
{
    public static final int CHANNEL_ROTATION_DELAY_MINIMUM_MS = 200;
    public static final int CHANNEL_ROTATION_DELAY_DEFAULT_MS = 500;
    public static final int CHANNEL_ROTATION_DELAY_MAXIMUM_MS = 2000;
    private int mTrafficChannelPoolSize = TRAFFIC_CHANNEL_LIMIT_DEFAULT;
    private boolean mIgnoreDataCalls = true;
    private boolean mIgnoreCRCChecksums = false;
    private boolean mUseCompressedTalkgroups = false;
    private List<TimeslotFrequency> mTimeslotMap = new ArrayList<>();
    private List<Integer> mColorCodeFilter = new ArrayList<>();

    @JsonIgnore
    private DecodeEvent mChannelGrantEvent;

    public DecodeConfigDMR()
    {
    }

    /**
     * Overrides the default value to indicate that DMR has two timeslots
     */
    @Override
    public int getTimeslotCount()
    {
        return 2;
    }

    /**
     * Timeslot identifiers
     */
    @Override
    public int[] getTimeslots()
    {
        return new int[]{DMRMessage.TIMESLOT_1, DMRMessage.TIMESLOT_2};
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.DMR;
    }

    /**
     * Indicates if traffic channel grants for data calls should be ignored
     */
    /**
     * Optional list of color codes (0-15) that are allowed on this channel.  When the list is empty the filter is
     * disabled and every burst is processed, which is the default and matches the behavior of an unfiltered channel.
     *
     * When the list is populated, a burst carrying a CRC-valid color code that is not in this list causes its
     * timeslot to be rejected for the remainder of the call.  Bursts that do not carry a color code (such as voice
     * frame A) inherit the current decision for their timeslot, and a burst whose color code cannot be read is
     * always allowed through.
     *
     * @return allowed color codes, never null.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JacksonXmlProperty(isAttribute = false, localName = "color_code_filter")
    public List<Integer> getColorCodeFilter()
    {
        return mColorCodeFilter;
    }

    /**
     * Sets the list of allowed color codes.  Null or empty disables the filter.
     * @param colorCodes to allow.
     */
    public void setColorCodeFilter(List<Integer> colorCodes)
    {
        mColorCodeFilter = (colorCodes != null) ? colorCodes : new ArrayList<>();
    }

    /**
     * Indicates if the color code filter is enabled, meaning at least one allowed color code is specified.
     */
    @JsonIgnore
    public boolean hasColorCodeFilter()
    {
        return mColorCodeFilter != null && !mColorCodeFilter.isEmpty();
    }

    /**
     * Indicates if the specified color code is allowed on this channel.  Always true when the filter is disabled.
     * @param colorCode to test.
     */
    @JsonIgnore
    public boolean isColorCodeAllowed(int colorCode)
    {
        return !hasColorCodeFilter() || mColorCodeFilter.contains(colorCode);
    }

    @JacksonXmlProperty(isAttribute = true, localName = "ignore_data_calls")
    public boolean getIgnoreDataCalls()
    {
        return mIgnoreDataCalls;
    }

    /**
     * Sets flag to ignore data call traffic channel grants
     * @param ignore true to ignore data calls.
     */
    public void setIgnoreDataCalls(boolean ignore)
    {
        mIgnoreDataCalls = ignore;
    }

    /**
     * Indicates if decoder should ignore CRC checksums when validating decoded messages
     */
    @JacksonXmlProperty(isAttribute = true, localName = "ignore_crc")
    public boolean getIgnoreCRCChecksums()
    {
        return mIgnoreCRCChecksums;
    }

    /**
     * Sets flag to ignore CRC checksums
     * @param ignore true to ignore CRC checksums.
     */
    public void setIgnoreCRCChecksums(boolean ignore)
    {
        mIgnoreCRCChecksums = ignore;
    }

    /**
     * Indicates if decoder should use compressed talkgroups
     */
    @JacksonXmlProperty(isAttribute = true, localName = "use_compressed_talkgroups")
    public boolean isUseCompressedTalkgroups()
    {
        return mUseCompressedTalkgroups;
    }

    /**
     * Sets flag to use compressed talkgroups.
     *
     * Converts the air interface value to a compressed value described in ETSI 102 361-2, V.2.4.1, Paragraph C2.1.2
     * This has been observed in use on Hytera Tier III systems.
     * See: https://cwh050.blogspot.com/2021/03/converting-flat-number-to-dmr-id.html?m=1
     * @param useCompressedTalkgroups true to use compressed talkgroups.
     */
    public void setUseCompressedTalkgroups(boolean useCompressedTalkgroups)
    {
        mUseCompressedTalkgroups = useCompressedTalkgroups;
    }

    /**
     * Traffic channel pool size.
     * @return
     */
    @JacksonXmlProperty(isAttribute = true, localName = "traffic_channel_pool_size")
    public int getTrafficChannelPoolSize()
    {
        return mTrafficChannelPoolSize;
    }

    /**
     * Sets the traffic channel pool size which is the maximum number of
     * simultaneous traffic channels that can be allocated.
     *
     * This limits the maximum calls so that busy systems won't cause more
     * traffic channels to be allocated than the decoder/software/host computer
     * can support.
     */
    public void setTrafficChannelPoolSize(int size)
    {
        mTrafficChannelPoolSize = size;
    }

    /**
     * Source channel specification for this decoder
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(24000.0, 12500, 6500.0, 7200.0);
    }

    /**
     * Timeslot map for this decode configuration.
     *
     * Note: this is only used for MotoTRBO systems
     */
    @JacksonXmlProperty(isAttribute = false, localName = "timeslot")
    public List<TimeslotFrequency> getTimeslotMap()
    {
        return mTimeslotMap;
    }

    /**
     * Sets the timeslot map
     * @param timeslots for a MotoTRBO system
     */
    public void setTimeslotMap(List<TimeslotFrequency> timeslots)
    {
        mTimeslotMap = timeslots;
    }

    /**
     * Adds a timeslot frequency to the map
     */
    @JsonIgnore
    public void addTimeslotFrequency(TimeslotFrequency timeslotFrequency)
    {
        mTimeslotMap.add(timeslotFrequency);
    }

    @JsonIgnore
    /**
     * Original channel grant event.  This is used for trunking systems to pass the original channel grant event to
     * the decoder state on channel startup.
     */
    public DecodeEvent getChannelGrantEvent()
    {
        return mChannelGrantEvent;
    }

    /**
     * Sets the original channel grant event.
     * @param channelGrantEvent to set on the decoder state.
     */
    public void setChannelGrantEvent(DecodeEvent channelGrantEvent)
    {
        mChannelGrantEvent = channelGrantEvent;
    }

    @JsonIgnore
    /**
     * Indicates if this decode config has an original channel grant event to preload.
     */
    public boolean hasChannelGrantEvent()
    {
        return mChannelGrantEvent != null;
    }
}
