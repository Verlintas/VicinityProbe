/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.Autocorrelation
import com.vicinityprobe.analysis.ComplementaryFilter
import com.vicinityprobe.analysis.LinearTrend
import com.vicinityprobe.analysis.MagCalib
import com.vicinityprobe.analysis.Moments
import com.vicinityprobe.analysis.SpectralAnalysis
import com.vicinityprobe.analysis.tiltCompensatedHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FusionTest {

    @Test
    fun `倾斜补偿航向_水平放置_磁北为零度`() {
        // 手机水平(重力在 -z),磁场指向 x(磁北)
        val heading = tiltCompensatedHeading(0f, 0f, 9.81f, 30f, 0f, -10f)
        assertNotNull(heading)
        assertTrue(kotlin.math.abs(heading!! - 0.0) < 5.0)
    }

    @Test
    fun `倾斜补偿航向_旋转90度_航向随动`() {
        // 磁场指向 y → 航向约 90°
        val heading = tiltCompensatedHeading(0f, 0f, 9.81f, 0f, 30f, -10f)
        assertNotNull(heading)
        assertTrue(kotlin.math.abs(heading!! - 90.0) < 5.0)
    }

    @Test
    fun `倾斜补偿航向_手机竖屏倾斜_仍能计算`() {
        // 手机竖起(重力在 -y),磁场沿 -z → 合理航向
        val heading = tiltCompensatedHeading(0f, 9.81f, 0f, 0f, 0f, 30f)
        assertNotNull(heading)
    }

    @Test
    fun `倾斜补偿航向_零重力_返回null`() {
        assertNull(tiltCompensatedHeading(0f, 0f, 0f, 10f, 0f, 0f))
    }

    @Test
    fun `互补滤波器_静止时_姿态收敛于加速度参考`() {
        val f = ComplementaryFilter()
        // 手机水平:roll≈0, pitch≈0
        var a = f.update(0.01, 0f, 0f, 0f, 0f, 0f, 9.81f)
        repeat(100) { a = f.update(0.01, 0f, 0f, 0f, 0f, 0f, 9.81f) }
        assertTrue(kotlin.math.abs(a.roll) < 0.1)
        assertTrue(kotlin.math.abs(a.pitch) < 0.1)
    }

    @Test
    fun `互补滤波器_倾斜30度_收敛到对应姿态`() {
        val f = ComplementaryFilter()
        val tilt = 30.0 * PI / 180.0
        // 重力分解:az = g*cos30, ay = g*sin30 → roll = atan2(ay, az) = 30°
        var a = f.update(0.01, 0f, 0f, 0f, 0f, 9.81f * sin(tilt).toFloat(), 9.81f * cos(tilt).toFloat())
        repeat(200) {
            a = f.update(0.01, 0f, 0f, 0f, 0f, 9.81f * sin(tilt).toFloat(), 9.81f * cos(tilt).toFloat())
        }
        assertEquals(30.0, Math.toDegrees(a.roll), 2.0)
    }

    @Test
    fun `互补滤波器_陀螺仪积分跟随旋转`() {
        val f = ComplementaryFilter()
        // 静止初始化
        var a = f.update(0.01, 0f, 0f, 0f, 0f, 0f, 9.81f)
        repeat(50) { a = f.update(0.01, 0f, 0f, 0f, 0f, 0f, 9.81f) }
        // 以 1 rad/s 绕 x 轴旋转 0.5s,且加速度仍水平(快速旋转时加速度参考噪声大但陀螺主导)
        val dt = 0.01
        repeat(50) { a = f.update(dt, 1f, 0f, 0f, 0f, 0f, 9.81f) }
        // 陀螺积分约 0.5 rad,互补滤波会因加速度参考缓慢拉回,但应保持正角度
        assertTrue(a.roll > 0.2)
    }
}

class InferenceTest {

    @Test
    fun `线性趋势_正斜率_检测为上升`() {
        val samples = (0 until 50).map { 1.0 + 0.1 * it }
        val fit = LinearTrend.fit(samples, 1.0)
        assertNotNull(fit)
        assertEquals(0.1, fit!!.slopePerSecond, 1e-6)
        assertTrue(fit.r2 > 0.999)
        assertTrue(fit.pValueApprox < 0.001)
        assertTrue(!fit.stationary)
    }

    @Test
    fun `线性趋势_常数序列_判定为平稳`() {
        val samples = List(50) { 5.0 }
        val fit = LinearTrend.fit(samples, 1.0)
        assertNotNull(fit)
        assertTrue(fit!!.stationary)
        assertTrue(kotlin.math.abs(fit.slopePerSecond) < 1e-9)
    }

    @Test
    fun `线性趋势_负斜率_检测为下降`() {
        // y = 100 - 2*i, dt=0.5s → 每秒斜率 = -2/0.5 = -4
        val samples = (0 until 50).map { 100.0 - 2.0 * it }
        val fit = LinearTrend.fit(samples, 0.5)
        assertNotNull(fit)
        assertEquals(-4.0, fit!!.slopePerSecond, 1e-6)
        assertTrue(fit.pValueApprox < 0.001)
    }

    @Test
    fun `线性趋势_样本不足_返回null`() {
        assertNull(LinearTrend.fit(listOf(1.0, 2.0), 1.0))
    }

