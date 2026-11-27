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

import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.HeartbeatEntry;
import io.github.dsheirer.settings.network.HeartbeatSettings;
import java.util.Optional;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.controlsfx.control.ToggleSwitch;

/**
 * Heartbeat Monitor: pings monitoring URLs while each configured P25 control channel site is decoding.  Entries are
 * stored as soon as they are added, edited or removed.
 */
public class HeartbeatMonitorSettingsPage extends OutputSettingsPage
{
    private final HeartbeatSettings mHeartbeatSettings;
    private final ObservableList<HeartbeatEntry> mItems;
    private TableView<HeartbeatEntry> mTable;
    private Button mEditButton;
    private Button mRemoveButton;

    /**
     * Constructs an instance
     * @param settings to edit
     */
    public HeartbeatMonitorSettingsPage(Settings settings)
    {
        super(SettingsPageType.HEARTBEAT_MONITOR, settings);
        mHeartbeatSettings = settings.getHeartbeatSettings();
        mItems = FXCollections.observableArrayList(mHeartbeatSettings.getEntries());

        SettingsSection howItWorks = addSection("How It Works");
        howItWorks.addContent(paragraph(
            "P25 trunking towers transmit RFSS Status Broadcast messages on the control channel " +
            "several times per second — these are normal housekeeping messages that carry the " +
            "system ID and site ID of the site your receiver is locked to.\n\n" +
            "SDRTrunk watches for these messages on each configured (System ID, Site ID) pair.  " +
            "When a match is found, it fires a throttled HTTP GET request to each of your " +
            "configured push URL endpoints.  If the control channel goes silent — antenna " +
            "problem, SDR disconnect, coverage gap — the pings stop, and your monitoring tool " +
            "marks the site as down and alerts you."));

        SettingsSection scope = addSection("Important — What It Does and Does Not Check");
        scope.addContent(callout(
            "✓  The control channel is tuned, receiving RF, and decoding valid P25 messages\n" +
            "✓  The specific site (System ID + Site ID) is actively broadcasting\n" +
            "✗  It does NOT verify that voice calls are being decoded\n" +
            "✗  It does NOT apply to DMR or other protocols (P25 Phase 1 control channels only)\n" +
            "✗  It does NOT apply to traffic/voice channels — only the control channel"));

        SettingsSection findIds = addSection("Finding Your System ID and Site ID");
        findIds.addContent(paragraph(
            "1.  Start SDRTrunk and let the control channel lock on your P25 system.\n" +
            "2.  Click the channel in the main window and open the Details tab.\n" +
            "3.  Look for lines like:  SYSTEM: 291   and   SITE: 1\n" +
            "4.  Use those decimal numbers in the System ID and Site ID fields below.\n\n" +
            "Note: SDRTrunk sometimes shows both decimal and hex (e.g. SYSTEM: 291 / 0x123).\n" +
            "Always use the decimal number — the one before the slash."));
        findIds.addContent(warningNote("Changes take effect after restarting SDRTrunk."));

        SettingsSection entries = addSection("Heartbeat Entries");
        entries.addContent(muted("Each entry monitors one P25 site. Entries can be disabled without deleting them."));
        entries.addContent(getTable());
        entries.addContent(createButtons());
    }

