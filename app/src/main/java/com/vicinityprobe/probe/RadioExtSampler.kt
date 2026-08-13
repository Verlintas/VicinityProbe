package com.vicinityprobe.probe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.nfc.NfcAdapter
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.net.NetworkInterface

fun failedMeasurement(spec: ProbeSpec, code: String, detail: String) = Measurement(
    spec = spec, status = code,
    quality = QualityReport(QualityLevel.FAILED, code, detail),
)

fun okMeasurement(spec: ProbeSpec, attrs: Map<String, String> = emptyMap(), stats: Map<String, ChannelStats> = emptyMap(),
                  quality: QualityReport = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1)) =
    Measurement(spec = spec, status = QualityLevels.CODE_OK, attributes = attrs, stats = stats, quality = quality)

/** WiFi 链路动态:会话期间 RSSI 时序 + 链路速率 + 发射功率(反射)/热点状态(反射) */
class WifiDynamicSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_dynamic")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) return failedMeasurement(spec, QualityLevels.CODE_FEATURE_OFF, "WiFi 没开|WiFi off")
        val rec = ChannelRecorder("rssi")
        var rxSpeed = 0; var txSpeed = 0; var supplicant = "?"
        val start = session.startRealtimeMs
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            try {
                val info = wifi.connectionInfo
                if (info.networkId != -1) {
                    rec.add(SystemClockCompat.elapsedRealtime() - start, info.rssi.toFloat())
                    rxSpeed = info.rxLinkSpeedMbps
                    txSpeed = info.txLinkSpeedMbps
                    supplicant = info.supplicantState?.name ?: "?"
                }
            } catch (_: Throwable) {}
            session.live.set("wifi_dynamic", "rssi dBm", rec.snapshot().lastOrNull()?.second?.let { String.format("%.0f", it) } ?: "…")
            delay(200)
        }
        if (rec.size() == 0) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "没连 WiFi|Not connected to WiFi")
        }
        val stats = ChannelStats.compute(rec.snapshot().map { it.second }.toFloatArray(), "dBm")
        val attrs = LinkedHashMap<String, String>()
        attrs["rx_link_speed_mbps"] = rxSpeed.toString()
        attrs["tx_link_speed_mbps"] = txSpeed.toString()
        attrs["supplicant_state"] = supplicant
        // 反射:热点状态(隐藏 API,失败则标注不可读)
        try {
            val ap = wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
            attrs["ap_hotspot_enabled"] = ap?.toString() ?: "unreadable"
        } catch (_: Throwable) {
            attrs["ap_hotspot_enabled"] = "unreadable"
        }
        // 接口 MAC(NetworkInterface,多数设备可读 wlan0)
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.firstOrNull { it.name == "wlan0" }
                ?.hardwareAddress?.joinToString(":") { "%02X".format(it) }
                ?.let { attrs["mac"] = it }
        } catch (_: Throwable) {}
        val coverage = stats.n.toDouble() / (session.elapsedMs().toDouble() / 1000) / 5.0 * 100
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, stats = mapOf("rssi" to stats), attributes = attrs,
            quality = QualityReport(
                level = if (coverage >= 60) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = stats.n,
                achievedRateHz = stats.n / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 5.0,
                coveragePct = coverage.coerceAtMost(100.0),
            ),
            samplesFile = if (stats.n > 0) {
                val dir = File(session.samplesDir, spec.id); dir.mkdirs()
                rec.writeCsv(File(dir, "channel_rssi.csv"), "t_ms,rssi")
                spec.id
            } else null,
            series = mapOf("rssi" to rec.decimate()),
        )
    }
}

