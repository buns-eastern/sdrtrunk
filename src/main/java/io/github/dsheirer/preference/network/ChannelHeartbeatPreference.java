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
 * User preferences for the channel heartbeat feature: fire a heartbeat to an external URL whenever one of the
 * chosen talkgroups (trunking or conventional) becomes active.
 */
public class ChannelHeartbeatPreference extends Preference
{
    private static final String KEY_ENABLED = "channelheartbeat.enabled";
    private static final String KEY_URL = "channelheartbeat.urlTemplate";
    private static final String KEY_DEBOUNCE = "channelheartbeat.debounceSeconds";
    private static final String KEY_COUNT = "channelheartbeat.count";
    private static final String KEY_PREFIX = "channelheartbeat.";

    private final Preferences mPreferences = Preferences.userNodeForPackage(ChannelHeartbeatPreference.class);
    private boolean mEnabled;
    private String mUrlTemplate;
    private int mDebounceSeconds;
    private List<ChannelHeartbeatEntry> mEntries;

    /**
     * Constructs an instance.
     * @param updateListener notified whenever preferences change
     */
    public ChannelHeartbeatPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mEnabled = mPreferences.getBoolean(KEY_ENABLED, false);
        mUrlTemplate = mPreferences.get(KEY_URL, "");
        mDebounceSeconds = mPreferences.getInt(KEY_DEBOUNCE, 10);
        mEntries = load();
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.SOURCE_CHANNEL_HEARTBEAT;
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    public String getUrlTemplate()
    {
        return mUrlTemplate;
    }

    public int getDebounceSeconds()
    {
        return mDebounceSeconds;
    }

    /**
     * Returns the watched talkgroup entries (defensive copy).
     */
    public List<ChannelHeartbeatEntry> getEntries()
    {
        List<ChannelHeartbeatEntry> copy = new ArrayList<>();

        for(ChannelHeartbeatEntry entry: mEntries)
        {
            copy.add(new ChannelHeartbeatEntry(entry));
        }

        return copy;
    }

    /**
     * Persists the full configuration and notifies listeners.
     */
    public void store(boolean enabled, String urlTemplate, int debounceSeconds, List<ChannelHeartbeatEntry> entries)
    {
        mEnabled = enabled;
        mUrlTemplate = urlTemplate != null ? urlTemplate : "";
        mDebounceSeconds = debounceSeconds;
        mEntries = new ArrayList<>();

        if(entries != null)
        {
            for(ChannelHeartbeatEntry entry: entries)
            {
                mEntries.add(new ChannelHeartbeatEntry(entry));
            }
        }

        mPreferences.putBoolean(KEY_ENABLED, mEnabled);
        mPreferences.put(KEY_URL, mUrlTemplate);
        mPreferences.putInt(KEY_DEBOUNCE, mDebounceSeconds);
        persistEntries();
        notifyPreferenceUpdated();
    }

    private List<ChannelHeartbeatEntry> load()
    {
        List<ChannelHeartbeatEntry> entries = new ArrayList<>();
        int count = mPreferences.getInt(KEY_COUNT, 0);

        for(int i = 0; i < count; i++)
        {
            ChannelHeartbeatEntry entry = new ChannelHeartbeatEntry();
            entry.setTalkgroup(mPreferences.getInt(KEY_PREFIX + i + ".talkgroup", 0));
            entry.setLabel(mPreferences.get(KEY_PREFIX + i + ".label", ""));
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
            ChannelHeartbeatEntry entry = mEntries.get(i);
            mPreferences.putInt(KEY_PREFIX + i + ".talkgroup", entry.getTalkgroup());
            mPreferences.put(KEY_PREFIX + i + ".label", entry.getLabel());
        }

        for(int i = mEntries.size(); i < oldCount; i++)
        {
            mPreferences.remove(KEY_PREFIX + i + ".talkgroup");
            mPreferences.remove(KEY_PREFIX + i + ".label");
        }
    }
}
