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

package com.vicinityprobe.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 手写 Radix-2 Cooley-Tukey FFT(可迭代实现)。
 * 输入长度须为 2 的幂;就地变换,输出为复数数组(实部在前)。
 */
object Fft {
    /** 蝶形因子缓存:key = 变换长度,value = 各 stage 的旋转因子表(避免重复计算 cos/sin) */
    private val twiddleCache = HashMap<Int, Array<DoubleArray>>()
    private const val TWIDDLE_CACHE_MAX = 4

    private fun twiddles(n: Int): Array<DoubleArray> {
        synchronized(twiddleCache) {
            twiddleCache[n]?.let { return it }
            val stages = Array(32) { idx ->
                val len = 2 shl idx
                if (len <= 0 || len > n) DoubleArray(0) else {
                    val ang = -2 * PI / len
                    DoubleArray(len) { i ->
                        if (i % 2 == 0) cos(ang * (i / 2)) else sin(ang * (i / 2))
                    }
                }
            }
            if (twiddleCache.size >= TWIDDLE_CACHE_MAX) twiddleCache.clear()
            twiddleCache[n] = stages
            return stages
        }
    }

    fun fft(re: DoubleArray, im: DoubleArray) {
        require(re.size == im.size && re.size and (re.size - 1) == 0) { "长度必须为 2 的幂" }
        val n = re.size
        if (n == 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val table = twiddles(n)
        var stage = 0
        var len = 2
        while (len <= n) {
            val tw = table[stage]
            var i = 0
            while (i < n) {
                var k = 0
                val half = len / 2
                while (k < half) {
                    val wRe = tw[k * 2]
                    val wIm = tw[k * 2 + 1]
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vi = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    k++
                }
                i += len
            }
            stage++
            len = len shl 1
        }
    }

    /** 功率谱密度(单边):输入时域样本(采样率 fsHz),返回 (freq[], power[]) */
    fun powerSpectrum(samples: DoubleArray, fsHz: Double): Pair<DoubleArray, DoubleArray> {
        val n = samples.size
        if (n < 2 || n and (n - 1) != 0) throw IllegalArgumentException("长度必须为 2 的幂且 >= 2")
        val re = samples.copyOf()
        val im = DoubleArray(n)
        // Hann 窗,消除频谱泄漏
        var windowEnergy = 0.0
        for (i in 0 until n) {
            val w = 0.5 * (1 - cos(2 * PI * i / (n - 1)))
            re[i] *= w
            windowEnergy += w * w
        }
        fft(re, im)
        val freq = DoubleArray(n / 2)
        val power = DoubleArray(n / 2)
        // 单边谱归一化:2 / (n * Σw²),并排除 DC bin
        val nrm = 2.0 / (n * windowEnergy)
        for (i in 0 until n / 2) {
            freq[i] = i.toDouble() * fsHz / n
            power[i] = (re[i] * re[i] + im[i] * im[i]) * nrm
        }
        return freq to power
    }

    /** 主导频率:功率谱中能量最大的频率(排除 DC 附近,通常 >0.5Hz) */
    fun dominantFrequency(freq: DoubleArray, power: DoubleArray, minFreqHz: Double = 0.5): Pair<Double, Double> {
        var bestIdx = -1
        for (i in freq.indices) {
            if (freq[i] < minFreqHz) continue
            if (bestIdx == -1 || power[i] > power[bestIdx]) bestIdx = i
        }
        return if (bestIdx == -1) 0.0 to 0.0 else freq[bestIdx] to power[bestIdx]
    }

    /** 频谱平坦度:几何均值/算术均值,接近 1 表示白噪声状 */
    fun flatness(power: DoubleArray): Double {
        var sum = 0.0
        var logSum = 0.0
        var count = 0
        for (p in power) {
            if (p <= 0) continue
            sum += p
            logSum += ln(p)
            count++
        }
        if (count == 0 || sum == 0.0) return 0.0
        return kotlin.math.exp(logSum / count) / (sum / count)
    }

    /** 频带能量占比:freqHz 到 power 数组,按频带累计 */
    fun bandEnergy(freq: DoubleArray, power: DoubleArray, bands: List<Pair<String, Pair<Double, Double>>>): Map<String, Double> {
        val total = power.sum().let { if (it == 0.0) 1.0 else it }
        val out = HashMap<String, Double>()
        for ((name, range) in bands) {
            var e = 0.0
            for (i in freq.indices) {
                if (freq[i] >= range.first && freq[i] < range.second) e += power[i]
            }
            out[name] = e / total
        }
        return out
    }
}

/** 频谱分析器:对任意时域信号给出频谱摘要 */
class SpectrumAnalyzer(private val fsHz: Double) {
    private val minLen = 256