/** WiFi RTT(802.11mc FTM)测距:对支持 RTT 的 AP 测距 */
class WifiRttSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_rtt")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val rttManager = ctx.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        if (rttManager == null || !rttManager.isAvailable) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "设备不支持 WiFi RTT|WiFi RTT unsupported")
        }
        val permOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permOk) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "RTT 需要精确定位权限|RTT requires fine location")
        }
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val aps = try { wifi.scanResults.filter { it.is80211mcResponder }.take(8) } catch (_: Throwable) { emptyList() }
        if (aps.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "没发现支持 RTT 的 AP|No 802.11mc responder found")
        }
        val builder = RangingRequest.Builder()
        aps.forEach { builder.addAccessPoint(it) }
        val results = java.util.concurrent.ConcurrentHashMap<String, RangingResult>()
        var finished = false
        val cb = object : RangingResultCallback() {
            override fun onRangingFailure(status: Int) { finished = true }
            override fun onRangingResults(resultsList: List<RangingResult>) {
                resultsList.forEach { r ->
                    val ssid = r.macAddress?.let { mac -> aps.firstOrNull { it.BSSID == mac.toString() }?.SSID } ?: "?"
                    results[ssid] = r
                }
                finished = true
            }
        }
        try {
            rttManager.startRanging(builder.build(), ContextCompat.getMainExecutor(ctx), cb)
        } catch (_: Throwable) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "RTT 启动失败|RTT start failed")
        }
        val end = kotlin.math.min(SystemClockCompat.elapsedRealtime() + 4000, session.deadlineRealtimeMs)
        while (kotlin.coroutines.coroutineContext.isActive && !finished && SystemClockCompat.elapsedRealtime() < end) { delay(100) }

        if (results.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "RTT 没结果|No RTT results")
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["responders"] = aps.size.toString()
        val dists = ArrayList<Double>()
        results.forEach { (ssid, r) ->
            if (r.status == RangingResult.STATUS_SUCCESS) {
                dists.add(r.distanceMm / 1000.0)
                attrs["rtt_$ssid"] = String.format("%.1f m (±%.1f m, %ddBm)", r.distanceMm / 1000.0, r.distanceStdDevMm / 1000.0, r.rssi)
            } else {
                attrs["rtt_$ssid"] = "status=${r.status}"
            }
        }
        if (dists.isEmpty()) return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "RTT 全失败了|All RTT failed")
        return okMeasurement(
            spec,
            attrs = attrs,
            stats = mapOf("distance" to ChannelStats.compute(dists.map { it.toFloat() }.toFloatArray(), "m")),
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = dists.size),
        )
    }
}

/** WiFi Direct(P2P)对等设备发现 */
class WifiDirectSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_direct")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val p2p = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (p2p == null) return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "不支持 WiFi Direct|P2P unsupported")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "没给邻近设备权限|NEARBY_WIFI_DEVICES required")
        }
        var channel: WifiP2pManager.Channel? = null
        var initOk = false
        var done = false
        val peers = java.util.Collections.synchronizedList(ArrayList<String>())
        var groupOwner = 0
        p2p.initialize(ctx, android.os.Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {}
        }).let { ch ->
            channel = ch
            initOk = true
        }
        if (!initOk || channel == null) return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "init failed")

        try {
            p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { done = true }
            })
            p2p.requestPeers(channel) { peerList ->
                synchronized(peers) {
                    peers.clear()
                    peerList.deviceList.forEach { d ->
                        peers.add("${d.deviceName ?: "(unnamed)"} | ${d.deviceAddress} | ${d.primaryDeviceType}${if (d.isGroupOwner) " | GO" else ""}")
                        if (d.isGroupOwner) groupOwner++
                    }
                }
                done = true
            }
        } catch (_: Throwable) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "P2P 发现失败|P2P discovery failed")
        }
        val end = kotlin.math.min(SystemClockCompat.elapsedRealtime() + 5000, session.deadlineRealtimeMs)
        while (kotlin.coroutines.coroutineContext.isActive && !done && SystemClockCompat.elapsedRealtime() < end) { delay(100) }
        val list = synchronized(peers) { peers.toList() }
        val attrs = LinkedHashMap<String, String>()
        attrs["peers_found"] = list.size.toString()
        attrs["group_owners"] = groupOwner.toString()
        if (list.isNotEmpty()) attrs["detail"] = list.joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = list.size))
    }
}

