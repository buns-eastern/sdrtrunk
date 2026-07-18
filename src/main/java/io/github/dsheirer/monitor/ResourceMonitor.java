/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides real-time monitoring of application resource usage.
 *
 * Monitors:
 * - CPU usage
 * - RAM usage
 * - Drive Space - Event Logs
 * - Drive Space - Recordings
 */
public class ResourceMonitor
{
    private static final Logger sLog = LoggerFactory.getLogger(ResourceMonitor.class);
    private static final LoggingSuppressor sLogSuppressor = new LoggingSuppressor(sLog);
    private static final int SCALOR_MEGABYTE = 1024 * 1024;
    private UserPreferences mUserPreferences;
    private ScheduledFuture<?> mMemoryCpuMonitorFuture;
    private ScheduledFuture<?> mStorageMonitorFuture;
    private LongProperty mMemoryTotal = new SimpleLongProperty();
    private LongProperty mMemoryAllocated = new SimpleLongProperty();
    private LongProperty mMemoryUsed = new SimpleLongProperty();
    private DoubleProperty mJavaMemoryUsedPercentage = new SimpleDoubleProperty();
    private DoubleProperty mSystemMemoryUsedPercentage = new SimpleDoubleProperty();
    private DoubleProperty mCpuPercentage = new SimpleDoubleProperty();
    private BooleanProperty mCpuAvailable = new SimpleBooleanProperty();
    private DoubleProperty mDirectoryUsePercentEventLogs = new SimpleDoubleProperty();
    private DoubleProperty mDirectoryUsePercentRecordings = new SimpleDoubleProperty();
    private StringProperty mFileSizeEventLogs = new SimpleStringProperty();
    private StringProperty mFileSizeRecordings = new SimpleStringProperty();
    private StringProperty mSdrtrunkUptime = new SimpleStringProperty();
    private StringProperty mMachineUptime = new SimpleStringProperty();
    private volatile Long mBootEpochMillis = null;
    private boolean mBootTimeRequested = false;
    private OperatingSystemMXBean mOperatingSystemMXBean;

    /**
     * Constructs an instance
     */
    public ResourceMonitor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;

        try
        {
            mOperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        }
        catch(Exception e)
        {
            sLog.error("Error accessing operating system MX bean to monitor CPU usage", e);
        }

