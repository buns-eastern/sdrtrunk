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
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.util.ThreadPool;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports USB / tuner hardware health to an Uptime Kuma push monitor by reading the real per-tuner status that
 * SDRTrunk already maintains (Enabled / Disabled / Error / Removed) rather than parsing the application log.
 *
 * A push is sent "down" - with the offending tuner's identity and its actual error message - when any tuner is
 * in an error state or has dropped off the bus, and "up" when all tuners are healthy.  A tuner the user has
 * intentionally disabled is not treated as a fault.  A configurable window keeps the monitor down for a short
 * period after an error clears, so a brief hiccup is still visible downstream.
 */
public class TunerErrorMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerErrorMonitor.class);
    private static final long TICK_SECONDS = 5;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private ScheduledFuture<?> mFuture;
    private Set<String> mPreviousTunerIds = new HashSet<>();
    private long mLastErrorMs = 0;
    private String mLastDetail = null;
    private boolean mReportedDown = false;
    private long mLastPushMs = 0;

    /**
     * Constructs an instance.
     * @param userPreferences source of the USB error monitor configuration
     * @param tunerManager source of live per-tuner status
     */
    public TunerErrorMonitor(UserPreferences userPreferences, TunerManager tunerManager)
    {
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
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
            mLog.info("USB/tuner error monitor started");
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
            mLog.error("USB/tuner error monitor tick failed (continuing)", t);
        }
    }

    private void evaluate()
    {
        if(mUserPreferences == null || mTunerManager == null)
        {
            return;
        }

        SoftwareHeartbeatPreference pref = mUserPreferences.getSoftwareHeartbeatPreference();

        if(!pref.isUsbEnabled())
        {
            return;
        }

        String kumaUrl = pref.getUsbKumaUrl();

        if(kumaUrl == null || kumaUrl.isBlank())
        {
            return;
        }

        long now = System.currentTimeMillis();

        //Read the real per-tuner status - all discovered tuners, including any currently in an error state
        List<String> errored = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        int healthy = 0;

        DiscoveredTunerModel model = mTunerManager.getDiscoveredTunerModel();
        int count = model.getRowCount();

        for(int i = 0; i < count; i++)
        {
            DiscoveredTuner tuner = model.getDiscoveredTuner(i);

            if(tuner == null)
            {
                continue;
            }

            String id = tuner.getId();
            currentIds.add(id);
            TunerStatus status = tuner.getTunerStatus();

            if(status == TunerStatus.ERROR)
            {
                String detail = tuner.hasErrorMessage() ? (id + " - " + tuner.getErrorMessage()) : id;
                errored.add(detail);
            }
            else if(status == TunerStatus.ENABLED)
            {
                healthy++;
            }
            //DISABLED tuners are an intentional user choice and are not treated as a fault
        }

        //A tuner that was present last scan but is gone now has dropped off the bus (removed / unplugged)
        List<String> removed = new ArrayList<>();

        for(String previousId: mPreviousTunerIds)
        {
            if(!currentIds.contains(previousId))
            {
                removed.add(previousId);
            }
        }

        mPreviousTunerIds = currentIds;

        boolean activeError = !errored.isEmpty();

        if(activeError)
        {
            mLastErrorMs = now;
            mLastDetail = errored.get(0) + (errored.size() > 1 ? " (+" + (errored.size() - 1) + " more)" : "");
        }
        else if(!removed.isEmpty())
        {
            mLastErrorMs = now;
            mLastDetail = "Tuner removed: " + String.join(", ", removed);
        }

        long windowMs = Math.max(0, pref.getUsbWindowSeconds()) * 1000L;
        boolean withinWindow = mLastErrorMs > 0 && (now - mLastErrorMs) <= windowMs;
        boolean down = activeError || withinWindow;

        String message = down
                ? (mLastDetail != null ? mLastDetail : "USB/tuner error")
                : "OK (" + healthy + (healthy == 1 ? " tuner healthy)" : " tuners healthy)");

        long intervalMs = Math.max(1, pref.getUsbIntervalSeconds()) * 1000L;
        boolean transition = (down != mReportedDown);

        if(transition || (now - mLastPushMs) >= intervalMs)
        {
            push(kumaUrl, down ? "down" : "up", message);
            mReportedDown = down;
            mLastPushMs = now;
        }
    }

    /**
     * Pushes to an Uptime Kuma push URL.  Any query string on the supplied URL is stripped and replaced with a
     * single clean parameter set (the token lives in the path) so a pasted example URL does not send duplicates.
     */
    private void push(String baseUrl, String status, String message)
    {
        String trimmed = baseUrl.trim();
        int query = trimmed.indexOf('?');
        String base = (query >= 0) ? trimmed.substring(0, query) : trimmed;

        String url = base + "?status=" + status
                + "&msg=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&ping=";

        Thread.ofVirtual().start(() -> fireGet(url));
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
                mLog.warn("USB/tuner error monitor push -> HTTP {}", code);
            }
        }
        catch(Exception e)
        {
            mLog.warn("USB/tuner error monitor push failed: {}", e.getMessage());
        }
    }
}
