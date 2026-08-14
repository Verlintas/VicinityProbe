/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import android.content.Context
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URL

/**
 * 硬核网络检测:
 * - DNS 劫持检测(自写 DNS 客户端,多公共 DNS 对比)
 * - ARP 欺骗检测(网关 MAC 多次采样对比)
 * - mDNS 服务发现(组播 _services._dns-sd._udp.local)
 * - UPnP 设备深度解析(拉取描述 XML)
 */

/** 自写 DNS 客户端:构造查询包并解析 A/CNAME 记录 */
object MiniDns {
    fun query(server: String, domain: String, timeoutMs: Int = 2000): List<String>? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            val id = (System.nanoTime() and 0xFFFF).toInt()
            val qname = encodeName(domain)
            val pkt = ByteArray(12 + qname.size + 4)
            pkt[0] = (id shr 8).toByte(); pkt[1] = id.toByte()
            pkt[2] = 0x01; pkt[3] = 0x00   // RD
            pkt[5] = 0x01                  // QDCOUNT=1
            System.arraycopy(qname, 0, pkt, 12, qname.size)
            val qOff = 12 + qname.size
            pkt[qOff] = 0; pkt[qOff + 1] = 1    // QTYPE A
            pkt[qOff + 2] = 0; pkt[qOff + 3] = 1 // QCLASS IN
            socket.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(server), 53))
            val resp = ByteArray(4096)
            val rp = DatagramPacket(resp, resp.size)
            socket.receive(rp)
            socket.close()
            parseResponse(resp, rp.length)
        } catch (_: Throwable) { null }
    }

    fun encodeName(domain: String): ByteArray {
        val parts = domain.split('.')
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { p ->
            out.write(p.length)
            out.write(p.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun parseResponse(buf: ByteArray, len: Int): List<String> {
        val answers = ArrayList<String>()
        if (len < 12) return answers
        val qdcount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val ancount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (ancount == 0) return answers
        var pos = 12
        // 跳过 question
        repeat(qdcount) {
            pos = skipName(buf, pos, len) + 4
        }
        repeat(ancount) {
            if (pos >= len) return answers
            pos = skipName(buf, pos, len)
            if (pos + 10 > len) return answers
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val rdlen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
            val dataOff = pos + 10
            if (dataOff + rdlen > len) return answers
            when (type) {
                1 -> if (rdlen == 4) answers.add("${buf[dataOff].toInt() and 0xFF}.${buf[dataOff + 1].toInt() and 0xFF}.${buf[dataOff + 2].toInt() and 0xFF}.${buf[dataOff + 3].toInt() and 0xFF}")
                5 -> answers.add("CNAME")
            }
            pos = dataOff + rdlen
        }
        return answers.distinct()
    }

    private fun skipName(buf: ByteArray, start: Int, len: Int): Int {
        var pos = start
        var jumps = 0
        while (pos < len && jumps < 16) {
            val l = buf[pos].toInt() and 0xFF
            if (l == 0) return pos + 1
            if (l and 0xC0 == 0xC0) { jumps++; return pos + 2 }
            pos += 1 + l
        }
        return pos
    }
}

/** DNS 劫持检测:多公共 DNS 解析同一域名,对比结果一致性 */
class DnsHijackSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_dns_hijack")!!

    private val servers = listOf("8.8.8.8", "1.1.1.1", "223.5.5.5")
    private val domains = listOf("github.com", "www.baidu.com", "www.cloudflare.com", "www.google.com", "www.qq.com")

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        var inconsistencies = 0
        var queried = 0
        for (domain in domains) {
            val results = HashMap<String, List<String>>()
            for (server in servers) {
                val ips = withContext(Dispatchers.IO) { MiniDns.query(server, domain) }
                if (ips != null) results[server] = ips
            }
            queried += results.size
            val nonEmpty = results.values.filter { it.isNotEmpty() && !it.contains("CNAME") }
            // 空结果(NXDOMAIN/超时)也要参与比对,否则会漏报"一个解析器有 A 记录、另一个 NXDOMAIN"的不一致
            val sets = results.values.map { if (it.isEmpty()) listOf("<empty>") else it }.toSet()
            val verdict = when {
                results.size < 2 -> "insufficient"
                sets.size > 1 -> {
                    inconsistencies++
                    "MISMATCH"
                }
                nonEmpty.isEmpty() -> "NXDOMAIN"
                else -> "consistent"
            }
            attrs["dns_$domain"] = results.entries.joinToString(" | ") { "${it.key}:${it.value.take(4).joinToString(",")}" } +
                " → $verdict"
        }
        attrs["queried"] = queried.toString()
        attrs["inconsistencies"] = inconsistencies.toString()
        attrs["verdict"] = when {
            inconsistencies > 0 -> bil("检测到 DNS 结果不一致(可能劫持/分裂 DNS)|DNS results inconsistent across resolvers (possible hijack/split-DNS)", "检测到 DNS 结果不一致(可能劫持/分裂 DNS)|DNS results inconsistent across resolvers (possible hijack/split-DNS)")
            queried >= 6 -> "多源 DNS 结果一致,未发现劫持迹象|Consistent across resolvers, no hijack signs"
            else -> "查询不足,无法判定|Insufficient queries"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(
                level = if (inconsistencies > 0) QualityLevel.DEGRADED else QualityLevel.EXCELLENT,
                code = QualityLevels.CODE_OK, sampleCount = queried,
            ))
    }
}

