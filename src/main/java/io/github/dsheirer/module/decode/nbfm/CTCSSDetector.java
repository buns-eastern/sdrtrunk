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

package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.module.decode.ctcss.CTCSSCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Real-time CTCSS tone detector using the Goertzel algorithm.
 *
 * Analyzes demodulated FM audio to detect sub-audible CTCSS tones (67.0 - 254.1 Hz).
 * The Goertzel algorithm is an efficient way to compute the energy at specific frequencies
 * without performing a full FFT.
 *
 * Processing approach:
 * - Collects samples into blocks (sized for ~2 cycles of the lowest target tone)
 * - Runs Goertzel on each target frequency
 * - Compares detected energy against a noise floor estimate
 * - Reports the strongest matching tone if it exceeds the threshold
 */
public class CTCSSDetector
{
    private static final Logger LOGGER = LoggerFactory.getLogger(CTCSSDetector.class);

    /**
     * Minimum SNR (signal-to-noise ratio in dB) for a tone to be considered detected.
     * CTCSS tones are typically 10-15% of peak deviation, so they're relatively weak.
     */
    private static final float DETECTION_THRESHOLD_DB = 6.0f;

    /**
     * Frequency cutoff for the spectral leakage preference logic.
     * Below this frequency, closely-spaced Goertzel bins cause significant leakage,
     * so we allow preferring a nearby target bin over the peak bin if within 2 dB.
     */
    private static final float LOW_FREQ_NARROWBAND_CUTOFF = 120.0f;

    /**
     * Number of consecutive detections required before reporting a match.
     * Prevents false triggers from transient energy.
     * 3 blocks at ~75ms each = ~225ms detection latency (within normal radio PL decode time).
     */
    private static final int CONFIRMATION_COUNT = 3;

    /**
     * Number of consecutive misses before declaring tone lost.
     */
    private static final int LOSS_COUNT = 4;

    /**
     * Peak-to-floor margin (dB) the strongest CTCSS bin must exceed over the median power of the
     * sub-audible band to count as a tone present.  A genuine PL tone is a sharp spike far above
     * this floor; broadband / co-channel noise is flat and never clears it.  Combined with the
     * 3-block confirmation (same tone required), this rejects noise while passing real tones.
     */
    private static final float DETECT_MARGIN_DB = 10.0f;

    private final Set<CTCSSCode> mTargetCodes;
    private final float[] mTargetFrequencies;
    private final CTCSSCode[] mTargetCodeArray;
    private final float mSampleRate;
    private final int mBlockSize;

    // Goertzel coefficients for each target frequency
    private final float[] mCoefficients;

    // Sample accumulator
    private float[] mSampleBuffer;
    private int mSampleIndex = 0;

    // Detection state
    private CTCSSCode mDetectedCode = null;
    private int mConfirmationCounter = 0;
    private int mLossCounter = 0;

    // Tone survey: log top tones periodically regardless of threshold
    private static final int SURVEY_INTERVAL_BLOCKS = 40; // ~every 3 seconds at 75ms/block
    private int mSurveyBlockCounter = 0;

    // Channel identification for log messages (e.g. "MetroFire - Red [483.3125]")
    private String mChannelLabel = "";

    // Callback
    private CTCSSDetectorListener mListener;

    /**
     * Listener interface for CTCSS detection events
     */
    public interface CTCSSDetectorListener
    {
        void ctcssDetected(CTCSSCode code);
        void ctcssRejected(CTCSSCode code);
        void ctcssLost();
    }

