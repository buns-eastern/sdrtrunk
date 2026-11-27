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

package io.github.dsheirer.gui.settings.backup;

import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.backup.BackupException;
import io.github.dsheirer.settings.backup.BackupManager;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backup & Migrate: saves all settings into one backup file, and restores a backup at the next start.
 */
public class BackupSettingsPage extends SettingsPage
{
    private static final Logger mLog = LoggerFactory.getLogger(BackupSettingsPage.class);
    private static final String KEY_LAST_TIME = "backup.last.time";
    private static final String KEY_LAST_FILE = "backup.last.file";
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
    private static Runnable sCloseApplicationHandler;

    /** This computer's last backup details.  Stored under the backup node, which is never included in a backup. */
    private final Preferences mPreferences = Preferences.userNodeForPackage(BackupManager.class);
    private final BackupManager mBackupManager;
    private final Button mCreateButton = new Button("Create Backup…");
    private final Button mRestoreButton = new Button("Restore From Backup…");
    private final Label mLastBackupLabel = new Label();

    /**
     * Constructs an instance
     * @param settings to back up
     */
    public BackupSettingsPage(Settings settings)
    {
        super(SettingsPageType.BACKUP, settings);
        mBackupManager = new BackupManager(settings.getDirectorySettings(), settings.getPlaylistSettings(),
            settings.getJmbeLibrarySettings());

        SettingsSection inBackup = new SettingsSection("What’s in the backup");
        inBackup.addContent(bullets(
            "All settings in this Preferences window",
            "Look and layout (theme, fonts, table columns)",
            "Playlists (channels, aliases, streaming)",
            "Tuner settings (matched to each tuner by serial number)",
            "Icons, map and color settings",
            "JMBE audio library files"));

        SettingsSection notInBackup = new SettingsSection("Not in the backup");
        notInBackup.addHelp("These are different on every computer:");
        notInBackup.addContent(bullets(
            "Folder locations",
            "Window sizes and positions",
            "Audio output device",
            "CPU calibration",
            "Recordings and logs"));

        addContent(twoColumns(inBackup, notInBackup));

        addContent(warning("Keep this file private.",
            "It contains your streaming keys, monitoring web addresses and RadioReference login. " +
                "Do not share or post it."));
        addContent(warning("Use the same SDRTrunk version.",
            "Restore a backup with the same version of SDRTrunk that created it. " +
                "Using a different version is at your own risk."));

        mCreateButton.setDefaultButton(false);
        mCreateButton.getStyleClass().add("settings-primary-button");
        mCreateButton.setOnAction(e -> createBackup());
        mRestoreButton.setOnAction(e -> restoreBackup());
        mLastBackupLabel.getStyleClass().add("settings-help");
        mLastBackupLabel.setWrapText(true);
        updateLastBackup();

        SettingsSection create = new SettingsSection("Create a backup");
        create.addContent(steps(
            flow(plain("Click "), bold("Create Backup"), plain(".")),
            flow(plain("Choose where to save the file."))));
        create.addContent(mCreateButton);
        create.addContent(mLastBackupLabel);

        SettingsSection restore = new SettingsSection("Restore a backup");
        restore.addContent(steps(
            flow(plain("Click "), bold("Restore From Backup"), plain(" and pick the backup file.")),
            flow(plain("SDRTrunk checks the file and saves a copy of your current setup first.")),
            flow(plain("Restart SDRTrunk to finish."))));
        restore.addContent(mRestoreButton);

        addContent(twoColumns(create, restore));
    }

