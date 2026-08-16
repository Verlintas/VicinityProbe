/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.QualityLevel

/**
 * AI 深度分析的数据准备层(纯逻辑,可单元测试):
 * 1) [summarize] 把整份报告压缩成结构化摘要(含质量分布、关键指标、异常清单)
 * 2) [sanitize] 脱敏:剥离位置/序列号/MAC/SSID 等个人数据
 * 3) [systemPrompt] 分析角色系统提示词
 *
 * 原则:只发送本地算法已经"发现"的事实 + 摘要,不让 AI 猜原始数据;
 * 控制 token 预算(~4K),AI 负责解读、归因与建议,而非重新分析。
 */

data class AiSummary(
    val device: String,
    val androidVersion: String,
    val durationSec: Long,
    val probeCount: Int,
    val qualityCounts: Map<String, Int>,
    val anomalies: List<String>,        // 异常项(FAILED/DEGRADED + 超常数值)
    val keyMetrics: List<String>,       // 关键指标(声学/振动/定位/电池等)
    val text: String,                   // 组装好的提示词正文(不含 system)
)

object ReportSummarizer {

    /** 生成发送给 AI 的摘要(已脱敏) */
    fun summarize(report: MeasurementReport, lang: String = "zh"): AiSummary {
        val qc = report.measurements.groupBy { it.quality.level.name }
            .mapValues { it.value.size }
        val anomalies = ArrayList<String>()
        val keyMetrics = ArrayList<String>()

        for (m in report.measurements) {
            when (m.quality.level) {
                QualityLevel.FAILED -> anomalies.add("${m.spec.id}: ${m.quality.code}")
                QualityLevel.DEGRADED -> anomalies.add("${m.spec.id}: DEGRADED (${m.quality.code})")
                else -> {}
            }
            // 关键指标抽取
            extractKeyMetrics(m, keyMetrics)
            // 超常数值检测
            detectOutliers(m, anomalies)
        }

        val sb = StringBuilder()
        sb.appendLine("## 报告概要")
        sb.appendLine("- 设备: ${report.context.device} / Android ${report.context.androidVersion} (API ${report.context.apiLevel})")
        sb.appendLine("- 时长: ${report.plan.durationMs / 1000}s · 探测项: ${report.measurements.size}")
        sb.appendLine("- 质量分布: EXCELLENT=${qc["EXCELLENT"] ?: 0}, GOOD=${qc["GOOD"] ?: 0}, DEGRADED=${qc["DEGRADED"] ?: 0}, FAILED=${qc["FAILED"] ?: 0}")
        sb.appendLine()
        if (keyMetrics.isNotEmpty()) {
            sb.appendLine("## 关键指标")
            keyMetrics.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }
        if (anomalies.isNotEmpty()) {
            sb.appendLine("## 异常与注意项")
            anomalies.forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("## 异常与注意项")
            sb.appendLine("- 无显著异常")
        }
        return AiSummary(
            device = report.context.device,
            androidVersion = report.context.androidVersion,
            durationSec = report.plan.durationMs / 1000,
            probeCount = report.measurements.size,
            qualityCounts = qc,
            anomalies = anomalies,
            keyMetrics = keyMetrics,
            text = sb.toString(),
        )
    }

