package com.vicinityprobe.ui.report

import androidx.compose.foundation.background
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.fmt
import com.vicinityprobe.ui.components.LineChart
import com.vicinityprobe.ui.components.MetricGrid
import com.vicinityprobe.ui.components.RadarChart
import com.vicinityprobe.ui.components.StatusPill
import com.vicinityprobe.ui.navigation.Routes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(nav: NavController, reportId: String) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> Labels.tr(lang, l) }
    val vm: ReportViewModel = viewModel()

    LaunchedEffect(reportId) { vm.load(reportId) }
    val report by vm.report.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("环境数据报告", "Environment Report"))) },
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
            ReportHeader(r, lang)
            r.analysis?.let { a ->
                if (a.radar.size >= 3) {
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t(Labels.OVERALL), style = MaterialTheme.typography.titleMedium)
                            Text("${fmt(a.overallScore)}/100", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text(t(L("场景推断", "Scene")) + ": ${trBilingual(a.scene, lang)}", style = MaterialTheme.typography.bodyMedium)
                            RadarChart(a.radar.map { trBilingual(it.label, lang) to it.score })
                        }
                    }
                }
                a.weather?.let { w ->
                    OutlinedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t(Labels.WEATHER), style = MaterialTheme.typography.titleSmall)
                            if (w.fetched) {
                                Text("${trBilingual(w.conditionText ?: "", lang)} | ${fmt(w.temperatureC ?: 0.0)}°C | ${fmt(w.humidityPct ?: 0.0)}% | ${fmt(w.pressureHpa ?: 0.0)}hPa | ${fmt(w.windSpeedKph ?: 0.0)}km/h")
                                Text(t(L("本地", "Local")) + ": ${localMetric(r, "sensor.temperature", "avg", "°C")} | ${localMetric(r, "sensor.humidity", "avg", "%")} | ${localMetric(r, "sensor.pressure", "avg", "hPa")}", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text(t(L("联网获取失败", "Failed to fetch weather")) + (w.note?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (a.suggestions.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t(Labels.SUGGESTIONS), style = MaterialTheme.typography.titleSmall)
                            a.suggestions.forEach { Text("• ${trBilingual(it, lang)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            HorizontalDivider()

            Groups.ordered.forEach { group ->
                val list = r.results.filter { it.group == group }
                if (list.isEmpty()) return@forEach
                Text(t(Groups.label(group)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                list.forEach { pr -> ProbeCard(pr, lang) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.exportJson()?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "application/json") } }, modifier = Modifier.weight(1f)) {
                    Text("JSON")
                }
                OutlinedButton(onClick = { vm.exportMd(lang)?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/markdown") } }, modifier = Modifier.weight(1f)) {
                    Text("MD")
                }
                Button(onClick = { vm.exportPng()?.let { f -> com.vicinityprobe.report.ReportExporter.shareFile(context, f, "image/png") } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text(" PNG")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReportHeader(r: ProbeReport, lang: String) {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(r.createdAt))
    OutlinedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.deviceName, style = MaterialTheme.typography.titleMedium)
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (r.mode == "FULL") trBilingual("全部探测+分析|Full scan", lang) else trBilingual("自定义探测|Selected probes", lang)) +
                    " · ${r.scanDurationMs / 1000}s · " +
                    r.results.count { it.status == com.vicinityprobe.model.ProbeStatus.OK }.toString() + "/" + r.results.size + " OK",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProbeCard(pr: ProbeResult, lang: String) {
    var expanded by remember { mutableStateOf(pr.metrics.size <= 8) }
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(trBilingual(pr.name, lang), style = MaterialTheme.typography.titleSmall)
                StatusPill(pr.status)
            }
            if (pr.note != null && pr.note != pr.name) {
                Text(trBilingual(pr.note, lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                if (pr.metrics.isNotEmpty()) MetricGrid(pr.metrics, lang)
                pr.series.forEach { (key, pts) ->
                    LineChart(pts, trBilingual(pr.metrics.firstOrNull { it.key == key }?.label ?: key, lang), "")
                }
            } else {
                Text("${pr.metrics.size} ${trBilingual("项指标|metrics", lang)} · ${trBilingual("展开查看|Tap to expand", lang)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun localMetric(r: ProbeReport, id: String, key: String, unit: String): String {
    val m = r.results.firstOrNull { it.id == id }?.metrics?.firstOrNull { it.key == key }
    return if (m != null) "${m.value}${m.unit ?: unit}" else "—"
}
