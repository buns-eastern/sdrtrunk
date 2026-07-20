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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.beans.property.StringProperty;
import io.github.dsheirer.gui.theme.ThemeManager;

/**
 * JavaFX status panel box.
 */
public class StatusBox extends HBox
{
    private static final double BASE_FONT_SIZE = 12.0d;
    private static final double MIN_FONT_SIZE = 8.5d;
    private ResourceMonitor mResourceMonitor;
    private String mThemeStyle = "";
    private boolean mAdjusting = false;

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

        //Flexible spacer pushes the uptime readouts to the far right, away from the resource cluster.
        Region uptimeSpacer = new Region();
        HBox.setHgrow(uptimeSpacer, Priority.ALWAYS);
        getChildren().add(uptimeSpacer);

        Region divider = new Region();
        divider.setStyle("-fx-background-color: " + (ThemeManager.isDarkTheme() ? "#3c3f43" : "#d0d0d0") +
                "; -fx-min-width: 1; -fx-max-width: 1; -fx-min-height: 16; -fx-max-height: 16;");

        HBox machineBox = new HBox(6, buildIcon(ICON_MONITOR), mutedLabel("Computer"),
                valueLabel(mResourceMonitor.machineUptimeProperty()));
        machineBox.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(machineBox, new Tooltip("How long this computer has been running (days, hours, minutes)"));

        HBox appBox = new HBox(6, buildIcon(ICON_BROADCAST), mutedLabel("sdrtrunk"),
                valueLabel(mResourceMonitor.sdrtrunkUptimeProperty()));
        appBox.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(appBox, new Tooltip("How long sdrtrunk has been running (days, hours, minutes)"));

        HBox uptimeBox = new HBox(13, divider, machineBox, appBox);
        uptimeBox.setAlignment(Pos.CENTER_RIGHT);
        uptimeBox.setPadding(new Insets(0, 8, 0, 0));
        getChildren().add(uptimeBox);

        applyTheme();

        //Shrink the bar font to fit as the window narrows, so labels stop truncating.
        widthProperty().addListener((observable, oldValue, newValue) -> adjustFontSize());
    }

    private static final String ICON_MONITOR =
        "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z";
    private static final String ICON_BROADCAST =
        "M12 11c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6-3l-1.4 1.4C17.9 10.6 18.5 12 18.5 13.5c0 1.5-.6 2.9-1.9 4.1L18 19c1.7-1.5 2.5-3.5 2.5-5.5S19.7 9.5 18 8zM6 13.5c0-1.5.6-2.9 1.9-4.1L6.5 8C4.8 9.5 4 11.5 4 13.5s.8 4 2.5 5.5l1.4-1.4C6.6 16.4 6 15 6 13.5z";

    /**
     * Builds a small theme-tinted vector glyph from an SVG path, scaled into a 14x14 box.
     */
    private Region buildIcon(String svgPath)
    {
        Region icon = new Region();
        icon.setStyle("-fx-shape: \"" + svgPath + "\"; -fx-background-color: " + ThemeManager.headingTextColor() +
                "; -fx-min-width: 14; -fx-min-height: 14; -fx-max-width: 14; -fx-max-height: 14;");
        return icon;
    }

    /**
     * A muted section label matching the theme.
     */
    private Label mutedLabel(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");
        return label;
    }

    /**
     * A monospaced value label bound to the supplied string property so the digits line up as they update.
     */
    private Label valueLabel(StringProperty property)
    {
        Label label = new Label();
        label.textProperty().bind(property);
        label.setStyle("-fx-font-family: 'Monospaced'; -fx-font-weight: bold;");
        return label;
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
        mThemeStyle = "-fx-base: " + base + "; -fx-background-color: " + background +
            "; -fx-control-inner-background: " + inner + "; ";
        applyFontSize(BASE_FONT_SIZE);
    }

    /**
     * Applies the theme style plus a font size. Font size cascades to all the child labels.
     */
    private void applyFontSize(double size)
    {
        setStyle(mThemeStyle + "-fx-font-size: " + size + "px;");
    }

    /**
     * Shrinks the status bar font just enough to fit the current width so the labels stop truncating, down to a
     * readable floor. Runs whenever the bar is resized.
     */
    private void adjustFontSize()
    {
        if(mAdjusting)
        {
            return;
        }

        double available = getWidth();

        if(available <= 8.0d)
        {
            return;
        }

        mAdjusting = true;

        try
        {
            //Measure the natural width at the base font, then scale the font down if it overflows
            applyFontSize(BASE_FONT_SIZE);
            applyCss();
            double needed = prefWidth(-1);
            double size = BASE_FONT_SIZE;

            if(needed > available && needed > 0)
            {
                size = Math.max(MIN_FONT_SIZE, BASE_FONT_SIZE * (available / needed));
            }

            applyFontSize(size);
        }
        finally
        {
            mAdjusting = false;
        }
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
