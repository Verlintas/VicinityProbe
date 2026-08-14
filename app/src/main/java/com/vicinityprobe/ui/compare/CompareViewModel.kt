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

package com.vicinityprobe.ui.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.report.CompareEngine
import com.vicinityprobe.report.CompareResult
import com.vicinityprobe.report.HistoryManager
import com.vicinityprobe.report.ReportMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
        viewModelScope.launch { _items.value = withContext(kotlinx.coroutines.Dispatchers.IO) { history.list() } }
    }

    fun compare(idA: String?, idB: String?) {
        if (idA == null || idB == null || idA == idB) {
            _result.value = null
            return
        }
        viewModelScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val a = history.load(idA) ?: return@withContext null
                val b = history.load(idB) ?: return@withContext null
                CompareEngine.compare(a, b)
            }
            _result.value = result
        }
    }
}
