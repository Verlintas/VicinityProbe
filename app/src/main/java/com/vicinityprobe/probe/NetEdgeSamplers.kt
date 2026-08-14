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
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** ARP 邻居表:读取 /proc/net/arp,列出局域网邻居(IP/MAC/设备/状态) */
class ArpTableSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_arp_table")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        val neighbors = ArrayList<String>()
        var gwMac: String? = null
        val gw = NetInfo.gatewayAndPrefix(ctx)?.first
        try {
            val lines = File("/proc/net/arp").readLines()
            lines.drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6) {
                    val ip = parts[0]
                    val hwType = parts[1]
                    val flags = parts[2]
                    val mac = parts[3]
                    val device = parts[5]
                    if (hwType == "0x1" && mac != "00:00:00:00:00:00") {
                        val state = if (flags == "0x2") "REACHABLE" else "UNREACHABLE"
                        neighbors.add("$ip|$mac|$device|$state")
                        if (ip == gw) gwMac = mac
                    }
                }
            }
        } catch (_: Throwable) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "ARP 表不可读|ARP table unreadable")
        }
        if (neighbors.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "ARP 表为空|ARP table empty")
        }
        val ipMac = neighbors.associate { it.split('|')[0] to it.split('|')[1] }
        val duplicateMacs = ipMac.values.groupingBy { it }.eachCount().filter { it.value > 1 }
        val macVendors = neighbors.mapNotNull { it.split('|').getOrNull(1)?.let { mac -> mac to (OuiDb.vendor(mac) ?: "?") } }
            .distinctBy { it.first }
        attrs["neighbors"] = neighbors.size.toString()
        attrs["entries"] = neighbors.joinToString("\n")
        attrs["duplicate_mac"] = duplicateMacs.values.sum().toString()
        attrs["gateway_mac"] = gwMac ?: "?"
        attrs["gateway_vendor"] = gwMac?.let { OuiDb.vendor(it) ?: "?" } ?: "?"
        if (macVendors.isNotEmpty()) {
            attrs["vendors"] = macVendors.joinToString(",") { "${it.first}:${it.second}" }
        }
        return okMeasurement(spec, attrs, quality = QualityReport(
            level = if (duplicateMacs.isNotEmpty()) QualityLevel.DEGRADED else QualityLevel.EXCELLENT,
            code = QualityLevels.CODE_OK, sampleCount = neighbors.size,
        ))
    }
}

/** DNS over HTTPS 探测:经加密 DoH 解析域名,对比普通 DNS 结果并测延迟 */
class DohSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_doh")!!

    private val endpoints = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
    )
    private val domains = listOf("github.com", "www.baidu.com")

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        var ok = 0
        for (endpoint in endpoints) {
            for (domain in domains) {
                val (latencyMs, ips, certOk) = withContext(Dispatchers.IO) { queryDoH(endpoint, domain) }
                if (ips.isNotEmpty()) ok++
                attrs["${endpoint.substringAfter("https://").substringBefore('/')}_$domain"] =
                    "${latencyMs}ms|${ips.take(3).joinToString(",")}|cert:$certOk"
            }
        }
        attrs["ok"] = ok.toString()
        attrs["total"] = (endpoints.size * domains.size).toString()
        val reachable = ok > 0
        val verdict = when {
            !reachable -> bil("DoH 全部不可达(可能被阻断/网络受限)|DoH unreachable (blocked or restricted network?)", "")
            ok == endpoints.size * domains.size -> bil("DoH 解析全部成功|All DoH lookups succeeded", "")
            else -> bil("部分 DoH 端点不可达|Some DoH endpoints unreachable", "")
        }
        return okMeasurement(spec, attrs, quality = QualityReport(
            level = if (reachable) QualityLevel.EXCELLENT else QualityLevel.FAILED,
            code = if (reachable) QualityLevels.CODE_OK else QualityLevels.CODE_NO_DATA,
            detail = verdict,
            sampleCount = ok,
        ))
    }

    /** 宽松信任链验证(用于检测证书问题,不校验主机名以识别拦截中间人) */
    private fun queryDoH(endpoint: String, domain: String): Triple<Long, List<String>, Boolean> {
        return try {
            val start = System.nanoTime()
            val ctxTls = SSLContext.getInstance("TLS")
            ctxTls.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), null)
            val conn = java.net.URL(endpoint).openConnection() as HttpsURLConnection
            conn.sslSocketFactory = ctxTls.socketFactory
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.setRequestProperty("Content-Type", "application/dns-json")
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.outputStream.use { it.write("""{"name":"$domain","type":"A"}""".toByteArray()) }
            val code = conn.responseCode
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            val body = if (code == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else ""
            conn.disconnect()
            val ips = Regex("\"data\":\"([0-9.]+)\"").findAll(body).map { it.groupValues[1] }.toList()
            Triple(latencyMs, ips, code == 200)
        } catch (_: Throwable) {
            Triple(0L, emptyList(), false)
        }
    }
}