/** Wi-Fi Aware(NAN)能力与感知会话 */
class WifiAwareSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_aware")!!

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val aware = ctx.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (aware == null) return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "不支持 Wi-Fi Aware|Aware unsupported")
        if (!aware.isAvailable) return failedMeasurement(spec, QualityLevels.CODE_FEATURE_OFF, "Wi-Fi Aware 不可用|Aware unavailable")
        val attrs = LinkedHashMap<String, String>()
        try {
            val ch = aware.characteristics
            if (ch != null) {
                attrs["max_service_name_length"] = ch.maxServiceNameLength.toString()
                attrs["max_service_specific_info_length"] = ch.maxServiceSpecificInfoLength.toString()
                attrs["max_match_filter_length"] = ch.maxMatchFilterLength.toString()
            }
        } catch (_: Throwable) {}
        var attached = false
        // 简化:仅上报能力与 attach 结果
        val attach = object : AttachCallback() {
            override fun onAttached(s: android.net.wifi.aware.WifiAwareSession) {
                attached = true
                try {
                    s.subscribe(android.net.wifi.aware.SubscribeConfig.Builder().build(),
                        object : DiscoverySessionCallback() {},
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                } catch (_: Throwable) {}
            }
            override fun onAttachFailed() {}
        }
        try { aware.attach(attach, android.os.Handler(android.os.Looper.getMainLooper())) } catch (_: Throwable) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "attach 失败|attach failed")
        }
        val end = kotlin.math.min(SystemClockCompat.elapsedRealtime() + 3000, session.deadlineRealtimeMs)
        while (kotlin.coroutines.coroutineContext.isActive && !attached && SystemClockCompat.elapsedRealtime() < end) { delay(100) }
        attrs["attached"] = attached.toString()
        attrs["device_role"] = "unspecified"
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 蜂窝信号时序:会话期间持续采样信号等级/dBm */
class CellularSeriesSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("cellular_series")!!

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val levelRec = ChannelRecorder("level")
        val dbmRec = ChannelRecorder("dbm")
        var lastCell = "?"
        var samples = 0
        val start = session.startRealtimeMs
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            try {
                val ss = tm.getSignalStrength()
                if (ss != null) {
                    val t = SystemClockCompat.elapsedRealtime() - start
                    levelRec.add(t, ss.level.toFloat())
                    val dbm = reflectDbm(ss)
                    if (dbm != null) dbmRec.add(t, dbm)
                    samples++
                }
                try {
                    val registered = tm.allCellInfo.firstOrNull { it.isRegistered }
                    registered?.let { ci ->
                        val id = when (ci) {
                            is android.telephony.CellInfoLte -> "LTE ci=${ci.cellIdentity.ci}"
                            is android.telephony.CellInfoNr -> "NR nci=${(ci.cellIdentity as android.telephony.CellIdentityNr).nci}"
                            is android.telephony.CellInfoGsm -> "GSM cid=${ci.cellIdentity.cid}"
                            else -> null
                        }
                        if (id != null) lastCell = id
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            session.live.set("cellular_series", "level", "${levelRec.snapshot().lastOrNull()?.second?.toInt() ?: -1}/4")
            delay(500)
        }
        if (samples == 0) return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "没有信号数据|No signal data")
        val attrs = LinkedHashMap<String, String>()
        attrs["serving_cell"] = lastCell
        attrs["samples"] = samples.toString()
        val stats = LinkedHashMap<String, ChannelStats>()
        stats["level"] = ChannelStats.compute(levelRec.snapshot().map { it.second }.toFloatArray(), "level")
        if (dbmRec.size() > 0) stats["dbm"] = ChannelStats.compute(dbmRec.snapshot().map { it.second }.toFloatArray(), "dBm")
        val coverage = samples.toDouble() / (session.elapsedMs().toDouble() / 1000) / 2.0 * 100
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, stats = stats, attributes = attrs,
            quality = QualityReport(
                level = if (coverage >= 60) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = samples,
                achievedRateHz = samples / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 2.0,
                coveragePct = coverage.coerceAtMost(100.0),
            ),
            samplesFile = null,
            series = stats.mapKeys { it.key }.mapValues { (k, _) ->
                if (k == "level") levelRec.decimate() else dbmRec.decimate()
            }.filterValues { it.isNotEmpty() },
        )
    }

    private fun reflectDbm(ss: android.telephony.SignalStrength): Float? {
        try {
            val m = ss.javaClass.getMethod("getDbm")
            val v = m.invoke(ss) as? Int
            if (v != null && v != Int.MAX_VALUE) return v.toFloat()
        } catch (_: Throwable) {}
        return null
    }
}

