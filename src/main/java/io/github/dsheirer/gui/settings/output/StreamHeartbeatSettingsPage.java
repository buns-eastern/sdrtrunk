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

import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.StreamHeartbeatEntry;
import io.github.dsheirer.settings.network.StreamHeartbeatSettings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Streaming Heartbeat: reports the connection state of each audio stream in the playlist to an Uptime Kuma push
 * monitor.  Changes on this page are stored when the user clicks Save.
 */
public class StreamHeartbeatSettingsPage extends OutputSettingsPage
{
    private final StreamHeartbeatSettings mHeartbeatSettings;
    private final BroadcastModel mBroadcastModel;
    private final List<Row> mRows = new ArrayList<>();
    private final GridPane mGrid = new GridPane();
    private Label mStatusLabel;

    /**
     * Constructs an instance
     * @param settings to edit
     * @param playlistManager for the streams in the active playlist, or null
     */
    public StreamHeartbeatSettingsPage(Settings settings, PlaylistManager playlistManager)
    {
        super(SettingsPageType.STREAMING_HEARTBEAT, settings);
        mHeartbeatSettings = settings.getStreamHeartbeatSettings();
        mBroadcastModel = playlistManager != null ? playlistManager.getBroadcastModel() : null;

        SettingsSection howItWorks = addSection("How It Works");
        howItWorks.addContent(paragraph(
            "Every stream listed below is pulled straight from your active playlist.  Tick Monitor, paste that " +
            "stream's Uptime Kuma push URL, and SDRTrunk reports its live connection state:\n\n" +
            "•  While the stream is CONNECTED it pushes an \"up\" heartbeat every Interval seconds.  That steady " +
            "heartbeat is also your backstop — if SDRTrunk itself stops, every monitor goes silent and Kuma alerts.\n" +
            "•  When the stream faults, it pushes \"down\" with the exact reason so you can see what went wrong at " +
            "a glance.\n" +
            "•  A stream you turn off (Disabled) is treated as paused — reported \"up\" so it never false-alerts.  " +
            "A stream you remove from the playlist is simply dropped from monitoring."));

        SettingsSection buckets = addSection("Up, Paused, or Down");
        buckets.addContent(paragraph(
            "UP — the single healthy state:  Connected (actively streaming).\n" +
            "PAUSED (never alerts) — Disabled:  a stream you intentionally turned off.\n" +
            "DOWN — any of the 14 fault states below, once it persists past the stream's Down after time."));
        buckets.addContent(callout(
            "A stream is reported DOWN when it sits in any of these states past its Down after time:\n\n" +
            "1.   Connecting — reconnecting, not yet carrying audio\n" +
            "2.   Disconnected — dropped the connection to the server\n" +
            "3.   Network Unavailable — no network, or the server address cannot be resolved\n" +
            "4.   No Server — the server is unknown or unreachable\n" +
            "5.   Ready — configured but not yet connected\n" +
            "6.   Temporary Broadcast Error — error while sending; will reset and retry\n" +
            "7.   Configuration Error — settings the remote server rejects\n" +
            "8.   Invalid User Name/Password — credentials were rejected\n" +
            "9.   Invalid Mount/Stream ID — wrong mount point or stream identifier\n" +
            "10.  Invalid Settings — the configuration is not valid\n" +
            "11.  Max Sources Exceeded — the server is at its source limit\n" +
            "12.  Mount Point In Use — that mount point is already taken\n" +
            "13.  Unsupported Audio Type — the server will not accept this audio format\n" +
            "14.  Error — unspecified streaming error"));
        buckets.addContent(muted(
            "A stream you stop or disable yourself will NOT show as DOWN in Kuma — that is deliberate, so planned " +
            "downtime never pages you.  Only a stream that is supposed to be running, and is not, reports down."));

        SettingsSection debounce = addSection("Down After / Up After — Per-Stream Debounce");
        debounce.addContent(callout(
            "Down after:  the stream must stay faulted continuously for this many seconds before Kuma is told it " +
            "is DOWN.  A shorter blip that recovers first is absorbed and never alerts.\n\n" +
            "Up after:  once DOWN, the stream must hold Connected continuously for this many seconds before Kuma is " +
            "told it recovered.  This stops a feed that flaps up/down/up from bouncing the monitor.\n\n" +
            "Set both loose for a feed you know reconnects often (for example Down 120 / Up 120), and tight for a " +
            "feed you expect to be rock-solid (for example Down 5 / Up 0)."));
        debounce.addContent(saveNote("Click Save to apply. Changes take effect immediately — no restart needed."));

        SettingsSection streams = addSection("Streams in the Active Playlist");
        streams.addContent(muted(
            "Rows are populated automatically from your playlist. New streams appear with Monitor off."));

        mGrid.setHgap(10);
        mGrid.setVgap(6);
        mGrid.setPadding(new Insets(8, 0, 8, 0));
        ColumnConstraints monitorColumn = new ColumnConstraints();
        monitorColumn.setHalignment(HPos.CENTER);
        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setMinWidth(140);
        ColumnConstraints urlColumn = new ColumnConstraints();
        urlColumn.setHgrow(Priority.ALWAYS);
        urlColumn.setFillWidth(true);
        urlColumn.setMinWidth(220);
        mGrid.getColumnConstraints().addAll(monitorColumn, nameColumn, urlColumn, new ColumnConstraints(),
            new ColumnConstraints(), new ColumnConstraints());
        streams.addContent(mGrid);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        Button refreshButton = new Button("Refresh from playlist");
        refreshButton.setOnAction(e -> populateRows());
        mStatusLabel = muted("");
        HBox buttons = new HBox(8, saveButton, refreshButton, mStatusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);
        streams.addContent(buttons);

        populateRows();
    }

