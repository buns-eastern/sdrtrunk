/*******************************************************************************
 *     SDR Trunk
 *     Copyright (C) 2014-2026 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package io.github.dsheirer.spectrum;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * Provides selectable 256-entry color palettes for the waterfall/spectrogram display. Palettes are generated
 * by interpolating between a small set of color stops. The current selection is persisted and applied live to
 * any registered waterfall panels.
 */
public class WaterfallColorModel
{
    /**
     * Available waterfall palettes.
     */
    public enum Palette
    {
        INFERNO("Inferno"),
        VIRIDIS("Viridis"),
        ICE("Ice"),
        CLASSIC("Classic (green/yellow)"),
        GRAYSCALE("Grayscale");

        private final String mLabel;

        Palette(String label)
        {
            mLabel = label;
        }

        public String getLabel()
        {
            return mLabel;
        }
    }

    private static final String PREF_KEY = "waterfall.palette";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(WaterfallColorModel.class);
    private static final List<WaterfallPanel> PANELS = new CopyOnWriteArrayList<>();
    private static Palette sPalette = load();

    /**
     * The color model for the currently selected palette. Retained method name for backward compatibility.
     */
    public static IndexColorModel getDefaultColorModel()
    {
        return build(sPalette);
    }

    /**
     * Currently selected palette.
     */
    public static Palette getPalette()
    {
        return sPalette;
    }

    /**
     * Selects a palette, persists it, and applies it live to all registered waterfall panels.
     */
    public static void setPalette(Palette palette)
    {
        if(palette == null)
        {
            return;
        }

        sPalette = palette;
        PREFERENCES.put(PREF_KEY, palette.name());

        ColorModel colorModel = build(palette);

        for(WaterfallPanel panel : PANELS)
        {
            panel.setColorModel(colorModel);
        }
    }

    static void register(WaterfallPanel panel)
    {
        PANELS.add(panel);
    }

    static void unregister(WaterfallPanel panel)
    {
        PANELS.remove(panel);
    }

    private static Palette load()
    {
        try
        {
            return Palette.valueOf(PREFERENCES.get(PREF_KEY, Palette.INFERNO.name()));
        }
        catch(Exception e)
        {
            return Palette.INFERNO;
        }
    }

    /**
     * Builds the 256-entry IndexColorModel for a palette by interpolating between its color stops.
     */
    private static IndexColorModel build(Palette palette)
    {
        int[][] stops;

        switch(palette)
        {
            case VIRIDIS:
                stops = new int[][]{{0, 68, 1, 84}, {64, 59, 82, 139}, {128, 33, 145, 140}, {192, 94, 201, 98}, {255, 253, 231, 37}};
                break;
            case ICE:
                stops = new int[][]{{0, 0, 0, 8}, {70, 0, 40, 120}, {140, 0, 130, 200}, {200, 90, 190, 230}, {255, 224, 244, 255}};
                break;
            case CLASSIC:
                stops = new int[][]{{0, 0, 0, 127}, {31, 0, 0, 191}, {59, 243, 243, 29}, {90, 255, 255, 0}, {187, 255, 40, 0}, {255, 255, 0, 0}};
                break;
            case GRAYSCALE:
                stops = new int[][]{{0, 0, 0, 0}, {255, 255, 255, 255}};
                break;
            case INFERNO:
            default:
                //Low end lifted from near-black to a visible dark blue-purple so weak noise/signals still show.
                stops = new int[][]{{0, 12, 10, 42}, {40, 55, 20, 92}, {96, 110, 30, 110}, {144, 165, 46, 96}, {192, 216, 78, 62}, {224, 246, 132, 24}, {255, 252, 255, 164}};
                break;
        }

        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];

        for(int s = 0; s < stops.length - 1; s++)
        {
            int i0 = stops[s][0];
            int i1 = stops[s + 1][0];

            for(int x = i0; x <= i1 && x < 256; x++)
            {
                double t = (i1 == i0) ? 0.0 : (double)(x - i0) / (double)(i1 - i0);
                red[x] = (byte)Math.round(stops[s][1] + t * (stops[s + 1][1] - stops[s][1]));
                green[x] = (byte)Math.round(stops[s][2] + t * (stops[s + 1][2] - stops[s][2]));
                blue[x] = (byte)Math.round(stops[s][3] + t * (stops[s + 1][3] - stops[s][3]));
            }
        }

        return new IndexColorModel(8, 256, red, green, blue);
    }
}
