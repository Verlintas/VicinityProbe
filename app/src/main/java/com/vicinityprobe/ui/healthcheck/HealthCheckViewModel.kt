/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.healthcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.ai.AiApiException
import com.vicinityprobe.ai.AiClient
import com.vicinityprobe.ai.AiConfigStore
import com.vicinityprobe.analysis.AnalysisEngine
import com.vicinityprobe.analysis.HealthScorer
import com.vicinityprobe.analysis.ReportSummarizer
import com.vicinityprobe.model.domain.Category
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.probe.CapabilityProbe
import com.vicinityprobe.probe.CapabilityStatus
import com.vicinityprobe.probe.SessionController
import com.vicinityprobe.probe.SessionUiState
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 环境体检(玩具模式):一键跑"安全合规"的探测项 → 本地评分 → AI 通俗解读。
 * 合规:默认排除 SECURITY 类主动探测(端口扫描/抓包/指纹/蓝牙扫描等),
 * 只测传感器与环境物理量;发送 AI 前脱敏。
 */

enum class HealthPhase { IDLE, SCANNING, SCORING, AI, DONE, ERROR }

data class HealthCheckState(
    val phase: HealthPhase = HealthPhase.IDLE,
    val progress: Float = 0f,
    val scanningText: String = "",
    val report: MeasurementReport? = null,
    val localScore: com.vicinityprobe.analysis.HealthScore? = null,
    val aiScore: String? = null,        // AI 原始回复
    val aiGrade: String? = null,
    val aiParsed: Boolean = false,
    val error: String? = null,
)

class HealthCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HealthCheckState())
    val state: StateFlow<HealthCheckState> = _state

    private var job: Job? = null
    private var controller: SessionController? = null

    /** 体检用探测项:排除 SECURITY 类与合规高风险项 */
    fun healthProbeIds(): List<String> {
        val app = getApplication<Application>()
        return CapabilityProbe.enumerate(app)
            .filter { it.status == CapabilityStatus.SUPPORTED }
            .filter { it.spec.category != Category.SECURITY }
            .filter { !it.spec.complianceRisk }
            .map { it.probeId }
    }

    fun start() {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        val ids = healthProbeIds()
        if (ids.isEmpty()) {
            _state.value = HealthCheckState(phase = HealthPhase.ERROR, error = "没有可用的体检项|No probes available")
            return
        }
        _state.value = HealthCheckState(phase = HealthPhase.SCANNING)
        val controller = SessionController(app, ids.toSet(), 12_000L, "HEALTHCHECK")
        this.controller = controller
        job = viewModelScope.launch {
            val collectJob = viewModelScope.launch {
                try {
                    controller.stateFlow().collect { s ->
                        _state.value = _state.value.copy(
                            phase = HealthPhase.SCANNING,
                            progress = if (s.totalUnits > 0) s.completedUnits.toFloat() / s.totalUnits else 0f,
                            scanningText = s.live.entries.firstOrNull()?.let { "${it.value.first} ${it.value.second}" } ?: "",
                        )
                    }
                } catch (_: Exception) {}
            }
            try {
                val report = controller.run(File(app.filesDir, "reports"))
                val analyzed = report.copy(analysis = AnalysisEngine.analyze(report))
                // 存档,可在历史里查看
                withContext(Dispatchers.IO) { HistoryManager(app).save(analyzed) }
                collectJob.cancel()
                val local = HealthScorer.score(analyzed)
                _state.value = HealthCheckState(phase = HealthPhase.SCORING, report = analyzed, localScore = local)
                // AI 评分(配置了才跑)
                val cfg = AiConfigStore.load(app)
                if (cfg.apiKey.isNotBlank()) {
                    _state.value = _state.value.copy(phase = HealthPhase.AI)
                    try {
                        val summary = ReportSummarizer.summarize(analyzed, "zh")
                        val system = """
你是"环境体检师"。基于手机传感器测量的环境摘要,给这个环境打一个 0-100 的健康分。

规则:
1. 输出 JSON:{"score":0-100,"grade":"A|B|C|D","verdict":"一句话通俗结论","highlights":["做得好的方面,通俗"],"concerns":["有问题的方面,通俗"],"suggestions":["普通人可执行的改善建议"]}
2. 评分参考:噪声(70dB+扣分)、磁场(100µT+扣分)、温度、光线、气压、测量质量
3. 语言通俗,像朋友聊天一样,别用专业术语堆砌
4. 只依据摘要事实,不确定的写"需要复测"
                        """.trimIndent()
                        val answer = AiClient(cfg).complete(system, summary.text)
                        val parsed = com.vicinityprobe.ai.AiResultParser.parse(answer)
                        _state.value = HealthCheckState(
                            phase = HealthPhase.DONE,
                            report = analyzed,
                            localScore = local,
                            aiScore = answer,
                            aiGrade = parsed?.grade?.takeIf { it.isNotBlank() },
                            aiParsed = parsed?.parsed == true,
                        )
                    } catch (e: Exception) {
                        _state.value = HealthCheckState(
                            phase = HealthPhase.DONE,
                            report = analyzed,
                            localScore = local,
                            error = "AI 评分失败: ${e.message ?: "unknown"}",
                        )
                    }
                } else {
                    _state.value = HealthCheckState(phase = HealthPhase.DONE, report = analyzed, localScore = local)
                }
            } catch (e: Exception) {
                _state.value = HealthCheckState(phase = HealthPhase.ERROR, error = e.message ?: "扫描失败")
            }
        }
    }

    fun cancel() {
        controller?.cancel()
        job?.cancel()
        job = null
        _state.value = _state.value.copy(phase = HealthPhase.IDLE)
    }

    fun reset() {
        cancel()
        _state.value = HealthCheckState()
    }
}
