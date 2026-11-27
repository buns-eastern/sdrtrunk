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

package io.github.dsheirer.dsp.squelch;

import io.github.dsheirer.sample.Listener;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Removes MDC-1200 data bursts (the "squawk") from 8 kHz demodulated audio.
 *
 * Audio is held in a short delay line (250 ms).  A built-in MDC-1200 sync detector watches the audio as it enters the
 * delay line.  MDC-1200 is 1200 baud FFSK using 1200 Hz and 1800 Hz tones, where the tone for each bit is the XOR of
 * the current and previous data bits.  When the 40-bit MDC sync word is seen, the packet is located (preamble before
 * the sync, one or two 112-bit data blocks after it) and that stretch of audio is cut out of the stream before it
 * leaves the delay line, with short fades on each side of the cut.  Bursts at the start of a transmission, at the
 * end, or both, are handled the same way.
 *
 * Call flush() when the transmission ends (squelch closes) so the audio still in the delay line is delivered before
 * the call ends.
 *
 * Thread safety: not thread-safe.  All calls must come from the same processing thread.
 */
public class MdcSquawkRemover
{
    private static final int SAMPLE_RATE = 8000;
    private static final int BAUD = 1200;

    /** Audio delay, 250 ms */
    public static final int DELAY_SAMPLES = 2000;

    /** Ring buffer size (power of two, larger than the delay plus one incoming buffer) */
    private static final int RING_SIZE = 16384;
    private static final int RING_MASK = RING_SIZE - 1;

    /** Fade length on each side of a cut, 5 ms */
    private static final int FADE_SAMPLES = 40;

    /** MDC-1200 sync word, 40 bits */
    private static final long SYNC = 0x07092A446FL;
    private static final long MASK_40 = 0xFFFFFFFFFFL;
    private static final int MAXIMUM_SYNC_BIT_ERRORS = 2;

    /** Samples per bit (6.667 at 8 kHz) */
    private static final double SAMPLES_PER_BIT = (double)SAMPLE_RATE / BAUD;

    /** Sync word plus one 112-bit data block, in samples */
    private static final int SYNC_SAMPLES = (int)Math.round(40 * SAMPLES_PER_BIT);
    private static final int PACKET_SAMPLES = (int)Math.round((40 + 112) * SAMPLES_PER_BIT);

    /** Minimum preamble cut before the sync word (20 ms) and margin after the packet (10 ms) */
    private static final int MINIMUM_PREAMBLE_SAMPLES = 160;
    private static final int END_MARGIN_SAMPLES = 80;

    /** One 112-bit data block, in samples (93 ms) */
    private static final int DATA_BLOCK_SAMPLES = (int)Math.round(112 * SAMPLES_PER_BIT);

    /**
     * Second data block check: share of the audio energy between 900 and 2100 Hz across the 93 ms after the first
     * block.  MDC data measures about 0.73 - 0.78, voice and noise stay well below 0.6.
     */
    private static final float SECOND_BLOCK_BAND_RATIO = 0.62f;

    /** Tone purity measurement blocks (10 ms), used to find the true edges of the burst */
    private static final int BLOCK_SAMPLES = 80;
    private static final int BLOCK_HISTORY = 64;
    private static final float PURITY_THRESHOLD = 0.45f;
    private static final int SECOND_BLOCK_BLOCKS = DATA_BLOCK_SAMPLES / BLOCK_SAMPLES;

    /** Detector low-pass filter */
    private static final int LPF_TAPS = 31;
    private static final int DETECTOR_DELAY = (LPF_TAPS - 1) / 2 + 3;
    private static final float[] LPF_COEFFICIENTS = createLowPass(LPF_TAPS, 800.0);

    /** Mixer table for 1500 Hz at 8 kHz: exactly 3 cycles every 16 samples */
    private static final float[] MIXER_COS = new float[16];
    private static final float[] MIXER_SIN = new float[16];

