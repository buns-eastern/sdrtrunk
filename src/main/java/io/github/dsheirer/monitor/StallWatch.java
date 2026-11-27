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

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches for a frozen user interface or a stalled channel and, when one happens, saves a snapshot of every thread
 * to the application log folder so the cause can be found afterwards.
 *
 * Runs on its own daemon thread (not the shared thread pools, which may be the thing that's stuck).  The user
 * interface is checked every 5 seconds; when it hasn't responded for 15 seconds a snapshot is saved.  Channel stalls
 * are reported by the Dispatcher when it drops many samples and saved on the watch thread.  At most three snapshots are saved per run, at least
 * a minute apart, so a long freeze can't fill the disk.  Never throws.
 */
public class StallWatch
{
    private static final Logger sLog = LoggerFactory.getLogger(StallWatch.class);
    private static final long CHECK_INTERVAL_MS = 5_000;
    private static final long UI_STALL_MS = 15_000;
    private static final long MIN_GAP_BETWEEN_SNAPSHOTS_MS = 60_000;
    private static final int MAX_SNAPSHOTS = 3;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static volatile Path sLogDirectory;
    private static volatile boolean sStarted;
    private static volatile String sPendingReason;
    private static final AtomicLong sUiLastResponse = new AtomicLong(System.currentTimeMillis());
    private static long sLastSnapshot;
    private static int sSnapshotCount;

    private StallWatch()
    {
    }

    /**
     * Starts watching the user interface.  Call once the JavaFX toolkit is running.
     * @param logDirectory where snapshots are saved
     */
    public static synchronized void start(Path logDirectory)
    {
        sLogDirectory = logDirectory;

        if(sStarted)
        {
            return;
        }

        sStarted = true;
        sUiLastResponse.set(System.currentTimeMillis());
        Thread thread = new Thread(StallWatch::run, "sdrtrunk stall watch");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reports a stalled channel or other processing stall.  Saves a snapshot, subject to the limits.
     * @param reason shown at the top of the snapshot
     */
    public static void reportStall(String reason)
    {
        //Only records the request: the snapshot is taken on the stall watch thread so the caller (a tuner or
        //channel thread) is never slowed down by it
        sPendingReason = reason;
    }

    private static void run()
    {
        boolean pingPending = false;

        while(true)
        {
            try
            {
                Thread.sleep(CHECK_INTERVAL_MS);

                long now = System.currentTimeMillis();
                long sinceResponse = now - sUiLastResponse.get();

                String pending = sPendingReason;

                if(pending != null)
                {
                    sPendingReason = null;
                    snapshot(pending);
                }

                if(sinceResponse >= UI_STALL_MS)
                {
                    snapshot("User interface has not responded for " + (sinceResponse / 1000) + " seconds");
                }

                if(!pingPending || sinceResponse < CHECK_INTERVAL_MS * 2)
                {
                    pingPending = true;
                    Platform.runLater(() -> sUiLastResponse.set(System.currentTimeMillis()));
                }
            }
            catch(InterruptedException ie)
            {
                return;
            }
            catch(Throwable t)
            {
                //Keep watching
            }
        }
    }

    private static void snapshot(String reason)
    {
        Path directory = sLogDirectory;

        if(directory == null)
        {
            return;
        }

        synchronized(StallWatch.class)
        {
            long now = System.currentTimeMillis();

            if(sSnapshotCount >= MAX_SNAPSHOTS || (sSnapshotCount > 0 && now - sLastSnapshot < MIN_GAP_BETWEEN_SNAPSHOTS_MS))
            {
                return;
            }

            sSnapshotCount++;
            sLastSnapshot = now;
        }

        String stamp = LocalDateTime.now().format(FILE_TIME);

        try
        {
            //Platform threads with the locks each one holds and is waiting for
            StringBuilder sb = new StringBuilder();
            sb.append("sdrtrunk stall snapshot ").append(LocalDateTime.now()).append("\n");
            sb.append("Reason: ").append(reason).append("\n\n");

            for(ThreadInfo info: ManagementFactory.getThreadMXBean().dumpAllThreads(true, true))
            {
                sb.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());

                if(info.getLockName() != null)
                {
                    sb.append(" waiting for ").append(info.getLockName());

                    if(info.getLockOwnerName() != null)
                    {
                        sb.append(" held by \"").append(info.getLockOwnerName()).append('"');
                    }
                }

                sb.append('\n');
                StackTraceElement[] stack = info.getStackTrace();

                for(int i = 0; i < stack.length; i++)
                {
                    sb.append("    at ").append(stack[i]).append('\n');

                    for(MonitorInfo monitor: info.getLockedMonitors())
                    {
                        if(monitor.getLockedStackDepth() == i)
                        {
                            sb.append("      - locked ").append(monitor).append('\n');
                        }
                    }
                }

                for(LockInfo lock: info.getLockedSynchronizers())
                {
                    sb.append("    - holds ").append(lock).append('\n');
                }

                sb.append('\n');
            }

            Path platformFile = directory.resolve(stamp + "_sdrtrunk_stall_threads.log");
            Files.writeString(platformFile, sb.toString(), StandardCharsets.UTF_8);
            sLog.error("Stall detected - " + reason + " - thread snapshot saved to " + platformFile);
        }
        catch(Throwable t)
        {
            sLog.error("Stall detected - " + reason + " - unable to save thread snapshot: " + t.getMessage());
        }

        try
        {
            //Every thread, including the virtual threads used by the streams and heartbeats
            Path allFile = directory.resolve(stamp + "_sdrtrunk_stall_all_threads.log").toAbsolutePath();

            if(!Files.exists(allFile))
            {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(allFile.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            }
        }
        catch(Throwable t)
        {
            sLog.error("Unable to save the full thread snapshot: " + t.getMessage());
        }
    }
}
