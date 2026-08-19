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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var temperature by remember { mutableStateOf(initial.temperature) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens) }
    var customPrompt by remember { mutableStateOf(initial.customPrompt) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 当前预设匹配的模型列表
    val currentPreset = AiClient.PRESETS.firstOrNull { it.baseUrl == baseUrl.trim().trimEnd('/') }

    fun save() {
        vm.saveConfig(
            AiConfigStore.Config(
                baseUrl = baseUrl, apiKey = apiKey, model = model, sanitize = sanitize,
                temperature = temperature, maxTokens = maxTokens, customPrompt = customPrompt,
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
                    Text(t(L("配置 OpenAI 兼容 API(OpenAI / DeepSeek / Kimi / 智谱 / 通义 / 本地 Ollama…)", "Configure an OpenAI-compatible API (OpenAI / DeepSeek / Kimi / GLM / Qwen / local Ollama…)")), style = MaterialTheme.typography.bodySmall)
                    Text(
                        t(L("数据将发送到你配置的服务;若使用本地 Ollama 则数据不出设备", "Data is sent to your configured service; with local Ollama nothing leaves the device")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t(L("预设服务(点选自动填充地址与模型)", "Presets (tap to auto-fill URL & model)")), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiClient.PRESETS.forEach { preset ->
                            FilterChip(
                                selected = baseUrl.trim().trimEnd('/') == preset.baseUrl,
                                onClick = { baseUrl = preset.baseUrl; model = preset.defaultModel },
                                label = { Text(preset.name) },
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
                    // 模型:下拉选择预设模型 + 可自由输入
                    Box {
                        OutlinedTextField(
                            value = model, onValueChange = { model = it },
                            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (currentPreset != null && currentPreset.models.size > 1) {
                                    IconButton(onClick = { modelMenuOpen = true }) {
                                        Icon(Icons.Filled.Search, contentDescription = "models")
                                    }
                                }
                            },
                        )
                        DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                            (currentPreset?.models ?: emptyList()).forEach { m ->
                                DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelMenuOpen = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(L("发送前脱敏(移除位置/MAC/SSID 等)", "Sanitize before sending (strip location/MAC/SSID)")), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = sanitize, onCheckedChange = { sanitize = it })
                    }
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t(L("高级设置", "Advanced")), style = MaterialTheme.typography.titleSmall)
                    Text(t(L("温度", "Temperature")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        value = temperature.toFloat(),
                        onValueChange = { temperature = it.toDouble() },
                        valueRange = 0f..1.5f,
                    )
                    Text(String.format("%.1f (%.0f)", temperature, temperature * 100) + " — " + t(L("低=严谨 高=发散", "low=precise  high=creative")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t(L("最大输出 token", "Max tokens")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { maxTokens = it.toInt() },
                        valueRange = 512f..8192f,
                        steps = 14,
                    )
                    Text("$maxTokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t(L("自定义系统提示词(留空用默认)", "Custom system prompt (blank = default)")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = customPrompt, onValueChange = { customPrompt = it },
                        label = { Text("System prompt") },
                        minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Ollama 自动识别
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("本地 Ollama 自动识别", "Local Ollama auto-detect")), style = MaterialTheme.typography.titleSmall)
                    Text(
                        t(L("模拟器会自动使用 10.0.2.2(宿主机映射);真机可一键探测局域网内的 Ollama", "Emulators auto-use 10.0.2.2 (host mapping); on real devices tap to probe the LAN for Ollama")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            probing = true
                            testResult = null
                            scope.launch {
                                val found = AiClient(AiConfigStore.Config()).probeLocalOllama(context)
                                if (found != null) {
                                    baseUrl = found
                                    model = AiClient.PRESETS.first { it.name.startsWith("Ollama") }.defaultModel
                                    testResult = "✓ " + t(L("找到本地 Ollama:", "Found local Ollama:")) + " $found"
                                } else {
                                    testResult = t(L("未发现局域网 Ollama(确认电脑已启动 Ollama 并允许局域网访问 OLLAMA_HOST=0.0.0.0)", "No local Ollama found (ensure it runs and listens on LAN: OLLAMA_HOST=0.0.0.0)"))
                                }
                                probing = false
                            }
                        },
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(if (probing) t(L("探测中…", "Probing…")) else t(L("探测局域网 Ollama", "Probe LAN for Ollama")))
                    }
                    if (AiClient.isEmulator()) {
                        Text(
                            t(L("当前为模拟器:宿主机固定为 10.0.2.2,已自动处理", "Emulator detected: host is 10.0.2.2 — handled automatically")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
                            val cfg = AiConfigStore.Config(baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey, model = model)
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
