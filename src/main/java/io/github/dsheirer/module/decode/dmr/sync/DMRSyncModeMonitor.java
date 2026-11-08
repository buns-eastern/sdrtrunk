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
    private DMRSyncDetectMode mMode = DMRSyncDetectMode.AUTOMATIC;

    /**
     * Constructs an instance
     */
    public DMRSyncModeMonitor()
    {
    }

    /**
     * Sets (and locks) the sync detection mode.  This can be directly invoked when the operating mode is known
     * in advance, such as for a repeated traffic channel where the expected mode is BASE_ONLY.  Otherwise the mode
     * remains AUTOMATIC so that all sync pattern groups are detected.
     * @param mode to set
     */
    public void setMode(DMRSyncDetectMode mode)
    {
        mMode = mode;

        for(DMRSyncDetector detector : mSyncDetectors)
        {
            detector.setMode(mode);
        }
    }

    /**
     * Indicates a sync pattern is positively detected.
     *
     * The sync detection mode is not automatically narrowed to a single (base, mobile or direct) sync pattern group.
     * On conventional channels, false sync detections from noise during idle periods can outnumber the sync patterns
     * used by the radios on the channel.  Narrowing the mode would then permanently stop detection of the radios'
     * sync patterns until the channel is restarted.  Channels where the mode is known in advance (ie DMR traffic
     * channels) set the mode explicitly via setMode().
     *
     * @param pattern that was detected.
     */
    public void detected(DMRSyncPattern pattern)
    {
        //No automatic mode changes - see method comment.
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
