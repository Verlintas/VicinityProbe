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

package com.vicinityprobe.ui.scanning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.analysis.AnalysisEngine
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.probe.SessionController
import com.vicinityprobe.probe.SessionUiState
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.ReportMeta
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)
    private var controller: SessionController? = null

    private val _ui = MutableStateFlow<SessionUiState?>(null)
    val ui: StateFlow<SessionUiState?> = _ui

    private val _result = MutableStateFlow<ReportMeta?>(null)
    val result: StateFlow<ReportMeta?> = _result

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    fun start(ids: Set<String>, mode: String, durationMs: Long) {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        val controller = SessionController(app, ids, durationMs, mode)
        this.controller = controller
        job = viewModelScope.launch {
            try {
                val collectJob = viewModelScope.launch {
                    controller.stateFlow().collect { _ui.value = it }
                }
                val report = controller.run(File(app.filesDir, "reports"))
                val analyzed = report.copy(analysis = AnalysisEngine.analyze(report))
                val meta = history.save(analyzed)
                collectJob.cancel()
                _ui.value = _ui.value?.copy(completedUnits = _ui.value?.totalUnits ?: 0)
                _result.value = meta
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun cancel() {
        controller?.cancel()
    }
}
