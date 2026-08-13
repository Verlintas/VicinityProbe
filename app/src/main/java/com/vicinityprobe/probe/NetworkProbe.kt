package com.vicinityprobe.probe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkTypeNames {
    fun name(netType: Int): String = when (netType) {
        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        android.telephony.TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
        android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO-A"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
        android.telephony.TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        android.telephony.TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        else -> "UNKNOWN($netType)"
    }
}

object WifiSecurity {
    fun of(capabilities: String): String = when {
        capabilities.contains("SAE") && capabilities.contains("WPA3") -> "WPA3"
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

class NetworkProbe(private val selected: Set<String>) : ProbeUnit {
    override val id = "network"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()
        if (selected.contains("wifi")) results.addAll(WifiUnit(ctx, deadlineMs, live).run())
        if (selected.contains("wifi_scan")) results.addAll(WifiScanUnit(ctx, deadlineMs).run())
        if (selected.contains("cellular")) results.addAll(CellularUnit(ctx, deadlineMs, live).run())
        if (selected.contains("connectivity")) results.addAll(ConnectivityUnit(ctx).run())
        if (selected.contains("bluetooth")) results.addAll(BluetoothUnit(ctx, deadlineMs, live).run())
        if (selected.contains("bt_paired")) results.addAll(BluetoothPairedUnit(ctx).run())
        return results
    }
}

private class WifiUnit(
    private val ctx: Context,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    suspend fun run(): List<ProbeResult> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            return listOf(resultBuilder("wifi", Groups.NETWORK, Labels.WIFI, ProbeStatus.FEATURE_OFF, note = bil("WiFi 未开启", "WiFi is off")))
        }
        val nearbyPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES)
        if (nearbyPerm != PackageManager.PERMISSION_GRANTED) {
            return listOf(resultBuilder("wifi", Groups.NETWORK, Labels.WIFI, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.NEARBY_WIFI_DEVICES))
        }
        val info = try { wifi.connectionInfo } catch (_: Throwable) { null }
        if (info == null || info.networkId == -1 || info.ssid == WifiManager.UNKNOWN_SSID) {
            return listOf(resultBuilder("wifi", Groups.NETWORK, Labels.WIFI, ProbeStatus.OK,
                note = bil("当前未连接 WiFi", "Not connected to WiFi"),
                metrics = listOf(metric("state", L("状态", "State"), bil("未连接", "Disconnected"), primary = true))))
        }
        val metrics = mutableListOf(
            metric("ssid", Labels.SSID, info.ssid.trim('"'), primary = true),
            metric("bssid", Labels.BSSID, info.bssid ?: bil("未知", "Unknown")),
            metric("rssi", Labels.RSSI, "${info.rssi} dBm"),
            metric("freq", Labels.FREQ, "${info.frequency} MHz"),
            metric("channel", Labels.CHANNEL, ChannelOf.of(info.frequency).toString()),
            metric("link_speed", Labels.LINK_SPEED, "${info.linkSpeed} Mbps"),
            metric("hidden", L("隐藏网络", "Hidden network"), if (info.hiddenSSID) bil("是", "Yes") else bil("否", "No")),
        )
        val ip = info.ipAddress
        if (ip != 0) {
            metrics.add(metric("ip", Labels.IP_ADDR, (ip and 0xff).toString() + "." + ((ip shr 8) and 0xff) + "." + ((ip shr 16) and 0xff) + "." + ((ip shr 24) and 0xff)))
        }
        live.set("wifi", Labels.WIFI.en, info.ssid.trim('"'))
        return listOf(resultBuilder("wifi", Groups.NETWORK, Labels.WIFI, ProbeStatus.OK, metrics = metrics))
    }
}

