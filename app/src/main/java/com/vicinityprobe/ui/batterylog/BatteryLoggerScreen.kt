/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.batterylog

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.vicinityprobe.ui.components.StatPill
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryLoggerScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: BatteryLoggerViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.logging) com.vicinityprobe.ui.components.rememberKeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("电池放电记录", "Battery discharge log"))) },
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
                    Text(t(L("记录电压/电流/温度曲线,估算放电速率与剩余续航", "Log voltage / current / temperature curves — discharge rate and range estimate")), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start() }, enabled = !st.logging) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始记录", "Log")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.logging) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }

            st.current?.let { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill("${c.levelPct}%", t(L("电量", "level")), Modifier.weight(1f))
                    StatPill(String.format("%.2f V", c.voltageMv / 1000.0), t(L("电压", "voltage")), Modifier.weight(1f))
                    StatPill(
                        if (c.currentMa < 0) String.format("%.0f mA", -c.currentMa) else String.format("%+.0f mA", c.currentMa),
                        t(L("电流", "current")),
                        Modifier.weight(1f),
                    )
                }
                if (st.samples.size >= 2) {
                    BatteryChart(st.samples)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t(L("统计分析", "Analysis")), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow(t(L("起始电量", "Start level")), "${st.startLevelPct}%")
                            KeyValueRow(t(L("放电速率", "Discharge rate")), String.format("%.2f %%/h", st.dischargeRatePctPerHour), primary = true)
                            KeyValueRow(t(L("预计剩余", "Est. remaining")), if (st.estHoursLeft > 0) String.format("%.1f h", st.estHoursLeft) else "—")
                            KeyValueRow(t(L("平均功耗", "Avg power")), String.format("%.2f W", st.avgPowerW))
                            KeyValueRow(t(L("当前温度", "Temp")), String.format("%.1f °C", st.current?.tempC ?: 0.0))
                            KeyValueRow(t(L("记录时长", "Duration")), "${st.samples.size} " + t(L("秒", "s")))
                        }
                    }
                    OutlinedButton(onClick = {
                        val f = File(context.cacheDir, "battery_${System.currentTimeMillis()}.csv")
                        f.writeText(vm.exportCsv())
                        com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/csv")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Share, contentDescription = null); Text(t(L("导出 CSV", "Export CSV")))
                    }
                }
            }
            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 电压 + 温度 + 电量三曲线 */
@Composable
private fun BatteryChart(samples: List<BatterySample>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.vicinityprobe.model.trBilingual("曲线: 电压(青) · 温度(黄) · 电量(绿)|Curves: voltage (cyan) · temp (amber) · level (green)", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val voltageColor = Color(0xFF4DD0E1)
            val tempColor = Color(0xFFFFB74D)
            val levelColor = Color(0xFF81C784)
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val w = size.width
                val h = size.height
                val pad = 6.dp.toPx()
                fun norm(v: Float, min: Float, max: Float) = pad + (h - 2 * pad) * (1f - ((v - min) / (max - min).coerceAtLeast(0.01f)))
                fun series(f: (BatterySample) -> Float, min: Float, max: Float, color: Color) {
                    val path = Path()
                    samples.forEachIndexed { i, s ->
                        val x = pad + (w - 2 * pad) * (i.toFloat() / (samples.size - 1).coerceAtLeast(1))
                        val y = norm(f(s), min, max)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = 1.5f))
                }
                val volts = samples.map { it.voltageMv / 1000f }
                series({ it.voltageMv / 1000f }, volts.min(), volts.max(), voltageColor)
                val temps = samples.map { it.tempC.toFloat() }
                series({ it.tempC.toFloat() }, temps.min(), temps.max(), tempColor)
                series({ it.levelPct.toFloat() }, 0f, 100f, levelColor)
            }
        }
    }
}