/** 经典蓝牙发现(非 BLE) */
class BluetoothClassicSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("bt_classic")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = bm?.adapter
        if (adapter == null) return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "没有蓝牙硬件|No BT hardware")
        if (!adapter.isEnabled) return failedMeasurement(spec, QualityLevels.CODE_FEATURE_OFF, "蓝牙没开|BT off")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "需要蓝牙扫描/连接权限|BLUETOOTH_SCAN/CONNECT required")
        }
        val found = java.util.Collections.synchronizedList(ArrayList<BluetoothDevice>())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    d?.let { synchronized(found) { found.add(it) } }
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        val ok = try { adapter.startDiscovery() } catch (_: Throwable) { false }
        if (!ok) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "发现启动失败|Discovery start failed")
        }
        val end = kotlin.math.min(SystemClockCompat.elapsedRealtime() + 5000, session.deadlineRealtimeMs)
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < end) { delay(100) }
        try { adapter.cancelDiscovery() } catch (_: Throwable) {}
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        val list = synchronized(found) { found.distinctBy { it.address }.toList() }
        if (list.isEmpty()) {
            return okMeasurement(spec, mapOf("devices_found" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["devices_found"] = list.size.toString()
        attrs["detail"] = list.joinToString("\n") { d ->
            val name = try { d.name } catch (_: Throwable) { null } ?: "(unnamed)"
            "$name | ${d.address} | class=0x${String.format("%06X", d.bluetoothClass?.deviceClass ?: 0)}"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = list.size))
    }
}

