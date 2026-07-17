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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import io.github.dsheirer.gui.theme.ThemeManager;

/**
 * JavaFX status panel box.
 */
public class StatusBox extends HBox
{
    private ResourceMonitor mResourceMonitor;

    /**
     * Constructs an instance.
     * @param resourceMonitor for accessing resource usage statistics.
     */
    public StatusBox(ResourceMonitor resourceMonitor)
    {
        mResourceMonitor = resourceMonitor;
        setPadding(new Insets(1, 0, 1, 0));
        setSpacing(6);
        Label cpuLabel = new Label("CPU:");
        cpuLabel.setPadding(new Insets(0, 0, 0, 10));
        cpuLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(cpuLabel);

        ProgressBar cpuIndicator = new ProgressBar();
        cpuIndicator.progressProperty().bind(mResourceMonitor.cpuPercentageProperty());
        cpuIndicator.disableProperty().bind(mResourceMonitor.cpuAvailableProperty().not());
        cpuIndicator.setTooltip(new Tooltip("Java process CPU usage. Disabled if the CPU loading is not available from the OS"));
        applyUsageColoring(cpuIndicator);
        getChildren().add(cpuIndicator);

        Label memoryLabel = new Label("Allocated Memory:");
        memoryLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(memoryLabel);

        ProgressBar memoryBar = new ProgressBar();
        memoryBar.progressProperty().bind(mResourceMonitor.systemMemoryUsedPercentageProperty());
        memoryBar.setTooltip(new Tooltip("Percentage of total system memory that Java has reserved from the Operating System."));
        applyUsageColoring(memoryBar);
        getChildren().add(memoryBar);

        Label javaMemoryLabel = new Label("Used Memory:");
        javaMemoryLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(javaMemoryLabel);

        ProgressBar javaMemoryBar = new ProgressBar();
        javaMemoryBar.progressProperty().bind(mResourceMonitor.javaMemoryUsedPercentageProperty());
        javaMemoryBar.setTooltip(new Tooltip("Percentage of allocated memory that Java/sdrtrunk is currently using. This value fluctuates as Java manages memory and garbage collection"));
        applyUsageColoring(javaMemoryBar);
        getChildren().add(javaMemoryBar);

        Label eventLogsLabel = new Label("Event Logs:");
        eventLogsLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(eventLogsLabel);

        ProgressBar eventLogsBar = new ProgressBar();
        eventLogsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentEventLogsProperty());
        eventLogsBar.setTooltip(new Tooltip("Percentage of drive space used for event logs based on user-specified max threshold in user preferences"));
        applyUsageColoring(eventLogsBar);
        getChildren().add(eventLogsBar);

        Label eventLogsSizeLabel = new Label();
        eventLogsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeEventLogsProperty());
        eventLogsSizeLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(eventLogsSizeLabel);

        Label recordingsLabel = new Label("Recordings:");
        recordingsLabel.setPadding(new Insets(0, 0, 0, 10));
        recordingsLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(recordingsLabel);

        ProgressBar recordingsBar = new ProgressBar();
        recordingsBar.progressProperty().bind(mResourceMonitor.directoryUsePercentRecordingsProperty());
        recordingsBar.setTooltip(new Tooltip("Percentage of drive space used for recordings based on user-specified max threshold in user preferences"));
        applyUsageColoring(recordingsBar);
        getChildren().add(recordingsBar);

        Label recordingsSizeLabel = new Label();
        recordingsSizeLabel.textProperty().bind(mResourceMonitor.fileSizeRecordingsProperty());
        recordingsSizeLabel.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(recordingsSizeLabel);

        applyTheme();
    }

    /**
     * Applies a theme-appropriate background and control base so the status bar matches the app theme.
     */
    private void applyTheme()
    {
        boolean dark = ThemeManager.isDarkTheme();
        String base = dark ? "#3c3f43" : "#ececec";
        String background = dark ? "#1e1f22" : "#f4f4f4";
        String inner = dark ? "#2b2d31" : "#ffffff";
        setStyle("-fx-base: " + base + "; -fx-background-color: " + background + "; -fx-control-inner-background: " + inner + ";");
    }

    /**
     * Colors a usage bar on a calm cool-to-warm sweep (teal at low usage, amber at high). Deliberately avoids
     * red so a busy-but-normal machine does not look like an alarm.
     */
    private void applyUsageColoring(ProgressBar bar)
    {
        bar.progressProperty().addListener((obs, o, n) -> bar.setStyle("-fx-accent: " + usageColor(n.doubleValue()) + ";"));
        bar.setStyle("-fx-accent: " + usageColor(Math.max(0.0, bar.getProgress())) + ";");
    }

    private static String usageColor(double progress)
    {
        double p = Math.max(0.0, Math.min(1.0, progress));
        double hue = 185.0 - (p * 145.0); //teal (185) down to amber (40)
        javafx.scene.paint.Color c = javafx.scene.paint.Color.hsb(hue, 0.62, 0.82);
        return String.format("#%02X%02X%02X", (int)Math.round(c.getRed() * 255),
            (int)Math.round(c.getGreen() * 255), (int)Math.round(c.getBlue() * 255));
    }
}
