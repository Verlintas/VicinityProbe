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

package com.vicinityprobe.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
import kotlin.math.roundToLong

/** 定位采样器:连续采集 GPS/网络定位,统计精度/速度/可用率 */
class LocationSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("location")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return failed(QualityLevels.CODE_PERMISSION_DENIED, "没给定位权限|Location permission denied")
        }
        val providers = try { manager.getProviders(true) } catch (_: Throwable) { emptyList() }
        if (providers.none { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }) {
            return failed(QualityLevels.CODE_FEATURE_OFF, "定位服务没开|Location service off")
        }
        val fixes = java.util.Collections.synchronizedList(ArrayList<Location>())
        val accuracies = ArrayList<Double>()
        val speeds = ArrayList<Double>()
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                fixes.add(loc)
                accuracies.add(loc.accuracy.toDouble())
                if (loc.hasSpeed()) speeds.add(loc.speed.toDouble())
                session.live.set("location", "position", String.format("%.5f,%.5f", loc.latitude, loc.longitude))
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val looper = android.os.Looper.getMainLooper()
        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, looper) } catch (_: Throwable) {}
        try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0f, listener, looper) } catch (_: Throwable) {}

        // 等待首个定位(最长 10s)
        val fixWait = session.startRealtimeMs + 10_000
        while (kotlin.coroutines.coroutineContext.isActive && fixes.isEmpty() &&
            SystemClockCompat.elapsedRealtime() < fixWait && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs
        ) { delay(100) }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) { delay(200) }
        try { manager.removeUpdates(listener) } catch (_: Throwable) {}

        val last = fixes.lastOrNull()
        if (last == null) {
            return failed(QualityLevels.CODE_NO_FIX, "没定位到|No location fix")
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["latitude"] = String.format("%.6f", last.latitude)
        attrs["longitude"] = String.format("%.6f", last.longitude)
        attrs["provider"] = last.provider ?: "?"
        attrs["fix_count"] = fixes.size.toString()
        attrs["accuracy_m"] = String.format("%.1f", last.accuracy)
        if (last.hasAltitude()) attrs["altitude_m"] = String.format("%.1f", last.altitude)
        if (last.hasSpeed()) attrs["speed_ms"] = String.format("%.2f", last.speed)
        if (last.hasBearing()) attrs["bearing_deg"] = String.format("%.1f", last.bearing)
        if (android.os.Build.VERSION.SDK_INT >= 26 && last.hasVerticalAccuracy()) {
            attrs["v_accuracy_m"] = String.format("%.1f", last.verticalAccuracyMeters)
        }
        val accuracyStats = com.vicinityprobe.model.domain.ChannelStats.compute(accuracies.map { it.toFloat() }.toFloatArray(), "m")
        val speedStats = if (speeds.isNotEmpty()) com.vicinityprobe.model.domain.ChannelStats.compute(speeds.map { it.toFloat() }.toFloatArray(), "m/s") else null
        val rate = fixes.size.toDouble() / session.elapsedMs().toDouble() * 1000
        val q = if (last.accuracy <= 30) QualityLevel.EXCELLENT else if (last.accuracy <= 100) QualityLevel.GOOD else QualityLevel.DEGRADED
        return Measurement(
            spec = spec,
            status = QualityLevels.CODE_OK,
            stats = mapOf("accuracy" to accuracyStats) + (speedStats?.let { mapOf("speed" to it) } ?: emptyMap()),
            attributes = attrs,
            quality = QualityReport(q, QualityLevels.CODE_OK, "", sampleCount = fixes.size, achievedRateHz = rate, nominalRateHz = 2.0),
            samplesFile = null,
            series = emptyMap(),
            spectrum = null,
        )
    }

    private fun failed(code: String, detail: String) = Measurement(
        spec = spec, status = code,
        quality = QualityReport(QualityLevel.FAILED, code, detail),
    )
}