    static
    {
        for(int x = 0; x < 16; x++)
        {
            MIXER_COS[x] = (float)Math.cos(2.0 * Math.PI * 1500.0 * x / SAMPLE_RATE);
            MIXER_SIN[x] = (float)Math.sin(2.0 * Math.PI * 1500.0 * x / SAMPLE_RATE);
        }
    }

    private Listener<float[]> mOutputListener;

    //Delay line
    private final float[] mRing = new float[RING_SIZE];
    private long mWriteIndex = 0;
    private long mReadIndex = 0;
    private long mStreamStart = 0;

    //Cuts, in absolute sample indices: {start, end}.  The last one may still be growing (mActiveCut).
    private final ArrayDeque<long[]> mCuts = new ArrayDeque<>();
    private long[] mActiveCut = null;
    private long mActiveCutMinimumEnd = 0;
    private long mFirstBlockEnd = 0;
    private int mPacketsRemoved = 0;

    //Detector state
    private final float[] mLpfI = new float[LPF_TAPS];
    private final float[] mLpfQ = new float[LPF_TAPS];
    private int mLpfPointer = 0;
    private float mPreviousI = 0.0f;
    private float mPreviousQ = 0.0f;
    private final float[] mAverage = new float[7];
    private int mAveragePointer = 0;
    private float mAverageSum = 0.0f;
    private final int[] mClock = new int[8];
    private final int[] mBit = new int[8];
    private final long[] mRegister = new long[8];
    private int mMixerPointer = 0;

    //Purity state
    private float mHp1x1, mHp1x2, mHp1y1, mHp1y2;
    private float mG1200s1, mG1200s2, mG1800s1, mG1800s2, mEnergy;
    private int mBlockCount = 0;
    private final float[] mPurity = new float[BLOCK_HISTORY];
    private final float[] mBandEnergy = new float[BLOCK_HISTORY];
    private final float[] mWideEnergy = new float[BLOCK_HISTORY];
    private float mBandAccumulator, mWideAccumulator;
    private float mBpx1, mBpx2, mBpy1, mBpy2, mHpx1, mHpx2, mHpy1, mHpy2, mLpx1, mLpx2, mLpy1, mLpy2;
    private static final float G1200_COEFF = (float)(2.0 * Math.cos(2.0 * Math.PI * 1200.0 / SAMPLE_RATE));
    private static final float G1800_COEFF = (float)(2.0 * Math.cos(2.0 * Math.PI * 1800.0 / SAMPLE_RATE));
    private static final float[] HP_B;
    private static final float[] HP_A;
    private static final float[] BAND_B = new float[3];
    private static final float[] BAND_A = new float[2];
    private static final float[] WIDE_HP_B = new float[3];
    private static final float[] WIDE_HP_A = new float[2];
    private static final float[] WIDE_LP_B = new float[3];
    private static final float[] WIDE_LP_A = new float[2];

    static
    {
        //2nd order Butterworth high-pass at 400 Hz - keeps sub-audible tones out of the purity measurement
        double w0 = 2.0 * Math.PI * 400.0 / SAMPLE_RATE;
        double alpha = Math.sin(w0) / (2.0 * Math.sqrt(0.5));
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        HP_B = new float[]{(float)((1.0 + cos) / 2.0 / a0), (float)(-(1.0 + cos) / a0), (float)((1.0 + cos) / 2.0 / a0)};
        HP_A = new float[]{(float)(-2.0 * cos / a0), (float)((1.0 - alpha) / a0)};

        //Band (900 - 2100 Hz) and wide (300 - 3400 Hz) filters for the second data block check
        biquad(BAND_B, BAND_A, "bp", 1500.0, 1.25);
        biquad(WIDE_HP_B, WIDE_HP_A, "hp", 300.0, Math.sqrt(0.5));
        biquad(WIDE_LP_B, WIDE_LP_A, "lp", 3400.0, Math.sqrt(0.5));
    }

