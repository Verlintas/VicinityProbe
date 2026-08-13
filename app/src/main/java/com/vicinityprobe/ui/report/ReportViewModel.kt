package com.vicinityprobe.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.ReportExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _report = MutableStateFlow<MeasurementReport?>(null)
    val report: StateFlow<MeasurementReport?> = _report

    fun load(id: String) {
        viewModelScope.launch { _report.value = history.load(id) }
    }

    fun samplesDir(id: String): File? = history.samplesDir(id)

    fun exportJson() = _report.value?.let { ReportExporter.writeJson(getApplication(), it) }
    fun exportMd(lang: String) = _report.value?.let { ReportExporter.writeMarkdown(getApplication(), it, lang) }
    fun exportZip() = _report.value?.let { ReportExporter.shareZip(getApplication(), it) }
}
