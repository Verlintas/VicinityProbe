/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.Denoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DenoiseTest {

    @Test
    fun `中值滤波_去除脉冲尖峰`() {
        val clean = (0 until 100).map { it.toDouble() }
        val noisy = clean.toMutableList()
        noisy[50] = 999.0    // 单点尖峰
        noisy[51] = -999.0
        val out = Denoise.medianFilter(noisy, window = 5)
        // 尖峰被完全移除;相邻双尖峰使边缘窗口中位数偏移 1(窗口内 47..51)
        assertTrue("spike survived: ${out[50]}", out[50] in 48.0..52.0)
        assertTrue("spike survived: ${out[51]}", out[51] in 48.0..52.0)
        assertTrue("edge shifted too much: ${out[49]}", out[49] in 47.0..49.0)
        assertTrue("edge point changed too much: ${out[0]}", out[0] in 0.0..2.0)
    }

    @Test
    fun `中值滤波_保留阶跃信号`() {
        val step = (0 until 80).map { if (it < 40) 1.0 else 10.0 }
        val out = Denoise.medianFilter(step, window = 3)
        assertEquals(1.0, out[39], 0.01)
        assertEquals(10.0, out[41], 0.01)
    }

    @Test
    fun `EMA_噪声平滑_方差下降`() {
        val rnd = java.util.Random(3)
        val input = (0 until 2000).map { 5.0 + rnd.nextGaussian() }
        val ema = Denoise.ExponentialAverage(0.05)
        val out = input.map { ema.push(it) }
        val ratio = Denoise.noiseReductionRatio(input, out)
        assertTrue("noise not reduced: ratio=$ratio", ratio < 0.5)
    }

    @Test
    fun `卡尔曼_正弦加噪声_平滑且噪声降低`() {
        val rnd = java.util.Random(5)
        val fs = 100.0
        val freq = 1.0
        // 小幅度正弦 + 强噪声:噪声占主导,便于评估抑制效果
        val input = (0 until 600).map { i ->
            0.2 * sin(2 * Math.PI * freq * i / fs) + 0.5 * rnd.nextGaussian()
        }
        val kf = Denoise.Kalman1D(q = 0.01, r = 0.1)
        val out = input.map { kf.update(it) }
        val ratio = Denoise.noiseReductionRatio(input, out)
        assertTrue("noise not reduced: ratio=$ratio", ratio < 0.75)
    }

    @Test
    fun `卡尔曼_恒定信号_收敛到真值`() {
        val rnd = java.util.Random(7)
        val input = (0 until 200).map { 42.0 + 10 * rnd.nextGaussian() }
        val kf = Denoise.Kalman1D(q = 1e-6, r = 100.0)
        var last = 0.0
        input.forEach { last = kf.update(it) }
        assertEquals(42.0, last, 0.5)
    }

    @Test
    fun `尖峰抑制率_中值滤波_接近1`() {
        val rnd = java.util.Random(9)
        val input = (0 until 500).map { rnd.nextGaussian() }.toMutableList()
        input[100] = 100.0; input[250] = -100.0; input[400] = 100.0
        val out = Denoise.medianFilter(input, window = 5)
        val rate = Denoise.spikeRemovalRate(input, out)
        assertTrue("rate=$rate", rate > 0.5)
    }
}
