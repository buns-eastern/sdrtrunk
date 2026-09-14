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

import io.github.dsheirer.dsp.filter.channelizer.ChannelCalculator;
import io.github.dsheirer.dsp.filter.channelizer.IChannelResultsListener;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.record.wave.ComplexSamplesWaveRecorder;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.TunerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Per-tuner signal discovery monitor.  Taps the raw polyphase channelizer output, tracks power in every channelizer
 * bin, detects bins that rise above both an SNR-above-noise-floor threshold and an absolute level threshold, and
 * captures a short I/Q clip of each new hit straight from the channelizer bin for offline identification.
 *
 * All heavy lifting happens on the channelizer IFFT dispatch thread, so the per-sample work is kept to a
 * multiply-accumulate per bin plus a copy for any bins currently being captured.
 */
public class DiscoveryMonitor implements IChannelResultsListener, ISourceEventProcessor
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryMonitor.class);
    private static final SimpleDateFormat FILE_TIMESTAMP = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static final int EVALUATION_INTERVAL_MS = 100;
    private static final int CAPTURE_BUFFER_SAMPLES = 2048;
    private static final int HOLDOFF_EVALUATIONS = 5; //500 ms below threshold before a hit is considered ended

    public interface Listener
    {
        void hitStarted(String tunerId, long binFrequency, double levelDb, double snrDb, long timestamp);
        void hitUpdated(String tunerId, long binFrequency, double levelDb, double snrDb, long timestamp);
        void hitEnded(String tunerId, long binFrequency, long timestamp, long activeMs);
        void clipCompleted(String tunerId, long binFrequency, Path clip, double channelSampleRate);
        void floorUpdated(String tunerId, double floorDb, int binCount);
    }

    private final String mTunerId;
    private final PolyphaseChannelManager mChannelManager;
    private final TunerController mTunerController;
    private final DiscoverySettings mSettings;
    private final Path mClipDirectory;
    private final Listener mListener;
    private final Set<Long> mExcludedFrequencies = new CopyOnWriteArraySet<>();
    private volatile boolean mRunning = false;
    private volatile boolean mResetRequested = false;

    //Per-bin state - (re)allocated whenever the channel count changes
    private int mBinCount = 0;
    private double[] mPowerAccumulator;
    private int mAccumulatedSamples = 0;
    private double mChannelSampleRate = 50000.0;
    private int mSamplesPerEvaluation = 5000;
    private int[] mAboveCount;
    private int[] mBelowCount;
    private boolean[] mActive;
    private long[] mActiveSince;
    private long[] mLastCaptureTime;
    private boolean[] mExcluded;
    private long[] mBinFrequency;
    private double mFloorDb = -200;
    private long mLastTimestamp;

    private final List<Capture> mCaptures = new ArrayList<>();

    public DiscoveryMonitor(String tunerId, PolyphaseChannelManager channelManager, TunerController tunerController,
                            DiscoverySettings settings, Path clipDirectory, Listener listener)
    {
        mTunerId = tunerId;
        mChannelManager = channelManager;
        mTunerController = tunerController;
        mSettings = settings;
        mClipDirectory = clipDirectory;
        mListener = listener;
    }

    public String getTunerId()
    {
        return mTunerId;
    }

    public DiscoverySettings getSettings()
    {
        return mSettings;
    }

    public boolean isRunning()
    {
        return mRunning;
    }

    public double getFloorDb()
    {
        return mFloorDb;
    }

    public int getBinCount()
    {
        return mBinCount;
    }

    /**
     * Frequencies (Hz) to exclude - any bin whose center is within half a bin of one of these is never a hit.
     */
    public void setExcludedFrequencies(Set<Long> frequencies)
    {
        mExcludedFrequencies.clear();
        mExcludedFrequencies.addAll(frequencies);
        mResetRequested = true;
    }

    public void start()
    {
        if(!mRunning)
        {
            mRunning = true;
            mResetRequested = true;
            mTunerController.addListener(this);
            mChannelManager.addChannelResultsListener(this);
            mLog.info("Discovery monitor started for tuner [" + mTunerId + "]");
        }
    }

    public void stop()
    {
        if(mRunning)
        {
            mRunning = false;
            mChannelManager.removeChannelResultsListener(this);
            mTunerController.removeListener(this);

            synchronized(mCaptures)
            {
                for(Capture capture : mCaptures)
                {
                    capture.abort();
                }
                mCaptures.clear();
            }

            mLog.info("Discovery monitor stopped for tuner [" + mTunerId + "]");
        }
    }

    @Override
    public void process(SourceEvent event)
    {
        switch(event.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
            case NOTIFICATION_FREQUENCY_CORRECTION_CHANGE:
                mResetRequested = true;
                break;
            default:
                break;
        }
    }

    @Override
    public void receiveChannelResults(List<float[]> channelResults, double channelSampleRate, long timestamp)
    {
        if(!mRunning || channelResults.isEmpty())
        {
            return;
        }

        int binCount = channelResults.get(0).length / 2;

        if(binCount != mBinCount || mResetRequested || channelSampleRate != mChannelSampleRate)
        {
            reset(binCount, channelSampleRate);
        }

        mLastTimestamp = timestamp;

        //Accumulate power per bin and feed active captures
        for(float[] sample : channelResults)
        {
            if(sample == null || sample.length < mBinCount * 2)
            {
                continue;
            }

            for(int bin = 0, idx = 0; bin < mBinCount; bin++, idx += 2)
            {
                float i = sample[idx];
                float q = sample[idx + 1];
                mPowerAccumulator[bin] += (i * i) + (q * q);
            }
        }

        synchronized(mCaptures)
        {
            if(!mCaptures.isEmpty())
            {
                for(Capture capture : mCaptures)
                {
                    capture.receive(channelResults, timestamp);
                }

                mCaptures.removeIf(Capture::isComplete);
            }
        }

        mAccumulatedSamples += channelResults.size();

        if(mAccumulatedSamples >= mSamplesPerEvaluation)
        {
            evaluate(timestamp);
        }
    }

    private void reset(int binCount, double channelSampleRate)
    {
        mResetRequested = false;
        mBinCount = binCount;
        mChannelSampleRate = channelSampleRate;
        mSamplesPerEvaluation = (int)Math.max(1, channelSampleRate * EVALUATION_INTERVAL_MS / 1000.0);
        mPowerAccumulator = new double[binCount];
        mAccumulatedSamples = 0;
        mAboveCount = new int[binCount];
        mBelowCount = new int[binCount];
        mActive = new boolean[binCount];
        mActiveSince = new long[binCount];
        mLastCaptureTime = new long[binCount];
        mExcluded = new boolean[binCount];
        mBinFrequency = new long[binCount];

        ChannelCalculator calculator = mChannelManager.getChannelCalculator();
        int wrapAround = calculator.getWrapAroundIndex();
        double halfBin = calculator.getHalfChannelBandwidth();

        for(int bin = 0; bin < binCount; bin++)
        {
            if(bin == wrapAround || bin >= calculator.getChannelCount())
            {
                mExcluded[bin] = true;
                mBinFrequency[bin] = 0;
                continue;
            }

            long frequency = (long)calculator.getIndexCenterFrequency(bin, ChannelCalculator.IndexBoundaryPolicy.ADJUST_POSITIVE);
            mBinFrequency[bin] = frequency;

            if(bin == 0 && mSettings.isIgnoreDcBin())
            {
                mExcluded[bin] = true;
                continue;
            }

            for(long excluded : mExcludedFrequencies)
            {
                if(Math.abs(excluded - frequency) <= halfBin)
                {
                    mExcluded[bin] = true;
                    break;
                }
            }
        }

        synchronized(mCaptures)
        {
            for(Capture capture : mCaptures)
            {
                capture.abort();
            }
            mCaptures.clear();
        }
    }

    private void evaluate(long timestamp)
    {
        int count = mBinCount;
        double[] powerDb = new double[count];
        double scale = 1.0 / mAccumulatedSamples;

        for(int bin = 0; bin < count; bin++)
        {
            double power = mPowerAccumulator[bin] * scale;
            powerDb[bin] = power > 0 ? 10.0 * Math.log10(power) : -200.0;
            mPowerAccumulator[bin] = 0;
        }

        mAccumulatedSamples = 0;

        //Noise floor = median bin power (most bins are empty at any instant)
        double[] sorted = Arrays.copyOf(powerDb, count);
        Arrays.sort(sorted);
        double floor = sorted[count / 2];
        mFloorDb = floor;

        double snrThreshold = mSettings.getSnrThresholdDb();
        double levelThreshold = mSettings.getLevelThresholdDb();
        int dwellEvaluations = Math.max(1, mSettings.getDwellMs() / EVALUATION_INTERVAL_MS);
        long cooldownMs = mSettings.getCaptureCooldownSeconds() * 1000L;

        for(int bin = 0; bin < count; bin++)
        {
            if(mExcluded[bin])
            {
                continue;
            }

            double level = powerDb[bin];
            double snr = level - floor;
            boolean above = snr >= snrThreshold && level >= levelThreshold;

            if(above)
            {
                //Require a local maximum so a strong signal straddling two bins produces one hit, not two
                double left = bin > 0 ? powerDb[bin - 1] : -300;
                double right = bin < count - 1 ? powerDb[bin + 1] : -300;

                if(level < left || level < right)
                {
                    above = false;
                }
            }

            if(above)
            {
                mBelowCount[bin] = 0;
                mAboveCount[bin]++;

                if(!mActive[bin])
                {
                    if(mAboveCount[bin] >= dwellEvaluations)
                    {
                        mActive[bin] = true;
                        mActiveSince[bin] = timestamp;
                        mListener.hitStarted(mTunerId, mBinFrequency[bin], level, snr, timestamp);

                        if(timestamp - mLastCaptureTime[bin] >= cooldownMs)
                        {
                            startCapture(bin, timestamp, level);
                        }
                    }
                }
                else
                {
                    mListener.hitUpdated(mTunerId, mBinFrequency[bin], level, snr, timestamp);
                }
            }
            else
            {
                mAboveCount[bin] = 0;

                if(mActive[bin])
                {
                    mBelowCount[bin]++;

                    if(mBelowCount[bin] >= HOLDOFF_EVALUATIONS)
                    {
                        mActive[bin] = false;
                        mBelowCount[bin] = 0;
                        mListener.hitEnded(mTunerId, mBinFrequency[bin], timestamp, timestamp - mActiveSince[bin]);
                    }
                }
            }
        }

        mListener.floorUpdated(mTunerId, floor, count);
    }

    private void startCapture(int bin, long timestamp, double levelDb)
    {
        synchronized(mCaptures)
        {
            if(mCaptures.size() >= mSettings.getMaxConcurrentCaptures())
            {
                return;
            }

            for(Capture capture : mCaptures)
            {
                if(capture.mBin == bin)
                {
                    return;
                }
            }

            try
            {
                String name = FILE_TIMESTAMP.format(new Date(timestamp)) + "_" + mBinFrequency[bin] + "_discovery_" +
                    sanitize(mTunerId) + "_baseband";
                Path prefix = mClipDirectory.resolve(name);
                long samples = (long)(mSettings.getClipSeconds() * mChannelSampleRate);
                //Channelizer output amplitudes are tiny (tens of micro-units); scale so the signal RMS lands near
                //-10 dBFS in the 16-bit clip, otherwise every sample quantizes to zero.
                double rms = Math.pow(10.0, levelDb / 20.0);
                float gain = (float)Math.min(1.0E9, 0.3 / Math.max(rms, 1.0E-12));
                Capture capture = new Capture(bin, mBinFrequency[bin], prefix.toString(), samples, gain);
                capture.start();
                mCaptures.add(capture);
                mLastCaptureTime[bin] = timestamp;
            }
            catch(Exception e)
            {
                mLog.error("Error starting discovery capture", e);
            }
        }
    }

    private static String sanitize(String value)
    {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * In-flight I/Q clip capture for a single channelizer bin.
     */
    private class Capture
    {
        private final int mBin;
        private final long mFrequency;
        private final ComplexSamplesWaveRecorder mRecorder;
        private final long mTargetSamples;
        private final float mGain;
        private long mSamplesWritten = 0;
        private float[] mI = new float[CAPTURE_BUFFER_SAMPLES];
        private float[] mQ = new float[CAPTURE_BUFFER_SAMPLES];
        private int mPointer = 0;
        private boolean mComplete = false;

        Capture(int bin, long frequency, String filePrefix, long targetSamples, float gain)
        {
            mBin = bin;
            mFrequency = frequency;
            mTargetSamples = targetSamples;
            mGain = gain;
            mRecorder = new ComplexSamplesWaveRecorder((float)mChannelSampleRate, filePrefix);
        }

        void start()
        {
            mRecorder.start();
        }

        boolean isComplete()
        {
            return mComplete;
        }

        void receive(List<float[]> channelResults, long timestamp)
        {
            if(mComplete)
            {
                return;
            }

            int idx = mBin * 2;

            for(float[] sample : channelResults)
            {
                if(sample == null || sample.length <= idx + 1)
                {
                    continue;
                }

                mI[mPointer] = clamp(sample[idx] * mGain);
                mQ[mPointer] = clamp(sample[idx + 1] * mGain);
                mPointer++;
                mSamplesWritten++;

                if(mPointer >= CAPTURE_BUFFER_SAMPLES)
                {
                    flush(timestamp);
                }

                if(mSamplesWritten >= mTargetSamples)
                {
                    finish(timestamp);
                    return;
                }
            }
        }

        private static float clamp(float value)
        {
            return value > 0.999f ? 0.999f : (value < -0.999f ? -0.999f : value);
        }

        private void flush(long timestamp)
        {
            if(mPointer > 0)
            {
                mRecorder.receive(new ComplexSamples(Arrays.copyOf(mI, mPointer), Arrays.copyOf(mQ, mPointer), timestamp));
                mPointer = 0;
            }
        }

        private void finish(long timestamp)
        {
            flush(timestamp);
            mComplete = true;
            final Path file = mRecorder.getFile();
            mRecorder.flushAndStop();
            mListener.clipCompleted(mTunerId, mFrequency, file, mChannelSampleRate);
        }

        void abort()
        {
            if(!mComplete)
            {
                mComplete = true;
                mRecorder.stop();
            }
        }
    }
}
