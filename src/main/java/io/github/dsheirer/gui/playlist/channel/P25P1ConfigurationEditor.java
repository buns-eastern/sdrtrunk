/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.gui.playlist.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.playlist.record.RecordConfigurationEditor;
import io.github.dsheirer.gui.playlist.source.FrequencyEditor;
import io.github.dsheirer.gui.playlist.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.control.ToggleSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P25 Phase 1 channel configuration editor
 */
public class P25P1ConfigurationEditor extends ChannelConfigurationEditor
{
    private final static Logger mLog = LoggerFactory.getLogger(P25P1ConfigurationEditor.class);
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private SourceConfigurationEditor mSourceConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private RecordConfigurationEditor mRecordConfigurationEditor;
    private ToggleSwitch mIgnoreDataCallsButton;
    private Spinner<Integer> mTrafficChannelPoolSizeSpinner;
    private SegmentedButton mModulationSegmentedButton;
    private ToggleButton mC4FMToggleButton;
    private ToggleButton mLSMToggleButton;
    private TextField mNacDecField;
    private TextField mNacHexField;
    private TextField mSystemDecField;
    private TextField mSystemHexField;
    private TextField mSiteDecField;
    private TextField mSiteHexField;
    private boolean mUpdatingFilterFields = false;

    /**
     * Constructs an instance
     * @param playlistManager for playlists
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    public P25P1ConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                    UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_PHASE1;
    }

    private TitledPane getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new TitledPane("Source", getSourceConfigurationEditor());
            mSourcePane.setExpanded(true);
        }

        return mSourcePane;
    }

    private TitledPane getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new TitledPane();
            mDecoderPane.setText("Decoder: P25 Phase 1 (also used for P25 Phase 2 system with FDMA control channels)");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label modulationLabel = new Label("Modulation");
            GridPane.setHalignment(modulationLabel, HPos.RIGHT);
            GridPane.setConstraints(modulationLabel, 0, 0);
            gridPane.getChildren().add(modulationLabel);

            GridPane.setConstraints(getModulationSegmentedButton(), 1, 0);
            gridPane.getChildren().addAll(getModulationSegmentedButton());

            Label poolSizeLabel = new Label("Max Traffic Channels");
            GridPane.setHalignment(poolSizeLabel, HPos.RIGHT);
            GridPane.setConstraints(poolSizeLabel, 2, 0);
            gridPane.getChildren().add(poolSizeLabel);

            GridPane.setConstraints(getTrafficChannelPoolSizeSpinner(), 3, 0);
            gridPane.getChildren().add(getTrafficChannelPoolSizeSpinner());

            GridPane.setConstraints(getIgnoreDataCallsButton(), 4, 0);
            gridPane.getChildren().add(getIgnoreDataCallsButton());

            Label directionLabel = new Label("Ignore Data Calls");
            GridPane.setHalignment(directionLabel, HPos.LEFT);
            GridPane.setConstraints(directionLabel, 5, 0);
            gridPane.getChildren().add(directionLabel);

            Label modulationHelpLabel = new Label("C4FM: repeaters and non-simulcast trunked systems.  LSM: simulcast trunked systems.");
            GridPane.setConstraints(modulationHelpLabel, 0, 1, 6, 1);
            gridPane.getChildren().add(modulationHelpLabel);

            //Optional control-channel identity filters (NAC/System/Site).  Leave blank for no filtering (default).
            Label filterHeader = new Label("Site Lock Filters (optional)");
            GridPane.setConstraints(filterHeader, 0, 2, 6, 1);
            gridPane.getChildren().add(filterHeader);

            addFilterRow(gridPane, 3, "NAC", getNacDecField(), getNacHexField());
            addFilterRow(gridPane, 4, "System", getSystemDecField(), getSystemHexField());
            addFilterRow(gridPane, 5, "Site", getSiteDecField(), getSiteHexField());

            Label filterHelpLabel = new Label("Leave blank for normal operation.  When set, this channel only locks " +
                    "onto a control channel matching these values, and voice channels inherit the same filter.  " +
                    "Enter decimal or hex - the other field auto-fills.");
            filterHelpLabel.setWrapText(true);
            GridPane.setConstraints(filterHelpLabel, 0, 6, 6, 1);
            gridPane.getChildren().add(filterHelpLabel);

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
    }

    private void addFilterRow(GridPane gridPane, int row, String name, TextField decField, TextField hexField)
    {
        Label label = new Label(name);
        GridPane.setHalignment(label, HPos.RIGHT);
        GridPane.setConstraints(label, 0, row);
        gridPane.getChildren().add(label);

        Label decLabel = new Label("Dec:");
        GridPane.setHalignment(decLabel, HPos.RIGHT);
        GridPane.setConstraints(decLabel, 1, row);
        gridPane.getChildren().add(decLabel);

        GridPane.setConstraints(decField, 2, row);
        gridPane.getChildren().add(decField);

        Label hexLabel = new Label("Hex 0x:");
        GridPane.setHalignment(hexLabel, HPos.RIGHT);
        GridPane.setConstraints(hexLabel, 3, row);
        gridPane.getChildren().add(hexLabel);

        GridPane.setConstraints(hexField, 4, row);
        gridPane.getChildren().add(hexField);
    }

    private TextField getNacDecField()
    {
        if(mNacDecField == null)
        {
            mNacDecField = new TextField();
            mNacDecField.setPrefWidth(90);
            mNacDecField.textProperty().addListener((ob, o, n) -> onDecChanged(mNacDecField, getNacHexField()));
        }
        return mNacDecField;
    }

    private TextField getNacHexField()
    {
        if(mNacHexField == null)
        {
            mNacHexField = new TextField();
            mNacHexField.setPrefWidth(90);
            mNacHexField.textProperty().addListener((ob, o, n) -> onHexChanged(mNacHexField, getNacDecField()));
        }
        return mNacHexField;
    }

    private TextField getSystemDecField()
    {
        if(mSystemDecField == null)
        {
            mSystemDecField = new TextField();
            mSystemDecField.setPrefWidth(90);
            mSystemDecField.textProperty().addListener((ob, o, n) -> onDecChanged(mSystemDecField, getSystemHexField()));
        }
        return mSystemDecField;
    }

    private TextField getSystemHexField()
    {
        if(mSystemHexField == null)
        {
            mSystemHexField = new TextField();
            mSystemHexField.setPrefWidth(90);
            mSystemHexField.textProperty().addListener((ob, o, n) -> onHexChanged(mSystemHexField, getSystemDecField()));
        }
        return mSystemHexField;
    }

    private TextField getSiteDecField()
    {
        if(mSiteDecField == null)
        {
            mSiteDecField = new TextField();
            mSiteDecField.setPrefWidth(90);
            mSiteDecField.textProperty().addListener((ob, o, n) -> onDecChanged(mSiteDecField, getSiteHexField()));
        }
        return mSiteDecField;
    }

    private TextField getSiteHexField()
    {
        if(mSiteHexField == null)
        {
            mSiteHexField = new TextField();
            mSiteHexField.setPrefWidth(90);
            mSiteHexField.textProperty().addListener((ob, o, n) -> onHexChanged(mSiteHexField, getSiteDecField()));
        }
        return mSiteHexField;
    }

    /**
     * Synchronizes the hex field from a decimal entry and flags the editor modified.
     */
    private void onDecChanged(TextField decField, TextField hexField)
    {
        if(mUpdatingFilterFields)
        {
            return;
        }
        mUpdatingFilterFields = true;
        try
        {
            String text = decField.getText();
            if(text == null || text.isBlank())
            {
                hexField.setText("");
            }
            else
            {
                try
                {
                    int value = Integer.parseInt(text.trim());
                    if(value < 0)
                    {
                        value = 0;
                    }
                    hexField.setText(Integer.toHexString(value).toUpperCase());
                }
                catch(NumberFormatException e)
                {
                    //Ignore invalid decimal input - leave hex field unchanged
                }
            }
            modifiedProperty().set(true);
        }
        finally
        {
            mUpdatingFilterFields = false;
        }
    }