    /**
     * Constructs a CTCSS detector for a specific set of target tones.
     *
     * @param targetCodes the set of CTCSS codes to accept as matches. If null or empty, accepts all standard codes.
     * @param sampleRate the sample rate of the input audio in Hz
     */
    public CTCSSDetector(Set<CTCSSCode> targetCodes, float sampleRate)
    {
        mSampleRate = sampleRate;

        if(targetCodes == null || targetCodes.isEmpty())
        {
            mTargetCodes = CTCSSCode.STANDARD_CODES;
        }
        else
        {
            mTargetCodes = targetCodes;
        }

        // Always scan ALL standard CTCSS tones to prevent spectral leakage false matches.
        // We find the strongest tone across all frequencies, then check if it's in our allowed set.
        Set<CTCSSCode> allCodes = CTCSSCode.STANDARD_CODES;
        mTargetCodeArray = allCodes.toArray(new CTCSSCode[0]);
        mTargetFrequencies = new float[mTargetCodeArray.length];
        mCoefficients = new float[mTargetCodeArray.length];

        for(int i = 0; i < mTargetCodeArray.length; i++)
        {
            mTargetFrequencies[i] = mTargetCodeArray[i].getFrequency();
        }

        // Block size: enough samples for ~2.5 cycles of the lowest frequency we care about
        // Lowest CTCSS tone is 67.0 Hz. At 8000 Hz sample rate: 8000/67 * 2.5 ≈ 299 samples
        // We use a power-of-nothing block — just enough for good frequency resolution
        float lowestFreq = Float.MAX_VALUE;
        for(float freq : mTargetFrequencies)
        {
            if(freq > 0 && freq < lowestFreq)
            {
                lowestFreq = freq;
            }
        }

        // Block size targets sufficient frequency resolution to discriminate closely-spaced
        // CTCSS tones (e.g. 131.8 Hz vs 146.2 Hz = 14.4 Hz apart). Frequency resolution is
        // sampleRate / blockSize, so we need blockSize >= sampleRate / 14 for ~1 Hz margin.
        // We use the larger of: 5 cycles of the lowest tone, or sampleRate / 12 (for ~12 Hz resolution).
        int blockByCycles = (int)(mSampleRate / lowestFreq * 5.0f);
        int blockByResolution = (int)(mSampleRate / 12.0f);
        int blockSize = Math.max(blockByCycles, blockByResolution);

        // Clamp to reasonable range
        if(blockSize < 256)
        {
            blockSize = 256;
        }
        else if(blockSize > 4096)
        {
            blockSize = 4096;
        }

        mBlockSize = blockSize;

        mSampleBuffer = new float[mBlockSize];

        // Pre-compute Goertzel coefficients: 2 * cos(2π * freq / sampleRate)
        for(int i = 0; i < mTargetFrequencies.length; i++)
        {
            double normalizedFreq = mTargetFrequencies[i] / mSampleRate;
            mCoefficients[i] = (float)(2.0 * Math.cos(2.0 * Math.PI * normalizedFreq));
        }

        LOGGER.debug("{}CTCSSDetector initialized: {} target tones, block size {}, sample rate {}",
                mChannelLabel, mTargetCodeArray.length, mBlockSize, mSampleRate);
    }

    /**
     * Sets a channel label that is prepended to all log messages for channel identification.
     * Format example: "MetroFire - Red [483.3125]"
     *
     * @param label the channel label, or null/empty for no prefix
     */
    public void setChannelLabel(String label)
    {
        mChannelLabel = (label != null && !label.isEmpty()) ? "[" + label + "] " : "";
    }

    /**
     * Sets the listener for detection events.
     */
    public void setListener(CTCSSDetectorListener listener)
    {
        mListener = listener;
    }

    /**
     * Processes a buffer of demodulated FM audio samples.
     * Samples are accumulated into blocks, and Goertzel analysis is performed on each complete block.
     *
     * @param samples demodulated audio samples
     */
    public void process(float[] samples)
    {
        if(samples == null || samples.length == 0)
        {
            return;
        }

        int offset = 0;

        while(offset < samples.length)
        {
            int remaining = mBlockSize - mSampleIndex;
            int toCopy = Math.min(remaining, samples.length - offset);

            System.arraycopy(samples, offset, mSampleBuffer, mSampleIndex, toCopy);
            mSampleIndex += toCopy;
            offset += toCopy;

            if(mSampleIndex >= mBlockSize)
            {
                analyzeBlock();
                mSampleIndex = 0;
            }
        }
    }

    /**
     * Analyzes the current block of samples using Goertzel algorithm for each target frequency.
     * The strongest bin must exceed the total-energy SNR threshold (DETECTION_THRESHOLD_DB)
     * to be considered a detection.
     */
    private void analyzeBlock()
    {
        // Goertzel power at every standard CTCSS frequency.
        float[] powers = new float[mTargetFrequencies.length];
        float maxPower = 0f;
        int maxIndex = -1;

        for(int i = 0; i < mTargetFrequencies.length; i++)
        {
            powers[i] = goertzel(mSampleBuffer, mBlockSize, mCoefficients[i]);

            if(powers[i] > maxPower)
            {
                maxPower = powers[i];
                maxIndex = i;
            }
        }

        if(maxIndex < 0 || maxPower < 1e-10f)
        {
            handleNoDetection();
            return;
        }

        // Radio-style PL decode: a real CTCSS tone is a single sharp spike standing well above the
        // noise floor of the sub-audible band itself.  Estimate that floor as the median power across
        // all CTCSS bins (robust: one real tone barely moves the median of 50 bins), then require the
        // strongest bin to clear it by a fixed margin.  Broadband / co-channel mush has a flat
        // spectrum (peak barely above the median) and is rejected outright.  Because this is a ratio
        // of two in-band powers, voice energy no longer suppresses the tone the way total-energy did.
        float floor = medianPower(powers);
        float peakToFloorDb = (float)(10.0 * Math.log10((maxPower / (floor + 1e-12f)) + 1e-12f));

        if(peakToFloorDb >= DETECT_MARGIN_DB)
        {
            // A clear, dominant tone is present.  handleDetection() unmutes only if it is one of the
            // programmed tones; any other dominant tone is a non-target and the channel stays muted.
            handleDetection(mTargetCodeArray[maxIndex]);
        }
        else
        {
            handleNoDetection();
        }
    }

