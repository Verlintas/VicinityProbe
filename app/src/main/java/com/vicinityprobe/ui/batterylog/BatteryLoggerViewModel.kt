/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.batterylog

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

data class BatterySample(
    val tMs: Long,
    val voltageMv: Int,
    val currentMa: Double,
    val tempC: Double,
    val levelPct: Int,
)

data class BatteryLogState(
    val logging: Boolean = false,
    val samples: List<BatterySample> = emptyList(),
    val current: BatterySample? = null,
    val startLevelPct: Int = -1,
    val dischargeRatePctPerHour: Double = 0.0,
    val estHoursLeft: Double = 0.0,
    val avgPowerW: Double = 0.0,
    val error: String? = null,
)

class BatteryLoggerViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(BatteryLogState())
    val state: StateFlow<BatteryLogState> = _state

    private var job: Job? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        // 注册电池广播
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                // 电压/温度从广播 extra 读取(常量在 API 33+ 移除,键名稳定)
                val voltage = intent.getIntExtra("voltage", 0)
                val tempC = intent.getIntExtra("temperature", 0) / 10.0
                val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val sample = BatterySample(
                    tMs = System.currentTimeMillis(),
                    voltageMv = voltage,
                    currentMa = currentUa / 1000.0,
                    tempC = tempC,
                    levelPct = level,
                )
                val cur = _state.value
                _state.value = cur.copy(current = sample, samples = (cur.samples + sample).takeLast(7200))
            }
        }
        receiver = r
        try { app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) {}
        _state.value = _state.value.copy(logging = true, startLevelPct = -1)
        job = viewModelScope.launch(Dispatchers.IO) {
            val file = File(app.filesDir, "recordings").apply { mkdirs() }
                .resolve("battery_${System.currentTimeMillis()}.csv")
            FileWriter(file).use { w ->
                w.write("t_ms,voltage_mv,current_ma,temp_c,level_pct\n")
                var lastWrite = 0L
                while (isActive) {
                    val s = _state.value.current
                    if (s != null) {
                        w.write("${s.tMs},${s.voltageMv},${String.format("%.1f", s.currentMa)},${String.format("%.1f", s.tempC)},${s.levelPct}\n")
                        w.flush()
                        val cur = _state.value
                        if (cur.startLevelPct < 0) {
                            _state.value = cur.copy(startLevelPct = s.levelPct)
                        }
                        // 每秒更新统计(数据本身约 1Hz 来自系统广播)
                        if (s.tMs - lastWrite > 1000) {
                            lastWrite = s.tMs
                            computeStats()
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    private fun computeStats() {
        val cur = _state.value
        val samples = cur.samples
        if (samples.size < 2 || cur.startLevelPct < 0) return
        val elapsedH = (samples.last().tMs - samples.first().tMs) / 3_600_000.0
        val levelDrop = cur.startLevelPct - samples.last().levelPct
        val rate = if (elapsedH > 0.01) levelDrop / elapsedH else 0.0
        val avgCurrent = samples.map { it.currentMa }.filter { it < 0 }.average().let { if (it.isNaN()) 0.0 else it }
        val avgVoltage = samples.map { it.voltageMv }.average() / 1000.0
        _state.value = cur.copy(
            dischargeRatePctPerHour = rate,
            estHoursLeft = if (rate > 0.01) samples.last().levelPct / rate else 0.0,
            avgPowerW = if (avgCurrent < 0) -avgCurrent / 1000.0 * avgVoltage else 0.0,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        try { receiver?.let { getApplication<Application>().unregisterReceiver(it) } } catch (_: Throwable) {}
        receiver = null
        _state.value = _state.value.copy(logging = false)
    }

    /** 导出 CSV */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("t_ms,voltage_mv,current_ma,temp_c,level_pct")
        _state.value.samples.forEach { s ->
            sb.appendLine("${s.tMs},${s.voltageMv},${String.format(java.util.Locale.US, "%.1f", s.currentMa)},${String.format(java.util.Locale.US, "%.1f", s.tempC)},${s.levelPct}")
        }
        return sb.toString()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
