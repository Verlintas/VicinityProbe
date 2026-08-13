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

package com.vicinityprobe.report

import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.SpectrumResult
import com.vicinityprobe.model.trBilingual
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 专业 Markdown 报告生成器 */
object MarkdownWriter {
    fun write(report: MeasurementReport, lang: String): String {
        val zh = lang.startsWith("zh")
        val tb = { s: String -> trBilingual(s, lang) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.plan.createdAt))
        val sb = StringBuilder()

        sb.append("# VicinityProbe 测量报告\n\n")
        sb.append("> 专业环境数据采集报告 · schema v${report.schemaVersion}\n\n")

        // 1. 测量计划
        sb.append("## 1. 测量计划\n\n")
        sb.append("| 项目 | 值 |\n|---|---|\n")
        sb.append("| 计划 ID | `${report.plan.planId}` |\n")
        sb.append("| 时间 | $date |\n")
        sb.append("| 时长 | ${report.plan.durationMs / 1000} s |\n")
        sb.append("| 模式 | ${report.plan.operator} |\n")
        sb.append("| 探测项 | ${report.plan.probeIds.size} |\n")
        sb.append("| 仪器(设备) | ${report.context.device} |\n")
        sb.append("| 系统 | Android ${report.context.androidVersion} (API ${report.context.apiLevel}) |\n")
        sb.append("| 内核 | ${report.context.kernel} |\n")
        sb.append("| 时区/语言 | ${report.context.timezone} / ${report.context.locale} |\n")
        sb.append("| 电量 | ${report.context.batteryLevelPct?.let { "%.0f%%".format(it) } ?: "—"} |\n\n")

        // 2. 质量总览
        val byLevel = report.measurements.groupBy { it.quality.level }
        sb.append("## 2. 数据质量总览\n\n")
        sb.append("| 等级 | 数量 |\n|---|---|\n")
        sb.append("| EXCELLENT | ${byLevel[QualityLevel.EXCELLENT]?.size ?: 0} |\n")
        sb.append("| GOOD | ${byLevel[QualityLevel.GOOD]?.size ?: 0} |\n")
        sb.append("| DEGRADED | ${byLevel[QualityLevel.DEGRADED]?.size ?: 0} |\n")
        sb.append("| FAILED | ${byLevel[QualityLevel.FAILED]?.size ?: 0} |\n\n")

        // 3. 分析摘要
        report.analysis?.let { a ->
            sb.append("## 3. 分析摘要\n\n")
            a.acoustics?.let { ac ->
                sb.append("### 声学\n\n")
                sb.append("| 指标 | 值 |\n|---|---|\n")
                sb.append("| LAeq | ${ac.laeqDBA?.let { "%.1f".format(it) } ?: "—"} dB(A) |\n")
                sb.append("| Lpeak | ${ac.lpeakDBA?.let { "%.1f".format(it) } ?: "—"} dB(A) |\n")
                sb.append("| L10/L50/L90 | ${ac.l10DBA?.let { "%.1f".format(it) }} / ${ac.l50DBA?.let { "%.1f".format(it) }} / ${ac.l90DBA?.let { "%.1f".format(it) }} dB(A) |\n")
                sb.append("| 校准 | ${if (ac.calibrated) "已校准" else if (zh) "未校准(参考值)" else "Uncalibrated (reference)"} |\n\n")
            }
            a.vibration?.let { v ->
                sb.append("### 振动\n\n")
                sb.append("| 指标 | 值 |\n|---|---|\n")
                sb.append("| 主导频率 | ${v.dominantFrequencyHz?.let { "%.1f".format(it) } ?: "—"} Hz |\n")
                sb.append("| RMS 加速度 | ${v.rmsMs2?.let { "%.3f".format(it) } ?: "—"} m/s² |\n")
                sb.append("| 峰值因子 | ${v.crestFactor?.let { "%.2f".format(it) } ?: "—"} |\n")
                sb.append("| 振动等级 | ${v.vibrationLevel?.let { tb(it) } ?: "—"} |\n\n")
            }
            a.positioning?.let { p ->
                sb.append("### 定位\n\n")
                sb.append("| 指标 | 值 |\n|---|---|\n")
                sb.append("| 水平精度 | ${p.horizontalAccuracyM?.let { "%.1f".format(it) } ?: "—"} m |\n")
                sb.append("| 参与定位卫星 | ${p.satellitesUsed ?: "—"} / ${p.satellitesVisible ?: "—"} |\n")
                sb.append("| HDOP | ${p.hdop?.let { "%.2f".format(it) } ?: "—"} |\n\n")
            }
            a.contextClassification?.let { c ->
                sb.append("### 上下文分类\n\n")
                sb.append("- 类别: `${c.classId}` (置信度 ${"%.0f".format(c.confidence * 100)}%)\n")
                c.features.forEach { (k, v) -> sb.append("- 特征 `$k`: $v\n") }
                sb.append("\n")
            }
        }

        // 4. 逐项测量明细
        sb.append("## 4. 测量明细\n\n")
        report.measurements.forEach { m -> writeMeasurement(sb, m, lang) }

