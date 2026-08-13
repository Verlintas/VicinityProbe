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

package com.vicinityprobe.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.domain.AcousticsSummary
import com.vicinityprobe.model.domain.AnalysisSummary
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.PositioningSummary
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.SpectrumResult
import com.vicinityprobe.model.domain.VibrationSummary
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.KeyValueRow
import com.vicinityprobe.ui.components.LineChart
import com.vicinityprobe.ui.components.QualityPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(nav: NavController, reportId: String) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: ReportViewModel = viewModel()

    LaunchedEffect(reportId) { vm.load(reportId) }
    val report by vm.report.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("测量报告", "Measurement Report"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        if (report == null) {
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(t(L("加载中…", "Loading…")))
            }
            return@Scaffold
        }
        val r = report!!
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlanHeader(r, lang)
            QualitySummary(r)
            r.analysis?.let { AnalysisSection(it, lang) }
            HorizontalDivider()
            r.measurements.forEach { m -> MeasurementCard(m, lang, reportId) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.exportJson()?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "application/json") } }, modifier = Modifier.weight(1f)) {
                    Text("JSON")
                }
                OutlinedButton(onClick = { vm.exportMd(lang)?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/markdown") } }, modifier = Modifier.weight(1f)) {
                    Text("MD")
                }
                Button(onClick = { vm.exportZip()?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "application/zip") } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text(" RAW")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlanHeader(r: MeasurementReport, lang: String) {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(r.plan.createdAt))
    OutlinedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("schema v${r.schemaVersion} · ${r.context.device}", style = MaterialTheme.typography.titleMedium)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Android ${r.context.androidVersion} (API ${r.context.apiLevel}) · ${r.plan.durationMs / 1000}s · ${r.plan.operator}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun QualitySummary(r: MeasurementReport) {
    val counts = r.measurements.groupBy { it.quality.level }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            QualityLevel.EXCELLENT to (counts[QualityLevel.EXCELLENT]?.size ?: 0),
            QualityLevel.GOOD to (counts[QualityLevel.GOOD]?.size ?: 0),
            QualityLevel.DEGRADED to (counts[QualityLevel.DEGRADED]?.size ?: 0),
            QualityLevel.FAILED to (counts[QualityLevel.FAILED]?.size ?: 0),
        ).forEach { (level, count) ->
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QualityPill(level)
                    Text(count.toString(), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun AnalysisSection(a: AnalysisSummary, lang: String) {
    a.acoustics?.let { AcousticsCard(it, lang) }
    a.vibration?.let { VibrationCard(it, lang) }
    a.positioning?.let { PositioningCard(it, lang) }
    a.contextClassification?.let { c ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tb("上下文分类|Context classification", lang), style = MaterialTheme.typography.titleSmall)
                KeyValueRow("class", c.classId, primary = true)
                KeyValueRow("confidence", String.format("%.0f%%", c.confidence * 100))
                c.features.forEach { (k, v) -> KeyValueRow(k, v) }
            }
        }
    }
}

@Composable
private fun AcousticsCard(a: AcousticsSummary, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tb("声学分析|Acoustics", lang), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            KeyValueRow("LAeq", a.laeqDBA?.let { String.format("%.1f dB(A)", it) } ?: "—", primary = true)
            KeyValueRow("Lpeak", a.lpeakDBA?.let { String.format("%.1f dB(A)", it) } ?: "—")
            KeyValueRow("L10 / L50 / L90", listOf(a.l10DBA, a.l50DBA, a.l90DBA).joinToString(" / ") { it?.let { v -> String.format("%.1f", v) } ?: "—" })
            KeyValueRow("calibrated", a.calibrated.toString())
        }
    }
}

@Composable
private fun VibrationCard(v: VibrationSummary, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tb("振动分析|Vibration", lang), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            KeyValueRow("dominant_freq", v.dominantFrequencyHz?.let { String.format("%.1f Hz", it) } ?: "—", primary = true)
            KeyValueRow("rms", v.rmsMs2?.let { String.format("%.3f m/s²", it) } ?: "—")
            KeyValueRow("crest_factor", v.crestFactor?.let { String.format("%.2f", it) } ?: "—")
            v.vibrationLevel?.let { KeyValueRow("level", tb(it, lang)) }
        }
    }
}

@Composable
private fun PositioningCard(p: PositioningSummary, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tb("定位分析|Positioning", lang), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            KeyValueRow("accuracy", p.horizontalAccuracyM?.let { String.format("%.1f m", it) } ?: "—", primary = true)
            KeyValueRow("satellites", p.satellitesUsed?.let { "$it / ${p.satellitesVisible ?: "—"}" } ?: "—")
            KeyValueRow("hdop", p.hdop?.let { String.format("%.2f", it) } ?: "—")
        }
    }
}

@Composable
private fun MeasurementCard(m: Measurement, lang: String, reportId: String) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(trBilingual(m.spec.name, lang), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${m.spec.measurand} · ${m.spec.unit.symbol}" +
                            if (m.spec.nominalRateHz > 0) " · ${String.format("%.0f Hz", m.spec.nominalRateHz)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QualityPill(m.quality.level)
            }
            if (m.quality.detail.isNotBlank()) {
                Text(trBilingual(m.quality.detail, lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (expanded) {
                KeyValueRow("quality_code", m.quality.code + " · coverage " + String.format("%.0f%%", m.quality.coveragePct))
                KeyValueRow("samples", m.quality.sampleCount.toString() + " · " + String.format("%.1f Hz", m.quality.achievedRateHz))
                m.attributes.entries.sortedBy { it.key }.forEach { (k, v) ->
                    if (k != "detail" && k != "note") KeyValueRow(k, v)
                }
                if (m.stats.isNotEmpty()) {
                    Text(tb("统计量|Statistics", lang), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    m.stats.entries.sortedBy { it.key }.forEach { (ch, s) ->
                        KeyValueRow(ch, "n=${s.n}  mean=${"%.4g".format(s.mean)}  σ=${"%.4g".format(s.stddev)}  med=${"%.4g".format(s.median)}  p95=${"%.4g".format(s.p95)}")
                    }
                }
                m.spectrum?.let { SpectrumBlock(it, lang) }
                m.series.forEach { (k, pts) ->
                    LineChart(pts, k, m.spec.unit.symbol)
                }
                m.attributes["detail"]?.let {
                    Text(it.take(800), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                if (m.samplesFile != null) {
                    Text(tb("原始样本已存档|Raw samples archived: ", lang) + m.samplesFile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "${m.attributes.size} attrs · ${m.stats.size} channels" + (m.spectrum?.let { " · FFT ${String.format("%.0f Hz", it.dominantFrequencyHz)}" } ?: "") +
                        " · ${tb("点击展开|Tap to expand", lang)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpectrumBlock(s: SpectrumResult, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tb("频谱分析|Spectrum", lang) + " (${s.method})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            KeyValueRow("dominant", String.format("%.2f Hz", s.dominantFrequencyHz), primary = true)
            KeyValueRow("flatness", String.format("%.3f", s.flatness))
            s.bandEnergy.forEach { (k, v) -> KeyValueRow("band_$k", String.format("%.1f%%", v)) }
        }
    }
}

private fun tb(s: String, lang: String): String = trBilingual(s, lang)
