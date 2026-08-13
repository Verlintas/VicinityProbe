package com.vicinityprobe.report

import com.vicinityprobe.analysis.Analyzer
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.ReportMeta
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonReport {
    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(report: ProbeReport): String = json.encodeToString(report)
    fun decode(text: String): ProbeReport = json.decodeFromString<ProbeReport>(text)
}

object MarkdownWriter {
    fun write(report: ProbeReport, lang: String): String {
        val sb = StringBuilder()
        val t = { l: L -> Labels.tr(lang, l) }
        val tb = { s: String -> trBilingual(s, lang) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.createdAt))

        sb.append("# VicinityProbe — ${t(L("环境数据报告", "Environment Data Report"))}\n\n")
        sb.append("| ${t(L("项目", "Item"))} | ${t(L("内容", "Value"))} |\n|---|---|\n")
        sb.append("| ${t(L("设备", "Device"))} | ${report.deviceName} |\n")
        sb.append("| ${t(L("时间", "Time"))} | $date |\n")
        sb.append("| ${t(L("扫描时长", "Duration"))} | ${report.scanDurationMs / 1000}s |\n")
        sb.append("| ${t(L("模式", "Mode"))} | ${if (report.mode == "FULL") t(L("全部探测+分析", "Full scan + analysis")) else t(L("自定义探测", "Selected probes"))} |\n\n")

        val analysis = report.analysis
        if (analysis != null) {
            sb.append("## ${t(Labels.OVERALL)}\n\n")
            sb.append("**${fmt(analysis.overallScore)}/100**\n\n")
            sb.append("${t(L("场景推断", "Scene"))}: **${tb(analysis.scene)}**\n\n")
            if (analysis.radar.isNotEmpty()) {
                sb.append("| ${t(L("维度", "Dimension"))} | ${t(L("得分", "Score"))} |\n|---|---|\n")
                analysis.radar.forEach { sb.append("| ${tb(it.label)} | ${fmt(it.score)} |\n") }
            }
            analysis.weather?.let { w ->
                sb.append("\n## ${t(Labels.WEATHER)}\n\n")
                if (w.fetched) {
                    sb.append("| ${t(L("项目", "Item"))} | ${t(L("气象站", "Station"))} | ${t(L("本地", "Local"))} |\n|---|---|---|\n")
                    w.temperatureC?.let { sb.append("| ${t(L("温度", "Temperature"))} | ${fmt(it)}°C | ${localMetric(report, "sensor.temperature", "avg", "°C")} |\n") }
                    w.humidityPct?.let { sb.append("| ${t(L("湿度", "Humidity"))} | ${fmt(it)}% | ${localMetric(report, "sensor.humidity", "avg", "%")} |\n") }
                    w.pressureHpa?.let { sb.append("| ${t(L("气压", "Pressure"))} | ${fmt(it)}hPa | ${localMetric(report, "sensor.pressure", "avg", "hPa")} |\n") }
                    w.windSpeedKph?.let { sb.append("| ${t(L("风速", "Wind"))} | ${fmt(it)}km/h | — |\n") }
                    w.conditionText?.let { sb.append("| ${t(L("天气", "Weather"))} | ${tb(it)} | — |\n") }
                } else {
                    sb.append("${t(L("联网获取失败", "Weather fetch failed"))}${w.note?.let { " ($it)" } ?: ""}\n")
                }
            }
            if (analysis.suggestions.isNotEmpty()) {
                sb.append("\n## ${t(Labels.SUGGESTIONS)}\n\n")
                analysis.suggestions.forEach { sb.append("- ${tb(it)}\n") }
            }
        }

        sb.append("\n## ${t(L("探测明细", "Probe details"))}\n\n")
        Groups.ordered.forEach { group ->
            val list = report.results.filter { it.group == group }
            if (list.isEmpty()) return@forEach
            sb.append("\n### ${t(Groups.label(group))}\n\n")
            list.forEach { r ->
                val status = statusText(r.status, lang)
                sb.append("**${tb(r.name)}** — $status\n")
                r.note?.let { sb.append("${t(L("备注", "Note"))}: ${tb(it)}\n") }
                if (r.metrics.isNotEmpty()) {
                    sb.append("| ${t(L("指标", "Metric"))} | ${t(L("数值", "Value"))} |\n|---|---|\n")
                    r.metrics.forEach { m ->
                        sb.append("| ${tb(m.label)} | ${tb(m.value)}${m.unit?.let { " $it" } ?: ""} |\n")
                    }
                }
                r.series.values.firstOrNull()?.let { pts ->
                    if (pts.isNotEmpty()) {
                        val max = pts.maxOf { it.v }
                        val min = pts.minOf { it.v }
                        sb.append("${t(L("时序", "Series"))}: ${pts.size} ${t(L("个采样点", "samples"))} (${fmt(min)}~${fmt(max)})\n")
                    }
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun localMetric(report: ProbeReport, id: String, key: String, unit: String): String {
        val m = report.results.firstOrNull { it.id == id }?.metrics?.firstOrNull { it.key == key }
        return if (m != null) "${m.value}${m.unit ?: unit}" else "—"
    }

    private fun statusText(s: ProbeStatus, lang: String): String = when (s) {
        ProbeStatus.OK -> "✅ " + Labels.tr(lang, Labels.OK)
        ProbeStatus.NO_HARDWARE -> "❌ " + Labels.tr(lang, Labels.NO_HARDWARE)
        ProbeStatus.PERMISSION_MISSING -> "⚠️ " + Labels.tr(lang, Labels.PERMISSION_MISSING)
        ProbeStatus.FEATURE_OFF -> "⚠️ " + Labels.tr(lang, Labels.FEATURE_OFF)
        ProbeStatus.FAILED -> "⛔ " + Labels.tr(lang, Labels.FAILED)
        ProbeStatus.SKIPPED -> "⏭ " + Labels.tr(lang, Labels.SKIPPED)
    }
}

object ReportMetaFactory {
    fun from(report: ProbeReport): ReportMeta = ReportMeta(
        id = report.id,
        name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.createdAt)),
        createdAt = report.createdAt,
        durationMs = report.scanDurationMs,
        mode = report.mode,
        deviceName = report.deviceName,
        probeCount = report.results.size,
        okCount = report.results.count { it.status == ProbeStatus.OK },
        overallScore = report.analysis?.overallScore,
        scene = report.analysis?.scene,
    )
}
