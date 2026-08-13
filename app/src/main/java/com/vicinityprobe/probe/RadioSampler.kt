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

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.Inet4Address
import java.net.NetworkInterface

/** WiFi 连接采样器 */
class WifiSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) return failed(QualityLevels.CODE_FEATURE_OFF, "WiFi 未开启|WiFi off")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return failed(QualityLevels.CODE_PERMISSION_DENIED, "缺少邻近设备权限|NEARBY_WIFI_DEVICES required")
        }
        val info = try { wifi.connectionInfo } catch (_: Throwable) { null }
        val attrs = LinkedHashMap<String, String>()
        if (info == null || info.networkId == -1 || info.ssid == WifiManager.UNKNOWN_SSID) {
            attrs["state"] = "未连接|Disconnected"
        } else {
            attrs["ssid"] = info.ssid.trim('"')
            attrs["bssid"] = info.bssid ?: ""
            attrs["rssi_dbm"] = info.rssi.toString()
            attrs["frequency_mhz"] = info.frequency.toString()
            attrs["channel"] = ChannelOf.of(info.frequency).toString()
            attrs["link_speed_mbps"] = info.linkSpeed.toString()
            attrs["hidden"] = info.hiddenSSID.toString()
            val ip = info.ipAddress
            if (ip != 0) {
                attrs["ip"] = "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
            }
        }
        val rssiNum = attrs["rssi_dbm"]?.toIntOrNull()
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = if (rssiNum != null) mapOf("rssi" to com.vicinityprobe.model.domain.ChannelStats.compute(floatArrayOf(rssiNum.toFloat()), "dBm")) else emptyMap(),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1, achievedRateHz = 0.0),
        )
    }

    private fun failed(code: String, detail: String) = Measurement(spec, code,
        quality = QualityReport(QualityLevel.FAILED, code, detail))
}

/** WiFi 环境扫描采样器:AP 列表 + 安全分析 */
class WifiScanSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_scan")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) return failed(QualityLevels.CODE_FEATURE_OFF, "WiFi 未开启|WiFi off")
        val nearbyOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val locOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!nearbyOk || !locOk) return failed(QualityLevels.CODE_PERMISSION_DENIED, "需要邻近设备与定位权限|Needs nearby-devices & location")
        try { wifi.startScan() } catch (_: Throwable) {}
        delay(1500)
        val results = try { wifi.scanResults } catch (_: Throwable) { emptyList() }
        if (results.isEmpty()) {
            return failed(QualityLevels.CODE_THROTTLED, "无扫描结果(系统节流)|No results (system throttled)")
        }
        val secCounts = HashMap<String, Int>()
        results.forEach { r ->
            val s = WifiSecurity.of(r.capabilities)
            secCounts[s] = (secCounts[s] ?: 0) + 1
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["ap_count"] = results.size.toString()
        attrs["open_networks"] = (secCounts["OPEN"] ?: 0).toString()
        attrs["security_mix"] = secCounts.entries.joinToString(",") { "${WifiSecurity.display(it.key)}:${it.value}" }
        val top = results.sortedByDescending { it.level }.take(15)
        attrs["detail"] = top.joinToString("\n") {
            "${it.SSID.ifEmpty { "(hidden)" }} | ${WifiSecurity.display(WifiSecurity.of(it.capabilities))} | ${it.level}dBm | ${it.frequency}MHz | ${it.BSSID}"
        }
        val rssis = results.map { it.level.toFloat() }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("rssi_distribution" to com.vicinityprobe.model.domain.ChannelStats.compute(rssis.toFloatArray(), "dBm")),
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = results.size, achievedRateHz = 0.0),
        )
    }

    private fun failed(code: String, detail: String) = Measurement(spec, code,
        quality = QualityReport(QualityLevel.FAILED, code, detail))
}

