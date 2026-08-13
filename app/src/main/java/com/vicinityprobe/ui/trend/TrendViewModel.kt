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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TrendViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _items = MutableStateFlow<List<ReportMeta>>(emptyList())
    val items: StateFlow<List<ReportMeta>> = _items

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _items.value = history.list() }
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
}
