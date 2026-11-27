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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.ChannelHeartbeatEntry;
import io.github.dsheirer.settings.network.ChannelHeartbeatSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Channel Heartbeat: sends a heartbeat to a URL when one of the chosen talkgroups becomes active.  Changes on this
 * page are stored when the user clicks Save.
 */
public class ChannelHeartbeatSettingsPage extends OutputSettingsPage
{
    private static final String ANY_SYSTEM = "Any system";
    private final ChannelHeartbeatSettings mHeartbeatSettings;
    private final AliasModel mAliasModel;
    private final ChannelModel mChannelModel;
    private final ObservableList<ChannelHeartbeatEntry> mItems;
    private CheckBox mEnabled;
    private TextField mUrlTemplate;
    private Spinner<Integer> mDebounce;
    private TableView<ChannelHeartbeatEntry> mTable;
    private ComboBox<SystemTalkgroupOption> mPicker;
    private ComboBox<String> mManualSystem;
    private TextField mManualTalkgroup;
    private TextField mManualLabel;
    private Label mStatusLabel;

    /**
     * Constructs an instance
     * @param settings to edit
     * @param playlistManager for the channels and aliases offered in the picker, or null
     */
    public ChannelHeartbeatSettingsPage(Settings settings, PlaylistManager playlistManager)
    {
        super(SettingsPageType.CHANNEL_HEARTBEAT, settings);
        mHeartbeatSettings = settings.getChannelHeartbeatSettings();
        mAliasModel = playlistManager != null ? playlistManager.getAliasModel() : null;
        mChannelModel = playlistManager != null ? playlistManager.getChannelModel() : null;
        mItems = FXCollections.observableArrayList(mHeartbeatSettings.getEntries());

        SettingsSection howItWorks = addSection("How It Works");
        howItWorks.addContent(paragraph(
            "Pick the talkgroups you care about - trunking or conventional (every channel resolves to a " +
            "talkgroup inside SDRTrunk, including a conventional channel's assigned talkgroup). Whenever one of " +
            "them keys up, SDRTrunk sends an HTTP request to your URL with the talkgroup filled in.  Use it to " +
            "notify a status page, a dashboard, a webhook - anything that accepts a plain GET."));

        SettingsSection url = addSection("The URL");
        url.addContent(callout(
            "Put {channel} where the talkgroup should go, and optionally {label} for its name.  Everything else " +
            "- host, path, token, parameters - is yours; SDRTrunk only substitutes the placeholders and sends " +
            "the URL exactly as written.  Any special characters are percent-encoded automatically, so a token " +
            "with unusual characters works as-is."));
        url.addContent(muted(
            "The playlist list is grouped by system, so if the same talkgroup exists in more than one system " +
            "you pick the exact one you want - the system comes with it.  Manual entries can set a system or " +
            "leave it Any.  You can also use {system} in the URL."));
        url.addContent(muted(
            "Debounce is the minimum seconds between heartbeats for the same talkgroup, so one transmission (or " +
            "rapid re-keys) does not spam your endpoint."));
        url.addContent(saveNote("Click Save to apply. Changes take effect immediately - no restart needed."));

        SettingsSection configuration = addSection("Configuration");
        GridPane grid = formGrid();
        int row = 0;

        mEnabled = new CheckBox("Enable channel heartbeat");
        mEnabled.setSelected(mHeartbeatSettings.isEnabled());
        grid.add(rightLabel("Monitor:"), 0, row);
        grid.add(mEnabled, 1, row++);

        mUrlTemplate = new TextField(mHeartbeatSettings.getUrlTemplate());
        mUrlTemplate.setPromptText("https://your-endpoint.example.com/path?token=XXXX&channel={channel}&status=active");
        mUrlTemplate.setMaxWidth(Double.MAX_VALUE);
        grid.add(rightLabel("URL template:"), 0, row);
        grid.add(mUrlTemplate, 1, row++);

        mDebounce = secondsSpinner(0, 3600, mHeartbeatSettings.getDebounceSeconds(), 1);
        grid.add(rightLabel("Debounce (s):"), 0, row);
        grid.add(mDebounce, 1, row++);
        configuration.addContent(grid);

        SettingsSection watched = addSection("Watched Talkgroups");
        watched.addContent(getTable());

        Button removeButton = new Button("Remove selected talkgroup");
        removeButton.getStyleClass().add("settings-danger-button");
        removeButton.setOnAction(e -> onRemoveSelected());

        //Add from playlist
        FilteredList<SystemTalkgroupOption> filteredOptions =
            new FilteredList<>(FXCollections.observableArrayList(buildPickerOptions()), o -> true);
        mPicker = new ComboBox<>(filteredOptions);
        mPicker.setPromptText("Pick a system + talkgroup from your playlist");
        mPicker.setPrefWidth(340);

        TextField filter = new TextField();
        filter.setPromptText("Filter by system, name, or TG");
        filter.setPrefWidth(180);
        filter.textProperty().addListener((obs, oldValue, newValue) -> {
            String query = (newValue == null) ? "" : newValue.trim().toLowerCase();
            filteredOptions.setPredicate(option -> query.isEmpty() || option.matches(query));
        });

        Button addFromPlaylist = new Button("Add");
        addFromPlaylist.setOnAction(e -> {
            SystemTalkgroupOption option = mPicker.getValue();

            if(option != null)
            {
                addEntry(option.talkgroup, option.name, option.system);
            }
        });
        HBox pickerRow = new HBox(8, new Label("From playlist:"), filter, mPicker, addFromPlaylist);
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        //Add manually
        mManualTalkgroup = new TextField();
        mManualTalkgroup.setPromptText("Talkgroup #");
        mManualTalkgroup.setPrefWidth(110);
        mManualLabel = new TextField();
        mManualLabel.setPromptText("Label (optional)");
        mManualLabel.setPrefWidth(160);
        Button addManual = new Button("Add");
        addManual.setOnAction(e -> onAddManual());
        mManualSystem = systemCombo();
        HBox manualRow = new HBox(8, new Label("Or manually:"), mManualTalkgroup, mManualSystem, mManualLabel,
            addManual);
        manualRow.setAlignment(Pos.CENTER_LEFT);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        mStatusLabel = muted("");
        Region saveSpacer = new Region();
        HBox.setHgrow(saveSpacer, Priority.ALWAYS);
        HBox saveRow = new HBox(8, saveButton, mStatusLabel, saveSpacer, removeButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        watched.addContent(pickerRow);
        watched.addContent(manualRow);
        watched.addContent(saveRow);
    }

    @SuppressWarnings("unchecked")
    private TableView<ChannelHeartbeatEntry> getTable()
    {
        if(mTable == null)
        {
            mTable = new TableView<>(mItems);
            mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            mTable.setPlaceholder(muted("No talkgroups yet - add from your playlist or enter one below."));
            mTable.setPrefHeight(200);

            TableColumn<ChannelHeartbeatEntry,Number> talkgroupColumn = new TableColumn<>("Talkgroup");
            talkgroupColumn.setMaxWidth(140);
            talkgroupColumn.setMinWidth(100);
            talkgroupColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getTalkgroup()));

            TableColumn<ChannelHeartbeatEntry,String> systemColumn = new TableColumn<>("System");
            systemColumn.setMaxWidth(170);
            systemColumn.setMinWidth(90);
            systemColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getSystem().isBlank() ? "(any)" : data.getValue().getSystem()));

            TableColumn<ChannelHeartbeatEntry,String> labelColumn = new TableColumn<>("Label");
            labelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getLabel()));

            mTable.getColumns().addAll(talkgroupColumn, systemColumn, labelColumn);
        }

        return mTable;
    }

    /**
     * Builds (system, talkgroup) options from each channel, so a shared alias list cannot bleed one system's
     * talkgroups into another.
     */
    private List<SystemTalkgroupOption> buildPickerOptions()
    {
        List<SystemTalkgroupOption> options = new ArrayList<>();

        if(mChannelModel == null)
        {
            return options;
        }

        try
        {
            //Index the playlist talkgroups (and friendly names) by alias list
            Map<String,List<Integer>> talkgroupsByList = new HashMap<>();
            Map<String,String> nameByListTalkgroup = new HashMap<>();

            if(mAliasModel != null)
            {
                for(Alias alias: mAliasModel.getAliases())
                {
                    String list = alias.getAliasListName();

                    if(list == null)
                    {
                        continue;
                    }

                    for(AliasID id: alias.getAliasIdentifiers())
                    {
                        if(id instanceof Talkgroup talkgroup)
                        {
                            int tg = talkgroup.getValue();
                            talkgroupsByList.computeIfAbsent(list, k -> new ArrayList<>()).add(tg);
                            nameByListTalkgroup.putIfAbsent(list + "|" + tg, alias.getName());
                        }
                    }
                }
            }

            Set<String> seen = new HashSet<>();

            for(Channel channel: mChannelModel.getChannels())
            {
                String system = channel.getSystem();

                if(system == null || system.isBlank())
                {
                    continue;
                }

                String list = channel.getAliasListName();

                if(channel.getDecodeConfiguration() instanceof DecodeConfigAnalog analog)
                {
                    //Conventional channel carries a single assigned talkgroup
                    int tg = analog.getTalkgroup();

                    if(tg > 0 && seen.add(system.toLowerCase() + "|" + tg))
                    {
                        String name = nameByListTalkgroup.getOrDefault(list + "|" + tg, channel.getName());
                        options.add(new SystemTalkgroupOption(tg, system, name));
                    }
                }
                else
                {
                    //Trunking (or other) channel: its talkgroups come from its alias list, all under this system
                    List<Integer> talkgroups = talkgroupsByList.get(list);

                    if(talkgroups != null)
                    {
                        for(int tg: talkgroups)
                        {
                            if(seen.add(system.toLowerCase() + "|" + tg))
                            {
                                options.add(new SystemTalkgroupOption(tg, system,
                                    nameByListTalkgroup.get(list + "|" + tg)));
                            }
                        }
                    }
                }
            }
        }
        catch(Throwable t)
        {
            //If the playlist can't be read, the picker is simply empty; manual entry still works
        }

        options.sort((x, y) -> {
            int c = x.system.compareToIgnoreCase(y.system);
            return c != 0 ? c : Integer.compare(x.talkgroup, y.talkgroup);
        });

        return options;
    }

    private void onAddManual()
    {
        String text = mManualTalkgroup.getText();

        if(text != null && !text.trim().isEmpty())
        {
            try
            {
                int tg = Integer.parseInt(text.trim());
                addEntry(tg, mManualLabel.getText(), systemValue(mManualSystem));
                mManualTalkgroup.clear();
                mManualLabel.clear();
            }
            catch(NumberFormatException nfe)
            {
                mStatusLabel.setText("Talkgroup must be a number.");
            }
        }
    }

    private void addEntry(int talkgroup, String label, String system)
    {
        String sys = system != null ? system : "";

        for(ChannelHeartbeatEntry existing: mItems)
        {
            if(existing.getTalkgroup() == talkgroup && existing.getSystem().equalsIgnoreCase(sys))
            {
                mStatusLabel.setText("Talkgroup " + talkgroup + (sys.isEmpty() ? "" : " on " + sys) +
                    " is already in the list.");
                return;
            }
        }

        mItems.add(new ChannelHeartbeatEntry(talkgroup, label != null ? label : "", sys));
        mStatusLabel.setText("");
    }

    private ComboBox<String> systemCombo()
    {
        TreeSet<String> systems = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        if(mChannelModel != null)
        {
            try
            {
                for(Channel channel: mChannelModel.getChannels())
                {
                    String system = channel.getSystem();

                    if(system != null && !system.isBlank())
                    {
                        systems.add(system);
                    }
                }
            }
            catch(Throwable t)
            {
                //If channels can't be read, only the Any option is offered
            }
        }

        List<String> options = new ArrayList<>();
        options.add(ANY_SYSTEM);
        options.addAll(systems);

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(options));
        combo.setValue(ANY_SYSTEM);
        combo.setPrefWidth(150);
        return combo;
    }

    private static String systemValue(ComboBox<String> combo)
    {
        String value = combo.getValue();
        return (value == null || ANY_SYSTEM.equals(value)) ? "" : value;
    }

    private void onRemoveSelected()
    {
        ChannelHeartbeatEntry selected = getTable().getSelectionModel().getSelectedItem();

        if(selected == null)
        {
            mStatusLabel.setText("Select a talkgroup in the list first, then Remove.");
            return;
        }

        String system = selected.getSystem().isBlank() ? "any system" : selected.getSystem();
        String label = selected.getLabel().isBlank() ? "" : selected.getLabel() + " - ";
        String description = label + "talkgroup " + selected.getTalkgroup() + "  (" + system + ")";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove talkgroup");
        confirm.setHeaderText("Remove this talkgroup from the Channel Heartbeat list?");
        confirm.setContentText(description +
            "\n\nThis only removes it from the heartbeat list - it does not change your playlist.");

        if(getScene() != null && getScene().getWindow() != null)
        {
            confirm.initOwner(getScene().getWindow());
        }

        Optional<ButtonType> result = confirm.showAndWait();

        if(result.isPresent() && result.get() == ButtonType.OK)
        {
            mItems.remove(selected);
            mStatusLabel.setText("Removed " + description + ".");
        }
    }

    private void onSave()
    {
        int debounce = (mDebounce.getValue() != null) ? mDebounce.getValue() : 10;
        mHeartbeatSettings.store(mEnabled.isSelected(), mUrlTemplate.getText(), debounce, new ArrayList<>(mItems));
        mStatusLabel.setText("Saved " + mItems.size() + " talkgroup(s).");
    }

    /**
     * A pickable system + talkgroup pair for the playlist dropdown, grouped under its system.
     */
    private static class SystemTalkgroupOption
    {
        private final int talkgroup;
        private final String system;
        private final String name;

        SystemTalkgroupOption(int talkgroup, String system, String name)
        {
            this.talkgroup = talkgroup;
            this.system = system != null ? system : "";
            this.name = name != null ? name : "";
        }

        @Override
        public String toString()
        {
            String label = name.isEmpty() ? Integer.toString(talkgroup) : name + " (" + talkgroup + ")";
            return system.isEmpty() ? label : system + "  ·  " + label;
        }

        boolean matches(String query)
        {
            return system.toLowerCase().contains(query) || name.toLowerCase().contains(query) ||
                Integer.toString(talkgroup).contains(query);
        }
    }
}
