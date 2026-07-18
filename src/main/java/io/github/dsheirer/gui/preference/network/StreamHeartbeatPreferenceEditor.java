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

import io.github.dsheirer.audio.broadcast.BroadcastModel;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.StreamHeartbeatEntry;
import io.github.dsheirer.preference.network.StreamHeartbeatPreference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Preference editor for the Streaming Heartbeat Monitor feature.
 *
 * Auto-populates one row per audio streaming configuration found in the active playlist and lets the user
 * push an Uptime Kuma heartbeat per stream, with independent down/up debounce timers so a flapping upstream
 * (e.g. OpenMHz) does not generate false alerts.
 */
public class StreamHeartbeatPreferenceEditor extends HBox
{
    private final StreamHeartbeatPreference mPreference;
    private final BroadcastModel mBroadcastModel;
    private final List<Row> mRows = new ArrayList<>();
    private GridPane mGrid;
    private Label mStatusLabel;

    public StreamHeartbeatPreferenceEditor(UserPreferences userPreferences, BroadcastModel broadcastModel)
    {
        mPreference = userPreferences.getStreamHeartbeatPreference();
        mBroadcastModel = broadcastModel;
        setMaxWidth(Double.MAX_VALUE);

        VBox outer = new VBox();
        outer.setMaxWidth(Double.MAX_VALUE);

        ScrollPane topScroll = new ScrollPane(buildExplanation());
        topScroll.setFitToWidth(true);
        topScroll.setPrefHeight(300);
        topScroll.setMaxHeight(300);
        topScroll.setStyle("-fx-background-color: transparent;");

        VBox tableSection = buildTableSection();
        VBox.setVgrow(tableSection, Priority.ALWAYS);

        outer.getChildren().addAll(topScroll, tableSection);
        HBox.setHgrow(outer, Priority.ALWAYS);
        getChildren().add(outer);
    }

