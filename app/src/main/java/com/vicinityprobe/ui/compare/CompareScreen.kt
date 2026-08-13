package com.vicinityprobe.ui.compare

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.fmt
import com.vicinityprobe.ui.components.StatusPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> Labels.tr(lang, l) }
    val vm: CompareViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()

    var idA by remember { mutableStateOf<String?>(null) }
    var idB by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(idA, idB) { vm.compare(idA, idB) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("报告对比", "Compare reports"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ReportPicker(Modifier.weight(1f), items, idA, { idA = it }, t(L("报告 A", "Report A")))
                ReportPicker(Modifier.weight(1f), items, idB, { idB = it }, t(L("报告 B", "Report B")))
            }
            val r = result
            if (r != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("评分: ${r.scoreA?.let { fmt(it) } ?: "—"} → ${r.scoreB?.let { fmt(it) } ?: "—"}")
                    Text("OK ${r.okCountA} → ${r.okCountB}")
                }
                if (r.rows.isEmpty()) {
                    Text(t(L("两份报告的公共指标无差异", "No common metric differences")), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(r.rows, key = { "${it.probeName}_${it.metricLabel}" }) { row ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    "${trBilingual(row.probeName, lang)} · ${trBilingual(row.metricLabel, lang)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("A: ${row.valueA}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text("B: ${row.valueB}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(t(L("选择两份报告进行对比", "Select two reports to compare")), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportPicker(
    modifier: Modifier,
    items: List<com.vicinityprobe.model.ReportMeta>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    placeholder: String,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = items.firstOrNull { it.id == selectedId }?.name ?: placeholder,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { meta ->
                DropdownMenuItem(
                    text = { Text(meta.name) },
                    onClick = { onSelect(meta.id); expanded = false },
                )
            }
        }
    }
}