/** QUIC 连通性探测:向知名 QUIC 服务发 Initial 包,验证 UDP 443 QUIC 可达性 */
class QuicProbeSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_quic")!!

    /** cloudflare / google 的 QUIC 服务 */
    private val targets = listOf("cloudflare.com" to 443, "www.google.com" to 443)

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        var success = 0
        for ((host, port) in targets) {
            val (latencyMs, version, scid, firstByte) = withContext(Dispatchers.IO) { probeQuic(host, port) }
            attrs[host] = if (firstByte >= 0)
                "${latencyMs}ms|v${version}|scid:${scid.take(8)}|resp:0x${"%02X".format(firstByte)}"
            else "unreachable"
            if (firstByte >= 0) success++
        }
        val reachable = success > 0
        attrs["ok"] = success.toString()
        return okMeasurement(spec, attrs, quality = QualityReport(
            level = if (reachable) QualityLevel.EXCELLENT else QualityLevel.FAILED,
            code = if (reachable) QualityLevels.CODE_OK else QualityLevels.CODE_NO_DATA,
            detail = if (reachable) bil("QUIC (UDP 443) 可达|QUIC (UDP 443) reachable", "")
            else bil("QUIC 不可达(UDP 443 被阻断?)|QUIC unreachable (UDP 443 blocked?)", ""),
            sampleCount = success,
        ))
    }

    /** 发 QUIC Initial(伪随机 DCID),等待 3s 内收到任何 UDP 响应 */
    private fun probeQuic(host: String, port: Int): Quad {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            val rnd = java.util.Random()
            val dcid = ByteArray(8) { (rnd.nextInt(256)).toByte() }
            val pkt = buildQuicInitial(dcid)
            socket.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(host), port))
            val start = System.nanoTime()
            val buf = ByteArray(2048)
            val rp = DatagramPacket(buf, buf.size)
            val firstByte = try {
                socket.receive(rp); buf[0].toInt() and 0xFF
            } catch (_: SocketTimeoutException) {
                -1
            }
            socket.close()
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            // 解析版本(长包头 bytes 1-4)
            val version = if (rp.length >= 5 && firstByte and 0x80 != 0)
                ((buf[1].toInt() and 0xFF) shl 24) or ((buf[2].toInt() and 0xFF) shl 16) or
                    ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
            else 0
            val scid = if (rp.length >= 6 && firstByte and 0x80 != 0)
                dcid.joinToString("") { "%02X".format(it) } else ""
            Quad(latencyMs, version, scid, firstByte)
        } catch (_: Throwable) {
            Quad(0L, 0, "", -1)
        }
    }

    private fun buildQuicInitial(dcid: ByteArray): ByteArray {
        // 长包头: 1 字节首部(0xC0=初始) + 4 字节版本(0x00000001) + DCID len + DCID + SCID len(0) + Token len(0) + 长度
        val out = java.io.ByteArrayOutputStream()
        out.write(0xC0)                          // 长头 + 固定位 + 初始包类型
        out.write(byteArrayOf(0, 0, 0, 1))       // QUIC v1
        out.write(dcid.size)                     // DCID 长度
        out.write(dcid)                          // DCID
        out.write(0)                             // SCID 长度
        out.write(0)                             // token 长度
        out.write(byteArrayOf(0, 0))             // 包长度(占位,0)
        val body = ByteArray(64)                 // 伪 CRYPTO 帧(服务端只需识别初始包)
        java.util.Random().nextBytes(body)
        out.write(body)
        return out.toByteArray()
    }

    private data class Quad(val latencyMs: Long, val version: Int, val scid: String, val firstByte: Int)
}
