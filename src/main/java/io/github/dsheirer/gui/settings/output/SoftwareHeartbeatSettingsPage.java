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

package io.github.dsheirer.gui.settings.output;

import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.SoftwareHeartbeatSettings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

/**
 * Software Heartbeat: pushes a periodic heartbeat while the application runs, and a separate USB / tuner error
 * monitor.  Changes on this page are stored when the user clicks Save.
 */
public class SoftwareHeartbeatSettingsPage extends OutputSettingsPage
{
    private final SoftwareHeartbeatSettings mHeartbeatSettings;
    private CheckBox mEnabled;
    private TextField mKumaUrl;
    private Spinner<Integer> mKumaInterval;
    private TextField mSecondUrl;
    private Spinner<Integer> mSecondInterval;
    private Label mStatusLabel;
    private CheckBox mUsbEnabled;
    private TextField mUsbKumaUrl;
    private Spinner<Integer> mUsbInterval;
    private Spinner<Integer> mUsbWindow;
    private Label mUsbStatusLabel;

    /**
     * Constructs an instance
     * @param settings to edit
     */
    public SoftwareHeartbeatSettingsPage(Settings settings)
    {
        super(SettingsPageType.SOFTWARE_HEARTBEAT, settings);
        mHeartbeatSettings = settings.getSoftwareHeartbeatSettings();

        SettingsSection howItWorks = addSection("How It Works");
        howItWorks.addContent(paragraph(
            "While SDRTrunk is running it sends a heartbeat to each URL you configure, each at its own interval.\n\n" +
            "There is nothing to compute as \"down\" from inside the application — if this code is running, the " +
            "application is up.  A stopped or crashed application simply stops sending, and your monitor flips to " +
            "down on its own (silence = down).  This replaces an external watchdog script that only checks whether " +
            "the application process is alive."));

        SettingsSection urls = addSection("The Two URLs");
        urls.addContent(callout(
            "Uptime Kuma push URL:  paste just the base push URL (the token is in the path).  SDRTrunk adds " +
            "status and message parameters itself, so any example query on the end is ignored.\n\n" +
            "Second heartbeat URL (optional):  sent exactly as entered, every interval — nothing is added or " +
            "changed.  Put any complete web address here, including its own parameters, to notify a second " +
            "monitoring endpoint."));
        urls.addContent(muted(
            "Tip: set the Kuma monitor's own Heartbeat Interval to roughly double the Kuma interval below, with a " +
            "retry or two, so a single missed beat does not flip it red."));
        urls.addContent(saveNote("Click Save to apply. Changes take effect immediately — no restart needed."));

        SettingsSection configuration = addSection("Configuration");
        GridPane grid = formGrid();
        int row = 0;

        mEnabled = new CheckBox("Enable software heartbeat");
        mEnabled.setSelected(mHeartbeatSettings.isEnabled());
        grid.add(rightLabel("Monitor:"), 0, row);
        grid.add(mEnabled, 1, row++);

        mKumaUrl = new TextField(mHeartbeatSettings.getKumaUrl());
        mKumaUrl.setPromptText("http://kuma-host:3001/api/push/TOKEN");
        mKumaUrl.setMaxWidth(Double.MAX_VALUE);
        grid.add(rightLabel("Uptime Kuma push URL:"), 0, row);
        grid.add(mKumaUrl, 1, row++);

        mKumaInterval = secondsSpinner(5, 3600, mHeartbeatSettings.getKumaIntervalSeconds(), 5);
        grid.add(rightLabel("Kuma interval (s):"), 0, row);
        grid.add(mKumaInterval, 1, row++);

        mSecondUrl = new TextField(mHeartbeatSettings.getSecondUrl());
        mSecondUrl.setPromptText("https://your-endpoint.example.com/heartbeat?...  (optional, sent as-is)");
        mSecondUrl.setMaxWidth(Double.MAX_VALUE);
        grid.add(rightLabel("Second heartbeat URL:"), 0, row);
        grid.add(mSecondUrl, 1, row++);

        mSecondInterval = secondsSpinner(5, 3600, mHeartbeatSettings.getSecondIntervalSeconds(), 5);
        grid.add(rightLabel("Second URL interval (s):"), 0, row);
        grid.add(mSecondInterval, 1, row++);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        mStatusLabel = muted("");
        HBox buttons = new HBox(8, saveButton, mStatusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        configuration.addContent(grid);
        configuration.addContent(buttons);

        SettingsSection usb = addSection("USB / Tuner Error Monitor");
        Label usbSubtitle = paragraph(
            "Get a push the moment a tuner fails - a USB error, a shutdown, or a device dropping off the bus. " +
            "This reads each tuner's real status inside SDRTrunk (Enabled, Disabled, Error, Removed), so there is " +
            "no log file to parse and it covers every tuner type.");
        usbSubtitle.getStyleClass().add("settings-page-description");
        usb.addContent(usbSubtitle);
        usb.addContent(callout(
            "Down:  any tuner is in Error (with its actual error message) or has dropped off the bus.  The push " +
            "message names the tuner and the reason.\n" +
            "Up:  all tuners are healthy.\n" +
            "Ignored:  a tuner you have intentionally disabled is not treated as a fault.\n\n" +
            "Down window:  how long the monitor stays down after an error clears, so a brief hiccup is still " +
            "visible downstream (0 = report only the live state)."));

        SettingsSection usbConfiguration = addSection("USB Error Configuration");
        GridPane usbGrid = formGrid();
        row = 0;

        mUsbEnabled = new CheckBox("Enable USB / tuner error monitor");
        mUsbEnabled.setSelected(mHeartbeatSettings.isUsbEnabled());
        usbGrid.add(rightLabel("Monitor:"), 0, row);
        usbGrid.add(mUsbEnabled, 1, row++);

        mUsbKumaUrl = new TextField(mHeartbeatSettings.getUsbKumaUrl());
        mUsbKumaUrl.setPromptText("http://kuma-host:3001/api/push/TOKEN");
        mUsbKumaUrl.setMaxWidth(Double.MAX_VALUE);
        usbGrid.add(rightLabel("Uptime Kuma push URL:"), 0, row);
        usbGrid.add(mUsbKumaUrl, 1, row++);

        mUsbInterval = secondsSpinner(5, 3600, mHeartbeatSettings.getUsbIntervalSeconds(), 5);
        usbGrid.add(rightLabel("Interval (s):"), 0, row);
        usbGrid.add(mUsbInterval, 1, row++);

        mUsbWindow = secondsSpinner(0, 3600, mHeartbeatSettings.getUsbWindowSeconds(), 5);
        usbGrid.add(rightLabel("Down window (s):"), 0, row);
        usbGrid.add(mUsbWindow, 1, row++);

        Button usbSaveButton = new Button("Save");
        usbSaveButton.setOnAction(e -> onSaveUsb());
        mUsbStatusLabel = muted("");
        HBox usbButtons = new HBox(8, usbSaveButton, mUsbStatusLabel);
        usbButtons.setAlignment(Pos.CENTER_LEFT);
        usbConfiguration.addContent(usbGrid);
        usbConfiguration.addContent(usbButtons);
    }

    private void onSave()
    {
        int kumaInterval = (mKumaInterval.getValue() != null) ? mKumaInterval.getValue() : 60;
        int secondInterval = (mSecondInterval.getValue() != null) ? mSecondInterval.getValue() : 60;
        mHeartbeatSettings.store(mEnabled.isSelected(), mKumaUrl.getText(), kumaInterval,
            mSecondUrl.getText(), secondInterval);
        mStatusLabel.setText(mEnabled.isSelected() ? "Saved — heartbeat active." : "Saved — heartbeat disabled.");
    }

    private void onSaveUsb()
    {
        int interval = (mUsbInterval.getValue() != null) ? mUsbInterval.getValue() : 60;
        int window = (mUsbWindow.getValue() != null) ? mUsbWindow.getValue() : 60;
        mHeartbeatSettings.storeUsb(mUsbEnabled.isSelected(), mUsbKumaUrl.getText(), interval, window);
        mUsbStatusLabel.setText(mUsbEnabled.isSelected() ? "Saved - monitor active." : "Saved - monitor disabled.");
    }
}
