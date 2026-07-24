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
 * One per-channel liveness monitor for the Standalone Channel Heartbeat feature.  While the named channel is
 * running (its processing chain is started), SDRTrunk pings the configured URL on the configured interval.  If
 * the channel stops -- or SDRTrunk stops -- the pings stop, so a push-style uptime monitor watching that URL
 * flips to down.  Unlike the activity-based Channel Heartbeat, this fires continuously while the channel runs,
 * regardless of whether any traffic is being received, so a quiet channel does not look "down".
 */
public class KumaChannelMonitorEntry
{
    private String mChannelName = "";
    private String mSystem = "";
    private String mUrl = "";
    private int mIntervalSeconds = 60;

    public KumaChannelMonitorEntry()
    {
    }

    public KumaChannelMonitorEntry(String channelName, String system, String url, int intervalSeconds)
    {
        mChannelName = channelName != null ? channelName : "";
        mSystem = system != null ? system : "";
        mUrl = url != null ? url : "";
        mIntervalSeconds = intervalSeconds;
    }

    public KumaChannelMonitorEntry(KumaChannelMonitorEntry other)
    {
        mChannelName = other.mChannelName;
        mSystem = other.mSystem;
        mUrl = other.mUrl;
        mIntervalSeconds = other.mIntervalSeconds;
    }

    public String getChannelName()
    {
        return mChannelName;
    }

    public void setChannelName(String channelName)
    {
        mChannelName = channelName != null ? channelName : "";
    }

    /**
     * Optional system, used only to disambiguate when the same channel name exists under more than one system.
     * Blank matches the channel name on any system.
     */
    public String getSystem()
    {
        return mSystem;
    }

    public void setSystem(String system)
    {
        mSystem = system != null ? system : "";
    }

    /** The URL to ping while the channel runs.  Sent verbatim (special characters percent-encoded). */
    public String getUrl()
    {
        return mUrl;
    }

    public void setUrl(String url)
    {
        mUrl = url != null ? url : "";
    }

    /** Seconds between successive pings while the channel is running. */
    public int getIntervalSeconds()
    {
        return mIntervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds)
    {
        mIntervalSeconds = intervalSeconds;
    }
}
