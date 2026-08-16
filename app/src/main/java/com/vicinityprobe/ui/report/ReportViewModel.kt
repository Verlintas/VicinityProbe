/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _report = MutableStateFlow<MeasurementReport?>(null)
    val report: StateFlow<MeasurementReport?> = _report

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    fun load(id: String) {
        viewModelScope.launch {
            _loadError.value = false
            _report.value = withContext(Dispatchers.IO) { history.load(id) }
            _loadError.value = _report.value == null
        }
    }

    fun samplesDir(id: String): File? = history.samplesDir(id)

    fun audit(findings: List<com.vicinityprobe.analysis.AuditFinding>, lang: String): String {
        val report = _report.value ?: return ""
        return com.vicinityprobe.analysis.SecurityAudit.markdown(report, findings, lang)
    }

    /** 导出在 IO 线程执行,完成后通过系统分享面板共享,避免主线程 ANR */
    fun export(kind: String, lang: String = "zh") {
        val report = _report.value ?: return
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val f: File? = withContext(Dispatchers.IO) {
                    when (kind) {
                        "json" -> ReportExporter.writeJson(getApplication(), report)
                        "md" -> ReportExporter.writeMarkdown(getApplication(), report, lang)
                        "zip" -> ReportExporter.shareZip(getApplication(), report)
                        else -> null
                    }
                }
                f?.let { ReportExporter.shareFile(getApplication(), it, when (kind) { "json" -> "application/json"; "md" -> "text/markdown"; else -> "application/zip" }) }
            } catch (_: Throwable) {
                // 导出失败静默处理(避免崩溃);后续可加 toast
            } finally {
                _exporting.value = false
            }
        }
    }
}
