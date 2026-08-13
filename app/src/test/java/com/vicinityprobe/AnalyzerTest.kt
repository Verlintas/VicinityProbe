package com.vicinityprobe

import com.vicinityprobe.analysis.Analyzer
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

object TestProbes {
    fun probe(id: String, group: String, metrics: List<Pair<String, Double>> = emptyList(), extra: List<Pair<String, String>> = emptyList()): ProbeResult {
        val m = metrics.map { (k, v) -> com.vicinityprobe.model.Metric(k, bil(k, k), v.toString()) } +
            extra.map { (k, v) -> com.vicinityprobe.model.Metric(k, bil(k, k), v) }
        return ProbeResult(id, group, bil(id, id), ProbeStatus.OK, metrics = m)
    }
}

class AnalyzerTest {
    private fun report(vararg probes: ProbeResult): ProbeReport =
        ProbeReport("t", 0L, 10_000L, "FULL", "test", probes.toList())

    @Test
    fun bright_quiet_comfortable_gets_high_score() {
        val r = report(
            TestProbes.probe("sensor.light", Groups.SENSOR, listOf("avg" to 500.0)),
            TestProbes.probe("noise", Groups.AUDIO, listOf("avg" to 40.0)),
            TestProbes.probe("sensor.temperature", Groups.SENSOR, listOf("avg" to 22.0)),
            TestProbes.probe("sensor.humidity", Groups.SENSOR, listOf("avg" to 50.0)),
            TestProbes.probe("cellular", Groups.NETWORK, listOf("signal" to -95.0)),
            TestProbes.probe("location", Groups.LOCATION, listOf("accuracy" to 8.0)),
        )
        val a = Analyzer.analyze(r)
        assertTrue(a.overallScore >= 75)
        assertEquals(95.0, a.scores["lighting"] ?: 0.0, 0.01)
        assertEquals(85.0, a.scores["noise"] ?: 0.0, 0.01)
    }

    @Test
    fun dark_noisy_scores_low_and_suggests() {
        val r = report(
            TestProbes.probe("sensor.light", Groups.SENSOR, listOf("avg" to 10.0)),
            TestProbes.probe("noise", Groups.AUDIO, listOf("avg" to 80.0)),
            TestProbes.probe("wifi_scan", Groups.NETWORK, extra = listOf("open" to "2")),
        )
        val a = Analyzer.analyze(r)
        assertTrue(a.overallScore < 40)
        assertTrue(a.suggestions.any { it.contains("光照") || it.contains("light") })
        assertTrue(a.suggestions.any { it.contains("开放") || it.contains("open") })
    }

    @Test
    fun scene_inference() {
        val inVehicle = Analyzer.analyze(report(TestProbes.probe("location", Groups.LOCATION, listOf("speed" to 20.0))))
        assertEquals(Analyzer.sceneLabel(Analyzer.SCENE_VEHICLE), inVehicle.scene)

        val walking = Analyzer.analyze(report(TestProbes.probe("location", Groups.LOCATION, listOf("speed" to 2.0))))
        assertEquals(Analyzer.sceneLabel(Analyzer.SCENE_MOTION), walking.scene)

        val indoor = Analyzer.analyze(report(TestProbes.probe("sensor.light", Groups.SENSOR, listOf("avg" to 50.0))))
        assertEquals(Analyzer.sceneLabel(Analyzer.SCENE_INDOOR), indoor.scene)

        val outdoor = Analyzer.analyze(report(TestProbes.probe("sensor.light", Groups.SENSOR, listOf("avg" to 20000.0))))
        assertEquals(Analyzer.sceneLabel(Analyzer.SCENE_OUTDOOR), outdoor.scene)
    }

    @Test
    fun missing_dimensions_renormalize() {
        val r = report(TestProbes.probe("noise", Groups.AUDIO, listOf("avg" to 50.0)))
        val a = Analyzer.analyze(r)
        assertTrue(a.radar.size == 1)
        assertTrue(a.overallScore in 0.0..100.0)
    }
}
