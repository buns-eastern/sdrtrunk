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
 * One watched talkgroup for the channel heartbeat feature.  The talkgroup value is what gets substituted into
 * the heartbeat URL's {channel} placeholder; the label is a friendly name for display only.
 */
public class ChannelHeartbeatEntry
{
    private int mTalkgroup;
    private String mLabel = "";
    private String mSystem = "";

    public ChannelHeartbeatEntry()
    {
    }

    public ChannelHeartbeatEntry(int talkgroup, String label, String system)
    {
        mTalkgroup = talkgroup;
        mLabel = label != null ? label : "";
        mSystem = system != null ? system : "";
    }

    public ChannelHeartbeatEntry(ChannelHeartbeatEntry other)
    {
        mTalkgroup = other.mTalkgroup;
        mLabel = other.mLabel;
        mSystem = other.mSystem;
    }

    public int getTalkgroup()
    {
        return mTalkgroup;
    }

    public void setTalkgroup(int talkgroup)
    {
        mTalkgroup = talkgroup;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public void setLabel(String label)
    {
        mLabel = label != null ? label : "";
    }

    /**
     * Optional system filter.  Blank means match this talkgroup on any system.
     */
    public String getSystem()
    {
        return mSystem;
    }

    public void setSystem(String system)
    {
        mSystem = system != null ? system : "";
    }
}
