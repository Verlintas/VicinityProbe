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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class HttpResult(
    val statusLine: String,
    val headers: String,
    val body: String,
    val elapsedMs: Long,
    val finalUrl: String,
    val bytes: Int,
)

/** HTTP 请求工具(curl 风格):方法/头/体/重定向/证书开关,Headers 与 Body 分栏 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpRequestScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val scope = rememberCoroutineScope()

    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")
    var url by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("GET") }
    var methodExpanded by remember { mutableStateOf(false) }
    var headers by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var followRedirects by remember { mutableStateOf(true) }
    var ignoreCert by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HttpResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(expanded = methodExpanded, onExpandedChange = { methodExpanded = it }) {
                            OutlinedTextField(
                                value = method,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Method") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded) },
                                modifier = Modifier.menuAnchor().weight(1f),
                            )
                            ExposedDropdownMenu(expanded = methodExpanded, onDismissRequest = { methodExpanded = false }) {
                                methods.forEach { m ->
                                    DropdownMenuItem(text = { Text(m) }, onClick = { method = m; methodExpanded = false })
                                }
                            }
                        }
                    }
                    // 头预设
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t(L("头预设", "Header presets")), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        FilterChip(selected = false, onClick = { headers = "User-Agent: Mozilla/5.0 (Linux; Android 16) VicinityProbe/0.9\nAccept: */*" }, label = { Text("Browser") })
                        FilterChip(selected = false, onClick = { headers = "Content-Type: application/json\nAccept: application/json" }, label = { Text("JSON API") })
                        FilterChip(selected = false, onClick = { headers = "" }, label = { Text(t(L("清空", "Clear"))) })
                    }
                    OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text(t(L("请求头(每行一个, name: value)", "Headers (one per line, name: value)"))) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(t(L("请求体(可选)", "Body (optional)"))) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Switch(checked = followRedirects, onCheckedChange = { followRedirects = it })
                        Text(t(L("跟随重定向", "Follow redirects")))
                        androidx.compose.material3.Switch(checked = ignoreCert, onCheckedChange = { ignoreCert = it })
                        Text(t(L("忽略证书错误", "Ignore cert errors")))
                    }
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { httpRequest(url, method, headers, body, followRedirects, ignoreCert) }
                                result = r
                                if (r != null) history = listOf(url) + history.filter { it != url }.take(9)
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

            // 历史
            if (history.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t(L("历史", "History")), style = MaterialTheme.typography.titleSmall)
                        history.take(5).forEach { u ->
                            TextButton(onClick = { url = u }) { Text(u.take(80), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            // 响应
            result?.let { r ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(r.statusLine, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("resp", "${r.statusLine}\n${r.headers}\n${r.body}"))
                            }) { Icon(Icons.Filled.ContentCopy, contentDescription = "copy") }
                        }
                        Text(
                            "${r.elapsedMs}ms · ${r.bytes}B · ${t(L("最终URL", "final URL"))}: ${r.finalUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(t(L("响应头", "Headers")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(r.headers, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text(t(L("响应体", "Body")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(r.body, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun httpRequest(urlStr: String, method: String, headersText: String, bodyText: String, follow: Boolean, ignoreCert: Boolean): HttpResult? {
    return try {
        val start = System.currentTimeMillis()
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = follow
        conn.requestMethod = method.uppercase()
        if (ignoreCert && conn is HttpsURLConnection) {
            try {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }), null)
                conn.sslSocketFactory = ctx.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            } catch (_: Throwable) {}
        }
        headersText.lines().filter { it.contains(":") }.forEach { line ->
            conn.setRequestProperty(line.substringBefore(':').trim(), line.substringAfter(':').trim())
        }
        if (bodyText.isNotEmpty() && method.uppercase() !in setOf("GET", "HEAD")) {
            conn.doOutput = true
            if (conn.getRequestProperty("Content-Type") == null) {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.write(bodyText.toByteArray())
        }
        val code = conn.responseCode
        val status = conn.responseMessage
        val respHeaders = conn.headerFields?.entries?.joinToString("\n") { (k, v) -> "${k ?: "HTTP"}: ${v.joinToString(", ")}" }
        val respBody = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        val finalUrl = conn.url.toString()
        val bytes = respBody.toByteArray().size
        conn.disconnect()
        val elapsed = System.currentTimeMillis() - start
        HttpResult("HTTP $code $status", respHeaders ?: "", prettyJson(respBody).take(20000), elapsed, finalUrl, bytes)
    } catch (e: Exception) {
        HttpResult("ERROR ${e.javaClass.simpleName}", "", e.message ?: "", 0, urlStr, 0)
    }
}

/** 尝试 JSON 缩进,失败原样返回 */
private fun prettyJson(text: String): String {
    if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) return text
    return try {
        val element = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.parseToJsonElement(text)
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    } catch (_: Throwable) { text }
}