/** GNSS 卫星采样器:星座/信噪比/几何 */
class GnssSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("gnss")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val permOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permOk) {
            return Measurement(spec, QualityLevels.CODE_PERMISSION_DENIED,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_PERMISSION_DENIED, "没给定位权限|Location permission denied"))
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return Measurement(spec, QualityLevels.CODE_FEATURE_OFF,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_FEATURE_OFF, "GPS 未开启|GPS off"))
        }
        var snapshots = 0
        var usedInFix = 0
        var visible = 0
        var bestSnr = 0.0
        val constellationCounts = HashMap<String, Int>()
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                snapshots++
                visible = status.satelliteCount
                usedInFix = 0
                bestSnr = 0.0
                constellationCounts.clear()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedInFix++
                    if (status.getCn0DbHz(i) > bestSnr) bestSnr = status.getCn0DbHz(i).toDouble()
                    val c = when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
                        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
                        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
                        else -> "Unknown"
                    }
                    constellationCounts[c] = (constellationCounts[c] ?: 0) + 1
                }
            }
        }
        try { manager.registerGnssStatusCallback(ContextCompat.getMainExecutor(ctx), cb) } catch (_: Throwable) {
            return Measurement(spec, QualityLevels.CODE_ACQUISITION_ERROR,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_ACQUISITION_ERROR, "register failed"))
        }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) { delay(200) }
        try { manager.unregisterGnssStatusCallback(cb) } catch (_: Throwable) {}

        if (snapshots == 0) {
            return Measurement(spec, QualityLevels.CODE_NO_DATA,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_DATA, "没有卫星状态|No satellite status"))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["visible"] = visible.toString()
        attrs["used_in_fix"] = usedInFix.toString()
        attrs["best_snr_dbHz"] = String.format("%.1f", bestSnr)
        attrs["constellations"] = constellationCounts.entries.joinToString(",") { "${it.key}:${it.value}" }
        attrs["snapshots"] = snapshots.toString()
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            attributes = attrs,
            quality = QualityReport(
                level = if (visible > 0) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = snapshots, achievedRateHz = snapshots.toDouble() / (session.elapsedMs().toDouble() / 1000),
            ),
        )
    }
}

/** NMEA 定位质量采样器:GGA 语句解析 */
class NmeaSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("nmea")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val permOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permOk) {
            return Measurement(spec, QualityLevels.CODE_PERMISSION_DENIED,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_PERMISSION_DENIED, "没给定位权限|Location permission denied"))
        }
        var count = 0
        var hdop: Double? = null
        var satsUsed: Int? = null
        var fixQuality: String? = null
        val listener = android.location.OnNmeaMessageListener { message, _ ->
            count++
            val parts = message.split(",")
            if (parts.size > 10 && parts[0].endsWith("GGA")) {
                try {
                    if (parts[6].isNotEmpty() && parts[6].toInt() != 0) {
                        fixQuality = when (parts[6].toInt()) {
                            1 -> "GPS 定位|GPS fix"; 2 -> "差分定位|DGPS"; 3 -> "推算|Dead reckoning"; 4 -> "RTK 固定|RTK fixed"
                            else -> "fix(${parts[6]})"
                        }
                        satsUsed = parts[7].toInt()
                        hdop = parts[8].toDoubleOrNull()
                    }
                } catch (_: Throwable) {}
            }
        }
        try { manager.addNmeaListener(ContextCompat.getMainExecutor(ctx), listener) } catch (_: Throwable) {
            return Measurement(spec, QualityLevels.CODE_ACQUISITION_ERROR,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_ACQUISITION_ERROR, "listener failed"))
        }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) { delay(200) }
        try { manager.removeNmeaListener(listener) } catch (_: Throwable) {}

        if (count == 0) {
            return Measurement(spec, QualityLevels.CODE_NO_DATA,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_DATA, "没有 NMEA 数据|No NMEA data"))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["sentences"] = count.toString()
        fixQuality?.let { attrs["fix_quality"] = it }
        satsUsed?.let { attrs["sats_used"] = it.toString() }
        hdop?.let { attrs["hdop"] = String.format("%.2f", it) }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            quality = QualityReport(
                level = if (hdop != null && hdop!! <= 3.0) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = count, achievedRateHz = count.toDouble() / (session.elapsedMs().toDouble() / 1000),
            ),
        )
    }
}
