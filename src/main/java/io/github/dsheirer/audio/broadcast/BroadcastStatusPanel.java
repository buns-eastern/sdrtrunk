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
package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import net.miginfocom.swing.MigLayout;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.RowFilter;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Table of broadcast streams and statuses.
 */
public class BroadcastStatusPanel extends JPanel
{
    private JTable mTable;
    private JTableColumnWidthMonitor mColumnWidthMonitor;
    private JScrollPane mScrollPane;
    private BroadcastModel mBroadcastModel;
    private UserPreferences mUserPreferences;
    private String mPreferenceKey;
    private TableRowSorter<BroadcastModel> mSorter;
    private boolean mShowOnlyProblems;

    /**
     * Constructs an instance
     * @param broadcastModel to access the streams
     * @param userPreferences for configuring the panel
     * @param preferenceKey to store column preferences for this panel.
     */
    public BroadcastStatusPanel(BroadcastModel broadcastModel, UserPreferences userPreferences, String preferenceKey)
    {
        mBroadcastModel = broadcastModel;
        mUserPreferences = userPreferences;
        mPreferenceKey = preferenceKey;

        init();
    }

    public JTable getTable()
    {
        return mTable;
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0 ", "[grow,fill]", "[grow,fill]"));

        mTable = new JTable(mBroadcastModel)
        {
            @Override
            protected void paintComponent(java.awt.Graphics g)
            {
                super.paintComponent(g);
                paintFilterPlaceholder(g, this);
            }
        };
        //Fill the viewport so the right-click menu is reachable (and the placeholder paints) when the filter
        //hides every row.
        mTable.setFillsViewportHeight(true);

        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer)mTable.getDefaultRenderer(String.class);
        renderer.setHorizontalAlignment(SwingConstants.CENTER);

