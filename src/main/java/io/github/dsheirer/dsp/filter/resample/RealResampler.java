/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.dsp.filter.resample;

import com.laszlosystems.libresample4j.Resampler;
import com.laszlosystems.libresample4j.SampleBuffers;
import io.github.dsheirer.sample.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Real sample buffer resampler
 */
public class RealResampler
{
    private static final Logger mLog = LoggerFactory.getLogger(RealResampler.class);

    private Resampler mResampler;
    private Listener<float[]> mResampledListener;
    private BufferManager mBufferManager;
    private double mResampleFactor;

    /**
     * Constructs an instance.
     * @param inputRate sample rate
     * @param outputRate sample rate
     * @param bufferSize for the input and output float buffers sized large enough to hold at least two of the
     * input or output buffer lengths, whichever is larger
     * @param outputArrayLength to create uniform length output arrays
     */
    public RealResampler(double inputRate, double outputRate, int bufferSize, int outputArrayLength)
    {
        mResampleFactor = outputRate / inputRate;
        mResampler = new Resampler(true, mResampleFactor, mResampleFactor);
        mBufferManager = new BufferManager(bufferSize, outputArrayLength);
    }

    /**
     * Resample factor (output rate / input rate)
     */
    public double getResampleFactor()
    {
        return mResampleFactor;
    }

    /**
     * Resamples all of the audio packets
     * @param audioPackets to resample
     * @return resampled audio packets
     */
    public List<float[]> resample(List<float[]> audioPackets)
    {
        List<float[]> resampled = new ArrayList<>();

        mResampledListener = resampledAudio -> resampled.add(resampledAudio);

        for(int x = 0; x < audioPackets.size(); x++)
        {
            if(x == audioPackets.size() - 1)
            {
                resample(audioPackets.get(x), true);
            }
            else
            {
                resample(audioPackets.get(x));
            }
        }

        mResampledListener = null;
        return resampled;
    }

    /**
     * Primary input method to the resampler
     * @param samples to resample
     */
    public void resample(float[] samples)
    {
        resample(samples, false);
    }

    /**
     * Primary input method to the resampler.
     *
     * Loads the incoming samples in chunks that fit the remaining space in the fixed-size input buffer, processing
     * each chunk before loading the next.  A caller can hand over more samples than the input buffer has room for (for
     * example a long unsquelched NBFM transmission) and loading them all at once would overflow the buffer and stop
     * the resampler from producing audio.  Only the final chunk is processed as the last batch.
     *
     * @param samples to resample
     * @param lastBatch set to true if this is the last set of samples
     */
    public void resample(float[] samples, boolean lastBatch)
    {
        int total = samples != null ? samples.length : 0;
        int offset = 0;

        if(total == 0)
        {
            mResampler.process(mResampleFactor, mBufferManager, lastBatch);
        }

        while(offset < total)
        {
            int free = mBufferManager.getInputBufferRemaining();

            if(free <= 0)
            {
                //The resampler normally consumes nearly all of the input, so this shouldn't happen.  Drop the rest of
                //this batch rather than loop forever.
                mLog.warn("RealResampler input buffer full - dropping " + (total - offset) + " of " + total +
                    " input samples to recover");
                break;
            }

            int chunk = Math.min(free, total - offset);
            mBufferManager.load(samples, offset, chunk);
            mResampler.process(mResampleFactor, mBufferManager, lastBatch && (offset + chunk == total));
            offset += chunk;
        }

        if(lastBatch)
        {
            mBufferManager.flushPartialOutput();
        }
    }

    /**
     * Flushes any buffered resampler output for the current stream.
     */
    public void flush()
    {
        resample(new float[0], true);
    }

    /**
     * Registers the listener to receive the resampled output buffers
     * @param listener to receive buffers
     */
    public void setListener(Listener<float[]> listener)
    {
        mResampledListener = listener;
    }

    /**
     * Buffer manager for input and output buffers used during the resampling process.
     */
    public class BufferManager implements SampleBuffers
    {
        private int mOutputArrayLength;
        private FloatBuffer mInputBuffer;
        private FloatBuffer mOutputBuffer;

        /**
         * Constructs an instance
         * @param bufferSize for the input and output float buffers
         * @param outputArrayLength to create uniform length output sample arrays
         */
        public BufferManager(int bufferSize, int outputArrayLength)
        {
            mOutputArrayLength = outputArrayLength;

            //The size of the underlying byte buffer in bytes is 4 times the requested input buffer size in floats
            mInputBuffer = ByteBuffer.allocate(bufferSize * 4).asFloatBuffer();
            mOutputBuffer = ByteBuffer.allocate(bufferSize * 4).asFloatBuffer();
        }

        /**
         * Queues the buffer samples for resampling
         */
        public void load(float[] samples)
        {
            mInputBuffer.put(samples);
        }

        /**
         * Loads part of the samples array into the input buffer.
         * @param samples array
         * @param offset of the first sample to load
         * @param length number of samples to load (no more than getInputBufferRemaining())
         */
        public void load(float[] samples, int offset, int length)
        {
            mInputBuffer.put(samples, offset, length);
        }

        /**
         * Space remaining in the input buffer, in samples.
         */
        public int getInputBufferRemaining()
        {
            return mInputBuffer.remaining();
        }

        @Override
        public int getInputBufferLength()
        {
            return mInputBuffer.position();
        }

        @Override
        public int getOutputBufferLength()
        {
            return mOutputBuffer.remaining();
        }

        /**
         * Provides input to the resampler
         */
        @Override
        public void produceInput(float[] samples, int offset, int length)
        {
            mInputBuffer.flip();
            mInputBuffer.get(samples, offset, length);
            mInputBuffer.compact();
        }

        /**
         * Receives output from the resampler and dispatches output buffers periodically.
         */
        @Override
        public void consumeOutput(float[] samples, int offset, int length)
        {
            mOutputBuffer.put(samples, offset, length);
            dispatchFullOutputBuffers();
        }

        /**
         * Dispatches any complete resampled output buffers.
         */
        private void dispatchFullOutputBuffers()
        {
            while(mOutputBuffer.position() > mOutputArrayLength)
            {
                dispatchOutputBuffer(mOutputArrayLength);
            }
        }

        /**
         * Flushes any remaining partial output by zero-padding to a full output buffer length.
         */
        public void flushPartialOutput()
        {
            if(mOutputBuffer.position() > 0)
            {
                dispatchOutputBuffer(mOutputBuffer.position());
            }
        }

        /**
         * Dispatches the requested number of queued output samples, zero-padding the remainder of the fixed-size
         * output block when fewer than {@code mOutputArrayLength} samples are available.
         */
        private void dispatchOutputBuffer(int sampleCount)
        {
            float[] resampled = new float[mOutputArrayLength];

            mOutputBuffer.flip();
            mOutputBuffer.get(resampled, 0, sampleCount);
            mOutputBuffer.compact();

            if(mResampledListener != null)
            {
                mResampledListener.receive(resampled);
            }
        }
    }
}
