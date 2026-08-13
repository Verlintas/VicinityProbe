package com.vicinityprobe

import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.Metric
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import com.vicinityprobe.report.CompareEngine
import com.vicinityprobe.report.JsonReport
import com.vicinityprobe.report.MarkdownWriter
import com.vicinityprobe.report.ReportMetaFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private fun probe(id: String, value: String): ProbeResult {
        val m = listOf(
            Metric("avg", bil("均值", "Avg"), value),
            Metric("level", bil("电量", "Level"), "80"),
        )
        return ProbeResult(id, Groups.DEVICE, bil(id, id), ProbeStatus.OK, metrics = m)
    }

    private fun report(id: String): ProbeReport = ProbeReport(
        id = id,
        createdAt = 1_700_000_000_000L,
        scanDurationMs = 10_000L,
        mode = "FULL",
        deviceName = "TestDevice",
        results = listOf(probe("battery", "77"), probe("sensor.light", "300")),
        analysis = null,
    )

    @Test
    fun json_round_trip() {
        val r = report("abc")
        val text = JsonReport.encode(r)
        assertTrue(text.contains("\"sensor.light\""))
        val back = JsonReport.decode(text)
        assertEquals(r.id, back.id)
        assertEquals(r.results.size, back.results.size)
        assertEquals("300", back.results.first { it.id == "sensor.light" }.metrics.first().value)
    }

    @Test
    fun markdown_contains_sections() {
        val md = MarkdownWriter.write(report("abc"), "zh")
        assertTrue(md.contains("# VicinityProbe"))
        assertTrue(md.contains("TestDevice"))
        assertTrue(md.contains("battery"))
        assertTrue(md.contains("## "))
    }

    @Test
    fun meta_factory_works() {
        val meta = ReportMetaFactory.from(report("abc"))
        assertEquals("abc", meta.id)
        assertEquals(2, meta.probeCount)
        assertEquals(2, meta.okCount)
    }

    @Test
    fun compare_finds_differences() {
        val a = report("a")
        val b = report("b").copy(
            results = report("b").results.map {
                if (it.id == "battery") it.copy(metrics = it.metrics.map { m -> if (m.key == "avg") m.copy(value = "95") else m }) else it
            },
        )
        val c = CompareEngine.compare(a, b)
        assertTrue(c.rows.any { it.metricLabel.contains("Avg") || it.metricLabel.contains("均值") })
    }
}
