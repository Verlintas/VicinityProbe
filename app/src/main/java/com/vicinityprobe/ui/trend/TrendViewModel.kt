package com.vicinityprobe.ui.trend

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.ReportMeta
import com.vicinityprobe.report.HistoryManager
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
