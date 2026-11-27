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

package io.github.dsheirer.gui.theme;

/**
 * Application color themes.  Each theme has a palette that the theme manager applies to the JavaFX scenes, and a
 * dark flag that adds the dark stylesheet.  The palettes are the colors of the FlatLaf themes that sdrtrunk used for
 * its Swing windows, so the themes look the same after the move to JavaFX.
 */
public enum Theme
{
    LIGHT("Light", false,
        new Palette(0xf2f2f2, 0xffffff, 0xfafafa, 0x2675bf, 0x2675bf, 0xffffff, 0x000000, 0xc2c2c2)),
    DARK("Dark", true,
        new Palette(0x3c3f41, 0x46494b, 0x353739, 0x4b6eaf, 0x4b6eaf, 0xeeeeee, 0xdddddd, 0x616365));

    public record Palette(int background, int inner, int innerAlternate, int accent, int selectionBackground,
                          int selectionText, int text, int border)
    {
    }

    private final String mDisplayName;
    private final boolean mDark;
    private final Palette mPalette;

    Theme(String displayName, boolean dark, Palette palette)
    {
        mDisplayName = displayName;
        mDark = dark;
        mPalette = palette;
    }

    /**
     * @return human-readable name shown in the preferences UI.
     */
    public String getDisplayName()
    {
        return mDisplayName;
    }

    /**
     * @return true if this theme is a dark palette - the JavaFX dark stylesheet is applied for
     *         dark themes and removed for light themes.
     */
    public boolean isDark()
    {
        return mDark;
    }

    /**
     * Theme colors
     */
    public Palette getPalette()
    {
        return mPalette;
    }

    /**
     * Resolve a theme by its enum name, returning {@link #LIGHT} as a safe default if {@code name}
     * is null or unrecognised.
     */
    public static Theme fromName(String name)
    {
        if(name != null)
        {
            //Themes offered by earlier versions map to the light or dark theme
            switch(name)
            {
                case "SOLARIZED_LIGHT":
                    return LIGHT;
                case "NORD", "DRACULA", "SOLARIZED_DARK", "MATERIAL_DEEP_OCEAN", "ARC_DARK", "ONE_DARK":
                    return DARK;
                default:
                    break;
            }

            for(Theme t: values())
            {
                if(t.name().equals(name))
                {
                    return t;
                }
            }
        }
        return LIGHT;
    }
}
