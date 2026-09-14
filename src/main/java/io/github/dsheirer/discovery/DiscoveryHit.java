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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One discovered signal (keyed by tuner + channelizer bin center frequency) with accumulated statistics, the most
 * recent captured clip, and any offline analysis results.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DiscoveryHit
{
    public enum Status { NEW, CAPTURED, ANALYZING, IDENTIFIED, UNIDENTIFIED, IGNORED }

    @JsonProperty("tunerId") private String mTunerId;
    @JsonProperty("binFrequency") private long mBinFrequency;
    @JsonProperty("carrierFrequency") private long mCarrierFrequency;
    @JsonProperty("firstHeard") private long mFirstHeard;
    @JsonProperty("lastHeard") private long mLastHeard;
    @JsonProperty("hitCount") private int mHitCount;
    @JsonProperty("peakLevelDb") private double mPeakLevelDb = -200;
    @JsonProperty("peakSnrDb") private double mPeakSnrDb = -200;
    @JsonProperty("lastLevelDb") private double mLastLevelDb = -200;
    @JsonProperty("totalActiveMs") private long mTotalActiveMs;
    @JsonProperty("protocol") private String mProtocol = "";
    @JsonProperty("details") private String mDetails = "";
    @JsonProperty("clipPath") private String mClipPath;
    @JsonProperty("status") private Status mStatus = Status.NEW;
    @JsonProperty("active") private boolean mActive;

    public DiscoveryHit() {}

    public DiscoveryHit(String tunerId, long binFrequency, long timestamp)
    {
        mTunerId = tunerId;
        mBinFrequency = binFrequency;
        mCarrierFrequency = binFrequency;
        mFirstHeard = timestamp;
        mLastHeard = timestamp;
    }

    /** Composite key: tuner + bin center frequency. */
    public String getKey() { return key(mTunerId, mBinFrequency); }

    public static String key(String tunerId, long binFrequency) { return tunerId + ":" + binFrequency; }

    public String getTunerId() { return mTunerId; }
    public void setTunerId(String v) { mTunerId = v; }
    public long getBinFrequency() { return mBinFrequency; }
    public void setBinFrequency(long v) { mBinFrequency = v; }
    public long getCarrierFrequency() { return mCarrierFrequency; }
    public void setCarrierFrequency(long v) { mCarrierFrequency = v; }
    public long getFirstHeard() { return mFirstHeard; }
    public void setFirstHeard(long v) { mFirstHeard = v; }
    public long getLastHeard() { return mLastHeard; }
    public void setLastHeard(long v) { mLastHeard = v; }
    public int getHitCount() { return mHitCount; }
    public void setHitCount(int v) { mHitCount = v; }
    public double getPeakLevelDb() { return mPeakLevelDb; }
    public void setPeakLevelDb(double v) { mPeakLevelDb = v; }
    public double getPeakSnrDb() { return mPeakSnrDb; }
    public void setPeakSnrDb(double v) { mPeakSnrDb = v; }
    public double getLastLevelDb() { return mLastLevelDb; }
    public void setLastLevelDb(double v) { mLastLevelDb = v; }
    public long getTotalActiveMs() { return mTotalActiveMs; }
    public void setTotalActiveMs(long v) { mTotalActiveMs = v; }
    public String getProtocol() { return mProtocol; }
    public void setProtocol(String v) { mProtocol = v == null ? "" : v; }
    public String getDetails() { return mDetails; }
    public void setDetails(String v) { mDetails = v == null ? "" : v; }
    public String getClipPath() { return mClipPath; }
    public void setClipPath(String v) { mClipPath = v; }
    public Status getStatus() { return mStatus; }
    public void setStatus(Status v) { mStatus = v; }
    public boolean isActive() { return mActive; }
    public void setActive(boolean v) { mActive = v; }

    public boolean hasClip() { return mClipPath != null && !mClipPath.isEmpty(); }
}
