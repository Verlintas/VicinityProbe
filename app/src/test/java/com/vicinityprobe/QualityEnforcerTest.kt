/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.probe.QualityEnforcer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityEnforcerTest {

    private fun m(
        level: QualityLevel = QualityLevel.EXCELLENT,
        attrs: Map<String, String> = emptyMap(),
        stats: Map<String, ChannelStats> = emptyMap(),
        code: String = QualityLevels.CODE_OK,
    ): Measurement {
        val spec = ProbeCatalog.byId("device")!!
        return Measurement(
            spec = spec, status = if (level == QualityLevel.FAILED) QualityLevels.CODE_NO_DATA else code,
            attributes = attrs, stats = stats,
            quality = QualityReport(level, code, ""),
        )
    }

    @Test
    fun `空数据EXCELLENT_降级为DEGRADED`() {
        val before = m(level = QualityLevel.EXCELLENT)
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.DEGRADED, after.quality.level)
        assertEquals(QualityLevels.CODE_NO_DATA, after.quality.code)
    }

    @Test
    fun `占位属性_视为无数据`() {
        val before = m(attrs = mapOf("serial" to "restricted", "temp" to "-1"))
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.DEGRADED, after.quality.level)
    }

    @Test
    fun `有效属性_保持EXCELLENT`() {
        val before = m(attrs = mapOf("model" to "Pixel 9", "serial" to "restricted"))
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.EXCELLENT, after.quality.level)
    }

    @Test
    fun `有统计通道_保持EXCELLENT`() {
        val stats = mapOf("x" to ChannelStats.compute(floatArrayOf(1f, 2f, 3f), "x"))
        val before = m(stats = stats)
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.EXCELLENT, after.quality.level)
    }

    @Test
    fun `DEGRADED且无数据_升级为FAILED`() {
        val before = m(level = QualityLevel.DEGRADED)
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.FAILED, after.quality.level)
    }

    @Test
    fun `FAILED_不改变`() {
        val before = m(level = QualityLevel.FAILED, code = QualityLevels.CODE_NO_HARDWARE)
        val after = QualityEnforcer.enforce(before)
        assertEquals(QualityLevel.FAILED, after.quality.level)
    }

    @Test
    fun `占位值判定`() {
        assertTrue(QualityEnforcer.isPlaceholder(""))
        assertTrue(QualityEnforcer.isPlaceholder("?"))
        assertTrue(QualityEnforcer.isPlaceholder("restricted"))
        assertTrue(QualityEnforcer.isPlaceholder("-1"))
        assertTrue(QualityEnforcer.isPlaceholder("N/A"))
        assertFalse(QualityEnforcer.isPlaceholder("Pixel 9"))
        assertFalse(QualityEnforcer.isPlaceholder("192.168.1.1"))
    }
}