        mTable.getColumnModel().getColumn(BroadcastModel.COLUMN_BROADCASTER_STATUS).setCellRenderer(new StatusCellRenderer());
        mTable.getColumnModel().getColumn(BroadcastModel.COLUMN_BROADCAST_SERVER_TYPE).setCellRenderer(new ServerTypeRenderer());
        mColumnWidthMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, mPreferenceKey);

        //Optional filter: hide streams that are Connected or Disabled so only streams with a problem show.
        mShowOnlyProblems = mUserPreferences.getSwingPreference().getInt(mPreferenceKey + ".problems.only", 0) == 1;
        mSorter = new TableRowSorter<>(mBroadcastModel);
        mSorter.setSortsOnUpdates(true);

        for(int column = 0; column < mBroadcastModel.getColumnCount(); column++)
        {
            mSorter.setSortable(column, false);
        }

        mSorter.setRowFilter(createStatusFilter());
        mTable.setRowSorter(mSorter);

        mTable.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                showPopup(e);
            }

            private void showPopup(MouseEvent e)
            {
                if(e.isPopupTrigger())
                {
                    JPopupMenu popup = new JPopupMenu();
                    JCheckBoxMenuItem item = new JCheckBoxMenuItem("Show only streams with problems", mShowOnlyProblems);
                    item.addActionListener(a -> setShowOnlyProblems(item.isSelected()));
                    popup.add(item);
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        mScrollPane = new JScrollPane(mTable);

        add(mScrollPane);
    }

    /**
     * When the problems-only filter is on and no streams are showing, paints a centered reminder so an empty
     * box does not look like streaming has stopped. Theme-aware via the table foreground.
     */
    private void paintFilterPlaceholder(java.awt.Graphics g, JTable table)
    {
        if(!mShowOnlyProblems || table.getRowCount() > 0)
        {
            return;
        }

        Graphics2D g2 = (Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int hidden = mBroadcastModel.getRowCount();
        String line1 = "Stream filter active";
        String line2 = "Connected and Disabled streams are hidden (" + hidden + " not shown)";
        String line3 = "Any stream with a problem will appear here";

        java.awt.Font base = table.getFont();
        java.awt.Font f1 = base.deriveFont(java.awt.Font.BOLD, base.getSize2D() + 3f);
        java.awt.Font f2 = base.deriveFont(java.awt.Font.PLAIN, base.getSize2D());

        Color fg = table.getForeground();
        Color c1 = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 180);
        Color c2 = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 135);
        Color c3 = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 110);

        int w = table.getWidth();
        int h = table.getHeight();

        g2.setFont(f1);
        FontMetrics fm1 = g2.getFontMetrics();
        g2.setFont(f2);
        FontMetrics fm2 = g2.getFontMetrics();

        int top = (h - (fm1.getHeight() + fm2.getHeight() * 2)) / 2;

        g2.setFont(f1);
        g2.setColor(c1);
        g2.drawString(line1, (w - fm1.stringWidth(line1)) / 2, top + fm1.getAscent());

        g2.setFont(f2);
        g2.setColor(c2);
        g2.drawString(line2, (w - fm2.stringWidth(line2)) / 2, top + fm1.getHeight() + fm2.getAscent());

        g2.setColor(c3);
        g2.drawString(line3, (w - fm2.stringWidth(line3)) / 2, top + fm1.getHeight() + fm2.getHeight() + fm2.getAscent());

        g2.dispose();
    }

    /**
     * Row filter that, when enabled, hides Connected and Disabled streams so only streams with an active
     * problem (errors, disconnected, connecting, etc.) remain visible.
     */
    private RowFilter<BroadcastModel, Integer> createStatusFilter()
    {
        return new RowFilter<BroadcastModel, Integer>()
        {
            @Override
            public boolean include(Entry<? extends BroadcastModel, ? extends Integer> entry)
            {
                if(!mShowOnlyProblems)
                {
                    return true;
                }

                Object value = entry.getValue(BroadcastModel.COLUMN_BROADCASTER_STATUS);

                if(value instanceof BroadcastState)
                {
                    BroadcastState state = (BroadcastState)value;
                    return state != BroadcastState.CONNECTED && state != BroadcastState.DISABLED;
                }

                return true;
            }
        };
    }

    /**
     * Enables or disables the problems-only filter and persists the choice.
     */
    private void setShowOnlyProblems(boolean enabled)
    {
        mShowOnlyProblems = enabled;
        mUserPreferences.getSwingPreference().setInt(mPreferenceKey + ".problems.only", enabled ? 1 : 0);
        mSorter.setRowFilter(createStatusFilter());
        mTable.repaint();
    }

    public class ServerTypeRenderer extends DefaultTableCellRenderer
    {
        public ServerTypeRenderer()
        {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel component = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof BroadcastServerType broadcastServerType)
            {
                component.setText(broadcastServerType.toString());
                Icon icon = new Icon("empty", broadcastServerType.getIconPath());
                ImageIcon imageIcon = icon.getIcon();
                ImageIcon scaledIcon = IconModel.getScaledIcon(imageIcon, 13);
                component.setIcon(scaledIcon);
            }
            else
            {
                component.setText(null);
                component.setIcon(null);
            }

            return component;
        }
    }

    /**
     * Custom cell renderer for the broadcast state column.
     */
    public class StatusCellRenderer extends DefaultTableCellRenderer
    {
        private static final String[] PILL_REFERENCE_LABELS =
            {"Connected", "Connecting", "Disconnected", "Disabled", "No Server", "Ready", "Error"};
        private Color mCellBackground = Color.BLACK;
        private Color mPillColor = null;

        public StatusCellRenderer()
        {
            setOpaque(false);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column)
        {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            mCellBackground = isSelected ? table.getSelectionBackground() : table.getBackground();
            mPillColor = null;
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());

            if(!isSelected && value instanceof BroadcastState)
            {
                BroadcastState state = (BroadcastState)value;

                if(state == BroadcastState.CONNECTED)
                {
                    mPillColor = new Color(46, 125, 50);
                    setForeground(Color.WHITE);
                }
                else if(state == BroadcastState.INVALID_SETTINGS || state == BroadcastState.NETWORK_UNAVAILABLE)
                {
                    mPillColor = new Color(240, 190, 40);
                    setForeground(Color.BLACK);
                }
                else if(state.isErrorState())
                {
                    mPillColor = new Color(190, 55, 55);
                    setForeground(Color.WHITE);
                }
                else if(state == BroadcastState.DISABLED)
                {
                    setForeground(Color.GRAY);
                }
            }

            return this;
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(mCellBackground);
            g2.fillRect(0, 0, getWidth(), getHeight());

            if(mPillColor != null)
            {
                FontMetrics fm = g2.getFontMetrics(getFont());

                //Uniform pill width sized to the common short statuses; a rare long status expands to fit.
                int uniform = 0;
                for(String reference : PILL_REFERENCE_LABELS)
                {
                    uniform = Math.max(uniform, fm.stringWidth(reference));
                }

                int pillWidth = Math.min(Math.max(uniform, fm.stringWidth(getText())) + 20, getWidth() - 4);
                int pillHeight = Math.min(fm.getHeight() + 5, getHeight() - 4);
                int x = (getWidth() - pillWidth) / 2;
                int y = (getHeight() - pillHeight) / 2;
                g2.setColor(mPillColor);
                g2.fillRoundRect(x, y, pillWidth, pillHeight, 9, 9);
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }
}