/** 蜂窝网络采样器 */
class CellularSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("cellular")!!

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val attrs = LinkedHashMap<String, String>()
        attrs["network_type"] = NetworkTypeNames.name(tm.dataNetworkType)
        attrs["operator"] = tm.networkOperatorName.ifEmpty { "unknown" }
        attrs["sim_country"] = tm.simCountryIso.uppercase().ifEmpty { "unknown" }
        attrs["roaming"] = tm.isNetworkRoaming.toString()
        val mccMnc = tm.networkOperator
        if (mccMnc.isNotBlank()) attrs["mcc_mnc"] = mccMnc

        var rsrp: Int? = null
        var rsrq: Int? = null
        var snr: Int? = null
        var level: Int? = null
        val cells = ArrayList<String>()
        try {
            tm.allCellInfo.forEach { ci ->
                var sig: String? = null
                var cell: String? = null
                when (ci) {
                    is android.telephony.CellInfoLte -> {
                        val s = ci.cellSignalStrength
                        rsrp = s.rsrp; rsrq = s.rsrq; snr = s.rssnr; level = s.level
                        sig = "LTE RSRP ${s.rsrp}dBm RSRQ ${s.rsrq}dB SNR ${s.rssnr}dB"
                        val id = ci.cellIdentity
                        cell = "LTE ci=${id.ci} tac=${id.tac} pci=${id.pci} earfcn=${id.earfcn}"
                    }
                    is android.telephony.CellInfoNr -> {
                        val s = ci.cellSignalStrength as android.telephony.CellSignalStrengthNr
                        rsrp = s.ssRsrp; rsrq = s.ssRsrq; snr = s.ssSinr; level = s.level
                        sig = "NR SS-RSRP ${s.ssRsrp}dBm SS-RSRQ ${s.ssRsrq}dB SS-SINR ${s.ssSinr}dB"
                        val id = ci.cellIdentity as android.telephony.CellIdentityNr
                        cell = "NR nci=${id.nci} pci=${id.pci} nrarfcn=${id.nrarfcn} tac=${id.tac}"
                    }
                    is android.telephony.CellInfoGsm -> {
                        sig = "GSM RSSI ${ci.cellSignalStrength.rssi}dBm"
                        cell = "GSM cid=${ci.cellIdentity.cid} lac=${ci.cellIdentity.lac}"
                    }
                    is android.telephony.CellInfoCdma -> {
                        sig = "CDMA level=${ci.cellSignalStrength.level}"
                        cell = "CDMA sid=${ci.cellIdentity.systemId} nid=${ci.cellIdentity.basestationId}"
                    }
                    is android.telephony.CellInfoWcdma -> {
                        sig = "WCDMA level=${ci.cellSignalStrength.level}"
                        cell = "WCDMA cid=${ci.cellIdentity.cid} psc=${ci.cellIdentity.psc}"
                    }
                }
                if (sig != null) {
                    if (ci.isRegistered) { attrs["serving_cell"] = sig; if (cell != null) attrs["serving_identity"] = cell }
                    else if (cells.size < 5) cells.add("$sig $cell")
                }
            }
        } catch (_: Throwable) {}
        if (cells.isNotEmpty()) attrs["neighbor_cells"] = cells.joinToString("\n")
        rsrp?.let { attrs["rsrp_dbm"] = it.toString() }
        rsrq?.let { attrs["rsrq_db"] = it.toString() }
        snr?.let { attrs["snr_db"] = it.toString() }
        level?.let { attrs["signal_level"] = "$it/4" }

        val q = when {
            rsrp == null -> QualityLevel.DEGRADED
            rsrp!! >= -95 -> QualityLevel.EXCELLENT
            rsrp!! >= -110 -> QualityLevel.GOOD
            else -> QualityLevel.DEGRADED
        }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = if (rsrp != null) mapOf("rsrp" to com.vicinityprobe.model.domain.ChannelStats.compute(floatArrayOf(rsrp.toFloat()), "dBm")) else emptyMap(),
            quality = QualityReport(q, QualityLevels.CODE_OK, "", sampleCount = 1, achievedRateHz = 0.0),
        )
    }
}

/** 网络接口与连通性采样器 */
class ConnectivitySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("connectivity")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val attrs = LinkedHashMap<String, String>()
        var vpn = false
        try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                val transports = ArrayList<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WiFi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("Cellular")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { transports.add("VPN"); vpn = true }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("BT")
                attrs["transports"] = transports.joinToString(",")
                attrs["uplink_kbps"] = caps.linkUpstreamBandwidthKbps.toString()
                attrs["downlink_kbps"] = caps.linkDownstreamBandwidthKbps.toString()
                attrs["metered"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).toString()
            }
            val lp = cm.getLinkProperties(cm.activeNetwork)
            if (lp != null) {
                lp.linkAddresses.mapNotNull { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
                    ?.let { attrs["ipv4"] = it.hostAddress ?: "" }
                lp.linkAddresses.mapNotNull { it.address }.filterNot { it is Inet4Address }.firstOrNull()
                    ?.let { attrs["ipv6"] = it.hostAddress?.substringBefore('%') ?: "" }
                lp.dnsServers.mapNotNull { it.hostAddress }.take(3).joinToString(",").takeIf { it.isNotEmpty() }
                    ?.let { attrs["dns"] = it }
                lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                    ?.let { attrs["gateway"] = it }
            }
        } catch (_: Throwable) {}
        attrs["vpn"] = vpn.toString()
        val ifaces = ArrayList<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                val ips = ni.interfaceAddresses.mapNotNull { it.address.hostAddress }.joinToString(",")
                val mac = try { ni.hardwareAddress?.joinToString(":") { "%02X".format(it) } } catch (_: Throwable) { null }
                ifaces.add("${ni.name} ${if (ni.isUp) "UP" else "DOWN"} mtu=${ni.mtu} ip=[$ips]${mac?.let { " mac=$it" } ?: ""}")
            }
        } catch (_: Throwable) {}
        if (ifaces.isNotEmpty()) attrs["interfaces"] = ifaces.joinToString("\n")
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1),
        )
    }
}

