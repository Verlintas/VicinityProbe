package com.vicinityprobe.ui.scanning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.analysis.Analyzer
import com.vicinityprobe.analysis.WeatherClient
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ReportMeta
import com.vicinityprobe.probe.ProbeController
import com.vicinityprobe.probe.ScanUiState
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)
    private var controller: ProbeController? = null

    private val _ui = MutableStateFlow<ScanUiState?>(null)
    val ui: StateFlow<ScanUiState?> = _ui

    private val _result = MutableStateFlow<ReportMeta?>(null)
    val result: StateFlow<ReportMeta?> = _result

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    fun start(ids: Set<String>, mode: String, durationMs: Long) {
        if (job?.isActive == true) return
        val controller = ProbeController(getApplication(), ids, durationMs, mode)
        this.controller = controller
        job = viewModelScope.launch {
            try {
                val scanJob = viewModelScope.launch {
                    controller.stateFlow().collect { _ui.value = it }
                }
                val report = controller.run()
                val analyzed = withContext(Dispatchers.IO) {
                    val lat = latLon(report)
                    val weather = if (lat != null) WeatherClient.fetch(lat.first, lat.second) else null
                    val a = report.copy(analysis = Analyzer.analyze(report, weather))
                    history.save(a)
                }
                scanJob.cancel()
                _ui.value = _ui.value?.copy(finished = true)
                _result.value = analyzed
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

    private fun latLon(report: ProbeReport): Pair<Double, Double>? {
        val num = { key: String ->
            report.results.firstOrNull { it.id == "location" }?.metrics?.firstOrNull { it.key == key }?.value
                ?.let { Regex("-?\\d+\\.?\\d*").find(it)?.value?.toDoubleOrNull() }
        }
        val lat = num("lat")
        val lon = num("lon")
        return if (lat != null && lon != null) lat to lon else null
    }
}
