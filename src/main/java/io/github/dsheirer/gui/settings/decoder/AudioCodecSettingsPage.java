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

package io.github.dsheirer.gui.settings.decoder;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.jmbe.JmbeCreator;
import io.github.dsheirer.jmbe.JmbeEditorRequest;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.jmbe.github.Version;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.SettingsType;
import io.github.dsheirer.settings.decoder.JmbeLibrarySettings;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings for the JMBE libraries that decode digital voice audio: the IMBE library used by P25 Phase 1, and the
 * optional AMBE library used by DMR, NXDN and P25 Phase 2.
 */
public class AudioCodecSettingsPage extends SettingsPage
{
    private final static Logger mLog = LoggerFactory.getLogger(AudioCodecSettingsPage.class);
    private static final String PATH_NOT_SET = "(not set)";
    private static final String CREATE_LIBRARY = "Create Library";
    private static final String CHECK_FOR_UPDATE = "Check For Library Update";
    private static final String LIBRARY_NOT_SETUP = "None.  Click Select or Create Library button to setup.";

    private Settings mSettings;
    private final LibrarySection mImbeSection;
    private final LibrarySection mAmbeSection;
    private ToggleSwitch mAlertUserWhenMissingSwitch;

    public AudioCodecSettingsPage(Settings settings)
    {
        super(SettingsPageType.AUDIO_CODECS, settings);
        mSettings = settings;

        //Register to receive JMBE library setting update notifications so we can update the version and path
        MyEventBus.getGlobalEventBus().register(this);

        mImbeSection = new LibrarySection(false, addSection("IMBE Audio Library (P25 Phase 1)"));
        mAmbeSection = new LibrarySection(true, addSection("AMBE Audio Library (DMR, NXDN, P25 Phase 2)"));

        SettingsSection alerts = addSection("Alerts");
        alerts.addRow("Alert when a decoder requires the missing JMBE library", getAlertUserWhenMissingSwitch())
            .defaultValue(getAlertUserWhenMissingSwitch().selectedProperty(),
                JmbeLibrarySettings.DEFAULT_ALERT_IF_MISSING_LIBRARY, getAlertUserWhenMissingSwitch()::setSelected);
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    private JmbeLibrarySettings getLibrarySettings()
    {
        return mSettings.getJmbeLibrarySettings();
    }

    private ToggleSwitch getAlertUserWhenMissingSwitch()
    {
        if(mAlertUserWhenMissingSwitch == null)
        {
            mAlertUserWhenMissingSwitch = new ToggleSwitch();
            mAlertUserWhenMissingSwitch.setSelected(getLibrarySettings().getAlertIfMissingLibraryRequired());
            mAlertUserWhenMissingSwitch.selectedProperty().addListener((observable, oldValue, alert) ->
                getLibrarySettings().setAlertIfMissingLibraryRequired(alert));
        }

        return mAlertUserWhenMissingSwitch;
    }

    @Subscribe
    public void settingsUpdated(SettingsType settingsType)
    {
        if(settingsType != null && settingsType == SettingsType.JMBE_LIBRARY)
        {
            Platform.runLater(() -> {
                mImbeSection.refresh();
                mAmbeSection.refresh();
                getAlertUserWhenMissingSwitch().setSelected(getLibrarySettings().getAlertIfMissingLibraryRequired());
            });
        }
    }

    /**
     * Library rows and buttons for either the IMBE (main) library or the optional AMBE library.
     */
    private class LibrarySection
    {
        private final boolean mAmbe;
        private final Label mVersionLabel = new Label();
        private final Label mPathLabel = new Label();
        private final Button mCreateButton = new Button();
        private final Button mSelectButton = new Button("Select...");
        private final Button mResetButton = new Button("Reset");

        /**
         * Constructs an instance
         * @param ambe true for the optional AMBE library or false for the IMBE (main) library.
         * @param section to add the rows and buttons to
         */
        LibrarySection(boolean ambe, SettingsSection section)
        {
            mAmbe = ambe;

            if(!mAmbe)
            {
                section.addHelp("Create Library downloads the JMBE creator from GitHub and uses it to build the " +
                    "library on this computer.");
            }

            mPathLabel.setWrapText(true);
            section.addRow("Current version", mVersionLabel);
            section.addRow("File", mPathLabel);

            mCreateButton.setOnAction(event -> checkForUpdatedLibrary());
            mSelectButton.setOnAction(event -> selectLibrary());
            mResetButton.setOnAction(event -> {
                if(mAmbe)
                {
                    getLibrarySettings().resetPathJmbeAmbeLibrary();
                }
                else
                {
                    getLibrarySettings().resetPathJmbeLibrary();
                }
            });

            section.addContent(new HBox(10, mCreateButton, mSelectButton, mResetButton));

            if(mAmbe)
            {
                section.addHelp("If not set, these protocols use the IMBE library above.");
            }

            refresh();
        }

        private Path getLibraryPath()
        {
            return mAmbe ? getLibrarySettings().getPathJmbeAmbeLibrary() : getLibrarySettings().getPathJmbeLibrary();
        }

        private Version getLibraryVersion()
        {
            return mAmbe ? getLibrarySettings().getCurrentAmbeVersion() : getLibrarySettings().getCurrentVersion();
        }

        /**
         * Updates the labels and button text from the current settings.
         */
        void refresh()
        {
            Path path = getLibraryPath();
            mPathLabel.setText(path != null ? path.toString() : PATH_NOT_SET);

            Version version = getLibraryVersion();

            if(version != null)
            {
                mVersionLabel.setText(version.toString());
            }
            else
            {
                mVersionLabel.setText(mAmbe ? PATH_NOT_SET : LIBRARY_NOT_SETUP);
            }

            mCreateButton.setText(path != null ? CHECK_FOR_UPDATE : CREATE_LIBRARY);
        }

        private void selectLibrary()
        {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select JMBE Audio Library Location");

            Path path = mSettings.getDirectorySettings().getDefaultJmbeDirectory();

            if(Files.exists(path))
            {
                fileChooser.setInitialDirectory(path.toFile());
            }

            Stage stage = (Stage)mSelectButton.getScene().getWindow();
            File selected = fileChooser.showOpenDialog(stage);

            if(selected != null)
            {
                if(mAmbe)
                {
                    getLibrarySettings().setPathJmbeAmbeLibrary(selected.toPath());
                }
                else
                {
                    getLibrarySettings().setPathJmbeLibrary(selected.toPath());
                }
            }
        }

        /**
         * Checks for availability of an updated JMBE library for this section
         */
        private void checkForUpdatedLibrary()
        {
            ThreadPool.CACHED.execute(() -> {
                try
                {
                    Version current = getLibraryVersion();
                    final Release release = GitHub.getLatestRelease(JmbeCreator.GITHUB_JMBE_RELEASES_URL,
                        JmbeCreator.JMBE_API_MAJOR_VERSION);

                    mLog.info("Checking for JMBE Library Updates (" + (mAmbe ? "AMBE" : "IMBE") +
                        ") for API major version " + JmbeCreator.JMBE_API_MAJOR_VERSION + " ...");
                    mLog.info("Current: " + (current != null ? current.toString() : "empty"));

                    //Offer the latest matching release when there is no current library, when the current library
                    //is a different major version, or when the matching release is newer than the current one.
                    final boolean canUpdate = (release != null) && ((current == null) ||
                        !current.hasMajor() || current.getMajor() != JmbeCreator.JMBE_API_MAJOR_VERSION ||
                        (release.getVersion().compareTo(current) > 0));

                    if(release != null)
                    {
                        mLog.info("Available: " + release.getVersion().toString());
                    }

                    if(canUpdate)
                    {
                        mLog.info("JMBE Library update is available");
                    }
                    else
                    {
                        mLog.info("No JMBE library update is available at this time");
                    }

                    Platform.runLater(() -> {
                        try
                        {
                            if(canUpdate)
                            {
                                String content = "JMBE library version " + release.getVersion().toString() +
                                    " is available.  Would you like to download the latest source code and " +
                                    "create an updated JMBE library?";
                                Alert alert = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.YES,
                                    ButtonType.NO);
                                alert.setResizable(true);

                                //Workaroud for dialog sizing issue on Windows
                                alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);

                                alert.setTitle("JMBE Library Update Check");
                                alert.setHeaderText("Update is available");
                                alert.initOwner(mCreateButton.getScene().getWindow());
                                alert.showAndWait().ifPresent(buttonType -> {
                                    if(buttonType == ButtonType.YES)
                                    {
                                        mLog.info("Posting JMBE editor request to event bus");
                                        MyEventBus.getGlobalEventBus().post(new JmbeEditorRequest(release, mAmbe));
                                    }
                                });
                            }
                            else
                            {
                                Alert alert = new Alert(Alert.AlertType.INFORMATION,
                                    "No JMBE library update is available.", ButtonType.OK);
                                alert.setTitle("JMBE Library Update Check");

                                if(release != null)
                                {
                                    alert.setHeaderText("No update available");
                                }
                                else
                                {
                                    alert.setHeaderText("Unable to determine latest release version.  Check network connection");
                                }

                                alert.initOwner(mCreateButton.getScene().getWindow());
                                alert.showAndWait();
                            }
                        }
                        catch(Throwable t)
                        {
                            mLog.error("Error during JavaFX portion of create library");
                        }
                    });
                }
                catch(Throwable t)
                {
                    mLog.error("Error during create library", t);
                }
            });
        }
    }
}