    /**
     * RBJ cookbook biquad coefficients, normalized so a0 = 1.
     */
    private static void biquad(float[] b, float[] a, String type, double frequency, double q)
    {
        double w0 = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        double b0, b1, b2;

        switch(type)
        {
            case "bp":
                b0 = alpha;
                b1 = 0.0;
                b2 = -alpha;
                break;
            case "hp":
                b0 = (1.0 + cos) / 2.0;
                b1 = -(1.0 + cos);
                b2 = (1.0 + cos) / 2.0;
                break;
            default:
                b0 = (1.0 - cos) / 2.0;
                b1 = 1.0 - cos;
                b2 = (1.0 - cos) / 2.0;
                break;
        }

        b[0] = (float)(b0 / a0);
        b[1] = (float)(b1 / a0);
        b[2] = (float)(b2 / a0);
        a[0] = (float)(-2.0 * cos / a0);
        a[1] = (float)((1.0 - alpha) / a0);
    }

    /**
     * Constructs an instance
     */
    public MdcSquawkRemover()
    {
        resetDetector();
    }

    /**
     * Sets the listener to receive the delayed audio with MDC bursts removed.
     */
    public void setOutputListener(Listener<float[]> listener)
    {
        mOutputListener = listener;
    }

    /**
     * Number of MDC packets removed since this instance was created.
     */
    public int getPacketsRemoved()
    {
        return mPacketsRemoved;
    }

    /**
     * Processes a buffer of 8 kHz audio.  Delayed audio is sent to the output listener.
     */
    public void process(float[] audio)
    {
        if(audio == null || audio.length == 0)
        {
            return;
        }

        int offset = 0;

        //Very large buffers are processed in pieces so the ring buffer never overruns the read pointer
        while(offset < audio.length)
        {
            int length = Math.min(audio.length - offset, RING_SIZE - DELAY_SAMPLES - BLOCK_SAMPLES);

            for(int x = offset; x < offset + length; x++)
            {
                float sample = audio[x];
                mRing[(int)(mWriteIndex & RING_MASK)] = sample;
                detect(sample, mWriteIndex);
                measurePurity(sample, mWriteIndex);
                mWriteIndex++;
            }

            offset += length;
            emit(mWriteIndex - DELAY_SAMPLES);
        }
    }

    /**
     * Delivers all audio remaining in the delay line (end of transmission) and resets the detector for the next one.
     */
    public void flush()
    {
        if(mActiveCut != null)
        {
            mActiveCut[1] = Math.min(Math.max(mActiveCut[1], mActiveCutMinimumEnd), mWriteIndex);
            mActiveCut = null;
        }

        emit(mWriteIndex);
        mCuts.clear();
        mStreamStart = mWriteIndex;
        resetDetector();
    }

    /**
     * Discards all buffered audio and resets.
     */
    public void reset()
    {
        mReadIndex = mWriteIndex;
        mStreamStart = mWriteIndex;
        mCuts.clear();
        mActiveCut = null;
        resetDetector();
    }

    private void resetDetector()
    {
        java.util.Arrays.fill(mLpfI, 0.0f);
        java.util.Arrays.fill(mLpfQ, 0.0f);
        java.util.Arrays.fill(mAverage, 0.0f);
        mAverageSum = 0.0f;
        mPreviousI = 0.0f;
        mPreviousQ = 0.0f;

        for(int k = 0; k < mClock.length; k++)
        {
            mClock[k] = k * (SAMPLE_RATE / mClock.length);
            mBit[k] = 0;
            mRegister[k] = 0;
        }

        mHp1x1 = mHp1x2 = mHp1y1 = mHp1y2 = 0.0f;
        mG1200s1 = mG1200s2 = mG1800s1 = mG1800s2 = mEnergy = 0.0f;
        mBlockCount = 0;
        java.util.Arrays.fill(mPurity, 0.0f);
        java.util.Arrays.fill(mBandEnergy, 0.0f);
        java.util.Arrays.fill(mWideEnergy, 0.0f);
        mBandAccumulator = mWideAccumulator = 0.0f;
        mBpx1 = mBpx2 = mBpy1 = mBpy2 = mHpx1 = mHpx2 = mHpy1 = mHpy2 = mLpx1 = mLpx2 = mLpy1 = mLpy2 = 0.0f;
    }

