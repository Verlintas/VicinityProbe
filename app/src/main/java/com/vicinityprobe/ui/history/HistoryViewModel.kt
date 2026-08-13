package com.vicinityprobe.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.model.ReportMeta
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val history = HistoryManager(application)

    private val _items = MutableStateFlow<List<ReportMeta>>(emptyList())
    val items: StateFlow<List<ReportMeta>> = _items

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _items.value = history.list() }
    }

    fun rename(id: String, name: String) {
        history.rename(id, name)
        refresh()
    }

    fun delete(id: String) {
        history.delete(id)
        refresh()
    }
}