/** 蓝牙 BLE 扫描采样器 */
class BluetoothSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("bluetooth")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = bm?.adapter
        if (adapter == null) return failed(QualityLevels.CODE_NO_HARDWARE, "无蓝牙硬件|No BT hardware")
        if (!adapter.isEnabled) return failed(QualityLevels.CODE_FEATURE_OFF, "蓝牙未开启|BT off")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return failed(QualityLevels.CODE_PERMISSION_DENIED, "缺少扫描权限|BLUETOOTH_SCAN required")
        }
        val scanner = adapter.bluetoothLeScanner ?: return failed(QualityLevels.CODE_ACQUISITION_ERROR, "no scanner")
        val found = java.util.Collections.synchronizedList(ArrayList<ScanResult>())
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { found.add(result) }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (_: Throwable) {
            return failed(QualityLevels.CODE_ACQUISITION_ERROR, "扫描启动失败|Scan start failed")
        }
        val end = kotlin.math.min(SystemClockCompat.elapsedRealtime() + 3000, session.deadlineRealtimeMs)
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < end) { delay(100) }
        try { scanner.stopScan(cb) } catch (_: Throwable) {}

        val list = synchronized(found) { found.sortedByDescending { it.rssi }.take(12) }
        val attrs = LinkedHashMap<String, String>()
        attrs["devices_found"] = list.size.toString()
        attrs["detail"] = list.joinToString("\n") { r ->
            val name = r.device.name ?: "(unnamed)"
            val services = r.scanRecord?.serviceUuids?.take(3)?.joinToString(",") ?: ""
            val mfg = r.scanRecord?.manufacturerSpecificData?.size() ?: 0
            "$name | ${r.device.address} | ${r.rssi}dBm${if (services.isNotEmpty()) " | svc:$services" else ""}${if (mfg > 0) " | mfg:${mfg}B" else ""}"
        }
        val rssis = list.map { it.rssi.toFloat() }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = if (rssis.isNotEmpty()) mapOf("rssi" to com.vicinityprobe.model.domain.ChannelStats.compute(rssis.toFloatArray(), "dBm")) else emptyMap(),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = list.size),
        )
    }

    private fun failed(code: String, detail: String) = Measurement(spec, code,
        quality = QualityReport(QualityLevel.FAILED, code, detail))
}

/** 已配对蓝牙采样器 */
class PairedDevicesSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("bt_paired")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = bm?.adapter
        if (adapter == null) return Measurement(spec, QualityLevels.CODE_NO_HARDWARE,
            quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_HARDWARE, "无蓝牙硬件|No BT hardware"))
        if (!adapter.isEnabled) return Measurement(spec, QualityLevels.CODE_FEATURE_OFF,
            quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_FEATURE_OFF, "蓝牙未开启|BT off"))
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return Measurement(spec, QualityLevels.CODE_PERMISSION_DENIED,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_PERMISSION_DENIED, "缺少连接权限|BLUETOOTH_CONNECT required"))
        }
        val devices = try { adapter.bondedDevices } catch (_: Throwable) { null }
        val list = devices?.map { "${it.name ?: "(unnamed)"} (${it.address})" }?.sorted() ?: emptyList()
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            attributes = mapOf("paired_count" to list.size.toString(), "detail" to list.joinToString("\n")),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = list.size),
        )
    }
}

object NetworkTypeNames {
    fun name(netType: Int): String = when (netType) {
        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        android.telephony.TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        else -> "UNKNOWN($netType)"
    }
}

object WifiSecurity {
    fun of(capabilities: String): String = when {
        capabilities.contains("SAE") -> "WPA3"
        capabilities.contains("WPA3") -> "WPA3/WPA2"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        capabilities.contains("ESS") -> "OPEN"
        else -> "UNKNOWN"
    }

    fun display(sec: String): String = when (sec) {
        "WPA3" -> bil("WPA3", "WPA3")
        "WPA3/WPA2" -> bil("WPA3/WPA2", "WPA3/WPA2")
        "WPA2" -> bil("WPA2", "WPA2")
        "WPA" -> bil("WPA", "WPA")
        "WEP" -> bil("WEP(弱)", "WEP (weak)")
        "OPEN" -> bil("开放(无加密)", "Open (no encryption)")
        else -> bil("未知", "Unknown")
    }
}

object ChannelOf {
    fun of(freqMHz: Int): Int = when {
        freqMHz in 2412..2484 -> (freqMHz - 2412) / 5 + 1
        freqMHz in 5170..5825 -> (freqMHz - 5170) / 5 + 34
        else -> 0
    }
}
