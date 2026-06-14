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
package io.github.dsheirer.gui.preference.network;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.StandaloneStreamPreference;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Preference editor for the Standalone Channel Heartbeat output feature (default port 9504).
 */
public class StandaloneStreamPreferenceEditor extends HBox
{
    private final StandaloneStreamPreference mPreference;

    public StandaloneStreamPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getStandaloneStreamPreference();
        setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    private VBox buildContent()
    {
        VBox root = new VBox(14);
        root.setPadding(new Insets(18, 20, 18, 20));
        root.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label("Standalone Channel Heartbeat");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label subtitle = new Label(
            "Emit liveness heartbeats for every running standalone (non-trunking) channel — NBFM, AM, " +
            "conventional DMR, etc. — over TCP, so a separate application can verify which conventional " +
            "channels are actually running.\n\n" +
            "Trunking systems are intentionally excluded: their control-channel traffic already advertises " +
            "liveness on the raw/event streams.  A conventional channel that is running but quiet, however, " +
            "emits nothing elsewhere — so this stream is the way to confirm it is alive.\n\n" +
            "Each running channel announces a channel_up the moment it starts (PLAY), a heartbeat on the " +
            "configured interval while it runs, and a channel_down when it stops.  Demultiplex by the " +
            "\"channel\" field.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #555555;");

        root.getChildren().addAll(title, subtitle, new Separator());

        // Enable toggle
        ToggleSwitch enableToggle = new ToggleSwitch();
        enableToggle.setSelected(mPreference.isEnabled());
        enableToggle.selectedProperty().addListener((obs, oldVal, enabled) ->
                mPreference.setEnabled(enabled));
        HBox enableRow = new HBox(10, enableToggle, bold("Enable Standalone Channel Heartbeat"));
        enableRow.setAlignment(Pos.CENTER_LEFT);

        Label restartNote = new Label("⚠  Changes take effect after restarting SDRTrunk.");
        restartNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");

        root.getChildren().addAll(enableRow, restartNote);

        // Port row
        Label portLabel = new Label("TCP port:");
        portLabel.setMinWidth(90);
        Spinner<Integer> portSpinner = new Spinner<>();
        portSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1024, 65535, mPreference.getPort()));
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(100);
        portSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
        {
            if(newVal != null) mPreference.setPort(newVal);
        });
        HBox portRow = new HBox(10, portLabel, portSpinner);
        portRow.setAlignment(Pos.CENTER_LEFT);

        // Interval row
        Label intervalLabel = new Label("Heartbeat (s):");
        intervalLabel.setMinWidth(90);
        Spinner<Integer> intervalSpinner = new Spinner<>();
        intervalSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 3600, mPreference.getIntervalSeconds()));
        intervalSpinner.setEditable(true);
        intervalSpinner.setPrefWidth(100);
        intervalSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
        {
            if(newVal != null) mPreference.setIntervalSeconds(newVal);
        });
        HBox intervalRow = new HBox(10, intervalLabel, intervalSpinner);
        intervalRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(portRow, intervalRow, new Separator());

        // Message format
        Label fmtHeader = new Label("MESSAGE FORMAT  ·  Newline-delimited JSON (NDJSON)");
        fmtHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1a5276;");
        root.getChildren().add(fmtHeader);

        String formatExample =
            "// Channel started (hit PLAY)\n" +
            "{\"type\":\"channel_up\",\"channel\":\"Fire Dispatch VHF\",\"timestamp\":\"2026-01-01 12:00:00\"}\n\n" +
            "// Heartbeat -- repeats every interval while the channel runs\n" +
            "{\"type\":\"heartbeat\",\"channel\":\"Fire Dispatch VHF\",\"status\":\"up\",\"timestamp\":\"2026-01-01 12:00:30\"}\n\n" +
            "// Channel stopped\n" +
            "{\"type\":\"channel_down\",\"channel\":\"Fire Dispatch VHF\",\"timestamp\":\"2026-01-01 12:05:10\"}\n\n" +
            "// Socket keepalive -- only while no standalone channel is running, so the socket stays verifiably alive\n" +
            "{\"type\":\"keepalive\"}";
        root.getChildren().add(codeBox(formatExample, 200));

        return root;
    }

    private Label bold(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private TextArea codeBox(String code, double prefHeight)
    {
        TextArea ta = new TextArea(code);
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.setPrefHeight(prefHeight);
        ta.setStyle("-fx-font-family: 'Courier New', monospace; -fx-font-size: 11px; " +
                    "-fx-control-inner-background: #1e2329; -fx-text-fill: #abb2bf; " +
                    "-fx-border-color: #444; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        VBox.setVgrow(ta, Priority.NEVER);
        return ta;
    }
}
