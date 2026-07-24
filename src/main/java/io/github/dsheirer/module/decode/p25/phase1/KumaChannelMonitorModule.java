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
import java.net.URI;
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
 * Pings a per-channel URL on a fixed interval for as long as a standalone (non-trunking) channel is running.
 *
 * The processing-chain lifecycle is the "is this channel running?" signal: start() is called when the channel
 * is played and stop() when it is stopped.  So while the channel runs this fires a GET every interval, and when
 * it stops the pings stop.  A push-style uptime monitor watching the URL therefore shows the channel up while
 * it is running and down when it stops -- regardless of whether any traffic is being received, so a quiet
 * channel does not look down.
 *
 * All network activity runs off the decode path on a virtual thread and every failure is swallowed, so the
 * monitor can never affect channel processing.
 */
public class KumaChannelMonitorModule extends Module
{
    private static final Logger mLog = LoggerFactory.getLogger(KumaChannelMonitorModule.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String mChannelName;
    private final String mUrl;
    private final long mIntervalSeconds;
    private ScheduledFuture<?> mFuture;

    /**
     * Constructs an instance.
     * @param channelName channel this monitor is attached to (for logging only)
     * @param url the URL to ping while the channel runs (sent verbatim, special characters percent-encoded)
     * @param intervalSeconds seconds between successive pings while running
     */
    public KumaChannelMonitorModule(String channelName, String url, int intervalSeconds)
    {
        mChannelName = channelName == null ? "" : channelName;
        mUrl = url == null ? "" : url;
        mIntervalSeconds = Math.max(1, intervalSeconds);
    }

    @Override
    public void start()
    {
        //Ping once immediately so the monitor comes up without waiting a full interval, then ping on schedule.
        ping();

        try
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::ping,
                    mIntervalSeconds, mIntervalSeconds, TimeUnit.SECONDS);
        }
        catch(Throwable t)
        {
            mLog.warn("Unable to schedule channel monitor ping for [{}]: {}", mChannelName, t.getMessage());
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
    }

    @Override
    public void reset()
    {
        //No state to reset.
    }

    /**
     * Fires the configured URL as a GET on a virtual thread.  Wrapped so any failure can never affect channel
     * processing.
     */
    private void ping()
    {
        try
        {
            if(mUrl.isBlank())
            {
                return;
            }

            final String url = sanitizeUrl(mUrl);
            Thread.ofVirtual().start(() -> fireGet(url));
        }
        catch(Throwable t)
        {
            mLog.warn("Channel monitor ping error for [{}]: {}", mChannelName, t.getMessage());
        }
    }

    private void fireGet(String url)
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
                mLog.warn("Channel monitor for [{}] -> HTTP {}", mChannelName, code);
            }
        }
        catch(Throwable t)
        {
            mLog.warn("Channel monitor for [{}] failed: {}", mChannelName, t.getMessage());
        }
    }

    /**
     * Percent-encodes any characters not legal in a URL so a URL containing raw special characters (for example
     * a token with braces or non-ASCII) is still sent successfully.  URL structure and anything already
     * percent-encoded is preserved, so a clean URL -- including its query string -- passes through unchanged.
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
}
