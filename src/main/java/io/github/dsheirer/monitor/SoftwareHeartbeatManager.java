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

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.SoftwareHeartbeatPreference;
import io.github.dsheirer.util.ThreadPool;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports that the application itself is alive by pushing a periodic heartbeat while it is running.
 *
 * There is no "down" to compute from inside the application: if this code runs, the application is up.  When the
 * application stops or crashes the heartbeats simply stop and any downstream monitor flips to down on its own
 * (silence = down).  Two independent targets are supported:
 *  - an Uptime Kuma push URL, sent with status=up and a short status message; and
 *  - an optional second URL, sent exactly as entered so any monitoring endpoint can be used.
 */
public class SoftwareHeartbeatManager
{
    private static final Logger mLog = LoggerFactory.getLogger(SoftwareHeartbeatManager.class);
    private static final long TICK_SECONDS = 5;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final UserPreferences mUserPreferences;
    private ScheduledFuture<?> mFuture;
    private long mKumaLastPushMs = 0;
    private long mSecondLastPushMs = 0;

    /**
     * Constructs an instance.
     * @param userPreferences source of the software heartbeat configuration
     */
    public SoftwareHeartbeatManager(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    /**
     * Begins the periodic heartbeat loop.
     */
    public void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS,
                TimeUnit.SECONDS);
            mLog.info("Software heartbeat monitor started");
        }
    }

    /**
     * Stops the periodic heartbeat loop.
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
            mLog.error("Software heartbeat monitor tick failed (continuing)", t);
        }
    }

    private void evaluate()
    {
        if(mUserPreferences == null)
        {
            return;
        }

        SoftwareHeartbeatPreference pref = mUserPreferences.getSoftwareHeartbeatPreference();

        if(!pref.isEnabled())
        {
            return;
        }

        long now = System.currentTimeMillis();

        //Uptime Kuma target - its own interval
        String kumaUrl = pref.getKumaUrl();

        if(kumaUrl != null && !kumaUrl.isBlank())
        {
            long kumaIntervalMs = Math.max(1, pref.getKumaIntervalSeconds()) * 1000L;

            if((now - mKumaLastPushMs) >= kumaIntervalMs)
            {
                mKumaLastPushMs = now;
                fireKuma(kumaUrl);
            }
        }

        //Second generic target - independent interval, sent exactly as entered
        String secondUrl = pref.getSecondUrl();

        if(secondUrl != null && !secondUrl.isBlank())
        {
            long secondIntervalMs = Math.max(1, pref.getSecondIntervalSeconds()) * 1000L;

            if((now - mSecondLastPushMs) >= secondIntervalMs)
            {
                mSecondLastPushMs = now;
                final String url = sanitizeUrl(secondUrl.trim());
                Thread.ofVirtual().start(() -> fireGet(url, "second URL"));
            }
        }
    }

    /**
     * Pushes an up heartbeat to an Uptime Kuma push URL.  Any query string on the supplied URL is stripped and
     * replaced with a single clean set of parameters (the token lives in the path), so a URL pasted with Kuma's
     * example parameters does not send duplicate keys.
     */
    private void fireKuma(String baseUrl)
    {
        String trimmed = baseUrl.trim();
        int query = trimmed.indexOf('?');
        String base = (query >= 0) ? trimmed.substring(0, query) : trimmed;

        String message = "SDRTrunk running (up " + formatUptime() + ")";
        String url = base + "?status=up"
                + "&msg=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&ping=";

        Thread.ofVirtual().start(() -> fireGet(url, "Uptime Kuma"));
    }

    private static String formatUptime()
    {
        long seconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;

        if(hours > 0)
        {
            return hours + "h " + minutes + "m";
        }

        if(minutes > 0)
        {
            return minutes + "m";
        }

        return seconds + "s";
    }

    /**
     * Percent-encodes any characters that are not legal in a URL so a second URL pasted with raw special
     * characters (for example a token containing braces or non-ASCII) is still sent successfully.  URL
     * structure and anything already percent-encoded is preserved, so a clean URL passes through unchanged.
     */
    private static String sanitizeUrl(String url)
    {
        StringBuilder sb = new StringBuilder(url.length());
        int i = 0;

        while(i < url.length())
        {
            char c = url.charAt(i);

            if(c == '%' && i + 2 < url.length() && isHexDigit(url.charAt(i + 1)) && isHexDigit(url.charAt(i + 2)))
            {
                sb.append(url, i, i + 3);
                i += 3;
            }
            else if(isUriSafe(c))
            {
                sb.append(c);
                i++;
            }
            else
            {
                int codePoint = url.codePointAt(i);

                for(byte b: new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8))
                {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }

                i += Character.charCount(codePoint);
            }
        }

        return sb.toString();
    }

    private static boolean isUriSafe(char c)
    {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || "-._~:/?#@!$&'()*+,;=".indexOf(c) >= 0;
    }

    private static boolean isHexDigit(char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private void fireGet(String url, String label)
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
                mLog.warn("Software heartbeat push to [{}] -> HTTP {}", label, code);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Software heartbeat push to [{}] failed: {}", label, e.getMessage());
        }
    }
}
