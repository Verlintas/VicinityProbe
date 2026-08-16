/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.wifimap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
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
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.KeyValueRow
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiMapScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: WifiMapViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    com.vicinityprobe.ui.components.rememberKeepScreenOn()   // 记录地图期间屏幕常亮
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()

    var live by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(live) {
        if (live) {
            while (live) {
                vm.refreshCurrent()
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("WiFi 信号地图", "WiFi signal map"))) },
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
                    Text(t(L("在室内走动,每隔几步记录一个采样点,生成信号热力图", "Walk around indoors, record a sample every few steps to build a signal heatmap")), style = MaterialTheme.typography.bodySmall)
                    com.vicinityprobe.ui.components.WarningNote(t(L("需要位置权限与 WiFi 扫描权限", "Requires location & WiFi-scan permissions")))
                    KeyValueRow(t(L("当前信号", "Current")), "${st.currentSsid}  ${st.currentRssi} dBm", primary = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.recordPoint() }) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null); Text(t(L("记录当前点", "Record point")))
                        }
                        OutlinedButton(onClick = { live = !live }) {
                            Text(if (live) t(L("停止刷新", "Stop live")) else t(L("实时刷新", "Live")))
                        }
                        OutlinedButton(onClick = { haptics.heavy(); vm.clear() }, enabled = st.samples.isNotEmpty()) {
                            Icon(Icons.Filled.Delete, contentDescription = null); Text(t(L("清空", "Clear")))
                        }
                    }
                    Text("${st.samples.size} " + t(L("个采样点", "samples")), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (st.samples.isNotEmpty()) {
                Heatmap(st.samples, lang)
                OutlinedButton(onClick = {
                    val f = File(context.cacheDir, "wifimap_${System.currentTimeMillis()}.csv")
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

/** 信号热力图:采样点归一化到方格,信号强度颜色映射 */
@Composable
private fun Heatmap(samples: List<WifiSample>, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trBilingual("信号热力图(相对位置)|Signal heatmap (relative)", lang), style = MaterialTheme.typography.titleSmall)
            val good = Color(0xFF2E7D32)
            val mid = Color(0xFFF9A825)
            val bad = Color(0xFFC62828)
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                val w = size.width
                val h = size.height
                val pad = 16.dp.toPx()
                val latMin = samples.minOf { it.lat }
                val latMax = samples.maxOf { it.lat }
                val lonMin = samples.minOf { it.lon }
                val lonMax = samples.maxOf { it.lon }
                val latSpan = (latMax - latMin).let { if (it < 1e-6) 1e-6 else it }
                val lonSpan = (lonMax - lonMin).let { if (it < 1e-6) 1e-6 else it }
                // 网格 12x12,插值平均
                val gridSize = 12
                val cellW = (w - 2 * pad) / gridSize
                val cellH = (h - 2 * pad) / gridSize
                val grid = Array(gridSize) { FloatArray(gridSize) }
                val counts = Array(gridSize) { IntArray(gridSize) }
                samples.forEach { s ->
                    val gx = (((s.lon - lonMin) / lonSpan) * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
                    val gy = (gridSize - 1) - (((s.lat - latMin) / latSpan) * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
                    grid[gy][gx] += s.rssi.toFloat()
                    counts[gy][gx]++
                }
                for (gy in 0 until gridSize) {
                    for (gx in 0 until gridSize) {
                        if (counts[gy][gx] == 0) continue
                        val avg = grid[gy][gx] / counts[gy][gx]
                        val color = when {
                            avg >= -55 -> good
                            avg >= -75 -> mid
                            else -> bad
                        }
                        drawRect(
                            color = color.copy(alpha = 0.55f),
                            topLeft = Offset(pad + gx * cellW, pad + gy * cellH),
                            size = androidx.compose.ui.geometry.Size(cellW, cellH),
                        )
                    }
                }
                // 采样点
                samples.forEach { s ->
                    val x = pad + (((s.lon - lonMin) / lonSpan) * (w - 2 * pad)).toFloat()
                    val y = pad + ((1f - ((s.lat - latMin) / latSpan).toFloat()) * (h - 2 * pad))
                    drawCircle(Color.White, radius = 5f, center = Offset(x, y))
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius = 5f, center = Offset(x, y), style = Stroke(1.5f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(good); Text("≥ -55", style = MaterialTheme.typography.labelSmall)
                LegendDot(mid); Text("-75…-55", style = MaterialTheme.typography.labelSmall)
                LegendDot(bad); Text("< -75", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawCircle(color)
    }
}
