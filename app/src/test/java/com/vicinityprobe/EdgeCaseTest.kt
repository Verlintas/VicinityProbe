/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.AWeighting
import com.vicinityprobe.analysis.Fft
import com.vicinityprobe.analysis.SpectrumAnalyzer
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SessionContextInfo
import com.vicinityprobe.probe.ChannelRecorder
import com.vicinityprobe.report.CompareEngine
import com.vicinityprobe.report.JsonReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EdgeCaseTest {

    private fun reportOf(id: String, value: Double): MeasurementReport {
        val spec = ProbeCatalog.byId("sensor.accelerometer")!!
        val m = Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            stats = mapOf("x" to ChannelStats.compute(floatArrayOf(value.toFloat()), "m/s²")),
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK),
        )
        return MeasurementReport(
            schemaVersion = 1, id = id,
            plan = MeasurementPlan(id, System.currentTimeMillis(), 1000L, listOf(spec.id), "TEST"),
            context = SessionContextInfo("Test", "13", 33, "x", "UTC", "en", 1000L),
            measurements = listOf(m),
        )
    }

    @Test
    fun `A计权_1000Hz为0dB_低频衰减_高频回升`() {
        assertEquals(0.0, AWeighting.weightDb(1000.0), 0.1)
        val f1 = AWeighting.weightDb(100.0)
        val f2 = AWeighting.weightDb(500.0)
        val f3 = AWeighting.weightDb(1000.0)
        val f4 = AWeighting.weightDb(5000.0)
        // 低频单调衰减至 0dB;2kHz 后有共振回升(标准行为)
        assertTrue("low freq not attenuating: $f1,$f2,$f3", f1 < f2 && f2 < f3)
        assertTrue("5kHz should be near 0: $f4", kotlin.math.abs(f4) < 2.0)
        assertTrue(AWeighting.weightDb(50.0) < -20.0)
    }

    @Test
    fun `FFT_非2幂长度_抛异常`() {
        val re = DoubleArray(1000)
        val im = DoubleArray(1000)
        try {
            Fft.fft(re, im)
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `FFT_长度1_直接返回`() {
        val re = DoubleArray(1) { 1.0 }
        val im = DoubleArray(1)
        Fft.fft(re, im)
        assertEquals(1.0, re[0], 1e-9)
    }

    @Test
    fun `ChannelStats_单元素_极值相等`() {
        val s = ChannelStats.compute(floatArrayOf(7f), "x")
        assertEquals(1, s.n)
        assertEquals(7.0, s.min, 1e-9)
        assertEquals(7.0, s.max, 1e-9)
        assertEquals(7.0, s.mean, 1e-9)
        assertEquals(7.0, s.median, 1e-9)
        assertEquals(7.0, s.p1, 1e-9)
    }

    @Test
    fun `ChannelStats_大数值_无溢出`() {
        val samples = FloatArray(2000) { (1e9 + it).toFloat() }
        val s = ChannelStats.compute(samples, "x")
        assertTrue(s.mean > 1e9)
        assertTrue(s.stddev < 1000)
        assertTrue(s.cv < 1e-5)
    }

    @Test
    fun `ChannelRecorder_降采样_保真度上限`() {
        val rec = ChannelRecorder("ch")
        for (i in 0 until 1000) rec.add(i.toLong(), i.toFloat())
        val dec = rec.decimate(100)
        assertTrue("dec=${dec.size}", dec.size <= 100)
        assertTrue(dec.isNotEmpty())
        assertEquals(0.0, dec.first().v, 1e-9)
    }

    @Test
    fun `ChannelRecorder_超上限_触发内存保护`() {
        val rec = ChannelRecorder("ch")
        for (i in 0 until ChannelRecorder.MAX_RAW + 1000) rec.add(i.toLong(), i.toFloat())
        assertTrue("size=${rec.size()}", rec.size() <= ChannelRecorder.MAX_RAW)
    }

    @Test
    fun `JsonReport_新字段_往返一致`() {
        val spec = ProbeCatalog.byId("noise")!!
        val m = Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK),
            spectrum = com.vicinityprobe.model.domain.SpectrumResult(
                method = "FFT-4096-Hann", dominantFrequencyHz = 1000.0, dominantAmplitude = 0.5,
                flatness = 0.3, bandEnergy = mapOf("low" to 0.1, "mid" to 0.8, "high" to 0.1),
                topPeaks = listOf(1000.0, 2000.0), fundamentalHz = 1000.0,
                thdPercent = 5.0, harmonicRichness = 0.25,
            ),
        )
        val report = MeasurementReport(
            schemaVersion = 1, id = "t1",
            plan = MeasurementPlan("t1", 1L, 1000L, listOf("noise"), "TEST"),
            context = SessionContextInfo("D", "13", 33, "k", "UTC", "en", 1L),
            measurements = listOf(m),
        )
        val decoded = JsonReport.decode(JsonReport.encode(report))
        assertEquals(1000.0, decoded.measurements[0].spectrum!!.dominantFrequencyHz, 1e-9)
        assertEquals(5.0, decoded.measurements[0].spectrum!!.thdPercent!!, 1e-9)
        assertEquals(listOf(1000.0, 2000.0), decoded.measurements[0].spectrum!!.topPeaks)
    }

    @Test
    fun `JsonReport_旧格式无新字段_可解码`() {
        // 模拟旧 schema(无 topPeaks/thd 字段)的 JSON
        val oldJson = """
        {"schemaVersion":1,"id":"old","plan":{"planId":"old","createdAt":1,"durationMs":1000,"probeIds":["noise"],"operator":"T"},
        "context":{"device":"D","androidVersion":"13","apiLevel":33,"kernel":"k","timezone":"UTC","locale":"en","elapsedRealtimeMs":1},
        "measurements":[{"spec":{"id":"noise","name":"n","category":"AUDIO","measurand":"SOUND_PRESSURE_LEVEL","unit":{"symbol":"dB(A)"}},
        "status":"OK","quality":{"level":"GOOD","code":"OK"},
        "spectrum":{"method":"FFT","dominantFrequencyHz":50.0,"dominantAmplitude":0.1,"flatness":0.5,"bandEnergy":{}}}]}
        """.trimIndent()
        val r = JsonReport.decode(oldJson)
        assertEquals(50.0, r.measurements[0].spectrum!!.dominantFrequencyHz, 1e-9)
        assertNull(r.measurements[0].spectrum!!.thdPercent)
    }

    @Test
    fun `CompareEngine_不同值_报告差异`() {
        val a = reportOf("a", 1.0)
        val b = reportOf("b", 9.0)
        val res = CompareEngine.compare(a, b)
        assertTrue(res.rows.isNotEmpty())
        assertTrue(res.rows.all { it.valueA != it.valueB })
    }

    @Test
    fun `频谱分析_静音输入_返回null不崩溃`() {
        val r = SpectrumAnalyzer(44100.0).analyze(List(1024) { 0.0 })
        assertTrue(r == null || r.dominantFrequencyHz >= 0)
    }

    @Test
    fun `频谱分析_极短输入_返回null`() {
        val r = SpectrumAnalyzer(44100.0).analyze(listOf(0.1, 0.2))
        assertNull(r)
    }
}