/** ARP 欺骗检测:会话内多次采样网关 MAC,检测变化 */
class ArpSpoofSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_arp_spoof")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val gw = NetInfo.gatewayAndPrefix(ctx)?.first
            ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No gateway")
        val macs = java.util.Collections.synchronizedList(ArrayList<String>())
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            readMac(gw)?.let { macs.add(it) }
            delay(500)
        }
        if (macs.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "网关 MAC 不可读|Gateway MAC unreadable")
        }
        val distinct = macs.distinct()
        val attrs = LinkedHashMap<String, String>()
        attrs["gateway"] = gw
        attrs["samples"] = macs.size.toString()
        attrs["mac_first"] = macs.first()
        attrs["mac_last"] = macs.last()
        attrs["distinct_macs"] = distinct.joinToString(",")
        attrs["changes"] = (distinct.size - 1).toString()
        // 辅助:对比网关 MAC 与 WiFi BSSID(若同网段)
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val bssid = wifi.connectionInfo.bssid
            if (bssid != null && gw == NetInfo.gatewayAndPrefix(ctx)?.first) {
                attrs["wifi_bssid"] = bssid
            }
        } catch (_: Throwable) {}
        val changed = distinct.size > 1
        attrs["verdict"] = if (changed) {
            "网关 MAC 在采样期间发生变化(网络切换或 ARP 欺骗嫌疑)|Gateway MAC changed during sampling (network switch or possible ARP spoofing)"
        } else {
            "网关 MAC 稳定,未发现 ARP 欺骗迹象|Gateway MAC stable, no spoofing signs"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(
                level = if (changed) QualityLevel.DEGRADED else QualityLevel.EXCELLENT,
                code = QualityLevels.CODE_OK, sampleCount = macs.size,
                achievedRateHz = macs.size.toDouble() / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 2.0,
            ))
    }

    private fun readMac(ip: String): String? {
        return try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6 && parts[0] == ip && parts[2] == "0x2") return parts[3]
            }
            null
        } catch (_: Throwable) { null }
    }
}

