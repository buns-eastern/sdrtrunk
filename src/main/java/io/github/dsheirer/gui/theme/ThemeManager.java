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

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import io.github.dsheirer.preference.UserPreferences;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the application look and feel (FlatLaf light/dark theme) and the base UI font (family and size),
 * persisting the user's choices to preferences and applying them at startup or live from the View menu.
 *
 * Note: this is an experimental appearance layer.  The custom-painted spectrum/symbol displays draw their own
 * colors via the SettingsManager and are unaffected.
 */
public class ThemeManager
{
    private static final Logger LOG = LoggerFactory.getLogger(ThemeManager.class);

    public static final String THEME_KEY = "application.appearance.theme";
    public static final String FONT_FAMILY_KEY = "application.appearance.font.family";
    public static final String FONT_SIZE_KEY = "application.appearance.font.size";

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String FONT_DEFAULT = "default";

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
        return sPreferences.getSwingPreference().getString(THEME_KEY, THEME_LIGHT);
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
     * Applies the stored look and feel, base font and JIDE component styling.
     */
    public static void apply()
    {
        try
        {
            if(THEME_DARK.equalsIgnoreCase(theme()))
            {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            }
            else
            {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }

            //Restore spreadsheet-style table grid lines (FlatLaf hides them by default)
            UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
            UIManager.put("Table.showVerticalLines", Boolean.TRUE);
            UIManager.put("Table.intercellSpacing", new Dimension(1, 1));

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
        addRadio(themeMenu, themeGroup, "Light", THEME_LIGHT.equalsIgnoreCase(theme()), () -> setTheme(THEME_LIGHT));
        addRadio(themeMenu, themeGroup, "Dark", THEME_DARK.equalsIgnoreCase(theme()), () -> setTheme(THEME_DARK));
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