    @SuppressWarnings("unchecked")
    private TableView<HeartbeatEntry> getTable()
    {
        if(mTable != null)
        {
            return mTable;
        }

        mTable = new TableView<>(mItems);
        mTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        mTable.setPlaceholder(new Label("No heartbeat entries — click Add to create one."));
        mTable.setPrefHeight(200);

        TableColumn<HeartbeatEntry,Boolean> enabledColumn = new TableColumn<>("On");
        enabledColumn.setMaxWidth(46);
        enabledColumn.setMinWidth(46);
        enabledColumn.setResizable(false);
        enabledColumn.setCellValueFactory(data -> new SimpleBooleanProperty(data.getValue().isEnabled()));
        enabledColumn.setCellFactory(column -> new TableCell<>()
        {
            private final CheckBox mCheckBox = new CheckBox();

            {
                mCheckBox.setDisable(true);
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Boolean item, boolean empty)
            {
                super.updateItem(item, empty);

                if(empty || item == null)
                {
                    setGraphic(null);
                }
                else
                {
                    mCheckBox.setSelected(item);
                    setGraphic(mCheckBox);
                }
            }
        });

        TableColumn<HeartbeatEntry,String> nameColumn = new TableColumn<>("Channel Name");
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getChannelName()));

        TableColumn<HeartbeatEntry,Number> systemColumn = new TableColumn<>("System ID");
        systemColumn.setMaxWidth(90);
        systemColumn.setMinWidth(80);
        systemColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getSystemId()));

        TableColumn<HeartbeatEntry,Number> siteColumn = new TableColumn<>("Site ID");
        siteColumn.setMaxWidth(76);
        siteColumn.setMinWidth(68);
        siteColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getSiteId()));

        TableColumn<HeartbeatEntry,Number> intervalColumn = new TableColumn<>("Interval (s)");
        intervalColumn.setMaxWidth(90);
        intervalColumn.setMinWidth(80);
        intervalColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getIntervalSeconds()));

        TableColumn<HeartbeatEntry,String> kumaColumn = new TableColumn<>("Uptime Kuma URL");
        kumaColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getKumaUrl()));

        TableColumn<HeartbeatEntry,String> pushColumn = new TableColumn<>("Push URL 2");
        pushColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getPushUrl2()));

        mTable.getColumns().addAll(enabledColumn, nameColumn, systemColumn, siteColumn, intervalColumn, kumaColumn,
            pushColumn);

        mTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            boolean hasSelection = selected != null;
            mEditButton.setDisable(!hasSelection);
            mRemoveButton.setDisable(!hasSelection);
        });

        return mTable;
    }

    private HBox createButtons()
    {
        Button addButton = new Button("Add");
        addButton.setOnAction(e -> onAdd());

        mEditButton = new Button("Edit");
        mEditButton.setDisable(true);
        mEditButton.setOnAction(e -> onEdit());

        mRemoveButton = new Button("Remove");
        mRemoveButton.setDisable(true);
        mRemoveButton.getStyleClass().add("settings-danger-button");
        mRemoveButton.setOnAction(e -> onRemove());

        HBox row = new HBox(8, addButton, mEditButton, mRemoveButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void onAdd()
    {
        showEditDialog(null).ifPresent(entry -> {
            mHeartbeatSettings.addEntry(entry);
            mItems.add(entry);
        });
    }

    private void onEdit()
    {
        int selectedIndex = getTable().getSelectionModel().getSelectedIndex();

        if(selectedIndex < 0)
        {
            return;
        }

        HeartbeatEntry original = mItems.get(selectedIndex);
        showEditDialog(original).ifPresent(updated -> {
            mHeartbeatSettings.updateEntry(selectedIndex, updated);
            mItems.set(selectedIndex, updated);
            getTable().refresh();
        });
    }

    private void onRemove()
    {
        int selectedIndex = getTable().getSelectionModel().getSelectedIndex();

        if(selectedIndex < 0)
        {
            return;
        }

        mHeartbeatSettings.removeEntry(selectedIndex);
        mItems.remove(selectedIndex);
    }

    private Optional<HeartbeatEntry> showEditDialog(HeartbeatEntry existing)
    {
        boolean isNew = (existing == null);
        HeartbeatEntry working = isNew ? new HeartbeatEntry() : new HeartbeatEntry(existing);

        Dialog<HeartbeatEntry> dialog = new Dialog<>();
        dialog.setTitle(isNew ? "Add Heartbeat Entry" : "Edit Heartbeat Entry");
        dialog.setHeaderText(null);
        styleDialog(dialog.getDialogPane());

        if(getScene() != null && getScene().getWindow() != null)
        {
            dialog.initOwner(getScene().getWindow());
        }

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        ColumnConstraints labelColumn = new ColumnConstraints(130);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);

        ToggleSwitch enabledToggle = new ToggleSwitch();
        enabledToggle.setSelected(working.isEnabled());
        enabledToggle.selectedProperty().addListener((obs, o, v) -> working.setEnabled(v));
        int row = 0;
        grid.add(rightLabel("Enabled:"), 0, row);
        grid.add(enabledToggle, 1, row++);

        TextField channelNameField = new TextField(working.getChannelName());
        channelNameField.setPromptText("e.g. ALPHA_SITE");
        channelNameField.textProperty().addListener((obs, o, v) -> working.setChannelName(v));
        GridPane.setFillWidth(channelNameField, true);
        grid.add(rightLabel("Channel Name:"), 0, row);
        grid.add(channelNameField, 1, row++);

        Spinner<Integer> systemIdSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 65535, working.getSystemId()));
        systemIdSpinner.setEditable(true);
        systemIdSpinner.setPrefWidth(120);
        systemIdSpinner.valueProperty().addListener((obs, o, v) -> { if(v != null) working.setSystemId(v); });
        HBox systemRow = new HBox(8, systemIdSpinner, smallHint("Decimal — shown in the Details tab as SYSTEM: 291"));
        systemRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(rightLabel("System ID:"), 0, row);
        grid.add(systemRow, 1, row++);

        Spinner<Integer> siteIdSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 65535, working.getSiteId()));
        siteIdSpinner.setEditable(true);
        siteIdSpinner.setPrefWidth(120);
        siteIdSpinner.valueProperty().addListener((obs, o, v) -> { if(v != null) working.setSiteId(v); });
        HBox siteRow = new HBox(8, siteIdSpinner, smallHint("Decimal — shown in the Details tab as SITE: 1"));
        siteRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(rightLabel("Site ID:"), 0, row);
        grid.add(siteRow, 1, row++);

        TextField kumaField = new TextField(working.getKumaUrl());
        kumaField.setPromptText("http://kuma-host:3001/api/push/TOKEN?status=up&msg=OK&ping=");
        kumaField.textProperty().addListener((obs, o, v) -> working.setKumaUrl(v));
        GridPane.setFillWidth(kumaField, true);
        grid.add(rightLabel("Uptime Kuma URL:"), 0, row);
        grid.add(kumaField, 1, row);
        grid.add(smallHint("Leave blank to skip"), 1, ++row);
        row++;

        TextField pushField = new TextField(working.getPushUrl2());
        pushField.setPromptText("https://your-monitor.example.com/api/beat/TOKEN  (optional)");
        pushField.textProperty().addListener((obs, o, v) -> working.setPushUrl2(v));
        GridPane.setFillWidth(pushField, true);
        grid.add(rightLabel("Push URL 2:"), 0, row);
        grid.add(pushField, 1, row);
        grid.add(smallHint("Secondary HTTP push endpoint — leave blank to skip"), 1, ++row);
        row++;

        Spinner<Integer> intervalSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 3600, working.getIntervalSeconds(), 10));
        intervalSpinner.setEditable(true);
        intervalSpinner.setPrefWidth(100);
        intervalSpinner.valueProperty().addListener((obs, o, v) -> { if(v != null) working.setIntervalSeconds(v); });
        HBox intervalRow = new HBox(8, intervalSpinner,
            smallHint("seconds between pings  (30 recommended; >= Kuma's heartbeat interval)"));
        intervalRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(rightLabel("Ping Interval:"), 0, row);
        grid.add(intervalRow, 1, row++);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(620);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        Runnable validate = () -> {
            boolean ok = (working.getSystemId() > 0 || working.getSiteId() > 0) &&
                (!working.getKumaUrl().isBlank() || !working.getPushUrl2().isBlank());
            saveButton.setDisable(!ok);
        };
        validate.run();
        systemIdSpinner.valueProperty().addListener((obs, o, v) -> validate.run());
        siteIdSpinner.valueProperty().addListener((obs, o, v) -> validate.run());
        kumaField.textProperty().addListener((obs, o, v) -> validate.run());
        pushField.textProperty().addListener((obs, o, v) -> validate.run());

        dialog.setResultConverter(buttonType -> buttonType == saveType ? working : null);
        return dialog.showAndWait();
    }
}
