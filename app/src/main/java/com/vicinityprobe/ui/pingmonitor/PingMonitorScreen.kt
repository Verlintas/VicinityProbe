/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.pingmonitor

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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
fun PingMonitorScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: PingMonitorViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.running) com.vicinityprobe.ui.components.rememberKeepScreenOn()

    var target by remember { mutableStateOf(vm.defaultTarget()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("Ping 监视器", "Ping monitor"))) },
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
                    Text(t(L("持续 TCP ping(443 端口),统计延迟/抖动/丢包", "Continuous TCP ping (port 443): latency / jitter / packet loss")), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        singleLine = true,
                        label = { Text(t(L("目标主机", "Target host"))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.confirm(); vm.start(target) }, enabled = !st.running) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始", "Start")))
                        }
                        OutlinedButton(onClick = { haptics.confirm(); vm.stop() }, enabled = st.running) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }

            if (st.samples.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill("${st.avgMs} ms", t(L("平均", "avg")), Modifier.weight(1f))
                    StatPill("${st.jitterMs} ms", t(L("抖动", "jitter")), Modifier.weight(1f))
                    StatPill(
                        if (st.sent == 0) "—" else "${(st.lost * 100 / st.sent)}%",
                        t(L("丢包", "loss")),
                        Modifier.weight(1f),
                        valueColor = if (st.lost * 100 / (st.sent.coerceAtLeast(1)) >= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                LatencyChart(st.samples)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${st.target}", style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(t(L("样本/丢失", "samples / lost")), "${st.samples.size} / ${st.lost}")
                        KeyValueRow(t(L("最小/平均/最大", "min / avg / max")), "${st.minMs} / ${st.avgMs} / ${st.maxMs} ms")
                    }
                }
            }
        }
    }
}

/** 延迟序列图:绿线正常,红点丢包 */
@Composable
private fun LatencyChart(samples: List<Long>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(com.vicinityprobe.model.trBilingual("延迟序列|Latency series", langOf(LocalContext.current)), style = MaterialTheme.typography.titleSmall)
            val ok = samples.filter { it >= 0 }
            val maxV = (ok.maxOrNull() ?: 100L).coerceAtLeast(100L)
            val good = Color(0xFF2E7D32)
            val bad = MaterialTheme.colorScheme.error
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val w = size.width
                val h = size.height
                val pad = 4.dp.toPx()
                val path = Path()
                samples.forEachIndexed { i, v ->
                    val x = pad + (w - 2 * pad) * (i.toFloat() / (samples.size - 1).coerceAtLeast(1))
                    val y = if (v < 0) h - pad else pad + (h - 2 * pad) * (1f - (v.toFloat() / maxV))
                    if (v < 0) {
                        drawCircle(bad, radius = 3f, center = Offset(x, y))
                    } else {
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                drawPath(path, color = good, style = Stroke(width = 2f))
            }
            Text(com.vicinityprobe.model.trBilingual("红点 = 丢包|Red dots = timeouts", langOf(LocalContext.current)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
