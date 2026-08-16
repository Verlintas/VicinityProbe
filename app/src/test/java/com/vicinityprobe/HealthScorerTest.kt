/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.HealthScorer
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SessionContextInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthScorerTest {

    private fun m(
        id: String,
        level: QualityLevel = QualityLevel.EXCELLENT,
        stats: Map<String, ChannelStats> = emptyMap(),
        attrs: Map<String, String> = emptyMap(),
    ): Measurement {
        val spec = ProbeCatalog.byId(id)!!
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            stats = stats, attributes = attrs,
            quality = QualityReport(level, QualityLevels.CODE_OK, ""),
        )
    }

    private fun reportOf(vararg ms: Measurement): MeasurementReport =
        MeasurementReport(
            schemaVersion = 1, id = "hc",
            plan = MeasurementPlan("hc", 1L, 12_000L, ms.map { it.spec.id }, "HEALTHCHECK"),
            context = SessionContextInfo("D", "15", 35, "k", "UTC", "en", 12_000L),
            measurements = ms.toList(),
        )

    private fun stats(values: FloatArray): Map<String, ChannelStats> =
        mapOf("value" to ChannelStats.compute(values, "x"))

    @Test
    fun `安静舒适环境_接近满分`() {
        val r = reportOf(
            m("noise", attrs = mapOf("LAeq" to "40.0")),
            m("sensor.temperature", stats = stats(floatArrayOf(23f, 24f, 23f))),
            m("sensor.light", stats = stats(floatArrayOf(500f, 520f, 510f))),
            m("sensor.pressure", stats = stats(floatArrayOf(1013f, 1013f))),
        )
        val s = HealthScorer.score(r)
        assertTrue("score=${s.score}", s.score >= 95)
        assertEquals("A", s.grade)
        assertTrue(s.reasons.isEmpty())
    }

    @Test
    fun `嘈杂环境_扣分并说明`() {
        val r = reportOf(m("noise", attrs = mapOf("LAeq" to "75.0")))
        val s = HealthScorer.score(r)
        assertTrue(s.score <= 90)
        assertTrue(s.reasons.any { it.contains("吵") || it.contains("噪声") })
    }

    @Test
    fun `强磁场_扣分`() {
        val r = reportOf(
            m("sensor.magnetometer", stats = mapOf("magnitude" to ChannelStats.compute(floatArrayOf(200f, 210f, 205f), "µT"))),
        )
        val s = HealthScorer.score(r)
        assertTrue(s.score <= 90)
        assertTrue(s.reasons.any { it.contains("磁场") })
    }

    @Test
    fun `失败项_大幅扣分`() {
        val r = reportOf(
            m("sensor.light"),
            m("sensor.temperature"),
            m("noise", level = QualityLevel.FAILED, attrs = mapOf("LAeq" to "0.0")),
            m("sensor.pressure", level = QualityLevel.FAILED),
            m("battery", level = QualityLevel.FAILED),
            m("gnss", level = QualityLevel.FAILED),
            m("sensor.accelerometer", level = QualityLevel.FAILED),
        )
        val s = HealthScorer.score(r)
        assertTrue("score=${s.score}", s.score < 90)
        assertTrue(s.reasons.any { it.contains("失败") })
    }

    @Test
    fun `暗环境_扣分`() {
        val r = reportOf(m("sensor.light", stats = stats(floatArrayOf(5f, 6f, 5f))))
        val s = HealthScorer.score(r)
        assertTrue(s.reasons.any { it.contains("暗") })
    }

    @Test
    fun `分数钳制在0到100`() {
        val manyFailed = (0 until 6).map { i ->
            m("sensor.temperature", level = QualityLevel.FAILED)
        }
        val r = reportOf(*manyFailed.toTypedArray())
        val s = HealthScorer.score(r)
        assertTrue(s.score in 0..100)
        assertTrue(s.grade == "C" || s.grade == "D")   // 52 分 → C;更多失败 → D
    }

    @Test
    fun `等级边界`() {
        assertEquals("A", HealthScorer.gradeOf(90))
        assertEquals("B", HealthScorer.gradeOf(89))
        assertEquals("B", HealthScorer.gradeOf(75))
        assertEquals("C", HealthScorer.gradeOf(74))
        assertEquals("D", HealthScorer.gradeOf(59))
    }
}
