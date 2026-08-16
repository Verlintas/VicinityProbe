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

package com.vicinityprobe.ui.scanning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.probe.SessionUiState
import com.vicinityprobe.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanningScreen(nav: NavController, ids: Set<String>, mode: String, durationMs: Long) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: ScanViewModel = viewModel()
    com.vicinityprobe.ui.components.rememberKeepScreenOn()   // 扫描期间屏幕常亮
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()

    LaunchedEffect(Unit) { vm.start(ids, mode, durationMs) }

    val state by vm.ui.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(result) {
        result?.let { r ->
            nav.navigate(Routes.report(r.id)) { popUpTo(Routes.HOME) { inclusive = false } }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(t(L("环境探测中", "Scanning environment…"))) }) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val s = state
            if (error != null) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t(L("扫描失败", "Scan failed")), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(t(L("返回重试", "Back & retry")))
                }
            } else if (s != null && s.durationMs > 0) {
                val elapsed = s.elapsedMs.coerceIn(0L, s.durationMs)
                // 倒计时:环从满到空,数字向上取整(剩余 9.8s 显示 10)
                val progress = (1f - elapsed.toFloat() / s.durationMs).coerceIn(0f, 1f)
                val remainingSec = kotlin.math.ceil((s.durationMs - elapsed).toDouble() / 1000.0).toInt()
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(150.dp),
                        strokeWidth = 8.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            remainingSec.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(t(L("剩余秒", "s remaining")), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("${s.completedUnits}/${s.totalUnits} ${t(L("个模块", "modules"))}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.live.entries.forEach { (id, pair) ->
                        LiveCard(label = pair.first, value = pair.second)
                    }
                }
            } else {
                CircularProgressIndicator()
                Text(t(L("初始化中…", "Initializing…")))
            }
            Button(
                onClick = {
                    haptics.confirm()
                    vm.cancel()
                    nav.popBackStack()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(t(L("取消", "Cancel")))
            }
        }
    }
}

@Composable
private fun LiveCard(label: String, value: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        }
    }
}