    /**
     * Median of the per-bin Goertzel powers — a robust estimate of the sub-audible band noise floor
     * used by the peak-to-floor tone-presence test.
     * @param powers per-bin Goertzel power values
     * @return median power
     */
    private static float medianPower(float[] powers)
    {
        float[] sorted = powers.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return (n % 2 == 0) ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2f : sorted[n / 2];
    }

    /**
     * Goertzel algorithm - computes the energy at a specific frequency.
     *
     * @param samples the input samples
     * @param numSamples number of samples to process
     * @param coefficient pre-computed 2*cos(2π*f/fs)
     * @return the magnitude squared at the target frequency
     */
    private float goertzel(float[] samples, int numSamples, float coefficient)
    {
        float s0 = 0;
        float s1 = 0;
        float s2 = 0;

        for(int i = 0; i < numSamples; i++)
        {
            s0 = samples[i] + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        // Magnitude squared = s1^2 + s2^2 - coefficient * s1 * s2
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2;
    }

    /**
     * Handles detection of a CTCSS tone in the current block.
     * Only reports to the listener if the detected tone is in the allowed (target) set.
     *
     * IMPORTANT: mLossCounter is only reset when the TARGET tone is detected. Non-target
     * detections do NOT reset the loss counter. This prevents broadband interference
     * (which randomly lights up different CTCSS bins each block) from holding the tone
     * gate open indefinitely via the holdover mechanism. Without this, digital noise
     * bursts following a valid transmission could leak through for their entire duration
     * because mLossCounter would never reach LOSS_COUNT.
     */
    private void handleDetection(CTCSSCode code)
    {
        // Only accept tones that are in our allowed set
        if(!mTargetCodes.contains(code))
        {
            // NON-TARGET tone detected: do NOT reset mLossCounter.
            // Broadband noise/digital interference lights up random CTCSS bins each block.
            // If we reset mLossCounter here, it would never reach LOSS_COUNT, and a prior
            // target match (held over from a valid transmission) would stay active forever.

            // Track confirmed rejections — only notify after same wrong tone seen CONFIRMATION_COUNT times
            if(mDetectedCode == code)
            {
                if(mConfirmationCounter < CONFIRMATION_COUNT)
                {
                    mConfirmationCounter++;

                    if(mConfirmationCounter >= CONFIRMATION_COUNT && mListener != null)
                    {
                        mListener.ctcssRejected(code);
                    }
                }
                // Already confirmed rejected — don't spam listener
            }
            else
            {
                mDetectedCode = code;
                mConfirmationCounter = 1;
            }
            return;
        }

        // TARGET tone detected — reset loss counter
        mLossCounter = 0;

        if(mDetectedCode == code)
        {
            // Same tone detected again — increment confirmation
            if(mConfirmationCounter < CONFIRMATION_COUNT)
            {
                mConfirmationCounter++;

                if(mConfirmationCounter >= CONFIRMATION_COUNT && mListener != null)
                {
                    mListener.ctcssDetected(code);
                }
            }
            // Already confirmed — just keep reporting
            else if(mListener != null)
            {
                mListener.ctcssDetected(code);
            }
        }
        else
        {
            // Different tone — restart confirmation
            mDetectedCode = code;
            mConfirmationCounter = 1;
        }
    }

    /**
     * Handles no CTCSS tone detected in the current block.
     */
    private void handleNoDetection()
    {
        if(mDetectedCode != null)
        {
            mLossCounter++;

            if(mLossCounter >= LOSS_COUNT)
            {
                mDetectedCode = null;
                mConfirmationCounter = 0;

                if(mListener != null)
                {
                    mListener.ctcssLost();
                }
            }
        }
    }

    /**
     * Resets the detector state.
     */
    public void reset()
    {
        mSampleIndex = 0;
        mDetectedCode = null;
        mConfirmationCounter = 0;
        mLossCounter = 0;
    }

    /**
     * Returns the currently detected CTCSS code, or null if none detected.
     */
    public CTCSSCode getDetectedCode()
    {
        return (mConfirmationCounter >= CONFIRMATION_COUNT) ? mDetectedCode : null;
    }

    /**
     * Returns the current loss counter value (number of consecutive blocks without target detection).
     * Used for diagnostic logging.
     */
    public int getLossCounter()
    {
        return mLossCounter;
    }

    /**
     * Returns the raw detected code (even if not yet confirmed), or null.
     * Used for diagnostic logging.
     */
    public CTCSSCode getRawDetectedCode()
    {
        return mDetectedCode;
    }
}
