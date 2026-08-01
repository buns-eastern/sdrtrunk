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
package io.github.dsheirer.gui.preference.call;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.duplicate.IssiCallMergeEntry;
import io.github.dsheirer.preference.duplicate.IssiCallMergePreference;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Preference editor for the ISSI Call Merge feature: redirect a source (system, talkgroup) so that it de-dupes
 * and streams as a primary (system, talkgroup).
 */
public class IssiCallMergePreferenceEditor extends HBox
{
    private final IssiCallMergePreference mPreference;
    private final AliasModel mAliasModel;
    private final ChannelModel mChannelModel;
    private CheckBox mEnabled;
    private TableView<IssiCallMergeEntry> mTable;
    private ObservableList<IssiCallMergeEntry> mItems;
    private ComboBox<SystemTalkgroupOption> mSourcePicker;
    private ComboBox<SystemTalkgroupOption> mPrimaryPicker;
    private TextField mSourceFilter;
    private TextField mPrimaryFilter;
    private Label mStatusLabel;

    public IssiCallMergePreferenceEditor(UserPreferences userPreferences, AliasModel aliasModel, ChannelModel channelModel)
    {
        mPreference = userPreferences.getIssiCallMergePreference();
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

        Label title = new Label("ISSI Call Merge");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label subtitle = new Label(
            "Stream a single copy of a talkgroup that rides on two independent systems (for example via P25 " +
            "ISSI), no matter which system carries the call.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");

        box.getChildren().addAll(title, subtitle, new Separator(), sectionHeader("HOW IT WORKS"));

        Label how = new Label(
            "Pick a source (system + talkgroup) and a primary (system + talkgroup), both from your playlist.  " +
            "When a call is decoded on the source, SDRTrunk treats it as the primary for de-duplication and " +
            "streaming - so it collapses with the primary's copy under your existing Call Management rules and " +
            "streams using the primary's stream configuration.  Only the primary side needs to be set to stream " +
            "in the playlist.  The two talkgroup numbers may be the same or different; both are entered here.");
        how.setWrapText(true);
        how.setStyle(ThemeManager.calloutStyle());
        box.getChildren().add(how);

        Label note = new Label(
            "This only affects streaming and duplicate handling for the talkgroups you list - every other " +
            "talkgroup on the source system is untouched.  With no entries, nothing changes.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        box.getChildren().add(note);

        Label saveNote = new Label("⚙  Click Save to apply.  Changes take effect for calls that start after saving.");
        saveNote.setStyle("-fx-text-fill: #996600; -fx-font-size: 11px;");
        box.getChildren().add(saveNote);

        return box;
    }

    private VBox buildForm()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 6, 20));
        box.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(sectionHeader("CONFIGURATION"));

        mEnabled = new CheckBox("Enable ISSI Call Merge");
        mEnabled.setSelected(mPreference.isEnabled());
        box.getChildren().add(mEnabled);
        return box;
    }

    @SuppressWarnings("unchecked")
    private VBox buildTableSection()
    {
        VBox box = new VBox(8);
        box.setPadding(new Insets(6, 20, 18, 20));
        box.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(sectionHeader("REDIRECTS  (source  →  primary)"));

        mTable = new TableView<>(mItems);
        mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label("No redirects yet - pick a source and a primary below.");
        placeholder.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mTable.setPlaceholder(placeholder);
        mTable.setPrefHeight(190);

        TableColumn<IssiCallMergeEntry, String> srcSys = new TableColumn<>("Source System");
        srcSys.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getSourceSystem()));
        TableColumn<IssiCallMergeEntry, Number> srcTg = new TableColumn<>("Source TG");
        srcTg.setMaxWidth(110);
        srcTg.setCellValueFactory(d -> new SimpleIntegerProperty(d.getValue().getSourceTalkgroup()));
        TableColumn<IssiCallMergeEntry, String> priSys = new TableColumn<>("Primary System");
        priSys.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getPrimarySystem()));
        TableColumn<IssiCallMergeEntry, Number> priTg = new TableColumn<>("Primary TG");
        priTg.setMaxWidth(110);
        priTg.setCellValueFactory(d -> new SimpleIntegerProperty(d.getValue().getPrimaryTalkgroup()));
        mTable.getColumns().addAll(srcSys, srcTg, priSys, priTg);

        List<SystemTalkgroupOption> options = buildPickerOptions();

        FilteredList<SystemTalkgroupOption> sourceFiltered = new FilteredList<>(FXCollections.observableArrayList(options), o -> true);
        mSourcePicker = new ComboBox<>(sourceFiltered);
        mSourcePicker.setPromptText("Source system + talkgroup");
        mSourcePicker.setPrefWidth(300);
        mSourcePicker.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mSourceFilter = new TextField();
        mSourceFilter.setPromptText("Filter");
        mSourceFilter.setPrefWidth(120);
        mSourceFilter.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mSourceFilter.textProperty().addListener((o, ov, nv) -> {
            String q = (nv == null) ? "" : nv.trim().toLowerCase();
            sourceFiltered.setPredicate(opt -> q.isEmpty() || opt.matches(q));
        });
        HBox sourceRow = new HBox(8, fieldLabel("Source:"), mSourceFilter, mSourcePicker);
        sourceRow.setAlignment(Pos.CENTER_LEFT);

        FilteredList<SystemTalkgroupOption> primaryFiltered = new FilteredList<>(FXCollections.observableArrayList(options), o -> true);
        mPrimaryPicker = new ComboBox<>(primaryFiltered);
        mPrimaryPicker.setPromptText("Primary system + talkgroup (the one that streams)");
        mPrimaryPicker.setPrefWidth(300);
        mPrimaryPicker.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mPrimaryFilter = new TextField();
        mPrimaryFilter.setPromptText("Filter");
        mPrimaryFilter.setPrefWidth(120);
        mPrimaryFilter.setStyle("-fx-prompt-text-fill: " + ThemeManager.mutedTextColor() + ";");
        mPrimaryFilter.textProperty().addListener((o, ov, nv) -> {
            String q = (nv == null) ? "" : nv.trim().toLowerCase();
            primaryFiltered.setPredicate(opt -> q.isEmpty() || opt.matches(q));
        });
        Button addButton = new Button("Add redirect");
        addButton.setOnAction(e -> onAdd());
        HBox primaryRow = new HBox(8, fieldLabel("Primary:"), mPrimaryFilter, mPrimaryPicker, addButton);
        primaryRow.setAlignment(Pos.CENTER_LEFT);

        Button removeButton = new Button("Remove selected");
        removeButton.setStyle("-fx-text-fill: " + (ThemeManager.isDarkTheme() ? "#ff7a7a" : "#cc0000") + ";");
        removeButton.setOnAction(e -> onRemove());
        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onSave());
        mStatusLabel = new Label("");
        mStatusLabel.setStyle("-fx-text-fill: " + ThemeManager.mutedTextColor() + "; -fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox saveRow = new HBox(8, saveButton, mStatusLabel, spacer, removeButton);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        box.getChildren().addAll(mTable, sourceRow, primaryRow, saveRow);
        return box;
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
        IssiCallMergeEntry selected = mTable.getSelectionModel().getSelectedItem();

        if(selected == null)
        {
            mStatusLabel.setText("Select a redirect first, then Remove.");
            return;
        }

        String description = selected.getSourceSystem() + " " + selected.getSourceTalkgroup() + "  →  "
                + selected.getPrimarySystem() + " " + selected.getPrimaryTalkgroup();

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
        mPreference.store(mEnabled.isSelected(), new ArrayList<>(mItems));
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
                    int tg = ((DecodeConfigAnalog)channel.getDecodeConfiguration()).getTalkgroup();

                    if(tg > 0 && seen.add(system.toLowerCase() + "|" + tg))
                    {
                        options.add(new SystemTalkgroupOption(tg, system, nameByListTalkgroup.getOrDefault(list + "|" + tg, channel.getName())));
                    }
                }
                else
                {
                    java.util.List<Integer> tgs = talkgroupsByList.get(list);

                    if(tgs != null)
                    {
                        for(int tg: tgs)
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

    private Label sectionHeader(String text)
    {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + ThemeManager.headingTextColor() + ";");
        return l;
    }

    private Label fieldLabel(String text)
    {
        Label l = new Label(text);
        l.setMinWidth(60);
        l.setStyle("-fx-text-fill: " + (ThemeManager.isDarkTheme() ? "#e3e8ee" : "#1a1a1a") + ";");
        return l;
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
            return system.toLowerCase().contains(query) || name.toLowerCase().contains(query)
                    || Integer.toString(talkgroup).contains(query);
        }
    }
}