private class WifiScanUnit(
    private val ctx: Context,
    private val deadlineMs: Long,
) {
    suspend fun run(): List<ProbeResult> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            return listOf(resultBuilder("wifi_scan", Groups.NETWORK, Labels.WIFI_SCAN, ProbeStatus.FEATURE_OFF, note = bil("WiFi 未开启", "WiFi is off")))
        }
        val nearbyPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val locPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!nearbyPerm || !locPerm) {
            return listOf(resultBuilder("wifi_scan", Groups.NETWORK, Labels.WIFI_SCAN, ProbeStatus.PERMISSION_MISSING, note = bil("需要邻近设备与位置权限", "Needs nearby devices & location permission")))
        }
        try { wifi.startScan() } catch (_: Throwable) {}
        delay(1500)
        val results = try { wifi.scanResults } catch (_: Throwable) { emptyList<WifiScanResult>() }
        if (results.isEmpty()) {
            return listOf(resultBuilder("wifi_scan", Groups.NETWORK, Labels.WIFI_SCAN, ProbeStatus.FAILED, note = bil("无扫描结果(系统节流或受限)", "No results (system throttling?)")))
        }
        val sorted = results.sortedByDescending { it.level }.take(15)
        val secCounts = HashMap<String, Int>()
        results.forEach { r ->
            val s = WifiSecurity.of(r.capabilities)
            secCounts[s] = (secCounts[s] ?: 0) + 1
        }
        val openCount = secCounts["OPEN"] ?: 0
        val metrics = mutableListOf(
            metric("ap_count", Labels.AP_COUNT, results.size.toString(), primary = true),
            metric("open", Labels.OPEN_NETWORKS, openCount.toString()),
            metric("security_dist", L("加密分布", "Security mix"), secCounts.entries.joinToString(", ") { "${WifiSecurity.display(it.key)}:${it.value}" }),
        )
        val detail = sorted.joinToString("\n") {
            val sec = WifiSecurity.of(it.capabilities)
            "${it.SSID.ifEmpty { bil("(隐藏)", "(hidden)") }} | ${WifiSecurity.display(sec)} | ${it.level}dBm | ${it.frequency}MHz | ${it.BSSID}"
        }
        metrics.add(metric("detail", L("热点明细", "AP details"), detail))
        return listOf(resultBuilder("wifi_scan", Groups.NETWORK, Labels.WIFI_SCAN, ProbeStatus.OK, metrics = metrics))
    }
}

private class CellularUnit(
    private val ctx: Context,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun run(): List<ProbeResult> {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val metrics = mutableListOf(
            metric("net_type", Labels.NET_TYPE, NetworkTypeNames.name(tm.dataNetworkType), primary = true),
            metric("operator", Labels.OPERATOR, tm.networkOperatorName.ifEmpty { bil("未知", "Unknown") }),
            metric("sim_country", Labels.SIM_COUNTRY, tm.simCountryIso.uppercase().ifEmpty { bil("未知", "Unknown") }),
            metric("roaming", Labels.ROAMING, if (tm.isNetworkRoaming) bil("漫游中", "Roaming") else bil("未漫游", "No roaming")),
            metric("sim_state", L("SIM 状态", "SIM state"), simStateName(tm.simState)),
        )
        val mccMnc = tm.networkOperator
        if (mccMnc.isNotBlank()) metrics.add(metric("mcc_mnc", Labels.MCC_MNC, mccMnc))

        var best: Pair<String, String>? = null
        val cells = mutableListOf<String>()
        try {
            tm.allCellInfo.forEach { ci ->
                var sig: String? = null
                var cell: String? = null
                when (ci) {
                    is android.telephony.CellInfoLte -> {
                        val s = ci.cellSignalStrength
                        sig = "LTE RSRP ${s.rsrp}dBm RSRQ ${s.rsrq}dB SNR ${s.rssnr}dB"
                        val id = ci.cellIdentity
                        cell = "LTE ci=${id.ci} tac=${id.tac} pci=${id.pci} earfcn=${id.earfcn}"
                    }
                    is android.telephony.CellInfoNr -> {
                        val s = ci.cellSignalStrength as android.telephony.CellSignalStrengthNr
                        sig = "NR SS-RSRP ${s.ssRsrp}dBm SS-RSRQ ${s.ssRsrq}dB SS-SINR ${s.ssSinr}dB"
                        val id = ci.cellIdentity as android.telephony.CellIdentityNr
                        cell = "NR nci=${id.nci} pci=${id.pci} nrarfcn=${id.nrarfcn} tac=${id.tac}"
                    }
                    is android.telephony.CellInfoGsm -> {
                        sig = "GSM RSSI ${ci.cellSignalStrength.rssi}dBm"
                        val id = ci.cellIdentity
                        cell = "GSM cid=${id.cid} lac=${id.lac}"
                    }
                    is android.telephony.CellInfoCdma -> {
                        val s = ci.cellSignalStrength
                        sig = "CDMA dBm=${s.level}"
                        val id = ci.cellIdentity
                        cell = "CDMA sid=${id.systemId} nid=${id.basestationId}"
                    }
                    is android.telephony.CellInfoWcdma -> {
                        sig = "WCDMA level=${ci.cellSignalStrength.level}"
                        val id = ci.cellIdentity
                        cell = "WCDMA cid=${id.cid} psc=${id.psc}"
                    }
                }
                if (sig != null) {
                    if (ci.isRegistered) best = sig to (cell ?: "")
                    else if (cells.size < 5) cells.add("$sig $cell")
                }
            }
        } catch (_: Throwable) {}

        if (best != null) {
            metrics.add(metric("signal", Labels.RSRP, best!!.first, primary = true))
            if (best!!.second.isNotEmpty()) metrics.add(metric("cell", Labels.CELL_ID, best!!.second))
        } else {
            metrics.add(metric("signal", Labels.RSRP, bil("无信号数据", "No signal data")))
        }
        if (cells.isNotEmpty()) {
            metrics.add(metric("neighbors", L("邻区", "Neighbor cells"), cells.joinToString("\n")))
        }
        val level = try {
            val l = tm.getSignalStrength()
            l?.level
        } catch (_: Throwable) { null }
        if (level != null) metrics.add(metric("level", L("信号等级", "Signal level"), "$level/4"))

        live.set("cellular", Labels.CELLULAR.en, NetworkTypeNames.name(tm.dataNetworkType))
        return listOf(resultBuilder("cellular", Groups.NETWORK, Labels.CELLULAR, ProbeStatus.OK, metrics = metrics))
    }

    private fun simStateName(state: Int): String = when (state) {
        android.telephony.TelephonyManager.SIM_STATE_READY -> bil("正常", "Ready")
        android.telephony.TelephonyManager.SIM_STATE_ABSENT -> bil("无 SIM", "Absent")
        android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN"
        android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK"
        android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> bil("网络锁定", "Network locked")
        android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> bil("未就绪", "Not ready")
        else -> "UNKNOWN($state)"
    }
}