    fun analyze(samples: List<Double>, minFreqHz: Double = 1.0): com.vicinityprobe.model.domain.SpectrumResult? {
        if (samples.size < minLen) return null
        // 取 2 的幂窗口
        var n = 1
        while (n * 2 <= samples.size && n < 16384) n = n shl 1
        if (n < minLen) return null
        val window = samples.takeLast(n).toDoubleArray()
        val (freq, power) = Fft.powerSpectrum(window, fsHz)
        val (df, da) = Fft.dominantFrequency(freq, power, minFreqHz)
        if (df <= 0) return null
        val bands = listOf(
            "low" to (0.0 to 200.0),
            "mid" to (200.0 to 2000.0),
            "high" to (2000.0 to fsHz / 2),
        )
        // 谱峰与谐波分析
        val pk = SpectralAnalysis.peaks(freq, power, minProminence = 0.08, minFreqHz = minFreqHz)
        val harmonics = if (df > 0) SpectralAnalysis.harmonics(freq, power, df) else null
        return com.vicinityprobe.model.domain.SpectrumResult(
            method = "FFT-$n-Hann",
            dominantFrequencyHz = (df * 100).roundToLong() / 100.0,
            dominantAmplitude = (da * 1e6).roundToLong() / 1e6,
            flatness = (Fft.flatness(power) * 100).roundToLong() / 100.0,
            bandEnergy = Fft.bandEnergy(freq, power, bands).mapValues { (it.value * 100).roundToLong() / 100.0 },
            topPeaks = pk.take(5).map { (it.frequencyHz * 100).roundToLong() / 100.0 },
            fundamentalHz = harmonics?.fundamentalHz,
            thdPercent = harmonics?.thdPercent,
            harmonicRichness = harmonics?.harmonicRichness,
        )
    }

    private fun Double.roundToLong(): Long = Math.round(this)
}

/**
 * A 计权声级计算。
 * 使用 ANSI S1.4-2014 标准 IIR 滤波器近似系数(4 个二阶节,采样率相关)。
 * 简化实现:采用经典加权公式在 RMS 值上做频域近似 A 计权(对窄带信号)。
 * 由于麦克风灵敏度未知,结果标记为未校准参考值。
 */
object AWeighting {
    /** A 计权衰减系数(ANSI S1.4,含 +2.00 dB 归一化,1kHz = 0 dB) */
    fun weightDb(freqHz: Double): Double {
        val f = freqHz
        val f2 = f * f
        val f4 = f2 * f2
        val num = 12194.0.pow(2.0) * f4
        val den = (f2 + 20.6.pow(2.0)) * sqrt((f2 + 107.7.pow(2.0)) * (f2 + 737.9.pow(2.0))) * (f2 + 12194.0.pow(2.0))
        return 20 * kotlin.math.log10(num / den) + 2.0
    }
}

/** 谱峰检测结果 */
data class SpectralPeak(val frequencyHz: Double, val amplitude: Double)

/** 谐波分析结果 */
data class HarmonicResult(
    val fundamentalHz: Double,
    val harmonics: List<SpectralPeak>,     // 2f, 3f, ...(最多 8 阶,频率窗口内)
    val thdPercent: Double,                // 总谐波失真(相对基波)
    val harmonicRichness: Double,          // 谐波能量占比 (0..1)
)

/**
 * 频谱增强分析:
 * 1) [peaks] 带显著度(prominence)的谱峰检测
 * 2) [harmonics] 谐波分析:由基频定位 2f..8f 的谐波与 THD
 */
object SpectralAnalysis {

