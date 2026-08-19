/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport

/**
 * 全局质量兜底(纯逻辑,可单元测试):
 * 部分采样器在"读不到数据"时仍返回 EXCELLENT(属性里塞 restricted/-1/? 占位)。
 * 会话收尾时统一降级:EXCELLENT 且没有任何有效数据 → DEGRADED(NO_DATA)。
 */
object QualityEnforcer {

    /** 占位/无效值判定 */
    fun isPlaceholder(v: String): Boolean {
        val t = v.trim()
        return t.isEmpty() || t == "?" || t.equals("restricted", ignoreCase = true) ||
            t == "-1" || t == "0" || t.startsWith("N/A", ignoreCase = true) ||
            t == "null" || t == "unknown"
    }

    /** 判定测量是否有有效数据 */
    fun hasValidData(m: Measurement): Boolean {
        if (m.stats.isNotEmpty() && m.stats.values.any { it.n > 0 }) return true
        if (m.series.isNotEmpty() && m.series.values.any { it.isNotEmpty() }) return true
        if (m.attributes.isNotEmpty()) {
            // 至少有一个非占位属性才算有数据
            if (m.attributes.values.any { !isPlaceholder(it) }) return true
        }
        return false
    }

    /**
     * 强制质量:EXCELLENT/GOOD 但无有效数据 → DEGRADED(NO_DATA);
     * DEGRADED 且无有效数据 → FAILED。
     */
    fun enforce(m: Measurement): Measurement {
        if (m.status != QualityLevels.CODE_OK && m.quality.level != QualityLevel.EXCELLENT) return m
        if (hasValidData(m)) return m
        return when (m.quality.level) {
            QualityLevel.EXCELLENT, QualityLevel.GOOD -> m.copy(
                status = QualityLevels.CODE_NO_DATA,
                quality = m.quality.copy(
                    level = QualityLevel.DEGRADED,
                    code = QualityLevels.CODE_NO_DATA,
                    detail = "没有采到有效数据|No valid data",
                    sampleCount = 0,
                ),
            )
            QualityLevel.DEGRADED -> m.copy(
                status = QualityLevels.CODE_NO_DATA,
                quality = m.quality.copy(
                    level = QualityLevel.FAILED,
                    code = QualityLevels.CODE_NO_DATA,
                    detail = "没有采到有效数据|No valid data",
                    sampleCount = 0,
                ),
            )
            else -> m
        }
    }
}