private class ConnectivityUnit(private val ctx: Context) {
    suspend fun run(): List<ProbeResult> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metrics = mutableListOf<com.vicinityprobe.model.Metric>()
        var vpn = false
        try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                val transports = ArrayList<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add(bil("WiFi", "WiFi"))
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add(bil("蜂窝", "Cellular"))
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add(bil("以太网", "Ethernet"))
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    transports.add("VPN")
                    vpn = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("BT")
                metrics.add(metric("transports", Labels.TRANSPORTS, transports.joinToString(", "), primary = true))
                metrics.add(metric("up_bandwidth", L("上行带宽", "Uplink"), "${caps.linkUpstreamBandwidthKbps} kbps"))
                metrics.add(metric("down_bandwidth", L("下行带宽", "Downlink"), "${caps.linkDownstreamBandwidthKbps} kbps"))
                metrics.add(metric("metered", L("计费网络", "Metered"), if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) bil("否", "No") else bil("是", "Yes")))
            }
            val lp = cm.getLinkProperties(cm.activeNetwork)
            if (lp != null) {
                val v4 = lp.linkAddresses.mapNotNull { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
                val v6 = lp.linkAddresses.mapNotNull { it.address }.filterNot { it is Inet4Address }.firstOrNull()
                if (v4 != null) metrics.add(metric("ipv4", L("IPv4", "IPv4"), v4.hostAddress ?: "", primary = true))
                if (v6 != null) metrics.add(metric("ipv6", L("IPv6", "IPv6"), v6.hostAddress?.substringBefore('%') ?: ""))
                val dns = lp.dnsServers.mapNotNull { it.hostAddress }.take(3)
                if (dns.isNotEmpty()) metrics.add(metric("dns", Labels.DNS, dns.joinToString(", ")))
                val gw = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                if (gw != null) metrics.add(metric("gateway", Labels.GATEWAY, gw))
            }
        } catch (_: Throwable) {}
        metrics.add(metric("vpn", Labels.VPN, if (vpn) bil("已启用 VPN", "VPN active") else bil("未检测到 VPN", "No VPN")))

        val interfaces = ArrayList<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                val ips = ni.interfaceAddresses.mapNotNull { it.address.hostAddress }.joinToString(",")
                val mac = try { ni.hardwareAddress?.joinToString(":") { "%02X".format(it) } } catch (_: Throwable) { null }
                interfaces.add("${ni.name} ${if (ni.isUp) "UP" else "DOWN"} mtu=${ni.mtu} ip=[$ips]${mac?.let { " mac=$it" } ?: ""}")
            }
        } catch (_: Throwable) {}
        if (interfaces.isNotEmpty()) metrics.add(metric("interfaces", L("网络接口", "Interfaces"), interfaces.joinToString("\n")))
        return listOf(resultBuilder("connectivity", Groups.NETWORK, Labels.CONNECTIVITY, ProbeStatus.OK, metrics = metrics))
    }
}