    /**
     * MDC-1200 sync detector.  Tracks the tone (1200 or 1800 Hz) with an FM discriminator centered at 1500 Hz, samples
     * it once per bit with 8 bit clocks at different phases, recovers the data bits (bit = previous bit XOR tone) and
     * compares the last 40 bits against the sync word and its inverse.
     */
    private void detect(float sample, long index)
    {
        //Mix down so 1200 Hz becomes -300 Hz and 1800 Hz becomes +300 Hz
        float i = sample * MIXER_COS[mMixerPointer];
        float q = -sample * MIXER_SIN[mMixerPointer];
        mMixerPointer = (mMixerPointer + 1) & 0xF;

        mLpfI[mLpfPointer] = i;
        mLpfQ[mLpfPointer] = q;
        float fi = 0.0f;
        float fq = 0.0f;
        int pointer = mLpfPointer;

        for(int x = 0; x < LPF_TAPS; x++)
        {
            fi += mLpfI[pointer] * LPF_COEFFICIENTS[x];
            fq += mLpfQ[pointer] * LPF_COEFFICIENTS[x];
            pointer = (pointer == 0) ? LPF_TAPS - 1 : pointer - 1;
        }

        mLpfPointer = (mLpfPointer + 1) % LPF_TAPS;

        //Discriminator: positive = above 1500 Hz (1800 Hz tone), negative = below (1200 Hz tone)
        float cross = (fq * mPreviousI) - (fi * mPreviousQ);
        float dot = (fi * mPreviousI) + (fq * mPreviousQ);
        mPreviousI = fi;
        mPreviousQ = fq;
        float frequency = (float)Math.atan2(cross, dot);

        //Average across one bit period
        mAverageSum += frequency - mAverage[mAveragePointer];
        mAverage[mAveragePointer] = frequency;
        mAveragePointer = (mAveragePointer + 1) % mAverage.length;

        for(int k = 0; k < mClock.length; k++)
        {
            mClock[k] += BAUD;

            if(mClock[k] >= SAMPLE_RATE)
            {
                mClock[k] -= SAMPLE_RATE;
                int tone = mAverageSum > 0 ? 1 : 0;
                mBit[k] ^= tone;
                mRegister[k] = ((mRegister[k] << 1) | mBit[k]) & MASK_40;

                int errors = Long.bitCount(mRegister[k] ^ SYNC);
                int inverseErrors = Long.bitCount(mRegister[k] ^ (SYNC ^ MASK_40));

                if(Math.min(errors, inverseErrors) <= MAXIMUM_SYNC_BIT_ERRORS)
                {
                    syncDetected(index);
                }
            }
        }
    }

    /**
     * Handles a sync word detection at the specified sample index (the sample that completed the sync word).
     */
    private void syncDetected(long index)
    {
        long syncStart = index - DETECTOR_DELAY - SYNC_SAMPLES;

        //Already cutting this packet (another bit clock phase found the same sync word)
        if(mActiveCut != null && syncStart <= mActiveCut[1])
        {
            return;
        }

        //Earliest start we can still cut.  Leave room for the fade on audio not yet delivered, except at the very start
        //of a transmission where there is nothing before the burst to fade.
        long earliest = (mReadIndex == mStreamStart) ? mStreamStart : mReadIndex + FADE_SAMPLES;
        earliest = Math.max(earliest, index - DELAY_SAMPLES + FADE_SAMPLES + BLOCK_SAMPLES);

        //Walk back over the preamble: 10 ms blocks before the sync word that are mostly 1200/1800 Hz tone.  A single
        //weak block (a short fade or dropout) doesn't end the preamble.
        long start = syncStart - MINIMUM_PREAMBLE_SAMPLES;
        long block = Math.floorDiv(syncStart, BLOCK_SAMPLES) - 1;
        int misses = 0;

        while(block >= 0 && (block + 1) * BLOCK_SAMPLES > earliest && block > mBlockCount - BLOCK_HISTORY)
        {
            if(mPurity[(int)(block % BLOCK_HISTORY)] >= PURITY_THRESHOLD)
            {
                start = Math.min(start, Math.max(block * BLOCK_SAMPLES, earliest));
                misses = 0;
            }
            else if(++misses > 1)
            {
                break;
            }

            block--;
        }

        start = Math.max(start - FADE_SAMPLES, earliest);

        //Don't overlap a previous cut
        if(!mCuts.isEmpty())
        {
            start = Math.max(start, mCuts.peekLast()[1]);
        }

        mFirstBlockEnd = syncStart + PACKET_SAMPLES;
        long end = mFirstBlockEnd + END_MARGIN_SAMPLES;
        mActiveCut = new long[]{start, end};
        mActiveCutMinimumEnd = end;
        mCuts.addLast(mActiveCut);
        mPacketsRemoved++;
    }

