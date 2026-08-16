/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.btanalysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.KeyValueRow
import com.vicinityprobe.ui.components.StatPill
import com.vicinityprobe.ui.components.WarningNote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtAnalysisScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: BtAnalysisViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()

    var durationSec by remember { mutableStateOf(10) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("蓝牙深度分析", "Bluetooth deep analysis"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("扫描周边蓝牙设备,统计每个设备的 RSSI 分布与厂商", "Scan nearby Bluetooth devices, per-device RSSI distribution and vendor")), style = MaterialTheme.typography.bodySmall)
                    WarningNote(t(L("蓝牙扫描属合规高风险项,仅限你有权访问的环境", "Bluetooth scanning is a compliance-flagged action — authorized environments only")))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5 to "5s", 10 to "10s", 20 to "20s").forEach { (sec, label) ->
                            FilterChip(selected = durationSec == sec, onClick = { durationSec = sec }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.start(durationSec) }, enabled = !st.scanning) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始扫描", "Scan")))
                        }
                        OutlinedButton(onClick = { vm.stop() }, enabled = st.scanning) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                    if (st.scanning) {
                        LinearProgressIndicator(progress = { st.elapsedSec.toFloat() / durationSec }, Modifier.fillMaxWidth())
                    }
                }
            }

            if (st.scanning || st.devices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill("${st.devices.size}", t(L("设备", "devices")), Modifier.weight(1f))
                    StatPill("${st.totalPackets}", t(L("信号样本", "samples")), Modifier.weight(1f))
                    StatPill(
                        if (st.devices.isEmpty()) "—" else "${st.devices.count { it.vendor != "?" }}",
                        t(L("识别厂商", "vendors")),
                        Modifier.weight(1f),
                    )
                }
            }

            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (st.devices.isEmpty() && !st.scanning) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t(L("尚未扫描,点击开始", "Not scanned yet — tap Scan")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (st.devices.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(st.devices, key = { it.address }) { d ->
                        BtDeviceCard(d, lang)
                    }
                }
            }
        }
    }
}

@Composable
private fun BtDeviceCard(d: BtDeviceStats, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    String.format("%d dBm", d.avgRssi),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        d.avgRssi >= -60 -> Color(0xFF2E7D32)
                        d.avgRssi >= -75 -> Color(0xFFF9A825)
                        else -> Color(0xFFC62828)
                    },
                )
            }
            Text(
                "${d.address} · ${d.type}" + (if (d.bonded) " · " + trBilingual("已配对|paired", lang) else "") +
                    " · " + (if (d.vendor != "?") d.vendor else trBilingual("未知厂商|unknown", lang)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueRow(
                trBilingual("RSSI 分布|RSSI range", lang),
                "${d.minRssi} … ${d.avgRssi} … ${d.maxRssi} dBm (${d.rssiSamples.size})",
            )
            // 信号质量条(平均 RSSI 相对 -100..0 归一化)
            val fill = ((d.avgRssi + 100).coerceAtLeast(0) / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fill }, Modifier.fillMaxWidth())
        }
    }
}
