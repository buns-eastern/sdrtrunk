/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.dmr.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the sync detection mode for a set of DMR sync detectors.  The mode defaults to AUTOMATIC (all sync pattern
 * groups) and is only narrowed when explicitly set, such as for DMR traffic channels (BASE_ONLY).
 */
public class DMRSyncModeMonitor
{
    private final List<DMRSyncDetector> mSyncDetectors = new ArrayList<>();
    private int mBaseCount;
    private int mMobileCount;
    private int mDirectCount;
    private DMRSyncDetectMode mLockedMode = DMRSyncDetectMode.AUTOMATIC;
    private DMRSyncDetectMode mMode = DMRSyncDetectMode.AUTOMATIC;

    /**
     * Constructs an instance
     */
    public DMRSyncModeMonitor()
    {
    }

    /**
     * Sets (and locks) the sync detection mode.  This can be directly invoked when the operating mode is known
     * in advance, such as for a repeated traffic channel where the expected mode is BASE_ONLY.  Setting AUTOMATIC
     * unlocks the mode so that this monitor detects the dominant mode from the sync detections.
     * @param mode to set
     */
    public void setMode(DMRSyncDetectMode mode)
    {
        mLockedMode = mode;
        clearCounts();
        applyMode(mode);
    }

    /**
     * Current sync detection mode.
     */
    public DMRSyncDetectMode getMode()
    {
        return mMode;
    }

    /**
     * Restores AUTOMATIC mode and clears the sync detection counts so that the dominant mode is re-established from
     * subsequent sync detections.  Has no effect when the mode is locked via setMode().
     */
    public void reset()
    {
        if(mLockedMode == DMRSyncDetectMode.AUTOMATIC)
        {
            clearCounts();

            if(mMode != DMRSyncDetectMode.AUTOMATIC)
            {
                applyMode(DMRSyncDetectMode.AUTOMATIC);
            }
        }
    }

    /**
     * Indicates a sync pattern is positively detected.
     *
     * The sync detection mode is not automatically narrowed to a single (base, mobile or direct) sync pattern group.
     * On conventional channels, false sync detections from noise during idle periods can outnumber the sync patterns
     * used by the radios on the channel.  Narrowing the mode would then stop detection of the radios' sync patterns.
     * Channels where the mode is known in advance (ie DMR traffic channels) set the mode explicitly via setMode().
     * @param pattern that was detected.
     */
    public void detected(DMRSyncPattern pattern)
    {
        //No automatic narrowing - see above.
    }

    private void clearCounts()
    {
        mBaseCount = 0;
        mMobileCount = 0;
        mDirectCount = 0;
    }

    private void applyMode(DMRSyncDetectMode mode)
    {
        mMode = mode;

        for(DMRSyncDetector detector : mSyncDetectors)
        {
            detector.setMode(mode);
        }
    }

    /**
     * Adds a sync detector to be managed by this instance.
     * @param syncDetector to add
     */
    public void add(DMRSyncDetector syncDetector)
    {
        mSyncDetectors.add(syncDetector);
    }
}
