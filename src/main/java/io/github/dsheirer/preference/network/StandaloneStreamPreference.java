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
 * User preferences for the Standalone Channel Heartbeat output feature.
 *
 * When enabled, SDRTrunk opens a TCP server on the configured port (default 9504) and emits liveness
 * heartbeats for every running standalone (non-trunking) channel — NBFM, AM, conventional DMR, etc. — as
 * newline-delimited JSON.  Each running channel announces a channel_up when it starts, a heartbeat every
 * interval while it runs, and a channel_down when it stops, so a downstream consumer can verify which
 * conventional channels are alive.  Trunking channels are intentionally excluded — their control traffic
 * already advertises liveness on the raw/event streams.
 */
public class StandaloneStreamPreference extends Preference
{
    private static final String KEY_ENABLED  = "standalone.stream.enabled";
    private static final String KEY_PORT     = "standalone.stream.port";
    private static final String KEY_INTERVAL = "standalone.stream.interval.seconds";
    private static final String KEY_KUMA_COUNT = "standalone.stream.kuma.count";
    private static final String KEY_KUMA_PREFIX = "standalone.stream.kuma.";

    private static final boolean DEFAULT_ENABLED  = true;
    private static final int     DEFAULT_PORT     = 9504;
    private static final int     DEFAULT_INTERVAL = 30;

    private final Preferences mPreferences = Preferences.userNodeForPackage(StandaloneStreamPreference.class);
    private List<KumaChannelMonitorEntry> mKumaMonitors;

    /**
     * Constructs an instance.
     * @param updateListener notified whenever preferences change
     */
    public StandaloneStreamPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
        mKumaMonitors = loadKumaMonitors();
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.SOURCE_STANDALONE_STREAM;
    }

    /** Returns true if the standalone channel heartbeat server should be started at launch. */
    public boolean isEnabled()
    {
        return mPreferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled)
    {
        mPreferences.putBoolean(KEY_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    /** TCP port the standalone channel heartbeat server listens on (default 9504). */
    public int getPort()
    {
        return mPreferences.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port)
    {
        mPreferences.putInt(KEY_PORT, port);
        notifyPreferenceUpdated();
    }

    /** Seconds between successive heartbeats for each running standalone channel (default 30). */
    public int getIntervalSeconds()
    {
        return mPreferences.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    public void setIntervalSeconds(int intervalSeconds)
    {
        mPreferences.putInt(KEY_INTERVAL, intervalSeconds);
        notifyPreferenceUpdated();
    }

    /**
     * Per-channel liveness monitors: while a named channel is running, SDRTrunk pings that entry's URL on its
     * interval, so a push-style uptime monitor can watch specific conventional channels.  Independent of the
     * TCP heartbeat server above -- these fire whether or not the 9504 stream is enabled.  Returns a defensive
     * copy.
     */
    public List<KumaChannelMonitorEntry> getKumaMonitors()
    {
        List<KumaChannelMonitorEntry> copy = new ArrayList<>();

        for(KumaChannelMonitorEntry entry: mKumaMonitors)
        {
            copy.add(new KumaChannelMonitorEntry(entry));
        }

        return copy;
    }

    /**
     * Persists the per-channel monitors and notifies listeners.
     */
    public void storeKumaMonitors(List<KumaChannelMonitorEntry> monitors)
    {
        mKumaMonitors = new ArrayList<>();

        if(monitors != null)
        {
            for(KumaChannelMonitorEntry entry: monitors)
            {
                mKumaMonitors.add(new KumaChannelMonitorEntry(entry));
            }
        }

        persistKumaMonitors();
        notifyPreferenceUpdated();
    }

    private List<KumaChannelMonitorEntry> loadKumaMonitors()
    {
        List<KumaChannelMonitorEntry> monitors = new ArrayList<>();
        int count = mPreferences.getInt(KEY_KUMA_COUNT, 0);

        for(int i = 0; i < count; i++)
        {
            KumaChannelMonitorEntry entry = new KumaChannelMonitorEntry();
            entry.setChannelName(mPreferences.get(KEY_KUMA_PREFIX + i + ".channel", ""));
            entry.setSystem(mPreferences.get(KEY_KUMA_PREFIX + i + ".system", ""));
            entry.setUrl(mPreferences.get(KEY_KUMA_PREFIX + i + ".url", ""));
            entry.setIntervalSeconds(mPreferences.getInt(KEY_KUMA_PREFIX + i + ".interval", 60));
            monitors.add(entry);
        }

        return monitors;
    }

    private void persistKumaMonitors()
    {
        int oldCount = mPreferences.getInt(KEY_KUMA_COUNT, 0);
        mPreferences.putInt(KEY_KUMA_COUNT, mKumaMonitors.size());

        for(int i = 0; i < mKumaMonitors.size(); i++)
        {
            KumaChannelMonitorEntry entry = mKumaMonitors.get(i);
            mPreferences.put(KEY_KUMA_PREFIX + i + ".channel", entry.getChannelName());
            mPreferences.put(KEY_KUMA_PREFIX + i + ".system", entry.getSystem());
            mPreferences.put(KEY_KUMA_PREFIX + i + ".url", entry.getUrl());
            mPreferences.putInt(KEY_KUMA_PREFIX + i + ".interval", entry.getIntervalSeconds());
        }

        for(int i = mKumaMonitors.size(); i < oldCount; i++)
        {
            mPreferences.remove(KEY_KUMA_PREFIX + i + ".channel");
            mPreferences.remove(KEY_KUMA_PREFIX + i + ".system");
            mPreferences.remove(KEY_KUMA_PREFIX + i + ".url");
            mPreferences.remove(KEY_KUMA_PREFIX + i + ".interval");
        }
    }
}
