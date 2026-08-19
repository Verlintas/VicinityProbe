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
import kotlinx.coroutines.Dispatchers
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
    @Volatile private var cancelled = false
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
            launch(Dispatchers.Default) {
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

        val measurements = results.values
            .map { QualityEnforcer.enforce(it) }   // 空数据统一降级
            .sortedBy { it.spec.id }
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
            QualityLevel.FAILED, code, "采集出错|Acquisition error",
        ),
    )

    private fun buildUnits(): List<Any> {
        val units = ArrayList<Any>()
        val sensorSpecs = probeIds.mapNotNull { ProbeCatalog.byId(it) }
            .filter { it.id.startsWith("sensor.") }
        if (sensorSpecs.isNotEmpty()) units.add(SensorBatchSampler(sensorSpecs))
        if (probeIds.contains("sensor_calib")) units.add(SensorCalibSampler())

        fun add(id: String, sampler: Sampler) {
            if (probeIds.contains(id)) units.add(sampler)
        }
        add("location", LocationSampler())
        add("gnss", GnssSampler())
        add("nmea", NmeaSampler())
        add("gnss_raw", GnssRawSampler())
        add("gnss_hw", GnssHwSampler())
        add("wifi", WifiSampler())
        add("wifi_dynamic", WifiDynamicSampler())
        add("wifi_scan", WifiScanSampler())
        add("wifi_rtt", WifiRttSampler())
        add("wifi_direct", WifiDirectSampler())
        add("wifi_aware", WifiAwareSampler())
        add("cellular", CellularSampler())
        add("cellular_series", CellularSeriesSampler())
        add("connectivity", ConnectivitySampler())
        add("network_stats", NetworkStatsSampler())
        add("bluetooth", BluetoothSampler())
        add("bt_classic", BluetoothClassicSampler())
        add("bt_paired", PairedDevicesSampler())
        add("nfc", NfcSampler())
        add("fm_radio", FmRadioSampler())
        add("infrared", InfraredSampler())
        add("noise", AudioSampler())
        add("audio_state", AudioStateSampler())
        add("audio_link_test", AudioLinkTestSampler())
        add("battery", BatterySampler())
        add("device", DeviceSampler())
        add("system", SystemSampler())
        add("thermal", ThermalSampler())
        add("power_state", PowerStateSampler())
        add("kernel", KernelSampler())
        add("display", DisplaySampler())
        add("storage", StorageSampler())
        add("net_arp", ArpHostDiscoverySampler())
        add("net_portscan", PortScanSampler())
        add("net_http_fingerprint", HttpFingerprintSampler())
        add("net_dns", DnsProbeSampler())
        add("net_ssdp", SsdpDiscoverySampler())
        add("net_ping", PingSampler())
        add("net_banner", BannerGrabSampler())
        add("net_http_methods", HttpMethodsSampler())
        add("net_http_security", HttpSecuritySampler())
        add("net_tls_versions", TlsVersionsSampler())
        add("net_ntp", NtpSampler())
        add("net_proxy", ProxyConfigSampler())
        add("net_subnet_scan", SubnetScanSampler())
        add("net_mqtt", MqttProbeSampler())
        add("net_http_paths", HttpPathSampler())
        add("net_tcp_concurrency", TcpConcurrencySampler())
        add("net_tls_cipher", TlsCipherProbeSampler())
        add("net_ssh_ver", SshVersionSampler())
        add("net_smb", SmbProbeSampler())
        add("net_dns_hijack", DnsHijackSampler())
        add("net_arp_spoof", ArpSpoofSampler())
        add("net_arp_table", ArpTableSampler())
        add("net_doh", DohSampler())
        add("net_quic", QuicProbeSampler())
        add("net_mdns", MdnsSampler())
        add("net_upnp_detail", UpnpDetailSampler())
        add("proc_net_conn", ProcNetConnSampler())
        add("proc_meminfo", ProcMeminfoSampler())
        add("cpu_per_core", PerCoreCpuSampler())
        add("disk_stats", DiskStatsSampler())
        add("proc_uptime", BootStatsSampler())
        add("battery_drain", BatteryDrainSampler())
        add("wifi_channel", WifiChannelSampler())
        return units
    }
}
