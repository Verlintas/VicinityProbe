package com.vicinityprobe.ui.preflight

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.domain.Category
import com.vicinityprobe.model.langOf
import com.vicinityprobe.model.trBilingual
import com.vicinityprobe.probe.Capability
import com.vicinityprobe.probe.CapabilityProbe
import com.vicinityprobe.probe.CapabilityStatus
import com.vicinityprobe.ui.ProbeInfo
import com.vicinityprobe.ui.navigation.Routes

/** 分类顶栏标签顺序 */
private val categoryOrder = listOf(
    Category.MOTION, Category.ENVIRONMENT, Category.MAGNETIC, Category.BIOSIGNAL,
    Category.AUDIO, Category.POSITIONING, Category.RADIO, Category.ELECTRICAL,
    Category.SYSTEM, Category.DEVICE, Category.CONTEXT, Category.SECURITY,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreflightScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }

    var caps by remember { mutableStateOf(CapabilityProbe.enumerate(context)) }
    // 默认全部不勾选,由用户自己挑(取消勾选即移除 key,计数才准确)
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var infoTarget by remember { mutableStateOf<Capability?>(null) }
    var durationMs by rememberSaveable { mutableStateOf(10_000L) }
    val durations = listOf(5_000L to "5s", 10_000L to "10s", 30_000L to "30s", 60_000L to "60s")

    // 授权返回后重新枚举能力,卡片状态即时刷新
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { caps = CapabilityProbe.enumerate(context) }

    val currentCategory = categoryOrder[selectedTab.coerceIn(0, categoryOrder.size - 1)]
    val visibleCaps = caps.filter { it.spec.category == currentCategory }
    val selectedIds = selected.filterValues { it }.keys

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("选择探测项", "Choose probes"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        durations.forEach { (ms, label) ->
                            FilterChip(
                                selected = durationMs == ms,
                                onClick = { durationMs = ms },
                                label = { Text(label) },
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val ids = selectedIds.toList()
                            nav.navigate(Routes.scan(ids, "SELECTED", durationMs))
                        },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(
                            t(L("开始测量", "Start measurement")) +
                                if (selectedIds.isNotEmpty()) " (${selectedIds.size})" else " — " + t(L("先勾选要测的项目", "select probes first")),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 分类顶栏(可滚动)
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                categoryOrder.forEachIndexed { i, cat ->
                    val count = caps.count { it.spec.category == cat }
                    val selectedInCat = selectedIds.count { k ->
                        caps.firstOrNull { it.probeId == k }?.spec?.category == cat
                    }
                    Surface(
                        color = if (i == selectedTab) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = MaterialTheme.shapes.large,
                        onClick = { selectedTab = i },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "${cat.name} $selectedInCat/$count",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Text(
                t(L("本设备可探测", "This device supports")) + ": ${CapabilityProbe.supportedCount(caps)}/${caps.size}" +
                    " · " + t(L("已选", "selected")) + ": ${selectedIds.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // 卡片网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleCaps, key = { it.probeId }) { cap ->
                    ProbeCard(
                        cap = cap,
                        checked = selected[cap.probeId] ?: false,
                        lang = lang,
                        onChecked = { v -> if (v) selected[cap.probeId] = true else selected.remove(cap.probeId) },
                        onInfo = { infoTarget = cap },
                        onGrant = { permissionLauncher.launch(it) },
                    )
                }
            }
        }
    }

    // 简介弹窗
    infoTarget?.let { cap ->
        val usage = ProbeInfo.of(cap.probeId, cap.name.zh)
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text(if (lang.startsWith("zh")) cap.name.zh else cap.name.en) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(trBilingual(usage, lang), style = MaterialTheme.typography.bodyMedium)
                    if (cap.spec.complianceRisk) {
                        Text(
                            "⚠️ " + trBilingual(cap.spec.riskNote, lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = com.vicinityprobe.ui.components.WarningColor,
                        )
                    }
                    Text(
                        "${cap.spec.measurand} · ${cap.spec.unit.symbol}" +
                            if (cap.spec.nominalRateHz > 0) " · ${String.format("%.0f Hz", cap.spec.nominalRateHz)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { infoTarget = null }) { Text(t(L("知道了", "Got it"))) }
            },
        )
    }
}

@Composable
private fun ProbeCard(
    cap: Capability,
    checked: Boolean,
    lang: String,
    onChecked: (Boolean) -> Unit,
    onInfo: () -> Unit,
    onGrant: (Array<String>) -> Unit,
) {
    val context = LocalContext.current
    val canSelect = cap.status == CapabilityStatus.SUPPORTED
    val statusColor = when (cap.status) {
        CapabilityStatus.SUPPORTED -> Color(0xFF1B5E20)
        CapabilityStatus.PERMISSION_MISSING -> MaterialTheme.colorScheme.error
        CapabilityStatus.NO_HARDWARE -> Color.Gray
        CapabilityStatus.FEATURE_OFF -> com.vicinityprobe.ui.components.WarningColor
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        trBilingual(cap.name.zh + "|" + cap.name.en, lang),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (canSelect) MaterialTheme.colorScheme.onSurface else Color.Gray,
                    )
                    Text(
                        "${cap.spec.measurand}" + if (cap.spec.nominalRateHz > 0) " · ${String.format("%.0fHz", cap.spec.nominalRateHz)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 卡片一角:简介按钮
                IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            when (cap.status) {
                CapabilityStatus.SUPPORTED -> {}
                CapabilityStatus.NO_HARDWARE -> Text("✗ no hardware", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                CapabilityStatus.FEATURE_OFF -> Text("✗ feature off", style = MaterialTheme.typography.labelSmall, color = statusColor)
                CapabilityStatus.PERMISSION_MISSING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        TextButton(
                            onClick = {
                                val missing = cap.requiredPermissions.filter {
                                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                }.toTypedArray()
                                if (missing.isNotEmpty()) onGrant(missing)
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        ) { Text("grant", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked && canSelect,
                    enabled = canSelect,
                    onCheckedChange = onChecked,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    if (lang.startsWith("zh")) (if (checked) "已选" else "选择") else (if (checked) "Selected" else "Select"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f)) {}
                if (cap.spec.complianceRisk) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = com.vicinityprobe.ui.components.WarningColor, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

