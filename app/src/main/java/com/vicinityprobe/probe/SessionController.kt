package com.vicinityprobe.probe

import android.content.Context
import android.os.Build
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.MeasurementPlan
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.SessionContextInfo
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
import java.io.File
import java.util.Locale
import java.util.UUID

data class SessionUiState(
    val elapsedMs: Long = 0,
    val durationMs: Long = 0,
    val live: Map<String, Pair<String, String>> = emptyMap(),
    val completedUnits: Int = 0,
    val totalUnits: Int = 0,
)

/**
 * 测量会话控制器:编排采样器执行、管理状态机、产出 MeasurementReport。
 * 状态机:INIT → PREFLIGHT → SAMPLING → FINALIZE
 * 原始样本落盘至 reports/<id>/samples/
 */
class SessionController(
    private val ctx: Context,
    private val probeIds: Set<String>,
    private val durationMs: Long,
    private val mode: String,
    private val scanScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val live = LiveMetrics()
    private val state = MutableStateFlow(SessionUiState())
    private var cancelled = false
    private var jobs: List<Job> = emptyList()

    fun stateFlow(): StateFlow<SessionUiState> = state.asStateFlow()

    fun cancel() {
        cancelled = true
        jobs.forEach { it.cancel() }
    }

    suspend fun run(reportDir: File): MeasurementReport = coroutineScope {
        val startRealtime = SystemClockCompat.elapsedRealtime()
        val deadline = startRealtime + durationMs
        val planId = UUID.randomUUID().toString()
        val samplesDir = File(reportDir, "$planId/samples")

        val units = buildUnits()
        val results = java.util.concurrent.ConcurrentHashMap<String, Measurement>()

        jobs = units.map { unit ->
            launch {
                when (unit) {
                    is BatchSampler -> {
                        val list = try {
                            unit.run(ctx, SessionContext(startRealtime, deadline, samplesDir, live))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            unit.specs.map { failedMeasurement(it, "ACQUISITION_ERROR") }
                        }
                        list.forEach { results[it.spec.id] = it }
                    }
                    is Sampler -> {
                        val m = try {
                            unit.run(ctx, SessionContext(startRealtime, deadline, samplesDir, live))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            failedMeasurement(unit.spec, "ACQUISITION_ERROR")
                        }
                        results[m.spec.id] = m
                    }
                }
            }
        }

        while (scanScope.isActive && !cancelled) {
            val now = SystemClockCompat.elapsedRealtime()
            state.value = SessionUiState(
                elapsedMs = now - startRealtime,
                durationMs = durationMs,
                live = live.snapshot(),
                completedUnits = jobs.count { it.isCompleted },
                totalUnits = jobs.size,
            )
            if (now >= deadline) break
            delay(200)
        }
        jobs.forEach { it.cancelAndJoin() }

        val measurements = results.values.sortedBy { it.spec.id }
        val battery = results["battery"]?.attributes?.get("level_pct")?.toDoubleOrNull()
        MeasurementReport(
            schemaVersion = 1,
            id = planId,
            plan = MeasurementPlan(
                planId = planId,
                createdAt = System.currentTimeMillis(),
                durationMs = durationMs,
                probeIds = measurements.map { it.spec.id },
                operator = mode,
            ),
            context = SessionContextInfo(
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                kernel = System.getProperty("os.version") ?: "?",
                timezone = java.util.TimeZone.getDefault().id,
                locale = Locale.getDefault().toLanguageTag(),
                elapsedRealtimeMs = SystemClockCompat.elapsedRealtime() - startRealtime,
                batteryLevelPct = battery,
            ),
            measurements = measurements,
        )
    }

    private fun failedMeasurement(spec: ProbeSpec, code: String) = Measurement(
        spec = spec, status = code,
        quality = com.vicinityprobe.model.domain.QualityReport(
            QualityLevel.FAILED, code, "采集异常|Acquisition error",
        ),
    )

    private fun buildUnits(): List<Any> {
        val units = ArrayList<Any>()
        val sensorSpecs = probeIds.mapNotNull { ProbeCatalog.byId(it) }
            .filter { it.id.startsWith("sensor.") }
        if (sensorSpecs.isNotEmpty()) units.add(SensorBatchSampler(sensorSpecs))

        fun add(id: String, sampler: Sampler) {
            if (probeIds.contains(id)) units.add(sampler)
        }
        add("location", LocationSampler())
        add("gnss", GnssSampler())
        add("nmea", NmeaSampler())
        add("wifi", WifiSampler())
        add("wifi_scan", WifiScanSampler())
        add("cellular", CellularSampler())
        add("connectivity", ConnectivitySampler())
        add("bluetooth", BluetoothSampler())
        add("bt_paired", PairedDevicesSampler())
        add("noise", AudioSampler())
        add("audio_state", AudioStateSampler())
        add("battery", BatterySampler())
        add("device", DeviceSampler())
        add("system", SystemSampler())
        return units
    }
}
