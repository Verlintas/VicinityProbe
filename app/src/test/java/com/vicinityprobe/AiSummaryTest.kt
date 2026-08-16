/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.ReportSummarizer
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SessionContextInfo
import com.vicinityprobe.model.domain.ChannelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSummaryTest {

    private fun measurement(
        id: String,
        level: QualityLevel,
        stats: Map<String, ChannelStats> = emptyMap(),
        attrs: Map<String, String> = emptyMap(),
        qualityCode: String = QualityLevels.CODE_OK,
    ): Measurement {
        val spec = ProbeCatalog.byId(id)!!
        return Measurement(
            spec = spec, status = if (level == QualityLevel.FAILED) qualityCode else QualityLevels.CODE_OK,
            stats = stats, attributes = attrs,
            quality = QualityReport(level, qualityCode, ""),
        )
    }

    private fun reportOf(vararg ms: Measurement): MeasurementReport {
        return MeasurementReport(
            schemaVersion = 1, id = "t1",
            plan = MeasurementPlan("t1", 1L, 10_000L, ms.map { it.spec.id }, "TEST"),
            context = SessionContextInfo("Pixel 9", "15", 35, "6.1", "UTC", "en", 10_000L),
            measurements = ms.toList(),
        )
    }

    @Test
    fun `摘要_统计质量分布与异常`() {
        val r = reportOf(
            measurement("noise", QualityLevel.EXCELLENT, attrs = mapOf("LAeq" to "55.0", "L10" to "60.0")),
            measurement("battery", QualityLevel.GOOD, attrs = mapOf("level_pct" to "80")),
            measurement("nfc", QualityLevel.FAILED, qualityCode = "NO_HARDWARE"),
            measurement("wifi", QualityLevel.DEGRADED, qualityCode = "SYSTEM_THROTTLED"),
        )
        val s = ReportSummarizer.summarize(r, "zh")
        assertEquals(4, s.probeCount)
        assertEquals(1, s.qualityCounts["EXCELLENT"])
        assertEquals(1, s.qualityCounts["FAILED"])
        assertTrue(s.anomalies.any { it.contains("nfc") && it.contains("NO_HARDWARE") })
        assertTrue(s.anomalies.any { it.contains("wifi") && it.contains("DEGRADED") })
        assertTrue(s.keyMetrics.any { it.contains("55.0") })
        assertTrue(s.text.contains("EXCELLENT=1"))
    }

    @Test
    fun `摘要_检测超常磁场`() {
        val stats = mapOf("magnitude" to ChannelStats.compute(floatArrayOf(300f, 320f, 310f), "µT"))
        val r = reportOf(measurement("sensor.magnetometer", QualityLevel.EXCELLENT, stats = stats))
        val s = ReportSummarizer.summarize(r, "zh")
        assertTrue("应检测到强磁场: ${s.anomalies}", s.anomalies.any { it.contains("磁场异常") })
    }

    @Test
    fun `摘要_加速度异常`() {
        val stats = mapOf("magnitude" to ChannelStats.compute(floatArrayOf(25f, 30f, 28f), "m/s²"))
        val r = reportOf(measurement("sensor.accelerometer", QualityLevel.EXCELLENT, stats = stats))
        val s = ReportSummarizer.summarize(r, "zh")
        assertTrue(s.anomalies.any { it.contains("加速度异常") })
    }

    @Test
    fun `摘要_无异常时给出占位`() {
        val r = reportOf(measurement("sensor.light", QualityLevel.EXCELLENT))
        val s = ReportSummarizer.summarize(r, "zh")
        assertTrue(s.text.contains("无显著异常"))
        assertTrue(s.anomalies.isEmpty())
    }

    @Test
    fun `脱敏_SSID打码`() {
        assertEquals("ab***", ReportSummarizer.sanitizeSsid("abcd1234"))
        assertEquals("***", ReportSummarizer.sanitizeSsid("ab"))
        assertFalse(ReportSummarizer.sanitizeSsid("HomeWiFi-5G").contains("HomeWiFi-5G"))
    }

    @Test
    fun `系统提示词_要求JSON输出`() {
        val zh = ReportSummarizer.systemPrompt("zh")
        assertTrue(zh.contains("JSON"))
        assertTrue(zh.contains("summary"))
        val en = ReportSummarizer.systemPrompt("en")
        assertTrue(en.contains("JSON"))
        assertTrue(en.contains("rigorous"))
    }
}
