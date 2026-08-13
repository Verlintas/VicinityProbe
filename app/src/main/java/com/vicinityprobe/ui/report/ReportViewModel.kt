package com.vicinityprobe.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _report = MutableStateFlow<ProbeReport?>(null)
    val report: StateFlow<ProbeReport?> = _report

    fun load(id: String) {
        viewModelScope.launch {
            _report.value = history.load(id)
        }
    }

    fun exportJson() = _report.value?.let { com.vicinityprobe.report.ReportExporter.writeJson(getApplication(), it) }
    fun exportMd(lang: String) = _report.value?.let { com.vicinityprobe.report.ReportExporter.writeMarkdown(getApplication(), it, lang) }
    fun exportPng() = _report.value?.let { com.vicinityprobe.report.ReportExporter.writePng(getApplication(), it) }
}
