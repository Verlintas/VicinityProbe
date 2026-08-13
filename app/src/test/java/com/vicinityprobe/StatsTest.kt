package com.vicinityprobe

import com.vicinityprobe.analysis.Fft
import com.vicinityprobe.analysis.SpectrumAnalyzer
import com.vicinityprobe.model.domain.ChannelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StatsTest {
    @Test
    fun channel_stats_matches_expected() {
        val s = ChannelStats.compute(floatArrayOf(1f, 2f, 3f, 4f, 5f), "")
        assertEquals(5, s.n)
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(5.0, s.max, 1e-9)
        assertEquals(3.0, s.mean, 1e-9)
        assertEquals(1.414213562, s.stddev, 1e-6)
        assertEquals(3.0, s.median, 1e-9)
        assertEquals(1.0, s.p1, 1e-9)
        assertEquals(5.0, s.p99, 1e-9)
        assertEquals(3.31662479, s.rms, 1e-6)
    }

    @Test
    fun channel_stats_empty_is_guarded() {
        val s = ChannelStats.compute(FloatArray(0), "")
        assertEquals(0, s.n)
    }

    @Test
    fun fft_detects_dominant_sinusoid() {
        // 生成 50Hz 正弦,采样率 1000Hz,1 秒
        val fs = 1000.0
        val n = 1024
        val samples = DoubleArray(n) { sin(2 * PI * 50.0 * it / fs) }
        val (freq, power) = Fft.powerSpectrum(samples, fs)
        val (domF, _) = Fft.dominantFrequency(freq, power)
        assertTrue("dominant=${domF}", domF in 49.0..51.0)
    }

    @Test
    fun spectrum_analyzer_extracts_dominant() {
        val fs = 44100.0
        val n = 8192
        val f0 = 1000.0
        val samples = List(n) { sin(2 * PI * f0 * it / fs) }
        val r = SpectrumAnalyzer(fs).analyze(samples)
        assertTrue(r != null)
        assertTrue(r!!.dominantFrequencyHz in 990.0..1010.0)
        assertTrue(r.bandEnergy["mid"]!! > 0.5)
    }

    @Test
    fun fft_power_spectrum_flat_for_noise() {
        // 白噪声频谱平坦度应较高
        val rnd = java.util.Random(42)
        val n = 1024
        val samples = DoubleArray(n) { rnd.nextDouble() * 2 - 1 }
        val (freq, power) = Fft.powerSpectrum(samples, 1000.0)
        val flat = Fft.flatness(power)
        assertTrue("flat=$flat", flat > 0.5)
    }
}
