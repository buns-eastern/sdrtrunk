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

package io.github.dsheirer.gui.settings.call;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.duplicate.IssiCallMergeEntry;
import io.github.dsheirer.settings.duplicate.IssiCallMergeSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.controlsfx.control.ToggleSwitch;

/**
 * ISSI Call Merge: redirects a source system + talkgroup to a primary system + talkgroup so that a talkgroup that
 * rides on two independent systems is de-duplicated and streamed as one.  Unlike the other settings pages, changes
 * on this page are stored when the user clicks Save.
 */
public class IssiCallMergeSettingsPage extends SettingsPage
{
    private final IssiCallMergeSettings mIssiSettings;
    private final AliasModel mAliasModel;
    private final ChannelModel mChannelModel;
    private final ObservableList<IssiCallMergeEntry> mItems;
    private ToggleSwitch mEnabled;
    private TableView<IssiCallMergeEntry> mTable;
    private ComboBox<SystemTalkgroupOption> mSourcePicker;
    private ComboBox<SystemTalkgroupOption> mPrimaryPicker;
    private Label mStatusLabel;

    /**
     * Constructs an instance
     * @param settings to edit
     * @param playlistManager for the channels and aliases offered in the pickers, or null
     */
    public IssiCallMergeSettingsPage(Settings settings, PlaylistManager playlistManager)
    {
        super(SettingsPageType.ISSI_CALL_MERGE, settings);
        mIssiSettings = settings.getIssiCallMergeSettings();
        mAliasModel = playlistManager != null ? playlistManager.getAliasModel() : null;
        mChannelModel = playlistManager != null ? playlistManager.getChannelModel() : null;
        mItems = FXCollections.observableArrayList(mIssiSettings.getEntries());

        SettingsSection howItWorks = addSection("How It Works");
        Label explanation = new Label("Pick a source (system + talkgroup) and a primary (system + talkgroup), both " +
            "from your playlist.  When a call is decoded on the source, SDRTrunk treats it as the primary for " +
            "de-duplication and streaming - so it collapses with the primary's copy under your existing Call " +
            "Management rules and streams using the primary's stream configuration.  Only the primary side needs to " +
            "be set to stream in the playlist.  The two talkgroup numbers may be the same or different; both are " +
            "entered here.");
        explanation.setWrapText(true);
        howItWorks.addContent(explanation);
        howItWorks.addHelp("This only affects streaming and duplicate handling for the talkgroups you list - every " +
            "other talkgroup on the source system is untouched.  With no entries, nothing changes.");

        SettingsSection configuration = addSection("Configuration");
        configuration.addRow("Enable ISSI Call Merge", getEnabled());

        SettingsSection redirects = addSection("Redirects  (source  →  primary)");
        redirects.addContent(getTable());
        redirects.addContent(createPickers());
        redirects.addContent(createSaveRow());
    }

    private ToggleSwitch getEnabled()
    {
        if(mEnabled == null)
        {
            mEnabled = new ToggleSwitch();
            mEnabled.setSelected(mIssiSettings.isEnabled());
        }

        return mEnabled;
    }

