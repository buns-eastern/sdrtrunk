/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.dsp.am;

import java.util.Arrays;

/**
 * Levels demodulated AM audio so that weak and strong transmissions come out at the same loudness, and applies a
 * user output gain.
 *
 * The AM demodulator output is the signal envelope: a carrier level with the audio riding on top of it.  Both scale
 * with received signal strength, so a fixed gain leaves weak signals quiet and drives strong signals into clipping.
 * When auto level is enabled, this class tracks the carrier level and divides the envelope by it.  What remains is
 * the modulation alone, where full (100%) modulation is full scale regardless of how strong the signal was received.
 * The output gain is then applied and peaks are soft limited so that boosted audio saturates smoothly.
 *
 * Samples that were muted (zeroed) by the squelch re-arm the carrier tracker so that each transmission is levelled
 * independently.
 *
 * Levelling also raises the noise that follows the end of a transmission, between the moment the signal drops and
 * the moment the squelch closes, to full loudness.  When auto level is enabled the most recent audio is held back
 * and is discarded when the squelch closes, which trims that squelch tail.  In this mode squelched samples produce
 * no output, so the returned array holds only the audio that has been released and can be shorter than the input.
 *
 * When auto level is disabled the envelope is only scaled by the output gain, and with unity gain the samples are
 * passed through untouched.
 */
public class AmAudioLeveler
{
    public static final float MINIMUM_OUTPUT_GAIN = 0.1f;
    public static final float MAXIMUM_OUTPUT_GAIN = 10.0f;
    public static final float DEFAULT_OUTPUT_GAIN = 1.0f;

    //Carrier tracker time constants.  Both are slow relative to voice so the tracker follows the carrier and fading
    //but not the audio.  The faster one is used briefly at the start of each transmission to settle quickly.
    private static final double SETTLE_TIME_CONSTANT_SECONDS = 0.005;
    private static final double TRACK_TIME_CONSTANT_SECONDS = 0.040;
    private static final double SETTLE_DURATION_SECONDS = 0.020;
    private static final float MINIMUM_CARRIER = 1.0e-6f;

    //Length of audio that is held back and discarded when the squelch closes.
    private static final double TAIL_TRIM_SECONDS = 0.100;

    //Soft limiter: linear below the knee, smoothly saturating toward the ceiling above it.
    private static final float LIMITER_KNEE = 0.6f;
    private static final float LIMITER_CEILING = 0.95f;
    private static final float LIMITER_RANGE = LIMITER_CEILING - LIMITER_KNEE;

    private final boolean mAutoLevel;
    private final float mOutputGain;
    private float mSettleAlpha;
    private float mTrackAlpha;
    private int mSettleSampleCount;
    private int mSettleRemaining;
    private float mCarrier;
    private boolean mActive;
    private float[] mHeld = new float[1];
    private int mHeldCount;
    private int mHeldIndex;

    /**
     * Constructs an instance
     * @param autoLevel true to normalize each transmission to the same loudness
     * @param outputGain linear gain applied to the audio, constrained to the supported range.
     */
    public AmAudioLeveler(boolean autoLevel, float outputGain)
    {
        mAutoLevel = autoLevel;
        mOutputGain = constrain(outputGain);
        setSampleRate(25000.0);
    }

    /**
     * Constrains the output gain to the supported range, using the default for an unusable value.
     */
    public static float constrain(float outputGain)
    {
        if(Float.isNaN(outputGain))
        {
            return DEFAULT_OUTPUT_GAIN;
        }

        return Math.max(MINIMUM_OUTPUT_GAIN, Math.min(MAXIMUM_OUTPUT_GAIN, outputGain));
    }

