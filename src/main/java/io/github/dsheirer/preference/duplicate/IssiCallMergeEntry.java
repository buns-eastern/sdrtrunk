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
package io.github.dsheirer.preference.duplicate;

/**
 * One ISSI Call Merge redirect: a source (system, talkgroup) that should be treated, for de-duplication and
 * streaming, as a primary (system, talkgroup).  When a call is decoded on the source system/talkgroup, it is
 * re-pointed to the primary identity so the existing per-system duplicate-call rules collapse it with the
 * primary's copy and it streams using the primary's stream configuration.  The two talkgroup values may be the
 * same or different - both are entered explicitly.
 */
public class IssiCallMergeEntry
{
    private String mSourceSystem = "";
    private int mSourceTalkgroup;
    private String mPrimarySystem = "";
    private int mPrimaryTalkgroup;

    public IssiCallMergeEntry()
    {
    }

    public IssiCallMergeEntry(String sourceSystem, int sourceTalkgroup, String primarySystem, int primaryTalkgroup)
    {
        mSourceSystem = sourceSystem != null ? sourceSystem : "";
        mSourceTalkgroup = sourceTalkgroup;
        mPrimarySystem = primarySystem != null ? primarySystem : "";
        mPrimaryTalkgroup = primaryTalkgroup;
    }

    public IssiCallMergeEntry(IssiCallMergeEntry other)
    {
        mSourceSystem = other.mSourceSystem;
        mSourceTalkgroup = other.mSourceTalkgroup;
        mPrimarySystem = other.mPrimarySystem;
        mPrimaryTalkgroup = other.mPrimaryTalkgroup;
    }

    public String getSourceSystem() { return mSourceSystem; }
    public void setSourceSystem(String v) { mSourceSystem = v != null ? v : ""; }

    public int getSourceTalkgroup() { return mSourceTalkgroup; }
    public void setSourceTalkgroup(int v) { mSourceTalkgroup = v; }

    public String getPrimarySystem() { return mPrimarySystem; }
    public void setPrimarySystem(String v) { mPrimarySystem = v != null ? v : ""; }

    public int getPrimaryTalkgroup() { return mPrimaryTalkgroup; }
    public void setPrimaryTalkgroup(int v) { mPrimaryTalkgroup = v; }

    /**
     * Indicates if this entry matches the supplied decoded (system, talkgroup).
     */
    public boolean matchesSource(String system, int talkgroup)
    {
        return talkgroup == mSourceTalkgroup && system != null && system.equalsIgnoreCase(mSourceSystem);
    }
}
