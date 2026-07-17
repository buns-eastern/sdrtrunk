/*
 * ******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2019 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.preference.swing;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumnModel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors a JTable column model and persists column width changes to the user preferences.  Restores
 * previous column width preferred sizes on application restart.
 */
public class JTableColumnWidthMonitor
{
    private static final Logger mLog = LoggerFactory.getLogger(JTableColumnWidthMonitor.class);
    private UserPreferences mUserPreferences;
    private JTable mTable;
    private String mKey;
    private ColumnResizeListener mColumnResizeListener = new ColumnResizeListener();
    private AtomicBoolean mSaveInProgress = new AtomicBoolean();
    private AtomicBoolean mRestoring = new AtomicBoolean();

    /**
     * Constructs a column width monitor.
     *
     * @param userPreferences to store column widths
     * @param table to monitor for column width changes
     * @param key that uniquely identifies the table to monitor
     */
    public JTableColumnWidthMonitor(UserPreferences userPreferences, JTable table, String key)
    {
        mUserPreferences = userPreferences;
        mTable = table;
        mKey = key;

        mTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);

        // Wait until the UI is realized to restore column order and preferred widths
        EventQueue.invokeLater(this::restore);

        // Keep listening for drag-resizes so you can re-save new widths
        mTable.getColumnModel().addColumnModelListener(mColumnResizeListener);
    }

    /**
     * Prepares this monitor for disposal by unregistering as a listener to the table column model.
     */
    public void dispose()
    {
        if(mTable != null && mColumnResizeListener != null)
        {
            mTable.getColumnModel().removeColumnModelListener(mColumnResizeListener);
        }

        mTable = null;
        mUserPreferences = null;
    }

    /**
     * Restores column order first, then preferred widths.  Guards against the restore itself re-triggering
     * a save, and never throws - a bad/mismatched stored layout just falls back to defaults.
     */
    private void restore()
    {
        mRestoring.set(true);

        try
        {
            restoreColumnOrder();
            restoreColumnWidths();
        }
        catch(Exception e)
        {
            mLog.error("Error restoring table column order/widths for [" + mKey + "]", e);
        }
        finally
        {
            mRestoring.set(false);
        }
    }

    /**
     * Restores the persisted column order.  Skips restoration (leaving the default order) if no complete,
     * valid ordering is stored - e.g. first run, or the table's column set changed between versions.
     */
    private void restoreColumnOrder()
    {
        TableColumnModel model = mTable.getColumnModel();
        int count = model.getColumnCount();
        int[] desired = new int[count];
        boolean[] seen = new boolean[count];

        for(int position = 0; position < count; position++)
        {
            int modelIndex = mUserPreferences.getSwingPreference().getInt(getOrderKey(position), Integer.MAX_VALUE);

            //Missing, out of range, or duplicate -> stored order is invalid/incomplete, keep defaults
            if(modelIndex == Integer.MAX_VALUE || modelIndex < 0 || modelIndex >= count || seen[modelIndex])
            {
                return;
            }

            desired[position] = modelIndex;
            seen[modelIndex] = true;
        }

        for(int position = 0; position < count; position++)
        {
            int currentView = viewIndexForModelIndex(model, desired[position]);

            if(currentView >= 0 && currentView != position)
            {
                model.moveColumn(currentView, position);
            }
        }
    }

    /**
     * Sets the preferred column widths on the table from persisted settings
     */
    private void restoreColumnWidths()
    {
        TableColumnModel model = mTable.getColumnModel();

        for(int x = 0; x < model.getColumnCount(); x++)
        {
            int width = mUserPreferences.getSwingPreference().getInt(getColumnKey(x), Integer.MAX_VALUE);

            if(width != Integer.MAX_VALUE)
            {
                model.getColumn(x).setPreferredWidth(width);
            }
        }
    }

    /**
     * Stores the current column widths to the user preferences
     */
    private void storeColumnWidths()
    {
        TableColumnModel model = mTable.getColumnModel();

        for(int x = 0; x < model.getColumnCount(); x++)
        {
            mUserPreferences.getSwingPreference().setInt(getColumnKey(x), model.getColumn(x).getWidth());
        }
    }

    /**
     * Stores the current column order as the model index displayed at each view position.
     */
    private void storeColumnOrder()
    {
        TableColumnModel model = mTable.getColumnModel();

        for(int position = 0; position < model.getColumnCount(); position++)
        {
            mUserPreferences.getSwingPreference().setInt(getOrderKey(position), model.getColumn(position).getModelIndex());
        }
    }

    /**
     * Finds the current view position of the column with the specified model index, or -1 if not found.
     */
    private int viewIndexForModelIndex(TableColumnModel model, int modelIndex)
    {
        for(int view = 0; view < model.getColumnCount(); view++)
        {
            if(model.getColumn(view).getModelIndex() == modelIndex)
            {
                return view;
            }
        }

        return -1;
    }

    /**
     * Schedules a single debounced save of column widths and order, unless a restore is in progress.
     */
    private void scheduleSave()
    {
        if(mRestoring.get())
        {
            return;
        }

        if(mSaveInProgress.compareAndSet(false, true))
        {
            ThreadPool.SCHEDULED.schedule(new ColumnWidthSaveTask(), 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Constructs a preference key for the column number
     */
    private String getColumnKey(int column)
    {
        return mKey + ".column." + column;
    }

    /**
     * Constructs a preference key for the column order at the specified view position.
     */
    private String getOrderKey(int position)
    {
        return mKey + ".order." + position;
    }

    /**
     * Table column model listener.
     */
    class ColumnResizeListener implements TableColumnModelListener
    {
        @Override
        public void columnMarginChanged(ChangeEvent e)
        {
            scheduleSave();
        }

        @Override
        public void columnAdded(TableColumnModelEvent e){}
        @Override
        public void columnRemoved(TableColumnModelEvent e){}
        @Override
        public void columnMoved(TableColumnModelEvent e)
        {
            //A column was dragged to a new position - persist the new order (ignore no-op moves)
            if(e.getFromIndex() != e.getToIndex())
            {
                scheduleSave();
            }
        }
        @Override
        public void columnSelectionChanged(ListSelectionEvent e){}
    }

    public class ColumnWidthSaveTask implements Runnable
    {

        @Override
        public void run()
        {
            storeColumnWidths();
            storeColumnOrder();
            mSaveInProgress.set(false);
        }
    }
}
