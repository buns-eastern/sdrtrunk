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

import io.github.dsheirer.util.ThreadPool;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How long this computer and sdrtrunk have been running.
 *
 * Application uptime comes from the JVM runtime bean (identical on every OS).  System boot time is detected once, on
 * a background thread, per OS - /proc/uptime on Linux, sysctl kern.boottime on macOS, and PowerShell LastBootUpTime
 * on Windows - then uptime is derived on each call with no repeated process spawns.  Any failure to determine boot
 * time degrades to a dash and can never stall the UI or throw.
 */
public class SystemUptime
{
    private static final Logger sLog = LoggerFactory.getLogger(SystemUptime.class);
    private static final String DASH = "—";
    private static final AtomicBoolean sBootTimeRequested = new AtomicBoolean();
    private static volatile Long sBootEpochMillis = null;

    private SystemUptime()
    {
    }

    /**
     * Starts the one-time boot time detection on a background thread, if not already started.
     */
    public static void requestBootTime()
    {
        if(sBootTimeRequested.compareAndSet(false, true))
        {
            ThreadPool.CACHED.submit(() -> {
                try
                {
                    sBootEpochMillis = detectBootEpochMillis();
                }
                catch(Throwable t)
                {
                    sLog.debug("System boot time unavailable - machine uptime will show a dash: " + t.getMessage());
                }
            });
        }
    }

    /**
     * How long sdrtrunk has been running, as days, hours and minutes.
     */
    public static String getSdrtrunkUptime()
    {
        return formatDuration(ManagementFactory.getRuntimeMXBean().getUptime());
    }

    /**
     * How long this computer has been running, as days, hours and minutes, or a dash when it isn't known (yet).
     */
    public static String getMachineUptime()
    {
        requestBootTime();
        Long boot = sBootEpochMillis;
        return boot != null ? formatDuration(System.currentTimeMillis() - boot) : DASH;
    }

    /**
     * Formats a duration as days, hours and minutes, for example 3d 4h 15m.
     */
    public static String formatDuration(long millis)
    {
        if(millis < 0)
        {
            millis = 0;
        }

        long totalMinutes = millis / 60000L;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;
        return days + "d " + hours + "h " + minutes + "m";
    }

    private static Long detectBootEpochMillis()
    {
        try
        {
            String os = System.getProperty("os.name", "").toLowerCase();

            if(os.contains("win"))
            {
                return windowsBootEpochMillis();
            }
            else if(os.contains("mac") || os.contains("darwin"))
            {
                return macBootEpochMillis();
            }

            return unixProcBootEpochMillis();
        }
        catch(Throwable t)
        {
            return null;
        }
    }

    /**
     * Linux: /proc/uptime holds the seconds since boot as its first value.
     */
    private static Long unixProcBootEpochMillis() throws Exception
    {
        Path uptime = Path.of("/proc/uptime");

        if(!Files.exists(uptime))
        {
            return null;
        }

        String content = Files.readString(uptime).trim();
        String first = content.split("\\s+")[0];
        double seconds = Double.parseDouble(first);
        return System.currentTimeMillis() - (long)(seconds * 1000.0);
    }

    /**
     * macOS: sysctl kern.boottime reports the boot time as "{ sec = <epoch>, usec = ... }".
     */
    private static Long macBootEpochMillis() throws Exception
    {
        String out = runCommand(new String[]{"sysctl", "-n", "kern.boottime"});

        if(out != null)
        {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("sec\\s*=\\s*(\\d+)").matcher(out);

            if(m.find())
            {
                return Long.parseLong(m.group(1)) * 1000L;
            }
        }

        return null;
    }

    /**
     * Windows: ask PowerShell for whole seconds of uptime, then derive the boot epoch.
     */
    private static Long windowsBootEpochMillis() throws Exception
    {
        String out = runCommand(new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command",
            "[int64]((Get-Date) - (Get-CimInstance Win32_OperatingSystem).LastBootUpTime).TotalSeconds"});

        if(out != null)
        {
            String digits = out.replaceAll("[^0-9]", "");

            if(!digits.isEmpty())
            {
                return System.currentTimeMillis() - Long.parseLong(digits) * 1000L;
            }
        }

        return null;
    }

    /**
     * Runs a short-lived command and returns its output, or null on timeout/failure. Used only once at startup.
     */
    private static String runCommand(String[] command) throws Exception
    {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        if(!process.waitFor(5, TimeUnit.SECONDS))
        {
            process.destroyForcibly();
            return null;
        }

        try(InputStream is = process.getInputStream())
        {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
