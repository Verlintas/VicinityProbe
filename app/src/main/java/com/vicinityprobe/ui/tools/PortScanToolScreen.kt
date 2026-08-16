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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.probe.PortServices
import com.vicinityprobe.probe.ScanTargetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PortScanResult(val port: Int, val ms: Long)

/** 端口范围扫描工具:预设组/并发可调/进度/取消/分享 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortScanToolScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(ScanTargetConfig.target(context) ?: "") }
    var portStart by remember { mutableStateOf("1") }
    var portEnd by remember { mutableStateOf("1024") }
    var timeoutMs by remember { mutableStateOf("300") }
    var concurrency by remember { mutableStateOf("40") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var progressPct by remember { mutableStateOf(0f) }
    var openPorts by remember { mutableStateOf<List<PortScanResult>>(emptyList()) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun preset(start: Int, end: Int) {
        portStart = start.toString(); portEnd = end.toString()
    }

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
                color = com.vicinityprobe.ui.components.WarningColor,
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(t(L("目标", "Target"))) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = portStart, onValueChange = { portStart = it }, label = { Text(t(L("起始", "Start"))) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = portEnd, onValueChange = { portEnd = it }, label = { Text(t(L("结束", "End"))) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = timeoutMs, onValueChange = { timeoutMs = it }, label = { Text("ms") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = concurrency, onValueChange = { concurrency = it }, label = { Text(t(L("并发", "Threads"))) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    // 预设端口组
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(t(L("预设", "Presets")), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        FilterChip(selected = false, onClick = { preset(1, 1024) }, label = { Text("1-1024") })
                        FilterChip(selected = false, onClick = { preset(1, 65535) }, label = { Text("1-65535") })
                        FilterChip(selected = false, onClick = { portStart = "80,443,8080,8443,8888,3000,8000,8081,9000,9090,5601,9200"; portEnd = "" }, label = { Text("Web") })
                        FilterChip(selected = false, onClick = { portStart = "1433,1521,3306,5432,6379,9200,27017,11211"; portEnd = "" }, label = { Text("DB") })
                        FilterChip(selected = false, onClick = { portStart = "1883,8883,4840,23,161,502,102,445,21,22"; portEnd = "" }, label = { Text("IoT") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val host = target.trim()
                                if (host.isEmpty()) return@Button
                                cancelFlag.set(false)
                                busy = true
                                openPorts = emptyList()
                                elapsedMs = 0
                                val startTime = System.currentTimeMillis()
                                val job = scope.launch {
                                    val (found, done) = withContext(Dispatchers.IO) {
                                        scanAdvanced(host, portStart, portEnd, timeoutMs.toIntOrNull() ?: 300, concurrency.toIntOrNull() ?: 40, cancelFlag, startTime) { pct ->
                                            progressPct = pct
                                        }
                                    }
                                    openPorts = found
                                    elapsedMs = done
                                    progress = t(L("完成", "Done")) + " · ${found.size} " + t(L("开放", "open")) + " · ${done}ms"
                                    busy = false
                                }
                                scanJob = job
                                progress = t(L("扫描中…", "Scanning…"))
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Text(t(L("扫描", "Scan")))
                        }
                        OutlinedButton(
                            onClick = { cancelFlag.set(true) },
                            enabled = busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Text(t(L("取消", "Cancel")))
                        }
                        if (!busy && openPorts.isNotEmpty()) {
                            IconButton(onClick = {
                                val report = buildString {
                                    appendLine("VicinityProbe port scan: $target (${elapsedMs}ms)")
                                    appendLine("open: ${openPorts.size}")
                                    openPorts.forEach { (p, ms) -> appendLine("$p/tcp ${PortServices.ports[p] ?: "unknown"} ${ms}ms") }
                                }
                                val f = java.io.File(context.cacheDir, "portscan.txt")
                                f.writeText(report)
                                com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/plain")
                            }) { Icon(Icons.Filled.Share, contentDescription = "share") }
                        }
                    }
                    if (busy) {
                        LinearProgressIndicator(progress = { progressPct }, Modifier.fillMaxWidth())
                    }
                    Text(progress, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (openPorts.isNotEmpty()) {
                Text(t(L("开放端口", "Open ports")) + ": ${openPorts.size}", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(openPorts, key = { it.port }) { (port, ms) ->
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

/** 支持 起止范围 或 逗号分隔端口列表;带进度与取消 */
private suspend fun scanAdvanced(
    host: String, startText: String, endText: String, timeout: Int, threads: Int,
    cancel: AtomicBoolean, startTime: Long, onProgress: (Float) -> Unit,
): Pair<List<PortScanResult>, Long> {
    val ports = if (startText.contains(",")) {
        startText.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct()
    } else {
        val s = startText.toIntOrNull() ?: 1
        val e = endText.toIntOrNull() ?: 1024
        if (e < s) emptyList() else (s..e).toList()
    }
    val total = ports.size
    if (total == 0) return emptyList<PortScanResult>() to 0L
    val scanned = AtomicInteger(0)
    val found = java.util.concurrent.ConcurrentLinkedQueue<PortScanResult>()
    val threadCount = threads.coerceIn(1, 200)
    val chunkSize = (total + threadCount - 1) / threadCount
    val chunks = ports.chunked(chunkSize)
    val workers = chunks.map { chunk ->
        Thread {
            chunk.forEach { port ->
                if (cancel.get()) return@forEach
                try {
                    val t0 = System.nanoTime()
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), timeout.coerceAtLeast(50))
                        found.add(PortScanResult(port, (System.nanoTime() - t0) / 1_000_000))
                    }
                } catch (_: Throwable) {}
                scanned.incrementAndGet()
            }
        }
    }
    workers.forEach { it.start() }
    // 进度轮询
    while (workers.any { it.isAlive }) {
        onProgress(scanned.get().toFloat() / total)
        kotlinx.coroutines.delay(100)
    }
    workers.forEach { it.join() }
    onProgress(1f)
    return (found.sortedBy { it.port }) to (System.currentTimeMillis() - startTime)
}
