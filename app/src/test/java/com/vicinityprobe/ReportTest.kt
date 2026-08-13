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

package com.vicinityprobe

import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SessionContextInfo
import com.vicinityprobe.report.CompareEngine
import com.vicinityprobe.report.JsonReport
import com.vicinityprobe.report.MarkdownWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private fun measurement(id: String, value: Double): Measurement = Measurement(
        spec = ProbeCatalog.byId(id)!!,
        status = QualityLevels.CODE_OK,
        stats = mapOf("value" to ChannelStats.compute(floatArrayOf(value.toFloat()), "")),
        attributes = mapOf("level_pct" to "80"),
        quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 10, achievedRateHz = 5.0),
    )

    private fun report(id: String, ms: List<Measurement> = listOf(measurement("battery", 77.0), measurement("sensor.light", 300.0))): MeasurementReport =
        MeasurementReport(
            schemaVersion = 1,
            id = id,
            plan = MeasurementPlan(id, 1_700_000_000_000L, 10_000L, ms.map { it.spec.id }, "FULL"),
            context = SessionContextInfo("TestDevice", "15", 36, "k", "UTC", "en", 0L),
            measurements = ms,
        )

    @Test
    fun json_round_trip() {
        val r = report("abc")
        val text = JsonReport.encode(r)
        assertTrue(text.contains("\"sensor.light\""))
        val back = JsonReport.decode(text)
        assertEquals(r.id, back.id)
        assertEquals(r.measurements.size, back.measurements.size)
        assertEquals(300.0, back.measurements.first { it.spec.id == "sensor.light" }.stats["value"]!!.mean, 1e-9)
        assertTrue(text.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun markdown_contains_sections() {
        val md = MarkdownWriter.write(report("abc"), "zh")
        assertTrue(md.contains("测量计划"))
        assertTrue(md.contains("TestDevice"))
        assertTrue(md.contains("电池电气参数"))
        assertTrue(md.contains("EXCELLENT"))
    }

    @Test
    fun compare_finds_differences() {
        val a = report("a")
        val b = report("b").copy(
            measurements = report("b").measurements.map {
                if (it.spec.id == "battery") {
                    it.copy(stats = it.stats.mapValues { s ->
                        s.value.copy(mean = 95.0, min = 95.0, max = 95.0, median = 95.0, p95 = 95.0, rms = 95.0)
                    })
                } else it
            },
        )
        val c = CompareEngine.compare(a, b)
        assertTrue(c.rows.any { it.channel == "value" && it.stat == "mean" })
    }
}
