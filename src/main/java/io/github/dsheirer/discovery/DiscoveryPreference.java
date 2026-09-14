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

package io.github.dsheirer.discovery;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Persisted user preferences for signal discovery: global options plus per-tuner detection settings.
 */
public class DiscoveryPreference extends Preference
{
    private final Preferences mPreferences = Preferences.userNodeForPackage(DiscoveryPreference.class);
    private static final String KEY_AUTO_ANALYZE = "discovery.auto.analyze";
    private static final String KEY_INCLUDE_P25 = "discovery.include.p25";
    private static final String KEY_RETENTION_DAYS = "discovery.retention.days";
    private static final String KEY_IGNORED = "discovery.ignored.frequencies";
    private static final String KEY_ENABLED_PREFIX = "discovery.enabled.";
    private static final String KEY_SNR_PREFIX = "discovery.snr.";
    private static final String KEY_LEVEL_PREFIX = "discovery.level.";
    private static final String KEY_DWELL_PREFIX = "discovery.dwell.";
    private static final String KEY_CLIP_PREFIX = "discovery.clip.";
    private static final String KEY_COOLDOWN_PREFIX = "discovery.cooldown.";
    private static final String KEY_CAPTURES_PREFIX = "discovery.captures.";
    private static final String KEY_DC_PREFIX = "discovery.ignoredc.";

    public DiscoveryPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.DISCOVERY;
    }

    public boolean isAutoAnalyze()
    {
        return mPreferences.getBoolean(KEY_AUTO_ANALYZE, true);
    }

    public void setAutoAnalyze(boolean value)
    {
        mPreferences.putBoolean(KEY_AUTO_ANALYZE, value);
        notifyPreferenceUpdated();
    }

    public boolean isIncludeP25()
    {
        return mPreferences.getBoolean(KEY_INCLUDE_P25, false);
    }

    public void setIncludeP25(boolean value)
    {
        mPreferences.putBoolean(KEY_INCLUDE_P25, value);
        notifyPreferenceUpdated();
    }

    public int getRetentionDays()
    {
        return mPreferences.getInt(KEY_RETENTION_DAYS, 7);
    }

    public void setRetentionDays(int value)
    {
        mPreferences.putInt(KEY_RETENTION_DAYS, Math.max(0, value));
        notifyPreferenceUpdated();
    }

    public Set<Long> getIgnoredFrequencies()
    {
        Set<Long> frequencies = new HashSet<>();
        String raw = mPreferences.get(KEY_IGNORED, "");

        for(String token : raw.split(","))
        {
            token = token.trim();

            if(!token.isEmpty())
            {
                try
                {
                    frequencies.add(Long.parseLong(token));
                }
                catch(NumberFormatException e)
                {
                    //skip
                }
            }
        }

        return frequencies;
    }

    public void setIgnoredFrequencies(Collection<Long> frequencies)
    {
        StringBuilder sb = new StringBuilder();

        for(Long frequency : frequencies)
        {
            if(sb.length() > 0)
            {
                sb.append(',');
            }

            sb.append(frequency);
        }

        //java.util.prefs values are limited to 8k characters
        String value = sb.toString();

        if(value.length() > Preferences.MAX_VALUE_LENGTH)
        {
            value = value.substring(0, value.lastIndexOf(',', Preferences.MAX_VALUE_LENGTH - 1));
        }

        mPreferences.put(KEY_IGNORED, value);
        notifyPreferenceUpdated();
    }

    private static String node(String tunerId)
    {
        //Preference keys are limited to 80 characters
        String key = tunerId.replaceAll("[^A-Za-z0-9_-]", "_");
        return key.length() > 50 ? key.substring(0, 50) : key;
    }

    public boolean isEnabled(String tunerId)
    {
        return mPreferences.getBoolean(KEY_ENABLED_PREFIX + node(tunerId), false);
    }

    public void setEnabled(String tunerId, boolean enabled)
    {
        mPreferences.putBoolean(KEY_ENABLED_PREFIX + node(tunerId), enabled);
        notifyPreferenceUpdated();
    }

    public DiscoverySettings loadSettings(String tunerId)
    {
        String n = node(tunerId);
        DiscoverySettings defaults = new DiscoverySettings();
        DiscoverySettings settings = new DiscoverySettings();
        settings.setSnrThresholdDb(mPreferences.getDouble(KEY_SNR_PREFIX + n, defaults.getSnrThresholdDb()));
        settings.setLevelThresholdDb(mPreferences.getDouble(KEY_LEVEL_PREFIX + n, defaults.getLevelThresholdDb()));
        settings.setDwellMs(mPreferences.getInt(KEY_DWELL_PREFIX + n, defaults.getDwellMs()));
        settings.setClipSeconds(mPreferences.getInt(KEY_CLIP_PREFIX + n, defaults.getClipSeconds()));
        settings.setCaptureCooldownSeconds(mPreferences.getInt(KEY_COOLDOWN_PREFIX + n, defaults.getCaptureCooldownSeconds()));
        settings.setMaxConcurrentCaptures(mPreferences.getInt(KEY_CAPTURES_PREFIX + n, defaults.getMaxConcurrentCaptures()));
        settings.setIgnoreDcBin(mPreferences.getBoolean(KEY_DC_PREFIX + n, defaults.isIgnoreDcBin()));
        return settings;
    }

    public void saveSettings(String tunerId, DiscoverySettings settings)
    {
        String n = node(tunerId);
        mPreferences.putDouble(KEY_SNR_PREFIX + n, settings.getSnrThresholdDb());
        mPreferences.putDouble(KEY_LEVEL_PREFIX + n, settings.getLevelThresholdDb());
        mPreferences.putInt(KEY_DWELL_PREFIX + n, settings.getDwellMs());
        mPreferences.putInt(KEY_CLIP_PREFIX + n, settings.getClipSeconds());
        mPreferences.putInt(KEY_COOLDOWN_PREFIX + n, settings.getCaptureCooldownSeconds());
        mPreferences.putInt(KEY_CAPTURES_PREFIX + n, settings.getMaxConcurrentCaptures());
        mPreferences.putBoolean(KEY_DC_PREFIX + n, settings.isIgnoreDcBin());
        notifyPreferenceUpdated();
    }
}
