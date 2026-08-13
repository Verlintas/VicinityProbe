/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import java.text.DecimalFormat
import kotlin.math.abs

object SystemClockCompat {
    fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

val fmtInt: DecimalFormat = DecimalFormat("0")
val fmt1: DecimalFormat = DecimalFormat("0.0")
val fmt2: DecimalFormat = DecimalFormat("0.00")

fun fmt(v: Double): String = if (abs(v) >= 1000) fmtInt.format(v) else fmt2.format(v)

/** 实时指标表(线程安全) */
class LiveMetrics {
    private val map = HashMap<String, Pair<String, String>>()

    @Synchronized
    fun set(id: String, label: String, value: String) {
        map[id] = label to value
    }

    @Synchronized
    fun snapshot(): Map<String, Pair<String, String>> = map.toMap()
}
