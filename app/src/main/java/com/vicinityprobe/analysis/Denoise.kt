/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import kotlin.math.abs

/**
 * 传感器去噪滤波(纯数学,可单元测试):
 * - [medianFilter] 滚动中值滤波:去脉冲尖峰(手持抖动/瞬时干扰),保留阶跃
 * - [ExponentialAverage] 指数滑动平均(EMA):平滑白噪声,α 越小越平滑
 * - [Kalman1D] 一维卡尔曼滤波:自适应噪声方差,兼顾平滑与响应
 */
object Denoise {

    /** 滚动中值滤波:窗口大小须为奇数,输出与输入等长(边缘用可用窗口) */
    fun medianFilter(samples: List<Double>, window: Int = 5): List<Double> {
        if (samples.isEmpty() || window <= 1) return samples
        val w = if (window % 2 == 0) window + 1 else window
        val half = w / 2
        return samples.mapIndexed { i, _ ->
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(samples.size - 1)
            samples.subList(lo, hi + 1).sorted()[samples.subList(lo, hi + 1).size / 2]
        }
    }

    /** 指数滑动平均 */
    class ExponentialAverage(private val alpha: Double = 0.2) {
        private var value: Double? = null

        fun push(x: Double): Double {
            value = if (value == null) x else value!! * (1 - alpha) + x * alpha
            return value!!
        }

        fun reset() { value = null }
    }

    /**
     * 一维卡尔曼滤波(标量):
     * 状态 x,过程噪声 q,测量噪声 r。
     * q 越大越跟随测量(响应快),r 越大越平滑(抑制噪声)。
     */
    class Kalman1D(private val q: Double = 1e-4, private val r: Double = 1e-2) {
        private var x = 0.0
        private var p = 1.0
        private var initialized = false

        fun update(z: Double): Double {
            if (!initialized) {
                x = z
                p = r
                initialized = true
                return x
            }
            // 预测(恒速模型近似:无过程驱动)
            p += q
            // 更新
            val k = p / (p + r)
            x += k * (z - x)
            p = (1 - k) * p
            return x
        }

        fun reset() { initialized = false }
    }

    /** 滤波质量评估:输出信号的标准差与输入比(降噪比,<1 表示噪声被抑制) */
    fun noiseReductionRatio(input: List<Double>, output: List<Double>): Double {
        if (input.isEmpty() || input.size != output.size) return 1.0
        val inMean = input.average()
        val outMean = output.average()
        val inStd = kotlin.math.sqrt(input.sumOf { (it - inMean) * (it - inMean) } / input.size)
        val outStd = kotlin.math.sqrt(output.sumOf { (it - outMean) * (it - outMean) } / output.size)
        if (inStd <= 0) return 1.0
        return outStd / inStd
    }

    /** 峰值抑制比:输出中超过输入 3σ 的尖峰数量 */
    fun spikeRemovalRate(input: List<Double>, output: List<Double>): Double {
        if (input.size != output.size || input.size < 4) return 0.0
        val mean = input.average()
        val std = kotlin.math.sqrt(input.sumOf { (it - mean) * (it - mean) } / input.size)
        if (std <= 0) return 0.0
        val spikesIn = input.count { abs(it - mean) > 3 * std }
        val spikesOut = output.count { abs(it - mean) > 3 * std }
        if (spikesIn == 0) return 1.0
        return ((spikesIn - spikesOut).toDouble() / spikesIn).coerceAtLeast(0.0)
    }
}
