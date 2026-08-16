/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.soundlevel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.components.KeyValueRow
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundLevelScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: SoundLevelViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.running) com.vicinityprobe.ui.components.rememberKeepScreenOn()   // 记录期间屏幕常亮

    var durationSec by remember { mutableStateOf(300L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("声级记录器", "Sound level recorder"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("分钟级 LAeq 统计 + 时段分析", "Per-minute LAeq statistics with time-window analysis")), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60L to "1m", 300L to "5m", 900L to "15m", 3600L to "1h").forEach { (sec, label) ->
                            FilterChip(selected = durationSec == sec, onClick = { durationSec = sec }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start(durationSec) }, enabled = !st.running) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始记录", "Record")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.running) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }

            if (st.running) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.vicinityprobe.ui.components.AnimatedNumber(
                            value = String.format("%.1f", st.currentDb) + " dB(A)",
                            style = MaterialTheme.typography.displaySmall,
                            color = when {
                                st.currentDb >= 85 -> MaterialTheme.colorScheme.error
                                st.currentDb >= 70 -> Color(0xFFE65100)
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(
                            "${st.elapsedSec / 60}:${String.format("%02d", st.elapsedSec % 60)} / ${st.totalSec / 60}:00",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            t(L("本分钟 LAeq", "This minute")) + ": " + String.format("%.1f dB", st.currentMinuteDb),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (st.minuteBins.isNotEmpty()) {
                MinuteChart(st.minuteBins)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val avg = st.minuteBins.average()
                        val max = st.minuteBins.max()
                        val min = st.minuteBins.min()
                        val loudMinutes = st.minuteBins.count { it >= 70 }
                        Text(t(L("时段统计", "Session stats")), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(t(L("平均 LAeq", "Mean LAeq")), String.format("%.1f dB(A)", avg), primary = true)
                        KeyValueRow(t(L("峰值", "Peak")), String.format("%.1f dB(A)", st.peakDb))
                        KeyValueRow(t(L("最安静分钟", "Quietest minute")), String.format("%.1f dB(A)", min))
                        KeyValueRow(t(L("最吵分钟", "Loudest minute")), String.format("%.1f dB(A)", max))
                        KeyValueRow(
                            t(L("≥70 dB 分钟占比", "Minutes ≥70 dB")),
                            "${loudMinutes}/${st.minuteBins.size} (${(loudMinutes * 100 / st.minuteBins.size)}%)",
                        )
                    }
                }
                OutlinedButton(onClick = {
                    val f = File(context.cacheDir, "soundlevel_${System.currentTimeMillis()}.csv")
                    f.writeText(vm.exportCsv())
                    com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/csv")
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null); Text(t(L("导出 CSV", "Export CSV")))
                }
            }
            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 分钟级 LAeq 柱状图(40-110 dB 区间) */
@Composable
private fun MinuteChart(bins: List<Double>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.vicinityprobe.model.trBilingual("分钟级 LAeq|Per-minute LAeq", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val primary = MaterialTheme.colorScheme.primary
            val error = MaterialTheme.colorScheme.error
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val w = size.width
                val h = size.height
                val slot = w / bins.size
                bins.forEachIndexed { i, v ->
                    val norm = (((v - 40) / 70).coerceIn(0.0, 1.0)).toFloat()
                    val bh = norm * (h - 8.dp.toPx())
                    drawRect(
                        color = if (v >= 70) error else primary,
                        topLeft = Offset(i * slot + slot * 0.1f, h - bh),
                        size = androidx.compose.ui.geometry.Size(slot * 0.8f, bh),
                    )
                }
                // 70 dB 参考线
                val y70 = h - ((70 - 40) / 70f) * (h - 8.dp.toPx())
                drawLine(Color.Gray.copy(alpha = 0.6f), Offset(0f, y70), Offset(w, y70), 1f)
            }
            Text(com.vicinityprobe.model.trBilingual("红线 = 70 dB 参考|Red line = 70 dB reference", langOf(LocalContext.current)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
