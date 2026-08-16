/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 统计推断(纯数学,可单元测试):
 * 1) [LinearTrend] 最小二乘线性趋势 + 拟合优度 R² + 显著性 t 统计量
 * 2) [Autocorrelation] 自相关函数与周期检测
 * 3) [Moments] 偏度/超峰度
 */

/** 线性趋势拟合结果 */
data class TrendFit(
    val slopePerSecond: Double,   // 每秒变化量
    val intercept: Double,
    val r2: Double,               // 决定系数 [0,1]
    val tStat: Double,            // 斜率显著性(≈ N(0,1) 分布,|t|>2 显著)
    val pValueApprox: Double,     // 双尾近似 p 值
    val stationary: Boolean,      // 趋势不显著(近似稳态)
)

object LinearTrend {
    /**
     * @param samples 等间隔样本
     * @param dtSeconds 采样间隔
     */
    fun fit(samples: List<Double>, dtSeconds: Double): TrendFit? {
        val n = samples.size
        if (n < 3 || dtSeconds <= 0) return null
        if (samples.any { !it.isFinite() }) return null
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in samples.indices) {
            val x = i * dtSeconds
            val y = samples[i]
            sx += x; sy += y; sxy += x * y; sxx += x * x; syy += y * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-12) return null
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        val ssRes = run {
            var r = 0.0
            for (i in samples.indices) {
                val yHat = intercept + slope * i * dtSeconds
                r += (samples[i] - yHat) * (samples[i] - yHat)
            }
            r
        }
        val ssTot = syy - sy * sy / n
        val r2 = when {
            ssTot > 1e-12 -> 1.0 - ssRes / ssTot
            ssRes < 1e-12 -> 1.0   // 常数序列:完美拟合
            else -> 0.0
        }
        // 斜率标准误
        val se = if (n > 2) sqrt((ssRes / (n - 2)) / (sxx - sx * sx / n)) else 0.0
        val t = when {
            se > 1e-12 -> slope / se
            kotlin.math.abs(slope) > 0 -> Double.POSITIVE_INFINITY   // 完美拟合:标准误为 0
            else -> 0.0
        }
        val p = approxPValue(abs(t), n - 2)
        return TrendFit(
            slopePerSecond = slope,
            intercept = intercept,
            r2 = r2.coerceIn(0.0, 1.0),
            tStat = t,
            pValueApprox = p,
            stationary = p > 0.05,
        )
    }

    /** 双尾 p 值近似:标准正态 CDF(Abramowitz & Stegun 26.2.17,误差 < 7.5e-8) */
    private fun approxPValue(t: Double, df: Int): Double {
        if (df <= 0) return 1.0
        if (!t.isFinite()) return if (t > 0) 0.0 else 1.0   // 完美拟合:t=∞ → 极显著
        // 小样本修正:学生化 t → 正态
        val tAdj = t * sqrt(df.toDouble() / (df + 2.0))
        val p = 2.0 * (1.0 - normalCdf(tAdj))
        return p.coerceIn(0.0, 1.0)
    }

    private fun normalCdf(z: Double): Double {
        if (!z.isFinite()) return if (z > 0) 1.0 else 0.0
        val t = 1.0 / (1.0 + 0.2316419 * kotlin.math.abs(z))
        val d = 0.39894228040143268 * kotlin.math.exp(-z * z / 2.0)
        val c = doubleArrayOf(0.319381530, -0.356563782, 1.781477937, -1.821255978, 1.330274429)
        var s = 0.0
        var tk = t
        for (coef in c) { s += coef * tk; tk *= t }
        val p = d * s
        return if (z > 0) 1.0 - p else p
    }
}

/** 自相关分析:检测周期性成分 */
object Autocorrelation {
    /**
     * 归一化自相关(无偏),最大滞后 maxLag。
     * @return acf[lag],lag = 1..maxLag
     */
    fun acf(samples: List<Double>, maxLag: Int): List<Double> {
        val n = samples.size
        if (n < 2 || maxLag <= 0) return emptyList()
        val m = minOf(maxLag, n - 1)
        val mean = samples.sum() / n
        var var0 = 0.0
        for (v in samples) var0 += (v - mean) * (v - mean)
        if (var0 <= 1e-12) return List(m) { 1.0 }
        return (1..m).map { lag ->
            var cov = 0.0
            for (i in 0 until n - lag) cov += (samples[i] - mean) * (samples[i + lag] - mean)
            cov / (n - lag) / (var0 / n)
        }
    }

    /**
     * 周期检测:在 1..maxLag 内找超过阈值的最大自相关峰。
     * @return (lag, acfValue, periodSeconds = lag*dt)
     */
    fun detectPeriod(samples: List<Double>, dtSeconds: Double, maxLag: Int = 64, threshold: Double = 0.3): Triple<Int, Double, Double>? {
        // 零方差(常数序列)无周期性可言
        val mean0 = samples.average()
        if (samples.all { kotlin.math.abs(it - mean0) < 1e-12 }) return null
        val a = acf(samples, maxLag)
        if (a.isEmpty()) return null
        var bestVal = 0.0
        for (v in a) if (v > bestVal) bestVal = v
        if (bestVal < threshold) return null
        // 周期信号在 1f/2f/3f 处都有峰:取超过最大峰 95% 的最小 lag(最短周期最有意义)
        var bestLag = -1
        for (i in a.indices) {
            if (a[i] >= bestVal * 0.95 && (bestLag == -1 || i + 1 < bestLag)) bestLag = i + 1
        }
        if (bestLag <= 0) return null
        return Triple(bestLag, bestVal, bestLag * dtSeconds)
    }
}

/** 高阶矩:偏度与超峰度 */
object Moments {
    data class Result(val n: Int, val skewness: Double, val kurtosisExcess: Double, val peakedness: String)

    fun compute(samples: List<Double>): Result? {
        val n = samples.size
        if (n < 4) return null
        val mean = samples.sum() / n
        var m2 = 0.0; var m3 = 0.0; var m4 = 0.0
        for (v in samples) {
            val d = v - mean
            m2 += d * d; m3 += d * d * d; m4 += d * d * d * d
        }
        m2 /= n; m3 /= n; m4 /= n
        val s2 = sqrt(m2)
        if (s2 < 1e-12) return Result(n, 0.0, 0.0, "flat")
        val skew = m3 / (s2 * s2 * s2)
        val kurt = m4 / (m2 * m2) - 3.0
        val peakedness = when {
            kurt > 1.0 -> "leptokurtic"
            kurt < -1.0 -> "platykurtic"
            else -> "mesokurtic"
        }
        return Result(n, skew, kurt, peakedness)
    }
}
