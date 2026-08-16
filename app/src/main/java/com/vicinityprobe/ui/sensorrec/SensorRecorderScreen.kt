/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.sensorrec

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.components.KeyValueRow
import com.vicinityprobe.ui.components.StatPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorRecorderScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: SensorRecorderViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.recording) com.vicinityprobe.ui.components.rememberKeepScreenOn()

    // 500ms 刷新一次实时波形
    var tick by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(st.recording) {
        while (true) {
            kotlinx.coroutines.delay(500)
            if (st.recording) tick++
        }
    }
    val live = if (st.recording) vm.liveSnapshot(st.sensor.chCount) else emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("传感器 RAW 录制", "Sensor RAW recorder"))) },
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
                    Text(t(L("最高采样率录制传感器原始数据流到 CSV,可离线分析", "Record raw sensor streams at max rate to CSV for offline analysis")), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecSensor.entries.forEach { s ->
                            FilterChip(
                                selected = st.sensor == s,
                                onClick = { if (!st.recording) vm.setSensor(s) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start() }, enabled = !st.recording) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始录制", "Record")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.recording) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }

            if (st.recording) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill(String.format("%.0f Hz", st.sampleRateHz), t(L("采样率", "rate")), Modifier.weight(1f))
                    StatPill("${st.sampleCount}", t(L("样本", "samples")), Modifier.weight(1f))
                    StatPill("${st.elapsedMs / 1000}s", t(L("时长", "elapsed")), Modifier.weight(1f))
                }
                LiveWaveform(live, st.sensor.chCount)
                KeyValueRow(t(L("输出文件", "Output")), st.recordedFile ?: "?")
            }

            if (!st.recording && st.recordedFile != null) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t(L("已录制", "Recorded")), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(t(L("文件", "File")), st.recordedFile ?: "?")
                        KeyValueRow(t(L("样本", "Samples")), "${st.sampleCount}")
                        KeyValueRow(t(L("平均采样率", "Avg rate")), String.format("%.1f Hz", st.sampleRateHz))
                        OutlinedButton(onClick = {
                            val f = java.io.File(context.filesDir, "recordings/${st.recordedFile}")
                            if (f.exists()) com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/csv")
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(t(L("分享 CSV", "Share CSV")))
                        }
                    }
                }
            }
            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 多通道实时波形 */
@Composable
private fun LiveWaveform(series: List<FloatArray>, channels: Int) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            val colors = listOf(Color(0xFF4DD0E1), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFCE93D8))
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val w = size.width
                val h = size.height
                series.forEachIndexed { si, data ->
                    if (data.size < 2) return@forEachIndexed
                    val min = data.min()
                    val max = data.max()
                    val span = (max - min).let { if (it == 0f) 1f else it }
                    val path = Path()
                    data.forEachIndexed { i, v ->
                        val x = w * i / (data.size - 1)
                        val y = h * (si + 0.5f) / channels - ((v - min) / span - 0.5f) * h / channels * 0.9f
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = colors[si % colors.size], style = Stroke(width = 1.5f))
                }
            }
        }
    }
}
