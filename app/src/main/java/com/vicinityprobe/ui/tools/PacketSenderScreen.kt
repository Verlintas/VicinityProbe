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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.probe.ScanTargetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 数据包发送器:ASCII/HEX 双模式、载荷预设、循环发送、时间戳日志 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketSenderScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(ScanTargetConfig.target(context) ?: "") }
    var port by remember { mutableStateOf("80") }
    var proto by remember { mutableStateOf("TCP") }
    var payload by remember { mutableStateOf("") }
    var asciiMode by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableStateOf("1") }
    var intervalMs by remember { mutableStateOf("200") }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val ts = SimpleDateFormat("HH:mm:ss.SSS", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("数据包发送器", "Packet sender"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
                actions = {
                    IconButton(onClick = { log = emptyList() }) { Icon(Icons.Filled.Delete, contentDescription = "clear") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "⚠️ " + t(L("发送自定义数据包属主动网络行为,仅限你有权访问的目标", "Sending custom packets is active network activity — authorized targets only")),
                style = MaterialTheme.typography.labelSmall,
                color = com.vicinityprobe.ui.components.WarningColor,
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(t(L("目标", "Target")) + " IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = repeatCount, onValueChange = { repeatCount = it }, label = { Text(t(L("次数", "Count"))) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = intervalMs, onValueChange = { intervalMs = it }, label = { Text(t(L("间隔ms", "Interval ms"))) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("TCP", "UDP").forEachIndexed { i, p ->
                            SegmentedButton(
                                selected = proto == p,
                                onClick = { proto = p },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            ) { Text(p) }
                        }
                    }
                    // 输入模式
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow {
                            listOf("HEX" to false, "ASCII" to true).forEachIndexed { i, (label, ascii) ->
                                SegmentedButton(
                                    selected = asciiMode == ascii,
                                    onClick = { asciiMode = ascii },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                                ) { Text(label) }
                            }
                        }
                        Text(
                            t(L("载荷预设", "Payload presets")),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    // 载荷预设
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = false, onClick = { payload = "474554202f20485454502f312e310d0a486f73743a207461726765740d0a0d0a"; asciiMode = false }, label = { Text("HTTP GET") })
                        FilterChip(selected = false, onClick = { payload = "1b" + "00".repeat(47); asciiMode = false }, label = { Text("NTP") })
                        FilterChip(selected = false, onClick = { payload = "" }, label = { Text(t(L("空探测", "Empty"))) })
                    }
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text(if (asciiMode) t(L("载荷(ASCII 文本)", "Payload (ASCII)")) else t(L("载荷(十六进制)", "Payload (hex)"))) },
                        placeholder = { Text(if (asciiMode) "GET / HTTP/1.1" else "474554202f20485454502f312e31") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Button(
                        onClick = {
                            val host = target.trim()
                            val p = port.toIntOrNull() ?: return@Button
                            val count = (repeatCount.toIntOrNull() ?: 1).coerceIn(1, 100)
                            val interval = (intervalMs.toLongOrNull() ?: 200L).coerceAtLeast(20L)
                            if (host.isEmpty()) return@Button
                            busy = true
                            scope.launch {
                                val bytes = if (asciiMode) payload.toByteArray(Charsets.ISO_8859_1)
                                else try {
                                    payload.trim().replace(" ", "").chunked(2)
                                        .filter { it.length == 2 }.map { it.toInt(16).toByte() }.toByteArray()
                                } catch (_: Throwable) { ByteArray(0) }
                                for (i in 0 until count) {
                                    val res = withContext(Dispatchers.IO) {
                                        if (proto == "TCP") tcpSend(host, p, bytes) else udpSend(host, p, bytes)
                                    }
                                    log = listOf("[${ts.format(Date())}] #${i + 1} $res") + log
                                    if (i < count - 1) delay(interval)
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Text(if (busy) t(L("发送中…", "Sending…")) else t(L("发送", "Send")))
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t(L("日志", "Log")), style = MaterialTheme.typography.titleSmall)
                    if (log.isEmpty()) {
                        Text(t(L("(空)", "(empty)")), style = MaterialTheme.typography.bodySmall)
                    } else {
                        log.take(30).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun hexDump(bytes: ByteArray, max: Int = 512): String {
    val sb = StringBuilder()
    val n = minOf(bytes.size, max)
    for (i in 0 until n) {
        sb.append(String.format("%02X ", bytes[i].toInt() and 0xFF))
        if ((i + 1) % 16 == 0) sb.append("\n")
    }
    val ascii = StringBuilder()
    for (i in 0 until n) {
        val c = bytes[i].toInt().toChar()
        ascii.append(if (c.code in 32..126) c else '.')
    }
    return "HEX[${bytes.size}B]:\n$sb\nASCII: $ascii"
}

private fun tcpSend(host: String, port: Int, payload: ByteArray): String {
    return try {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 2000)
        s.soTimeout = 2000
        if (payload.isNotEmpty()) {
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
        }
        val buf = ByteArray(4096)
        val n = try { s.inputStream.read(buf) } catch (_: Throwable) { -1 }
        s.close()
        if (n > 0) "connected, resp ${n}B:\n" + hexDump(buf.copyOf(n)) else "connected, no response"
    } catch (e: Exception) {
        "TCP ${e.javaClass.simpleName}"
    }
}

private fun udpSend(host: String, port: Int, payload: ByteArray): String {
    return try {
        val socket = DatagramSocket()
        socket.soTimeout = 2000
        val data = if (payload.isEmpty()) byteArrayOf(0x00) else payload
        socket.send(DatagramPacket(data, data.size, java.net.InetAddress.getByName(host), port))
        val resp = ByteArray(4096)
        val pkt = DatagramPacket(resp, resp.size)
        val n = try { socket.receive(pkt); pkt.length } catch (_: Throwable) { -1 }
        socket.close()
        if (n > 0) "sent ${data.size}B, resp ${n}B:\n" + hexDump(resp.copyOf(n)) else "sent ${data.size}B, no response"
    } catch (e: Exception) {
        "UDP ${e.javaClass.simpleName}"
    }
}
