/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.gpstrack

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 轨迹点 */
data class TrackPoint(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMs: Float,
    val accuracyM: Float,
)

data class TrackState(
    val recording: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val durationSec: Long = 0,
    val avgSpeedMs: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val error: String? = null,
)

class GpsTrackViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state

    private var locManager: LocationManager? = null
    private var listener: LocationListener? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.recording) return
        val app = getApplication<Application>()
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locManager = lm
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _state.value = _state.value.copy(error = "GPS 未开启|GPS is off")
            return
        }
        _state.value = TrackState(recording = true)
        val startedAt = System.currentTimeMillis()
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val cur = _state.value
                val pts = cur.points + TrackPoint(
                    tMs = System.currentTimeMillis(),
                    lat = loc.latitude,
                    lon = loc.longitude,
                    speedMs = loc.speed,
                    accuracyM = loc.accuracy,
                )
                val last = cur.points.lastOrNull()
                val delta = if (last != null && loc.accuracy < 80f && last.accuracyM < 80f) {
                    haversine(last.lat, last.lon, loc.latitude, loc.longitude)
                } else 0.0
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                _state.value = cur.copy(
                    points = pts,
                    distanceM = cur.distanceM + delta,
                    durationSec = elapsed,
                    avgSpeedMs = if (elapsed > 0) cur.distanceM / elapsed else 0.0,
                    maxSpeedMs = maxOf(cur.maxSpeedMs, loc.speed.toDouble()),
                )
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = l
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, l)
        } catch (_: Throwable) {
            _state.value = _state.value.copy(error = "无法订阅定位|Cannot subscribe to location", recording = false)
        }
    }

    fun stop() {
        try { listener?.let { locManager?.removeUpdates(it) } } catch (_: Throwable) {}
        listener = null
        _state.value = _state.value.copy(recording = false)
    }

    fun clear() {
        stop()
        _state.value = TrackState()
    }

    /** 导出 KML(Google Earth 可打开) */
    fun exportKml(): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>VicinityProbe track</name><LineString><coordinates>")
        _state.value.points.forEach { p ->
            sb.appendLine("${p.lon},${p.lat},0")
        }
        sb.appendLine("</coordinates></LineString></Document></kml>")
        return sb.toString()
    }

    /** 导出 CSV */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("t_ms,lat,lon,speed_ms,accuracy_m")
        _state.value.points.forEach { p ->
            sb.appendLine("${p.tMs},${p.lat},${p.lon},${p.speedMs},${p.accuracyM}")
        }
        return sb.toString()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
