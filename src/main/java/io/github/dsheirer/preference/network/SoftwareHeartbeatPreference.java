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
 * second fully user-supplied URL that is sent exactly as entered.  Each target has its own push interval.
 */
public class SoftwareHeartbeatPreference extends Preference
{
    private static final String KEY_ENABLED = "softwareheartbeat.enabled";
    private static final String KEY_KUMA_URL = "softwareheartbeat.kumaUrl";
    private static final String KEY_SECOND_URL = "softwareheartbeat.secondUrl";
    private static final String KEY_KUMA_INTERVAL = "softwareheartbeat.kumaIntervalSeconds";
    private static final String KEY_SECOND_INTERVAL = "softwareheartbeat.secondIntervalSeconds";
    private static final String KEY_LEGACY_INTERVAL = "softwareheartbeat.intervalSeconds";
    private static final String KEY_USB_ENABLED = "softwareheartbeat.usbEnabled";
    private static final String KEY_USB_KUMA_URL = "softwareheartbeat.usbKumaUrl";
    private static final String KEY_USB_INTERVAL = "softwareheartbeat.usbIntervalSeconds";
    private static final String KEY_USB_WINDOW = "softwareheartbeat.usbWindowSeconds";

    private final Preferences mPreferences = Preferences.userNodeForPackage(SoftwareHeartbeatPreference.class);
    private boolean mEnabled;
    private String mKumaUrl;
    private String mSecondUrl;
    private int mKumaIntervalSeconds;
    private int mSecondIntervalSeconds;
    private boolean mUsbEnabled;
    private String mUsbKumaUrl;
    private int mUsbIntervalSeconds;
    private int mUsbWindowSeconds;

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

        //Migrate: if a single legacy interval was saved, use it as the default for both targets
        int legacy = mPreferences.getInt(KEY_LEGACY_INTERVAL, 60);
        mKumaIntervalSeconds = mPreferences.getInt(KEY_KUMA_INTERVAL, legacy);
        mSecondIntervalSeconds = mPreferences.getInt(KEY_SECOND_INTERVAL, legacy);

        mUsbEnabled = mPreferences.getBoolean(KEY_USB_ENABLED, false);
        mUsbKumaUrl = mPreferences.get(KEY_USB_KUMA_URL, "");
        mUsbIntervalSeconds = mPreferences.getInt(KEY_USB_INTERVAL, 60);
        mUsbWindowSeconds = mPreferences.getInt(KEY_USB_WINDOW, 60);
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

    public int getKumaIntervalSeconds()
    {
        return mKumaIntervalSeconds;
    }

    public int getSecondIntervalSeconds()
    {
        return mSecondIntervalSeconds;
    }

    /**
     * Persists the supplied configuration and notifies listeners.
     */
    public void store(boolean enabled, String kumaUrl, int kumaIntervalSeconds, String secondUrl,
                      int secondIntervalSeconds)
    {
        mEnabled = enabled;
        mKumaUrl = kumaUrl != null ? kumaUrl : "";
        mSecondUrl = secondUrl != null ? secondUrl : "";
        mKumaIntervalSeconds = kumaIntervalSeconds;
        mSecondIntervalSeconds = secondIntervalSeconds;

        mPreferences.putBoolean(KEY_ENABLED, mEnabled);
        mPreferences.put(KEY_KUMA_URL, mKumaUrl);
        mPreferences.put(KEY_SECOND_URL, mSecondUrl);
        mPreferences.putInt(KEY_KUMA_INTERVAL, mKumaIntervalSeconds);
        mPreferences.putInt(KEY_SECOND_INTERVAL, mSecondIntervalSeconds);

        notifyPreferenceUpdated();
    }

    public boolean isUsbEnabled()
    {
        return mUsbEnabled;
    }

    public String getUsbKumaUrl()
    {
        return mUsbKumaUrl;
    }

    public int getUsbIntervalSeconds()
    {
        return mUsbIntervalSeconds;
    }

    public int getUsbWindowSeconds()
    {
        return mUsbWindowSeconds;
    }

    /**
     * Persists the USB/tuner error monitor configuration and notifies listeners.
     */
    public void storeUsb(boolean usbEnabled, String usbKumaUrl, int usbIntervalSeconds, int usbWindowSeconds)
    {
        mUsbEnabled = usbEnabled;
        mUsbKumaUrl = usbKumaUrl != null ? usbKumaUrl : "";
        mUsbIntervalSeconds = usbIntervalSeconds;
        mUsbWindowSeconds = usbWindowSeconds;

        mPreferences.putBoolean(KEY_USB_ENABLED, mUsbEnabled);
        mPreferences.put(KEY_USB_KUMA_URL, mUsbKumaUrl);
        mPreferences.putInt(KEY_USB_INTERVAL, mUsbIntervalSeconds);
        mPreferences.putInt(KEY_USB_WINDOW, mUsbWindowSeconds);

        notifyPreferenceUpdated();
    }
}
