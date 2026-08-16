/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.nfcscan

import android.app.Activity
import android.nfc.NfcAdapter
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
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.ui.components.KeyValueRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcScanScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: NfcScanViewModel = viewModel()
    val report by vm.state.collectAsStateWithLifecycle()
    val haptics = com.vicinityprobe.ui.components.rememberAppHaptics()

    // 启用 Reader Mode:贴卡即读,不弹系统 UI
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        val act = activity
        if (adapter != null && act != null) {
            adapter.enableReaderMode(
                act,
                { tagIntent ->
                    vm.onTagDiscovered(tagIntent)
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        }
        onDispose {
            if (adapter != null && act != null) adapter.disableReaderMode(act)
        }
    }

    val riskColor = when (report.riskLevel) {
        "HIGH" -> Color(0xFFC62828)
        "LOW" -> Color(0xFFF9A825)
        else -> Color(0xFF1565C0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("NFC 卡片分析", "NFC card analyzer"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
                actions = {
                    IconButton(onClick = { haptics.tap(); vm.reset() }) { Icon(Icons.Filled.Refresh, contentDescription = "reset") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report.uid == "?") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(t(L("将卡片贴近手机背部 NFC 区域", "Hold a card against the NFC area on the back of the phone")), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            trBilingual("支持 Mifare Classic/Ultralight/NTAG/ISO 14443/15693/FeliCa|Supports Mifare Classic / Ultralight / NTAG / ISO 14443 / ISO 15693 / FeliCa", lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                if (report.scanning) {
                    Text(t(L("分析中…", "Analyzing…")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                // 风险等级横幅
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            when (report.riskLevel) {
                                "HIGH" -> t(L("高风险", "HIGH RISK"))
                                "LOW" -> t(L("低风险", "LOW RISK"))
                                else -> "INFO"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = riskColor,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(report.cardType, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                // 基本信息
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t(L("卡片信息", "Card info")), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow("UID", report.uid, primary = true)
                        KeyValueRow("ATQA", report.atqa)
                        KeyValueRow("SAK", report.sak)
                        if (report.historicalBytes != "?") KeyValueRow("Historical", report.historicalBytes)
                        if (report.maxTransceive > 0) KeyValueRow("Max transceive", "${report.maxTransceive}")
                        KeyValueRow(t(L("技术", "Technologies")), report.technologies.map { it.substringAfterLast('.') }.joinToString(", "))
                    }
                }
                // Mifare Classic 结果
                if (report.mifareSectors > 0) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t(L("Mifare Classic", "Mifare Classic")), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow(t(L("扇区数", "Sectors")), "${report.mifareSectors}")
                            KeyValueRow(t(L("默认密钥扇区", "Default-key sectors")), "${report.unlockedSectors}" + if (report.keyBUnlocked) " (KeyB!)" else "", primary = report.defaultKeyUsed)
                            if (report.readSector0 != "?") {
                                KeyValueRow(t(L("块 0 (UID区)", "Block 0")), report.readSector0, primary = true)
                            }
                            Text(
                                if (report.defaultKeyUsed)
                                    trBilingual("⚠ 检测到默认密钥:该卡可被完整读取/克隆|Default keys detected: card is fully readable/cloneable", lang)
                                else trBilingual("默认密钥未通过:扇区 0 密钥已修改|Default keys rejected: sector 0 key is modified", lang),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (report.defaultKeyUsed) Color(0xFFC62828) else Color(0xFF2E7D32),
                            )
                        }
                    }
                }
                // 完整转储
                if (report.dumpLines.isNotEmpty()) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t(L("完整转储 (默认密钥解锁的扇区)", "Full dump (default-key sectors)")), style = MaterialTheme.typography.titleSmall)
                            report.dumpLines.take(40).forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            if (report.dumpLines.size > 40) {
                                Text("… ${report.dumpLines.size - 40} " + t(L("行更多", "more lines")), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = {
                                val f = java.io.File(context.cacheDir, "nfc_dump_${System.currentTimeMillis()}.txt")
                                f.writeText(vm.exportDump())
                                com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/plain")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Share, contentDescription = null); Text(t(L("导出转储", "Export dump")))
                            }
                        }
                    }
                }
                // Ultralight 页
                if (report.ulPages.isNotEmpty()) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t(L("内存页", "Memory pages")), style = MaterialTheme.typography.titleSmall)
                            report.ulPages.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                        }
                    }
                }
                // NDEF
                if (report.ndefPresent || report.ndefRecords.isNotEmpty()) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t(L("NDEF 内容", "NDEF content")), style = MaterialTheme.typography.titleSmall)
                            report.ndefRecords.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (report.ndefSize > 0) KeyValueRow(t(L("容量", "Capacity")), "${report.ndefSize} B")
                        }
                    }
                }
                // NDEF 写入器(仅限自己的测试标签)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t(L("NDEF 写入", "NDEF writer")), style = MaterialTheme.typography.titleSmall)
                        var writeText by remember { mutableStateOf("") }
                        androidx.compose.material3.OutlinedTextField(
                            value = writeText,
                            onValueChange = { writeText = it },
                            singleLine = true,
                            label = { Text(t(L("要写入的文本", "Text to write")) + " (NDEF)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                haptics.confirm()
                                if (writeText.isNotBlank()) vm.writeNdefText(writeText)
                            },
                            enabled = writeText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t(L("写入标签", "Write to tag"))) }
                        report.writeResult?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (it.startsWith("OK")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            t(L("仅对你有权写入的测试标签使用", "Use only on test tags you own")),
                            style = MaterialTheme.typography.labelSmall,
                            color = com.vicinityprobe.ui.components.WarningColor,
                        )
                    }
                }
                // 安全发现
                if (report.findings.isNotEmpty()) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t(L("安全发现", "Findings")), style = MaterialTheme.typography.titleSmall)
                            report.findings.forEach { Text("• " + trBilingual(it, lang), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            report.lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                t(L("仅对你有权测试的卡片使用。破解/克隆他人卡片属违法行为。", "Use only on cards you are authorized to test. Breaking/cloning others' cards is illegal.")),
                style = MaterialTheme.typography.labelSmall,
                color = com.vicinityprobe.ui.components.WarningColor,
            )
        }
    }
}
