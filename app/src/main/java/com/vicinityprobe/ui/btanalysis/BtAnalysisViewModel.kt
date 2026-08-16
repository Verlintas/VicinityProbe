/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.btanalysis

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.probe.OuiDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 设备 RSSI 序列统计 */
data class BtDeviceStats(
    val address: String,
    val name: String,
    val rssiSamples: List<Int>,
    val minRssi: Int,
    val avgRssi: Int,
    val maxRssi: Int,
    val vendor: String,
    val type: String,          // BLE / CLASSIC
    val bonded: Boolean,
)

data class BtAnalysisState(
    val scanning: Boolean = false,
    val elapsedSec: Int = 0,
    val devices: List<BtDeviceStats> = emptyList(),
    val totalPackets: Int = 0,
    val error: String? = null,
)

class BtAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(BtAnalysisState())
    val state: StateFlow<BtAnalysisState> = _state

    private var job: Job? = null
    private val rssiByAddr = LinkedHashMap<String, MutableList<Int>>()
    private val metaByAddr = LinkedHashMap<String, Triple<String, String, Boolean>>()  // addr -> (name, type, bonded)

    @android.annotation.SuppressLint("MissingPermission")
    fun start(durationSec: Int) {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(error = "蓝牙未开启|Bluetooth off")
            return
        }
        rssiByAddr.clear(); metaByAddr.clear()
        _state.value = BtAnalysisState(scanning = true)
        job = viewModelScope.launch(Dispatchers.Default) {
            val leScanner = adapter.bluetoothLeScanner
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val d = result.device
                    val addr = d.address
                    synchronized(rssiByAddr) {
                        rssiByAddr.getOrPut(addr) { ArrayList() }.add(result.rssi)
                        metaByAddr[addr] = Triple(
                            d.name ?: "(unnamed)",
                            "BLE",
                            d.bondState == BluetoothDevice.BOND_BONDED,
                        )
                    }
                }
            }
            activeCallback = callback
            try { leScanner?.startScan(callback) } catch (_: Throwable) {}
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started < durationSec * 1000L) {
                val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                val (devices, packets) = synchronized(rssiByAddr) { snapshot() }
                _state.value = BtAnalysisState(
                    scanning = true, elapsedSec = elapsed,
                    devices = devices, totalPackets = packets,
                )
                delay(500)
            }
            try { leScanner?.stopScan(callback) } catch (_: Throwable) {}
            activeCallback = null
            val (devices, packets) = synchronized(rssiByAddr) { snapshot() }
            _state.value = BtAnalysisState(scanning = false, elapsedSec = durationSec, devices = devices, totalPackets = packets)
        }
    }

    private fun snapshot(): Pair<List<BtDeviceStats>, Int> {
        val out = ArrayList<BtDeviceStats>()
        var packets = 0
        rssiByAddr.forEach { (addr, samples) ->
            val (name, type, bonded) = metaByAddr[addr] ?: Triple("?", "BLE", false)
            val sorted = samples.sorted()
            out.add(
                BtDeviceStats(
                    address = addr,
                    name = name,
                    rssiSamples = samples.toList(),
                    minRssi = sorted.first(),
                    avgRssi = sorted.average().toInt(),
                    maxRssi = sorted.last(),
                    vendor = OuiDb.vendor(addr) ?: "?",
                    type = type,
                    bonded = bonded,
                ),
            )
            packets += samples.size
        }
        return out.sortedByDescending { it.avgRssi } to packets
    }

    fun stop() {
        job?.cancel()
        job = null
        stopScanNow()
        _state.value = _state.value.copy(scanning = false)
    }

    private var activeCallback: ScanCallback? = null

    private fun stopScanNow() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            activeCallback?.let { adapter.bluetoothLeScanner?.stopScan(it) }
        } catch (_: Throwable) {}
        activeCallback = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
