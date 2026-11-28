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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records what the application is doing when startup is slow, so a slow start (ie with no internet connection) can
 * be traced to the exact step.
 *
 * Runs on its own daemon thread from the start of the application until the main window is up.  A normal start
 * reaches the main window in well under the first check, so nothing is written.  When startup is still running at
 * the first check (12 seconds), a snapshot of every thread is appended to one log file, then again every 8 seconds,
 * up to 8 snapshots.  Never throws.
 */
public class StartupWatch
{
    private static final Logger sLog = LoggerFactory.getLogger(StartupWatch.class);
    private static final long FIRST_CHECK_MS = 12_000;
    private static final long CHECK_INTERVAL_MS = 8_000;
    private static final int MAX_SNAPSHOTS = 8;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static volatile boolean sFinished;
    private static boolean sStarted;

    private StartupWatch()
    {
    }

    /**
     * Starts watching startup.
     * @param logDirectory where the slow startup log is saved
     */
    public static synchronized void start(Path logDirectory)
    {
        if(sStarted || logDirectory == null)
        {
            return;
        }

        sStarted = true;
        long startTime = System.currentTimeMillis();
        Path file = logDirectory.resolve(LocalDateTime.now().format(FILE_TIME) + "_sdrtrunk_slow_startup.log");

        Thread thread = new Thread(() -> run(startTime, file), "sdrtrunk startup watch");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Indicates that startup is complete (the main window is up).  Stops watching.
     */
    public static void finished()
    {
        sFinished = true;
    }

    private static void run(long startTime, Path file)
    {
        try
        {
            Thread.sleep(FIRST_CHECK_MS);

            for(int count = 1; count <= MAX_SNAPSHOTS && !sFinished; count++)
            {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                String section = "==== sdrtrunk slow startup snapshot " + count + " at " + LocalDateTime.now() +
                    " - startup still running after " + elapsed + " seconds ====\n\n" + StallWatch.threadReport() + "\n";
                Files.writeString(file, section, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

                if(count == 1)
                {
                    sLog.warn("Startup is slow - still running after " + elapsed + " seconds - recording what it's " +
                        "doing to " + file);
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            }
        }
        catch(Throwable t)
        {
            //Diagnostics only - never affect startup
        }
    }
}
