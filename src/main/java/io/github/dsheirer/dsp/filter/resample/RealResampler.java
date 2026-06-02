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
    protected static final Logger mLog = LoggerFactory.getLogger(RealResampler.class);

    private Resampler mResampler;
    private Listener<float[]> mResampledListener;
    private BufferManager mBufferManager;
    private double mResampleFactor;
    private boolean mLastBatch;

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
     * Defensively chunks the incoming sample array so it can never overflow the
     * fixed-size input FloatBuffer in BufferManager.  Without chunking, an
     * upstream caller (e.g. NBFMDecoder's NoiseSquelch listener during a long
     * unsquelched transmission) could hand us a samples[] larger than the
     * remaining input-buffer capacity, and the bare mInputBuffer.put(samples)
     * inside load() would throw BufferOverflowException -- silently stalling
     * the NBFM audio path because the resampler stops producing output.
     *
     * The loop loads at most "remaining capacity" floats at a time, then calls
     * the underlying resampler so it drains what it can from the input buffer
     * before the next load.  lastBatch=true is propagated only on the FINAL
     * chunk so the output-side flush still happens at the right moment.
     *
     * @param samples to resample
     * @param lastBatch set to true if this is the last set of samples
     */
    public void resample(float[] samples, boolean lastBatch)
    {
        // store state of lastBatch for buffer flushing in consumeOutput below
        mLastBatch = lastBatch;

        if(samples == null || samples.length == 0)
        {
            if(lastBatch)
            {
                //Allow the resampler to flush even when handed an empty trailing batch.
                mResampler.process(mResampleFactor, mBufferManager, true);
            }
            return;
        }

        int offset = 0;
        int total  = samples.length;

        while(offset < total)
        {
            int free = mBufferManager.getInputBufferRemaining();
            if(free <= 0)
            {
                //The input buffer is full and the underlying resampler couldn't
                //drain it on the previous iteration.  This should not happen with
                //libresample4j under normal operation -- it always consumes the
                //majority of available input and leaves only a small interpolation
                //residual.  If we ever see it, log loudly and drop the rest of
                //this batch rather than spin forever.
                mLog.warn("RealResampler input buffer wedged ({} samples leftover, 0 free) -- dropping {} of {} input samples to recover",
                          mBufferManager.getInputBufferLength(), total - offset, total);
                break;
            }

            int chunk = Math.min(free, total - offset);
            boolean isFinalChunk = (offset + chunk == total);
            //Only the very last chunk carries lastBatch -- earlier chunks must
            //not trigger the output-flush logic.
            boolean chunkLastBatch = lastBatch && isFinalChunk;

            mBufferManager.load(samples, offset, chunk);
            //Re-store mLastBatch right before process() because the inner
            //consumeOutput() callback reads it -- earlier chunks must see false
            //to suppress the flush, only the final chunk sees the caller's value.
            mLastBatch = chunkLastBatch;
            mResampler.process(mResampleFactor, mBufferManager, chunkLastBatch);

            offset += chunk;
        }
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
         * Queues the buffer samples for resampling.
         *
         * NOTE: callers should prefer load(float[], int, int) or chunk through
         * RealResampler.resample(float[], boolean), both of which respect the
         * input buffer's remaining capacity.  This bare overload is kept for
         * source compatibility and will throw BufferOverflowException if the
         * supplied samples.length exceeds mInputBuffer.remaining().
         */
        public void load(float[] samples)
        {
            mInputBuffer.put(samples);
        }

        /**
         * Queues a slice of the supplied samples array for resampling.  Used by
         * RealResampler.resample() to feed the input buffer in chunks that
         * always fit, avoiding the input-side BufferOverflowException that
         * could otherwise mute the NBFM audio path mid-transmission.
         *
         * @param samples backing array
         * @param offset start index into samples
         * @param length number of floats to copy (must be &lt;= getInputBufferRemaining())
         */
        public void load(float[] samples, int offset, int length)
        {
            mInputBuffer.put(samples, offset, length);
        }

        /**
         * Number of float slots still available in the input buffer before
         * another put() would overflow.  Used by RealResampler.resample() to
         * size each load so the bare FloatBuffer.put() can never throw.
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

            while(mOutputBuffer.position() > mOutputArrayLength)
            {
                float[] resampled = new float[mOutputArrayLength];

                mOutputBuffer.flip();
                mOutputBuffer.get(resampled);
                mOutputBuffer.compact();

                if(mResampledListener != null)
                {
                    mResampledListener.receive(resampled);
                }
            }
            // if this is the last batch of audio being processed by the resampler and there are still
            //  samples remaining in the buffer, pad the block with zeros to make
            //  mOutputArrayLength (usually 512) samples.
            if(mLastBatch && mOutputBuffer.position() != 0)
            {
                float[] resampled = new float[mOutputArrayLength];
                mOutputBuffer.flip();       // sets limit to remaining array length
                mOutputBuffer.get(resampled, 0, mOutputBuffer.limit());     // unused are already zeroed, for padding
                mOutputBuffer.compact();

                if (mResampledListener != null)
                {
                    mResampledListener.receive(resampled);
                }
            }
        }
    }
}
