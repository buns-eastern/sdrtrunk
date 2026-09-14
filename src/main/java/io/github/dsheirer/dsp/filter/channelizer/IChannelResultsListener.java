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

package io.github.dsheirer.dsp.filter.channelizer;

import java.util.List;

/**
 * Listener for raw polyphase channelizer output.  Each float[] in the list is one output sample across all
 * channels, interleaved I/Q: channel n is at [2n] (I) and [2n+1] (Q).  Arrays are shared with the channel sources
 * and must be treated as read-only.  Invoked on the channelizer's IFFT dispatcher thread - keep processing cheap.
 */
public interface IChannelResultsListener
{
    /**
     * Receive a batch of channelizer output samples.
     * @param channelResults list of interleaved per-sample channel results
     * @param channelSampleRate output sample rate of each channel in Hertz
     * @param timestamp of the first sample in the batch
     */
    void receiveChannelResults(List<float[]> channelResults, double channelSampleRate, long timestamp);
}
