package com.vicinityprobe.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * GNSS 原始观测量:载波相位、伪距率、多径、信噪比等。
 * 专业级定位观测数据(类似 Android GNSS Logger)。
 */
class GnssRawSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("gnss_raw")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val permOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permOk) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "需要精确定位权限|Fine location required")
        }
        var events = 0
        var measurements = 0
        var carrierPhaseObs = 0
        var validPseudoRange = 0
        var multipath = 0
        val cn0 = ArrayList<Float>()
        val constellations = HashMap<String, Int>()
        val cb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                events++
                eventArgs.measurements.forEach { m: GnssMeasurement ->
                    measurements++
                    val c = when (m.constellationType) {
                        android.location.GnssStatus.CONSTELLATION_GPS -> "GPS"
                        android.location.GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                        android.location.GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        android.location.GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        android.location.GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        android.location.GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
                        else -> "Other"
                    }
                    constellations[c] = (constellations[c] ?: 0) + 1
                    cn0.add(m.cn0DbHz.toFloat())
                    if (m.hasCarrierFrequencyHz()) carrierPhaseObs++
                    if (!m.pseudorangeRateMetersPerSecond.isNaN() && !m.pseudorangeRateMetersPerSecond.isInfinite()) validPseudoRange++
                    if (m.multipathIndicator != GnssMeasurement.MULTIPATH_INDICATOR_NOT_DETECTED) multipath++
                }
            }
        }
        var registered = false
        try {
            manager.registerGnssMeasurementsCallback(ContextCompat.getMainExecutor(ctx), cb)
            registered = true
        } catch (_: Throwable) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "GNSS 原始测量注册失败|register failed")
        }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) { delay(200) }
        if (registered) { try { manager.unregisterGnssMeasurementsCallback(cb) } catch (_: Throwable) {} }

        if (measurements == 0) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "无原始观测量(需室外开阔环境)|No raw measurements")
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["epochs"] = events.toString()
        attrs["measurements"] = measurements.toString()
        attrs["carrier_phase_obs"] = carrierPhaseObs.toString()
        attrs["valid_pseudorange_rate"] = validPseudoRange.toString()
        attrs["multipath_flagged"] = multipath.toString()
        attrs["constellations"] = constellations.entries.joinToString(",") { "${it.key}:${it.value}" }
        val cn0Stats = com.vicinityprobe.model.domain.ChannelStats.compute(cn0.toFloatArray(), "dBHz")
        attrs["cn0_mean_dbHz"] = String.format("%.1f", cn0Stats.mean)
        attrs["cn0_max_dbHz"] = String.format("%.1f", cn0Stats.max)
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("cn0" to cn0Stats),
            quality = QualityReport(
                level = if (events > 3) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = measurements,
                achievedRateHz = events.toDouble() / (session.elapsedMs().toDouble() / 1000),
            ),
        )
    }
}

/** GNSS 硬件信息:硬件型号/年代/批量能力/天线 */
class GnssHwSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("gnss_hw")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val permOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permOk) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "需要精确定位权限|Fine location required")
        }
        val attrs = LinkedHashMap<String, String>()
        try { manager.gnssHardwareModelName?.let { attrs["hardware_model"] = it } } catch (_: Throwable) {}
        try { attrs["hardware_year"] = manager.gnssYearOfHardware.toString() } catch (_: Throwable) {}
        var antennaCount = 0
        var antennaBands = 0
        try {
            val caps = manager.gnssCapabilities
            attrs["gnss_capabilities"] = caps.toString()
            for (m in caps.javaClass.methods) {
                if (m.name.startsWith("has") && m.parameterCount == 0 && m.returnType == java.lang.Boolean.TYPE) {
                    try { attrs["cap_" + m.name.removePrefix("has")] = m.invoke(caps).toString() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        try {
            val antennas = manager.gnssAntennaInfos ?: emptyList()
            antennaCount = antennas.size
            antennaBands = antennas.sumOf { it.carrierFrequencyMHz.let { f -> if (f > 0) 1 else 0 } }
        } catch (_: Throwable) {}
        delay(500)
        attrs["antenna_count"] = antennaCount.toString()
        attrs["antenna_bands"] = antennaBands.toString()
        return okMeasurement(spec, attrs,
            quality = QualityReport(
                level = if (attrs.isNotEmpty()) QualityLevel.GOOD else QualityLevel.DEGRADED,
                QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}
