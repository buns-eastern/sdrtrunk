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
package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.module.Module;
import io.github.dsheirer.util.ThreadPool;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits liveness heartbeats for a standalone (non-trunking) channel onto the StandaloneChannelStreamManager
 * stream (default port 9504) while the channel is running.
 *
 * The processing-chain lifecycle is the "is this channel playing?" signal: start() is called when the
 * channel is enabled/played and stop() when it is disabled.  So:
 *   - start() emits a channel_up immediately, then a heartbeat every interval
 *   - stop()  emits a channel_down
 *
 * A downstream consumer tapping the stream tracks each channel by its "channel" field: heartbeats ticking =
 * up, ticks stopped / channel_down = down.  All emission is null-safe and wrapped so the heartbeat can never
 * affect channel processing.
 */
public class ChannelHeartbeatModule extends Module
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelHeartbeatModule.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String mChannelName;
    private final long mIntervalSeconds;
    private ScheduledFuture<?> mFuture;

    /**
     * Constructs an instance.
     * @param channelName descriptive channel name reported on the stream
     * @param intervalSeconds seconds between successive heartbeats while running
     */
    public ChannelHeartbeatModule(String channelName, int intervalSeconds)
    {
        mChannelName = channelName == null ? "" : channelName;
        mIntervalSeconds = Math.max(1, intervalSeconds);
    }

    @Override
    public void start()
    {
        //Announce the channel is up, then tick a heartbeat on the configured interval while it runs.
        emit("channel_up", null);

        try
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> emit("heartbeat", "up"),
                    mIntervalSeconds, mIntervalSeconds, TimeUnit.SECONDS);
        }
        catch(Exception e)
        {
            mLog.warn("Unable to schedule standalone channel heartbeat for [{}]: {}", mChannelName, e.getMessage());
        }
    }

    @Override
    public void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(false);
            mFuture = null;
        }

        //Announce the channel is down so consumers can mark it offline immediately.
        emit("channel_down", null);
    }

    @Override
    public void reset()
    {
        //No state to reset.
    }

    /**
     * Broadcasts a heartbeat message to the standalone channel stream, if the stream is running.
     * Wrapped so any failure can never affect channel processing.
     * @param type message type (channel_up / heartbeat / channel_down)
     * @param status optional status value (may be null)
     */
    private void emit(String type, String status)
    {
        try
        {
            StandaloneChannelStreamManager mgr = StandaloneChannelStreamManager.getInstance();
            if(mgr == null || !mgr.isRunning())
            {
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"").append(escape(type)).append("\"");
            sb.append(",\"channel\":\"").append(escape(mChannelName)).append("\"");
            if(status != null)
            {
                sb.append(",\"status\":\"").append(escape(status)).append("\"");
            }
            sb.append(",\"timestamp\":\"").append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\"}");

            mgr.broadcast(sb.toString());
        }
        catch(Exception e)
        {
            mLog.warn("Standalone channel heartbeat emit error for [{}]: {}", mChannelName, e.getMessage());
        }
    }

    private static String escape(String s)
    {
        if(s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
    }
}
