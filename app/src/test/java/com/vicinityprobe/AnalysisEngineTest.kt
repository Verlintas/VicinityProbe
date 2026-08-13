package com.vicinityprobe

import com.vicinityprobe.analysis.AnalysisEngine
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SessionContextInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

object TestMeasurements {
    fun spec(id: String): ProbeSpec = ProbeCatalog.byId(id)!!

    fun ok(id: String, attrs: Map<String, String> = emptyMap(), stats: Map<String, com.vicinityprobe.model.domain.ChannelStats> = emptyMap()): Measurement =
        Measurement(
            spec = spec(id), status = QualityLevels.CODE_OK, attributes = attrs, stats = stats,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 100, achievedRateHz = 10.0),
        )
}

class AnalysisEngineTest {
    private fun report(vararg ms: Measurement): MeasurementReport = MeasurementReport(
        schemaVersion = 1,
        id = "t",
        plan = MeasurementPlan("t", 0L, 10_000L, ms.map { it.spec.id }, "TEST"),
        context = SessionContextInfo("test", "15", 36, "k", "UTC", "en", 0L),
        measurements = ms.toList(),
    )

    @Test
    fun acoustics_extracted() {
        val m = TestMeasurements.ok("noise", attrs = mapOf(
            "LAeq" to "65.2", "Lpeak" to "80.1", "L10" to "70", "L50" to "60", "L90" to "50", "calibrated" to "false",
        ))
        val a = AnalysisEngine.analyze(report(m)).acoustics!!
        assertEquals(65.2, a.laeqDBA!!, 1e-9)
        assertEquals(80.1, a.lpeakDBA!!, 1e-9)
        assertEquals(50.0, a.l90DBA!!, 1e-9)
        assertEquals(false, a.calibrated)
    }

    @Test
    fun vibration_from_accel_magnitude() {
        val stats = mapOf("magnitude" to com.vicinityprobe.model.domain.ChannelStats.compute(
            floatArrayOf(9.7f, 9.8f, 9.9f, 10.1f, 10.2f, 10.0f, 9.6f, 10.3f), "m/s²"))
        val m = TestMeasurements.ok("sensor.accelerometer", stats = stats)
        val v = AnalysisEngine.analyze(report(m)).vibration!!
        assertTrue(v.rmsMs2!! > 9.0)
        assertTrue(v.crestFactor!! > 1.0)
    }

    @Test
    fun positioning_summary() {
        val loc = TestMeasurements.ok("location", attrs = mapOf("accuracy_m" to "12.5"))
        val gnss = TestMeasurements.ok("gnss", attrs = mapOf("used_in_fix" to "14", "visible" to "30"))
        val p = AnalysisEngine.analyze(report(loc, gnss)).positioning!!
        assertEquals(12.5, p.horizontalAccuracyM!!, 1e-9)
        assertEquals(14, p.satellitesUsed)
        assertEquals(30, p.satellitesVisible)
    }

    @Test
    fun context_classification_vehicle_by_speed() {
        val loc = TestMeasurements.ok("location", attrs = mapOf("speed_ms" to "25.0"))
        val c = AnalysisEngine.analyze(report(loc)).contextClassification!!
        assertEquals("vehicle", c.classId)
        assertTrue(c.confidence >= 0.5)
    }

    @Test
    fun no_measurements_yields_nulls() {
        val a = AnalysisEngine.analyze(report())
        assertNull(a.acoustics)
        assertNull(a.vibration)
    }

    @Test
    fun failed_measurement_excluded() {
        val m = Measurement(
            spec = TestMeasurements.spec("noise"), status = QualityLevels.CODE_PERMISSION_DENIED,
            quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_PERMISSION_DENIED, ""),
        )
        val a = AnalysisEngine.analyze(report(m))
        assertNull(a.acoustics)
    }
}