    /**
     * 谱峰检测:局部极大 + 幅度 ≥ maxAmplitude * minProminence 才计入。
     * @param minProminence 相对最大峰的最小显著度(0..1),默认 0.1
     */
    fun peaks(freq: DoubleArray, power: DoubleArray, minProminence: Double = 0.1, minFreqHz: Double = 1.0): List<SpectralPeak> {
        val maxAmp = (power.maxOrNull() ?: 0.0)
        if (maxAmp <= 0) return emptyList()
        val out = ArrayList<SpectralPeak>()
        for (i in 1 until power.size - 1) {
            if (freq[i] < minFreqHz) continue
            val p = power[i]
            if (p < maxAmp * minProminence) continue
            if (p > power[i - 1] && p >= power[i + 1]) {
                // 二次插值精确定位峰频
                val a = power[i - 1]; val b = p; val c = power[i + 1]
                val denom = a - 2 * b + c
                val delta = if (kotlin.math.abs(denom) > 1e-12) 0.5 * (a - c) / denom else 0.0
                val fPeak = freq[i] + delta * (freq[1] - freq[0])
                out.add(SpectralPeak((fPeak * 100).roundToLong() / 100.0, p))
            }
        }
        return out.sortedByDescending { it.amplitude }
    }

    /**
     * 谐波分析:给定基频,在 ±2% 容差窗内寻找 2..8 次谐波。
     * @return null 当基频无效或谱过短
     */
    fun harmonics(freq: DoubleArray, power: DoubleArray, fundamentalHz: Double, minProminence: Double = 0.05): HarmonicResult? {
        if (fundamentalHz <= 0 || freq.size < 8) return null
        val f0 = fundamentalHz
        val f0Power = powerNearest(freq, power, f0)
        if (f0Power <= 0) return null
        val df = freq[1] - freq[0]
        if (df <= 0) return null
        val hs = ArrayList<SpectralPeak>()
        var harmonicEnergy = 0.0
        for (order in 2..8) {
            val target = f0 * order
            if (target > freq.last()) break
            val idx = ((target - freq.first()) / df).toInt().coerceIn(0, freq.size - 1)
            // 搜索窗 ±2% 目标频率(换算成 bin 数),防高次谐波滑出窗口
            val win = kotlin.math.ceil(target * 0.02 / df).toInt().coerceAtLeast(1)
            val searchFrom = (idx - win).coerceAtLeast(0)
            val searchTo = (idx + win).coerceAtMost(freq.size - 1)
            var bestI = searchFrom
            for (i in searchFrom..searchTo) if (power[i] > power[bestI]) bestI = i
            // 按幅度比(power 开方)判断显著度
            if (sqrt(power[bestI]) > sqrt(f0Power) * minProminence) {
                hs.add(SpectralPeak((freq[bestI] * 100).roundToLong() / 100.0, power[bestI]))
                harmonicEnergy += power[bestI]
            }
        }
        val thd = if (f0Power > 0) 100.0 * sqrt(harmonicEnergy) / sqrt(f0Power) else 0.0
        return HarmonicResult(
            fundamentalHz = (f0 * 100).roundToLong() / 100.0,
            harmonics = hs,
            thdPercent = (thd * 100).roundToLong() / 100.0,
            harmonicRichness = if (f0Power + harmonicEnergy > 0)
                (harmonicEnergy / (f0Power + harmonicEnergy) * 1000).roundToLong() / 1000.0 else 0.0,
        )
    }

    private fun powerNearest(freq: DoubleArray, power: DoubleArray, target: Double): Double {
        var best = 0
        var bestDiff = Double.MAX_VALUE
        for (i in freq.indices) {
            val d = kotlin.math.abs(freq[i] - target)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return power[best]
    }

    /** 谐波失真等级判定(参考):<1% 纯净,<5% 轻微,<25% 失真,其余严重 */
    fun thdLevel(thdPercent: Double): String = when {
        thdPercent < 1.0 -> "clean"
        thdPercent < 5.0 -> "slightly-distorted"
        thdPercent < 25.0 -> "distorted"
        else -> "severely-distorted"
    }

    private fun Double.roundToLong(): Long = Math.round(this)
}
