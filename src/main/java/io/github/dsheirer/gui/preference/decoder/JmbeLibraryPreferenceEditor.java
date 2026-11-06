/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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
package io.github.dsheirer.gui.preference.decoder;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.jmbe.JmbeCreator;
import io.github.dsheirer.jmbe.JmbeEditorRequest;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.jmbe.github.Version;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.decoder.JmbeLibraryPreference;
import io.github.dsheirer.util.ThreadPool;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * JMBE audio library preference settings.  The IMBE library (P25 Phase 1) is the main JMBE library.  An optional,
 * separate AMBE library can be set for DMR, NXDN and P25 Phase 2 audio.  When the AMBE library is not set, those
 * protocols use the IMBE library.
 */
public class JmbeLibraryPreferenceEditor extends VBox
{
    private final static Logger mLog = LoggerFactory.getLogger(JmbeLibraryPreferenceEditor.class);

    private static final String PATH_NOT_SET = "(not set)";
    private static final String CREATE_LIBRARY = "Create Library";
    private static final String CHECK_FOR_UPDATE = "Check For Library Update";
    private static final String LIBRARY_NOT_SETUP = "None.  Click Select or Create Library button to setup.";

    private UserPreferences mUserPreferences;
    private CheckBox mAlertUserWhenMissingCheckBox;
    private LibrarySection mImbeSection;
    private LibrarySection mAmbeSection;

    public JmbeLibraryPreferenceEditor(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;

        //Register to receive directory preference update notifications so we can update the path labels
        MyEventBus.getGlobalEventBus().register(this);

        mImbeSection = new LibrarySection(false);
        mAmbeSection = new LibrarySection(true);

        Label restartLabel = new Label("Changes take effect after restarting SDRTrunk.");

        setPadding(new Insets(10,10,10,10));
        setSpacing(10);
        getChildren().addAll(new Label("JMBE Audio Libraries"), getAlertUserWhenMissingCheckBox(), new Separator(),
            mImbeSection, new Separator(), mAmbeSection, new Separator(), restartLabel);
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    private JmbeLibraryPreference getPreference()
    {
        return mUserPreferences.getJmbeLibraryPreference();
    }

    private CheckBox getAlertUserWhenMissingCheckBox()
    {
        if(mAlertUserWhenMissingCheckBox == null)
        {
            mAlertUserWhenMissingCheckBox = new CheckBox("Alert when decoder requires missing JMBE library");
            mAlertUserWhenMissingCheckBox.setSelected(getPreference().getAlertIfMissingLibraryRequired());
            mAlertUserWhenMissingCheckBox.setOnAction(event -> {
                boolean alert = mAlertUserWhenMissingCheckBox.isSelected();
                getPreference().setAlertIfMissingLibraryRequired(alert);
            });
        }

        return mAlertUserWhenMissingCheckBox;
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType != null && preferenceType == PreferenceType.JMBE_LIBRARY)
        {
            Platform.runLater(() -> {
                mImbeSection.refresh();
                mAmbeSection.refresh();
                getAlertUserWhenMissingCheckBox().setSelected(getPreference().getAlertIfMissingLibraryRequired());
            });
        }
    }

    /**
     * Library settings section for either the IMBE (main) library or the optional AMBE library.
     */
    private class LibrarySection extends VBox
    {
        private final boolean mAmbe;
        private final Label mVersionLabel = new Label();
        private final Label mPathLabel = new Label();
        private final Button mCreateButton = new Button();
        private final Button mSelectButton = new Button("Select...");
        private final Button mResetButton = new Button("Reset");

        /**
         * Constructs an instance
         * @param ambe true for the optional AMBE library section or false for the IMBE (main) library section.
         */
        LibrarySection(boolean ambe)
        {
            mAmbe = ambe;
            setSpacing(10);

            GridPane gridPane = new GridPane();
            gridPane.setVgap(10);
            gridPane.setHgap(10);

            int row = 0;
            gridPane.add(new Label(mAmbe ? "AMBE Audio Library (DMR, NXDN, P25 Phase 2)" :
                "IMBE Audio Library (P25 Phase 1)"), 0, row, 2, 1);

            Label versionLabel = new Label("Current Version:");
            GridPane.setHalignment(versionLabel, HPos.RIGHT);
            gridPane.add(versionLabel, 0, ++row);
            gridPane.add(mVersionLabel, 1, row);

            Label fileLabel = new Label("File:");
            GridPane.setHalignment(fileLabel, HPos.RIGHT);
            gridPane.add(fileLabel, 0, ++row);
            gridPane.add(mPathLabel, 1, row);

            mCreateButton.setOnAction(event -> checkForUpdatedLibrary());
            mSelectButton.setOnAction(event -> selectLibrary());
            mResetButton.setOnAction(event -> {
                if(mAmbe)
                {
                    getPreference().resetPathJmbeAmbeLibrary();
                }
                else
                {
                    getPreference().resetPathJmbeLibrary();
                }
            });

            HBox buttonsBox = new HBox();
            buttonsBox.setSpacing(10);
            buttonsBox.getChildren().addAll(mCreateButton, mSelectButton, mResetButton);

            getChildren().addAll(gridPane, buttonsBox);

            if(mAmbe)
            {
                getChildren().add(new Label("If not set, these protocols use the IMBE library above."));
            }

            refresh();
        }

        private Path getLibraryPath()
        {
            return mAmbe ? getPreference().getPathJmbeAmbeLibrary() : getPreference().getPathJmbeLibrary();
        }

        private Version getLibraryVersion()
        {
            return mAmbe ? getPreference().getCurrentAmbeVersion() : getPreference().getCurrentVersion();
        }

        /**
         * Updates the labels and button text from the current preference values.
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

            Path path = mUserPreferences.getDirectoryPreference().getDefaultJmbeDirectory();

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
                    getPreference().setPathJmbeAmbeLibrary(selected.toPath());
                }
                else
                {
                    getPreference().setPathJmbeLibrary(selected.toPath());
                }
            }
        }

        /**
         * Checks for availability of an updated JMBE library for this section.
         */
        private void checkForUpdatedLibrary()
        {
            ThreadPool.CACHED.execute(() -> {
                try
                {
                    Version current = getLibraryVersion();
                    final Release release = GitHub.getLatestRelease(JmbeCreator.GITHUB_JMBE_RELEASES_URL);

                    mLog.info("Checking for JMBE Library Updates (" + (mAmbe ? "AMBE" : "IMBE") + ") ...");
                    mLog.info("Current: " + (current != null ? current.toString() : "empty"));

                    final boolean canUpdate = (release != null) && ((current == null) ||
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
                                Alert alert = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.YES, ButtonType.NO);
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
