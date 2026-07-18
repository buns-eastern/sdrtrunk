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

import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.SoftwareHeartbeatPreference;
import javafx.geometry.HPos;
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
 * Preference editor for the Software Heartbeat Monitor feature - a single configuration confirming the
 * application itself is alive by pushing a periodic heartbeat to one or two URLs.
 */
public class SoftwareHeartbeatPreferenceEditor extends HBox
{
    private final SoftwareHeartbeatPreference mPreference;
    private CheckBox mEnabled;
    private TextField mKumaUrl;
    private TextField mSecondUrl;
    private Spinner<Integer> mKumaInterval;
    private Spinner<Integer> mSecondInterval;
    private Label mStatusLabel;

    public SoftwareHeartbeatPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getSoftwareHeartbeatPreference();
        setMaxWidth(Double.MAX_VALUE);

        VBox outer = new VBox();
        outer.setMaxWidth(Double.MAX_VALUE);

        ScrollPane topScroll = new ScrollPane(buildExplanation());
        topScroll.setFitToWidth(true);
        topScroll.setPrefHeight(300);
        topScroll.setMaxHeight(300);
        topScroll.setStyle("-fx-background-color: transparent;");

        VBox formSection = buildForm();
        VBox.setVgrow(formSection, Priority.ALWAYS);

        outer.getChildren().addAll(topScroll, formSection);
        HBox.setHgrow(outer, Priority.ALWAYS);
        getChildren().add(outer);
    }

    private VBox buildExplanation()
    {
        VBox box = new VBox(12);
        box.setPadding(new Insets(18, 20, 10, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label("Software Heartbeat Monitor");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label subtitle = new Label(
            "Confirm that SDRTrunk itself is alive by pushing a periodic heartbeat to your monitoring tools.  " +
            "If the application stops or crashes, the heartbeats stop and your monitors alert.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");

        box.getChildren().addAll(title, subtitle, new Separator());
        box.getChildren().add(sectionHeader("HOW IT WORKS"));

        Label howItWorks = new Label(
            "While SDRTrunk is running it sends a heartbeat to each URL you configure, each at its own interval.\n\n" +
            "There is nothing to compute as \"down\" from inside the application — if this code is running, the " +
            "application is up.  A stopped or crashed application simply stops sending, and your monitor flips to " +
            "down on its own (silence = down).  This replaces an external watchdog script that only checks whether " +
            "the application process is alive.");
        howItWorks.setWrapText(true);
        box.getChildren().add(howItWorks);

        box.getChildren().add(new Separator());
        box.getChildren().add(sectionHeader("THE TWO URLS"));

        Label urls = new Label(
            "Uptime Kuma push URL:  paste just the base push URL (the token is in the path).  SDRTrunk adds " +
            "status and message parameters itself, so any example query on the end is ignored.\n\n" +
            "Second heartbeat URL (optional):  sent exactly as entered, every interval — nothing is added or " +
            "changed.  Put any complete web address here, including its own parameters, to notify a second " +
            "monitoring endpoint.");
        urls.setWrapText(true);
        urls.setStyle("-fx-background-color: #f0f4f8; -fx-padding: 8px; " +
                      "-fx-background-radius: 4px; -fx-font-size: 11.5px;");
        box.getChildren().add(urls);

        Label kumaNote = new Label(
            "Tip: set the Kuma monitor's own Heartbeat Interval to roughly double the Kuma interval below, with a " +
            "retry or two, so a single missed beat does not flip it red.");
        kumaNote.setWrapText(true);
        kumaNote.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        box.getChildren().add(kumaNote);

        Label saveNote = new Label("⚙  Click Save to apply. Changes take effect immediately — no restart needed.");
        saveNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");
        box.getChildren().add(saveNote);

        return box;
    }

    private VBox buildForm()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 18, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().add(sectionHeader("CONFIGURATION"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 0, 8, 0));

        ColumnConstraints labelCol = new ColumnConstraints(150);
        labelCol.setHalignment(HPos.RIGHT);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        int row = 0;

        mEnabled = new CheckBox("Enable software heartbeat");
        mEnabled.setSelected(mPreference.isEnabled());
        grid.add(rightLabel("Monitor:"), 0, row);
        grid.add(mEnabled, 1, row++);

        mKumaUrl = new TextField(mPreference.getKumaUrl());
        mKumaUrl.setPromptText("http://kuma-host:3001/api/push/TOKEN");
        mKumaUrl.setMaxWidth(Double.MAX_VALUE);
        grid.add(rightLabel("Uptime Kuma push URL:"), 0, row);
        grid.add(mKumaUrl, 1, row++);

        mKumaInterval = intervalSpinner(mPreference.getKumaIntervalSeconds());
        grid.add(rightLabel("Kuma interval (s):"), 0, row);
        grid.add(mKumaInterval, 1, row++);

        mSecondUrl = new TextField(mPreference.getSecondUrl());
        mSecondUrl.setPromptText("https://your-endpoint.example.com/heartbeat?...  (optional, sent as-is)");
        mSecondUrl.setMaxWidth(Double.MAX_VALUE);
        grid.add(rightLabel("Second heartbeat URL:"), 0, row);
        grid.add(mSecondUrl, 1, row++);

        mSecondInterval = intervalSpinner(mPreference.getSecondIntervalSeconds());
        grid.add(rightLabel("Second URL interval (s):"), 0, row);
        grid.add(mSecondInterval, 1, row++);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        mStatusLabel = new Label("");
        mStatusLabel.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        HBox buttons = new HBox(8, saveButton, mStatusLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(grid, buttons);
        return box;
    }

    private void onSave()
    {
        int kumaInterval = (mKumaInterval.getValue() != null) ? mKumaInterval.getValue() : 60;
        int secondInterval = (mSecondInterval.getValue() != null) ? mSecondInterval.getValue() : 60;
        mPreference.store(mEnabled.isSelected(), mKumaUrl.getText(), kumaInterval,
            mSecondUrl.getText(), secondInterval);

        if(mStatusLabel != null)
        {
            mStatusLabel.setText(mEnabled.isSelected() ? "Saved — heartbeat active." : "Saved — heartbeat disabled.");
        }
    }

    private Spinner<Integer> intervalSpinner(int value)
    {
        Spinner<Integer> s = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            5, 3600, Math.max(5, Math.min(3600, value)), 5));
        s.setEditable(true);
        s.setPrefWidth(110);
        return s;
    }

    private Label sectionHeader(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        return l;
    }

    private Label rightLabel(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        GridPane.setHalignment(l, HPos.RIGHT);
        return l;
    }
}
