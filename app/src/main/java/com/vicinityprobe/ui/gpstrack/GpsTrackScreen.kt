/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.gpstrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
fun GpsTrackScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: GpsTrackViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.recording) com.vicinityprobe.ui.components.rememberKeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("GPS 轨迹记录", "GPS track recorder"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("户外移动时记录轨迹,自动统计里程与速度(需 GPS 定位)", "Record a track while moving outdoors — distance & speed statistics (GPS fix required)")), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start() }, enabled = !st.recording) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始记录", "Record")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.recording) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                        OutlinedButton(onClick = { haptics.heavy(); vm.clear() }, enabled = st.points.isNotEmpty()) {
                            Icon(Icons.Filled.Delete, contentDescription = null); Text(t(L("清空", "Clear")))
                        }
                    }
                    if (st.recording) {
                        Text(t(L("记录中…", "Recording…")) + " ${st.points.size} " + t(L("点", "pts")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (st.points.size >= 2) {
                TrackMap(st.points)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill(fmtDist(st.distanceM), t(L("里程", "distance")), Modifier.weight(1f))
                    StatPill(fmtSpeed(st.avgSpeedMs), t(L("平均速度", "avg speed")), Modifier.weight(1f))
                    StatPill(fmtSpeed(st.maxSpeedMs), t(L("最高速度", "max speed")), Modifier.weight(1f))
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        KeyValueRow(t(L("时长", "Duration")), "${st.durationSec / 60}m ${st.durationSec % 60}s")
                        KeyValueRow(t(L("轨迹点", "Points")), "${st.points.size}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        val f = File(context.cacheDir, "track_${System.currentTimeMillis()}.kml")
                        f.writeText(vm.exportKml())
                        com.vicinityprobe.report.ReportExporter.shareFile(context, f, "application/vnd.google-earth.kml+xml")
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null); Text("KML")
                    }
                    OutlinedButton(onClick = {
                        val f = File(context.cacheDir, "track_${System.currentTimeMillis()}.csv")
                        f.writeText(vm.exportCsv())
                        com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/csv")
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null); Text("CSV")
                    }
                }
            }
        }
    }
}

/** 轨迹图:经纬度归一化到画布,点按速度着色 */
@Composable
private fun TrackMap(points: List<TrackPoint>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.vicinityprobe.model.trBilingual("轨迹(颜色 = 速度)|Track (color = speed)", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val slow = Color(0xFF1565C0)
            val fast = Color(0xFFC62828)
            val trackColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                val w = size.width
                val h = size.height
                val pad = 14.dp.toPx()
                val latMin = points.minOf { it.lat }
                val latMax = points.maxOf { it.lat }
                val lonMin = points.minOf { it.lon }
                val lonMax = points.maxOf { it.lon }
                val latSpan = (latMax - latMin).let { if (it < 1e-7) 1e-7 else it }
                val lonSpan = (lonMax - lonMin).let { if (it < 1e-7) 1e-7 else it }
                val maxSpeed = points.maxOf { it.speedMs }.coerceAtLeast(1f)
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = pad + ((p.lon - lonMin) / lonSpan * (w - 2 * pad)).toFloat()
                    val y = pad + ((1f - ((p.lat - latMin) / latSpan).toFloat()) * (h - 2 * pad))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = trackColor, style = Stroke(width = 4f))
                // 速度着色点
                points.forEach { p ->
                    val x = pad + ((p.lon - lonMin) / lonSpan * (w - 2 * pad)).toFloat()
                    val y = pad + ((1f - ((p.lat - latMin) / latSpan).toFloat()) * (h - 2 * pad))
                    val t01 = (p.speedMs / maxSpeed).coerceIn(0f, 1f)
                    val color = androidx.compose.ui.graphics.lerp(slow, fast, t01)
                    drawCircle(color, radius = 3.5f, center = Offset(x, y))
                }
                // 起点/终点标记
                val s0 = points.first()
                val s1 = points.last()
                drawCircle(Color(0xFF2E7D32), radius = 6f, center = Offset(
                    pad + ((s0.lon - lonMin) / lonSpan * (w - 2 * pad)).toFloat(),
                    pad + ((1f - ((s0.lat - latMin) / latSpan).toFloat()) * (h - 2 * pad)),
                ))
                drawCircle(Color(0xFFC62828), radius = 6f, center = Offset(
                    pad + ((s1.lon - lonMin) / lonSpan * (w - 2 * pad)).toFloat(),
                    pad + ((1f - ((s1.lat - latMin) / latSpan).toFloat()) * (h - 2 * pad)),
                ))
            }
            Text(
                com.vicinityprobe.model.trBilingual("绿 = 起点 · 红 = 终点 · 线色 = 速度(蓝慢→红快)|Green = start · red = end · line color = speed", langOf(LocalContext.current)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fmtDist(m: Double): String = if (m >= 1000) String.format("%.2f km", m / 1000) else String.format("%.0f m", m)
private fun fmtSpeed(ms: Double): String = if (ms >= 1) String.format("%.1f km/h", ms * 3.6) else String.format("%.0f m/s", ms)
