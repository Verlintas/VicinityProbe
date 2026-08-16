/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.realtime

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: RealTimeViewModel = viewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    com.vicinityprobe.ui.components.rememberKeepScreenOn()   // 观测期间屏幕常亮
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()

    var selected by remember { mutableStateOf(WaveMode.ACCEL) }
    var settingsOpen by remember { mutableStateOf(false) }
    var alertEnabled by remember { mutableStateOf(vm.settings.enabled) }
    var noiseTh by remember { mutableStateOf(vm.settings.noiseDb.toString()) }
    var tempTh by remember { mutableStateOf(vm.settings.tempMax.toString()) }
    var lightTh by remember { mutableStateOf(vm.settings.lightMin.toString()) }

    LaunchedEffect(selected) { vm.start(selected) }

    // 离开页面时停止传感器/麦克风采样,避免后台持续耗电与告警
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("实时监测", "Real-time monitor"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
                actions = {
                    TextButton(onClick = { settingsOpen = true }) { Text(t(L("告警设置", "Alerts"))) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 波形源选择
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WaveMode.entries.forEach { m ->
                    FilterChip(
                        selected = selected == m,
                        onClick = { selected = m },
                        label = { Text(m.name) },
                    )
                }
                // 卡尔曼平滑开关
                val smooth by vm.smoothing.collectAsStateWithLifecycle()
                FilterChip(
                    selected = smooth,
                    onClick = { haptics.tap(); vm.toggleSmoothing() },
                    label = { Text(t(L("平滑", "Smooth"))) },
                )
            }

            // 实时数值卡
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    snap.labels.forEachIndexed { i, label ->
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(snap.values.getOrElse(i) { "—" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // 波形
            if (snap.mode == WaveMode.SPECTRUM) {
                SpectrumWaterfall(snap.spectrum ?: emptyList())
            } else if (snap.mode == WaveMode.ATTITUDE) {
                AttitudeLevel(snap.attitude)
            } else {
                Oscilloscope(snap.series, snap.labels)
            }
        }
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text(t(L("阈值告警", "Threshold alerts"))) },
            text = {
                val noiseOk = noiseTh.toIntOrNull() != null
                val tempOk = tempTh.toIntOrNull() != null
                val lightOk = lightTh.toIntOrNull() != null
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(t(L("启用告警", "Enabled")), modifier = Modifier.weight(1f))
                        Switch(checked = alertEnabled, onCheckedChange = { alertEnabled = it })
                    }
                    OutlinedTextField(value = noiseTh, onValueChange = { noiseTh = it }, label = { Text("Noise dB(A)") }, singleLine = true, isError = !noiseOk)
                    OutlinedTextField(value = tempTh, onValueChange = { tempTh = it }, label = { Text("Temp °C") }, singleLine = true, isError = !tempOk)
                    OutlinedTextField(value = lightTh, onValueChange = { lightTh = it }, label = { Text("Light lx (below)") }, singleLine = true, isError = !lightOk)
                    Text(t(L("超限时通知栏提醒(同一指标 5 秒内最多一次)", "Alerts fire via notification (max once per 5 s per metric)")), style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveSettings(
                            AlertSettings(
                                enabled = alertEnabled,
                                noiseDb = noiseTh.toIntOrNull() ?: 70,
                                tempMax = tempTh.toIntOrNull() ?: 35,
                                lightMin = lightTh.toIntOrNull() ?: 10,
                            ),
                        )
                        settingsOpen = false
                    },
                    enabled = noiseTh.toIntOrNull() != null && tempTh.toIntOrNull() != null && lightTh.toIntOrNull() != null,
                ) { Text(t(L("保存", "Save"))) }
            },
            dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text(t(L("取消", "Cancel"))) } },
        )
    }
}

@Composable
private fun AttitudeLevel(att: AttitudeSnapshot?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("attitude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 水平仪圆盘:气泡位置 = (pitch, roll) 的投影
            val primary = MaterialTheme.colorScheme.primary
            val tertiary = MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.fillMaxWidth().height(220.dp).padding(8.dp)) {
                val w = size.width
                val h = size.height
                val cx = w / 2
                val cy = h / 2
                val r = minOf(w, h) / 2 - 12.dp.toPx()
                // 外圈
                drawCircle(primary.copy(alpha = 0.15f), radius = r, center = Offset(cx, cy))
                drawCircle(primary, radius = r, center = Offset(cx, cy), style = Stroke(width = 2f))
                // 同心刻度圈(10° 一圈,气泡行程 ±40°)
                val travel = (r * 0.8f) / 40f
                for (deg in -40..40 step 10) {
                    drawCircle(
                        color = primary.copy(alpha = 0.3f),
                        radius = travel * deg,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1f),
                    )
                }
                // 十字线
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(cx - r, cy), Offset(cx + r, cy), 1f)
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(cx, cy - r), Offset(cx, cy + r), 1f)
                if (att != null) {
                    // 气泡:pitch 向右,roll 向上(设备右手系)
                    val bx = cx + (att.pitchDeg * travel).toFloat()
                    val by = cy - (att.rollDeg * travel).toFloat()
                    drawCircle(tertiary.copy(alpha = 0.6f), radius = 16.dp.toPx(), center = Offset(bx, by))
                    drawCircle(tertiary, radius = 8.dp.toPx(), center = Offset(bx, by))
                }
            }
            Text(
                if (att != null)
                    "roll ${String.format("%+.1f°", att.rollDeg)}  ·  pitch ${String.format("%+.1f°", att.pitchDeg)}  ·  稳定 ${String.format("%.0f%%", att.stability * 100)}"
                else "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Oscilloscope(series: List<FloatArray>, labels: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("oscilloscope", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                val w = size.width
                val h = size.height
                // 网格
                for (i in 0..4) {
                    val y = h * i / 4
                    drawLine(Color.Gray.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), 1f)
                }
                val colors = listOf(Color(0xFF4DD0E1), Color(0xFFFFB74D), Color(0xFF81C784))
                series.forEachIndexed { si, data ->
                    if (data.size < 2) return@forEachIndexed
                    val min = data.min()
                    val max = data.max()
                    val span = (max - min).let { if (it == 0f) 1f else it }
                    val path = Path()
                    data.forEachIndexed { i, v ->
                        val x = w * i / (data.size - 1)
                        val y = h * 0.9f - ((v - min) / span) * h * 0.8f
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = colors[si % colors.size], style = Stroke(width = 2f))
                }
            }
        }
    }
}

@Composable
private fun SpectrumWaterfall(rows: List<FloatArray>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("spectrum waterfall 0-8 kHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                if (rows.isEmpty()) return@Canvas
                val w = size.width
                val h = size.height
                val rowH = h / 40
                val cols = rows[0].size
                rows.forEachIndexed { ri, row ->
                    // 归一化到 0-80 dB 显示(蓝→黄→红)
                    for (ci in 0 until cols) {
                        val v = row[ci]
                        val norm = ((v + 20) / 90).coerceIn(0f, 1f)
                        drawRect(
                            color = if (norm < 0.33f) Color(0xFF1565C0) else if (norm < 0.66f) Color(0xFFF9A825) else Color(0xFFC62828),
                            topLeft = androidx.compose.ui.geometry.Offset(w * ci / cols, h - (ri + 1) * rowH),
                            size = androidx.compose.ui.geometry.Size(w / cols, rowH + 1f),
                        )
                    }
                }
            }
        }
    }
}
