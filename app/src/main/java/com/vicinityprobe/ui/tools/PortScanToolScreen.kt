/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.probe.PortServices
import com.vicinityprobe.probe.ScanTargetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** 自定义端口范围扫描器:起止端口 + 并发 + 超时 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortScanToolScreen(nav: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(ScanTargetConfig.target(context) ?: "") }
    var portStart by remember { mutableStateOf("1") }
    var portEnd by remember { mutableStateOf("1024") }
    var timeoutMs by remember { mutableStateOf("300") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var openPorts by remember { mutableStateOf<List<Pair<Int, Long>>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("端口范围扫描", "Port range scan"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "⚠️ " + t(L("端口扫描属主动网络探测,仅限你有权访问的目标", "Port scanning is active probing — authorized targets only")),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE65100),
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(t(L("目标", "Target"))) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = portStart, onValueChange = { portStart = it }, label = { Text(t(L("起始端口", "Start"))) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = portEnd, onValueChange = { portEnd = it }, label = { Text(t(L("结束端口", "End"))) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = timeoutMs, onValueChange = { timeoutMs = it }, label = { Text(t(L("超时ms", "Timeout ms"))) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            val host = target.trim()
                            val s = portStart.toIntOrNull() ?: return@Button
                            val e = portEnd.toIntOrNull() ?: return@Button
                            val to = timeoutMs.toIntOrNull() ?: 300
                            if (host.isEmpty() || e < s || e - s > 65535) return@Button
                            busy = true
                            progress = t(L("扫描中…", "Scanning…"))
                            openPorts = emptyList()
                            scope.launch {
                                val found = withContext(Dispatchers.IO) { scanRange(host, s, e, to) }
                                openPorts = found
                                progress = t(L("完成", "Done")) + ": ${found.size} " + t(L("个开放端口", "open"))
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(t(L("扫描", "Scan")))
                    }
                    Text(progress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (openPorts.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(openPorts, key = { it.first }) { (port, ms) ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$port/tcp ${PortServices.ports[port] ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
                                Text("${ms}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun scanRange(host: String, start: Int, end: Int, timeout: Int): List<Pair<Int, Long>> {
    val found = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Long>>()
    val ports = (start..end).toList()
    val threads = 40
    val chunkSize = (ports.size + threads - 1) / threads
    val chunks = ports.chunked(chunkSize)
    val workers = chunks.map { chunk ->
        Thread {
            chunk.forEach { port ->
                try {
                    val t0 = System.nanoTime()
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), timeout)
                        found.add(port to (System.nanoTime() - t0) / 1_000_000)
                    }
                } catch (_: Throwable) {}
            }
        }
    }
    workers.forEach { it.start() }
    workers.forEach { it.join() }
    return found.sortedBy { it.first }
}
