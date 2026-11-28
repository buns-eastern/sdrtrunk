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

package io.github.dsheirer.gui.settings.application;

import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.gui.theme.Theme;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.application.ApplicationSettings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import org.controlsfx.control.ToggleSwitch;

/**
 * Appearance settings - selects the active UI {@link Theme} and the global GUI zoom factor for the
 * application windows.
 */
public class AppearanceSettingsPage extends SettingsPage
{
    private final ApplicationSettings mApplicationSettings;
    private ComboBox<Theme> mThemeComboBox;
    private ToggleSwitch mComplementaryAliasColorsSwitch;
    private Slider mGuiScaleSlider;
    private Label mGuiScaleValueLabel;
    private Slider mNowPlayingTextSlider;
    private Label mNowPlayingTextValueLabel;
    private ComboBox<ApplicationSettings.RowSpacing> mRowSpacingComboBox;

    public AppearanceSettingsPage(Settings settings)
    {
        super(SettingsPageType.APPEARANCE, settings);
        mApplicationSettings = settings.getApplicationSettings();

        SettingsSection theme = addSection("Colors");
        theme.addRow("Theme", getThemeComboBox())
            .defaultValue(getThemeComboBox().valueProperty(), ApplicationSettings.DEFAULT_THEME,
                getThemeComboBox()::setValue);
        theme.addRow("Use complementary alias colors", getComplementaryAliasColorsSwitch())
            .defaultValue(getComplementaryAliasColorsSwitch().selectedProperty(),
                ApplicationSettings.DEFAULT_COMPLEMENTARY_ALIAS_COLORS, getComplementaryAliasColorsSwitch()::setSelected)
            .help("Dark themes only. Replaces alias colors that are hard to read against the background with a " +
                "complementary color.");

        SettingsSection scale = addSection("Scale");
        getGuiScaleSlider().setPrefWidth(320);
        scale.addRow("User interface scale", getGuiScaleSlider(), getGuiScaleValueLabel())
            .defaultValue(getGuiScaleSlider().valueProperty(), ApplicationSettings.DEFAULT_GUI_SCALE, value -> {
                getGuiScaleSlider().setValue(value.doubleValue());
                mApplicationSettings.setGuiScale(value.doubleValue());
            })
            .help("Adjusts the size of all UI components. Takes effect immediately.");

        SettingsSection nowPlaying = addSection("Now Playing");
        getNowPlayingTextSlider().setPrefWidth(320);
        nowPlaying.addRow("Text size", getNowPlayingTextSlider(), getNowPlayingTextValueLabel())
            .defaultValue(getNowPlayingTextSlider().valueProperty(), ApplicationSettings.DEFAULT_NOW_PLAYING_TEXT_SCALE,
                value -> getNowPlayingTextSlider().setValue(value.doubleValue()))
            .help("Size of the text in the Now Playing table only. Takes effect immediately.");
        nowPlaying.addRow("Row spacing", getRowSpacingComboBox())
            .defaultValue(getRowSpacingComboBox().valueProperty(), ApplicationSettings.DEFAULT_NOW_PLAYING_ROW_SPACING,
                getRowSpacingComboBox()::setValue)
            .help("Space above and below each row in the Now Playing table. Tighter fits more rows.");
    }

    /**
     * Now Playing text size slider: 70% to 130% in 5% steps, applied as it moves.
     */
    private Slider getNowPlayingTextSlider()
    {
        if(mNowPlayingTextSlider == null)
        {
            mNowPlayingTextSlider = new Slider(ApplicationSettings.MIN_NOW_PLAYING_TEXT_SCALE,
                ApplicationSettings.MAX_NOW_PLAYING_TEXT_SCALE, mApplicationSettings.getNowPlayingTextScale());
            mNowPlayingTextSlider.setMajorTickUnit(0.1d);
            mNowPlayingTextSlider.setMinorTickCount(1); //5% steps
            mNowPlayingTextSlider.setSnapToTicks(true);
            mNowPlayingTextSlider.setShowTickMarks(true);
            mNowPlayingTextSlider.setShowTickLabels(false);
            mNowPlayingTextSlider.setBlockIncrement(0.05d);
            mNowPlayingTextSlider.valueProperty().addListener((obs, oldV, newV) -> {
                if(newV != null)
                {
                    double snapped = Math.round(newV.doubleValue() * 20) / 20.0;
                    getNowPlayingTextValueLabel().setText(formatScale(snapped));

                    if(Math.abs(snapped - mApplicationSettings.getNowPlayingTextScale()) > 1e-6)
                    {
                        mApplicationSettings.setNowPlayingTextScale(snapped);
                    }
                }
            });
        }

        return mNowPlayingTextSlider;
    }

    private Label getNowPlayingTextValueLabel()
    {
        if(mNowPlayingTextValueLabel == null)
        {
            mNowPlayingTextValueLabel = new Label(formatScale(mApplicationSettings.getNowPlayingTextScale()));
            mNowPlayingTextValueLabel.setMinWidth(50);
        }

        return mNowPlayingTextValueLabel;
    }

