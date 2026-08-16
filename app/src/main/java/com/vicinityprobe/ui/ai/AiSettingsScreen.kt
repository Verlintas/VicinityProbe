/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.ai

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.ai.AiClient
import com.vicinityprobe.ai.AiConfigStore
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: AiViewModel = viewModel()
    val initial = remember { vm.config() }

    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var sanitize by remember { mutableStateOf(initial.sanitize) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        vm.saveConfig(
            AiConfigStore.Config(
                baseUrl = baseUrl, apiKey = apiKey, model = model, sanitize = sanitize,
            ),
        )
        testResult = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("AI 深度分析设置", "AI deep analysis settings"))) },
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
                    Text(t(L("配置 OpenAI 兼容 API(OpenAI / DeepSeek / Moonshot / 本地 Ollama…)", "Configure an OpenAI-compatible API (OpenAI / DeepSeek / Moonshot / local Ollama…)")), style = MaterialTheme.typography.bodySmall)
                    Text(
                        t(L("数据将发送到你配置的服务;若使用本地 Ollama 则数据不出设备", "Data is sent to your configured service; with local Ollama nothing leaves the device")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t(L("预设服务", "Presets")), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiClient.PRESETS.forEach { (name, url, mdl) ->
                            FilterChip(
                                selected = baseUrl == url,
                                onClick = { baseUrl = url; model = mdl },
                                label = { Text(name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(L("发送前脱敏(移除位置/MAC/SSID 等)", "Sanitize before sending (strip location/MAC/SSID)")), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = sanitize, onCheckedChange = { sanitize = it })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    save()
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = try {
                            val cfg = AiConfigStore.Config(baseUrl = baseUrl, apiKey = apiKey, model = model)
                            val reply = AiClient(cfg).test()
                            "✓ " + t(L("连接成功:", "Connected:")) + " " + reply.take(60)
                        } catch (e: Exception) {
                            "✗ " + (e.message ?: "error")
                        }
                        testing = false
                    }
                }, enabled = apiKey.isNotBlank() && !testing, modifier = Modifier.weight(1f)) {
                    Text(if (testing) t(L("测试中…", "Testing…")) else t(L("保存并测试", "Save & test")))
                }
                Button(onClick = { save(); nav.popBackStack() }, enabled = apiKey.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(t(L("保存", "Save")))
                }
            }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text(
                t(L("API Key 使用系统 Keystore 加密存储,不会明文落盘", "The API key is encrypted with the system Keystore — never stored in plaintext")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
