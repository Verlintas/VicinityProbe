/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.gnss

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.KeyValueRow
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GnssScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: GnssViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("GNSS 卫星观测", "GNSS satellite view"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!st.gpsOn) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        trBilingual("GPS 未开启或无权限,请在系统设置中开启定位|GPS off or no permission — enable location in system settings", lang),
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (st.visible > 0) {
                SkyPlot(st.satellites)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t(L("观测统计", "Observation stats")), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(t(L("可见卫星", "Visible")), "${st.visible}", primary = true)
                        KeyValueRow(t(L("参与定位", "Used in fix")), "${st.used}")
                        KeyValueRow(t(L("平均 C/N0", "Mean C/N0")), String.format("%.1f dB-Hz", st.meanCn0))
                        KeyValueRow(t(L("最强卫星", "Best satellite")), st.bestSatellite)
                        val byConst = st.satellites.groupBy { it.constellation }
                        byConst.forEach { (c, sats) ->
                            KeyValueRow(c, "${sats.size} · ${sats.count { it.usedInFix }} " + t(L("参与", "used")), primary = c == "GPS")
                        }
                    }
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t(L("卫星列表(按信号强度)", "Satellites by signal")), style = MaterialTheme.typography.titleSmall)
                        st.satellites.take(16).forEach { s ->
                            KeyValueRow(
                                "${s.constellation}#${s.svid} " + (if (s.usedInFix) "✓" else ""),
                                String.format("%.0f dB-Hz · az %.0f° · el %.0f°", s.cn0, s.azimuth, s.elevation),
                            )
                        }
                    }
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        trBilingual("等待卫星数据…(需在户外)|Waiting for satellite data… (go outdoors)", lang),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 天空图:方位角 → 极坐标,仰角 → 半径;颜色按星座 */
@Composable
private fun SkyPlot(satellites: List<GnssSatellite>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(trBilingual("卫星天空图(中心 = 天顶)|Sky plot (center = zenith)", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val primary = MaterialTheme.colorScheme.primary
            val onSurface = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxWidth().height(260.dp).padding(8.dp)) {
                val w = size.width
                val h = size.height
                val cx = w / 2
                val cy = h / 2
                val r = minOf(w, h) / 2 - 16.dp.toPx()
                // 同心圆:30°/60° 仰角圈 + 外圈
                drawCircle(primary.copy(alpha = 0.1f), radius = r, center = Offset(cx, cy))
                drawCircle(primary.copy(alpha = 0.3f), radius = r * 2f / 3f, center = Offset(cx, cy), style = Stroke(1f))
                drawCircle(primary.copy(alpha = 0.3f), radius = r / 3f, center = Offset(cx, cy), style = Stroke(1f))
                drawCircle(primary, radius = r, center = Offset(cx, cy), style = Stroke(2f))
                // 十字
                drawLine(onSurface.copy(alpha = 0.3f), Offset(cx - r, cy), Offset(cx + r, cy), 1f)
                drawLine(onSurface.copy(alpha = 0.3f), Offset(cx, cy - r), Offset(cx, cy + r), 1f)
                // 卫星:方位角 0° = 北(上)
                satellites.forEach { s ->
                    val rad = Math.toRadians(s.azimuth.toDouble())
                    val dist = r * (1f - (s.elevation / 90f).coerceIn(0f, 1f))
                    val x = cx + (dist * sin(rad)).toFloat()
                    val y = cy - (dist * cos(rad)).toFloat()
                    val color = constellationColor(s.constellation)
                    drawCircle(color, radius = 9f, center = Offset(x, y))
                    drawCircle(onSurface.copy(alpha = 0.5f), radius = 9f, center = Offset(x, y), style = Stroke(1f))
                    if (s.usedInFix) {
                        drawCircle(color.copy(alpha = 0.3f), radius = 14f, center = Offset(x, y), style = Stroke(2f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("GPS" to Color(0xFF4DD0E1), "GALILEO" to Color(0xFF81C784), "GLONASS" to Color(0xFFFFB74D), "BEIDOU" to Color(0xFFCE93D8), "QZSS" to Color(0xFFF06292), "OTHER" to Color(0xFF90A4AE))
                    .forEach { (name, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
            }
        }
    }
}

private fun constellationColor(c: String): Color = when (c) {
    "GPS" -> Color(0xFF4DD0E1)
    "GALILEO" -> Color(0xFF81C784)
    "GLONASS" -> Color(0xFFFFB74D)
    "BEIDOU" -> Color(0xFFCE93D8)
    "QZSS" -> Color(0xFFF06292)
    else -> Color(0xFF90A4AE)
}
