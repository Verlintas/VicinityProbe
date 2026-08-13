package com.vicinityprobe.analysis

import com.vicinityprobe.model.domain.AcousticsSummary
import com.vicinityprobe.model.domain.AnalysisSummary
import com.vicinityprobe.model.domain.ContextClassification
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.PositioningSummary
import com.vicinityprobe.model.domain.VibrationSummary
import kotlin.math.sqrt

/**
 * 专业分析引擎:基于测量数据计算声学/振动/定位/上下文分类摘要。
 * 全部基于实际测量值,不输出主观评分。
 */
object AnalysisEngine {
    fun analyze(report: MeasurementReport): AnalysisSummary {
        val byId = report.measurements.associateBy { it.spec.id }
        return AnalysisSummary(
            acoustics = acoustics(byId["noise"]),
            vibration = vibration(byId["sensor.accelerometer"]),
            positioning = positioning(byId["location"], byId["gnss"], byId["nmea"]),
            contextClassification = classify(byId["location"], byId["sensor.accelerometer"], byId["sensor.activity"]),
        )
    }

    private fun acoustics(m: Measurement?): AcousticsSummary? {
        if (m == null || m.status != "OK") return null
        val laeq = m.attributes["LAeq"]?.toDoubleOrNull()
        val lpeak = m.attributes["Lpeak"]?.toDoubleOrNull()
        val l10 = m.attributes["L10"]?.toDoubleOrNull()
        val l50 = m.attributes["L50"]?.toDoubleOrNull()
        val l90 = m.attributes["L90"]?.toDoubleOrNull()
        return AcousticsSummary(
            laeqDBA = laeq, lpeakDBA = lpeak, l10DBA = l10, l50DBA = l50, l90DBA = l90,
            calibrated = m.attributes["calibrated"] == "true",
        )
    }

    private fun vibration(m: Measurement?): VibrationSummary? {
        if (m == null || m.status != "OK") return null
        val mag = m.stats["magnitude"] ?: return null
        val rms = mag.rms
        val crest = if (mag.rms > 0) mag.max / mag.rms else 0.0
        val dominant = m.spectrum?.dominantFrequencyHz
        // ISO 2631 全身振动近似分级(参考级,未校准)
        val level = when {
            rms < 0.02 -> null
            rms < 0.1 -> "weak"
            rms < 0.3 -> "moderate"
            else -> "severe"
        }
        return VibrationSummary(
            dominantFrequencyHz = dominant,
            rmsMs2 = (rms * 1000).roundToLong() / 1000.0,
            crestFactor = (crest * 100).roundToLong() / 100.0,
            vibrationLevel = level,
        )
    }

    private fun positioning(loc: Measurement?, gnss: Measurement?, nmea: Measurement?): PositioningSummary? {
        if (loc == null && gnss == null && nmea == null) return null
        return PositioningSummary(
            horizontalAccuracyM = loc?.attributes?.get("accuracy_m")?.toDoubleOrNull(),
            satellitesUsed = gnss?.attributes?.get("used_in_fix")?.toIntOrNull(),
            satellitesVisible = gnss?.attributes?.get("visible")?.toIntOrNull(),
            hdop = nmea?.attributes?.get("hdop")?.toDoubleOrNull(),
            fixRatePct = null,
        )
    }

    private fun classify(loc: Measurement?, accel: Measurement?, activity: Measurement?): ContextClassification? {
        val features = LinkedHashMap<String, String>()
        val speed = loc?.attributes?.get("speed_ms")?.toDoubleOrNull()
        if (speed != null) features["speed_ms"] = String.format("%.2f", speed)
        val accelRms = accel?.stats?.get("magnitude")?.rms
        if (accelRms != null) features["accel_rms_ms2"] = String.format("%.3f", accelRms)
        val activityLabel = activity?.attributes?.get("activity")
        if (activityLabel != null) features["activity_sensor"] = activityLabel

        val classId = when {
            activityLabel?.contains("vehicle") == true || (speed != null && speed > 15) -> "vehicle"
            activityLabel?.contains("walking") == true || activityLabel?.contains("running") == true ||
                activityLabel?.contains("bicycle") == true || (speed != null && speed > 1.5) -> "motion"
            speed != null && speed <= 1.5 -> "stationary"
            else -> "unknown"
        }
        // 简单置信度:多特征一致则置信度高
        val confidence = when (classId) {
            "vehicle" -> if (speed != null && activityLabel != null) 0.95 else 0.75
            "motion" -> if (speed != null && accelRms != null) 0.9 else 0.7
            "stationary" -> 0.6
            else -> 0.3
        }
        return ContextClassification(classId, confidence, features)
    }

    private fun Double.roundToLong(): Long = Math.round(this)
}
