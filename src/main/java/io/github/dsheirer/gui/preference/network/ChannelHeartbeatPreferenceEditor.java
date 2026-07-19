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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.network.ChannelHeartbeatEntry;
import io.github.dsheirer.preference.network.ChannelHeartbeatPreference;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Preference editor for the Channel Heartbeat feature - fire a heartbeat to an external endpoint whenever a
 * chosen talkgroup or channel becomes active.
 */
public class ChannelHeartbeatPreferenceEditor extends HBox
{
    private static final String ANY_SYSTEM = "Any system";
    private final ChannelHeartbeatPreference mPreference;
    private final AliasModel mAliasModel;
    private final ChannelModel mChannelModel;
    private CheckBox mEnabled;
    private TextField mUrlTemplate;
    private Spinner<Integer> mDebounce;
    private TableView<ChannelHeartbeatEntry> mTable;
    private ObservableList<ChannelHeartbeatEntry> mItems;
    private ComboBox<SystemTalkgroupOption> mPicker;
    private TextField mFilter;
    private ComboBox<String> mManualSystem;
    private TextField mManualTalkgroup;
    private TextField mManualLabel;
    private Label mStatusLabel;

    public ChannelHeartbeatPreferenceEditor(UserPreferences userPreferences, AliasModel aliasModel, ChannelModel channelModel)
    {
        mPreference = userPreferences.getChannelHeartbeatPreference();
        mAliasModel = aliasModel;
        mChannelModel = channelModel;
        mItems = FXCollections.observableArrayList(mPreference.getEntries());
        setMaxWidth(Double.MAX_VALUE);

        VBox page = new VBox(6);
        page.setMaxWidth(Double.MAX_VALUE);
        page.getChildren().addAll(buildExplanation(), buildForm(), new Separator(), buildTableSection());

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    private VBox buildExplanation()
    {
        VBox box = new VBox(12);
        box.setPadding(new Insets(18, 20, 10, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        Label title = new Label("Channel Heartbeat");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label subtitle = new Label(
            "Fire a heartbeat to an external endpoint the moment one of your chosen talkgroups or channels " +
            "becomes active.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");

        box.getChildren().addAll(title, subtitle, new Separator());
        box.getChildren().add(sectionHeader("HOW IT WORKS"));

        Label how = new Label(
            "Pick the talkgroups you care about - trunking or conventional (every channel resolves to a " +
            "talkgroup inside SDRTrunk, including a conventional channel's assigned talkgroup). Whenever one of " +
            "them keys up, SDRTrunk sends an HTTP request to your URL with the talkgroup filled in.  Use it to " +
            "notify a status page, a dashboard, a webhook - anything that accepts a plain GET.");
        how.setWrapText(true);
        box.getChildren().add(how);

        box.getChildren().add(new Separator());
        box.getChildren().add(sectionHeader("THE URL"));

        Label url = new Label(
            "Put {channel} where the talkgroup should go, and optionally {label} for its name.  Everything else " +
            "- host, path, token, parameters - is yours; SDRTrunk only substitutes the placeholders and sends " +
            "the URL exactly as written.  Any special characters are percent-encoded automatically, so a token " +
            "with unusual characters works as-is.");
        url.setWrapText(true);
        url.setStyle(ThemeManager.calloutStyle());
        box.getChildren().add(url);

        Label systemNote = new Label(
            "The playlist list is grouped by system, so if the same talkgroup exists in more than one system " +
            "you pick the exact one you want - the system comes with it.  Manual entries can set a system or " +
            "leave it Any.  You can also use {system} in the URL.");
        systemNote.setWrapText(true);
        systemNote.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        box.getChildren().add(systemNote);

        Label debounceNote = new Label(
            "Debounce is the minimum seconds between heartbeats for the same talkgroup, so one transmission (or " +
            "rapid re-keys) does not spam your endpoint.");
        debounceNote.setWrapText(true);
        debounceNote.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        box.getChildren().add(debounceNote);

        Label saveNote = new Label("⚙  Click Save to apply. Changes take effect immediately - no restart needed.");
        saveNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");
        box.getChildren().add(saveNote);

        return box;
    }

    private VBox buildForm()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 10, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().add(sectionHeader("CONFIGURATION"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 0, 8, 0));

        ColumnConstraints labelCol = new ColumnConstraints(120);
        labelCol.setHalignment(HPos.RIGHT);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        int row = 0;

        mEnabled = new CheckBox("Enable channel heartbeat");
        mEnabled.setSelected(mPreference.isEnabled());
        grid.add(rightLabel("Monitor:"), 0, row);
        grid.add(mEnabled, 1, row++);

        mUrlTemplate = new TextField(mPreference.getUrlTemplate());
        mUrlTemplate.setPromptText("https://your-endpoint.example.com/path?token=XXXX&channel={channel}&status=active");
        mUrlTemplate.setMaxWidth(Double.MAX_VALUE);
        mUrlTemplate.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        grid.add(rightLabel("URL template:"), 0, row);
        grid.add(mUrlTemplate, 1, row++);

        mDebounce = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
            0, 3600, Math.max(0, Math.min(3600, mPreference.getDebounceSeconds())), 1));
        mDebounce.setEditable(true);
        mDebounce.setPrefWidth(110);
        grid.add(rightLabel("Debounce (s):"), 0, row);
        grid.add(mDebounce, 1, row++);

        box.getChildren().add(grid);
        return box;
    }