    @Test
    fun `自相关_周期信号_检出周期`() {
        // 10Hz 正弦,100Hz 采样,1000 点 → 周期 10 样本
        val samples = (0 until 1000).map { sin(2 * PI * 10 * it / 100.0) }
        val period = Autocorrelation.detectPeriod(samples, 0.01, maxLag = 60)
        assertNotNull(period)
        assertEquals(10, period!!.first)
        assertEquals(0.1, period.third, 1e-9)
    }

    @Test
    fun `自相关_白噪声_检不出周期`() {
        val rnd = java.util.Random(42)
        val samples = (0 until 500).map { rnd.nextGaussian() }
        val period = Autocorrelation.detectPeriod(samples, 0.01, maxLag = 40, threshold = 0.3)
        // 白噪声自相关应低于阈值(样本量大时)
        val a = Autocorrelation.acf(samples, 40)
        assertTrue(period == null || a.max()!! < 0.3)
    }

    @Test
    fun `矩_正态分布_偏度接近零`() {
        val rnd = java.util.Random(7)
        val samples = (0 until 20000).map { rnd.nextGaussian() }
        val m = Moments.compute(samples)
        assertNotNull(m)
        assertTrue(kotlin.math.abs(m!!.skewness) < 0.05)
    }

    @Test
    fun `矩_样本不足_返回null`() {
        assertNull(Moments.compute(listOf(1.0, 2.0, 3.0)))
    }
}

class MagCalibTest {

    @Test
    fun `硬铁偏移_球面样本_估计偏移`() {
        val rnd = java.util.Random(1)
        val offset = floatArrayOf(5f, -3f, 2f)
        val samples = (0 until 500).map {
            // 单位球面上的点 + 偏移
            val theta = rnd.nextDouble() * 2 * PI
            val phi = kotlin.math.acos(2 * rnd.nextDouble() - 1)
            val r = 30.0
            floatArrayOf(
                (r * sin(phi) * cos(theta)).toFloat() + offset[0],
                (r * sin(phi) * sin(theta)).toFloat() + offset[1],
                (r * cos(phi)).toFloat() + offset[2],
            )
        }
        val res = MagCalib.fit(samples)
        assertNotNull(res)
        assertEquals(5.0, res!!.offsetX, 1.5)
        assertEquals(-3.0, res.offsetY, 1.5)
        assertEquals(2.0, res.offsetZ, 1.5)
        assertEquals(30.0, res.radius, 2.0)
    }

    @Test
    fun `硬铁偏移_样本过少_返回null`() {
        assertNull(MagCalib.fit(List(5) { floatArrayOf(1f, 2f, 3f) }))
    }
}

class SpectralExtTest {

    @Test
    fun `谱峰检测_双音信号_检出两个峰`() {
        val n = 4096
        val fs = 44100.0
        val samples = DoubleArray(n) { i ->
            sin(2 * PI * 440.0 * i / fs) + 0.5 * sin(2 * PI * 880.0 * i / fs)
        }
        val (freq, power) = com.vicinityprobe.analysis.Fft.powerSpectrum(samples, fs)
        val peaks = SpectralAnalysis.peaks(freq, power, minProminence = 0.1, minFreqHz = 100.0)
        assertTrue(peaks.size >= 2)
        val top = peaks[0].frequencyHz
        assertTrue(kotlin.math.abs(top - 440.0) < 5.0)
    }

    @Test
    fun `谐波分析_50Hz加谐波_THD合理`() {
        val n = 8192
        val fs = 8192.0   // 使 50/150/250Hz 落在整数 bin,避免泄漏
        // 50Hz 基波 + 20% 三次谐波 + 10% 五次谐波
        val samples = DoubleArray(n) { i ->
            sin(2 * PI * 50.0 * i / fs) +
                0.2 * sin(2 * PI * 150.0 * i / fs) +
                0.1 * sin(2 * PI * 250.0 * i / fs)
        }
        val (freq, power) = com.vicinityprobe.analysis.Fft.powerSpectrum(samples, fs)
        val h = SpectralAnalysis.harmonics(freq, power, 50.0)
        assertNotNull(h)
        // sqrt(0.2² + 0.1²) = 0.224 → THD ≈ 22.4%
        assertEquals(22.4, h!!.thdPercent, 3.0)
        assertEquals(2, h.harmonics.size)
        assertEquals("distorted", SpectralAnalysis.thdLevel(h.thdPercent))
    }

    @Test
    fun `谐波分析_纯正弦_低THD`() {
        val n = 8192
        val fs = 44100.0
        val samples = DoubleArray(n) { i -> sin(2 * PI * 1000.0 * i / fs) }
        val (freq, power) = com.vicinityprobe.analysis.Fft.powerSpectrum(samples, fs)
        val h = SpectralAnalysis.harmonics(freq, power, 1000.0)
        assertNotNull(h)
        assertTrue(h!!.thdPercent < 1.0)
        assertEquals("clean", SpectralAnalysis.thdLevel(h.thdPercent))
    }

    @Test
    fun `谐波分析_非法基频_返回null`() {
        val n = 1024
        val (freq, power) = com.vicinityprobe.analysis.Fft.powerSpectrum(DoubleArray(n), 44100.0)
        assertNull(SpectralAnalysis.harmonics(freq, power, 0.0))
    }
}
