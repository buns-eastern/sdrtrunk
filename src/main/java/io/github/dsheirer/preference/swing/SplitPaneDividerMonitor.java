/*
 * ******************************************************************************
 * sdrtrunk
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
 * *****************************************************************************
 */

package io.github.dsheirer.preference.swing;

import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.preference.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists and restores the divider locations of a {@link JideSplitPane} to/from user preferences so that
 * the sizing of stacked panels is remembered across application restarts.  Note: JIDE only returns/accepts
 * valid divider locations while the split pane is displayed on screen, so store at shutdown and restore after
 * the frame is visible.
 */
public class SplitPaneDividerMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(SplitPaneDividerMonitor.class);

    private SplitPaneDividerMonitor()
    {
    }

    /**
     * Stores the current divider locations for the split pane, keyed by the supplied preference key.
     * Does nothing if any location is invalid (e.g. the pane is not currently displayed).
     */
    public static void store(UserPreferences preferences, JideSplitPane splitPane, String key)
    {
        try
        {
            int[] locations = splitPane.getDividerLocations();

            if(locations == null || locations.length == 0)
            {
                return;
            }

            for(int location : locations)
            {
                if(location < 0)
                {
                    return;
                }
            }

            preferences.getSwingPreference().setInt(key + ".divider.count", locations.length);

            for(int i = 0; i < locations.length; i++)
            {
                preferences.getSwingPreference().setInt(key + ".divider." + i, locations[i]);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error storing split pane divider locations for [" + key + "]", e);
        }
    }

    /**
     * Restores previously stored divider locations for the split pane.  Skips restoration (leaving the default
     * layout) if nothing is stored or the number of dividers no longer matches (e.g. panels were shown/hidden).
     */
    public static void restore(UserPreferences preferences, JideSplitPane splitPane, String key)
    {
        try
        {
            int[] current = splitPane.getDividerLocations();
            int currentCount = (current == null) ? 0 : current.length;
            int storedCount = preferences.getSwingPreference().getInt(key + ".divider.count", Integer.MAX_VALUE);

            if(storedCount == Integer.MAX_VALUE || storedCount != currentCount)
            {
                return;
            }

            for(int i = 0; i < storedCount; i++)
            {
                int location = preferences.getSwingPreference().getInt(key + ".divider." + i, Integer.MAX_VALUE);

                if(location != Integer.MAX_VALUE && location >= 0)
                {
                    splitPane.setDividerLocation(i, location);
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("Error restoring split pane divider locations for [" + key + "]", e);
        }
    }
}