    /**
     * Synchronizes the decimal field from a hex entry and flags the editor modified.
     */
    private void onHexChanged(TextField hexField, TextField decField)
    {
        if(mUpdatingFilterFields)
        {
            return;
        }
        mUpdatingFilterFields = true;
        try
        {
            String text = hexField.getText();
            if(text == null || text.isBlank())
            {
                decField.setText("");
            }
            else
            {
                try
                {
                    int value = Integer.parseInt(text.trim().replaceFirst("(?i)^0x", ""), 16);
                    if(value < 0)
                    {
                        value = 0;
                    }
                    decField.setText(Integer.toString(value));
                }
                catch(NumberFormatException e)
                {
                    //Ignore invalid hex input - leave decimal field unchanged
                }
            }
            modifiedProperty().set(true);
        }
        finally
        {
            mUpdatingFilterFields = false;
        }
    }

    /**
     * Loads a filter value (or null) into the decimal and hex fields without flagging the editor modified.
     */
    private void setFilterField(TextField decField, TextField hexField, Integer value)
    {
        mUpdatingFilterFields = true;
        try
        {
            if(value == null)
            {
                decField.setText("");
                hexField.setText("");
            }
            else
            {
                decField.setText(Integer.toString(value));
                hexField.setText(Integer.toHexString(value).toUpperCase());
            }
        }
        finally
        {
            mUpdatingFilterFields = false;
        }
    }

