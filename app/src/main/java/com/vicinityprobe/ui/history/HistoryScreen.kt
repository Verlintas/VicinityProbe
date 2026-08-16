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

package com.vicinityprobe.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.langOf
import com.vicinityprobe.report.ReportMeta
import com.vicinityprobe.ui.navigation.Routes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> Labels.tr(lang, l) }
    val vm: HistoryViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()

    var renameTarget by remember { mutableStateOf<ReportMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<ReportMeta?>(null) }
    var newName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("历史报告", "History"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { vm.refresh() },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(t(L("暂无报告,先进行一次扫描吧", "No reports yet. Run a scan first.")))
                    Text(t(L("下拉刷新", "Pull down to refresh")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { meta ->
                    OutlinedCard(onClick = { nav.navigate(Routes.report(meta.id)) }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(meta.name, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(meta.createdAt)) +
                                        " · ${meta.probeCount} probes" +
                                        " · EXC ${meta.excellentCount} / DEG ${meta.degradedCount} / FAIL ${meta.failedCount}" +
                                        if (meta.samplesKept) " · RAW" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renameTarget = meta; newName = meta.name }) { Icon(Icons.Filled.Edit, contentDescription = "rename") }
                                    IconButton(onClick = { deleteTarget = meta }) { Icon(Icons.Filled.Delete, contentDescription = "delete") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { meta ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(t(L("重命名", "Rename"))) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.rename(meta.id, newName); renameTarget = null },
                    enabled = newName.isNotBlank(),
                ) { Text(t(L("确定", "OK"))) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(t(L("取消", "Cancel"))) } },
        )
    }
    deleteTarget?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t(L("删除报告", "Delete report"))) },
            text = { Text("${meta.name}?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(meta.id); deleteTarget = null }) { Text(t(L("删除", "Delete"))) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t(L("取消", "Cancel"))) } },
        )
    }
}
