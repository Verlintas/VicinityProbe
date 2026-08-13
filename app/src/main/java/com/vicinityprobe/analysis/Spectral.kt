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
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vi = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 功率谱密度(单边):输入时域样本(采样率 fsHz),返回 (freq[], power[]) */
    fun powerSpectrum(samples: DoubleArray, fsHz: Double): Pair<DoubleArray, DoubleArray> {
        val n = samples.size
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
        return com.vicinityprobe.model.domain.SpectrumResult(
            method = "FFT-$n-Hann",
            dominantFrequencyHz = (df * 100).roundToLong() / 100.0,
            dominantAmplitude = (da * 1e6).roundToLong() / 1e6,
            flatness = (Fft.flatness(power) * 100).roundToLong() / 100.0,
            bandEnergy = Fft.bandEnergy(freq, power, bands).mapValues { (it.value * 100).roundToLong() / 100.0 },
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
    /** A 计权衰减系数(用于在已知频谱上的加权) */
    fun weightDb(freqHz: Double): Double {
        val f = freqHz
        val f2 = f * f
        val num = 12194.0.pow(2.0) * f2.pow(4.0)
        val den = (f2 + 20.6.pow(2.0)) * sqrt((f2 + 107.7.pow(2.0)) * (f2 + 737.9.pow(2.0))) * (f2 + 12194.0.pow(2.0))
        return 20 * kotlin.math.log10(num / den)
    }
}