    /**
     * Sets the handler used by "Close SDRTrunk Now" to run the normal application shutdown.
     */
    public static void setCloseApplicationHandler(Runnable handler)
    {
        sCloseApplicationHandler = handler;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------------------------------------------------

    private void createBackup()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Create Backup");
        chooser.setInitialFileName(BackupManager.getDefaultBackupFileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup file (*.zip)", "*.zip"));
        setInitialDirectory(chooser);

        File file = chooser.showSaveDialog(getWindow());

        if(file == null)
        {
            return;
        }

        Path target = file.toPath();

        if(!target.getFileName().toString().toLowerCase().endsWith(".zip"))
        {
            target = target.resolveSibling(target.getFileName().toString() + ".zip");
        }

        final Path finalTarget = target;
        setBusy(true);

        ThreadPool.CACHED.execute(() -> {
            try
            {
                mBackupManager.createBackup(finalTarget);
                mPreferences.put(KEY_LAST_TIME, LocalDateTime.now().toString());
                mPreferences.put(KEY_LAST_FILE, finalTarget.toAbsolutePath().toString());

                Platform.runLater(() -> {
                    setBusy(false);
                    updateLastBackup();
                    showMessage("Backup Saved", flowNode(
                        okLine("Your backup was saved and checked."),
                        fileRow("File", finalTarget.toAbsolutePath().toString()),
                        new Label("Keep this file private.")), ButtonType.OK);
                });
            }
            catch(BackupException be)
            {
                mLog.error("Backup failed", be);
                Platform.runLater(() -> {
                    setBusy(false);
                    showMessage("Can’t Create Backup", flowNode(
                        new Label("The backup could not be saved."),
                        boldLabel("Nothing was changed.")), ButtonType.OK);
                });
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------------------------------------------------

    private void restoreBackup()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Restore From Backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup file (*.zip)", "*.zip"));
        setInitialDirectory(chooser);

        File file = chooser.showOpenDialog(getWindow());

        if(file == null)
        {
            return;
        }

        Path backup = file.toPath();
        setBusy(true);

        //Check the whole file first
        ThreadPool.CACHED.execute(() -> {
            try
            {
                BackupManager.BackupContents contents = BackupManager.readAndVerify(backup);
                Platform.runLater(() -> {
                    setBusy(false);
                    confirmRestore(backup, contents);
                });
            }
            catch(BackupException be)
            {
                mLog.error("Backup can't be used", be);
                Platform.runLater(() -> {
                    setBusy(false);
                    showDamaged();
                });
            }
        });
    }

    private void confirmRestore(Path backup, BackupManager.BackupContents contents)
    {
        String backupVersion = contents.getApplicationVersion();
        String thisVersion = BackupManager.getApplicationVersion();

        if(!backupVersion.equals(thisVersion))
        {
            GridPane versions = new GridPane();
            versions.setHgap(10);
            versions.setVgap(3);
            versions.add(mutedLabel("Backup made with"), 0, 0);
            versions.add(new Label(formatVersion(backupVersion)), 1, 0);
            versions.add(mutedLabel("This computer"), 0, 1);
            versions.add(new Label(formatVersion(thisVersion)), 1, 1);

            ButtonType restoreAnyway = new ButtonType("Restore Anyway", ButtonBar.ButtonData.OK_DONE);
            Optional<ButtonType> choice = showMessage("Different SDRTrunk Version", flowNode(
                new Label("This backup was made with a different version of SDRTrunk."),
                versions,
                new Label("Restoring may not work correctly. Continue at your own risk.")),
                ButtonType.CANCEL, restoreAnyway);

            if(choice.isEmpty() || choice.get() != restoreAnyway)
            {
                return;
            }
        }

        Path beforeRestore;

        try
        {
            beforeRestore = mBackupManager.getBeforeRestorePath();
        }
        catch(Exception e)
        {
            mLog.error("Unable to prepare the backups folder", e);
            showCantRestore();
            return;
        }

        ButtonType restore = new ButtonType("Restore", ButtonBar.ButtonData.OK_DONE);
        Optional<ButtonType> confirm = showMessage("Restore This Backup?", flowNode(
            new Label("Your current settings, playlists and tuner settings will be replaced."),
            new Label("A copy of your current setup is saved first, so you can go back:"),
            fileRow("Saved to", beforeRestore.toAbsolutePath().toString())),
            ButtonType.CANCEL, restore);

        if(confirm.isEmpty() || confirm.get() != restore)
        {
            return;
        }

        setBusy(true);

        ThreadPool.CACHED.execute(() -> {
            try
            {
                mBackupManager.stageRestore(backup, beforeRestore);
                Platform.runLater(() -> {
                    setBusy(false);
                    showRestartToFinish();
                });
            }
            catch(BackupException be)
            {
                mLog.error("Restore could not be staged", be);
                Platform.runLater(() -> {
                    setBusy(false);
                    showCantRestore();
                });
            }
        });
    }

    private void showRestartToFinish()
    {
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType closeNow = new ButtonType("Close SDRTrunk Now", ButtonBar.ButtonData.OK_DONE);
        Optional<ButtonType> choice = showMessage("Restart to Finish", flowNode(
            new Label("The backup was checked and is ready."),
            new Label("Restart SDRTrunk to finish the restore. Until you restart, nothing has changed.")),
            later, closeNow);

        if(choice.isPresent() && choice.get() == closeNow && sCloseApplicationHandler != null)
        {
            sCloseApplicationHandler.run();
        }
    }

    private void showDamaged()
    {
        showMessage("Can’t Use This Backup", flowNode(
            new Label("This backup file is damaged or incomplete."),
            boldLabel("Nothing was changed.")), ButtonType.OK);
    }

    private void showCantRestore()
    {
        showMessage("Can’t Restore Backup", flowNode(
            new Label("The restore could not be prepared."),
            boldLabel("Nothing was changed.")), ButtonType.OK);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Startup results (shown once after a staged restore was applied)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Shows the result of a restore that was applied at this start, if any.  Call on the JavaFX thread.
     */
    public static void showStartupResults(Window owner)
    {
        Path undo = BackupManager.getCompletedRestoreUndoPath();
        boolean failed = BackupManager.isRestoreFailed();
        BackupManager.clearStartupResults();

        if(undo != null)
        {
            show(owner, "Restore Complete", flowNode(
                okLine("Your backup was restored."),
                new Label("To undo, restore this file:"),
                fileRow("File", undo.toAbsolutePath().toString())), ButtonType.OK);
        }
        else if(failed)
        {
            show(owner, "Can’t Use This Backup", flowNode(
                new Label("This backup file is damaged or incomplete."),
                boldLabel("Nothing was changed.")), ButtonType.OK);
        }
    }

    /**
     * Indicates if there is a restore result to show at startup.
     */
    public static boolean hasStartupResults()
    {
        return BackupManager.getCompletedRestoreUndoPath() != null || BackupManager.isRestoreFailed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private void setBusy(boolean busy)
    {
        mCreateButton.setDisable(busy);
        mRestoreButton.setDisable(busy);
    }

    private void updateLastBackup()
    {
        String time = mPreferences.get(KEY_LAST_TIME, null);
        String file = mPreferences.get(KEY_LAST_FILE, null);

        if(time != null && file != null)
        {
            String display;

            try
            {
                display = LocalDateTime.parse(time).format(DISPLAY_TIME);
            }
            catch(Exception e)
            {
                display = time;
            }

            mLastBackupLabel.setText("Last backup: " + display + "\n" + Paths.get(file).getFileName());
            mLastBackupLabel.setVisible(true);
            mLastBackupLabel.setManaged(true);
        }
        else
        {
            mLastBackupLabel.setVisible(false);
            mLastBackupLabel.setManaged(false);
        }
    }

    private void setInitialDirectory(FileChooser chooser)
    {
        String last = mPreferences.get(KEY_LAST_FILE, null);

        try
        {
            if(last != null)
            {
                Path folder = Paths.get(last).getParent();

                if(folder != null && Files.isDirectory(folder))
                {
                    chooser.setInitialDirectory(folder.toFile());
                    return;
                }
            }

            Path home = Paths.get(System.getProperty("user.home"));

            if(Files.isDirectory(home))
            {
                chooser.setInitialDirectory(home.toFile());
            }
        }
        catch(Exception e)
        {
            //Use the chooser default
        }
    }

    /**
     * Readable version: "nightly · Nov 11, 2026 1:29 PM" for nightly builds, otherwise the version number.
     */
    static String formatVersion(String version)
    {
        if(version == null)
        {
            return "unknown";
        }

        String text = version.startsWith("sdrtrunk ") ? version.substring(9) : version;

        if(text.startsWith("nightly - "))
        {
            String stamp = text.substring(10).trim();

            try
            {
                OffsetDateTime time = OffsetDateTime.parse(stamp, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
                return "nightly · " + time.atZoneSameInstant(ZoneId.systemDefault()).format(DISPLAY_TIME);
            }
            catch(Exception e)
            {
                return "nightly · " + stamp;
            }
        }

        return text;
    }

    private Window getWindow()
    {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private Optional<ButtonType> showMessage(String title, Node content, ButtonType... buttons)
    {
        return show(getWindow(), title, content, buttons);
    }

    private static Optional<ButtonType> show(Window owner, String title, Node content, ButtonType... buttons)
    {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(540);
        alert.getButtonTypes().setAll(buttons);

        //The settings styles (help text, file paths, danger and primary buttons) also apply to these dialogs
        alert.getDialogPane().getStyleClass().add("settings-editor");
        URL stylesheet = BackupSettingsPage.class.getResource("/settings_editor.css");

        if(stylesheet != null)
        {
            alert.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        }

        if(owner != null)
        {
            alert.initOwner(owner);
        }

        for(ButtonType type: buttons)
        {
            Node button = alert.getDialogPane().lookupButton(type);

            if(button != null)
            {
                if(type.getText().equals("Restore Anyway"))
                {
                    button.getStyleClass().add("settings-danger-button");
                }
                else if(type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                {
                    button.getStyleClass().add("settings-primary-button");
                }
            }
        }

        return alert.showAndWait();
    }

    private static VBox flowNode(Node... nodes)
    {
        VBox box = new VBox(10, nodes);
        box.setPadding(new Insets(4, 0, 4, 0));

        for(Node node: nodes)
        {
            if(node instanceof Label label)
            {
                label.setWrapText(true);
            }
        }

        return box;
    }

    private static Label okLine(String text)
    {
        return new Label("✓ " + text);
    }

    private static Label boldLabel(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static Label mutedLabel(String text)
    {
        Label label = new Label(text);
        label.getStyleClass().add("settings-help");
        return label;
    }

    private static GridPane fileRow(String name, String path)
    {
        GridPane row = new GridPane();
        row.setHgap(10);
        Label value = new Label(path);
        value.getStyleClass().addAll("settings-help", "settings-value");
        value.setWrapText(true);
        row.add(mutedLabel(name), 0, 0);
        row.add(value, 1, 0);
        return row;
    }

    private static GridPane twoColumns(Node left, Node right)
    {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(50);
        column.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(column, column);
        grid.add(left, 0, 0);
        grid.add(right, 1, 0);
        GridPane.setFillHeight(left, true);
        GridPane.setFillHeight(right, true);

        if(left instanceof VBox box)
        {
            box.setMaxHeight(Double.MAX_VALUE);
        }

        if(right instanceof VBox box)
        {
            box.setMaxHeight(Double.MAX_VALUE);
        }

        return grid;
    }

    private static VBox bullets(String... items)
    {
        VBox box = new VBox(4);

        for(String item: items)
        {
            Label label = new Label("•  " + item);
            label.setWrapText(true);
            box.getChildren().add(label);
        }

        return box;
    }

    private static VBox steps(TextFlow... flows)
    {
        VBox box = new VBox(4);
        int number = 1;

        for(TextFlow flow: flows)
        {
            Text prefix = new Text((number++) + ".  ");
            prefix.getStyleClass().add("settings-text");
            flow.getChildren().add(0, prefix);
            box.getChildren().add(flow);
        }

        return box;
    }

    private static TextFlow flow(Text... parts)
    {
        return new TextFlow(parts);
    }

    private static Text plain(String text)
    {
        Text t = new Text(text);
        t.getStyleClass().add("settings-text");
        return t;
    }

    private static Text bold(String text)
    {
        Text t = plain(text);
        t.setStyle("-fx-font-weight: bold;");
        return t;
    }

    private static Node warning(String title, String body)
    {
        Text heading = new Text(title + " ");
        heading.getStyleClass().add("settings-warning-title");
        Text text = new Text(body);
        text.getStyleClass().add("settings-text");
        TextFlow flow = new TextFlow(heading, text);
        IconNode icon = new IconNode(FontAwesome.EXCLAMATION_TRIANGLE);
        icon.setIconSize(13);
        icon.getStyleClass().add("settings-warning-icon");
        HBox box = new HBox(10, icon, flow);
        box.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(flow, Priority.ALWAYS);
        box.getStyleClass().add("settings-warning-box");
        return box;
    }
}
