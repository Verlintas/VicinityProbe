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

package com.vicinityprobe.model.domain

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * 统计量:对测量通道的描述性统计。
 * 由原始样本在测量结束时精确计算(排序求分位数),实时阶段用在线算法近似。
 */
@Serializable
data class ChannelStats(
    val n: Int = 0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val mean: Double = 0.0,
    val stddev: Double = 0.0,
    val rms: Double = 0.0,
    val cv: Double = 0.0,               // 变异系数 stddev/|mean|
    val p1: Double = 0.0,
    val p5: Double = 0.0,
    val p25: Double = 0.0,
    val median: Double = 0.0,
    val p75: Double = 0.0,
    val p95: Double = 0.0,
    val p99: Double = 0.0,
    val last: Double = 0.0,
    val unit: String = "",
) {
    companion object {
        fun compute(samples: FloatArray, unit: String): ChannelStats {
            val n = samples.size
            if (n == 0) return ChannelStats(unit = unit)
            val sorted = samples.sorted()
            val mean: Double = sorted.sumOf { it.toDouble() } / n
            var m2 = 0.0
            var sq = 0.0
            for (v in sorted) {
                val d = v - mean
                m2 += d * d
                sq += v.toDouble() * v
            }
            val stddev = sqrt(m2 / n)
            val rms = sqrt(sq / n)
            fun p(q: Double): Double {
                val idx = kotlin.math.round((n - 1) * q).toInt().coerceIn(0, n - 1)
                return sorted[idx].toDouble()
            }
            return ChannelStats(
                n = n,
                min = sorted.first().toDouble(),
                max = sorted.last().toDouble(),
                mean = mean,
                stddev = stddev,
                rms = rms,
                cv = if (mean != 0.0) stddev / kotlin.math.abs(mean) else 0.0,
                p1 = p(0.01), p5 = p(0.05), p25 = p(0.25),
                median = p(0.5), p75 = p(0.75), p95 = p(0.95), p99 = p(0.99),
                last = samples.last().toDouble(),
                unit = unit,
            )
        }
    }
}

/** 数据质量等级 */
@Serializable
enum class QualityLevel { EXCELLENT, GOOD, DEGRADED, FAILED }

/** 数据质量报告:覆盖率、采样率达成度、原因码 */
@Serializable
data class QualityReport(
    val level: QualityLevel = QualityLevel.FAILED,
    val code: String = "",                  // 机器可读原因码
    val detail: String = "",                // 人类可读说明(bil)
    val coveragePct: Double = 0.0,          // 实际/标称采样率覆盖率
    val sampleCount: Int = 0,
    val achievedRateHz: Double = 0.0,
    val nominalRateHz: Double = 0.0,
)

object QualityLevels {
    const val CODE_OK = "OK"
    const val CODE_NO_HARDWARE = "NO_HARDWARE"
    const val CODE_PERMISSION_DENIED = "PERMISSION_DENIED"
    const val CODE_FEATURE_OFF = "FEATURE_OFF"
    const val CODE_NO_FIX = "NO_FIX"
    const val CODE_INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES"
    const val CODE_SAMPLE_RATE_LOW = "SAMPLE_RATE_LOW"
    const val CODE_SENSOR_UNCALIBRATED = "SENSOR_UNCALIBRATED"
    const val CODE_ACQUISITION_ERROR = "ACQUISITION_ERROR"
    const val CODE_NO_DATA = "NO_DATA"
    const val CODE_THROTTLED = "SYSTEM_THROTTLED"

    fun ok(rateHz: Double, count: Int, coverage: Double = 100.0) = QualityReport(
        level = QualityLevel.EXCELLENT, code = CODE_OK,
        sampleCount = count, achievedRateHz = rateHz, coveragePct = coverage,
    )
}
