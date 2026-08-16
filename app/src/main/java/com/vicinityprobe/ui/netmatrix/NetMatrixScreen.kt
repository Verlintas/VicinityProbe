/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.netmatrix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.components.KeyValueRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetMatrixScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: NetMatrixViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.monitoring) com.vicinityprobe.ui.components.rememberKeepScreenOn()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("网络健康矩阵", "Network health matrix"))) },
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
                    Text(t(L("并行监测网关/DNS/公网节点,矩阵视图展示连通性与延迟", "Monitor gateway / DNS / public endpoints in parallel — matrix view of reachability & latency")), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start() }, enabled = !st.monitoring) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始监测", "Monitor")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.monitoring) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }

            if (st.targets.isNotEmpty()) {
                MatrixView(st)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t(L("详情", "Details")), style = MaterialTheme.typography.titleSmall)
                        st.targets.forEach { target ->
                            KeyValueRow(
                                "${target.name} (${target.host})",
                                if (target.reachable) "${target.latencyMs} ms" else t(L("不可达", "unreachable")),
                                primary = target.reachable,
                            )
                        }
                        KeyValueRow(t(L("轮次", "Round")), "${st.round}")
                    }
                }
            }
        }
    }
}

/** 矩阵雷达图:中心 = 本机,节点按延迟着色 */
@Composable
private fun MatrixView(st: NetMatrixState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.vicinityprobe.model.trBilingual("连通矩阵(中心 = 本机)|Reachability matrix (center = this device)", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val good = Color(0xFF2E7D32)
            val mid = Color(0xFFF9A825)
            val bad = Color(0xFFC62828)
            val primary = MaterialTheme.colorScheme.primary
            val targets = st.targets
            Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                val w = size.width
                val h = size.height
                val cx = w / 2
                val cy = h / 2
                val r = minOf(w, h) / 2 - 24.dp.toPx()
                // 外圈 + 刻度圈
                drawCircle(primary.copy(alpha = 0.1f), radius = r, center = Offset(cx, cy))
                drawCircle(primary.copy(alpha = 0.3f), radius = r * 0.6f, center = Offset(cx, cy), style = Stroke(1f))
                drawCircle(primary, radius = r, center = Offset(cx, cy), style = Stroke(2f))
                // 径向线
                val n = targets.size.coerceAtLeast(1)
                for (i in 0 until n) {
                    val ang = 2 * Math.PI * i / n - Math.PI / 2
                    drawLine(
                        primary.copy(alpha = 0.25f),
                        Offset(cx, cy),
                        Offset((cx + r * kotlin.math.cos(ang)).toFloat(), (cy + r * kotlin.math.sin(ang)).toFloat()),
                        1f,
                    )
                }
                // 节点:按延迟着色,不可达 = 红
                targets.forEachIndexed { i, target ->
                    val ang = 2 * Math.PI * i / n - Math.PI / 2
                    val dist = if (target.reachable) {
                        // 延迟 0-300ms 映射到 r*0.2..r
                        (r * (0.2 + 0.8 * (target.latencyMs.coerceIn(0, 300) / 300.0))).toFloat()
                    } else r
                    val x = (cx + dist * kotlin.math.cos(ang)).toFloat()
                    val y = (cy + dist * kotlin.math.sin(ang)).toFloat()
                    val color = when {
                        !target.reachable -> bad
                        target.latencyMs < 50 -> good
                        target.latencyMs < 150 -> mid
                        else -> bad
                    }
                    drawCircle(color, radius = 14f, center = Offset(x, y))
                    drawCircle(Color.White.copy(alpha = 0.8f), radius = 14f, center = Offset(x, y), style = Stroke(2f))
                    drawCircle(color, radius = 6f, center = Offset(x, y))
                }
                // 中心本机
                drawCircle(primary, radius = 10f, center = Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.8f), radius = 10f, center = Offset(cx, cy), style = Stroke(2f))
            }
            // 图例:节点名 → 颜色
            targets.forEach { target ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val color = when {
                        !target.reachable -> bad
                        target.latencyMs < 50 -> good
                        target.latencyMs < 150 -> mid
                        else -> bad
                    }
                    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                    Text("${target.name} · ${if (target.reachable) "${target.latencyMs}ms" else "✗"}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
