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

    fun audit(findings: List<com.vicinityprobe.analysis.AuditFinding>, lang: String): String {
        val report = _report.value ?: return ""
        return com.vicinityprobe.analysis.SecurityAudit.markdown(report, findings, lang)
    }

    fun exportJson() = _report.value?.let { ReportExporter.writeJson(getApplication(), it) }
    fun exportMd(lang: String) = _report.value?.let { ReportExporter.writeMarkdown(getApplication(), it, lang) }
    fun exportZip() = _report.value?.let { ReportExporter.shareZip(getApplication(), it) }
}