    @SuppressWarnings("unchecked")
    private VBox buildTableSection()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 18, 20));
        box.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().add(sectionHeader("WATCHED TALKGROUPS"));

        mTable = new TableView<>(mItems);
        mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label("No talkgroups yet - add from your playlist or enter one below.");
        placeholder.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mTable.setPlaceholder(placeholder);
        mTable.setPrefHeight(200);

        TableColumn<ChannelHeartbeatEntry, Number> tgCol = new TableColumn<>("Talkgroup");
        tgCol.setMaxWidth(140);
        tgCol.setMinWidth(100);
        tgCol.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getTalkgroup()));

        TableColumn<ChannelHeartbeatEntry, String> systemCol = new TableColumn<>("System");
        systemCol.setMaxWidth(170);
        systemCol.setMinWidth(90);
        systemCol.setCellValueFactory(data -> new SimpleStringProperty(
            data.getValue().getSystem().isBlank() ? "(any)" : data.getValue().getSystem()));

        TableColumn<ChannelHeartbeatEntry, String> labelCol = new TableColumn<>("Label");
        labelCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getLabel()));

        mTable.getColumns().addAll(tgCol, systemCol, labelCol);

        Button removeButton = new Button("Remove selected talkgroup");
        removeButton.setStyle("-fx-text-fill: " + (ThemeManager.isDarkTheme() ? "#ff7a7a" : "#cc0000") + ";");
        removeButton.setOnAction(e -> {
            ChannelHeartbeatEntry selected = mTable.getSelectionModel().getSelectedItem();
            if(selected != null)
            {
                mItems.remove(selected);
            }
        });

        //Add from playlist
        javafx.collections.transformation.FilteredList<SystemTalkgroupOption> filteredOptions =
            new javafx.collections.transformation.FilteredList<>(
                FXCollections.observableArrayList(buildPickerOptions()), o -> true);
        mPicker = new ComboBox<>(filteredOptions);
        mPicker.setPromptText("Pick a system + talkgroup from your playlist");
        mPicker.setPrefWidth(340);
        mPicker.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");

        mFilter = new TextField();
        mFilter.setPromptText("Filter by system, name, or TG");
        mFilter.setPrefWidth(180);
        mFilter.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mFilter.textProperty().addListener((obs, oldValue, newValue) -> {
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
        HBox pickerRow = new HBox(8, fieldLabel("From playlist:"), mFilter, mPicker, addFromPlaylist);
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        //Add manually
        mManualTalkgroup = new TextField();
        mManualTalkgroup.setPromptText("Talkgroup #");
        mManualTalkgroup.setPrefWidth(110);
        mManualTalkgroup.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mManualLabel = new TextField();
        mManualLabel.setPromptText("Label (optional)");
        mManualLabel.setPrefWidth(160);
        mManualLabel.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        Button addManual = new Button("Add");
        addManual.setOnAction(e -> onAddManual());
        mManualSystem = systemCombo();
        HBox manualRow = new HBox(8, fieldLabel("Or manually:"), mManualTalkgroup, mManualSystem, mManualLabel, addManual);
        manualRow.setAlignment(Pos.CENTER_LEFT);

        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        mStatusLabel = new Label("");
        mStatusLabel.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        HBox saveRow = new HBox(8, saveButton, removeButton, mStatusLabel);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(mTable, pickerRow, manualRow, saveRow);
        return box;
    }

    /**
     * Builds the picker options from the playlist aliases that carry a talkgroup, sorted and de-duplicated.
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
            java.util.Map<String,java.util.List<Integer>> talkgroupsByList = new java.util.HashMap<>();
            java.util.Map<String,String> nameByListTalkgroup = new java.util.HashMap<>();

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
                        if(id instanceof Talkgroup)
                        {
                            int tg = ((Talkgroup)id).getValue();
                            talkgroupsByList.computeIfAbsent(list, k -> new ArrayList<>()).add(tg);
                            nameByListTalkgroup.putIfAbsent(list + "|" + tg, alias.getName());
                        }
                    }
                }
            }

            //Derive real (system, talkgroup) pairs from each channel. The channel owns BOTH its system and,
            //for a conventional channel, its assigned talkgroup - so a shared alias list can no longer bleed
            //one system's talkgroups into another when an alias list is shared across systems.
            java.util.Set<String> seen = new java.util.HashSet<>();

            for(Channel channel: mChannelModel.getChannels())
            {
                String system = channel.getSystem();

                if(system == null || system.isBlank())
                {
                    continue;
                }

                String list = channel.getAliasListName();

                if(channel.getDecodeConfiguration() instanceof DecodeConfigAnalog)
                {
                    //Conventional channel carries a single assigned talkgroup
                    int tg = ((DecodeConfigAnalog)channel.getDecodeConfiguration()).getTalkgroup();

                    if(tg > 0 && seen.add(system.toLowerCase() + "|" + tg))
                    {
                        String name = nameByListTalkgroup.getOrDefault(list + "|" + tg, channel.getName());
                        options.add(new SystemTalkgroupOption(tg, system, name));
                    }
                }
                else
                {
                    //Trunking (or other) channel: its talkgroups come from its alias list, all under this system
                    java.util.List<Integer> tgs = talkgroupsByList.get(list);

                    if(tgs != null)
                    {
                        for(int tg: tgs)
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
                mStatusLabel.setText("Talkgroup " + talkgroup + (sys.isEmpty() ? "" : " on " + sys) + " is already in the list.");
                return;
            }
        }

        mItems.add(new ChannelHeartbeatEntry(talkgroup, label != null ? label : "", sys));
        mStatusLabel.setText("");
    }

    private ComboBox<String> systemCombo()
    {
        java.util.TreeSet<String> systems = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);

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

    private void onSave()
    {
        int debounce = (mDebounce.getValue() != null) ? mDebounce.getValue() : 10;
        mPreference.store(mEnabled.isSelected(), mUrlTemplate.getText(), debounce, new ArrayList<>(mItems));
        mStatusLabel.setText("Saved " + mItems.size() + " talkgroup(s).");
    }

    private Label sectionHeader(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        return l;
    }

    private Label fieldLabel(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + fg() + ";");
        return l;
    }

    private static String fg()
    {
        return ThemeManager.isDarkTheme() ? "#e3e8ee" : "#1a1a1a";
    }

    private Label rightLabel(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: " + fg() + ";");
        GridPane.setHalignment(l, HPos.RIGHT);
        return l;
    }

    /**
     * A pickable system+talkgroup pair for the playlist dropdown, grouped under its system.
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
            return system.isEmpty() ? label : system + "  \u00b7  " + label;
        }

        boolean matches(String query)
        {
            return system.toLowerCase().contains(query)
                    || name.toLowerCase().contains(query)
                    || Integer.toString(talkgroup).contains(query);
        }
    }
}
