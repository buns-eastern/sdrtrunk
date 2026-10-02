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
package io.github.dsheirer.module.decode.am;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.dsp.am.AmAudioLeveler;
import io.github.dsheirer.dsp.squelch.ISquelchConfiguration;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * AM decoder configuration
 */
public class DecodeConfigAM extends DecodeConfigAnalog implements ISquelchConfiguration
{
    private int mSquelchThreshold = -78;
    private boolean mSquelchAutoTrack = true;
    private boolean mAudioAutoLevel = true;
    private float mAudioOutputGain = AmAudioLeveler.DEFAULT_OUTPUT_GAIN;

	public DecodeConfigAM()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.AM;
    }

    @Override
    protected Bandwidth getDefaultBandwidth()
    {
        return Bandwidth.BW_15_0;
    }

    /**
     * Source channel specification for this decoder
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        switch(getBandwidth())
        {
            case BW_3_0:
                return new ChannelSpecification(25000.0, 3000, 1500.0, 1700.0);
            case BW_5_0:
                return new ChannelSpecification(25000.0, 5000, 2500.0, 2700.0);
            case BW_8_33:
                return new ChannelSpecification(25000.0, 10000, 5000.0, 7000.0);
            case BW_15_0:
                return new ChannelSpecification(25000.0, 15000, 7500.0, 9500.0);
            case BW_25_0:
                return new ChannelSpecification(25000.0, 25000, 12500.0, 14500.0);
            default:
                throw new IllegalArgumentException("Unrecognized AM bandwidth value: " + getBandwidth());
        }
    }

    /**
     * Sets squelch threshold
     * @param threshold (dB)
     */
    @JacksonXmlProperty(isAttribute =  true, localName = "squelch")
    @Override
    public void setSquelchThreshold(int threshold)
    {
        mSquelchThreshold = threshold;
    }

    /**
     * Squelch threshold
     * @return threshold (dB)
     */
    @Override
    public int getSquelchThreshold()
    {
        return mSquelchThreshold;
    }

    /**
     * Enable or disable the squelch noise floor auto-track feature.
     * @param autoTrack true to enable.
     */
    @JacksonXmlProperty(isAttribute =  true, localName = "autoTrack")
    @Override
    public void setSquelchAutoTrack(boolean autoTrack)
    {
        mSquelchAutoTrack = autoTrack;
    }

    /**
     * Indicates if the squelch noise floor auto-track feature is enabled.
     * @return true if enabled.
     */
    @Override
    public boolean isSquelchAutoTrack()
    {
        return mSquelchAutoTrack;
    }

    /**
     * Indicates if audio auto level is enabled so that weak and strong transmissions have the same loudness.
     * @return true if enabled (default).
     */
    @JacksonXmlProperty(isAttribute = true, localName = "audioAutoLevel")
    public boolean isAudioAutoLevel()
    {
        return mAudioAutoLevel;
    }

    /**
     * Enables or disables audio auto level.
     * @param autoLevel true to enable.
     */
    public void setAudioAutoLevel(boolean autoLevel)
    {
        mAudioAutoLevel = autoLevel;
    }

    /**
     * Linear output gain applied to the channel audio where 1.0 is unity.
     * @return output gain
     */
    @JacksonXmlProperty(isAttribute = true, localName = "audioOutputGain")
    public float getAudioOutputGain()
    {
        return mAudioOutputGain;
    }

    /**
     * Sets the linear output gain applied to the channel audio.  Out of range values are constrained.
     * @param gain where 1.0 is unity.
     */
    public void setAudioOutputGain(float gain)
    {
        mAudioOutputGain = AmAudioLeveler.constrain(gain);
    }
}
