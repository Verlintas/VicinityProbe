/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.speedtest

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.components.KeyValueRow
import com.vicinityprobe.ui.components.rememberAppHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: SpeedTestViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    if (st.phase == SpeedPhase.LATENCY || st.phase == SpeedPhase.DOWNLOAD || st.phase == SpeedPhase.UPLOAD) {
        com.vicinityprobe.ui.components.rememberKeepScreenOn()
    }

    val running = st.phase == SpeedPhase.LATENCY || st.phase == SpeedPhase.DOWNLOAD || st.phase == SpeedPhase.UPLOAD

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("网络测速", "Speed test"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 速度环
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box50()
                    val mainValue = when (st.phase) {
                        SpeedPhase.DOWNLOAD -> String.format("%.1f", st.downloadMbps)
                        SpeedPhase.UPLOAD -> String.format("%.1f", st.uploadMbps)
                        SpeedPhase.DONE -> String.format("%.1f", st.downloadMbps)
                        else -> "—"
                    }
                    com.vicinityprobe.ui.components.AnimatedNumber(
                        value = mainValue,
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when (st.phase) {
                            SpeedPhase.DOWNLOAD -> t(L("下载 Mbps", "download Mbps"))
                            SpeedPhase.UPLOAD -> t(L("上传 Mbps", "upload Mbps"))
                            SpeedPhase.DONE -> t(L("下载 Mbps", "download Mbps"))
                            SpeedPhase.LATENCY -> t(L("测延迟中…", "measuring latency…"))
                            SpeedPhase.IDLE -> t(L("点击开始测速", "Tap start"))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running) {
                        LinearProgressIndicator(progress = { st.phaseProgress.coerceIn(0.02f, 1f) }, Modifier.fillMaxWidth())
                    }
                }
            }

            Button(
                onClick = { haptics.confirm(); vm.start() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(t(L("开始测速", "Start test")))
            }

            if (st.phase != SpeedPhase.IDLE) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t(L("结果", "Results")), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(t(L("延迟", "Latency")), "${st.latencyMs} ms", primary = true)
                        KeyValueRow(t(L("抖动", "Jitter")), "${st.jitterMs} ms")
                        KeyValueRow(t(L("下载", "Download")), String.format("%.2f Mbps", st.downloadMbps))
                        KeyValueRow(t(L("上传", "Upload")), String.format("%.2f Mbps", st.uploadMbps))
                        if (st.downloadMbps > 0 && st.uploadMbps > 0) {
                            val rating = when {
                                st.downloadMbps >= 100 -> t(L("极快 (≥100 Mbps)", "Excellent (≥100 Mbps)"))
                                st.downloadMbps >= 30 -> t(L("快 (≥30 Mbps)", "Fast (≥30 Mbps)"))
                                st.downloadMbps >= 5 -> t(L("一般 (≥5 Mbps)", "Fair (≥5 Mbps)"))
                                else -> t(L("较慢 (<5 Mbps)", "Slow (<5 Mbps)"))
                            }
                            KeyValueRow(t(L("综合评级", "Rating")), rating)
                        }
                    }
                }
            }
            Text(
                t(L("测速服务器: Cloudflare 边缘节点;上传发送 16MB 随机数据", "Server: Cloudflare edge; upload sends 16 MB random data")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Box50() {
    androidx.compose.foundation.layout.Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(120.dp), strokeWidth = 8.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
    }
}
