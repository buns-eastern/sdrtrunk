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
import java.util.prefs.Preferences;

/**
 * User preferences for the application (software) heartbeat monitor.  A single configuration that reports the
 * application itself is alive by pushing a periodic heartbeat to an Uptime Kuma push URL and, optionally, to a
 * second fully user-supplied URL that is sent exactly as entered.
 */
public class SoftwareHeartbeatPreference extends Preference
{
    private static final String KEY_ENABLED = "softwareheartbeat.enabled";
    private static final String KEY_KUMA_URL = "softwareheartbeat.kumaUrl";
    private static final String KEY_SECOND_URL = "softwareheartbeat.secondUrl";
    private static final String KEY_INTERVAL = "softwareheartbeat.intervalSeconds";

    private final Preferences mPreferences = Preferences.userNodeForPackage(SoftwareHeartbeatPreference.class);
    private boolean mEnabled;
    private String mKumaUrl;
    private String mSecondUrl;
    private int mIntervalSeconds;

    /**
     * Constructs an instance.
     * @param updateListener notified whenever preferences change
     */
    public SoftwareHeartbeatPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mEnabled = mPreferences.getBoolean(KEY_ENABLED, false);
        mKumaUrl = mPreferences.get(KEY_KUMA_URL, "");
        mSecondUrl = mPreferences.get(KEY_SECOND_URL, "");
        mIntervalSeconds = mPreferences.getInt(KEY_INTERVAL, 60);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.SOURCE_SOFTWARE_HEARTBEAT;
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    public String getKumaUrl()
    {
        return mKumaUrl;
    }

    public String getSecondUrl()
    {
        return mSecondUrl;
    }

    public int getIntervalSeconds()
    {
        return mIntervalSeconds;
    }

    /**
     * Persists the supplied configuration and notifies listeners.
     */
    public void store(boolean enabled, String kumaUrl, String secondUrl, int intervalSeconds)
    {
        mEnabled = enabled;
        mKumaUrl = kumaUrl != null ? kumaUrl : "";
        mSecondUrl = secondUrl != null ? secondUrl : "";
        mIntervalSeconds = intervalSeconds;

        mPreferences.putBoolean(KEY_ENABLED, mEnabled);
        mPreferences.put(KEY_KUMA_URL, mKumaUrl);
        mPreferences.put(KEY_SECOND_URL, mSecondUrl);
        mPreferences.putInt(KEY_INTERVAL, mIntervalSeconds);

        notifyPreferenceUpdated();
    }
}
