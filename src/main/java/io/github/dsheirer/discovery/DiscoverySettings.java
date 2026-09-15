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

/**
 * Tunable detection/capture settings for one tuner's signal discovery monitor.  All values are user adjustable and
 * may be changed while the monitor is running.
 */
public class DiscoverySettings
{
    private volatile double mSnrThresholdDb = 15.0;
    private volatile double mLevelThresholdDb = -150.0;
    private volatile int mDwellMs = 200;
    private volatile int mClipSeconds = 5;
    private volatile int mMaxConcurrentCaptures = 8;
    private volatile int mCaptureCooldownSeconds = 60;
    private volatile boolean mIgnoreDcBin = true;

    public double getSnrThresholdDb() { return mSnrThresholdDb; }
    public void setSnrThresholdDb(double v) { mSnrThresholdDb = v; }

    public double getLevelThresholdDb() { return mLevelThresholdDb; }
    public void setLevelThresholdDb(double v) { mLevelThresholdDb = v; }

    public int getDwellMs() { return mDwellMs; }
    public void setDwellMs(int v) { mDwellMs = Math.max(0, v); }

    public int getClipSeconds() { return mClipSeconds; }
    public void setClipSeconds(int v) { mClipSeconds = Math.max(1, v); }

    public int getMaxConcurrentCaptures() { return mMaxConcurrentCaptures; }
    public void setMaxConcurrentCaptures(int v) { mMaxConcurrentCaptures = Math.max(1, v); }

    public int getCaptureCooldownSeconds() { return mCaptureCooldownSeconds; }
    public void setCaptureCooldownSeconds(int v) { mCaptureCooldownSeconds = Math.max(0, v); }

    public boolean isIgnoreDcBin() { return mIgnoreDcBin; }
    public void setIgnoreDcBin(boolean v) { mIgnoreDcBin = v; }
}