private class BluetoothUnit(
    private val ctx: Context,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    suspend fun run(): List<ProbeResult> {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter ?: return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.NO_HARDWARE, note = bil("无蓝牙硬件", "No Bluetooth hardware")))
        if (!adapter.isEnabled) {
            return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.FEATURE_OFF, note = bil("蓝牙未开启", "Bluetooth is off")))
        }
        val scanPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (!scanPerm) {
            return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.BLUETOOTH_SCAN))
        }
        val scanner = adapter.bluetoothLeScanner ?: return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.FAILED))
        val found = ArrayList<ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(found) { found.add(result) }
            }
        }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (_: Throwable) {
            return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.FAILED))
        }
        val scanEnd = SystemClockCompat.elapsedRealtime() + 3000
        while (SystemClockCompat.elapsedRealtime() < scanEnd && SystemClockCompat.elapsedRealtime() < deadlineMs) {
            delay(100)
        }
        try { scanner.stopScan(callback) } catch (_: Throwable) {}
        val list = synchronized(found) { found.sortedByDescending { it.rssi }.take(12) }
        if (list.isEmpty()) {
            return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.OK,
                note = bil("未发现 BLE 设备", "No BLE devices found"),
                metrics = listOf(metric("count", L("设备数", "Devices"), "0", primary = true))))
        }
        val metrics = mutableListOf(
            metric("count", L("发现设备数", "Devices found"), list.size.toString(), primary = true),
        )
        val detail = list.joinToString("\n") { r ->
            val name = r.device.name ?: bil("(未知名称)", "(unnamed)")
            val services = r.scanRecord?.serviceUuids?.take(3)?.joinToString(",") ?: ""
            val mfg = r.scanRecord?.manufacturerSpecificData?.size() ?: 0
            "$name | ${r.device.address} | ${r.rssi}dBm${if (services.isNotEmpty()) " | svc:$services" else ""}${if (mfg > 0) " | mfg:${mfg}B" else ""}"
        }
        metrics.add(metric("detail", L("设备明细", "Device details"), detail))
        live.set("bluetooth", Labels.BLUETOOTH.en, "${list.size} devices")
        return listOf(resultBuilder("bluetooth", Groups.NETWORK, Labels.BLUETOOTH, ProbeStatus.OK, metrics = metrics))
    }
}

private class BluetoothPairedUnit(private val ctx: Context) {
    suspend fun run(): List<ProbeResult> {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter ?: return listOf(resultBuilder("bt_paired", Groups.NETWORK, Labels.BT_PAIRED, ProbeStatus.NO_HARDWARE, note = bil("无蓝牙硬件", "No Bluetooth hardware")))
        if (!adapter.isEnabled) {
            return listOf(resultBuilder("bt_paired", Groups.NETWORK, Labels.BT_PAIRED, ProbeStatus.FEATURE_OFF, note = bil("蓝牙未开启", "Bluetooth is off")))
        }
        val connectPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!connectPerm) {
            return listOf(resultBuilder("bt_paired", Groups.NETWORK, Labels.BT_PAIRED, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.BLUETOOTH_CONNECT))
        }
        val devices = try { adapter.bondedDevices } catch (_: Throwable) { null }
        if (devices.isNullOrEmpty()) {
            return listOf(resultBuilder("bt_paired", Groups.NETWORK, Labels.BT_PAIRED, ProbeStatus.OK,
                metrics = listOf(metric("count", L("已配对", "Paired"), "0", primary = true))))
        }
        val list = devices.map { "${it.name ?: bil("(未知)", "(unnamed)")} (${it.address})" }.sorted()
        return listOf(resultBuilder("bt_paired", Groups.NETWORK, Labels.BT_PAIRED, ProbeStatus.OK,
            metrics = listOf(
                metric("count", L("已配对", "Paired"), list.size.toString(), primary = true),
                metric("detail", L("设备列表", "Devices"), list.joinToString("\n")),
            )))
    }
}
