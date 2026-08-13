package com.vicinityprobe.ui.preflight

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.domain.Category
import com.vicinityprobe.model.langOf
import com.vicinityprobe.probe.Capability
import com.vicinityprobe.probe.CapabilityProbe
import com.vicinityprobe.probe.CapabilityStatus
import com.vicinityprobe.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreflightScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }

    val caps = remember { CapabilityProbe.enumerate(context) }
    val selected = remember {
        mutableStateMapOf<String, Boolean>().apply {
            caps.filter { it.status == CapabilityStatus.SUPPORTED }.forEach { put(it.probeId, true) }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val supported = caps.count { it.status == CapabilityStatus.SUPPORTED }
    val selectedCount = selected.values.count { it }

    Scaffold(
        topBar = { TopAppBar(title = { Text(t(L("能力预检与测量计划", "Preflight & measurement plan"))) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t(L("本设备可探测", "This device supports")) + ": $supported/${caps.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = {
                    caps.filter { it.status == CapabilityStatus.SUPPORTED }.forEach { selected[it.probeId] = true }
                }) { Text(t(L("全选可用", "Select all"))) }
            }

            Category.entries.forEach { category ->
                val groupCaps = caps.filter { it.spec.category == category }
                if (groupCaps.isEmpty()) return@forEach
                HorizontalDivider()
                Text(category.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                groupCaps.forEach { cap -> CapabilityRow(cap, selected[cap.probeId] ?: false, { v -> selected[cap.probeId] = v }, permissionLauncher::launch) }
            }

            Button(
                onClick = {
                    val ids = selected.filterValues { it }.keys.toList()
                    nav.navigate(Routes.scan(ids, "SELECTED", 10_000L))
                },
                enabled = selectedCount > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(t(L("开始测量", "Start measurement")) + " ($selectedCount)")
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    cap: Capability,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    requestPermission: (Array<String>) -> Unit,
) {
    val context = LocalContext.current
    val lang = langOf(context)
    val canSelect = cap.status == CapabilityStatus.SUPPORTED
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked && canSelect,
            enabled = canSelect,
            onCheckedChange = onChecked,
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (lang.startsWith("zh")) cap.name.zh else cap.name.en,
                style = MaterialTheme.typography.bodyMedium,
                color = if (canSelect) MaterialTheme.colorScheme.onSurface else Color.Gray,
            )
            Text(
                "${cap.spec.measurand} · ${cap.spec.unit.symbol}" +
                    if (cap.spec.nominalRateHz > 0) " · ${"%.0f".format(cap.spec.nominalRateHz)}Hz" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (cap.status) {
                CapabilityStatus.SUPPORTED -> {}
                CapabilityStatus.NO_HARDWARE -> Text("— no hardware", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                CapabilityStatus.FEATURE_OFF -> Text("— feature off", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
                CapabilityStatus.PERMISSION_MISSING -> {
                    Text(
                        "— permission: " + cap.requiredPermissions.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        val missing = cap.requiredPermissions.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }.toTypedArray()
                        if (missing.isNotEmpty()) requestPermission(missing)
                    }) { Text("grant") }
                }
            }
        }
    }
}
