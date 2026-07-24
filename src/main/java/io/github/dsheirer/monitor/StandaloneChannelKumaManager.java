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
package io.github.dsheirer.monitor;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.KumaChannelMonitorEntry;
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
 * Per-channel liveness monitors for the Standalone Channel Heartbeat feature.  For each configured monitor
 * whose channel is currently running (its processing chain is started), this pushes an up heartbeat to that
 * monitor's URL on its own interval, so a push-style uptime monitor (for example Uptime Kuma) stays up while
 * the channel runs and goes down when the channel or SDRTrunk stops.  It tracks running, not received traffic,
 * so a quiet channel still counts as up.
 *
 * This is a single application-wide scheduled monitor -- the same proven pattern as the software heartbeat --
 * rather than anything attached to the decode chain, and all network activity runs off a virtual thread with
 * every failure swallowed so it can never affect channel processing.
 */
public class StandaloneChannelKumaManager
{
    private static final Logger mLog = LoggerFactory.getLogger(StandaloneChannelKumaManager.class);
    private static final long TICK_SECONDS = 5;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final UserPreferences mUserPreferences;
    private final ChannelModel mChannelModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final Map<String,Long> mLastPushMs = new HashMap<>();
    private final Set<String> mLoggedOk = new HashSet<>();
    private ScheduledFuture<?> mFuture;

    public StandaloneChannelKumaManager(UserPreferences userPreferences, ChannelModel channelModel,
            ChannelProcessingManager channelProcessingManager)
    {
        mUserPreferences = userPreferences;
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
    }

    public void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS,
                    TimeUnit.SECONDS);
            mLog.info("Standalone channel Kuma monitor started");
        }
    }

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
            mLog.error("Standalone channel Kuma monitor tick failed (continuing)", t);
        }
    }

    private void evaluate()
    {
        if(mUserPreferences == null || mChannelModel == null || mChannelProcessingManager == null)
        {
            return;
        }

        List<KumaChannelMonitorEntry> monitors = mUserPreferences.getStandaloneStreamPreference().getKumaMonitors();

        if(monitors.isEmpty())
        {
            mLastPushMs.clear();
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> stillConfigured = new HashSet<>();

        for(KumaChannelMonitorEntry monitor: monitors)
        {
            if(monitor.getUrl().isBlank())
            {
                continue;
            }

            String key = monitor.getChannelName().toLowerCase() + "|" + monitor.getSystem().toLowerCase();
            stillConfigured.add(key);

            if(!isChannelRunning(monitor))
            {
                //Channel not running -> stop pushing so the monitor goes down, and reset so it pushes again
                //immediately when the channel comes back.
                mLastPushMs.remove(key);
                continue;
            }

            long intervalMs = Math.max(1, monitor.getIntervalSeconds()) * 1000L;
            long last = mLastPushMs.getOrDefault(key, 0L);

            if((now - last) >= intervalMs)
            {
                mLastPushMs.put(key, now);
                fireKuma(monitor.getUrl(), monitor.getChannelName());
            }
        }

        mLastPushMs.keySet().retainAll(stillConfigured);
    }

    /**
     * Returns true if a channel matching the monitor (by name, and by system when the monitor specifies one) is
     * currently processing.
     */
    private boolean isChannelRunning(KumaChannelMonitorEntry monitor)
    {
        for(Channel channel: mChannelModel.getChannels())
        {
            String name = channel.getName();

            if(name == null || !name.equalsIgnoreCase(monitor.getChannelName()))
            {
                continue;
            }

            if(!monitor.getSystem().isBlank() && channel.getSystem() != null
                    && !channel.getSystem().equalsIgnoreCase(monitor.getSystem()))
            {
                continue;
            }

            ProcessingChain chain = mChannelProcessingManager.getProcessingChain(channel);

            if(chain != null && chain.isProcessing())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Pushes an up heartbeat to a push URL.  Any query string on the supplied URL is stripped and replaced with
     * a single clean set of parameters (the token lives in the path), so a URL pasted with Kuma's example
     * parameters does not send duplicate keys.
     */
    private void fireKuma(String baseUrl, String channelName)
    {
        String trimmed = baseUrl.trim();
        int query = trimmed.indexOf('?');
        String base = (query >= 0) ? trimmed.substring(0, query) : trimmed;

        String message = channelName + " running";
        final String url = base + "?status=up"
                + "&msg=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&ping=";

        Thread.ofVirtual().start(() -> fireGet(url, channelName));
    }

    private void fireGet(String url, String channelName)
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
                mLog.warn("Channel monitor push for [{}] -> HTTP {}", channelName, code);
            }
            else if(mLoggedOk.add(channelName.toLowerCase()))
            {
                mLog.info("Channel monitor push for [{}] OK (HTTP {})", channelName, code);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Channel monitor push for [{}] failed: {}", channelName, e.getMessage());
        }
    }
}
