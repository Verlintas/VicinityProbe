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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.trBilingual
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 报告 PNG 渲染:按测量报告结构生成摘要图片 */
object ReportImageRenderer {
    private const val W = 1080
    private const val PAD = 48
    private const val LINE = 54

    fun render(report: MeasurementReport, context: Context): Bitmap {
        val lang = context.resources.configuration.locales[0].language
        val zh = lang.startsWith("zh")
        val tb = { s: String -> trBilingual(s, lang) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.plan.createdAt))

        val sb = StringBuilder()
        sb.appendLine("VicinityProbe · Measurement Report (schema v${report.schemaVersion})")
        sb.appendLine("${report.context.device} · Android ${report.context.androidVersion} (API ${report.context.apiLevel})")
        sb.appendLine("$date · ${report.plan.durationMs / 1000}s · mode=${report.plan.operator}")
        sb.appendLine("")
        val byLevel = report.measurements.groupBy { it.quality.level }
        sb.appendLine("EXCELLENT:${byLevel[QualityLevel.EXCELLENT]?.size ?: 0}  GOOD:${byLevel[QualityLevel.GOOD]?.size ?: 0}  DEGRADED:${byLevel[QualityLevel.DEGRADED]?.size ?: 0}  FAILED:${byLevel[QualityLevel.FAILED]?.size ?: 0}")
        report.analysis?.let { a ->
            a.acoustics?.let { ac ->
                sb.appendLine("")
                sb.appendLine("LAeq=${ac.laeqDBA?.let { "%.1f".format(it) } ?: "--"} dB(A)  Lpeak=${ac.lpeakDBA?.let { "%.1f".format(it) } ?: "--"}  L10/L50/L90=${ac.l10DBA?.let { "%.1f".format(it) }}/${ac.l50DBA?.let { "%.1f".format(it) }}/${ac.l90DBA?.let { "%.1f".format(it) }}")
            }
            a.vibration?.let { v ->
                sb.appendLine("Vibration: f=${v.dominantFrequencyHz?.let { "%.1f".format(it) } ?: "--"}Hz  RMS=${v.rmsMs2?.let { "%.3f".format(it) } ?: "--"} m/s²")
            }
            a.positioning?.let { p ->
                sb.appendLine("Position: acc=${p.horizontalAccuracyM?.let { "%.1f".format(it) } ?: "--"}m  sats=${p.satellitesUsed ?: "--"}/${p.satellitesVisible ?: "--"}  HDOP=${p.hdop?.let { "%.2f".format(it) } ?: "--"}")
            }
            a.contextClassification?.let { c ->
                sb.appendLine("Context: ${c.classId} (${"%.0f".format(c.confidence * 100)}%)")
            }
        }
        sb.appendLine("")
        report.measurements.forEach { m ->
            val name = tb(m.spec.name)
            val q = m.quality.level.name
            sb.appendLine("$name [$q]")
            m.attributes.entries.sortedBy { it.key }.forEach { (k, v) ->
                if (k != "detail") sb.appendLine("  $k: ${v.replace("\n", " ").take(90)}")
            }
            m.stats.entries.sortedBy { it.key }.forEach { (ch, s) ->
                sb.appendLine("  $ch: n=${s.n} mean=${"%.3g".format(s.mean)} std=${"%.3g".format(s.stddev)} med=${"%.3g".format(s.median)} p95=${"%.3g".format(s.p95)}")
            }
            m.spectrum?.let { s ->
                sb.appendLine("  FFT ${s.method}: dom=${"%.1f".format(s.dominantFrequencyHz)}Hz flat=${"%.2f".format(s.flatness)}")
            }
        }

        val lines = wrap(sb.lines().filter { it.isNotEmpty() })
        val height = lines.size * LINE + PAD * 2
        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(247, 250, 251))

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(11, 93, 110); textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(11, 93, 110); textSize = 40f
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 40, 50); textSize = 34f }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 120, 130); textSize = 30f }

        var y = PAD.toFloat() + 40
        var li = 0
        for (line in lines) {
            val paint = when {
                li == 0 -> title
                line.startsWith("  ") || line.contains(": n=") || line.startsWith("EXCELLENT:") ||
                    line.startsWith("LAeq=") || line.startsWith("Vibration:") || line.startsWith("Position:") || line.startsWith("Context:") -> body
                li <= 3 -> header
                else -> muted
            }
            canvas.drawText(line, PAD.toFloat(), y, paint)
            y += if (line.startsWith("  ")) LINE - 12 else LINE
            li++
        }
        return bitmap
    }

    private fun wrap(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            if (line.length <= 58) {
                out.add(line)
                continue
            }
            var s = line
            while (s.length > 58) {
                var cut = 58
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