/** NFC 能力与标签检测 */
class NfcSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("nfc")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val adapter = NfcAdapter.getDefaultAdapter(ctx)
        if (adapter == null) return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "没有 NFC 硬件|No NFC hardware")
        val attrs = LinkedHashMap<String, String>()
        attrs["enabled"] = adapter.isEnabled.toString()
        try {
            val m = NfcAdapter::class.java.getMethod("isNdefPushEnabled")
            attrs["ndef_push_enabled"] = m.invoke(adapter).toString()
        } catch (_: Throwable) {}
        // 反射:支持的技术列表(隐藏字段 mTechList)
        try {
            val f = NfcAdapter::class.java.getDeclaredField("mTechList")
            f.isAccessible = true
            val techs = f.get(adapter) as? Array<String>
            if (techs != null && techs.isNotEmpty()) attrs["technologies"] = techs.joinToString(",")
        } catch (_: Throwable) {
            attrs["technologies"] = "unreadable"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(if (adapter.isEnabled) QualityLevel.EXCELLENT else QualityLevel.DEGRADED,
                QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** FM 调谐器探测(RadioManager 模块;SDK 36 移除公开 API,全反射) */
class FmRadioSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("fm_radio")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        return try {
            val rmClass = Class.forName("android.hardware.radio.RadioManager")
            val service = ctx.getSystemService("radio") ?: return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "No radio service")
            val getModules = rmClass.getMethod("getModuleList")
            val modules = getModules.invoke(service) as? List<*> ?: emptyList<Any>()
            if (modules.isEmpty()) {
                return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "No FM tuner")
            }
            val attrs = LinkedHashMap<String, String>()
            attrs["tuner_count"] = modules.size.toString()
            modules.forEachIndexed { i, module ->
                val m = module ?: return@forEachIndexed
                val id = try { m.javaClass.getMethod("getId").invoke(m)?.toString() ?: "?" } catch (_: Throwable) { "?" }
                val vendor = try { m.javaClass.getMethod("getVendorId").invoke(m)?.toString() ?: "?" } catch (_: Throwable) { "?" }
                val hw = try { m.javaClass.getMethod("getHwAddress").invoke(m)?.toString() ?: "?" } catch (_: Throwable) { "?" }
                val props = try {
                    (m.javaClass.getMethod("getProperties").invoke(m) as? Map<*, *>)?.entries
                        ?.joinToString(",") { "${it.key}=${it.value}" } ?: ""
                } catch (_: Throwable) { "" }
                attrs["tuner_$i"] = "id=$id vendor=$vendor hw=$hw props=[$props]"
            }
            okMeasurement(spec, attrs,
                quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = modules.size))
        } catch (e: Throwable) {
            failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "radio:${e.javaClass.simpleName}")
        }
    }
}

/** 红外发射器 */
class InfraredSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("infrared")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val ir = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir == null || !ir.hasIrEmitter()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_HARDWARE, "没有红外发射器|No IR emitter")
        }
        val ranges = try { ir.carrierFrequencies } catch (_: Throwable) { emptyArray() }
        val attrs = LinkedHashMap<String, String>()
        attrs["has_ir_emitter"] = "true"
        if (ranges.isNotEmpty()) {
            attrs["carrier_freq_ranges_hz"] = ranges.joinToString(",") { "${it.minFrequency}~${it.maxFrequency}" }
            attrs["carrier_freq_max_khz"] = (ranges.maxOfOrNull { it.maxFrequency } ?: 0).toString()
        } else {
            attrs["carrier_freq_ranges_hz"] = "unavailable"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 流量与套接字统计 */
class NetworkStatsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("network_stats")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        fun traffic() {
            try {
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                attrs["total_rx_bytes"] = rx.toString()
                attrs["total_tx_bytes"] = tx.toString()
                attrs["total_rx_packets"] = TrafficStats.getTotalRxPackets().toString()
                attrs["total_tx_packets"] = TrafficStats.getTotalTxPackets().toString()
                if (rx != -1L) attrs["total_rx_gb"] = String.format("%.2f", rx / 1e9)
            } catch (_: Throwable) {}
        }
        traffic()
        val ifaces = ArrayList<String>()
        try {
            File("/proc/net/dev").readLines().drop(2).forEach { line ->
                val parts = line.split(":")
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val nums = parts[1].trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                    if (nums.size >= 9) ifaces.add("$name rx=${nums[0]} tx=${nums[8]} pkts_rx=${nums[1]} pkts_tx=${nums[9]}")
                }
            }
        } catch (_: Throwable) {}
        if (ifaces.isNotEmpty()) attrs["interfaces_traffic"] = ifaces.joinToString("\n")
        fun socketCount(path: String): Int = try { File(path).readLines().count { it.trim().isNotEmpty() && it.startsWith("  ") } } catch (_: Throwable) { -1 }
        val tcp = socketCount("/proc/net/tcp")
        val tcp6 = socketCount("/proc/net/tcp6")
        val udp = socketCount("/proc/net/udp")
        attrs["tcp_connections"] = (if (tcp >= 0 && tcp6 >= 0) tcp + tcp6 else -1).toString()
        attrs["udp_sockets"] = (if (udp >= 0) udp else -1).toString()
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}
