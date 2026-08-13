package com.vicinityprobe.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportImageRenderer {
    private const val W = 1080
    private const val PAD = 48
    private const val LINE = 56
    private const val SMALL = 44

    fun render(report: ProbeReport, context: Context): Bitmap {
        val lang = context.resources.configuration.locales[0].language
        val zh = lang.startsWith("zh")
        val t = { l: L -> if (zh) l.zh else l.en }
        val tb = { s: String -> trBilingual(s, lang) }

        val lines = buildString {
            appendLine("VicinityProbe")
            appendLine("${t(L("环境数据报告", "Environment Data Report"))} | ${report.deviceName}")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.createdAt)))
            appendLine("${t(L("时长", "Duration"))}: ${report.scanDurationMs / 1000}s | ${t(L("模式", "Mode"))}: ${if (report.mode == "FULL") t(L("全部", "FULL")) else t(L("自定义", "SELECTED"))}")
            report.analysis?.let { a ->
                appendLine("")
                appendLine("★★ ${t(Labels.OVERALL)}: ${fmt(a.overallScore)}/100")
                appendLine("${t(L("场景", "Scene"))}: ${tb(a.scene)}")
                a.radar.forEach { appendLine("  ${tb(it.label)}: ${fmt(it.score)}") }
                a.weather?.let { w ->
                    if (w.fetched) {
                        appendLine("${t(Labels.WEATHER)}: ${tb(w.conditionText ?: "")} ${fmt(w.temperatureC ?: 0.0)}°C ${fmt(w.humidityPct ?: 0.0)}% ${fmt(w.pressureHpa ?: 0.0)}hPa")
                    } else {
                        appendLine("${t(Labels.WEATHER)}: ${t(L("联网失败", "offline"))}")
                    }
                }
                if (a.suggestions.isNotEmpty()) {
                    appendLine("${t(Labels.SUGGESTIONS)}:")
                    a.suggestions.forEach { appendLine("  - ${tb(it)}") }
                }
            }
            appendLine("")
            Groups.ordered.forEach { group ->
                val list = report.results.filter { it.group == group }
                if (list.isEmpty()) return@forEach
                appendLine("── ${t(Groups.label(group))} ──")
                list.forEach { r ->
                    val st = when (r.status) {
                        ProbeStatus.OK -> "OK"
                        ProbeStatus.NO_HARDWARE -> "N/A"
                        ProbeStatus.PERMISSION_MISSING -> "PERM"
                        ProbeStatus.FEATURE_OFF -> "OFF"
                        ProbeStatus.FAILED -> "FAIL"
                        ProbeStatus.SKIPPED -> "SKIP"
                    }
                    appendLine("${tb(r.name)} [$st]")
                    r.note?.let { appendLine("  ${tb(it)}") }
                    r.metrics.take(12).forEach { m ->
                        appendLine("  ${tb(m.label)}: ${tb(m.value)}${m.unit?.let { " $it" } ?: ""}")
                    }
                    appendLine("")
                }
            }
        }

        val wrapped = wrap(lines.lines().filter { it.isNotEmpty() })
        val height = wrapped.size * LINE + PAD * 2 + SMALL
        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(247, 250, 251))

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(11, 93, 110)
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 40, 50)
            textSize = 40f
        }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 120, 130)
            textSize = 34f
        }
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(11, 93, 110)
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = PAD.toFloat() + 40
        val raw = lines.lines().filter { it.isNotEmpty() }
        var li = 0
        for (rawLine in raw) {
            val isTitle = li == 0
            val isHeader = rawLine.startsWith("──") || rawLine.startsWith("★★")
            canvas.drawText(rawLine, PAD.toFloat(), y, if (isTitle) title else if (isHeader) header else if (li <= 2) muted else body)
            y += if (rawLine.startsWith("  - ")) LINE - 14 else LINE
            li++
        }
        return bitmap
    }

    private fun wrap(lines: List<String>): List<String> {
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f }
        val out = ArrayList<String>()
        for (line in lines) {
            if (line.length <= 52) {
                out.add(line)
                continue
            }
            var s = line
            while (s.length > 52) {
                var cut = 52
                val idx = s.lastIndexOf(' ', cut)
                if (idx > 20) cut = idx
                out.add(s.substring(0, cut))
                s = s.substring(cut).trimStart()
            }
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
