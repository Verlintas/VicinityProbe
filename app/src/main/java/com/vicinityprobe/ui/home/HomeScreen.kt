/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.vicinityprobe.R
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.probe.CapabilityProbe
import com.vicinityprobe.probe.Perms
import com.vicinityprobe.ui.navigation.Routes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val t = { l: L -> Labels.tr(lang, l) }

    var fullMode by rememberSaveable { mutableStateOf(true) }
    var durationMs by rememberSaveable { mutableStateOf(10_000L) }
    val durations = listOf(5_000L to "5s", 10_000L to "10s", 30_000L to "30s", 60_000L to "60s")
    var targetHost by remember { mutableStateOf(com.vicinityprobe.probe.ScanTargetConfig.target(context) ?: "") }

    // 授权返回后刷新权限状态,文案即时更新
    var permTick by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permTick++ }

    val allGranted = remember(permTick) {
        Perms.runtime.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("VicinityProbe", style = MaterialTheme.typography.titleLarge)
                    Text(t(L("探测所有传感器,生成周遭环境数据报告", "Probe every sensor & module, generate an environment report")),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }, actions = {
                // 主题切换(循环:系统 → 浅色 → 深色),全局可观测,即时生效
                IconButton(onClick = {
                    val next = when (com.vicinityprobe.ui.theme.ThemeState.mode) {
                        com.vicinityprobe.ui.theme.ThemeMode.SYSTEM -> com.vicinityprobe.ui.theme.ThemeMode.LIGHT
                        com.vicinityprobe.ui.theme.ThemeMode.LIGHT -> com.vicinityprobe.ui.theme.ThemeMode.DARK
                        com.vicinityprobe.ui.theme.ThemeMode.DARK -> com.vicinityprobe.ui.theme.ThemeMode.SYSTEM
                    }
                    com.vicinityprobe.ui.theme.ThemeState.setMode(context, next)
                }) {
                    when (com.vicinityprobe.ui.theme.ThemeState.mode) {
                        com.vicinityprobe.ui.theme.ThemeMode.SYSTEM -> Icon(Icons.Filled.BrightnessAuto, contentDescription = "theme: system")
                        com.vicinityprobe.ui.theme.ThemeMode.LIGHT -> Icon(Icons.Filled.LightMode, contentDescription = "theme: light")
                        com.vicinityprobe.ui.theme.ThemeMode.DARK -> Icon(Icons.Filled.DarkMode, contentDescription = "theme: dark")
                    }
                }
            })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("探测模式", "Scan mode")), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = fullMode, onClick = { fullMode = true })
                        Column {
                            Text(t(L("全部探测 + 分析", "Full scan + analysis")))
                            Text(t(L("探测所有支持项,并生成环境评分与建议", "Probe everything supported, generate score & suggestions")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !fullMode, onClick = { fullMode = false })
                        Column {
                            Text(t(L("单个探测", "Selected probes")))
                            Text(t(L("在预检页自定义选择探测模块", "Choose probes on the preflight screen")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("扫描时长", "Scan duration")), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        durations.forEach { (ms, label) ->
                            FilterChip(
                                selected = durationMs == ms,
                                onClick = { durationMs = ms },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(L("主动探测目标", "Probe target")), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t(L("端口扫描/HTTP指纹/连通性测试的目标主机,留空则用默认网关", "Target for port scan / HTTP fingerprint / reachability; empty = default gateway")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = targetHost,
                        // 输入不落盘,失焦/离开页面时才保存,避免逐字符写 SharedPreferences
                        onValueChange = { targetHost = it },
                        singleLine = true,
                        placeholder = { Text(t(L("默认网关", "default gateway")) + " e.g. 192.168.1.1") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) com.vicinityprobe.probe.ScanTargetConfig.setTarget(context, targetHost.trim()) },
                    )
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        onDispose { com.vicinityprobe.probe.ScanTargetConfig.setTarget(context, targetHost.trim()) }
                    }
                }
            }

            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(t(L("权限状态", "Permissions")), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (allGranted) t(L("全部已授权", "All granted")) else t(L("需要授权", "Required")),
                            color = if (allGranted) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    val caps = remember { CapabilityProbe.enumerate(context) }
                    val granted = remember(permTick) {
                        Perms.runtime.count { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                    }
                    Text("${granted}/${Perms.runtime.size} ${t(L("项权限已授权", "permissions granted"))}", style = MaterialTheme.typography.bodyMedium)
                    Text(t(L("本设备支持", "This device supports")) + ": ${CapabilityProbe.supportedCount(caps)}/${caps.size} ${t(L("项探测", "probes"))}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { permissionLauncher.launch(Perms.runtime.toTypedArray()) }) {
                        Text(t(L("一键申请权限", "Request all permissions")))
                    }
                }
            }

            Button(
                onClick = {
                    haptics.confirm()
                    if (fullMode) {
                        val caps = CapabilityProbe.enumerate(context)
                        val ids = caps.filter { it.status == com.vicinityprobe.probe.CapabilityStatus.SUPPORTED }.map { it.probeId }
                        nav.navigate(Routes.scan(ids, "FULL", durationMs))
                    } else {
                        nav.navigate(Routes.PREFLIGHT)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (fullMode) t(L("开始全部探测", "Start full scan")) else t(L("选择探测项", "Choose probes")), style = MaterialTheme.typography.titleMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.HISTORY) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.History, contentDescription = null)
                        Text(t(L("历史报告", "History")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.TREND) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.ShowChart, contentDescription = null)
                        Text(t(L("连续监测", "Monitoring")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.COMPARE) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.CompareArrows, contentDescription = null)
                        Text(t(L("报告对比", "Compare")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.CAPTURE) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                        Text(t(L("抓包分析", "Capture")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.REALTIME) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Sensors, contentDescription = null)
                        Text(t(L("实时监测", "Monitor")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.CALIB) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.SettingsInputComponent, contentDescription = null)
                        Text(t(L("传感器标定", "Calibrate")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.WEB) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Language, contentDescription = null)
                        Text(t(L("Web 控制台", "Web console")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.PACKET) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                        Text(t(L("数据包发送", "Packet sender")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.HTTPTOOL) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Http, contentDescription = null)
                        Text(t(L("HTTP 请求", "HTTP tool")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.PORTSCAN) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(t(L("端口扫描", "Port scan")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.SOUNDLEVEL) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null)
                        Text(t(L("声级记录", "Sound level")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.WIFIMAP) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Map, contentDescription = null)
                        Text(t(L("WiFi 地图", "WiFi map")))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(onClick = { nav.navigate(Routes.GNSS) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.SatelliteAlt, contentDescription = null)
                        Text(t(L("GNSS 卫星", "GNSS view")))
                    }
                }
                OutlinedCard(onClick = { nav.navigate(Routes.BTANALYSIS) }, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Text(t(L("蓝牙分析", "BT analysis")))
                    }
                }
            }

            Text(
                t(L("请遵守当地法律法规合法使用本软件", "Use this software in compliance with local laws and regulations")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
