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
package io.github.dsheirer.preference.network;

/**
 * Plain POJO representing a single streaming heartbeat monitor entry.  Each entry maps one audio streaming
 * configuration (by its playlist name) to an Uptime Kuma push URL, with independent up/down debounce timers.
 */
public class StreamHeartbeatEntry
{
    private boolean mEnabled = false;
    private String mStreamName = "";
    private String mKumaUrl = "";
    private int mIntervalSeconds = 30;
    private int mDownAfterSeconds = 120;
    private int mUpAfterSeconds = 0;

    /**
     * Default constructor.
     */
    public StreamHeartbeatEntry()
    {
    }

    /**
     * Copy constructor.
     * @param other entry to copy
     */
    public StreamHeartbeatEntry(StreamHeartbeatEntry other)
    {
        mEnabled = other.mEnabled;
        mStreamName = other.mStreamName;
        mKumaUrl = other.mKumaUrl;
        mIntervalSeconds = other.mIntervalSeconds;
        mDownAfterSeconds = other.mDownAfterSeconds;
        mUpAfterSeconds = other.mUpAfterSeconds;
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    public void setEnabled(boolean enabled)
    {
        mEnabled = enabled;
    }

    public String getStreamName()
    {
        return mStreamName;
    }

    public void setStreamName(String streamName)
    {
        mStreamName = streamName != null ? streamName : "";
    }

    public String getKumaUrl()
    {
        return mKumaUrl;
    }

    public void setKumaUrl(String kumaUrl)
    {
        mKumaUrl = kumaUrl != null ? kumaUrl : "";
    }

    public int getIntervalSeconds()
    {
        return mIntervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds)
    {
        mIntervalSeconds = intervalSeconds;
    }

    public int getDownAfterSeconds()
    {
        return mDownAfterSeconds;
    }

    public void setDownAfterSeconds(int downAfterSeconds)
    {
        mDownAfterSeconds = downAfterSeconds;
    }

    public int getUpAfterSeconds()
    {
        return mUpAfterSeconds;
    }

    public void setUpAfterSeconds(int upAfterSeconds)
    {
        mUpAfterSeconds = upAfterSeconds;
    }
}