    private ComboBox<ApplicationSettings.RowSpacing> getRowSpacingComboBox()
    {
        if(mRowSpacingComboBox == null)
        {
            mRowSpacingComboBox = new ComboBox<>();
            mRowSpacingComboBox.getItems().addAll(ApplicationSettings.RowSpacing.values());
            mRowSpacingComboBox.setValue(mApplicationSettings.getNowPlayingRowSpacing());
            mRowSpacingComboBox.valueProperty().addListener((obs, oldV, newV) -> {
                if(newV != null && newV != mApplicationSettings.getNowPlayingRowSpacing())
                {
                    mApplicationSettings.setNowPlayingRowSpacing(newV);
                }
            });
        }

        return mRowSpacingComboBox;
    }

    /**
     * Combo box listing each {@link Theme}.  Selecting one persists the theme via
     * {@link ApplicationSettings#setTheme(Theme)} which triggers a global setting event and a re-theme through the
     * ThemeManager.
     */
    private ComboBox<Theme> getThemeComboBox()
    {
        if(mThemeComboBox == null)
        {
            mThemeComboBox = new ComboBox<>();
            mThemeComboBox.getItems().addAll(Theme.values());
            mThemeComboBox.setConverter(new StringConverter<>()
            {
                @Override
                public String toString(Theme theme)
                {
                    return theme != null ? theme.getDisplayName() : "";
                }

                @Override
                public Theme fromString(String string)
                {
                    return null;
                }
            });
            mThemeComboBox.setVisibleRowCount(Theme.values().length);
            mThemeComboBox.getSelectionModel().select(mApplicationSettings.getTheme());
            mThemeComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, theme) -> {
                if(theme != null)
                {
                    mApplicationSettings.setTheme(theme);
                }
            });
        }

        return mThemeComboBox;
    }

    /**
     * Switch to opt in to replacing hard to read alias colors with a complementary color in dark themes.
     */
    private ToggleSwitch getComplementaryAliasColorsSwitch()
    {
        if(mComplementaryAliasColorsSwitch == null)
        {
            mComplementaryAliasColorsSwitch = new ToggleSwitch();
            mComplementaryAliasColorsSwitch.setSelected(mApplicationSettings.isComplementaryAliasColors());
            mComplementaryAliasColorsSwitch.selectedProperty().addListener((observable, oldValue, selected) ->
                mApplicationSettings.setComplementaryAliasColors(selected));
        }

        return mComplementaryAliasColorsSwitch;
    }

    /**
     * Slider to adjust the global GUI zoom factor.  The slider snaps to 10% increments and only
     * persists the value when the user releases the slider so we do not thrash a LAF reinstall on
     * every intermediate value during a drag.
     */
    private Slider getGuiScaleSlider()
    {
        if(mGuiScaleSlider == null)
        {
            mGuiScaleSlider = new Slider(ApplicationSettings.MIN_GUI_SCALE,
                    ApplicationSettings.MAX_GUI_SCALE,
                    mApplicationSettings.getGuiScale());
            mGuiScaleSlider.setMajorTickUnit(0.25d);
            mGuiScaleSlider.setMinorTickCount(4); //5% steps
            mGuiScaleSlider.setSnapToTicks(true);
            mGuiScaleSlider.setShowTickMarks(true);
            mGuiScaleSlider.setShowTickLabels(false);
            mGuiScaleSlider.setBlockIncrement(0.05d);

            //Update the live percentage label as the user drags.
            mGuiScaleSlider.valueProperty().addListener((obs, oldV, newV) -> {
                if(newV != null)
                {
                    getGuiScaleValueLabel().setText(formatScale(newV.doubleValue()));
                }
            });

            //Persist + apply only when the user finishes the drag - avoids reinstalling the LAF
            //on every intermediate frame.
            mGuiScaleSlider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
                if(wasChanging && !changing)
                {
                    mApplicationSettings.setGuiScale(mGuiScaleSlider.getValue());
                }
            });

            //Cover keyboard / scroll-wheel adjustments which never enter the changing=true state.
            mGuiScaleSlider.setOnMouseReleased(e -> {
                if(!mGuiScaleSlider.isValueChanging())
                {
                    mApplicationSettings.setGuiScale(mGuiScaleSlider.getValue());
                }
            });
        }

        return mGuiScaleSlider;
    }

    private Label getGuiScaleValueLabel()
    {
        if(mGuiScaleValueLabel == null)
        {
            mGuiScaleValueLabel = new Label(formatScale(mApplicationSettings.getGuiScale()));
            mGuiScaleValueLabel.setMinWidth(50);
        }

        return mGuiScaleValueLabel;
    }

    private static String formatScale(double scale)
    {
        return Math.round(scale * 100) + "%";
    }
}
