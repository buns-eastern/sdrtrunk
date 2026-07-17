/*
 * *****************************************************************************
 * sdrtrunk
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

package io.github.dsheirer.gui.theme;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatArcDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatNordIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import io.github.dsheirer.preference.UserPreferences;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the application look and feel (FlatLaf themes) and the base UI font (family and size), persisting the
 * user's choices to preferences and applying them at startup or live from the View menu.
 *
 * Note: the custom-painted spectrum/symbol displays draw their own colors via the SettingsManager and are
 * unaffected by these themes.
 */
public class ThemeManager
{
    private static final Logger LOG = LoggerFactory.getLogger(ThemeManager.class);

    public static final String THEME_KEY = "application.appearance.theme";
    public static final String FONT_FAMILY_KEY = "application.appearance.font.family";
    public static final String FONT_SIZE_KEY = "application.appearance.font.size";

    public static final String THEME_ARC_DARK = "arc-dark";
    public static final String THEME_ONE_DARK = "one-dark";
    public static final String THEME_DRACULA = "dracula";
    public static final String THEME_NORD = "nord";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DEFAULT = THEME_ARC_DARK;

    public static final String FONT_DEFAULT = "default";

    //Theme key and display label, in menu order.
    private static final String[][] THEMES = {
        {THEME_ARC_DARK, "Arc Dark"},
        {THEME_ONE_DARK, "One Dark"},
        {THEME_DRACULA, "Dracula"},
        {THEME_NORD, "Nord"},
        {THEME_LIGHT, "Light"}
    };

    private static final String[] FONT_FAMILIES = {"SansSerif", "Serif", "Monospaced", "Dialog"};
    private static final int[] FONT_SIZES = {11, 12, 13, 14, 16, 18};

    private static UserPreferences sPreferences;

    private ThemeManager()
    {
    }

    /**
     * Initializes the theme manager with user preferences and applies the stored appearance.  Call once at
     * startup before building the GUI.
     */
    public static void initialize(UserPreferences preferences)
    {
        sPreferences = preferences;
        apply();
    }

    private static String theme()
    {
        return sPreferences.getSwingPreference().getString(THEME_KEY, THEME_DEFAULT);
    }

    private static String fontFamily()
    {
        return sPreferences.getSwingPreference().getString(FONT_FAMILY_KEY, FONT_DEFAULT);
    }

    private static int fontSize()
    {
        return sPreferences.getSwingPreference().getInt(FONT_SIZE_KEY, 0);
    }

    /**
     * Creates the look and feel instance for the specified theme key, defaulting to Arc Dark.
     */
    private static LookAndFeel createLookAndFeel(String key)
    {
        if(key == null)
        {
            key = THEME_DEFAULT;
        }

        switch(key)
        {
            case THEME_ONE_DARK:
                return new FlatOneDarkIJTheme();
            case THEME_DRACULA:
                return new FlatDraculaIJTheme();
            case THEME_NORD:
                return new FlatNordIJTheme();
            case THEME_LIGHT:
                return new FlatLightLaf();
            case THEME_ARC_DARK:
            default:
                return new FlatArcDarkIJTheme();
        }
    }

    /**
     * Applies the stored look and feel, table styling and base font.
     */
    public static void apply()
    {
        try
        {
            UIManager.setLookAndFeel(createLookAndFeel(theme()));

            //Restore spreadsheet-style table grid lines (FlatLaf hides them by default)
            UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
            UIManager.put("Table.showVerticalLines", Boolean.TRUE);
            UIManager.put("Table.intercellSpacing", new Dimension(1, 1));

            //FlatLaf's JIDE tab UI colors tab text from these keys (not the component foreground). Point them
            //at the themed tab foreground so tab labels are readable in every theme, live-switch included.
            Color tabText = UIManager.getColor("TabbedPane.foreground");
            if(tabText != null)
            {
                UIManager.put("JideTabbedPane.foreground", tabText);
                UIManager.put("JideTabbedPane.selectedTabTextForeground", tabText);
                UIManager.put("JideTabbedPane.unselectedTabTextForeground", tabText);
                UIManager.put("JideTabbedPane.activeTabTextForeground", tabText);
            }

            String family = fontFamily();
            int size = fontSize();

            if((family != null && !FONT_DEFAULT.equalsIgnoreCase(family)) || size > 0)
            {
                Font base = UIManager.getFont("defaultFont");

                if(base == null)
                {
                    base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
                }

                String resolvedFamily = (family == null || FONT_DEFAULT.equalsIgnoreCase(family)) ? base.getFamily() : family;
                int resolvedSize = (size > 0) ? size : base.getSize();
                UIManager.put("defaultFont", new Font(resolvedFamily, Font.PLAIN, resolvedSize));
            }
        }
        catch(Throwable t)
        {
            //Catch Throwable (not just Exception) so an Error such as NoClassDefFoundError from look and feel
            //setup can never prevent the application window from opening.
            LOG.error("Error applying application theme / look and feel", t);
        }
    }

    /**
     * Applies the current appearance settings and repaints all open windows.
     */
    private static void applyLive()
    {
        apply();
        FlatLaf.updateUI();
    }

    public static void setTheme(String theme)
    {
        sPreferences.getSwingPreference().setString(THEME_KEY, theme);
        applyLive();
    }

    public static void setFontFamily(String family)
    {
        sPreferences.getSwingPreference().setString(FONT_FAMILY_KEY, family);
        applyLive();
    }

    public static void setFontSize(int size)
    {
        sPreferences.getSwingPreference().setInt(FONT_SIZE_KEY, size);
        applyLive();
    }

    /**
     * Builds the Appearance submenu (theme, font, font size) for the View menu.
     */
    public static JMenu buildAppearanceMenu()
    {
        JMenu menu = new JMenu("Appearance");

        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        String currentTheme = theme();

        for(String[] entry : THEMES)
        {
            final String key = entry[0];
            addRadio(themeMenu, themeGroup, entry[1], key.equalsIgnoreCase(currentTheme), () -> setTheme(key));
        }

        menu.add(themeMenu);

        JMenu familyMenu = new JMenu("Font");
        ButtonGroup familyGroup = new ButtonGroup();
        String currentFamily = fontFamily();
        addRadio(familyMenu, familyGroup, "Default", FONT_DEFAULT.equalsIgnoreCase(currentFamily), () -> setFontFamily(FONT_DEFAULT));

        for(String family : FONT_FAMILIES)
        {
            final String f = family;
            addRadio(familyMenu, familyGroup, family, family.equalsIgnoreCase(currentFamily), () -> setFontFamily(f));
        }

        menu.add(familyMenu);

        JMenu sizeMenu = new JMenu("Font Size");
        ButtonGroup sizeGroup = new ButtonGroup();
        int currentSize = fontSize();
        addRadio(sizeMenu, sizeGroup, "Default", currentSize == 0, () -> setFontSize(0));

        for(int size : FONT_SIZES)
        {
            final int s = size;
            addRadio(sizeMenu, sizeGroup, Integer.toString(size), currentSize == size, () -> setFontSize(s));
        }

        menu.add(sizeMenu);

        return menu;
    }

    private static void addRadio(JMenu menu, ButtonGroup group, String label, boolean selected, Runnable action)
    {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, selected);
        item.addActionListener(e -> action.run());
        group.add(item);
        menu.add(item);
    }
}