        mMemoryTotal.set(Runtime.getRuntime().maxMemory());
        mSdrtrunkUptime.set(formatDuration(0));
        mMachineUptime.set("\u2014");
    }

    /**
     * Starts resource monitoring.
     */
    public void start()
    {
        if(mMemoryCpuMonitorFuture == null)
        {
            mMemoryCpuMonitorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> updateCpuMemory(), 1, 1, TimeUnit.SECONDS);
        }

        if(mStorageMonitorFuture == null)
        {
            mStorageMonitorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> updateDirectoryUsage(), 1,30, TimeUnit.SECONDS);
        }

        //Determine system boot time exactly once, on a background thread, so a slow or unavailable OS query
        //can never stall the UI. Until it resolves (or if it fails), machine uptime simply shows a dash.
        if(!mBootTimeRequested)
        {
            mBootTimeRequested = true;
            ThreadPool.CACHED.submit(() -> {
                try
                {
                    mBootEpochMillis = detectBootEpochMillis();
                }
                catch(Throwable t)
                {
                    sLog.debug("System boot time unavailable - machine uptime will show a dash: " + t.getMessage());
                }
            });
        }
    }

    /**
     * Stops resource monitoring.
     */
    public void stop()
    {
        if(mMemoryCpuMonitorFuture != null)
        {
            mMemoryCpuMonitorFuture.cancel(true);
            mMemoryCpuMonitorFuture = null;
        }

        if(mStorageMonitorFuture != null)
        {
            mStorageMonitorFuture.cancel(true);
            mStorageMonitorFuture = null;
        }
    }

    /**
     * Timer-driven method to update CPU and memory usage statistics.
     */
    private void updateCpuMemory()
    {
        double cpuLoadScaled = 0.0;

        if(mOperatingSystemMXBean != null)
        {
            double load = mOperatingSystemMXBean.getSystemLoadAverage();
            cpuLoadScaled = load / mOperatingSystemMXBean.getAvailableProcessors();
        }

        final double loadFinal = cpuLoadScaled;

        Platform.runLater(() -> {
            mMemoryAllocated.set(Runtime.getRuntime().totalMemory());
            mMemoryUsed.set(mMemoryAllocated.getValue() - Runtime.getRuntime().freeMemory());
            mJavaMemoryUsedPercentage.set((double)mMemoryUsed.get() / (double)mMemoryAllocated.get());
            mSystemMemoryUsedPercentage.set((double)mMemoryAllocated.get() / (double)mMemoryTotal.get());
            mCpuPercentage.set(loadFinal > 0 ? loadFinal : 0);
            mCpuAvailable.set(loadFinal >= 0);
            mSdrtrunkUptime.set(formatDuration(ManagementFactory.getRuntimeMXBean().getUptime()));
            Long boot = mBootEpochMillis;
            mMachineUptime.set(boot != null ? formatDuration(System.currentTimeMillis() - boot) : "\u2014");
        });
    }

    /**
     * Timer-driven method to update directory space usage statistics
     */
    private void updateDirectoryUsage()
    {
        long thresholdEventLog = mUserPreferences.getDirectoryPreference().getDirectoryMaxUsageEventLogs() * SCALOR_MEGABYTE;
        long thresholdRecording = mUserPreferences.getDirectoryPreference().getDirectoryMaxUsageRecordings() * SCALOR_MEGABYTE;

        Path recordingPath = mUserPreferences.getDirectoryPreference().getDirectoryRecording();
        Path eventLogsPath = mUserPreferences.getDirectoryPreference().getDirectoryEventLog();

        try
        {
            FileStore recordingFileStore = Files.getFileStore(recordingPath);
            FileStore eventLogsFileStore = Files.getFileStore(eventLogsPath);

            long recordingAvailable = recordingFileStore.getUsableSpace();
            long eventLogsAvailable = eventLogsFileStore.getUsableSpace();

            long eventLogUsed = FileUtils.sizeOfDirectory(eventLogsPath.toFile());
            long recordingUsed = FileUtils.sizeOfDirectory(recordingPath.toFile());

            long eventLogMax = Math.min(thresholdEventLog, eventLogUsed + eventLogsAvailable);
            long recordingMax = Math.min(thresholdRecording, recordingUsed + recordingAvailable);

            Platform.runLater(() -> {
                mDirectoryUsePercentEventLogs.set((double)eventLogUsed / (double)eventLogMax);
                mDirectoryUsePercentRecordings.set((double)recordingUsed / (double)recordingMax);
                mFileSizeEventLogs.set(FileUtils.byteCountToDisplaySize(eventLogUsed));
                mFileSizeRecordings.set(FileUtils.byteCountToDisplaySize(recordingUsed));
            });
        }
        catch(IOException ioe)
        {
            sLogSuppressor.error("Log Once", 1, "Unable to monitor file system - " + ioe.getMessage());
        }
    }

    /**
     * Directory use percentage for event logs directory.
     * @return usage in range 0.0 - 1.0 (or larger if it exceeds the threshold)
     */
    public DoubleProperty directoryUsePercentEventLogsProperty()
    {
        return mDirectoryUsePercentEventLogs;
    }

    /**
     * Directory use percentage for recordings directory.
     * @return usage in range 0.0 - 1.0 (or larger if it exceeds the threshold)
     */
    public DoubleProperty directoryUsePercentRecordingsProperty()
    {
        return mDirectoryUsePercentRecordings;
    }

    /**
     * Formatted value property for file size in event logs directory.
     * @return print friendly value property
     */
    public StringProperty fileSizeEventLogsProperty()
    {
        return mFileSizeEventLogs;
    }

    /**
     * Formatted value property for file size in recordings directory.
     * @return print friendly value property
     */
    public StringProperty fileSizeRecordingsProperty()
    {
        return mFileSizeRecordings;
    }

    /**
     * CPU usage percentage.
     * @return usage in range 0.0 - 1.0
     */
    public DoubleProperty cpuPercentageProperty()
    {
        return mCpuPercentage;
    }

    /**
     * Indicates if the CPU usage percentage value is available on this operating system.
     * @return false when the CPU load value is a negative value, indicating that the JVM for this OS doesn't support it.
     */
    public BooleanProperty cpuAvailableProperty()
    {
        return mCpuAvailable;
    }

    /**
     * Property for total system memory
     */
    public LongProperty memoryTotalProperty()
    {
        return mMemoryTotal;
    }

    /**
     * Property for memory currently allocated to the JVM
     */
    public LongProperty memoryAllocatedProperty()
    {
        return mMemoryAllocated;
    }

    /**
     * Property for memory used by the JVM (out of what has been allocated)
     */
    public LongProperty memoryUsedProperty()
    {
        return mMemoryUsed;
    }

    /**
     * Property for memory used vs memory allocated to the JVM.
     */
    public DoubleProperty javaMemoryUsedPercentageProperty()
    {
        return mJavaMemoryUsedPercentage;
    }

    /**
     * Property for JVM allocated vs total system memory.
     */
    public DoubleProperty systemMemoryUsedPercentageProperty()
    {
        return mSystemMemoryUsedPercentage;
    }

    /**
     * Formatted sdrtrunk (application) uptime, e.g. "7d 2h 28m".
     */
    public StringProperty sdrtrunkUptimeProperty()
    {
        return mSdrtrunkUptime;
    }

    /**
     * Formatted machine (operating system) uptime, e.g. "7d 2h 28m", or a dash if it cannot be determined.
     */
    public StringProperty machineUptimeProperty()
    {
        return mMachineUptime;
    }

    /**
     * Formats a duration in milliseconds as days, hours and minutes (e.g. 0d 0h 5m).
     */
    private static String formatDuration(long millis)
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

    /**
     * Determines the system boot time (epoch millis) for the current OS, or null if it cannot be determined.
     * Any failure returns null so the feature degrades to a dash rather than throwing.
     */
    private Long detectBootEpochMillis()
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
     * Linux (and other /proc systems): first token of /proc/uptime is seconds since boot.
     */
    private Long unixProcBootEpochMillis() throws Exception
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
    private Long macBootEpochMillis() throws Exception
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
    private Long windowsBootEpochMillis() throws Exception
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
    private String runCommand(String[] command) throws Exception
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
