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

package com.vicinityprobe.ui.trend

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.ReportMeta
import com.vicinityprobe.service.MonitoringService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TrendViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _items = MutableStateFlow<List<ReportMeta>>(emptyList())
    val items: StateFlow<List<ReportMeta>> = _items

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _items.value = withContext(kotlinx.coroutines.Dispatchers.IO) { history.list() } }
    }

    fun startMonitoring(intervalMinutes: Long) {
        val intent = Intent(getApplication(), MonitoringService::class.java)
            .setAction(MonitoringService.ACTION_START)
            .putExtra(MonitoringService.EXTRA_INTERVAL, intervalMinutes)
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopMonitoring() {
        val intent = Intent(getApplication(), MonitoringService::class.java)
            .setAction(MonitoringService.ACTION_STOP)
        getApplication<Application>().startService(intent)
    }

    /** 线性趋势推断结果 */
    data class TrendInference(
        val slopePerDay: Double,   // 每天变化量
        val r2: Double,
        val pValue: Double,
        val stationary: Boolean,
        val points: Int,
    )

    /** 对历史序列做最小二乘趋势推断(斜率/拟合优度/显著性) */
    fun inferTrend(channel: (ReportMeta) -> Double): TrendInference? {
        val sorted = _items.value.sortedBy { it.createdAt }
        if (sorted.size < 3) return null
        val samples = sorted.map(channel)
        val hours = sorted.zipWithNext { a, b -> (b.createdAt - a.createdAt) / 3_600_000.0 }
        val avgDtSec = (if (hours.isEmpty()) 0.0 else hours.average().coerceAtLeast(0.001)) * 3600.0
        val fit = com.vicinityprobe.analysis.LinearTrend.fit(samples, avgDtSec) ?: return null
        return TrendInference(
            slopePerDay = fit.slopePerSecond * 86_400.0,
            r2 = fit.r2,
            pValue = fit.pValueApprox,
            stationary = fit.stationary,
            points = samples.size,
        )
    }
}
