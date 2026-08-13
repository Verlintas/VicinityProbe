package com.vicinityprobe.ui.scanning

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
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanningScreen(nav: NavController, ids: Set<String>, mode: String, durationMs: Long) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> Labels.tr(lang, l) }
    val vm: ScanViewModel = viewModel()

    LaunchedEffect(Unit) { vm.start(ids, mode, durationMs) }

    val state by vm.ui.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()

    LaunchedEffect(result) {
        result?.let { nav.navigate(Routes.report(it.id)) { popUpTo(Routes.HOME) } }
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
            if (s != null) {
                val remaining = ((s.durationMs - s.elapsedMs).toDouble() / 1000.0).coerceAtLeast(0.0)
                CircularProgressIndicator(progress = { (s.elapsedMs.toFloat() / s.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.height(96.dp).padding(vertical = 8.dp))
                Text("${"%.0f".format(remaining)} s", style = MaterialTheme.typography.headlineMedium)
                Text("${s.doneCount}/${s.totalCount} ${t(L("个模块", "modules"))}", style = MaterialTheme.typography.bodyMedium)
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
