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
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlinx.coroutines.delay

object ConstellationNames {
    fun name(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> bil("未知", "Unknown")
    }
}

private fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

class LocationProbe(private val selected: Set<String>) : ProbeUnit {
    override val id = "location"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val results = ArrayList<ProbeResult>()

        if (selected.contains("location")) results.addAll(LocationUnit(ctx, manager, deadlineMs, live).run())
        if (selected.contains("gnss")) results.addAll(GnssUnit(ctx, manager, deadlineMs, live).run())
        if (selected.contains("nmea")) results.addAll(NmeaUnit(ctx, manager, deadlineMs, live).run())
        return results
    }
}

private class LocationUnit(
    private val ctx: Context,
    private val manager: LocationManager,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    suspend fun run(): List<ProbeResult> {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return listOf(resultBuilder("location", Groups.LOCATION, Labels.LOCATION, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val providers = try {
            manager.getProviders(true)
        } catch (_: Throwable) { emptyList() }
        if (providers.none { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }) {
            return listOf(resultBuilder("location", Groups.LOCATION, Labels.LOCATION, ProbeStatus.FEATURE_OFF, note = bil("定位服务未开启", "Location service is off")))
        }

        val fixes = java.util.Collections.synchronizedList(ArrayList<Location>())
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                fixes.add(loc)
                live.set("location", Labels.LOCATION.en, "${fmt(loc.latitude)}°, ${fmt(loc.longitude)}°")
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val looper = android.os.Looper.getMainLooper()
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, looper)
        } catch (_: Throwable) {}
        try {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, looper)
        } catch (_: Throwable) {}

        // Wait for at least one fix up to 8s
        val fixEnd = SystemClockCompat.elapsedRealtime() + 8000
        while (fixes.isEmpty() && SystemClockCompat.elapsedRealtime() < fixEnd && SystemClockCompat.elapsedRealtime() < deadlineMs) {
            kotlinx.coroutines.delay(100)
        }
        val last = fixes.lastOrNull()
        try { manager.removeUpdates(listener) } catch (_: Throwable) {}

        if (last == null) {
            return listOf(resultBuilder("location", Groups.LOCATION, Labels.LOCATION, ProbeStatus.FAILED, note = bil("未获取到定位", "No location fix acquired")))
        }
        val metrics = mutableListOf(
            metric("lat", Labels.LAT, fmt(last.latitude) + "°", primary = true),
            metric("lon", Labels.LON, fmt(last.longitude) + "°", primary = true),
            metric("accuracy", Labels.ACCURACY, fmt(last.accuracy.toDouble()), "m"),
            metric("speed", Labels.SPEED, fmt(last.speed.toDouble()), "m/s"),
            metric("provider", Labels.PROVIDER, if (last.provider == LocationManager.GPS_PROVIDER) bil("GPS", "GPS") else bil("网络", "Network")),
            metric("fix_count", Labels.FIX_COUNT, fixes.size.toString()),
        )
        if (last.hasAltitude()) metrics.add(metric("altitude", L("海拔", "Altitude"), fmt(last.altitude.toDouble()), "m"))
        if (last.hasBearing()) metrics.add(metric("bearing", Labels.BEARING, fmt(last.bearing.toDouble()), "°"))
        if (android.os.Build.VERSION.SDK_INT >= 26 && last.hasVerticalAccuracy()) {
            metrics.add(metric("v_accuracy", L("垂直精度", "Vertical accuracy"), fmt(last.verticalAccuracyMeters.toDouble()), "m"))
        }
        return listOf(resultBuilder("location", Groups.LOCATION, Labels.LOCATION, ProbeStatus.OK, metrics = metrics))
    }
}