    /**
     * Sets the sample rate of the demodulated samples so that the carrier tracker timing is correct.
     * @param sampleRate in hertz
     */
    public void setSampleRate(double sampleRate)
    {
        if(sampleRate > 0.0)
        {
            mSettleAlpha = (float)(1.0 - Math.exp(-1.0 / (SETTLE_TIME_CONSTANT_SECONDS * sampleRate)));
            mTrackAlpha = (float)(1.0 - Math.exp(-1.0 / (TRACK_TIME_CONSTANT_SECONDS * sampleRate)));
            mSettleSampleCount = (int)(SETTLE_DURATION_SECONDS * sampleRate);
            mHeld = new float[Math.max(1, (int)(TAIL_TRIM_SECONDS * sampleRate))];
        }

        reset();
    }

    /**
     * Resets the carrier tracker.
     */
    public void reset()
    {
        mActive = false;
        mCarrier = 0.0f;
        mSettleRemaining = 0;
        mHeldCount = 0;
        mHeldIndex = 0;
    }

    /**
     * Indicates if the squelch tail is being trimmed.  When true, the audio returned from process() excludes
     * squelched samples and the trimmed tail and can be shorter than the input, including empty.
     */
    public boolean isTrimmingTail()
    {
        return mAutoLevel;
    }

    /**
     * Indicates if this leveler changes the samples, or passes them through untouched.
     */
    public boolean isPassThrough()
    {
        return !mAutoLevel && mOutputGain == 1.0f;
    }

    /**
     * Processes a buffer of demodulated AM (envelope) samples.
     * @param envelope samples from the demodulator, where squelched samples are zero
     * @return processed audio samples.  The original array is returned when there is nothing to do.  When the
     * squelch tail is being trimmed this is only the audio that has been released, which can be an empty array.
     */
    public float[] process(float[] envelope)
    {
        if(isPassThrough())
        {
            return envelope;
        }

        float[] audio = new float[envelope.length];

        if(!mAutoLevel)
        {
            for(int x = 0; x < envelope.length; x++)
            {
                audio[x] = envelope[x] * mOutputGain;
            }

            return audio;
        }

        float sample;
        float levelled;
        int released = 0;

        for(int x = 0; x < envelope.length; x++)
        {
            sample = envelope[x];

            //Squelched (muted) or unusable sample - the transmission has ended.  Discard the held audio, which is
            //the squelch tail, and re-arm for the next transmission.
            if(!(sample > 0.0f) || Float.isInfinite(sample))
            {
                mActive = false;
                mHeldCount = 0;
                mHeldIndex = 0;
                continue;
            }

            if(mActive)
            {
                if(mSettleRemaining > 0)
                {
                    mSettleRemaining--;
                    mCarrier += (sample - mCarrier) * mSettleAlpha;
                }
                else
                {
                    mCarrier += (sample - mCarrier) * mTrackAlpha;
                }
            }
            else
            {
                mActive = true;
                mCarrier = sample;
                mSettleRemaining = mSettleSampleCount;
            }

            levelled = limit(((sample / Math.max(mCarrier, MINIMUM_CARRIER)) - 1.0f) * mOutputGain);

            //Hold back the most recent audio and release the oldest once the hold is full.
            if(mHeldCount < mHeld.length)
            {
                mHeld[mHeldCount++] = levelled;
            }
            else
            {
                audio[released++] = mHeld[mHeldIndex];
                mHeld[mHeldIndex] = levelled;
                mHeldIndex = (mHeldIndex + 1) % mHeld.length;
            }
        }

        return released == audio.length ? audio : Arrays.copyOf(audio, released);
    }

    /**
     * Soft limits the sample so that peaks saturate smoothly below full scale instead of hard clipping.
     */
    private static float limit(float sample)
    {
        float magnitude = Math.abs(sample);

        if(magnitude <= LIMITER_KNEE)
        {
            return sample;
        }

        float limited = LIMITER_KNEE + (LIMITER_RANGE * (float)Math.tanh((magnitude - LIMITER_KNEE) / LIMITER_RANGE));
        return sample < 0.0f ? -limited : limited;
    }
}