        // 5. 原始数据
        val withSamples = report.measurements.filter { it.samplesFile != null }
        if (withSamples.isNotEmpty()) {
            sb.append("## 5. 原始样本\n\n")
            sb.append("原始样本以 CSV 存档于报告目录 `samples/` 下:\n\n")
            withSamples.forEach { sb.append("- `${it.samplesFile}` — 通道: ${it.spec.sampleChannels.joinToString(", ")} 行数: ${it.quality.sampleCount}\n") }
            sb.append("\n")
        }

        // 6. 合规声明
        val risky = report.measurements.filter { it.spec.complianceRisk }
        sb.append("## 6. 合规提示\n\n")
        sb.append("请遵守当地法律法规合法使用本软件;使用方式由使用者自行负责。\n\n")
        if (risky.isNotEmpty()) {
            sb.append("本报告包含以下受监管风险标注的探测项:\n\n")
            risky.forEach { m ->
                sb.append("- **${tb(m.spec.name)}**: ${tb(m.spec.riskNote)}\n")
            }
            sb.append("\n")
        }

        sb.append("---\n*由 VicinityProbe 生成 · 报告 schema v${report.schemaVersion} · 未校准测量值均为参考级*\n")
        return sb.toString()
    }

    private fun writeMeasurement(sb: StringBuilder, m: Measurement, lang: String) {
        val zh = lang.startsWith("zh")
        val tb = { s: String -> trBilingual(s, lang) }
        val name = tb(m.spec.name)
        val q = m.quality
        sb.append("### ${name}\n\n")
        if (m.spec.complianceRisk) {
            sb.append("> ⚠️ **合规提示**: ${tb(m.spec.riskNote)}\n\n")
        }
        sb.append("| 属性 | 值 |\n|---|---|\n")
        sb.append("| 被测量 | ${m.spec.measurand} |\n")
        sb.append("| 单位 | ${m.spec.unit.symbol} |\n")
        sb.append("| 标称采样率 | ${if (m.spec.nominalRateHz > 0) "%.1f".format(m.spec.nominalRateHz) + " Hz" else "事件驱动"} |\n")
        sb.append("| 实际采样率 | ${if (q.achievedRateHz > 0) "%.2f".format(q.achievedRateHz) + " Hz" else "—"} |\n")
        sb.append("| 覆盖率 | ${"%.1f".format(q.coveragePct)}% |\n")
        sb.append("| 样本数 | ${q.sampleCount} |\n")
        sb.append("| 质量 | **${q.level}**${q.code.ifEmpty { "" }.let { if (it.isNotEmpty() && it != "OK") " (`$it`)" else "" }} |\n")
        if (q.detail.isNotBlank()) sb.append("| 说明 | ${tb(q.detail)} |\n")
        if (m.spec.typicalRange.isNotEmpty()) sb.append("| 量程 | ${m.spec.typicalRange} |\n")
        m.attributes.entries.sortedBy { it.key }.forEach { (k, v) ->
            if (k != "detail" && k != "note") sb.append("| $k | ${tb(v).replace("\n", "<br>")} |\n")
        }
        m.attributes["note"]?.let { sb.append("| 备注 | ${tb(it)} |\n") }
        sb.append("\n")

        if (m.stats.isNotEmpty()) {
            sb.append("**统计量**\n\n")
            sb.append("| 通道 | n | min | p5 | p25 | 中位 | p75 | p95 | max | mean | std | RMS | CV |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            m.stats.entries.sortedBy { it.key }.forEach { (ch, s) ->
                sb.append("| $ch | ${s.n} | ${"%.4g".format(s.min)} | ${"%.4g".format(s.p5)} | ${"%.4g".format(s.p25)} | ${"%.4g".format(s.median)} | ${"%.4g".format(s.p75)} | ${"%.4g".format(s.p95)} | ${"%.4g".format(s.max)} | ${"%.4g".format(s.mean)} | ${"%.4g".format(s.stddev)} | ${"%.4g".format(s.rms)} | ${"%.3f".format(s.cv)} |\n")
            }
            sb.append("\n")
        }
        m.spectrum?.let { writeSpectrum(sb, it) }
        m.attributes["detail"]?.let { sb.append("**明细**\n\n```\n${it}\n```\n\n") }
    }

    private fun writeSpectrum(sb: StringBuilder, s: SpectrumResult) {
        sb.append("**频谱分析** (${s.method})\n\n")
        sb.append("| 指标 | 值 |\n|---|---|\n")
        sb.append("| 主导频率 | ${"%.2f".format(s.dominantFrequencyHz)} Hz |\n")
        sb.append("| 主导幅值 | ${"%.3e".format(s.dominantAmplitude)} |\n")
        sb.append("| 频谱平坦度 | ${"%.3f".format(s.flatness)} |\n")
        s.bandEnergy.forEach { (k, v) -> sb.append("| 频带 $k 能量占比 | ${"%.1f".format(v)}% |\n") }
        sb.append("\n")
    }
}
