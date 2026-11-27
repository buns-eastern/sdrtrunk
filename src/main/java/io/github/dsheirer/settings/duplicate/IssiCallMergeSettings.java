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
package io.github.dsheirer.settings.duplicate;

import io.github.dsheirer.settings.AbstractSettings;
import io.github.dsheirer.settings.SettingsType;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * User preferences for the ISSI Call Merge feature: redirect a call decoded on a source (system, talkgroup) so
 * that de-duplication and streaming treat it as a primary (system, talkgroup).  This lets a talkgroup that rides
 * on two independent systems (for example via P25 ISSI) stream a single copy under a chosen primary identity,
 * exactly as two channels sharing one system name already do.  Entirely inert when no entries are configured.
 */
public class IssiCallMergeSettings extends AbstractSettings
{
    //Preferences node used by earlier versions (io.github.dsheirer.preference.duplicate package), kept so stored values
    //are still found and an earlier version still finds values stored by this one.  Don't change without migrating.
    private static final String NODE_PATH = "/io/github/dsheirer/preference/duplicate";
    private static final String KEY_ENABLED = "issicallmerge.enabled";
    private static final String KEY_COUNT = "issicallmerge.count";
    private static final String KEY_PREFIX = "issicallmerge.";

    private final Preferences mPreferences = Preferences.userRoot().node(NODE_PATH);
    private boolean mEnabled;
    private List<IssiCallMergeEntry> mEntries;

    public IssiCallMergeSettings(Listener<SettingsType> updateListener)
    {
        super(updateListener);
        mEnabled = mPreferences.getBoolean(KEY_ENABLED, true);
        mEntries = load();
    }

    @Override
    public SettingsType getSettingsType()
    {
        return SettingsType.AUDIO_ISSI_CALL_MERGE;
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    /**
     * True when the feature is enabled and at least one redirect is configured.  Runtime code checks this first
     * so that, with nothing configured, the audio and streaming path is completely unaffected.
     */
    public boolean isActive()
    {
        return mEnabled && !mEntries.isEmpty();
    }

    /**
     * Returns the redirect entries (defensive copy).
     */
    public List<IssiCallMergeEntry> getEntries()
    {
        List<IssiCallMergeEntry> copy = new ArrayList<>();

        for(IssiCallMergeEntry entry: mEntries)
        {
            copy.add(new IssiCallMergeEntry(entry));
        }

        return copy;
    }

    /**
     * Finds the redirect for a decoded (system, talkgroup), or null when none applies.
     */
    public IssiCallMergeEntry findRedirect(String system, int talkgroup)
    {
        if(!isActive() || system == null)
        {
            return null;
        }

        for(IssiCallMergeEntry entry: mEntries)
        {
            if(entry.matchesSource(system, talkgroup))
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * Persists the full configuration and notifies listeners.
     */
    public void store(boolean enabled, List<IssiCallMergeEntry> entries)
    {
        mEnabled = enabled;
        mEntries = new ArrayList<>();

        if(entries != null)
        {
            for(IssiCallMergeEntry entry: entries)
            {
                mEntries.add(new IssiCallMergeEntry(entry));
            }
        }

        mPreferences.putBoolean(KEY_ENABLED, mEnabled);
        persistEntries();
        notifySettingsUpdated();
    }

    private List<IssiCallMergeEntry> load()
    {
        List<IssiCallMergeEntry> entries = new ArrayList<>();
        int count = mPreferences.getInt(KEY_COUNT, 0);

        for(int i = 0; i < count; i++)
        {
            IssiCallMergeEntry entry = new IssiCallMergeEntry();
            entry.setSourceSystem(mPreferences.get(KEY_PREFIX + i + ".sourceSystem", ""));
            entry.setSourceTalkgroup(mPreferences.getInt(KEY_PREFIX + i + ".sourceTalkgroup", 0));
            entry.setPrimarySystem(mPreferences.get(KEY_PREFIX + i + ".primarySystem", ""));
            entry.setPrimaryTalkgroup(mPreferences.getInt(KEY_PREFIX + i + ".primaryTalkgroup", 0));
            entries.add(entry);
        }

        return entries;
    }

    private void persistEntries()
    {
        int oldCount = mPreferences.getInt(KEY_COUNT, 0);
        mPreferences.putInt(KEY_COUNT, mEntries.size());

        for(int i = 0; i < mEntries.size(); i++)
        {
            IssiCallMergeEntry entry = mEntries.get(i);
            mPreferences.put(KEY_PREFIX + i + ".sourceSystem", entry.getSourceSystem());
            mPreferences.putInt(KEY_PREFIX + i + ".sourceTalkgroup", entry.getSourceTalkgroup());
            mPreferences.put(KEY_PREFIX + i + ".primarySystem", entry.getPrimarySystem());
            mPreferences.putInt(KEY_PREFIX + i + ".primaryTalkgroup", entry.getPrimaryTalkgroup());
        }

        for(int i = mEntries.size(); i < oldCount; i++)
        {
            mPreferences.remove(KEY_PREFIX + i + ".sourceSystem");
            mPreferences.remove(KEY_PREFIX + i + ".sourceTalkgroup");
            mPreferences.remove(KEY_PREFIX + i + ".primarySystem");
            mPreferences.remove(KEY_PREFIX + i + ".primaryTalkgroup");
        }
    }
}