/** mDNS 服务发现:_services._dns-sd._udp.local PTR 查询 */
class MdnsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_mdns")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val responses = java.util.Collections.synchronizedList(ArrayList<String>())
        val socket = DatagramSocket()
        socket.soTimeout = 2000
        // PTR 查询 _services._dns-sd._udp.local
        val name = MiniDns.encodeName("_services._dns-sd._udp.local")
        val pkt = ByteArray(12 + name.size + 4)
        pkt[0] = 0; pkt[1] = 0x01
        pkt[2] = 0; pkt[3] = 0
        pkt[5] = 0x01
        System.arraycopy(name, 0, pkt, 12, name.size)
        val qOff = 12 + name.size
        pkt[qOff] = 0; pkt[qOff + 1] = 12    // PTR
        pkt[qOff + 2] = 0; pkt[qOff + 3] = 1
        try {
            val target = InetAddress.getByName("224.0.0.251")
            repeat(3) {
                socket.send(DatagramPacket(pkt, pkt.size, target, 5353))
                delay(100)
            }
            val buf = ByteArray(4096)
            val rp = DatagramPacket(buf, buf.size)
            val end = SystemClockCompat.elapsedRealtime() + 2500
            while (SystemClockCompat.elapsedRealtime() < end) {
                try {
                    socket.receive(rp)
                    val s = decodeMdns(buf, rp.length)
                    if (s != null) responses.add("${rp.address.hostAddress}: $s")
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}
        val list = synchronized(responses) { responses.distinct().toList() }
        if (list.isEmpty()) {
            return okMeasurement(spec, mapOf("services" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["services"] = list.size.toString()
        attrs["detail"] = list.joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = list.size))
    }

    /** 提取 PTR 响应中的服务实例名 */
    private fun decodeMdns(buf: ByteArray, len: Int): String? {
        if (len < 12) return null
        val ancount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (ancount == 0) return null
        var pos = 12
        val qdcount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        repeat(qdcount) { pos = skip(buf, pos, len) + 4 }
        val names = ArrayList<String>()
        repeat(ancount) {
            if (pos >= len) return@repeat
            pos = skip(buf, pos, len)
            if (pos + 10 > len) return@repeat
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val rdlen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
            val dataOff = pos + 10
            if (dataOff + rdlen > len) return@repeat
            if (type == 12) { // PTR: rdata 是域名
                val name = readName(buf, dataOff, len)
                if (name != null) names.add(name)
            }
            pos = dataOff + rdlen
        }
        return names.firstOrNull()
    }

    private fun skip(buf: ByteArray, start: Int, len: Int): Int {
        var pos = start
        var jumps = 0
        while (pos < len && jumps < 16) {
            val l = buf[pos].toInt() and 0xFF
            if (l == 0) return pos + 1
            if (l and 0xC0 == 0xC0) { jumps++; return pos + 2 }
            pos += 1 + l
        }
        return pos
    }

    private fun readName(buf: ByteArray, start: Int, len: Int): String? {
        var pos = start
        val sb = StringBuilder()
        var jumps = 0
        while (pos < len && jumps < 16) {
            val l = buf[pos].toInt() and 0xFF
            if (l == 0) break
            if (l and 0xC0 == 0xC0) {
                jumps++
                if (pos + 1 >= len) break
                pos = ((buf[pos].toInt() and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                continue
            }
            pos++
            if (pos + l > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append((buf[pos + i].toInt() and 0xFF).toChar())
            pos += l
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}

/** UPnP 设备深度解析:对 SSDP 发现的设备拉取描述文档 */
class UpnpDetailSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_upnp_detail")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        // 先做一次 SSDP 发现拿 LOCATION
        val locations = java.util.Collections.synchronizedList(ArrayList<String>())
        val socket = DatagramSocket()
        socket.soTimeout = 1500
        val req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
        try {
            val target = InetAddress.getByName("239.255.255.250")
            repeat(2) {
                socket.send(DatagramPacket(req.toByteArray(), req.length, target, 1900))
                delay(100)
            }
            val buf = ByteArray(4096)
            val rp = DatagramPacket(buf, buf.size)
            val end = SystemClockCompat.elapsedRealtime() + 1800
            while (SystemClockCompat.elapsedRealtime() < end) {
                try {
                    socket.receive(rp)
                    val data = String(rp.data, 0, rp.length)
                    val loc = data.lineSequence().firstOrNull { it.startsWith("LOCATION", true) }?.substringAfter(':')?.trim()
                    if (loc != null && loc.startsWith("http")) locations.add(loc)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}

        if (locations.isEmpty()) {
            return okMeasurement(spec, mapOf("devices" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["devices"] = locations.distinct().size.toString()
        val details = ArrayList<String>()
        locations.distinct().take(6).forEachIndexed { i, loc ->
            val info = withContext(Dispatchers.IO) { fetchDescription(loc) }
            details.add("[$i] $loc\n$info")
            attrs["device_$i"] = info ?: "unreachable"
        }
        attrs["detail"] = details.joinToString("\n\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = locations.distinct().size))
    }

    private fun fetchDescription(location: String): String? {
        return try {
            val url = URL(location)
            val conn = url.openConnection()
            conn.connectTimeout = 2000
            conn.readTimeout = 2500
            val body = conn.getInputStream().bufferedReader().use { it.readText() }.take(4096)
            val deviceType = Regex("deviceType[^>]*>([^<]+)<").find(body)?.groupValues?.get(1)
            val model = Regex("modelName[^>]*>([^<]+)<").find(body)?.groupValues?.get(1)
            val friendly = Regex("friendlyName[^>]*>([^<]+)<").find(body)?.groupValues?.get(1)
            val mfr = Regex("manufacturer[^>]*>([^<]+)<").find(body)?.groupValues?.get(1)
            val serial = Regex("serialNumber[^>]*>([^<]+)<").find(body)?.groupValues?.get(1)
            val services = Regex("<serviceType[^>]*>([^<]+)<").findAll(body).map { it.groupValues[1] }.toList().take(5)
            buildString {
                append("deviceType=${deviceType ?: "?"}\n")
                append("friendlyName=${friendly ?: "?"}\n")
                append("model=${model ?: "?"}\n")
                append("manufacturer=${mfr ?: "?"}\n")
                if (serial != null) append("serial=$serial\n")
                if (services.isNotEmpty()) append("services=${services.joinToString(",")}")
            }
        } catch (_: Throwable) { null }
    }
}