    /**
     * Rebuilds the row list from the active playlist stream names, merged with any saved settings.
     */
    private void populateRows()
    {
        mRows.clear();
        mGrid.getChildren().clear();

        mGrid.add(columnHeader("Monitor"), 0, 0);
        mGrid.add(columnHeader("Stream"), 1, 0);
        mGrid.add(columnHeader("Uptime Kuma push URL"), 2, 0);
        mGrid.add(columnHeader("Interval (s)"), 3, 0);
        mGrid.add(columnHeader("Down after (s)"), 4, 0);
        mGrid.add(columnHeader("Up after (s)"), 5, 0);

        //Preserve ordering: playlist streams first, then any saved orphan streams no longer in the playlist
        Set<String> names = new LinkedHashSet<>();

        if(mBroadcastModel != null)
        {
            names.addAll(mBroadcastModel.getBroadcastConfigurationNames());
        }

        for(StreamHeartbeatEntry saved: mHeartbeatSettings.getEntries())
        {
            names.add(saved.getStreamName());
        }

        int rowIndex = 1;

        for(String name: names)
        {
            StreamHeartbeatEntry saved = mHeartbeatSettings.getEntry(name);
            StreamHeartbeatEntry working = (saved != null) ? saved : new StreamHeartbeatEntry();
            working.setStreamName(name);

            boolean inPlaylist = (mBroadcastModel != null) &&
                mBroadcastModel.getBroadcastConfigurationNames().contains(name);

            Row row = new Row(working, inPlaylist);
            mRows.add(row);

            mGrid.add(row.mMonitor, 0, rowIndex);
            mGrid.add(row.mNameLabel, 1, rowIndex);
            mGrid.add(row.mKumaUrl, 2, rowIndex);
            mGrid.add(row.mInterval, 3, rowIndex);
            mGrid.add(row.mDownAfter, 4, rowIndex);
            mGrid.add(row.mUpAfter, 5, rowIndex);
            rowIndex++;
        }

        if(mRows.isEmpty())
        {
            mGrid.add(muted("No streaming configurations found in the active playlist."), 0, 1, 6, 1);
        }

        if(mStatusLabel != null)
        {
            mStatusLabel.setText("");
        }
    }

    private void onSave()
    {
        List<StreamHeartbeatEntry> toSave = new ArrayList<>();

        for(Row row: mRows)
        {
            StreamHeartbeatEntry entry = row.harvest();

            //Only persist rows the user actually configured to keep the store lean
            if(entry.isEnabled() || !entry.getKumaUrl().isBlank())
            {
                toSave.add(entry);
            }
        }

        mHeartbeatSettings.setEntries(toSave);
        mStatusLabel.setText("Saved " + toSave.size() + " monitored stream(s).");
    }

    private static Label columnHeader(String text)
    {
        Label label = new Label(text);
        label.getStyleClass().add("settings-column-header");
        return label;
    }

    /**
     * Bundles the controls for a single stream row and the working entry they edit.
     */
    private static class Row
    {
        private final StreamHeartbeatEntry mEntry;
        private final CheckBox mMonitor;
        private final Label mNameLabel;
        private final TextField mKumaUrl;
        private final Spinner<Integer> mInterval;
        private final Spinner<Integer> mDownAfter;
        private final Spinner<Integer> mUpAfter;

        Row(StreamHeartbeatEntry entry, boolean inPlaylist)
        {
            mEntry = entry;
            mMonitor = new CheckBox();
            mMonitor.setSelected(entry.isEnabled());

            mNameLabel = new Label(inPlaylist ? entry.getStreamName() : entry.getStreamName() + "  (not in playlist)");

            if(!inPlaylist)
            {
                mNameLabel.getStyleClass().add("settings-warning-text");
            }

            mKumaUrl = new TextField(entry.getKumaUrl());
            mKumaUrl.setPromptText("http://kuma-host:3001/api/push/TOKEN");
            mKumaUrl.setMaxWidth(Double.MAX_VALUE);

            mInterval = spinner(5, 3600, entry.getIntervalSeconds());
            mDownAfter = spinner(0, 3600, entry.getDownAfterSeconds());
            mUpAfter = spinner(0, 3600, entry.getUpAfterSeconds());
        }

        private static Spinner<Integer> spinner(int min, int max, int value)
        {
            Spinner<Integer> spinner = secondsSpinner(min, max, value, 5);
            spinner.setPrefWidth(96);
            return spinner;
        }

        StreamHeartbeatEntry harvest()
        {
            mEntry.setEnabled(mMonitor.isSelected());
            mEntry.setKumaUrl(mKumaUrl.getText());

            if(mInterval.getValue() != null)
            {
                mEntry.setIntervalSeconds(mInterval.getValue());
            }

            if(mDownAfter.getValue() != null)
            {
                mEntry.setDownAfterSeconds(mDownAfter.getValue());
            }

            if(mUpAfter.getValue() != null)
            {
                mEntry.setUpAfterSeconds(mUpAfter.getValue());
            }

            return mEntry;
        }
    }
}