    /** 关键指标抽取(声学/振动/定位/电池/温度/光照) */
    private fun extractKeyMetrics(m: Measurement, out: MutableList<String>) {
        val a = m.attributes
        when (m.spec.id) {
            "noise" -> {
                a["LAeq"]?.let { out.add("环境噪声 LAeq = $it dB(A)" + (a["L10"]?.let { ", L10 = $it" } ?: "")) }
            }
            "sensor.accelerometer" -> {
                m.stats["magnitude"]?.let { s -> out.add("振动 RMS = ${String.format("%.3f", s.rms)} m/s², 峰值因子 = ${String.format("%.1f", if (s.rms > 0) s.max / s.rms else 0.0)}") }
            }
            "location", "gnss" -> {
                a["accuracy_m"]?.let { out.add("定位精度 = $it m") }
                a["used_in_fix"]?.let { out.add("GNSS 使用卫星 = $it 颗") }
            }
            "battery" -> {
                a["level_pct"]?.let { out.add("电量 = $it%") }
            }
            "sensor.light" -> m.stats["value"]?.let { out.add("光照均值 = ${String.format("%.0f", it.mean)} lx") }
            "sensor.temperature" -> m.stats["value"]?.let { out.add("环境温度均值 = ${String.format("%.1f", it.mean)} °C") }
            "sensor.pressure" -> m.stats["value"]?.let { out.add("气压均值 = ${String.format("%.1f", it.mean)} hPa") }
            "net_arp_table" -> a["neighbors"]?.let { out.add("LAN 邻居设备 = $it 台") }
            "net_doh" -> a["ok"]?.let { out.add("DoH 解析成功 = $it 次") }
            "net_quic" -> a["ok"]?.let { out.add("QUIC 可达 = $it/2") }
            "wifi" -> a["ssid"]?.let { out.add("WiFi 网络 = ${sanitizeSsid(it)}") }
        }
    }

    /** 超常数值检测(超出物理常识范围) */
    private fun detectOutliers(m: Measurement, out: MutableList<String>) {
        for ((ch, s) in m.stats) {
            if (s.n < 3 || !s.mean.isFinite()) continue
            when (m.spec.id) {
                "sensor.accelerometer" -> if (s.rms > 20) out.add("加速度异常: RMS=${String.format("%.2f", s.rms)} m/s²(正常静止 <1, 剧烈振动 <10)")
                "sensor.temperature" -> if (s.mean > 60 || s.mean < -30) out.add("环境温度异常: ${String.format("%.1f", s.mean)} °C")
                "sensor.light" -> if (s.mean > 100000) out.add("光照异常: ${String.format("%.0f", s.mean)} lx")
                "noise" -> if (s.max > 120) out.add("声级异常: 峰值 ${String.format("%.0f", s.max)} dB(A)(>120 通常为信号饱和)")
                "sensor.magnetometer" -> {
                    val mag = s.rms
                    if (mag > 100) out.add("磁场异常: 幅值 ${String.format("%.0f", mag)} µT(远超地磁场 25-65 µT,疑近强磁源)")
                }
            }
        }
    }

    /** 脱敏:SSID 打码 */
    fun sanitizeSsid(ssid: String): String = if (ssid.length <= 2) "***" else ssid.take(2) + "***"

    /** 系统提示词:环境测量分析师角色 */
    fun systemPrompt(lang: String = "zh"): String = if (lang.startsWith("zh")) {
        """
你是"环境测量分析师",一位严谨的仪器工程师。用户会给你一份手机环境测量报告的"摘要"(包含质量分布、关键指标与异常清单)。

要求:
1. 输出 JSON:{"summary":"总体结论(2-3句)","findings":[{"item":"发现项","detail":"解读","severity":"info|low|medium|high"}],"risks":[{"risk":"风险","level":"low|medium|high","suggestion":"建议"}],"recommendations":["行动建议"]}
2. 只基于摘要给出的事实解读,不要编造未提供的数据;不确定的指标标注"需复测确认"
3. 用通俗语言解释专业指标(如 dB(A)、RMS、THD)对普通人的意义
4. 若异常清单为空,risks 可留空数组
5. 文本使用中文
""".trimIndent()
    } else {
        """
You are an "environmental measurement analyst", a rigorous instrument engineer. The user provides a summary of a phone-based environmental measurement report (quality distribution, key metrics, anomaly list).

Requirements:
1. Output JSON: {"summary":"overall conclusion (2-3 sentences)","findings":[{"item":"finding","detail":"interpretation","severity":"info|low|medium|high"}],"risks":[{"risk":"risk","level":"low|medium|high","suggestion":"suggestion"}],"recommendations":["action items"]}
2. Interpret ONLY facts present in the summary; never invent data; mark uncertain metrics as "needs re-measurement"
3. Explain professional metrics (dB(A), RMS, THD) in plain language
4. If the anomaly list is empty, risks may be an empty array
5. Respond in English
""".trimIndent()
    }
}