    /**
     * Measures how much of each 10 ms block is 1200/1800 Hz tone, and extends an active cut while the burst continues
     * (for example a second data block).
     */
    private void measurePurity(float sample, long index)
    {
        float y = HP_B[0] * sample + HP_B[1] * mHp1x1 + HP_B[2] * mHp1x2 - HP_A[0] * mHp1y1 - HP_A[1] * mHp1y2;
        mHp1x2 = mHp1x1;
        mHp1x1 = sample;
        mHp1y2 = mHp1y1;
        mHp1y1 = y;

        float s = y + G1200_COEFF * mG1200s1 - mG1200s2;
        mG1200s2 = mG1200s1;
        mG1200s1 = s;
        s = y + G1800_COEFF * mG1800s1 - mG1800s2;
        mG1800s2 = mG1800s1;
        mG1800s1 = s;
        mEnergy += y * y;

        float band = BAND_B[0] * sample + BAND_B[1] * mBpx1 + BAND_B[2] * mBpx2 - BAND_A[0] * mBpy1 - BAND_A[1] * mBpy2;
        mBpx2 = mBpx1;
        mBpx1 = sample;
        mBpy2 = mBpy1;
        mBpy1 = band;
        float wideHp = WIDE_HP_B[0] * sample + WIDE_HP_B[1] * mHpx1 + WIDE_HP_B[2] * mHpx2 - WIDE_HP_A[0] * mHpy1 -
            WIDE_HP_A[1] * mHpy2;
        mHpx2 = mHpx1;
        mHpx1 = sample;
        mHpy2 = mHpy1;
        mHpy1 = wideHp;
        float wide = WIDE_LP_B[0] * wideHp + WIDE_LP_B[1] * mLpx1 + WIDE_LP_B[2] * mLpx2 - WIDE_LP_A[0] * mLpy1 -
            WIDE_LP_A[1] * mLpy2;
        mLpx2 = mLpx1;
        mLpx1 = wideHp;
        mLpy2 = mLpy1;
        mLpy1 = wide;
        mBandAccumulator += band * band;
        mWideAccumulator += wide * wide;

        if(((index + 1) % BLOCK_SAMPLES) == 0)
        {
            float p1200 = mG1200s1 * mG1200s1 + mG1200s2 * mG1200s2 - G1200_COEFF * mG1200s1 * mG1200s2;
            float p1800 = mG1800s1 * mG1800s1 + mG1800s2 * mG1800s2 - G1800_COEFF * mG1800s1 * mG1800s2;
            float purity = (mEnergy > 1e-12f) ? (2.0f * (p1200 + p1800)) / (BLOCK_SAMPLES * mEnergy) : 0.0f;
            long block = index / BLOCK_SAMPLES;
            mPurity[(int)(block % BLOCK_HISTORY)] = purity;
            mBandEnergy[(int)(block % BLOCK_HISTORY)] = mBandAccumulator;
            mWideEnergy[(int)(block % BLOCK_HISTORY)] = mWideAccumulator;
            mBandAccumulator = mWideAccumulator = 0.0f;
            mBlockCount = (int)Math.min(Integer.MAX_VALUE, block + 1);
            mG1200s1 = mG1200s2 = mG1800s1 = mG1800s2 = mEnergy = 0.0f;

            if(mActiveCut != null)
            {
                //Some MDC messages carry a second 112-bit data block right after the first.  Once that stretch of
                //audio has been measured, extend the cut over it if it looks like MDC data, then finish the cut.
                long firstWindowBlock = Math.floorDiv(mFirstBlockEnd + BLOCK_SAMPLES - 1, BLOCK_SAMPLES);
                long lastWindowBlock = firstWindowBlock + SECOND_BLOCK_BLOCKS - 1;

                if(block >= lastWindowBlock)
                {
                    float bandSum = 0.0f;
                    float wideSum = 0.0f;
                    float packetWide = 0.0f;
                    int packetBlocks = 0;

                    for(long b = firstWindowBlock; b <= lastWindowBlock; b++)
                    {
                        bandSum += mBandEnergy[(int)(b % BLOCK_HISTORY)];
                        wideSum += mWideEnergy[(int)(b % BLOCK_HISTORY)];
                    }

                    for(long b = firstWindowBlock - SECOND_BLOCK_BLOCKS; b < firstWindowBlock; b++)
                    {
                        packetWide += mWideEnergy[(int)(b % BLOCK_HISTORY)];
                        packetBlocks++;
                    }

                    boolean loudEnough = packetBlocks > 0 && wideSum >= 0.25f * packetWide;

                    if(loudEnough && wideSum > 0.0f && (bandSum / wideSum) >= SECOND_BLOCK_BAND_RATIO)
                    {
                        mActiveCut[1] = mFirstBlockEnd + DATA_BLOCK_SAMPLES + END_MARGIN_SAMPLES;
                    }

                    mActiveCut = null;
                }
            }
        }
    }

