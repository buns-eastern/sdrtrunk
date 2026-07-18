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

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * User preferences for streaming heartbeat monitor entries.  Entries are keyed by stream name so that the
 * editor can auto-populate rows from the active playlist while preserving each stream's saved settings.
 */
public class StreamHeartbeatPreference extends Preference
{
    private static final String KEY_COUNT = "streamheartbeat.count";
    private static final String KEY_PREFIX = "streamheartbeat.";

    private final Preferences mPreferences = Preferences.userNodeForPackage(StreamHeartbeatPreference.class);
    private List<StreamHeartbeatEntry> mEntries;

    /**
     * Constructs an instance.
     * @param updateListener notified whenever preferences change
     */
    public StreamHeartbeatPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mEntries = load();
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.SOURCE_STREAM_HEARTBEAT;
    }

    /**
     * Returns the current list of entries (defensive copy).
     */
    public List<StreamHeartbeatEntry> getEntries()
    {
        List<StreamHeartbeatEntry> copy = new ArrayList<>();

        for(StreamHeartbeatEntry entry: mEntries)
        {
            copy.add(new StreamHeartbeatEntry(entry));
        }

        return copy;
    }

    /**
     * Returns the saved entry for the given stream name, or null if none exists.
     */
    public StreamHeartbeatEntry getEntry(String streamName)
    {
        if(streamName != null)
        {
            for(StreamHeartbeatEntry entry: mEntries)
            {
                if(streamName.equals(entry.getStreamName()))
                {
                    return new StreamHeartbeatEntry(entry);
                }
            }
        }

        return null;
    }

    /**
     * Replaces all entries with the supplied list and persists.
     */
    public void setEntries(List<StreamHeartbeatEntry> entries)
    {
        List<StreamHeartbeatEntry> replacement = new ArrayList<>();

        if(entries != null)
        {
            for(StreamHeartbeatEntry entry: entries)
            {
                replacement.add(new StreamHeartbeatEntry(entry));
            }
        }

        mEntries = replacement;
        persist();
        notifyPreferenceUpdated();
    }

    private List<StreamHeartbeatEntry> load()
    {
        List<StreamHeartbeatEntry> entries = new ArrayList<>();
        int count = mPreferences.getInt(KEY_COUNT, 0);

        for(int i = 0; i < count; i++)
        {
            StreamHeartbeatEntry entry = new StreamHeartbeatEntry();
            entry.setEnabled(mPreferences.getBoolean(KEY_PREFIX + i + ".enabled", false));
            entry.setStreamName(mPreferences.get(KEY_PREFIX + i + ".streamName", ""));
            entry.setKumaUrl(mPreferences.get(KEY_PREFIX + i + ".kumaUrl", ""));
            entry.setIntervalSeconds(mPreferences.getInt(KEY_PREFIX + i + ".intervalSeconds", 30));
            entry.setDownAfterSeconds(mPreferences.getInt(KEY_PREFIX + i + ".downAfterSeconds", 120));
            entry.setUpAfterSeconds(mPreferences.getInt(KEY_PREFIX + i + ".upAfterSeconds", 0));
            entries.add(entry);
        }

        return entries;
    }

    private void persist()
    {
        int oldCount = mPreferences.getInt(KEY_COUNT, 0);
        mPreferences.putInt(KEY_COUNT, mEntries.size());

        for(int i = 0; i < mEntries.size(); i++)
        {
            StreamHeartbeatEntry entry = mEntries.get(i);
            mPreferences.putBoolean(KEY_PREFIX + i + ".enabled", entry.isEnabled());
            mPreferences.put(KEY_PREFIX + i + ".streamName", entry.getStreamName());
            mPreferences.put(KEY_PREFIX + i + ".kumaUrl", entry.getKumaUrl());
            mPreferences.putInt(KEY_PREFIX + i + ".intervalSeconds", entry.getIntervalSeconds());
            mPreferences.putInt(KEY_PREFIX + i + ".downAfterSeconds", entry.getDownAfterSeconds());
            mPreferences.putInt(KEY_PREFIX + i + ".upAfterSeconds", entry.getUpAfterSeconds());
        }

        //Clean up stale keys beyond current list size
        for(int i = mEntries.size(); i < oldCount; i++)
        {
            mPreferences.remove(KEY_PREFIX + i + ".enabled");
            mPreferences.remove(KEY_PREFIX + i + ".streamName");
            mPreferences.remove(KEY_PREFIX + i + ".kumaUrl");
            mPreferences.remove(KEY_PREFIX + i + ".intervalSeconds");
            mPreferences.remove(KEY_PREFIX + i + ".downAfterSeconds");
            mPreferences.remove(KEY_PREFIX + i + ".upAfterSeconds");
        }
    }
}