    @SuppressWarnings("unchecked")
    private TableView<IssiCallMergeEntry> getTable()
    {
        if(mTable == null)
        {
            mTable = new TableView<>(mItems);
            mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            mTable.setPlaceholder(new Label("No redirects yet - pick a source and a primary below."));
            mTable.setPrefHeight(190);

            TableColumn<IssiCallMergeEntry,String> sourceSystem = new TableColumn<>("Source System");
            sourceSystem.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getSourceSystem()));
            TableColumn<IssiCallMergeEntry,Number> sourceTalkgroup = new TableColumn<>("Source TG");
            sourceTalkgroup.setMaxWidth(110);
            sourceTalkgroup.setCellValueFactory(d -> new SimpleIntegerProperty(d.getValue().getSourceTalkgroup()));
            TableColumn<IssiCallMergeEntry,String> primarySystem = new TableColumn<>("Primary System");
            primarySystem.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getPrimarySystem()));
            TableColumn<IssiCallMergeEntry,Number> primaryTalkgroup = new TableColumn<>("Primary TG");
            primaryTalkgroup.setMaxWidth(110);
            primaryTalkgroup.setCellValueFactory(d -> new SimpleIntegerProperty(d.getValue().getPrimaryTalkgroup()));
            mTable.getColumns().addAll(sourceSystem, sourceTalkgroup, primarySystem, primaryTalkgroup);
        }

        return mTable;
    }

    private Region createPickers()
    {
        List<SystemTalkgroupOption> options = buildPickerOptions();

        FilteredList<SystemTalkgroupOption> sourceOptions =
            new FilteredList<>(FXCollections.observableArrayList(options), o -> true);
        mSourcePicker = new ComboBox<>(sourceOptions);
        mSourcePicker.setPromptText("Source system + talkgroup");
        mSourcePicker.setPrefWidth(300);
        TextField sourceFilter = createFilter(sourceOptions);
        HBox sourceRow = new HBox(8, fieldLabel("Source:"), sourceFilter, mSourcePicker);
        sourceRow.setAlignment(Pos.CENTER_LEFT);

        FilteredList<SystemTalkgroupOption> primaryOptions =
            new FilteredList<>(FXCollections.observableArrayList(options), o -> true);
        mPrimaryPicker = new ComboBox<>(primaryOptions);
        mPrimaryPicker.setPromptText("Primary system + talkgroup (the one that streams)");
        mPrimaryPicker.setPrefWidth(300);
        TextField primaryFilter = createFilter(primaryOptions);
        Button addButton = new Button("Add redirect");
        addButton.setOnAction(e -> onAdd());
        HBox primaryRow = new HBox(8, fieldLabel("Primary:"), primaryFilter, mPrimaryPicker, addButton);
        primaryRow.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, sourceRow, primaryRow);
        return box;
    }

    private Region createSaveRow()
    {
        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());

        IconNode gear = new IconNode(FontAwesome.COG);
        gear.setIconSize(11);
        gear.getStyleClass().add("settings-warning-icon");
        Label saveNote = new Label("Click Save to apply.  Changes take effect for calls that start after saving.", gear);
        saveNote.getStyleClass().add("settings-warning-note");

        mStatusLabel = new Label("");
        mStatusLabel.getStyleClass().add("settings-help");

        Button removeButton = new Button("Remove selected");
        removeButton.getStyleClass().add("settings-danger-button");
        removeButton.setOnAction(e -> onRemove());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, saveButton, saveNote, mStatusLabel, spacer, removeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField createFilter(FilteredList<SystemTalkgroupOption> options)
    {
        TextField filter = new TextField();
        filter.setPromptText("Filter");
        filter.setPrefWidth(120);
        filter.textProperty().addListener((o, ov, nv) -> {
            String query = (nv == null) ? "" : nv.trim().toLowerCase();
            options.setPredicate(option -> query.isEmpty() || option.matches(query));
        });
        return filter;
    }

    private static Label fieldLabel(String text)
    {
        Label label = new Label(text);
        label.setMinWidth(60);
        return label;
    }

    private void onAdd()
    {
        SystemTalkgroupOption source = mSourcePicker.getValue();
        SystemTalkgroupOption primary = mPrimaryPicker.getValue();

        if(source == null || primary == null)
        {
            mStatusLabel.setText("Pick both a source and a primary.");
            return;
        }

        if(source.system.equalsIgnoreCase(primary.system) && source.talkgroup == primary.talkgroup)
        {
            mStatusLabel.setText("Source and primary are the same - nothing to redirect.");
            return;
        }

        for(IssiCallMergeEntry existing: mItems)
        {
            if(existing.matchesSource(source.system, source.talkgroup))
            {
                mStatusLabel.setText("A redirect for that source is already in the list.");
                return;
            }
        }

        mItems.add(new IssiCallMergeEntry(source.system, source.talkgroup, primary.system, primary.talkgroup));
        mStatusLabel.setText("");
    }

    private void onRemove()
    {
        IssiCallMergeEntry selected = getTable().getSelectionModel().getSelectedItem();

        if(selected == null)
        {
            mStatusLabel.setText("Select a redirect first, then Remove.");
            return;
        }

        String description = selected.getSourceSystem() + " " + selected.getSourceTalkgroup() + "  →  " +
            selected.getPrimarySystem() + " " + selected.getPrimaryTalkgroup();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove redirect");
        confirm.setHeaderText("Remove this ISSI Call Merge redirect?");
        confirm.setContentText(description + "\n\nThis only removes the redirect - it does not change your playlist.");

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
        mIssiSettings.store(getEnabled().isSelected(), new ArrayList<>(mItems));
        mStatusLabel.setText("Saved " + mItems.size() + " redirect(s).");
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
                    int tg = analog.getTalkgroup();

                    if(tg > 0 && seen.add(system.toLowerCase() + "|" + tg))
                    {
                        options.add(new SystemTalkgroupOption(tg, system,
                            nameByListTalkgroup.getOrDefault(list + "|" + tg, channel.getName())));
                    }
                }
                else
                {
                    List<Integer> talkgroups = talkgroupsByList.get(list);

                    if(talkgroups != null)
                    {
                        for(int tg: talkgroups)
                        {
                            if(seen.add(system.toLowerCase() + "|" + tg))
                            {
                                options.add(new SystemTalkgroupOption(tg, system, nameByListTalkgroup.get(list + "|" + tg)));
                            }
                        }
                    }
                }
            }
        }
        catch(Throwable t)
        {
            //If the playlist can't be read, the pickers are simply empty.
        }

        options.sort((x, y) -> {
            int c = x.system.compareToIgnoreCase(y.system);
            return c != 0 ? c : Integer.compare(x.talkgroup, y.talkgroup);
        });

        return options;
    }

    /**
     * A pickable system + talkgroup pair, grouped under its system.
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