    private void clearFilterFields()
    {
        setFilterField(getNacDecField(), getNacHexField(), null);
        setFilterField(getSystemDecField(), getSystemHexField(), null);
        setFilterField(getSiteDecField(), getSiteHexField(), null);
    }

    /**
     * Parses a filter field into an Integer, returning null when blank or invalid (no filtering).
     */
    private Integer parseFilterField(TextField decField)
    {
        String text = decField.getText();
        if(text == null || text.isBlank())
        {
            return null;
        }
        try
        {
            return Integer.parseInt(text.trim());
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private TitledPane getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new TitledPane("Logging", getEventLogConfigurationEditor());
            mEventLogPane.setExpanded(false);
        }

        return mEventLogPane;
    }

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            Label notice = new Label("Note: use aliases to control call audio recording");
            notice.setPadding(new Insets(10, 10, 0, 10));

            VBox vBox = new VBox();
            vBox.getChildren().addAll(getRecordConfigurationEditor(), notice);

            mRecordPane.setContent(vBox);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_MINIMUM_MS,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_MAXIMUM_MS,
                DecodeConfigP25Phase1.CHANNEL_ROTATION_DELAY_DEFAULT_MS);

            //Add a listener so that we can push change notifications up to this editor
            mSourceConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mSourceConfigurationEditor;
    }

    private EventLogConfigurationEditor getEventLogConfigurationEditor()
    {
        if(mEventLogConfigurationEditor == null)
        {
            List<EventLogType> types = new ArrayList<>();
            types.add(EventLogType.CALL_EVENT);
            types.add(EventLogType.DECODED_MESSAGE);
            types.add(EventLogType.TRAFFIC_CALL_EVENT);
            types.add(EventLogType.TRAFFIC_DECODED_MESSAGE);
            types.add(EventLogType.SYSTEM_CALL_EVENT);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private SegmentedButton getModulationSegmentedButton()
    {
        if(mModulationSegmentedButton == null)
        {
            mModulationSegmentedButton = new SegmentedButton();
            mModulationSegmentedButton.getStyleClass().add(SegmentedButton.STYLE_CLASS_DARK);
            mModulationSegmentedButton.getButtons().addAll(getC4FMToggleButton(), getLSMToggleButton());
            mModulationSegmentedButton.getToggleGroup().selectedToggleProperty().addListener(new ChangeListener<Toggle>()
            {
                @Override
                public void changed(ObservableValue<? extends Toggle> observable, Toggle oldValue, Toggle newValue)
                {
                    if(newValue == null)
                    {
                        //Ensure at least one toggle is always selected
                        oldValue.setSelected(true);
                    }
                    else if(oldValue != null && newValue != null)
                    {
                        //Only set modified if the toggle changed from one to the other
                        modifiedProperty().set(true);
                    }
                }
            });
        }

        return mModulationSegmentedButton;
    }

    private ToggleButton getC4FMToggleButton()
    {
        if(mC4FMToggleButton == null)
        {
            mC4FMToggleButton = new ToggleButton("C4FM");
        }

        return mC4FMToggleButton;
    }

    private ToggleButton getLSMToggleButton()
    {
        if(mLSMToggleButton == null)
        {
            mLSMToggleButton = new ToggleButton("LSM");
        }

        return mLSMToggleButton;
    }

    private ToggleSwitch getIgnoreDataCallsButton()
    {
        if(mIgnoreDataCallsButton == null)
        {
            mIgnoreDataCallsButton = new ToggleSwitch();
            mIgnoreDataCallsButton.setDisable(true);
            mIgnoreDataCallsButton.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mIgnoreDataCallsButton;
    }

    private Spinner<Integer> getTrafficChannelPoolSizeSpinner()
    {
        if(mTrafficChannelPoolSizeSpinner == null)
        {
            mTrafficChannelPoolSizeSpinner = new Spinner();
            mTrafficChannelPoolSizeSpinner.setDisable(true);
            mTrafficChannelPoolSizeSpinner.setTooltip(
                new Tooltip("Maximum number of traffic channels that can be created by the decoder"));
            mTrafficChannelPoolSizeSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50);
            mTrafficChannelPoolSizeSpinner.setValueFactory(svf);
            mTrafficChannelPoolSizeSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mTrafficChannelPoolSizeSpinner;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            types.add(RecorderType.DEMODULATED_BIT_STREAM);
            types.add(RecorderType.MBE_CALL_SEQUENCE);
            types.add(RecorderType.TRAFFIC_BASEBAND);
            types.add(RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM);
            types.add(RecorderType.TRAFFIC_MBE_CALL_SEQUENCE);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        getIgnoreDataCallsButton().setDisable(config == null);
        getTrafficChannelPoolSizeSpinner().setDisable(config == null);

        if(config instanceof DecodeConfigP25Phase1)
        {
            DecodeConfigP25Phase1 decodeConfig = (DecodeConfigP25Phase1)config;
            getIgnoreDataCallsButton().setSelected(decodeConfig.getIgnoreDataCalls());
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(decodeConfig.getTrafficChannelPoolSize());
            if(decodeConfig.getModulation() == Modulation.C4FM)
            {
                getC4FMToggleButton().setSelected(true);
                getLSMToggleButton().setSelected(false);
            }
            else
            {
                getC4FMToggleButton().setSelected(false);
                getLSMToggleButton().setSelected(true);
            }
            setFilterField(getNacDecField(), getNacHexField(), decodeConfig.getNacFilter());
            setFilterField(getSystemDecField(), getSystemHexField(), decodeConfig.getSystemFilter());
            setFilterField(getSiteDecField(), getSiteHexField(), decodeConfig.getSiteFilter());
        }
        else
        {
            getIgnoreDataCallsButton().setSelected(false);
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(0);
            clearFilterFields();
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigP25Phase1 config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigP25Phase1 p1)
        {
            config = p1;
        }
        else
        {
            config = new DecodeConfigP25Phase1();
        }

        config.setIgnoreDataCalls(getIgnoreDataCallsButton().isSelected());
        config.setTrafficChannelPoolSize(getTrafficChannelPoolSizeSpinner().getValue());
        config.setModulation(getC4FMToggleButton().isSelected() ? Modulation.C4FM : Modulation.CQPSK);
        config.setNacFilter(parseFilterField(getNacDecField()));
        config.setSystemFilter(parseFilterField(getSystemDecField()));
        config.setSiteFilter(parseFilterField(getSiteDecField()));
        getItem().setDecodeConfiguration(config);
    }

    @Override
    protected void setEventLogConfiguration(EventLogConfiguration config)
    {
        getEventLogConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveEventLogConfiguration()
    {
        getEventLogConfigurationEditor().save();

        if(getEventLogConfigurationEditor().getItem().getLoggers().isEmpty())
        {
            getItem().setEventLogConfiguration(null);
        }
        else
        {
            getItem().setEventLogConfiguration(getEventLogConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setAuxDecoderConfiguration(AuxDecodeConfiguration config)
    {
        //no-op
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        //no-op
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        getRecordConfigurationEditor().setDisable(config == null);
        getRecordConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveRecordConfiguration()
    {
        getRecordConfigurationEditor().save();
        RecordConfiguration config = getRecordConfigurationEditor().getItem();
        getItem().setRecordConfiguration(config);
    }

    @Override
    protected void setSourceConfiguration(SourceConfiguration config)
    {
        getSourceConfigurationEditor().setSourceConfiguration(config);
    }

    @Override
    protected void saveSourceConfiguration()
    {
        getSourceConfigurationEditor().save();
        SourceConfiguration sourceConfiguration = getSourceConfigurationEditor().getSourceConfiguration();
        getItem().setSourceConfiguration(sourceConfiguration);
    }
}
