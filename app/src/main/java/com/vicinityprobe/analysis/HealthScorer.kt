/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.QualityLevel
import kotlin.math.roundToInt

/**
 * 环境体检本地评分(纯逻辑,可单元测试):
 * 100 分起步,按质量等级与异常指标扣分,给出等级与通俗原因。
 * AI 未配置时用此结果;AI 配置后作为评分的事实基础。
 */

data class HealthScore(
    val score: Int,
    val grade: String,          // A / B / C / D
    val reasons: List<String>,  // 扣分原因(通俗)
    val positives: List<String>,// 表现好的方面
)

object HealthScorer {

    fun gradeOf(score: Int): String = when {
        score >= 90 -> "A"
        score >= 75 -> "B"
        score >= 60 -> "C"
        else -> "D"
    }

    fun score(report: MeasurementReport): HealthScore {
        var score = 100
        val reasons = ArrayList<String>()
        val positives = ArrayList<String>()
        val byId = report.measurements.associateBy { it.spec.id }

        // 质量等级扣分
        val failed = report.measurements.count { it.quality.level == QualityLevel.FAILED }
        val degraded = report.measurements.count { it.quality.level == QualityLevel.DEGRADED }
        if (failed > 0) {
            val penalty = (failed * 8).coerceAtMost(40)
            score -= penalty
            reasons.add("有 $failed 项探测失败(扣 $penalty 分)")
        }
        if (degraded > 0) {
            val penalty = (degraded * 3).coerceAtMost(21)
            score -= penalty
            reasons.add("有 $degraded 项数据质量欠佳(扣 $penalty 分)")
        }

        // 环境指标扣分(通俗化)
        noise(byId["noise"], score, reasons).let { if (it != 0) score = it }
        magnet(byId["sensor.magnetometer"], score, reasons).let { if (it != 0) score = it }
        temp(byId["sensor.temperature"], score, reasons).let { if (it != 0) score = it }
        light(byId["sensor.light"], score, reasons).let { if (it != 0) score = it }
        pressure(byId["sensor.pressure"], score, reasons).let { if (it != 0) score = it }
        battery(byId["battery"], score, reasons).let { if (it != 0) score = it }

        // 正面信息
        noise(byId["noise"], 0, positives, positive = true)
        light(byId["sensor.light"], 0, positives, positive = true)
        if (byId["sensor.accelerometer"]?.quality?.level == QualityLevel.EXCELLENT) {
            positives.add("设备运行平稳,无明显异常振动")
        }

        val finalScore = score.coerceIn(0, 100)
        return HealthScore(finalScore, gradeOf(finalScore), reasons, positives)
    }

    private fun noise(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val laeq = m?.attributes?.get("LAeq")?.toDoubleOrNull() ?: return 0
        return when {
            laeq >= 85 -> { out.add("环境噪声偏高(${laeq.roundToInt()} dB),长时间暴露对听力有影响"); score - 20 }
            laeq >= 70 -> { out.add("环境比较吵(${laeq.roundToInt()} dB),适合短时间停留"); score - 10 }
            laeq >= 55 -> { out.add("环境略显嘈杂(${laeq.roundToInt()} dB)"); score - 4 }
            laeq in 35.0..54.9 -> { if (positive) out.add("环境安静舒适(${laeq.roundToInt()} dB)"); score }
            else -> { if (positive) out.add("环境非常安静(${laeq.roundToInt()} dB)"); score }
        }
    }

    private fun magnet(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val mag = m?.stats?.get("magnitude")?.mean ?: return 0
        return when {
            mag > 100 -> { out.add("附近有较强磁场(${mag.roundToInt()} µT),可能来自电器或磁铁"); score - 10 }
            mag in 65.0..100.0 -> { out.add("磁场偏强(${mag.roundToInt()} µT)"); score - 5 }
            else -> { if (positive) out.add("磁场环境正常(${mag.roundToInt()} µT)"); score }
        }
    }

    private fun temp(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val t = m?.stats?.get("value")?.mean ?: return 0
        return when {
            t > 35 -> { out.add("环境偏热(${t.roundToInt()}°C),注意通风降温"); score - 8 }
            t < 10 -> { out.add("环境偏冷(${t.roundToInt()}°C),注意保暖"); score - 5 }
            t in 18.0..26.0 -> { if (positive) out.add("温度舒适(${t.roundToInt()}°C)"); score }
            else -> { if (positive) out.add("温度尚可(${t.roundToInt()}°C)"); score }
        }
    }

    private fun light(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val lx = m?.stats?.get("value")?.mean ?: return 0
        return when {
            lx < 10 -> { out.add("光线很暗(${lx.roundToInt()} lx),阅读或工作可能费眼"); score - 5 }
            lx < 50 -> { out.add("光线偏暗(${lx.roundToInt()} lx)"); score - 2 }
            lx > 2000 -> { out.add("光线很强(${lx.roundToInt()} lx),可能刺眼"); score - 2 }
            lx in 300.0..800.0 -> { if (positive) out.add("光线明亮舒适(${lx.roundToInt()} lx)"); score }
            else -> { if (positive) out.add("光照适宜(${lx.roundToInt()} lx)"); score }
        }
    }

    private fun pressure(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val p = m?.stats?.get("value")?.mean ?: return 0
        return when {
            p < 900 || p > 1080 -> { out.add("气压异常(${p.roundToInt()} hPa),可能处于特殊环境或传感器异常"); score - 3 }
            else -> { if (positive) out.add("气压正常(${p.roundToInt()} hPa)"); score }
        }
    }

    private fun battery(m: com.vicinityprobe.model.domain.Measurement?, score: Int, out: MutableList<String>, positive: Boolean = false): Int {
        val lvl = m?.attributes?.get("level_pct")?.toIntOrNull() ?: return 0
        return when {
            lvl < 20 -> { out.add("手机电量偏低($lvl%)"); score - 3 }
            else -> { if (positive) out.add("手机电量充足($lvl%)"); score }
        }
    }
}