private class GnssUnit(
    private val ctx: Context,
    private val manager: LocationManager,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun run(): List<ProbeResult> {
        if (!hasLocationPermission(ctx)) {
            return listOf(resultBuilder("gnss", Groups.LOCATION, Labels.GNSS, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.ACCESS_FINE_LOCATION))
        }
        val gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            return listOf(resultBuilder("gnss", Groups.LOCATION, Labels.GNSS, ProbeStatus.FEATURE_OFF, note = bil("GPS 未开启", "GPS is off")))
        }
        val lock = Any()
        var snapshot: GnssStatus? = null
        var bestSnr = 0.0
        var usedCount = 0
        var updated = false
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                synchronized(lock) {
                    snapshot = status
                    updated = true
                    bestSnr = 0.0
                    usedCount = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedCount++
                        if (status.getCn0DbHz(i) > bestSnr) bestSnr = status.getCn0DbHz(i).toDouble()
                    }
                }
                live.set("gnss", Labels.GNSS.en, "${usedCount}/${status.satelliteCount} sats")
            }
        }
        val executor = ContextCompat.getMainExecutor(ctx)
        try {
            manager.registerGnssStatusCallback(executor, callback)
        } catch (_: Throwable) {
            return listOf(resultBuilder("gnss", Groups.LOCATION, Labels.GNSS, ProbeStatus.FAILED))
        }
        waitUntil(kotlin.coroutines.coroutineContext, deadlineMs)
        try { manager.unregisterGnssStatusCallback(callback) } catch (_: Throwable) {}

        val st = synchronized(lock) { snapshot }
        if (st == null) {
            return listOf(resultBuilder("gnss", Groups.LOCATION, Labels.GNSS, ProbeStatus.FAILED, note = bil("无卫星状态数据", "No satellite status")))
        }
        val metrics = mutableListOf(
            metric("sats_total", Labels.SATS_TOTAL, st.satelliteCount.toString(), primary = true),
            metric("sats_used", Labels.SATS_USED, usedCount.toString()),
            metric("snr_top", Labels.SNR_TOP, fmt(bestSnr), "dBHz"),
        )
        val constCounts = HashMap<String, Int>()
        val detail = StringBuilder()
        var shown = 0
        for (i in 0 until st.satelliteCount) {
            val c = ConstellationNames.name(st.getConstellationType(i))
            constCounts[c] = (constCounts[c] ?: 0) + 1
            if (shown < 10) {
                if (detail.isNotEmpty()) detail.append(", ")
                detail.append(c).append(' ').append(st.getSvid(i))
                    .append(st.usedInFix(i) ?: "")
                    .append(" snr=").append(fmt(st.getCn0DbHz(i).toDouble()))
                    .append(" el=").append(st.getElevationDegrees(i).toInt())
                shown++
            }
        }
        metrics.add(metric("constellation", L("星座分布", "Constellations"), constCounts.entries.joinToString(", ") { "${it.key}:${it.value}" }))
        metrics.add(metric("detail", L("卫星明细", "Satellite details"), detail.toString().take(600)))
        return listOf(resultBuilder("gnss", Groups.LOCATION, Labels.GNSS, ProbeStatus.OK, metrics = metrics))
    }
}

private class NmeaUnit(
    private val ctx: Context,
    private val manager: LocationManager,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun run(): List<ProbeResult> {
        if (!hasLocationPermission(ctx)) {
            return listOf(resultBuilder("nmea", Groups.LOCATION, Labels.NMEA, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.ACCESS_FINE_LOCATION))
        }
        var hdop: Double? = null
        var satsUsed: Int? = null
        var fixQuality: String? = null
        var altitude: Double? = null
        var nmeaCount = 0
        val lock = Any()
        val listener = android.location.OnNmeaMessageListener { message, _ ->
            nmeaCount++
            val parts = message.split(",")
            if (parts.size > 10 && parts[0].endsWith("GGA")) {
                try {
                    if (parts[6].isNotEmpty() && parts[6].toInt() != 0) {
                        val q = parts[6].toInt()
                        fixQuality = when (q) {
                            1 -> bil("GPS 定位", "GPS fix")
                            2 -> bil("差分定位", "DGPS fix")
                            3 -> bil("推算定位", "Dead reckoning")
                            4 -> bil("RTK 固定", "RTK fixed")
                            else -> bil("定位", "Fix ($q)")
                        }
                        satsUsed = parts[7].toInt()
                        hdop = parts[8].toDoubleOrNull()
                        altitude = parts[9].toDoubleOrNull()
                    }
                } catch (_: Throwable) {}
            }
        }
        val executor = ContextCompat.getMainExecutor(ctx)
        try {
            manager.addNmeaListener(executor, listener)
        } catch (_: Throwable) {
            return listOf(resultBuilder("nmea", Groups.LOCATION, Labels.NMEA, ProbeStatus.FAILED))
        }
        waitUntil(kotlin.coroutines.coroutineContext, deadlineMs)
        try { manager.removeNmeaListener(listener) } catch (_: Throwable) {}

        val h = synchronized(lock) { hdop }
        val s = synchronized(lock) { satsUsed }
        val q = synchronized(lock) { fixQuality }
        val a = synchronized(lock) { altitude }
        if (h == null && s == null && q == null) {
            return listOf(resultBuilder("nmea", Groups.LOCATION, Labels.NMEA, ProbeStatus.FAILED, note = bil("无 NMEA 数据", "No NMEA data")))
        }
        val metrics = mutableListOf(metric("nmea_count", L("NMEA 语句数", "NMEA sentences"), nmeaCount.toString()))
        if (q != null) metrics.add(metric("quality", Labels.FIX_QUALITY, q, primary = true))
        if (s != null) metrics.add(metric("sats_used", Labels.SATS_USED, s.toString()))
        if (h != null) metrics.add(metric("hdop", Labels.HDOP, fmt(h)))
        if (a != null) metrics.add(metric("altitude", L("海拔(GGA)", "Altitude (GGA)"), fmt(a), "m"))
        return listOf(resultBuilder("nmea", Groups.LOCATION, Labels.NMEA, ProbeStatus.OK, metrics = metrics))
    }
}
