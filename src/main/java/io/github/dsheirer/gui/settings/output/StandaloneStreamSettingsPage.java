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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.KumaChannelMonitorEntry;
import io.github.dsheirer.settings.network.StandaloneStreamSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.controlsfx.control.ToggleSwitch;

/**
 * Standalone Channel Heartbeat: TCP liveness heartbeats for running conventional channels, and per-channel monitors
 * that ping a URL while a chosen channel runs.  The switch, port and interval are stored as soon as they change;
 * the per-channel monitors are stored when the user clicks Save.
 */
public class StandaloneStreamSettingsPage extends OutputSettingsPage
{
    private final StandaloneStreamSettings mStreamSettings;
    private final ChannelModel mChannelModel;
    private final ObservableList<KumaChannelMonitorEntry> mKumaItems;
    private TableView<KumaChannelMonitorEntry> mKumaTable;
    private ComboBox<ChannelOption> mKumaPicker;
    private TextField mKumaUrl;
    private Spinner<Integer> mKumaInterval;
    private Label mKumaStatus;

    /**
     * Constructs an instance
     * @param settings to edit
     * @param playlistManager for the channels offered in the picker, or null
     */
    public StandaloneStreamSettingsPage(Settings settings, PlaylistManager playlistManager)
    {
        super(SettingsPageType.STANDALONE_CHANNEL_HEARTBEAT, settings);
        mStreamSettings = settings.getStandaloneStreamSettings();
        mChannelModel = playlistManager != null ? playlistManager.getChannelModel() : null;
        mKumaItems = FXCollections.observableArrayList(mStreamSettings.getKumaMonitors());

        ToggleSwitch enableToggle = new ToggleSwitch();
        enableToggle.setSelected(mStreamSettings.isEnabled());
        enableToggle.selectedProperty().addListener((obs, oldVal, enabled) -> mStreamSettings.setEnabled(enabled));

        SettingsSection section = addSection(null);
        section.addRow("Enable Standalone Channel Heartbeat", enableToggle);
        section.addContent(warningNote("Changes take effect after restarting SDRTrunk."));
        section.addContent(portRow("TCP port:", mStreamSettings.getPort(), mStreamSettings::setPort));

        Label intervalLabel = new Label("Heartbeat (s):");
        intervalLabel.setMinWidth(90);
        Spinner<Integer> intervalSpinner = secondsSpinner(1, 3600, mStreamSettings.getIntervalSeconds(), 1);
        intervalSpinner.setPrefWidth(100);
        intervalSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if(newVal != null)
            {
                mStreamSettings.setIntervalSeconds(newVal);
            }
        });
        HBox intervalRow = new HBox(10, intervalLabel, intervalSpinner);
        intervalRow.setAlignment(Pos.CENTER_LEFT);
        section.addContent(intervalRow);

        SettingsSection format = addSection("Message Format  ·  Newline-delimited JSON (NDJSON)");
        String formatExample =
            "// Channel started (hit PLAY)\n" +
            "{\"type\":\"channel_up\",\"channel\":\"Fire Dispatch VHF\",\"timestamp\":\"2026-01-01 12:00:00\"}\n\n" +
            "// Heartbeat -- repeats every interval while the channel runs\n" +
            "{\"type\":\"heartbeat\",\"channel\":\"Fire Dispatch VHF\",\"status\":\"up\",\"timestamp\":\"2026-01-01 12:00:30\"}\n\n" +
            "// Channel stopped\n" +
            "{\"type\":\"channel_down\",\"channel\":\"Fire Dispatch VHF\",\"timestamp\":\"2026-01-01 12:05:10\"}\n\n" +
            "// Socket keepalive -- only while no standalone channel is running, so the socket stays verifiably alive\n" +
            "{\"type\":\"keepalive\"}";
        format.addContent(codeBox(formatExample, 200));

        SettingsSection monitors = addSection("Per-Channel Liveness Monitors  ·  push a URL while a channel runs");
        monitors.addContent(callout(
            "Pick a conventional channel and give it a URL to ping.  While that channel is running, SDRTrunk " +
            "GETs the URL every interval; when the channel stops (or SDRTrunk stops), the pings stop.  Point it " +
            "at an Uptime Kuma push monitor's URL and the monitor stays up while the channel runs and goes down " +
            "when it stops — one push monitor per channel.  This tracks running, not traffic, so a quiet " +
            "channel still counts as up.  It works whether or not the TCP heartbeat above is enabled."));
        monitors.addContent(muted(
            "Kuma tip: create a Push monitor, set its Heartbeat Interval a little longer than the interval you " +
            "pick here (for example ping every 60 s, Kuma interval 120 s), then paste that monitor's push URL " +
            "below.  The URL is sent verbatim, so its ?status=up query is preserved; special characters are " +
            "percent-encoded automatically."));
        monitors.addContent(saveNote(
            "Click Save to store. A change takes effect when the channel is next started (restart " +
            "SDRTrunk, or stop and play the channel)."));
        monitors.addContent(getKumaTable());

        FilteredList<ChannelOption> filtered =
            new FilteredList<>(FXCollections.observableArrayList(buildChannelOptions()), o -> true);
        mKumaPicker = new ComboBox<>(filtered);
        mKumaPicker.setPromptText("Pick a channel");
        mKumaPicker.setPrefWidth(280);

        TextField kumaFilter = new TextField();
        kumaFilter.setPromptText("Filter by system or channel");
        kumaFilter.setPrefWidth(180);
        kumaFilter.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = (newValue == null) ? "" : newValue.trim().toLowerCase();
            filtered.setPredicate(option -> query.isEmpty() || option.matches(query));
        });

        HBox pickerRow = new HBox(8, new Label("Channel:"), kumaFilter, mKumaPicker);
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        mKumaInterval = secondsSpinner(1, 3600, 60, 1);
        mKumaInterval.setPrefWidth(90);

        mKumaUrl = new TextField();
        mKumaUrl.setPromptText("https://your-kuma/api/push/TOKEN?status=up&msg=OK&ping=");
        mKumaUrl.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mKumaUrl, Priority.ALWAYS);
        Button addButton = new Button("Add");
        addButton.setOnAction(e -> onAddKumaMonitor());
        HBox urlRow = new HBox(8, new Label("Ping every (s):"), mKumaInterval, new Label("URL:"), mKumaUrl, addButton);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        Button removeButton = new Button("Remove selected monitor");
        removeButton.getStyleClass().add("settings-danger-button");
        removeButton.setOnAction(e -> onRemoveKumaMonitor());

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSaveKuma());
        mKumaStatus = muted("");
        Region saveSpacer = new Region();
        HBox.setHgrow(saveSpacer, Priority.ALWAYS);
        HBox saveRow = new HBox(8, saveButton, mKumaStatus, saveSpacer, removeButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        monitors.addContent(pickerRow);
        monitors.addContent(urlRow);
        monitors.addContent(saveRow);
    }

    @SuppressWarnings("unchecked")
    private TableView<KumaChannelMonitorEntry> getKumaTable()
    {
        if(mKumaTable == null)
        {
            mKumaTable = new TableView<>(mKumaItems);
            mKumaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            mKumaTable.setPlaceholder(muted("No channel monitors yet — pick a channel and add a URL below."));
            mKumaTable.setPrefHeight(180);

            TableColumn<KumaChannelMonitorEntry,String> channelColumn = new TableColumn<>("Channel");
            channelColumn.setMinWidth(120);
            channelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChannelName()));

            TableColumn<KumaChannelMonitorEntry,String> systemColumn = new TableColumn<>("System");
            systemColumn.setMaxWidth(160);
            systemColumn.setMinWidth(80);
            systemColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getSystem().isBlank() ? "(any)" : data.getValue().getSystem()));

            TableColumn<KumaChannelMonitorEntry,Number> intervalColumn = new TableColumn<>("Every (s)");
            intervalColumn.setMaxWidth(90);
            intervalColumn.setMinWidth(70);
            intervalColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getIntervalSeconds()));

            TableColumn<KumaChannelMonitorEntry,String> urlColumn = new TableColumn<>("URL");
            urlColumn.setMinWidth(220);
            urlColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUrl()));

            mKumaTable.getColumns().addAll(channelColumn, systemColumn, intervalColumn, urlColumn);
        }

        return mKumaTable;
    }

    /**
     * Builds the channel picker options from the channel model: every non-trunking channel (name does not start
     * with "T-"), grouped by system, de-duplicated and sorted.
     */
    private List<ChannelOption> buildChannelOptions()
    {
        List<ChannelOption> options = new ArrayList<>();

        if(mChannelModel == null)
        {
            return options;
        }

        try
        {
            Set<String> seen = new HashSet<>();

            for(Channel channel: mChannelModel.getChannels())
            {
                String name = channel.getName();

                if(name == null || name.isBlank() || name.startsWith("T-"))
                {
                    continue;
                }

                String system = channel.getSystem() != null ? channel.getSystem() : "";

                if(seen.add(system.toLowerCase() + "|" + name.toLowerCase()))
                {
                    options.add(new ChannelOption(name, system));
                }
            }
        }
        catch(Throwable t)
        {
            //If channels can't be read, the picker is simply empty.
        }

        options.sort((x, y) -> {
            int c = x.system.compareToIgnoreCase(y.system);
            return c != 0 ? c : x.name.compareToIgnoreCase(y.name);
        });

        return options;
    }

    private void onAddKumaMonitor()
    {
        ChannelOption option = mKumaPicker.getValue();

        if(option == null)
        {
            mKumaStatus.setText("Pick a channel first.");
            return;
        }

        String url = mKumaUrl.getText() != null ? mKumaUrl.getText().trim() : "";

        if(url.isEmpty())
        {
            mKumaStatus.setText("Enter a URL to ping.");
            return;
        }

        for(KumaChannelMonitorEntry existing: mKumaItems)
        {
            if(existing.getChannelName().equalsIgnoreCase(option.name) &&
                existing.getSystem().equalsIgnoreCase(option.system))
            {
                mKumaStatus.setText("A monitor for " + option.name + " is already in the list.");
                return;
            }
        }

        int interval = (mKumaInterval.getValue() != null) ? mKumaInterval.getValue() : 60;
        mKumaItems.add(new KumaChannelMonitorEntry(option.name, option.system, url, interval));
        mKumaUrl.clear();
        mKumaStatus.setText("");
    }

    private void onRemoveKumaMonitor()
    {
        KumaChannelMonitorEntry selected = getKumaTable().getSelectionModel().getSelectedItem();

        if(selected == null)
        {
            mKumaStatus.setText("Select a monitor in the list first, then Remove.");
            return;
        }

        String system = selected.getSystem().isBlank() ? "any system" : selected.getSystem();
        String description = selected.getChannelName() + "  (" + system + ")";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove monitor");
        confirm.setHeaderText("Remove this channel monitor?");
        confirm.setContentText(description +
            "\n\nThis only removes the liveness ping — it does not change your playlist.");

        if(getScene() != null && getScene().getWindow() != null)
        {
            confirm.initOwner(getScene().getWindow());
        }

        Optional<ButtonType> result = confirm.showAndWait();

        if(result.isPresent() && result.get() == ButtonType.OK)
        {
            mKumaItems.remove(selected);
            mKumaStatus.setText("Removed " + description + ".");
        }
    }

    private void onSaveKuma()
    {
        mStreamSettings.storeKumaMonitors(new ArrayList<>(mKumaItems));
        mKumaStatus.setText("Saved " + mKumaItems.size() + " monitor(s).");
    }

    /**
     * A pickable channel for the monitor dropdown, grouped under its system.
     */
    private static class ChannelOption
    {
        private final String name;
        private final String system;

        ChannelOption(String name, String system)
        {
            this.name = name != null ? name : "";
            this.system = system != null ? system : "";
        }

        @Override
        public String toString()
        {
            return system.isEmpty() ? name : system + "  ·  " + name;
        }

        boolean matches(String query)
        {
            return system.toLowerCase().contains(query) || name.toLowerCase().contains(query);
        }
    }
}
