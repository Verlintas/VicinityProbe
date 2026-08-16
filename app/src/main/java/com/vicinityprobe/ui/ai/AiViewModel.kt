/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.ai.AiApiException
import com.vicinityprobe.ai.AiClient
import com.vicinityprobe.ai.AiConfigStore
import com.vicinityprobe.analysis.ReportSummarizer
import com.vicinityprobe.model.domain.MeasurementReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AiAnalysisState(
    val running: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AiAnalysisState())
    val state: StateFlow<AiAnalysisState> = _state

    fun config(): AiConfigStore.Config = AiConfigStore.load(getApplication())

    fun saveConfig(c: AiConfigStore.Config) = AiConfigStore.save(getApplication(), c)

    /** 对报告执行 AI 深度分析 */
    fun analyze(report: MeasurementReport) {
        if (_state.value.running) return
        val cfg = config()
        if (cfg.apiKey.isBlank()) {
            _state.value = AiAnalysisState(error = "未配置 API Key,请先在 AI 设置中配置|API key not configured")
            return
        }
        _state.value = AiAnalysisState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = ReportSummarizer.summarize(report, if (cfg.sanitize) "zh" else "zh")
                val system = ReportSummarizer.systemPrompt("zh")
                val client = AiClient(cfg)
                val answer = client.complete(system, summary.text)
                _state.value = AiAnalysisState(result = answer)
            } catch (e: AiApiException) {
                _state.value = AiAnalysisState(error = "AI API 错误: ${e.message}")
            } catch (e: Exception) {
                _state.value = AiAnalysisState(error = "分析失败: ${e.message ?: "unknown"}")
            }
        }
    }

    fun clear() {
        _state.value = AiAnalysisState()
    }

    override fun onCleared() {
        super.onCleared()
    }
}
