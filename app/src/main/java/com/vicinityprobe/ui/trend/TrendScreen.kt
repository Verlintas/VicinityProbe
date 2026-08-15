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

package com.vicinityprobe.ui.trend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.domain.SeriesPt
import com.vicinityprobe.model.langOf
import com.vicinityprobe.report.ReportMeta
import com.vicinityprobe.ui.components.LineChart
import com.vicinityprobe.ui.navigation.Routes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendInferenceCard(
    title: String,
    inference: com.vicinityprobe.ui.trend.TrendViewModel.TrendInference?,
    t: (com.vicinityprobe.model.L) -> String,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (inference == null) {
                Text(
                    t(L("至少 3 条记录才能推断趋势", "At least 3 records needed for trend inference")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val trend = t(if (inference.stationary)
                    L("统计上平稳(无显著趋势)", "Statistically stationary (no significant trend)")
                else if (inference.slopePerDay > 0)
                    L("显著上升趋势", "Significant upward trend")
                else
                    L("显著下降趋势", "Significant downward trend"))
                Text(trend, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${t(L("斜率", "slope"))}: ${String.format("%+.2f", inference.slopePerDay)}/day · R² = ${String.format("%.2f", inference.r2)} · p = ${String.format("%.3f", inference.pValue)} · n = ${inference.points}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> Labels.tr(lang, l) }
    val vm: TrendViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()

    var interval by remember { mutableStateOf(10L) }
    var monitoring by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("连续监测与趋势", "Continuous monitoring & trends"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t(L("前台服务定时扫描,自动存档并生成趋势", "Foreground service scans periodically and builds trends")), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5L, 10L, 30L, 60L).forEach { m ->
                                FilterChip(
                                    selected = interval == m,
                                    onClick = { interval = m },
                                    label = { Text("${m} ${t(L("分钟", "min"))}") },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.startMonitoring(interval)
                                    monitoring = true
                                },
                                enabled = !monitoring,
                            ) { Text(t(L("开始监测", "Start monitoring"))) }
                            OutlinedButton(
                                onClick = {
                                    vm.stopMonitoring()
                                    monitoring = false
                                },
                                enabled = monitoring,
                            ) { Text(t(L("停止", "Stop"))) }
                        }
                    }
                }
            }
            if (items.size >= 2) {
                item {
                    Text(t(L("EXCELLENT 质量探测项趋势", "EXCELLENT quality probes trend")), style = MaterialTheme.typography.titleSmall)
                    val pts = items.sortedBy { it.createdAt }.mapIndexed { i, m -> SeriesPt(i.toLong(), m.excellentCount.toDouble()) }
                    LineChart(pts, t(L("EXCELLENT 项数", "EXCELLENT count")), "")
                }
                item {
                    Text(t(L("数据质量等级趋势", "Quality level trend")), style = MaterialTheme.typography.titleSmall)
                    val pts = items.sortedBy { it.createdAt }.mapIndexed { i, m -> SeriesPt(i.toLong(), m.okCount.toDouble()) }
                    LineChart(pts, t(L("OK 项数", "OK count")), "")
                }
                item {
                    TrendInferenceCard(
                        title = t(L("EXCELLENT 趋势推断", "EXCELLENT trend inference")),
                        inference = vm.inferTrend { it.excellentCount.toDouble() },
                        t = t,
                    )
                }
                item {
                    TrendInferenceCard(
                        title = t(L("OK 趋势推断", "OK trend inference")),
                        inference = vm.inferTrend { it.okCount.toDouble() },
                        t = t,
                    )
                }
            }
            item {
                Text(t(L("历史记录", "History")), style = MaterialTheme.typography.titleSmall)
            }
            items(items, key = { it.id }) { meta ->
                OutlinedCard(onClick = { nav.navigate(Routes.report(meta.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(meta.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            SimpleDateFormat("yyyy-MM-dd HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(meta.createdAt)) +
                                " · EXC ${meta.excellentCount} / DEG ${meta.degradedCount} / FAIL ${meta.failedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
