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
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.StreamHeartbeatEntry;
import io.github.dsheirer.util.ThreadPool;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the live health of each configured audio streaming feed to an Uptime Kuma push monitor.
 *
 * Behaviour (per stream, all independent):
 *  - CONNECTED  -> push "up" every Interval seconds.  This steady heartbeat is also the backstop: if SDRTrunk
 *                  itself stops, all monitors go silent and Kuma alerts.
 *  - fault      -> after the fault persists continuously for the stream's "Down after" seconds, push "down"
 *                  with the exact BroadcastState label as the reason.  A shorter blip is absorbed (stays "up").
 *  - recovery   -> once DOWN, the stream must hold CONNECTED continuously for "Up after" seconds before "up"
 *                  is reported.  This debounces a feed that flaps up/down/up.
 *  - DISABLED   -> reported "up" (paused) so an intentionally disabled stream never alerts.
 *
 * All work runs off the audio path on a scheduled thread and can never throw into streaming.  Reads of the
 * broadcaster state are lock-free snapshots; HTTP pushes fire on virtual threads.
 */
public class StreamHeartbeatManager
{
    private static final Logger mLog = LoggerFactory.getLogger(StreamHeartbeatManager.class);
    private static final long TICK_SECONDS = 5;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final UserPreferences mUserPreferences;
    private final BroadcastModel mBroadcastModel;
    private final Map<String,RuntimeState> mRuntime = new HashMap<>();
    private ScheduledFuture<?> mFuture;

    /**
     * Constructs an instance.
     * @param userPreferences source of the streaming heartbeat entries
     * @param broadcastModel source of live per-stream broadcast state
     */
    public StreamHeartbeatManager(UserPreferences userPreferences, BroadcastModel broadcastModel)
    {
        mUserPreferences = userPreferences;
        mBroadcastModel = broadcastModel;
    }

