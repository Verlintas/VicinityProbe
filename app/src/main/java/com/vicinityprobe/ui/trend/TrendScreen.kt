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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.SeriesPoint
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.fmt
import com.vicinityprobe.ui.components.LineChart
import com.vicinityprobe.ui.navigation.Routes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    Text(t(L("综合评分趋势", "Overall score trend")), style = MaterialTheme.typography.titleSmall)
                    val pts = items.sortedBy { it.createdAt }.mapIndexed { i, m -> SeriesPoint(i.toLong(), m.overallScore ?: 0.0) }
                    LineChart(pts, t(L("评分", "Score")), "")
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
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(meta.createdAt)) +
                                (meta.overallScore?.let { " · ${t(L("评分", "score"))}: ${fmt(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
