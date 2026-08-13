/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.service.CaptureStats
import com.vicinityprobe.service.FlowEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: CaptureViewModel = viewModel()
    val stats by vm.stats.collectAsStateWithLifecycle()

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("抓包分析", "Packet capture"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            t(L("基于系统 VPN 通道的免 root 抓包:统计协议/连接流/域名/HTTP 请求,可导出标准 pcap 供 Wireshark 分析", "Root-free capture via the system VPN channel: protocol/flow/domain/HTTP statistics, exportable as standard pcap for Wireshark")),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "⚠️ " + t(L("抓包会采集明文流量与 DNS,属高风险合规项,仅限对你有权访问的网络使用", "Capture collects plaintext traffic & DNS — high compliance risk; only use on networks you are authorized to inspect")),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val prepare = vm.prepareIntent()
                                    if (prepare != null) authLauncher.launch(prepare)
                                    else vm.start()
                                },
                                enabled = !stats.running,
                            ) { Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("开始抓包", "Start"))) }
                            OutlinedButton(onClick = { vm.stop() }, enabled = stats.running) {
                                Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                            }
                            if (!stats.running && stats.pcapFile != null) {
                                OutlinedButton(onClick = {
                                    com.vicinityprobe.service.CaptureController.pcapPath()?.let { f ->
                                        com.vicinityprobe.report.ReportExporter.shareFile(context, f, "application/vnd.tcpdump.pcap")
                                    }
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = null); Text("pcap")
                                }
                            }
                        }
                        if (stats.running) {
                            Text(
                                t(L("抓包中", "Capturing")) + " · " + fmtDuration(stats.durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard("TCP", stats.tcpPackets, Modifier.weight(1f))
                    StatCard("UDP", stats.udpPackets, Modifier.weight(1f))
                    StatCard("ICMP", stats.icmpPackets, Modifier.weight(1f))
                    StatCard("other", stats.otherPackets, Modifier.weight(1f))
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t(L("总览", "Totals")), style = MaterialTheme.typography.titleSmall)
                        Text("packets: ${stats.totalPackets}  bytes: ${fmtBytes(stats.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (stats.flows.isNotEmpty()) {
                item { SectionTitle(t(L("TCP/UDP 连接流", "Flows"))) }
                items(stats.flows, key = { "${it.proto}|${it.clientIp}:${it.clientPort}|${it.serverIp}:${it.serverPort}" }) { f ->
                    FlowRow(f, lang)
                }
            }

            if (stats.topDomains.isNotEmpty()) {
                item { SectionTitle(t(L("域名 TOP(DNS/SNI)", "Top domains (DNS/SNI)"))) }
                items(stats.topDomains, key = { it.first }) { (domain, count) ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("×$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (stats.httpRequests.isNotEmpty()) {
                item { SectionTitle(t(L("HTTP 请求", "HTTP requests"))) }
                items(stats.httpRequests, key = { it }) { req ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(req, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
                    }
                }
            }

            if (stats.pcapFile != null && !stats.running) {
                item {
                    Text(
                        t(L("pcap 文件", "pcap file")) + ": ${stats.pcapFile}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Text("", Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Long, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FlowRow(f: FlowEntry, lang: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${f.proto} ${f.clientIp}:${f.clientPort} → ${f.serverIp}:${f.serverPort}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(f.state, style = MaterialTheme.typography.labelSmall, color = if (f.state == "SYN" || f.state == "RST") Color(0xFFE65100) else MaterialTheme.colorScheme.primary)
            }
            Text(
                "↑ ${fmtBytes(f.sentBytes)}  ↓ ${fmtBytes(f.recvBytes)}  ${f.packets}pkt",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1 shl 30 -> String.format("%.2f GB", b / 1e9)
    b >= 1 shl 20 -> String.format("%.2f MB", b / 1e6)
    b >= 1 shl 10 -> String.format("%.1f KB", b / 1e3)
    else -> "$b B"
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}m ${s % 60}s"
}
