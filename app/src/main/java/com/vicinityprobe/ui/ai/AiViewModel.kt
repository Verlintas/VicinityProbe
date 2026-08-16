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
import com.vicinityprobe.ai.AiResult
import com.vicinityprobe.ai.AiResultParser
import com.vicinityprobe.analysis.ReportSummarizer
import com.vicinityprobe.model.domain.MeasurementReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AiAnalysisState(
    val running: Boolean = false,
    val raw: String? = null,          // AI 原始回复(降级展示用)
    val result: AiResult? = null,     // 结构化解析结果
    val cached: Boolean = false,      // 是否为上次缓存的分析
    val error: String? = null,
)

class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AiAnalysisState())
    val state: StateFlow<AiAnalysisState> = _state

    fun config(): AiConfigStore.Config = AiConfigStore.load(getApplication())

    fun saveConfig(c: AiConfigStore.Config) = AiConfigStore.save(getApplication(), c)

    private fun analysisFile(reportId: String): File =
        File(getApplication<Application>().filesDir, "reports/$reportId/ai_analysis.json")

    /** 读取上次缓存的分析结果(进入报告页时调用) */
    fun loadCached(reportId: String) {
        val f = analysisFile(reportId)
        if (!f.exists()) return
        withContextSafe {
            val raw = f.readText()
            _state.value = AiAnalysisState(
                raw = raw,
                result = AiResultParser.parse(raw),
                cached = true,
            )
        }
    }

    /** 对报告执行 AI 深度分析(结果持久化) */
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
                val summary = ReportSummarizer.summarize(report, "zh")
                val system = cfg.customPrompt.ifBlank { ReportSummarizer.systemPrompt("zh") }
                val answer = AiClient(cfg).complete(system, summary.text)
                // 持久化
                try {
                    analysisFile(report.id).apply { parentFile?.mkdirs() }.writeText(answer)
                } catch (_: Throwable) {}
                _state.value = AiAnalysisState(
                    raw = answer,
                    result = AiResultParser.parse(answer),
                )
            } catch (e: AiApiException) {
                _state.value = AiAnalysisState(error = "AI API 错误: ${e.message}")
            } catch (e: Exception) {
                _state.value = AiAnalysisState(error = "分析失败: ${e.message ?: "unknown"}")
            }
        }
    }

    /**
     * 趋势解读:合并最近 N 份报告的摘要,让 AI 解读质量趋势与指标变化。
     */
    fun analyzeTrend(reports: List<MeasurementReport>) {
        if (_state.value.running || reports.size < 2) return
        val cfg = config()
        if (cfg.apiKey.isBlank()) {
            _state.value = AiAnalysisState(error = "未配置 API Key|API key not configured")
            return
        }
        _state.value = AiAnalysisState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("# 连续监测趋势摘要(${reports.size} 份报告)")
                reports.sortedBy { it.plan.createdAt }.forEachIndexed { i, r ->
                    val s = ReportSummarizer.summarize(r, "zh")
                    sb.appendLine("## 报告 ${i + 1} (${r.plan.createdAt})")
                    sb.appendLine(s.text)
                }
                val system = """
你是"环境趋势分析师"。用户提供同一环境下多份测量报告的摘要序列。
分析:
1. 输出 JSON:{"summary":"整体趋势结论(3-4句)","trends":[{"metric":"指标","direction":"up|down|stable|fluctuating","detail":"变化描述"}],"risks":[{"risk":"风险","level":"low|medium|high","suggestion":"建议"}],"recommendations":["行动建议"]}
2. 只依据摘要中出现的数据;方向必须与数据一致
3. 用中文
                """.trimIndent()
                val answer = AiClient(cfg).complete(system, sb.toString())
                _state.value = AiAnalysisState(
                    raw = answer,
                    result = AiResultParser.parse(answer),
                )
            } catch (e: Exception) {
                _state.value = AiAnalysisState(error = "趋势分析失败: ${e.message ?: "unknown"}")
            }
        }
    }

    fun clear() {
        _state.value = AiAnalysisState()
    }

    /** 简单 IO 包装(不引入协程重载) */
    private fun withContextSafe(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
