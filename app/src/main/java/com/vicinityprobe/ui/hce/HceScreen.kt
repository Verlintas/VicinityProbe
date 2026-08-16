/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.hce

import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.service.HceService
import com.vicinityprobe.ui.components.KeyValueRow
import com.vicinityprobe.ui.components.WarningNote
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HceScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }

    var defaultEnabled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(HceService.selected) }

    // 轮询 HCE 选中状态 + 默认服务状态
    LaunchedEffect(Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        val cardEmulation = adapter?.let { CardEmulation.getInstance(it) }
        val component = android.content.ComponentName(context, com.vicinityprobe.service.HceService::class.java)
        while (true) {
            selected = HceService.selected
            defaultEnabled = runCatching {
                cardEmulation?.isDefaultServiceForAid(component, HceService.AID) == true
            }.getOrElse { false }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("HCE 卡模拟", "HCE card emulation"))) },
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
                    Text(t(L("把手机模拟成一张 ISO 7816-4 卡片,用于测试读卡器 APDU 交互", "Emulate an ISO 7816-4 card to test reader APDU interaction")), style = MaterialTheme.typography.bodySmall)
                    WarningNote(t(L("仅用于测试你自己拥有的读卡器/门禁系统", "For testing readers/systems you own only")))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(L("默认 HCE 服务", "Default HCE service")), modifier = Modifier.weight(1f))
                        Switch(
                            checked = defaultEnabled,
                            onCheckedChange = {
                                try {
                                    context.startActivity(android.content.Intent("android.settings.NFC_PAYMENT_SETTINGS"))
                                } catch (_: Throwable) {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                                }
                            },
                        )
                    }
                    Text(
                        if (defaultEnabled) t(L("本应用是默认 HCE 服务,可被读卡器选中", "This app is the default HCE service — selectable by readers"))
                        else t(L("未设为默认:读卡器 SELECT 时会弹出选择", "Not default: reader SELECT prompts a chooser")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t(L("模拟卡片信息", "Emulated card")), style = MaterialTheme.typography.titleSmall)
                    KeyValueRow("AID", "F0 01 02 03 04 05 06", primary = true)
                    KeyValueRow(t(L("类别", "Category")), "other")
                    KeyValueRow("SELECT", "00 A4 04 00 → 90 00")
                    KeyValueRow("READ", "00 B0 xx 00 → 16B \"VICINITY-PROBE-X\"")
                    KeyValueRow("GET_UID", "00 CA → DE AD BE EF 01 02 03 04")
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t(L("实时状态", "Live status")), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.Nfc,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (selected) t(L("已 SELECT!读卡器正在与本机通信", "SELECTED! A reader is talking to this phone"))
                            else t(L("等待读卡器 SELECT…", "Waiting for a reader SELECT…")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        trBilingual("测试方法:另一台手机/读卡器贴近本机,选择 AID 后发送 READ 命令|Test: hold another phone/reader nearby, SELECT the AID, send READ", lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
