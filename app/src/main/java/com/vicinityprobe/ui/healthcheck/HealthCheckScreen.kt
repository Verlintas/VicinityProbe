/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.healthcheck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.WarningNote
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: HealthCheckViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()
    if (st.phase == HealthPhase.SCANNING || st.phase == HealthPhase.AI) {
        com.vicinityprobe.ui.components.rememberKeepScreenOn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("环境体检", "Environment checkup"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (st.phase) {
                HealthPhase.IDLE -> IdleView(vm, t)
                HealthPhase.SCANNING -> ScanningView(st, t)
                HealthPhase.SCORING, HealthPhase.AI -> {
                    CircularProgressIndicator()
                    Text(
                        if (st.phase == HealthPhase.AI) t(L("AI 评分中…", "AI scoring…")) else t(L("本地评分中…", "Scoring…")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HealthPhase.DONE -> DoneView(st, t, vm, lang) { id -> nav.navigate(com.vicinityprobe.ui.navigation.Routes.report(id)) }
                HealthPhase.ERROR -> {
                    Text(st.error ?: "?", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.reset() }) { Text(t(L("重试", "Retry"))) }
                }
            }
            if (st.phase != HealthPhase.IDLE && st.phase != HealthPhase.ERROR) {
                OutlinedButton(onClick = { haptics.confirm(); vm.cancel() }, enabled = st.phase == HealthPhase.SCANNING || st.phase == HealthPhase.AI) {
                    Text(t(L("取消", "Cancel")))
                }
            }
        }
    }
}

@Composable
private fun IdleView(vm: HealthCheckViewModel, t: (L) -> String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(t(L("一键体检", "One-tap checkup")), style = MaterialTheme.typography.headlineMedium)
        Text(
            t(L("跑一遍安全合规的环境探测,本地评分 + AI 通俗解读,给你的环境打个分", "Run compliant environment probes, local scoring + plain-language AI verdict")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            "${vm.healthProbeIds().size} " + t(L("项合规探测(不含端口扫描/抓包/蓝牙等主动安全测试)", "compliant probes (no port scan / capture / BT — those are active security tests)")),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(t(L("开始体检(约 15 秒)", "Start checkup (~15 s)")), style = MaterialTheme.typography.titleMedium)
        }
        WarningNote(t(L("体检仅反映测量时刻的环境;结果仅供参考,不构成专业认证", "The checkup reflects the environment at measurement time; results are informal, not a professional certification")))
    }
}

@Composable
private fun ScanningView(st: HealthCheckState, t: (L) -> String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
            CircularProgressIndicator(
                progress = { st.progress.coerceIn(0.02f, 1f) },
                modifier = Modifier.size(160.dp),
                strokeWidth = 10.dp,
                strokeCap = StrokeCap.Round,
            )
            Text("${(st.progress * 100).roundToInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(t(L("正在探测环境中…", "Probing environment…")), style = MaterialTheme.typography.titleMedium)
        if (st.scanningText.isNotBlank()) {
            Text(st.scanningText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DoneView(st: HealthCheckState, t: (L) -> String, vm: HealthCheckViewModel, lang: String, onOpenReport: (String) -> Unit) {
    val local = st.localScore
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (local != null) {
            // 大分数环(本地评分)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val color = scoreColor(local.score)
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(
                                color = color,
                                startAngle = -90f,
                                sweepAngle = 360f * local.score / 100f,
                                useCenter = false,
                                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(local.score.toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = color)
                            Text("/ 100", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t(L("环境等级", "Grade")), style = MaterialTheme.typography.titleMedium)
                        Text(local.grade, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (local.positives.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t(L("表现不错", "Looking good")), style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E7D32))
                        local.positives.forEach { Text("✓ " + it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (local.reasons.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t(L("扣分项", "Deductions")), style = MaterialTheme.typography.titleSmall, color = Color(0xFFC62828))
                        local.reasons.forEach { Text("✗ " + it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        // AI 解读
        if (st.aiScore != null) {
            val parsed = com.vicinityprobe.ai.AiResultParser.parse(st.aiScore!!)
            if (parsed != null && parsed.parsed) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t(L("AI 体检师说", "AI checkup verdict")), style = MaterialTheme.typography.titleSmall)
                        if (parsed.grade.isNotBlank()) {
                            Text(
                                t(L("AI 评分", "AI score")) + ": " + parsed.grade,
                                style = MaterialTheme.typography.titleMedium,
                                color = scoreColor(parsed.grade),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (parsed.verdict.isNotBlank()) {
                            Text(parsed.verdict, style = MaterialTheme.typography.bodyMedium)
                        }
                        parsed.highlights.forEach { Text("✓ " + it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)) }
                        parsed.concerns.forEach { Text("✗ " + it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828)) }
                        parsed.recommendations.forEach { Text("→ " + it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            trBilingual("(说明来自大模型,仅供娱乐参考)|(LLM-generated, informal)", lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t(L("AI 体检师说", "AI checkup verdict")), style = MaterialTheme.typography.titleSmall)
                        Text(st.aiScore!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (st.error != null) {
            Text(st.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.weight(1f)) {
                Text(t(L("重新体检", "Re-check")))
            }
            st.report?.let { r ->
                OutlinedButton(
                    onClick = { onOpenReport(r.id) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(t(L("查看完整报告", "Full report")))
                }
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> Color(0xFF2E7D32)
    score >= 75 -> Color(0xFFF9A825)
    score >= 60 -> Color(0xFFE65100)
    else -> Color(0xFFC62828)
}

private fun scoreColor(grade: String): Color = when (grade.uppercase()) {
    "A" -> Color(0xFF2E7D32)
    "B" -> Color(0xFFF9A825)
    "C" -> Color(0xFFE65100)
    else -> Color(0xFFC62828)
}
