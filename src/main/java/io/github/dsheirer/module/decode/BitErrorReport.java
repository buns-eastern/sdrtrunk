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

package io.github.dsheirer.module.decode;

/**
 * Report of forward error correction (FEC) bit error statistics for a quantity of FEC-protected bits, suitable for
 * deriving a bit error rate (BER) measurement over known/correctable bits.
 *
 * @param bitErrors quantity of bit errors that were detected/corrected.
 * @param bitsChecked quantity of FEC-protected bits that were checked.
 */
public record BitErrorReport(int bitErrors, int bitsChecked)
{
}
