/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.service.WebServerService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebConsoleScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: WebConsoleViewModel = viewModel()

    var running by remember { mutableStateOf(vm.isRunning()) }
    val ip = remember { WebServerService.localIp() }

    LaunchedEffect(Unit) {
        while (true) {
            running = WebServerService.isRunning()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("Web 控制台", "Web console"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t(L("在电脑浏览器打开地址,查看全部历史报告、下载原始样本 CSV 与 pcap,或远程触发扫描", "Open the address in a desktop browser: browse all reports, download raw CSV & pcap, trigger remote scans")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "⚠️ " + t(L("仅限可信局域网使用,不要暴露到公网", "LAN only — never expose to the public internet")),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100),
                    )
                    if (running) {
                        val url = "http://${ip ?: "?"}:${WebServerService.port()}"
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(url, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("url", url))
                            }) { Icon(Icons.Filled.ContentCopy, contentDescription = "copy", modifier = Modifier.padding(start = 8.dp)) }
                        }
                        if (ip == null) {
                            Text(t(L("未找到局域网 IP(WiFi 可能未连接)", "No LAN IP found (WiFi may be off)")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.start() }, enabled = !running) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(t(L("启动", "Start")))
                        }
                        OutlinedButton(onClick = { vm.stop() }, enabled = running) {
                            Icon(Icons.Filled.Stop, contentDescription = null); Text(t(L("停止", "Stop")))
                        }
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t(L("接口", "Endpoints")), style = MaterialTheme.typography.titleSmall)
                    Text("/  ·  /api/reports  ·  /api/report/{id}  ·  /api/capture  ·  /api/capabilities  ·  /api/scan (POST)  ·  /download/report/{id}  ·  /download/samples/{id}/…  ·  /download/pcap", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
