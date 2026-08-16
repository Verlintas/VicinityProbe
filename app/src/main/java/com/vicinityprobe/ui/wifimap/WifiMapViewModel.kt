/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.wifimap

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 采样点:位置 + 信号读数 */
data class WifiSample(
    val lat: Double,
    val lon: Double,
    val ssid: String,
    val rssi: Int,
)

data class WifiMapState(
    val samples: List<WifiSample> = emptyList(),
    val currentRssi: Int = 0,
    val currentSsid: String = "?",
    val locationKnown: Boolean = false,
    val error: String? = null,
)

class WifiMapViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(WifiMapState())
    val state: StateFlow<WifiMapState> = _state

    @android.annotation.SuppressLint("MissingPermission")
    fun refreshCurrent() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = loc.getProviders(true).mapNotNull { loc.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (best == null) {
                _state.value = _state.value.copy(error = "暂无位置(GPS 开启并获取到定位后才能记录)|No location yet (enable GPS and obtain a fix)")
            }
            val scans = try {
                wifi.startScan()   // 主动触发新扫描,否则拿到的是陈旧缓存
                kotlinx.coroutines.delay(1500)
                wifi.scanResults
            } catch (_: Throwable) { emptyList() }
            val strongest = scans.maxByOrNull { it.level }
            _state.value = _state.value.copy(
                currentRssi = strongest?.level ?: 0,
                currentSsid = strongest?.SSID?.ifBlank { strongest.BSSID } ?: "?",
                locationKnown = best != null,
                error = null,
            )
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun recordPoint() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val loc = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = loc.getProviders(true).mapNotNull { loc.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            val scans = try { wifi.scanResults } catch (_: Throwable) { emptyList() }
            val strongest = scans.maxByOrNull { it.level }
            if (best == null || strongest == null) {
                _state.value = _state.value.copy(error = "记录失败:无位置或未扫到 WiFi|Record failed: no location or no WiFi scan")
                return@launch
            }
            val sample = WifiSample(
                lat = best.latitude, lon = best.longitude,
                ssid = strongest.SSID.ifBlank { strongest.BSSID },
                rssi = strongest.level,
            )
            _state.value = _state.value.copy(
                samples = _state.value.samples + sample,
                currentRssi = strongest.level,
                currentSsid = sample.ssid,
                locationKnown = true,
                error = null,
            )
        }
    }

    fun clear() {
        _state.value = WifiMapState()
    }

    /** 导出 CSV */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("lat,lon,ssid,rssi")
        _state.value.samples.forEach { s ->
            sb.appendLine("${s.lat},${s.lon},${s.ssid},${s.rssi}")
        }
        return sb.toString()
    }
}