    private VBox buildExplanation()
    {
        VBox box = new VBox(12);
        box.setPadding(new Insets(18, 20, 10, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label("Streaming Heartbeat Monitor");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label subtitle = new Label(
            "Continuously report the health of each audio streaming feed (Broadcastify, OpenMHz, " +
            "RdioScanner, Icecast, etc.) to an Uptime Kuma push monitor — one independent monitor per stream.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");

        box.getChildren().addAll(title, subtitle, new Separator());
        box.getChildren().add(sectionHeader("HOW IT WORKS"));

        Label howItWorks = new Label(
            "Every stream listed below is pulled straight from your active playlist.  Tick Monitor, paste " +
            "that stream's Uptime Kuma push URL, and SDRTrunk reports its live connection state:\n\n" +
            "•  While the stream is CONNECTED it pushes an \"up\" heartbeat every Interval seconds.  That " +
            "steady heartbeat is also your backstop — if SDRTrunk itself stops, every monitor goes silent and " +
            "Kuma alerts.\n" +
            "•  When the stream faults, it pushes \"down\" with the exact reason (Disconnected, Network " +
            "Unavailable, Invalid Credentials, and so on).\n" +
            "•  A DISABLED stream stays green (paused) and never alerts.");
        howItWorks.setWrapText(true);
        box.getChildren().add(howItWorks);

        box.getChildren().add(new Separator());
        box.getChildren().add(sectionHeader("DOWN AFTER / UP AFTER — PER-STREAM DEBOUNCE"));

        Label debounce = new Label(
            "Down after:  the stream must stay faulted continuously for this many seconds before Kuma is told " +
            "it is DOWN.  A shorter blip that recovers first is absorbed and never alerts.\n\n" +
            "Up after:  once DOWN, the stream must hold CONNECTED continuously for this many seconds before Kuma " +
            "is told it recovered.  This stops a feed that flaps up/down/up from bouncing the monitor.\n\n" +
            "Set both loose for a known-flaky feed (OpenMHz: Down 120 / Up 120) and tight for solid infrastructure " +
            "(your own server: Down 5 / Up 0).");
        debounce.setWrapText(true);
        debounce.setStyle("-fx-background-color: #f0f4f8; -fx-padding: 8px; " +
                          "-fx-background-radius: 4px; -fx-font-size: 11.5px;");
        box.getChildren().add(debounce);

        Label restartNote = new Label("⚙  Click Save to apply. Changes take effect immediately — no restart needed.");
        restartNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");
        box.getChildren().add(restartNote);

        return box;
    }

    private VBox buildTableSection()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 18, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        Label header = sectionHeader("STREAMS IN THE ACTIVE PLAYLIST");
        Label hint = new Label("Rows are populated automatically from your playlist. New streams appear with Monitor off.");
        hint.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");

        mGrid = new GridPane();
        mGrid.setHgap(10);
        mGrid.setVgap(6);
        mGrid.setPadding(new Insets(8, 0, 8, 0));

        ColumnConstraints cMonitor = new ColumnConstraints();
        cMonitor.setHalignment(javafx.geometry.HPos.CENTER);
        ColumnConstraints cName = new ColumnConstraints();
        cName.setMinWidth(140);
        ColumnConstraints cUrl = new ColumnConstraints();
        cUrl.setHgrow(Priority.ALWAYS);
        cUrl.setFillWidth(true);
        cUrl.setMinWidth(220);
        ColumnConstraints cInt = new ColumnConstraints();
        ColumnConstraints cDown = new ColumnConstraints();
        ColumnConstraints cUp = new ColumnConstraints();
        mGrid.getColumnConstraints().addAll(cMonitor, cName, cUrl, cInt, cDown, cUp);

        ScrollPane scroll = new ScrollPane(mGrid);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(220);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        Button refreshButton = new Button("Refresh from playlist");
        refreshButton.setOnAction(e -> populateRows());
        mStatusLabel = new Label("");
        mStatusLabel.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");

        HBox buttons = new HBox(8, saveButton, refreshButton, mStatusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(header, hint, scroll, buttons);

        populateRows();
        return box;
    }

    /**
     * Rebuilds the row list from the active playlist stream names, merged with any saved settings.
     */
    private void populateRows()
    {
        mRows.clear();
        mGrid.getChildren().clear();

        //Header row
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

        for(StreamHeartbeatEntry saved: mPreference.getEntries())
        {
            names.add(saved.getStreamName());
        }

        int rowIndex = 1;

        for(String name: names)
        {
            StreamHeartbeatEntry saved = mPreference.getEntry(name);
            StreamHeartbeatEntry working = (saved != null) ? saved : new StreamHeartbeatEntry();
            working.setStreamName(name);

            boolean inPlaylist = (mBroadcastModel != null) && mBroadcastModel.getBroadcastConfigurationNames().contains(name);

            Row row = new Row(working, inPlaylist);
            mRows.add(row);

            mGrid.add(row.monitor, 0, rowIndex);
            mGrid.add(row.nameLabel, 1, rowIndex);
            mGrid.add(row.kumaUrl, 2, rowIndex);
            mGrid.add(row.interval, 3, rowIndex);
            mGrid.add(row.downAfter, 4, rowIndex);
            mGrid.add(row.upAfter, 5, rowIndex);
            rowIndex++;
        }

        if(mRows.isEmpty())
        {
            Label empty = new Label("No streaming configurations found in the active playlist.");
            empty.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");
            mGrid.add(empty, 0, 1, 6, 1);
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

        mPreference.setEntries(toSave);

        if(mStatusLabel != null)
        {
            mStatusLabel.setText("Saved " + toSave.size() + " monitored stream(s).");
        }
    }

    private Label columnHeader(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        return l;
    }

    private Label sectionHeader(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        return l;
    }

    /**
     * Bundles the controls for a single stream row and the working entry they edit.
     */
    private static class Row
    {
        private final StreamHeartbeatEntry entry;
        private final CheckBox monitor;
        private final Label nameLabel;
        private final TextField kumaUrl;
        private final Spinner<Integer> interval;
        private final Spinner<Integer> downAfter;
        private final Spinner<Integer> upAfter;

        Row(StreamHeartbeatEntry entry, boolean inPlaylist)
        {
            this.entry = entry;

            monitor = new CheckBox();
            monitor.setSelected(entry.isEnabled());

            nameLabel = new Label(inPlaylist ? entry.getStreamName() : entry.getStreamName() + "  (not in playlist)");
            if(!inPlaylist)
            {
                nameLabel.setStyle("-fx-text-fill: #996600;");
            }

            kumaUrl = new TextField(entry.getKumaUrl());
            kumaUrl.setPromptText("http://kuma-host:3001/api/push/TOKEN");
            kumaUrl.setMaxWidth(Double.MAX_VALUE);

            interval = spinner(5, 3600, entry.getIntervalSeconds(), 5);
            downAfter = spinner(0, 3600, entry.getDownAfterSeconds(), 5);
            upAfter = spinner(0, 3600, entry.getUpAfterSeconds(), 5);
        }

        private static Spinner<Integer> spinner(int min, int max, int value, int step)
        {
            Spinner<Integer> s = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, Math.max(min, Math.min(max, value)), step));
            s.setEditable(true);
            s.setPrefWidth(96);
            return s;
        }

        StreamHeartbeatEntry harvest()
        {
            entry.setEnabled(monitor.isSelected());
            entry.setKumaUrl(kumaUrl.getText());

            if(interval.getValue() != null)
            {
                entry.setIntervalSeconds(interval.getValue());
            }

            if(downAfter.getValue() != null)
            {
                entry.setDownAfterSeconds(downAfter.getValue());
            }

            if(upAfter.getValue() != null)
            {
                entry.setUpAfterSeconds(upAfter.getValue());
            }

            return entry;
        }
    }
}