    /**
     * Begins the periodic evaluation loop.
     */
    public void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS,
                TimeUnit.SECONDS);
            mLog.info("Streaming heartbeat monitor started");
        }
    }

    /**
     * Stops the periodic evaluation loop.
     */
    public void stop()
    {
        if(mFuture != null)
        {
            mFuture.cancel(true);
            mFuture = null;
        }
    }

    /**
     * Scheduled entry point - guarded so nothing can ever propagate out of the monitor.
     */
    private void tick()
    {
        try
        {
            evaluate();
        }
        catch(Throwable t)
        {
            mLog.error("Streaming heartbeat monitor tick failed (continuing)", t);
        }
    }

    private void evaluate()
    {
        if(mUserPreferences == null || mBroadcastModel == null)
        {
            return;
        }

        List<StreamHeartbeatEntry> entries = mUserPreferences.getStreamHeartbeatPreference().getEntries();

        if(entries.isEmpty())
        {
            return;
        }

        Map<String,BroadcastState> stateByName = snapshotStates();
        long now = System.currentTimeMillis();
        Set<String> active = new HashSet<>();

        for(StreamHeartbeatEntry entry: entries)
        {
            if(!entry.isEnabled() || entry.getKumaUrl().isBlank())
            {
                continue;
            }

            String name = entry.getStreamName();
            BroadcastState state = stateByName.get(name);

            //Stream not present in the active playlist (removed or reloading) - skip rather than false-alarm
            if(state == null)
            {
                continue;
            }

            active.add(name);
            RuntimeState rs = mRuntime.computeIfAbsent(name, k -> new RuntimeState());
            evaluateStream(entry, state, rs, now);
        }

        //Drop runtime state for streams no longer monitored so the map cannot grow without bound
        mRuntime.keySet().retainAll(active);
    }

    /**
     * Builds a lock-free snapshot of the current broadcast state for every configured stream.
     */
    private Map<String,BroadcastState> snapshotStates()
    {
        Map<String,BroadcastState> map = new HashMap<>();

        for(ConfiguredBroadcast cb: mBroadcastModel.getConfiguredBroadcasts())
        {
            try
            {
                String name = cb.getBroadcastConfiguration().getName();
                BroadcastState state = cb.broadcastStateProperty().get();

                if(name != null && state != null)
                {
                    map.put(name, state);
                }
            }
            catch(Throwable t)
            {
                //Ignore a single unreadable entry
            }
        }

        return map;
    }

    /**
     * Applies the up/down hysteresis state machine to a single stream and pushes when required.
     */
    private void evaluateStream(StreamHeartbeatEntry entry, BroadcastState state, RuntimeState rs, long now)
    {
        long intervalMs = Math.max(1, entry.getIntervalSeconds()) * 1000L;
        long downAfterMs = Math.max(0, entry.getDownAfterSeconds()) * 1000L;
        long upAfterMs = Math.max(0, entry.getUpAfterSeconds()) * 1000L;

        boolean desiredDown;
        String message;

        if(state == BroadcastState.DISABLED)
        {
            rs.faultSinceMs = 0;
            rs.healthySinceMs = 0;
            desiredDown = false;
            message = "Paused (stream disabled)";
        }
        else if(state == BroadcastState.CONNECTED)
        {
            if(rs.healthySinceMs == 0)
            {
                rs.healthySinceMs = now;
            }
            rs.faultSinceMs = 0;

            if(rs.reportedDown)
            {
                long stableMs = now - rs.healthySinceMs;

                if(stableMs >= upAfterMs)
                {
                    desiredDown = false;
                    message = "Recovered";
                }
                else
                {
                    //Hold red until the feed proves it is stable for the up-after window
                    desiredDown = true;
                    message = "Recovering (stable " + (stableMs / 1000) + "s / " + entry.getUpAfterSeconds() + "s)";
                }
            }
            else
            {
                desiredDown = false;
                message = "Connected";
            }
        }
        else
        {
            if(rs.faultSinceMs == 0)
            {
                rs.faultSinceMs = now;
            }
            rs.healthySinceMs = 0;

            if(rs.reportedDown)
            {
                desiredDown = true;
                message = state.toString();
            }
            else
            {
                long downMs = now - rs.faultSinceMs;

                if(downMs >= downAfterMs)
                {
                    desiredDown = true;
                    message = state.toString() + " (down " + (downMs / 1000) + "s)";
                }
                else
                {
                    //Inside the down-after grace window - keep the monitor green, absorbing the blip
                    desiredDown = false;
                    message = "Degraded: " + state.toString();
                }
            }
        }

        boolean transition = (desiredDown != rs.reportedDown);

        if(transition || (now - rs.lastPushMs) >= intervalMs)
        {
            push(entry.getKumaUrl(), desiredDown ? "down" : "up", message, entry.getStreamName());
            rs.reportedDown = desiredDown;
            rs.lastPushMs = now;
        }
    }

    /**
     * Builds the Kuma push URL with status and message parameters and fires it on a virtual thread.
     */
    private void push(String baseUrl, String status, String message, String streamName)
    {
        //Use only the base push URL - the Kuma token lives in the path.  Strip any query the user pasted from
        //Kuma's example (?status=up&msg=OK&ping=) so our parameters are not duplicated: duplicate query keys make
        //Kuma parse status as an array, which never equals "up" and leaves the monitor stuck down.
        String trimmed = baseUrl.trim();
        int query = trimmed.indexOf('?');
        String base = (query >= 0) ? trimmed.substring(0, query) : trimmed;

        String url = base + "?status=" + status
                + "&msg=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&ping=";

        Thread.ofVirtual().start(() -> fireGet(url, streamName));
    }

    private void fireGet(String url, String streamName)
    {
        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();

            if(code < 200 || code >= 300)
            {
                mLog.warn("Streaming heartbeat push for [{}] -> HTTP {}", streamName, code);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Streaming heartbeat push failed for [{}]: {}", streamName, e.getMessage());
        }
    }

    /**
     * Per-stream runtime tracking for the hysteresis state machine.
     */
    private static class RuntimeState
    {
        private long faultSinceMs = 0;
        private long healthySinceMs = 0;
        private boolean reportedDown = false;
        private long lastPushMs = 0;
    }
}
