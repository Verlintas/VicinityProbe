package com.vicinityprobe.ui.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.ReportMeta
import com.vicinityprobe.report.CompareEngine
import com.vicinityprobe.report.CompareResult
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CompareViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _items = MutableStateFlow<List<ReportMeta>>(emptyList())
    val items: StateFlow<List<ReportMeta>> = _items

    private val _result = MutableStateFlow<CompareResult?>(null)
    val result: StateFlow<CompareResult?> = _result

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _items.value = history.list() }
    }

    fun compare(idA: String?, idB: String?) {
        if (idA == null || idB == null || idA == idB) {
            _result.value = null
            return
        }
        viewModelScope.launch {
            val a = history.load(idA) ?: return@launch
            val b = history.load(idB) ?: return@launch
            _result.value = CompareEngine.compare(a, b)
        }
    }
}
