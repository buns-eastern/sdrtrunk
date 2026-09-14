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
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Signal discovery tab: per-tuner enable, detection thresholds, and the list of discovered signals with offline
 * identification results.  Read-only with respect to the playlist.
 */
public class DiscoveryPanel extends JPanel
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryPanel.class);
    private static final DecimalFormat MHZ = new DecimalFormat("0.000000");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM/dd HH:mm:ss");

    private final DiscoveryManager mManager;
    private final HitTableModel mTableModel = new HitTableModel();
    private JTable mTable;
    private JComboBox<TunerItem> mTunerCombo;
    private JToggleButton mMonitorToggle;
    private JLabel mStatusLabel;
    private JSpinner mSnrSpinner;
    private JSpinner mLevelSpinner;
    private JSpinner mDwellSpinner;
    private JSpinner mClipSpinner;
    private JSpinner mCooldownSpinner;
    private JSpinner mRetentionSpinner;
    private JCheckBox mAutoAnalyzeCheck;
    private JCheckBox mIncludeP25Check;
    private JCheckBox mIgnoreDcCheck;
    private boolean mLoadingSettings = false;
    private final Timer mRefreshTimer;
    private volatile boolean mRefreshPending = false;

    public DiscoveryPanel(DiscoveryManager manager)
    {
        mManager = manager;
        init();

        mRefreshTimer = new Timer(500, e -> {
            if(mRefreshPending)
            {
                mRefreshPending = false;
                mTableModel.refresh();
            }

            updateStatus();
        });
        mRefreshTimer.start();

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
                SwingUtilities.invokeLater(DiscoveryPanel.this::updateToggle);
            }
        });

        //Tuners may not be fully started when this panel is constructed - refresh the list a few times
        Timer tunerRefresh = new Timer(5000, e -> {
            refreshTuners();
            mManager.autoStart();
            updateToggle();
        });
        tunerRefresh.setRepeats(true);
        tunerRefresh.start();
        new Timer(60000, e -> tunerRefresh.stop()) {{ setRepeats(false); }}.start();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 4 4 4 4, fill", "[grow,fill]", "[][][][grow,fill]"));

        //Row 1: tuner selection and monitor toggle
        JPanel row1 = new JPanel(new MigLayout("insets 0", "[][][][grow][]", "[]"));
        row1.add(new JLabel("Tuner:"));
        mTunerCombo = new JComboBox<>();
        mTunerCombo.addActionListener(e -> {
            loadSettings();
            updateToggle();
        });
        row1.add(mTunerCombo, "wmin 260");
        mMonitorToggle = new JToggleButton("Start Discovery");
        mMonitorToggle.addActionListener(e -> toggleMonitor());
        row1.add(mMonitorToggle);
        mStatusLabel = new JLabel(" ");
        row1.add(mStatusLabel, "growx");
        JButton refresh = new JButton("Refresh Tuners");
        refresh.addActionListener(e -> refreshTuners());
        row1.add(refresh);
        add(row1, "wrap");

        //Row 2: detection thresholds (per tuner)
        JPanel row2 = new JPanel(new MigLayout("insets 0", "[][][][][][][][][][][]", "[]"));
        mSnrSpinner = new JSpinner(new SpinnerNumberModel(15.0, 0.0, 80.0, 1.0));
        mLevelSpinner = new JSpinner(new SpinnerNumberModel(-50.0, -150.0, 20.0, 1.0));
        mDwellSpinner = new JSpinner(new SpinnerNumberModel(200, 0, 5000, 100));
        mClipSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
        mCooldownSpinner = new JSpinner(new SpinnerNumberModel(60, 0, 3600, 10));
        mIgnoreDcCheck = new JCheckBox("Ignore DC bin", true);

        row2.add(new JLabel("SNR ≥"));
        row2.add(mSnrSpinner, "w 70");
        row2.add(new JLabel("dB    Level ≥"));
        row2.add(mLevelSpinner, "w 80");
        row2.add(new JLabel("dB    Dwell"));
        row2.add(mDwellSpinner, "w 80");
        row2.add(new JLabel("ms    Clip"));
        row2.add(mClipSpinner, "w 60");
        row2.add(new JLabel("s    Re-capture after"));
        row2.add(mCooldownSpinner, "w 80");
        row2.add(new JLabel("s"));
        row2.add(mIgnoreDcCheck, "gapleft 12");
        add(row2, "wrap");

        for(JSpinner spinner : new JSpinner[]{mSnrSpinner, mLevelSpinner, mDwellSpinner, mClipSpinner, mCooldownSpinner})
        {
            spinner.addChangeListener(e -> applySettings());
        }

        mIgnoreDcCheck.addActionListener(e -> applySettings());

        //Row 3: global options and actions
        JPanel row3 = new JPanel(new MigLayout("insets 0", "[][][][][grow][][][][][][]", "[]"));
        mAutoAnalyzeCheck = new JCheckBox("Auto-analyze clips", mManager.getPreference().isAutoAnalyze());
        mAutoAnalyzeCheck.addActionListener(e -> mManager.getPreference().setAutoAnalyze(mAutoAnalyzeCheck.isSelected()));
        mIncludeP25Check = new JCheckBox("Include P25", mManager.getPreference().isIncludeP25());
        mIncludeP25Check.addActionListener(e -> mManager.getPreference().setIncludeP25(mIncludeP25Check.isSelected()));
        mRetentionSpinner = new JSpinner(new SpinnerNumberModel(mManager.getPreference().getRetentionDays(), 0, 365, 1));
        mRetentionSpinner.addChangeListener(e -> mManager.getPreference().setRetentionDays((Integer)mRetentionSpinner.getValue()));

        row3.add(mAutoAnalyzeCheck);
        row3.add(mIncludeP25Check);
        row3.add(new JLabel("Keep clips"));
        row3.add(mRetentionSpinner, "w 60");
        row3.add(new JLabel("days (0 = forever)"), "growx");

        JButton analyze = new JButton("Analyze");
        analyze.setToolTipText("Run offline identification on the selected clip(s)");
        analyze.addActionListener(e -> forSelected(mManager::analyze));
        JButton ignore = new JButton("Ignore");
        ignore.setToolTipText("Never report this frequency again");
        ignore.addActionListener(e -> forSelected(mManager::ignore));
        JButton unignore = new JButton("Un-ignore");
        unignore.addActionListener(e -> forSelected(mManager::unignore));
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> forSelected(mManager::delete));
        JButton clear = new JButton("Clear All");
        clear.addActionListener(e -> {
            if(JOptionPane.showConfirmDialog(this, "Delete all discovered signals and clips?", "Clear Discovery",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
            {
                mManager.clearAll();
            }
        });
        JButton export = new JButton("Export CSV");
        export.addActionListener(e -> exportCsv());

        row3.add(analyze);
        row3.add(ignore);
        row3.add(unignore);
        row3.add(delete);
        row3.add(clear);
        row3.add(export);
        add(row3, "wrap");

        //Table
        mTable = new JTable(mTableModel);
        mTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        mTable.setAutoCreateRowSorter(true);
        TableRowSorter<HitTableModel> sorter = new TableRowSorter<>(mTableModel);
        mTable.setRowSorter(sorter);
        sorter.toggleSortOrder(HitTableModel.COL_PEAK_LEVEL);
        sorter.toggleSortOrder(HitTableModel.COL_PEAK_LEVEL); //descending
        mTable.getColumnModel().getColumn(HitTableModel.COL_ACTIVE).setMaxWidth(40);
        mTable.getColumnModel().getColumn(HitTableModel.COL_FREQUENCY).setPreferredWidth(90);
        mTable.getColumnModel().getColumn(HitTableModel.COL_CARRIER).setPreferredWidth(90);
        mTable.getColumnModel().getColumn(HitTableModel.COL_DETAILS).setPreferredWidth(320);

        javax.swing.table.DefaultTableCellRenderer dateRenderer = new javax.swing.table.DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                setText(value instanceof Date d ? TIME.format(d) : "");
            }
        };
        mTable.setDefaultRenderer(Date.class, dateRenderer);

        javax.swing.table.DefaultTableCellRenderer mhzRenderer = new javax.swing.table.DefaultTableCellRenderer()
        {
            @Override
            protected void setValue(Object value)
            {
                setHorizontalAlignment(RIGHT);
                setText(value instanceof Double d ? MHZ.format(d) : "");
            }
        };
        mTable.getColumnModel().getColumn(HitTableModel.COL_FREQUENCY).setCellRenderer(mhzRenderer);
        mTable.getColumnModel().getColumn(HitTableModel.COL_CARRIER).setCellRenderer(mhzRenderer);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem copyFrequency = new JMenuItem("Copy Frequency");
        copyFrequency.addActionListener(e -> {
            DiscoveryHit hit = getSelectedHit();

            if(hit != null)
            {
                String value = MHZ.format(hit.getCarrierFrequency() / 1E6d);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
            }
        });
        popup.add(copyFrequency);
        JMenuItem copyClip = new JMenuItem("Copy Clip Path");
        copyClip.addActionListener(e -> {
            DiscoveryHit hit = getSelectedHit();

            if(hit != null && hit.hasClip())
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hit.getClipPath()), null);
            }
        });
        popup.add(copyClip);
        JMenuItem analyzeItem = new JMenuItem("Analyze");
        analyzeItem.addActionListener(e -> forSelected(mManager::analyze));
        popup.add(analyzeItem);

        mTable.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if(SwingUtilities.isRightMouseButton(e))
                {
                    int row = mTable.rowAtPoint(e.getPoint());

                    if(row >= 0 && !mTable.isRowSelected(row))
                    {
                        mTable.setRowSelectionInterval(row, row);
                    }

                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        add(new JScrollPane(mTable), "grow");

        refreshTuners();
        loadSettings();
        updateToggle();
    }

    private void refreshTuners()
    {
        TunerItem selected = (TunerItem)mTunerCombo.getSelectedItem();
        List<TunerItem> items = new ArrayList<>();

        for(DiscoveredTuner tuner : mManager.getDiscoveryCapableTuners())
        {
            items.add(new TunerItem(tuner));
        }

        DefaultComboBoxModel<TunerItem> model = new DefaultComboBoxModel<>(items.toArray(new TunerItem[0]));
        mTunerCombo.setModel(model);

        if(selected != null)
        {
            for(TunerItem item : items)
            {
                if(item.getId().equals(selected.getId()))
                {
                    mTunerCombo.setSelectedItem(item);
                    break;
                }
            }
        }
    }

    private TunerItem getSelectedTuner()
    {
        return (TunerItem)mTunerCombo.getSelectedItem();
    }

    private void loadSettings()
    {
        TunerItem tuner = getSelectedTuner();

        if(tuner == null)
        {
            return;
        }

        mLoadingSettings = true;

        try
        {
            DiscoverySettings settings = mManager.getSettings(tuner.getId());
            mSnrSpinner.setValue(settings.getSnrThresholdDb());
            mLevelSpinner.setValue(settings.getLevelThresholdDb());
            mDwellSpinner.setValue(settings.getDwellMs());
            mClipSpinner.setValue(settings.getClipSeconds());
            mCooldownSpinner.setValue(settings.getCaptureCooldownSeconds());
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

        TunerItem tuner = getSelectedTuner();

        if(tuner == null)
        {
            return;
        }

        DiscoverySettings settings = mManager.getSettings(tuner.getId());
        settings.setSnrThresholdDb((Double)mSnrSpinner.getValue());
        settings.setLevelThresholdDb((Double)mLevelSpinner.getValue());
        settings.setDwellMs((Integer)mDwellSpinner.getValue());
        settings.setClipSeconds((Integer)mClipSpinner.getValue());
        settings.setCaptureCooldownSeconds((Integer)mCooldownSpinner.getValue());
        settings.setIgnoreDcBin(mIgnoreDcCheck.isSelected());
        mManager.saveSettings(tuner.getId(), settings);
    }

    private void toggleMonitor()
    {
        TunerItem tuner = getSelectedTuner();

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
                JOptionPane.showMessageDialog(this, "Couldn't start discovery on this tuner", "Discovery",
                    JOptionPane.WARNING_MESSAGE);
            }
        }

        updateToggle();
    }

    private void updateToggle()
    {
        TunerItem tuner = getSelectedTuner();
        boolean monitoring = tuner != null && mManager.isMonitoring(tuner.getId());
        mMonitorToggle.setSelected(monitoring);
        mMonitorToggle.setText(monitoring ? "Stop Discovery" : "Start Discovery");
        mMonitorToggle.setEnabled(tuner != null);
        updateStatus();
    }

    private void updateStatus()
    {
        TunerItem tuner = getSelectedTuner();

        if(tuner == null)
        {
            mStatusLabel.setText("No discovery-capable tuners");
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

    private DiscoveryHit getSelectedHit()
    {
        int row = mTable.getSelectedRow();

        if(row < 0)
        {
            return null;
        }

        return mTableModel.getHit(mTable.convertRowIndexToModel(row));
    }

    private void forSelected(java.util.function.Consumer<DiscoveryHit> action)
    {
        int[] rows = mTable.getSelectedRows();
        List<DiscoveryHit> hits = new ArrayList<>();

        for(int row : rows)
        {
            DiscoveryHit hit = mTableModel.getHit(mTable.convertRowIndexToModel(row));

            if(hit != null)
            {
                hits.add(hit);
            }
        }

        for(DiscoveryHit hit : hits)
        {
            action.accept(hit);
        }
    }

    private void exportCsv()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("discovery_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".csv"));

        if(chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
        {
            try
            {
                mManager.exportCsv(chooser.getSelectedFile().toPath());
            }
            catch(IOException e)
            {
                mLog.error("Error exporting discovery CSV", e);
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Discovery", JOptionPane.ERROR_MESSAGE);
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

    /**
     * Table model over the manager's hit list.
     */
    private class HitTableModel extends AbstractTableModel
    {
        static final int COL_ACTIVE = 0;
        static final int COL_FREQUENCY = 1;
        static final int COL_CARRIER = 2;
        static final int COL_TUNER = 3;
        static final int COL_FIRST = 4;
        static final int COL_LAST = 5;
        static final int COL_HITS = 6;
        static final int COL_ACTIVE_SECONDS = 7;
        static final int COL_PEAK_LEVEL = 8;
        static final int COL_PEAK_SNR = 9;
        static final int COL_PROTOCOL = 10;
        static final int COL_DETAILS = 11;
        static final int COL_STATUS = 12;
        private final String[] mColumns = {"", "Bin (MHz)", "Carrier (MHz)", "Tuner", "First Heard", "Last Heard",
            "Hits", "Active (s)", "Peak dB", "Peak SNR", "Protocol", "Details", "Status"};
        private List<DiscoveryHit> mRows = new ArrayList<>();

        void refresh()
        {
            mRows = mManager.getHits();
            fireTableDataChanged();
        }

        DiscoveryHit getHit(int row)
        {
            return row >= 0 && row < mRows.size() ? mRows.get(row) : null;
        }

        @Override
        public int getRowCount()
        {
            return mRows.size();
        }

        @Override
        public int getColumnCount()
        {
            return mColumns.length;
        }

        @Override
        public String getColumnName(int column)
        {
            return mColumns[column];
        }

        @Override
        public Class<?> getColumnClass(int column)
        {
            return switch(column)
            {
                case COL_HITS, COL_ACTIVE_SECONDS -> Integer.class;
                case COL_PEAK_LEVEL, COL_PEAK_SNR -> Double.class;
                case COL_FIRST, COL_LAST -> Date.class;
                case COL_FREQUENCY, COL_CARRIER -> Double.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column)
        {
            DiscoveryHit hit = getHit(row);

            if(hit == null)
            {
                return null;
            }

            return switch(column)
            {
                case COL_ACTIVE -> hit.isActive() ? "●" : "";
                case COL_FREQUENCY -> hit.getBinFrequency() / 1E6d;
                case COL_CARRIER -> hit.getCarrierFrequency() / 1E6d;
                case COL_TUNER -> hit.getTunerId();
                case COL_FIRST -> new Date(hit.getFirstHeard());
                case COL_LAST -> new Date(hit.getLastHeard());
                case COL_HITS -> hit.getHitCount();
                case COL_ACTIVE_SECONDS -> (int)(hit.getTotalActiveMs() / 1000);
                case COL_PEAK_LEVEL -> Math.round(hit.getPeakLevelDb() * 10.0) / 10.0;
                case COL_PEAK_SNR -> Math.round(hit.getPeakSnrDb() * 10.0) / 10.0;
                case COL_PROTOCOL -> hit.getProtocol();
                case COL_DETAILS -> hit.getDetails();
                case COL_STATUS -> hit.getStatus().toString();
                default -> "";
            };
        }
    }

}
