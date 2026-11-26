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

package io.github.dsheirer.settings.network;

/**
 * Preferences node that holds the network stream and heartbeat settings.
 *
 * This is the node the earlier preference classes in the io.github.dsheirer.preference.network package stored their
 * values in, so values stored by earlier versions are still found and an earlier version still finds values stored by
 * this one.  Don't change the path or any key without migrating the stored values.
 */
public final class NetworkSettingsNode
{
    public static final String PATH = "/io/github/dsheirer/preference/network";

    private NetworkSettingsNode()
    {
    }
}
