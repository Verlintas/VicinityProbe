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
import androidx.compose.material.icons.filled.Send
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** HTTP 请求工具(curl 风格):方法/头/体/重定向,响应全文 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpRequestScreen(nav: NavController) {
    val lang = langOf(androidx.compose.ui.platform.LocalContext.current)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("GET") }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var followRedirects by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("HTTP 请求工具", "HTTP request tool"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "⚠️ " + t(L("仅限你有权访问的目标", "Authorized targets only")),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE65100),
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = method, onValueChange = { method = it }, label = { Text("Method") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text(t(L("请求头(每行一个, 冒号分隔)", "Headers (one per line, name: value)"))) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(t(L("请求体(可选)", "Body (optional)"))) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = followRedirects, onCheckedChange = { followRedirects = it })
                        Text(t(L("跟随重定向", "Follow redirects")), modifier = Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = {
                            busy = true
                            result = t(L("请求中…", "Sending…"))
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { httpRequest(url, method, headers, body, followRedirects) }
                                result = r
                                busy = false
                            }
                        },
                        enabled = !busy && url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Text(t(L("发送", "Send")))
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t(L("响应", "Response")), style = MaterialTheme.typography.titleSmall)
                    Text(
                        result.ifEmpty { t(L("(空)", "(empty)")) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun httpRequest(urlStr: String, method: String, headersText: String, bodyText: String, follow: Boolean): String {
    return try {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = follow
        conn.requestMethod = method.uppercase()
        headersText.lines().filter { it.contains(":") }.forEach { line ->
            conn.setRequestProperty(line.substringBefore(':').trim(), line.substringAfter(':').trim())
        }
        if (bodyText.isNotEmpty() && method.uppercase() !in setOf("GET", "HEAD")) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.write(bodyText.toByteArray())
        }
        val code = conn.responseCode
        val status = conn.responseMessage
        val respHeaders = conn.headerFields?.entries?.joinToString("\n") { (k, v) -> "${k ?: "HTTP"}: ${v.joinToString(", ")}" }
        val respBody = try {
            conn.inputStream.bufferedReader().use { it.readText() }.take(8192)
        } catch (_: Throwable) {
            conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(4096) ?: ""
        }
        val finalUrl = conn.url.toString()
        conn.disconnect()
        "HTTP $code $status\nURL: $finalUrl\n--- headers ---\n$respHeaders\n--- body ---\n$respBody"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message ?: ""}"
    }
}
