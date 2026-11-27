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

import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.settings.Settings;
import java.net.URL;
import java.util.function.IntConsumer;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;

/**
 * Base for the External Outputs settings pages: shared pieces for explanations, notes, code samples, tips and port
 * numbers.  Changes on these pages are stored as soon as they are made, unless a page says otherwise.
 */
public abstract class OutputSettingsPage extends SettingsPage
{
    /**
     * Constructs an instance
     * @param pageType for the page header
     * @param settings to edit
     */
    protected OutputSettingsPage(SettingsPageType pageType, Settings settings)
    {
        super(pageType, settings);
    }

    /**
     * Wrapping paragraph of explanation text.
     */
    protected static Label paragraph(String text)
    {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /**
     * Bold label.
     */
    protected static Label bold(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    /**
     * Note in the warning color with a warning icon, such as a restart notice.
     */
    protected static Label warningNote(String text)
    {
        IconNode icon = new IconNode(FontAwesome.EXCLAMATION_TRIANGLE);
        icon.setIconSize(11);
        icon.getStyleClass().add("settings-warning-icon");
        Label label = new Label(text, icon);
        label.setWrapText(true);
        label.getStyleClass().add("settings-warning-note");
        return label;
    }

    /**
     * Read-only box for a code sample or message format example.  Dark in every theme.
     */
    protected static TextArea codeBox(String code, double prefHeight)
    {
        TextArea textArea = new TextArea(code);
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setPrefHeight(prefHeight);
        textArea.getStyleClass().add("settings-code");
        VBox.setVgrow(textArea, Priority.NEVER);
        return textArea;
    }

    /**
     * Tip box with a light bulb icon.
     * @param text of the tip
     * @param fixedWidth to show the text in a fixed width font, for tips that line up commands
     */
    protected static Node tip(String text, boolean fixedWidth)
    {
        IconNode icon = new IconNode(FontAwesome.LIGHTBULB_O);
        icon.setIconSize(13);
        icon.getStyleClass().add("settings-tip-icon");
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-tip-text");

        if(fixedWidth)
        {
            label.getStyleClass().add("settings-value");
        }
        HBox box = new HBox(8, icon, label);
        box.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        box.getStyleClass().add("settings-tip");
        return box;
    }

    /**
     * Label and port spinner on one line.
     */
    protected static HBox portRow(String label, int value, IntConsumer setter)
    {
        Label name = new Label(label);
        name.setMinWidth(90);
        HBox row = new HBox(10, name, portSpinner(value, setter));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Editable spinner for a TCP port number (1024 - 65535) that stores each new value.
     */
    protected static Spinner<Integer> portSpinner(int value, IntConsumer setter)
    {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1024, 65535, value));
        spinner.setEditable(true);
        spinner.setPrefWidth(100);
        spinner.valueProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null)
            {
                setter.accept(newValue);
            }
        });
        return spinner;
    }

    /**
     * Note beside a Save button, in the warning color with a gear icon.
     */
    protected static Label saveNote(String text)
    {
        IconNode gear = new IconNode(FontAwesome.COG);
        gear.setIconSize(11);
        gear.getStyleClass().add("settings-warning-icon");
        Label label = new Label(text, gear);
        label.setWrapText(true);
        label.getStyleClass().add("settings-warning-note");
        return label;
    }

    /**
     * Small muted label, used for status messages after Save and for small notes.
     */
    protected static Label muted(String text)
    {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-help");
        return label;
    }

    /**
     * Paragraph shown in a shaded call-out box.
     */
    protected static Label callout(String text)
    {
        Label label = paragraph(text);
        label.getStyleClass().add("settings-callout");
        return label;
    }

    /**
     * Two column form: bold labels on the right of a fixed width column, fields filling the rest.
     */
    protected static GridPane formGrid()
    {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(4, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints(150);
        labelColumn.setHalignment(HPos.RIGHT);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        return grid;
    }

    /**
     * Bold form label aligned to the right of its column.
     */
    protected static Label rightLabel(String text)
    {
        Label label = bold(text);
        GridPane.setHalignment(label, HPos.RIGHT);
        return label;
    }

    /**
     * Editable spinner for a number of seconds.
     */
    protected static Spinner<Integer> secondsSpinner(int min, int max, int value, int step)
    {
        Spinner<Integer> spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max,
            Math.max(min, Math.min(max, value)), step));
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        return spinner;
    }

    /**
     * Applies the settings styles to a dialog opened from a settings page, so its hints and buttons match the page.
     */
    protected static void styleDialog(DialogPane dialogPane)
    {
        dialogPane.getStyleClass().add("settings-editor");
        URL stylesheet = OutputSettingsPage.class.getResource("/settings_editor.css");

        if(stylesheet != null)
        {
            dialogPane.getStylesheets().add(stylesheet.toExternalForm());
        }
    }

    /**
     * Small muted hint shown beside or under a form field.
     */
    protected static Label smallHint(String text)
    {
        Label label = muted(text);
        label.setStyle("-fx-font-size: 0.85em;");
        return label;
    }
}
