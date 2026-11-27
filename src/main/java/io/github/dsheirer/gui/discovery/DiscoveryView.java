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

package io.github.dsheirer.gui.discovery;

import io.github.dsheirer.discovery.DiscoveryHit;
import io.github.dsheirer.discovery.DiscoveryManager;
import io.github.dsheirer.discovery.DiscoveryMonitor;
import io.github.dsheirer.discovery.DiscoverySettings;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.ViewSettingsEditorRequest;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery tab: watches every bin of a tuner's polyphase channelizer for signals, records clips and identifies them.
 */
public class DiscoveryView extends StackPane
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryView.class);
    private static final DecimalFormat MHZ = new DecimalFormat("0.000000");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM/dd HH:mm:ss");

    private final DiscoveryManager mManager;
    private final ObservableList<DiscoveryHit> mHits = FXCollections.observableArrayList();
    private final FilteredList<DiscoveryHit> mFilteredHits = new FilteredList<>(mHits, hit -> true);
    private final TableView<DiscoveryHit> mTable = new TableView<>();
    private final ComboBox<TunerItem> mTunerCombo = new ComboBox<>();
    private final ToggleButton mMonitorToggle = new ToggleButton("Start Discovery");
    private final Label mStatusLabel = new Label(" ");
    private final Spinner<Double> mSnrSpinner = new Spinner<>(0.0, 80.0, 15.0, 1.0);
    private final Spinner<Double> mLevelSpinner = new Spinner<>(-200.0, 20.0, -150.0, 1.0);
    private final Spinner<Integer> mDwellSpinner = new Spinner<>(0, 5000, 200, 100);
    private final Spinner<Integer> mClipSpinner = new Spinner<>(1, 60, 5, 1);
    private final Spinner<Integer> mCooldownSpinner = new Spinner<>(0, 3600, 60, 10);
    private final CheckBox mIgnoreDcCheck = new CheckBox("Ignore DC bin");
    private final Spinner<Integer> mMinHitsSpinner = new Spinner<>(1, 1000, 1, 1);
    private final CheckBox mHideBelowLevelCheck = new CheckBox("Hide below Level");
    private final CheckBox mHideIgnoredCheck = new CheckBox("Hide ignored");
    private final VBox mContent = new VBox(6);
    private final VBox mNotice;
    private boolean mLoadingSettings = false;
    private volatile boolean mRefreshPending = false;
    private final Timeline mRefreshTimer;
    private final Timeline mTunerRefreshTimer;

    /**
     * Constructs an instance
     * @param manager for discovery
     */
    public DiscoveryView(DiscoveryManager manager)
    {
        mManager = manager;
        mNotice = createChannelizerNotice();
        init();
        getChildren().addAll(mContent, mNotice);
        updateCard();

        mRefreshTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if(mRefreshPending)
            {
                mRefreshPending = false;
                refreshTablePreservingSelection();
            }

            updateStatus();
        }));
        mRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        mRefreshTimer.play();

        mManager.addListener(new DiscoveryManager.HitListener()
        {
            @Override
            public void hitsChanged()
            {
                mRefreshPending = true;
            }

            @Override
            public void statusChanged(String tunerId, String status)
            {
                Platform.runLater(DiscoveryView.this::updateToggle);
            }
        });

        //Tuners can be started, stopped, or hot-plugged at any time, so keep rescanning for the life of the view.
        //This only rebuilds a small combo box and costs nothing measurable.
        mTunerRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            updateCard();
            refreshTuners();
            mManager.autoStart();
            updateToggle();
        }));
        mTunerRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        mTunerRefreshTimer.play();
    }

    /**
     * Stops the refresh timers.
     */
    public void dispose()
    {
        mRefreshTimer.stop();
        mTunerRefreshTimer.stop();
    }

    /**
     * Shows either the discovery controls or the heterodyne notice, depending on the channelizer setting.
     */
    private void updateCard()
    {
        boolean polyphase = mManager.isPolyphaseChannelizer();
        mContent.setVisible(polyphase);
        mNotice.setVisible(!polyphase);
    }

    /**
     * Full-view notice shown when the application is configured for the heterodyne channelizer.  Discovery reads the
     * polyphase channelizer's per-bin output, which heterodyne does not produce.
     */
    private VBox createChannelizerNotice()
    {
        Label headline = new Label("Discovery requires the Polyphase channelizer");
        headline.setFont(Font.font(null, FontWeight.BOLD, 22));
        headline.getStyleClass().add("discovery-notice-headline");

        Label detail = new Label("This application is currently set to the Heterodyne channelizer, which creates one " +
            "tuned down-converter per channel on demand and never produces a full-spectrum view.  Discovery watches " +
            "every bin of the Polyphase channelizer at once, so it has nothing to listen to in Heterodyne mode.");
        detail.setWrapText(true);
        detail.setTextAlignment(TextAlignment.CENTER);
        detail.setMaxWidth(560);
        detail.setFont(Font.font(14));

        Label how = new Label("To use Discovery: open Settings > Tuners, set Channelizer type to Polyphase, then " +
            "restart the application.  This tab enables itself automatically.");
        how.setWrapText(true);
        how.setTextAlignment(TextAlignment.CENTER);
        how.setMaxWidth(560);
        how.setFont(Font.font(14));

        Button settings = new Button("Open Tuner Settings");
        settings.setOnAction(e -> {
            try
            {
                MyEventBus.getGlobalEventBus().post(new ViewSettingsEditorRequest(SettingsPageType.TUNERS));
            }
            catch(Throwable t)
            {
                mLog.error("Error opening tuner settings from the discovery tab", t);
            }
        });

        VBox notice = new VBox(16, headline, detail, how, settings);
        notice.setAlignment(Pos.CENTER);
        notice.setPadding(new Insets(40));
        return notice;
    }

    private void init()
    {
        mContent.setPadding(new Insets(4));

        //Row 1: tuner selection and monitor toggle
        mTunerCombo.setMinWidth(260);
        mTunerCombo.setOnAction(e -> {
            loadSettings();
            updateToggle();
        });
        mMonitorToggle.setOnAction(e -> toggleMonitor());
        Button refresh = new Button("Refresh Tuners");
        refresh.setOnAction(e -> refreshTuners());
        HBox.setHgrow(mStatusLabel, Priority.ALWAYS);
        mStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox row1 = new HBox(8, new Label("Tuner:"), mTunerCombo, mMonitorToggle, mStatusLabel, refresh);
        row1.setAlignment(Pos.CENTER_LEFT);

        //Row 2: detection thresholds (per tuner)
        mIgnoreDcCheck.setSelected(true);
        for(Spinner<?> spinner: List.of(mSnrSpinner, mLevelSpinner, mDwellSpinner, mClipSpinner, mCooldownSpinner))
        {
            spinner.setEditable(true);
            spinner.valueProperty().addListener((o, ov, nv) -> applySettings());
        }

        mIgnoreDcCheck.setOnAction(e -> applySettings());
        mSnrSpinner.setPrefWidth(75);
        mLevelSpinner.setPrefWidth(85);
        mDwellSpinner.setPrefWidth(85);
        mClipSpinner.setPrefWidth(65);
        mCooldownSpinner.setPrefWidth(85);

        HBox row2 = new HBox(6, new Label("SNR ≥"), mSnrSpinner, new Label("dB    Level ≥"), mLevelSpinner,
            new Label("dB    Dwell"), mDwellSpinner, new Label("ms    Clip"), mClipSpinner,
            new Label("s    Re-capture after"), mCooldownSpinner, new Label("s"), mIgnoreDcCheck);
        row2.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(mIgnoreDcCheck, new Insets(0, 0, 0, 12));

        //Row 3: global options and actions
        CheckBox autoAnalyze = new CheckBox("Auto-analyze clips");
        autoAnalyze.setSelected(mManager.getPreference().isAutoAnalyze());
        autoAnalyze.setOnAction(e -> mManager.getPreference().setAutoAnalyze(autoAnalyze.isSelected()));
        CheckBox includeP25 = new CheckBox("Include P25");
        includeP25.setSelected(mManager.getPreference().isIncludeP25());
        includeP25.setOnAction(e -> mManager.getPreference().setIncludeP25(includeP25.isSelected()));
        Spinner<Integer> retention = new Spinner<>(0, 365, mManager.getPreference().getRetentionDays(), 1);
        retention.setEditable(true);
        retention.setPrefWidth(65);
        retention.valueProperty().addListener((o, ov, nv) -> {
            if(nv != null)
            {
                mManager.getPreference().setRetentionDays(nv);
            }
        });

        mMinHitsSpinner.setEditable(true);
        mMinHitsSpinner.setPrefWidth(65);
        mMinHitsSpinner.setTooltip(new Tooltip("View filter: only show signals heard at least this many times"));
        mMinHitsSpinner.valueProperty().addListener((o, ov, nv) -> applyViewFilter());
        mHideBelowLevelCheck.setTooltip(new Tooltip("View filter: hide rows whose peak level is below the Level threshold"));
        mHideBelowLevelCheck.setOnAction(e -> applyViewFilter());
        mHideIgnoredCheck.setSelected(true);
        mHideIgnoredCheck.setOnAction(e -> applyViewFilter());
        mLevelSpinner.valueProperty().addListener((o, ov, nv) -> applyViewFilter());

        Button analyze = new Button("Analyze");
        analyze.setTooltip(new Tooltip("Run offline identification on the selected clip(s)"));
        analyze.setOnAction(e -> forSelected(mManager::analyze));
        Button ignore = new Button("Ignore");
        ignore.setTooltip(new Tooltip("Never report this frequency again"));
        ignore.setOnAction(e -> forSelected(mManager::ignore));
        Button unignore = new Button("Un-ignore");
        unignore.setOnAction(e -> forSelected(mManager::unignore));
        Button delete = new Button("Delete");
        delete.setOnAction(e -> forSelected(mManager::delete));
        Button clear = new Button("Clear All");
        clear.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete all discovered signals and clips?",
                ButtonType.YES, ButtonType.NO);
            alert.setTitle("Clear Discovery");
            alert.setHeaderText(null);

            if(getScene() != null)
            {
                alert.initOwner(getScene().getWindow());
            }

            alert.showAndWait().filter(b -> b == ButtonType.YES).ifPresent(b -> mManager.clearAll());
        });
        Button export = new Button("Export CSV");
        export.setOnAction(e -> exportCsv());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label minHits = new Label("Min hits");
        HBox.setMargin(minHits, new Insets(0, 0, 0, 12));
        HBox row3 = new HBox(6, autoAnalyze, includeP25, new Label("Keep clips"), retention,
            new Label("days (0 = forever)"), minHits, mMinHitsSpinner, mHideBelowLevelCheck, mHideIgnoredCheck,
            spacer, analyze, ignore, unignore, delete, clear, export);
        row3.setAlignment(Pos.CENTER_LEFT);

        createTable();
        VBox.setVgrow(mTable, Priority.ALWAYS);
        mContent.getChildren().addAll(row1, row2, row3, mTable);

        refreshTuners();
        loadSettings();
        applyViewFilter();
        updateToggle();
    }

    private void createTable()
    {
        SortedList<DiscoveryHit> sorted = new SortedList<>(mFilteredHits);
        sorted.comparatorProperty().bind(mTable.comparatorProperty());
        mTable.setItems(sorted);
        mTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        mTable.setPlaceholder(new Label(""));

        TableColumn<DiscoveryHit,String> active = column("", hit -> hit.isActive() ? "●" : "");
        active.setMaxWidth(40);
        active.setPrefWidth(30);
        TableColumn<DiscoveryHit,Double> bin = mhzColumn("Bin (MHz)", hit -> hit.getBinFrequency() / 1E6d);
        TableColumn<DiscoveryHit,Double> carrier = mhzColumn("Carrier (MHz)", hit -> hit.getCarrierFrequency() / 1E6d);
        TableColumn<DiscoveryHit,String> tuner = column("Tuner", DiscoveryHit::getTunerId);
        TableColumn<DiscoveryHit,Date> first = dateColumn("First Heard", hit -> new Date(hit.getFirstHeard()));
        TableColumn<DiscoveryHit,Date> last = dateColumn("Last Heard", hit -> new Date(hit.getLastHeard()));
        TableColumn<DiscoveryHit,Integer> hits = column("Hits", DiscoveryHit::getHitCount);
        TableColumn<DiscoveryHit,Integer> activeSeconds = column("Active (s)",
            hit -> (int)(hit.getActiveMs(System.currentTimeMillis()) / 1000));
        TableColumn<DiscoveryHit,Double> peakLevel = column("Peak dB",
            hit -> Math.round(hit.getPeakLevelDb() * 10.0) / 10.0);
        TableColumn<DiscoveryHit,Double> peakSnr = column("Peak SNR",
            hit -> Math.round(hit.getPeakSnrDb() * 10.0) / 10.0);
        TableColumn<DiscoveryHit,String> protocol = column("Protocol", DiscoveryHit::getProtocol);
        TableColumn<DiscoveryHit,Integer> messages = column("Msgs", DiscoveryHit::getMessageCount);
        messages.setMaxWidth(60);
        TableColumn<DiscoveryHit,String> details = column("Details", DiscoveryHit::getDetails);
        details.setPrefWidth(320);
        TableColumn<DiscoveryHit,String> status = column("Status", hit -> hit.getStatus().toString());

        mTable.getColumns().addAll(List.of(active, bin, carrier, tuner, first, last, hits, activeSeconds, peakLevel,
            peakSnr, protocol, messages, details, status));

        //Default sort: peak level, strongest first
        peakLevel.setSortType(TableColumn.SortType.DESCENDING);
        mTable.getSortOrder().add(peakLevel);

        MenuItem copyFrequency = new MenuItem("Copy Frequency");
        copyFrequency.setOnAction(e -> {
            DiscoveryHit hit = mTable.getSelectionModel().getSelectedItem();

            if(hit != null)
            {
                copy(MHZ.format(hit.getCarrierFrequency() / 1E6d));
            }
        });
        MenuItem copyClip = new MenuItem("Copy Clip Path");
        copyClip.setOnAction(e -> {
            DiscoveryHit hit = mTable.getSelectionModel().getSelectedItem();

            if(hit != null && hit.hasClip())
            {
                copy(hit.getClipPath());
            }
        });
        MenuItem analyzeItem = new MenuItem("Analyze");
        analyzeItem.setOnAction(e -> forSelected(mManager::analyze));
        mTable.setContextMenu(new ContextMenu(copyFrequency, copyClip, analyzeItem));
    }

    private static void copy(String text)
    {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static <V> TableColumn<DiscoveryHit,V> column(String title, Function<DiscoveryHit,V> value)
    {
        TableColumn<DiscoveryHit,V> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        return column;
    }

    private static TableColumn<DiscoveryHit,Double> mhzColumn(String title, Function<DiscoveryHit,Double> value)
    {
        TableColumn<DiscoveryHit,Double> column = column(title, value);
        column.setPrefWidth(100);
        column.setCellFactory(c -> new TableCell<>()
        {
            @Override
            protected void updateItem(Double item, boolean empty)
            {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : MHZ.format(item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        return column;
    }

    private static TableColumn<DiscoveryHit,Date> dateColumn(String title, Function<DiscoveryHit,Date> value)
    {
        TableColumn<DiscoveryHit,Date> column = column(title, value);
        column.setCellFactory(c -> new TableCell<>()
        {
            @Override
            protected void updateItem(Date item, boolean empty)
            {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TIME.format(item));
            }
        });
        return column;
    }

    /**
     * Applies the view-only filters (min hits, hide below level, hide ignored) to the table.  Never changes the
     * underlying hit data.
     */
    private void applyViewFilter()
    {
        final int minHits = mMinHitsSpinner.getValue() != null ? mMinHitsSpinner.getValue() : 1;
        final boolean hideBelowLevel = mHideBelowLevelCheck.isSelected();
        final double level = mLevelSpinner.getValue() != null ? mLevelSpinner.getValue() : -200.0;
        final boolean hideIgnored = mHideIgnoredCheck.isSelected();

        mFilteredHits.setPredicate(hit -> {
            if(hit == null)
            {
                return false;
            }

            if(hideIgnored && hit.getStatus() == DiscoveryHit.Status.IGNORED)
            {
                return false;
            }

            if(hit.getHitCount() < minHits)
            {
                return false;
            }

            return !(hideBelowLevel && hit.getPeakLevelDb() < level);
        });
    }

    /**
     * Refreshes the table and restores the previous row selection (by hit key) so that a live-updating table
     * doesn't steal the user's selection.
     */
    private void refreshTablePreservingSelection()
    {
        Set<String> selectedKeys = new HashSet<>();

        for(DiscoveryHit hit: mTable.getSelectionModel().getSelectedItems())
        {
            if(hit != null)
            {
                selectedKeys.add(hit.getKey());
            }
        }

        mHits.setAll(mManager.getHits());
        mTable.refresh();

        if(!selectedKeys.isEmpty())
        {
            mTable.getSelectionModel().clearSelection();

            for(int row = 0; row < mTable.getItems().size(); row++)
            {
                DiscoveryHit hit = mTable.getItems().get(row);

                if(hit != null && selectedKeys.contains(hit.getKey()))
                {
                    mTable.getSelectionModel().select(row);
                }
            }
        }
    }

    private void refreshTuners()
    {
        TunerItem selected = mTunerCombo.getValue();
        List<TunerItem> items = new ArrayList<>();

        for(DiscoveredTuner tuner : mManager.getDiscoveryCapableTuners())
        {
            items.add(new TunerItem(tuner));
        }

        mTunerCombo.getItems().setAll(items);

        TunerItem toSelect = items.isEmpty() ? null : items.getFirst();

        if(selected != null)
        {
            for(TunerItem item : items)
            {
                if(item.getId().equals(selected.getId()))
                {
                    toSelect = item;
                    break;
                }
            }
        }

        mLoadingSettings = true;
        mTunerCombo.setValue(toSelect);
        mLoadingSettings = false;
    }

    private void loadSettings()
    {
        TunerItem tuner = mTunerCombo.getValue();

        if(tuner == null)
        {
            return;
        }

        mLoadingSettings = true;

        try
        {
            DiscoverySettings settings = mManager.getSettings(tuner.getId());
            mSnrSpinner.getValueFactory().setValue(settings.getSnrThresholdDb());
            mLevelSpinner.getValueFactory().setValue(settings.getLevelThresholdDb());
            mDwellSpinner.getValueFactory().setValue(settings.getDwellMs());
            mClipSpinner.getValueFactory().setValue(settings.getClipSeconds());
            mCooldownSpinner.getValueFactory().setValue(settings.getCaptureCooldownSeconds());
            mIgnoreDcCheck.setSelected(settings.isIgnoreDcBin());
        }
        finally
        {
            mLoadingSettings = false;
        }
    }

    private void applySettings()
    {
        if(mLoadingSettings)
        {
            return;
        }

        TunerItem tuner = mTunerCombo.getValue();

        if(tuner == null)
        {
            return;
        }

        DiscoverySettings settings = mManager.getSettings(tuner.getId());
        settings.setSnrThresholdDb(mSnrSpinner.getValue());
        settings.setLevelThresholdDb(mLevelSpinner.getValue());
        settings.setDwellMs(mDwellSpinner.getValue());
        settings.setClipSeconds(mClipSpinner.getValue());
        settings.setCaptureCooldownSeconds(mCooldownSpinner.getValue());
        settings.setIgnoreDcBin(mIgnoreDcCheck.isSelected());
        mManager.saveSettings(tuner.getId(), settings);
    }

    private void toggleMonitor()
    {
        TunerItem tuner = mTunerCombo.getValue();

        if(tuner == null)
        {
            mMonitorToggle.setSelected(false);
            return;
        }

        if(mManager.isMonitoring(tuner.getId()))
        {
            mManager.stop(tuner.getId());
        }
        else
        {
            applySettings();

            if(!mManager.start(tuner.getTuner()))
            {
                Alert alert = new Alert(Alert.AlertType.WARNING, "Couldn't start discovery on this tuner", ButtonType.OK);
                alert.setTitle("Discovery");
                alert.setHeaderText(null);

                if(getScene() != null)
                {
                    alert.initOwner(getScene().getWindow());
                }

                alert.showAndWait();
            }
        }

        updateToggle();
    }

    private void updateToggle()
    {
        TunerItem tuner = mTunerCombo.getValue();
        boolean monitoring = tuner != null && mManager.isMonitoring(tuner.getId());
        mMonitorToggle.setSelected(monitoring);
        mMonitorToggle.setText(monitoring ? "Stop Discovery" : "Start Discovery");
        mMonitorToggle.setDisable(tuner == null);
        updateStatus();
    }

    private void updateStatus()
    {
        TunerItem tuner = mTunerCombo.getValue();

        if(tuner == null)
        {
            mStatusLabel.setText("No tuners available yet - if a tuner is started, click Refresh Tuners");
            return;
        }

        DiscoveryMonitor monitor = mManager.getMonitor(tuner.getId());

        if(monitor != null && monitor.isRunning())
        {
            int active = 0;

            for(DiscoveryHit hit : mManager.getHits())
            {
                if(hit.isActive() && hit.getTunerId().equals(tuner.getId()))
                {
                    active++;
                }
            }

            mStatusLabel.setText(String.format("Monitoring %d bins   Noise floor %.1f dB   Active signals: %d",
                monitor.getBinCount(), monitor.getFloorDb(), active));
        }
        else
        {
            mStatusLabel.setText("Not monitoring");
        }
    }

    private void forSelected(Consumer<DiscoveryHit> action)
    {
        List<DiscoveryHit> hits = new ArrayList<>(mTable.getSelectionModel().getSelectedItems());

        for(DiscoveryHit hit : hits)
        {
            if(hit != null)
            {
                action.accept(hit);
            }
        }
    }

    private void exportCsv()
    {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("discovery_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".csv");
        File file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);

        if(file != null)
        {
            try
            {
                mManager.exportCsv(file.toPath());
            }
            catch(IOException e)
            {
                mLog.error("Error exporting discovery CSV", e);
                Alert alert = new Alert(Alert.AlertType.ERROR, "Export failed: " + e.getMessage(), ButtonType.OK);
                alert.setTitle("Discovery");
                alert.setHeaderText(null);
                alert.showAndWait();
            }
        }
    }

    /**
     * Combo box wrapper for a discovered tuner.
     */
    private static class TunerItem
    {
        private final DiscoveredTuner mTuner;

        TunerItem(DiscoveredTuner tuner)
        {
            mTuner = tuner;
        }

        String getId()
        {
            return mTuner.getId();
        }

        DiscoveredTuner getTuner()
        {
            return mTuner;
        }

        @Override
        public String toString()
        {
            String name = mTuner.hasTuner() ? mTuner.getTuner().getPreferredName() : mTuner.getId();

            if(mTuner.hasTuner())
            {
                double center = mTuner.getTuner().getTunerController().getFrequency() / 1E6d;
                double span = mTuner.getTuner().getTunerController().getSampleRate() / 1E6d;
                return String.format("%s  [%.3f MHz ± %.1f MHz]", name, center, span / 2.0);
            }

            return name;
        }
    }
}
