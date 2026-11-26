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


package io.github.dsheirer.module.decode.dmr.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the DMR sync mode monitor.
 */
public class DMRSyncModeMonitorTest
{
    private static DMRSyncModeMonitor monitor(DMRSyncDetector detector)
    {
        DMRSyncModeMonitor monitor = new DMRSyncModeMonitor();
        monitor.add(detector);
        return monitor;
    }

    private static void detect(DMRSyncModeMonitor monitor, DMRSyncPattern pattern, int count)
    {
        for(int x = 0; x < count; x++)
        {
            monitor.detected(pattern);
        }
    }

    @Test
    void detectionsDoNotNarrowTheMode()
    {
        DMRSyncDetector detector = new DMRSyncDetector() {};
        DMRSyncModeMonitor monitor = monitor(detector);
        detect(monitor, DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, 100);
        detect(monitor, DMRSyncPattern.BASE_STATION_DATA, 100);
        detect(monitor, DMRSyncPattern.MOBILE_STATION_VOICE, 100);
        assertEquals(DMRSyncDetectMode.AUTOMATIC, monitor.getMode());
        assertEquals(DMRSyncDetectMode.AUTOMATIC, detector.getMode());
    }

    @Test
    void resetKeepsAutomatic()
    {
        DMRSyncDetector detector = new DMRSyncDetector() {};
        DMRSyncModeMonitor monitor = monitor(detector);
        detect(monitor, DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, 11);
        monitor.reset();
        assertEquals(DMRSyncDetectMode.AUTOMATIC, monitor.getMode());
        assertEquals(DMRSyncDetectMode.AUTOMATIC, detector.getMode());
    }

    @Test
    void lockedModeIgnoresDetectionsAndReset()
    {
        DMRSyncDetector detector = new DMRSyncDetector() {};
        DMRSyncModeMonitor monitor = monitor(detector);
        monitor.setMode(DMRSyncDetectMode.BASE_ONLY);
        detect(monitor, DMRSyncPattern.DIRECT_DATA_TIMESLOT_1, 20);
        monitor.reset();
        assertEquals(DMRSyncDetectMode.BASE_ONLY, monitor.getMode());
        assertEquals(DMRSyncDetectMode.BASE_ONLY, detector.getMode());
    }
}