    /**
     * Sends delayed audio up to (not including) the target index, leaving out cut sections and fading around them.
     */
    private void emit(long target)
    {
        if(target <= mReadIndex)
        {
            return;
        }

        float[] output = new float[(int)(target - mReadIndex)];
        int count = 0;

        for(long x = mReadIndex; x < target; x++)
        {
            float gain = 1.0f;
            boolean cut = false;

            for(long[] c : mCuts)
            {
                if(x >= c[0] && x < c[1])
                {
                    cut = true;
                    break;
                }

                if(x < c[0] && x >= c[0] - FADE_SAMPLES)
                {
                    gain = Math.min(gain, (float)(c[0] - x) / FADE_SAMPLES);
                }
                else if(x >= c[1] && x < c[1] + FADE_SAMPLES && c != mActiveCut)
                {
                    gain = Math.min(gain, (float)(x - c[1] + 1) / FADE_SAMPLES);
                }
            }

            if(!cut)
            {
                output[count++] = mRing[(int)(x & RING_MASK)] * gain;
            }
        }

        mReadIndex = target;

        //Drop cuts that are fully behind the read pointer
        Iterator<long[]> it = mCuts.iterator();

        while(it.hasNext())
        {
            long[] c = it.next();

            if(c != mActiveCut && c[1] + FADE_SAMPLES < mReadIndex)
            {
                it.remove();
            }
        }

        if(count > 0 && mOutputListener != null)
        {
            if(count < output.length)
            {
                output = java.util.Arrays.copyOf(output, count);
            }

            mOutputListener.receive(output);
        }
    }

    /**
     * Windowed-sinc low-pass filter (Hamming window).
     */
    private static float[] createLowPass(int taps, double cutoff)
    {
        float[] coefficients = new float[taps];
        double fc = cutoff / SAMPLE_RATE;
        int middle = (taps - 1) / 2;
        double sum = 0.0;

        for(int x = 0; x < taps; x++)
        {
            int n = x - middle;
            double sinc = (n == 0) ? 2.0 * fc : Math.sin(2.0 * Math.PI * fc * n) / (Math.PI * n);
            double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * x / (taps - 1));
            coefficients[x] = (float)(sinc * window);
            sum += coefficients[x];
        }

        for(int x = 0; x < taps; x++)
        {
            coefficients[x] /= (float)sum;
        }

        return coefficients;
    }
}
