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

import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataModel;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.ChannelHeartbeatEntry;
import io.github.dsheirer.preference.network.ChannelHeartbeatPreference;
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
 * Fires a heartbeat to an external URL whenever one of the chosen talkgroups becomes active.
 *
 * Reads the application's own "Now Playing" channel metadata (which covers both trunking talkgroups and
 * conventional channels - a conventional channel's assigned talkgroup rides in the same TO identifier).  When a
 * watched talkgroup transitions into a call, the configured URL is fired with the talkgroup substituted into the
 * {channel} placeholder, debounced per talkgroup so one transmission does not spam.  Everything runs off the
 * decode path on a scheduled thread, is guarded so it can never throw into decoding, and only reads state.
 */
public class ChannelHeartbeatManager
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelHeartbeatManager.class);
    private static final long TICK_MILLIS = 250;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final UserPreferences mUserPreferences;
    private final ChannelMetadataModel mChannelMetadataModel;
    private ScheduledFuture<?> mFuture;
    private final Set<Integer> mActiveTalkgroups = new HashSet<>();
    private final Map<Integer,Long> mLastFiredMs = new HashMap<>();

    /**
     * Constructs an instance.
     * @param userPreferences source of the channel heartbeat configuration
     * @param channelMetadataModel live "Now Playing" metadata for all active channels
     */
    public ChannelHeartbeatManager(UserPreferences userPreferences, ChannelMetadataModel channelMetadataModel)
    {
        mUserPreferences = userPreferences;
        mChannelMetadataModel = channelMetadataModel;
    }

    /**
     * Begins the periodic evaluation loop.
     */
    public void start()
    {
        if(mFuture == null)
        {
            mFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::tick, TICK_MILLIS, TICK_MILLIS,
                TimeUnit.MILLISECONDS);
            mLog.info("Channel heartbeat monitor started");
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
            mLog.error("Channel heartbeat monitor tick failed (continuing)", t);
        }
    }

    private void evaluate()
    {
        if(mUserPreferences == null || mChannelMetadataModel == null)
        {
            return;
        }

        ChannelHeartbeatPreference pref = mUserPreferences.getChannelHeartbeatPreference();

        if(!pref.isEnabled())
        {
            return;
        }

        String template = pref.getUrlTemplate();

        if(template == null || template.isBlank())
        {
            return;
        }

        List<ChannelHeartbeatEntry> entries = pref.getEntries();

        if(entries.isEmpty())
        {
            return;
        }

        Map<Integer,String> watched = new HashMap<>();

        for(ChannelHeartbeatEntry entry: entries)
        {
            watched.put(entry.getTalkgroup(), entry.getLabel());
        }

        //Which watched talkgroups are currently in a call, per the live "Now Playing" metadata
        Set<Integer> activeNow = new HashSet<>();
        int rows = mChannelMetadataModel.getRowCount();

        for(int i = 0; i < rows; i++)
        {
            try
            {
                ChannelMetadata metadata = mChannelMetadataModel.getChannelMetadata(i);

                if(metadata == null)
                {
                    continue;
                }

                ChannelStateIdentifier stateIdentifier = metadata.getChannelStateIdentifier();

                if(stateIdentifier == null || stateIdentifier.getValue() != State.CALL)
                {
                    continue;
                }

                Identifier toIdentifier = metadata.getToIdentifier();

                if(toIdentifier != null && toIdentifier.getValue() instanceof Integer)
                {
                    int talkgroup = (Integer)toIdentifier.getValue();

                    if(watched.containsKey(talkgroup))
                    {
                        activeNow.add(talkgroup);
                    }
                }
            }
            catch(Throwable t)
            {
                //Ignore a single unreadable row (the model is updated on another thread)
            }
        }

        long now = System.currentTimeMillis();
        long debounceMs = Math.max(0, pref.getDebounceSeconds()) * 1000L;

        for(Map.Entry<Integer,String> watchedEntry: watched.entrySet())
        {
            int talkgroup = watchedEntry.getKey();
            boolean isActive = activeNow.contains(talkgroup);
            boolean wasActive = mActiveTalkgroups.contains(talkgroup);

            //Fire once on the transition into a call, subject to the per-talkgroup debounce
            if(isActive && !wasActive)
            {
                Long last = mLastFiredMs.get(talkgroup);

                if(last == null || (now - last) >= debounceMs)
                {
                    fire(template, talkgroup, watchedEntry.getValue());
                    mLastFiredMs.put(talkgroup, now);
                }
            }

            if(isActive)
            {
                mActiveTalkgroups.add(talkgroup);
            }
            else
            {
                mActiveTalkgroups.remove(talkgroup);
            }
        }

        //Drop tracking for talkgroups no longer watched
        mActiveTalkgroups.retainAll(watched.keySet());
        mLastFiredMs.keySet().retainAll(watched.keySet());
    }

    /**
     * Builds the URL from the template and fires it on a virtual thread.
     */
    private void fire(String template, int talkgroup, String label)
    {
        String built = template.replace("{channel}", Integer.toString(talkgroup));

        if(label != null)
        {
            built = built.replace("{label}", label);
        }

        final String url = sanitizeUrl(built);
        Thread.ofVirtual().start(() -> fireGet(url, talkgroup));
    }

    /**
     * Percent-encodes any characters not legal in a URL so a template containing raw special characters (for
     * example a token with braces or non-ASCII) is still sent successfully.  URL structure and anything already
     * percent-encoded is preserved, so a clean URL passes through unchanged.
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

    private void fireGet(String url, int talkgroup)
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
                mLog.warn("Channel heartbeat for talkgroup [{}] -> HTTP {}", talkgroup, code);
            }
        }
        catch(Exception e)
        {
            mLog.warn("Channel heartbeat for talkgroup [{}] failed: {}", talkgroup, e.getMessage());
        }
    }
}
