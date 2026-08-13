package com.vicinityprobe.probe

import android.content.Context
import android.os.Build
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class ScanUiState(
    val elapsedMs: Long = 0,
    val durationMs: Long = 0,
    val live: Map<String, Pair<String, String>> = emptyMap(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val finished: Boolean = false,
)

class ProbeController(
    private val ctx: Context,
    private val selectedIds: Set<String>,
    private val durationMs: Long,
    private val mode: String,
    private val scanScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val live = LiveMetrics()
    private val state = MutableStateFlow(ScanUiState())
    private var cancelled = false
    private var jobs: List<Job> = emptyList()

    fun stateFlow(): StateFlow<ScanUiState> = state.asStateFlow()

    fun cancel() {
        cancelled = true
        jobs.forEach { it.cancel() }
    }

    suspend fun run(): ProbeReport = coroutineScope {
        val deadline = SystemClockCompat.elapsedRealtime() + durationMs
        val units = buildUnits()
        val results = java.util.concurrent.ConcurrentHashMap<String, ProbeResult>()
        val started = System.currentTimeMillis()

        jobs = units.map { unit ->
            launch {
                val rs = try {
                    unit.run(ctx, deadline, live)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    listOf(resultBuilder(unit.id, Groups.DEVICE, L("采集失败", "Failed"), ProbeStatus.FAILED))
                }
                rs.forEach { results[it.id] = it }
            }
        }

        val startElapsed = SystemClockCompat.elapsedRealtime()
        while (scanScope.isActive && !cancelled) {
            val now = SystemClockCompat.elapsedRealtime()
            state.value = ScanUiState(
                elapsedMs = now - startElapsed,
                durationMs = durationMs,
                live = live.snapshot(),
                doneCount = jobs.count { it.isCompleted },
                totalCount = jobs.size,
            )
            if (now >= deadline) break
            delay(200)
        }
        jobs.forEach { it.cancelAndJoin() }

        ProbeReport(
            id = UUID.randomUUID().toString(),
            createdAt = started,
            scanDurationMs = durationMs,
            mode = mode,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            results = results.values.sortedBy { it.id },
        )
    }

    private fun buildUnits(): List<ProbeUnit> {
        val units = ArrayList<ProbeUnit>()
        val sensorSpecs = selectedIds.mapNotNull { SensorSpecs.byId(it) }
        if (sensorSpecs.isNotEmpty()) units.add(SensorBatchProbe(sensorSpecs))
        if (selectedIds.any { it in setOf("location", "gnss", "nmea") }) {
            units.add(LocationProbe(selectedIds))
        }
        if (selectedIds.any { it in setOf("wifi", "wifi_scan", "cellular", "connectivity", "bluetooth", "bt_paired") }) {
            units.add(NetworkProbe(selectedIds))
        }
        if (selectedIds.any { it in setOf("noise", "audio_state") }) units.add(AudioProbe(selectedIds))
        if (selectedIds.contains("battery")) units.add(BatteryProbe())
        if (selectedIds.any { it in setOf("device", "system") }) units.add(DeviceProbe(selectedIds))
        return units
    }
}
