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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.KumaChannelMonitorEntry;
import io.github.dsheirer.preference.network.StandaloneStreamPreference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Preference editor for the Standalone Channel Heartbeat output feature (default port 9504), plus the
 * per-channel liveness monitors that ping an external URL (for example an Uptime Kuma push monitor) while a
 * chosen conventional channel is running.
 */
public class StandaloneStreamPreferenceEditor extends HBox
{
    private final StandaloneStreamPreference mPreference;
    private final ChannelModel mChannelModel;

    private TableView<KumaChannelMonitorEntry> mKumaTable;
    private ObservableList<KumaChannelMonitorEntry> mKumaItems;
    private ComboBox<ChannelOption> mKumaPicker;
    private TextField mKumaFilter;
    private TextField mKumaUrl;
    private Spinner<Integer> mKumaInterval;
    private Label mKumaStatus;

    public StandaloneStreamPreferenceEditor(UserPreferences userPreferences, ChannelModel channelModel)
    {
        mPreference = userPreferences.getStandaloneStreamPreference();
        mChannelModel = channelModel;
        mKumaItems = FXCollections.observableArrayList(mPreference.getKumaMonitors());
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
        subtitle.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");

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
        fmtHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
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

        root.getChildren().add(new Separator());
        root.getChildren().add(buildKumaSection());

        return root;
    }

    /**
     * Per-channel liveness monitors: ping an external URL on an interval while a chosen channel is running.
     */
    @SuppressWarnings("unchecked")
    private VBox buildKumaSection()
    {
        VBox box = new VBox(8);
        box.setMaxWidth(Double.MAX_VALUE);

        Label header = new Label("PER-CHANNEL LIVENESS MONITORS  ·  push a URL while a channel runs");
        header.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        box.getChildren().add(header);

        Label how = new Label(
            "Pick a conventional channel and give it a URL to ping.  While that channel is running, SDRTrunk " +
            "GETs the URL every interval; when the channel stops (or SDRTrunk stops), the pings stop.  Point it " +
            "at an Uptime Kuma push monitor's URL and the monitor stays up while the channel runs and goes down " +
            "when it stops — one push monitor per channel.  This tracks running, not traffic, so a quiet " +
            "channel still counts as up.  It works whether or not the TCP heartbeat above is enabled.");
        how.setWrapText(true);
        how.setStyle(ThemeManager.calloutStyle());
        box.getChildren().add(how);

        Label kumaHint = new Label(
            "Kuma tip: create a Push monitor, set its Heartbeat Interval a little longer than the interval you " +
            "pick here (for example ping every 60 s, Kuma interval 120 s), then paste that monitor's push URL " +
            "below.  The URL is sent verbatim, so its ?status=up query is preserved; special characters are " +
            "percent-encoded automatically.");
        kumaHint.setWrapText(true);
        kumaHint.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        box.getChildren().add(kumaHint);

        Label restartNote = new Label(
            "⚙  Click Save to store. A change takes effect when the channel is next started (restart " +
            "SDRTrunk, or stop and play the channel).");
        restartNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");
        box.getChildren().add(restartNote);

        mKumaTable = new TableView<>(mKumaItems);
        mKumaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label("No channel monitors yet — pick a channel and add a URL below.");
        placeholder.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mKumaTable.setPlaceholder(placeholder);
        mKumaTable.setPrefHeight(180);

        TableColumn<KumaChannelMonitorEntry, String> chanCol = new TableColumn<>("Channel");
        chanCol.setMinWidth(120);
        chanCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChannelName()));

        TableColumn<KumaChannelMonitorEntry, String> sysCol = new TableColumn<>("System");
        sysCol.setMaxWidth(160);
        sysCol.setMinWidth(80);
        sysCol.setCellValueFactory(data -> new SimpleStringProperty(
            data.getValue().getSystem().isBlank() ? "(any)" : data.getValue().getSystem()));

        TableColumn<KumaChannelMonitorEntry, Number> intCol = new TableColumn<>("Every (s)");
        intCol.setMaxWidth(90);
        intCol.setMinWidth(70);
        intCol.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getIntervalSeconds()));

        TableColumn<KumaChannelMonitorEntry, String> urlCol = new TableColumn<>("URL");
        urlCol.setMinWidth(220);
        urlCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUrl()));

        mKumaTable.getColumns().addAll(chanCol, sysCol, intCol, urlCol);

        // Channel picker with type-to-filter
        FilteredList<ChannelOption> filtered =
            new FilteredList<>(FXCollections.observableArrayList(buildChannelOptions()), o -> true);
        mKumaPicker = new ComboBox<>(filtered);
        mKumaPicker.setPromptText("Pick a channel");
        mKumaPicker.setPrefWidth(280);
        mKumaPicker.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");

        mKumaFilter = new TextField();
        mKumaFilter.setPromptText("Filter by system or channel");
        mKumaFilter.setPrefWidth(180);
        mKumaFilter.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mKumaFilter.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = (newValue == null) ? "" : newValue.trim().toLowerCase();
            filtered.setPredicate(option -> query.isEmpty() || option.matches(query));
        });

        HBox pickerRow = new HBox(8, fieldLabel("Channel:"), mKumaFilter, mKumaPicker);
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        mKumaInterval = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 3600, 60, 1));
        mKumaInterval.setEditable(true);
        mKumaInterval.setPrefWidth(90);

        mKumaUrl = new TextField();
        mKumaUrl.setPromptText("https://your-kuma/api/push/TOKEN?status=up&msg=OK&ping=");
        mKumaUrl.setMaxWidth(Double.MAX_VALUE);
        mKumaUrl.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        HBox.setHgrow(mKumaUrl, Priority.ALWAYS);
        Button addButton = new Button("Add");
        addButton.setOnAction(e -> onAddKumaMonitor());
        HBox urlRow = new HBox(8, fieldLabel("Ping every (s):"), mKumaInterval, fieldLabel("URL:"), mKumaUrl, addButton);
        urlRow.setAlignment(Pos.CENTER_LEFT);

        Button removeButton = new Button("Remove selected monitor");
        removeButton.setStyle("-fx-text-fill: " + (ThemeManager.isDarkTheme() ? "#ff7a7a" : "#cc0000") + ";");
        removeButton.setOnAction(e -> onRemoveKumaMonitor());

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSaveKuma());
        mKumaStatus = new Label("");
        mKumaStatus.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        Region saveSpacer = new Region();
        HBox.setHgrow(saveSpacer, Priority.ALWAYS);
        HBox saveRow = new HBox(8, saveButton, mKumaStatus, saveSpacer, removeButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(mKumaTable, pickerRow, urlRow, saveRow);
        return box;
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
            java.util.Set<String> seen = new java.util.HashSet<>();

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
            if(existing.getChannelName().equalsIgnoreCase(option.name)
                    && existing.getSystem().equalsIgnoreCase(option.system))
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
        KumaChannelMonitorEntry selected = mKumaTable.getSelectionModel().getSelectedItem();

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
        mPreference.storeKumaMonitors(new ArrayList<>(mKumaItems));
        mKumaStatus.setText("Saved " + mKumaItems.size() + " monitor(s).");
    }

    private Label fieldLabel(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + (ThemeManager.isDarkTheme() ? "#e3e8ee" : "#1a1a1a") + ";");
        return l;
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
