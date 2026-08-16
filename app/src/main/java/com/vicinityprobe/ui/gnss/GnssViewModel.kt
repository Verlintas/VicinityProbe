/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.gnss

import android.app.Application
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 卫星观测 */
data class GnssSatellite(
    val svid: Int,
    val constellation: String,   // GPS/GLONASS/GALILEO/BEIDOU/QZSS/IRNSS/SBAS/UNKNOWN
    val cn0: Float,              // dB-Hz
    val azimuth: Float,          // 度
    val elevation: Float,        // 度
    val usedInFix: Boolean,
)

data class GnssState(
    val satellites: List<GnssSatellite> = emptyList(),
    val visible: Int = 0,
    val used: Int = 0,
    val meanCn0: Float = 0f,
    val bestSatellite: String = "?",
    val gpsOn: Boolean = false,
)

class GnssViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(GnssState())
    val state: StateFlow<GnssState> = _state

    private var locManager: LocationManager? = null
    private var callback: GnssStatus.Callback? = null
    private var handler: Handler? = null
    private var thread: HandlerThread? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return
        val app = getApplication<Application>()
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locManager = lm
        val t = HandlerThread("gnss").apply { start() }
        thread = t
        handler = Handler(t.looper)
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = ArrayList<GnssSatellite>(status.satelliteCount)
                for (i in 0 until status.satelliteCount) {
                    sats.add(
                        GnssSatellite(
                            svid = status.getSvid(i),
                            constellation = constellationName(status.getConstellationType(i)),
                            cn0 = status.getCn0DbHz(i),
                            azimuth = status.getAzimuthDegrees(i),
                            elevation = status.getElevationDegrees(i),
                            usedInFix = status.usedInFix(i),
                        ),
                    )
                }
                val used = sats.count { it.usedInFix }
                val cn0s = sats.map { it.cn0 }
                val mean = if (cn0s.isEmpty()) 0f else cn0s.average().toFloat()
                val best = sats.maxByOrNull { it.cn0 }
                _state.value = GnssState(
                    satellites = sats.sortedByDescending { it.cn0 },
                    visible = sats.size,
                    used = used,
                    meanCn0 = mean,
                    bestSatellite = best?.let { "${it.constellation}#${it.svid} ${String.format("%.0f dB-Hz", it.cn0)}" } ?: "?",
                    gpsOn = true,
                )
            }
        }
        callback = cb
        try {
            lm.registerGnssStatusCallback(cb, Handler(t.looper))
        } catch (_: Throwable) {
            _state.value = _state.value.copy(gpsOn = false)
        }
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_BEIDOU -> "BEIDOU"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "UNKNOWN"
    }

    fun stop() {
        try { callback?.let { locManager?.unregisterGnssStatusCallback(it) } } catch (_: Throwable) {}
        thread?.quitSafely()
        thread = null
        handler = null
        callback = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
