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

package com.vicinityprobe.report

import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.trBilingual

data class CompareRow(
    val probeName: String,
    val channel: String,
    val stat: String,
    val valueA: String,
    val valueB: String,
)

data class CompareResult(
    val rows: List<CompareRow>,
    val excellentA: Int,
    val excellentB: Int,
    val okA: Int,
    val okB: Int,
)

/** 双报告对比:对共有测量项的共有通道统计量与属性逐项对比 */
object CompareEngine {
    fun compare(a: MeasurementReport, b: MeasurementReport): CompareResult {
        val rows = ArrayList<CompareRow>()
        val byIdA = a.measurements.associateBy { it.spec.id }
        val byIdB = b.measurements.associateBy { it.spec.id }

        for (id in (byIdA.keys intersect byIdB.keys).sorted()) {
            val ma = byIdA[id]!!
            val mb = byIdB[id]!!
            val name = trBilingual(ma.spec.name, "en") + "|" + trBilingual(ma.spec.name, "zh")
            val channels = ma.stats.keys intersect mb.stats.keys
            for (ch in channels.sorted()) {
                val sa = ma.stats[ch]!!
                val sb = mb.stats[ch]!!
                listOf(
                    "mean" to (sa.mean to sb.mean),
                    "min" to (sa.min to sb.min),
                    "max" to (sa.max to sb.max),
                    "median" to (sa.median to sb.median),
                    "p95" to (sa.p95 to sb.p95),
                    "rms" to (sa.rms to sb.rms),
                ).forEach { (stat, pair) ->
                    if (pair.first.isNaN() || pair.second.isNaN() || kotlin.math.abs(pair.first - pair.second) > 1e-9) {
                        rows.add(CompareRow(name, ch, stat, "%.4g".format(pair.first), "%.4g".format(pair.second)))
                    }
                }
            }
            val attrs = ma.attributes.keys intersect mb.attributes.keys
            for (k in attrs.sorted()) {
                val va = ma.attributes[k]!!
                val vb = mb.attributes[k]!!
                if (va != vb) rows.add(CompareRow(name, k, "attr", va, vb))
            }
        }
        return CompareResult(
            rows = rows,
            excellentA = a.measurements.count { it.quality.level == com.vicinityprobe.model.domain.QualityLevel.EXCELLENT },
            excellentB = b.measurements.count { it.quality.level == com.vicinityprobe.model.domain.QualityLevel.EXCELLENT },
            okA = a.measurements.count { it.status == "OK" },
            okB = b.measurements.count { it.status == "OK" },
        )
    }
}
