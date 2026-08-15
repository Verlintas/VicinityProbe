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

package com.vicinityprobe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vicinityprobe.MainActivity
import com.vicinityprobe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 免 root 抓包引擎:基于 VpnService 全路由接管,解析 IP 包。
 * - IPv4/IPv6 → TCP/UDP/ICMP 统计
 * - TCP 五元组流表(方向/字节/状态)
 * - HTTP 明文请求解析(方法/路径/Host)
 * - DNS 查询域名解析
 * - TLS ClientHello SNI 提取
 * - 标准 pcap 文件导出(Wireshark 可直接打开)
 */
class PacketCaptureService : VpnService() {
    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 3
        const val ACTION_START = "com.vicinityprobe.action.CAPTURE_START"
        const val ACTION_STOP = "com.vicinityprobe.action.CAPTURE_STOP"

        const val VIRTUAL_IP = "10.0.0.2"
        private const val TUN_MTU = 1500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (captureJob?.isActive == true) return
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("capturing…"))
        // 清理上一次未正常关闭的 TUN/作业,防 fd 泄漏
        captureJob?.cancel()
        captureJob = null
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        val builder = this.Builder()
        builder.setSession("VicinityProbe capture")
        builder.addAddress(VIRTUAL_IP, 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setBlocking(true)
        }
        val fd = try { builder.establish() } catch (_: Throwable) { null }
        if (fd == null) {
            stopCapture()
            return
        }
        tunFd = fd
        CaptureController.reset()
        captureJob = scope.launch {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(TUN_MTU)
            try {
                while (isActive) {
                    val n = try { input.read(buf) } catch (_: Throwable) { -1 }
                    if (n <= 0) break
                    CaptureController.onPacket(buf, n)
                }
            } finally {
                try { input.close() } catch (_: Throwable) {}
                // TUN 意外死亡:清理状态,避免通知/运行态残留
                try { fd.close() } catch (_: Throwable) {}
                if (this@PacketCaptureService.tunFd === fd) this@PacketCaptureService.tunFd = null
                CaptureController.finalizeCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // 每秒刷新 UI 统计
        scope.launch {
            while (isActive) {
                CaptureController.tick()
                delay(1000)
            }
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        CaptureController.finalizeCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Packet capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, PacketCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VicinityProbe · packet capture")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        captureJob?.cancel()
        try { tunFd?.close() } catch (_: Throwable) {}
        super.onDestroy()
    }
}

/** 抓包流表条目 */
data class FlowEntry(
    val proto: String,
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int,
    val sentBytes: Long,
    val recvBytes: Long,
    val packets: Long,
    val state: String,
    val lastSeenMs: Long,
)

/** 抓包统计状态 */
data class CaptureStats(
    val running: Boolean = false,
    val durationMs: Long = 0,
    val totalPackets: Long = 0,
    val totalBytes: Long = 0,
    val tcpPackets: Long = 0,
    val udpPackets: Long = 0,
    val icmpPackets: Long = 0,
    val otherPackets: Long = 0,
    val tlsVersions: Map<String, Long> = emptyMap(),
    val tlsJa3: Map<String, Long> = emptyMap(),        // JA3 风格 TLS 指纹计数
    val quicPackets: Long = 0,
    val protocols: Map<String, Long> = emptyMap(),     // 应用层协议识别
    val topIps: List<Pair<String, Long>> = emptyList(),
    val flows: List<FlowEntry> = emptyList(),
    val topDomains: List<Pair<String, Long>> = emptyList(),
    val httpRequests: List<String> = emptyList(),
    val pcapFile: String? = null,
)

/** 抓包控制器:单例,解析与统计 */
object CaptureController {
    private val _stats = MutableStateFlow(CaptureStats())
    val stats: StateFlow<CaptureStats> = _stats.asStateFlow()

    private val flows = LinkedHashMap<String, FlowEntry>()
    private val domains = HashMap<String, Long>()
    private val httpReqs = ArrayDeque<String>()
    private var pcapOut: BufferedOutputStream? = null
    private var pcapFile: File? = null
    private var pcapWritten: Long = 0
    private var startedAt = 0L
    private val packets = AtomicLong(0)
    private val bytes = AtomicLong(0)
    private val tcpPkts = AtomicLong(0)
    private val udpPkts = AtomicLong(0)
    private val icmpPkts = AtomicLong(0)
    private val otherPkts = AtomicLong(0)
    private val httpTime = AtomicLong(0)
    private val quicPkts = AtomicLong(0)
    private val tlsVersions = HashMap<String, Long>()
    private val tlsJa3 = HashMap<String, Long>()
    private val protocols = HashMap<String, Long>()
    private val ipBytes = HashMap<String, Long>()

    /** 常见应用协议端口映射 */
    private val PROTO_PORTS = mapOf(
        53 to "DNS", 67 to "DHCP", 68 to "DHCP", 123 to "NTP", 1900 to "SSDP", 5353 to "mDNS",
        443 to "HTTPS", 80 to "HTTP", 8080 to "HTTP", 8443 to "HTTPS", 22 to "SSH",
        25 to "SMTP", 110 to "POP3", 143 to "IMAP", 389 to "LDAP", 3389 to "RDP",
    )

    /** pcap 文件上限 256MB,防磁盘耗尽 */
    private const val PCAP_MAX_BYTES = 256L * 1024 * 1024
    /** SNI/域名提取正则(预编译,防每包编译) */
    private val SNI_REGEX = Regex("[A-Za-z0-9]([A-Za-z0-9-.]{3,62}[A-Za-z0-9])")
    /** HTTP 方法起始字节嗅探(预计算,防每包分配) */
    private val HTTP_FIRST_BYTES = "GPHDOPT".toByteArray()

    fun reset() {
        synchronized(this) {
            flows.clear(); domains.clear(); httpReqs.clear()
            packets.set(0); bytes.set(0); tcpPkts.set(0); udpPkts.set(0); icmpPkts.set(0); otherPkts.set(0)
            quicPkts.set(0); tlsVersions.clear(); ipBytes.clear()
            tlsJa3.clear(); protocols.clear()
            startedAt = System.currentTimeMillis()
            // 打开 pcap 文件
            try {
                pcapOut?.close()
            } catch (_: Throwable) {}
            val ctx = AppContextHolder.context ?: return
            val dir = File(ctx.filesDir, "captures").apply { mkdirs() }
            pcapFile = File(dir, "capture_${System.currentTimeMillis()}.pcap")
            pcapOut = FileOutputStream(pcapFile).buffered()
            pcapWritten = 0
            // pcap global header (little-endian)
            val hdr = ByteArray(24)
            putIntLE(hdr, 0, 0xa1b2c3d4.toInt())
            putShortLE(hdr, 4, 2)          // version major
            putShortLE(hdr, 6, 4)          // version minor
            putIntLE(hdr, 8, 0)            // thiszone
            putIntLE(hdr, 12, 0)           // sigfigs
            putIntLE(hdr, 16, 65535)       // snaplen
            putIntLE(hdr, 20, 101)         // LINKTYPE_RAW (IP packets)
            pcapOut?.write(hdr)
        }
    }

    fun onPacket(buf: ByteArray, n: Int) {
        packets.incrementAndGet()
        bytes.addAndGet(n.toLong())
        synchronized(this) {
            try {
                pcapOut?.let { out ->
                    // 超过上限后停止写入,保留已抓内容
                    if (pcapWritten + n < PCAP_MAX_BYTES) {
                        val now = System.currentTimeMillis()
                        val hdr = ByteArray(16)
                        putIntLE(hdr, 0, (now / 1000).toInt())
                        putIntLE(hdr, 4, ((now % 1000) * 1000).toInt())
                        putIntLE(hdr, 8, n)
                        putIntLE(hdr, 12, n)
                        out.write(hdr)
                        out.write(buf, 0, n)
                        pcapWritten += n
                    }
                }
            } catch (_: Throwable) {}
            parse(buf, n)
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        // 加锁快照,避免与 onPacket 并发修改
        val snapshot = synchronized(this) {
            flows.entries.removeIf { now - it.value.lastSeenMs > 45_000 }
            Triple(
                flows.values.sortedByDescending { it.sentBytes + it.recvBytes }.take(30),
                domains.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value.toLong() },
                httpReqs.toList().takeLast(20).reversed(),
            )
        }
        try { pcapOut?.flush() } catch (_: Throwable) {}
        _stats.value = CaptureStats(
            running = true,
            durationMs = now - startedAt,
            totalPackets = packets.get(),
            totalBytes = bytes.get(),
            tcpPackets = tcpPkts.get(),
            udpPackets = udpPkts.get(),
            icmpPackets = icmpPkts.get(),
            otherPackets = otherPkts.get(),
            tlsVersions = synchronized(tlsVersions) { tlsVersions.toMap() },
            tlsJa3 = synchronized(tlsJa3) { tlsJa3.toMap() },
            quicPackets = quicPkts.get(),
            protocols = synchronized(protocols) { protocols.toMap() },
            topIps = synchronized(ipBytes) { ipBytes.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value } },
            flows = snapshot.first,
            topDomains = snapshot.second,
            httpRequests = snapshot.third,
            pcapFile = pcapFile?.name,
        )
    }

    fun finalizeCapture() {
        synchronized(this) {
            try { pcapOut?.close() } catch (_: Throwable) {}
            pcapOut = null
            _stats.value = _stats.value.copy(running = false, pcapFile = pcapFile?.name)
        }
    }

    fun pcapPath(): File? = pcapFile

    private fun parse(buf: ByteArray, n: Int) {
        if (n < 20) return
        val version = (buf[0].toInt() shr 4) and 0xF
        when (version) {
            4 -> parseIpv4(buf, n)
            6 -> parseIpv6(buf, n)
        }
    }

    private fun parseIpv4(buf: ByteArray, n: Int) {
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (n < ihl + 8) return
        val proto = buf[9].toInt() and 0xFF
        val src = ipv4(buf, 12)
        val dst = ipv4(buf, 16)
        when (proto) {
            6 -> parseTcp(buf, ihl, n, "TCP", src, dst)
            17 -> parseUdp(buf, ihl, n, "UDP", src, dst)
            1 -> { icmpPkts.incrementAndGet() }
            else -> otherPkts.incrementAndGet()
        }
    }

    private fun parseIpv6(buf: ByteArray, n: Int) {
        val next = buf[6].toInt() and 0xFF
        val src = ipv6(buf, 8)
        val dst = ipv6(buf, 24)
        when (next) {
            6 -> parseTcp(buf, 40, n, "TCP", src, dst)
            17 -> parseUdp(buf, 40, n, "UDP", src, dst)
            58 -> { icmpPkts.incrementAndGet() }
            else -> otherPkts.incrementAndGet()
        }
    }

    private fun parseTcp(buf: ByteArray, offset: Int, n: Int, proto: String, src: String, dst: String) {
        if (n < offset + 20) return
        tcpPkts.incrementAndGet()
        val srcPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        val dstPort = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        val flags = buf[offset + 13].toInt() and 0xFF
        val dataOff = ((buf[offset + 12].toInt() and 0xF0) shr 4) * 4
        val payloadLen = n - offset - dataOff

        val isClient = src == PacketCaptureService.VIRTUAL_IP
        val clientIp = if (isClient) src else dst
        val clientPort = if (isClient) srcPort else dstPort
        val serverIp = if (isClient) dst else src
        val serverPort = if (isClient) dstPort else srcPort
        val key = "$proto|$clientIp:$clientPort|$serverIp:$serverPort"

        // 应用层协议识别(按服务端端口)
        synchronized(protocols) {
            val pname = PROTO_PORTS[serverPort]
            if (pname != null) protocols[pname] = (protocols[pname] ?: 0) + 1
            else {
                protocols["TCP:other"] = (protocols["TCP:other"] ?: 0) + 1
            }
        }

        val now = System.currentTimeMillis()
        val existing = flows[key]
        val sent = (existing?.sentBytes ?: 0) + if (isClient) payloadLen else 0
        val recv = (existing?.recvBytes ?: 0) + if (!isClient) payloadLen else 0
        val pkts = (existing?.packets ?: 0) + 1
        synchronized(ipBytes) {
            ipBytes[serverIp] = (ipBytes[serverIp] ?: 0) + payloadLen
        }
        val state = when {
            flags and 0x02 != 0 -> "SYN"
            flags and 0x04 != 0 -> "RST"
            flags and 0x01 != 0 -> "FIN"
            else -> "EST"
        }
        flows[key] = FlowEntry(proto, clientIp, clientPort, serverIp, serverPort, sent, recv, pkts, state, now)

        // 应用层解析(先做廉价字节嗅探,命中才拷贝,减少每包 GC 分配)
        if (payloadLen > 0 && offset + dataOff < n) {
            val payloadStart = offset + dataOff
            val first = buf[payloadStart].toInt() and 0xFF
            when (serverPort) {
                80, 8080 -> {
                    if (isClient && first.toByte() in HTTP_FIRST_BYTES) {
                        val payload = ByteArray(payloadLen)
                        System.arraycopy(buf, payloadStart, payload, 0, payloadLen)
                        parseHttp(payload, clientIp)
                    }
                }
                443, 8443 -> {
                    if (isClient && first == 0x16 && payloadLen >= 44 && payloadLen <= 4096) {
                        val payload = ByteArray(payloadLen)
                        System.arraycopy(buf, payloadStart, payload, 0, payloadLen)
                        parseSni(payload)
                    }
                }
            }
        }
    }

    private fun parseUdp(buf: ByteArray, offset: Int, n: Int, proto: String, src: String, dst: String) {
        if (n < offset + 8) return
        udpPkts.incrementAndGet()
        val srcPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        val dstPort = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        val payloadLen = n - offset - 8
        // 应用层协议识别
        synchronized(protocols) {
            val pname = PROTO_PORTS[dstPort] ?: "UDP:other"
            protocols[pname] = (protocols[pname] ?: 0) + 1
        }
        // QUIC 检测:UDP 443,长头首字节 0xC0-0xFF
        if (payloadLen > 0 && dstPort == 443) {
            val first = buf[offset + 8].toInt() and 0xFF
            if (first and 0xC0 == 0xC0) quicPkts.incrementAndGet()
        }
        // DNS 查询:客户端 → 53 端口请求方向(QR 位=0)
        if (payloadLen >= 12 && dstPort == 53 && (buf[offset + 8].toInt() and 0x80) == 0) {
            val payload = ByteArray(payloadLen)
            System.arraycopy(buf, offset + 8, payload, 0, payloadLen)
            parseDnsRequest(payload)
        }
        // 流表(UDP 会话)
        val isClient = src == PacketCaptureService.VIRTUAL_IP
        val key = "$proto|${if (isClient) src else dst}:${if (isClient) srcPort else dstPort}|${if (isClient) dst else src}:${if (isClient) dstPort else srcPort}"
        val now = System.currentTimeMillis()
        val existing = flows[key]
        flows[key] = FlowEntry(
            proto, if (isClient) src else dst, if (isClient) srcPort else dstPort,
            if (isClient) dst else src, if (isClient) dstPort else srcPort,
            (existing?.sentBytes ?: 0) + if (isClient) payloadLen else 0,
            (existing?.recvBytes ?: 0) + if (!isClient) payloadLen else 0,
            (existing?.packets ?: 0) + 1, "UDP", now,
        )
        synchronized(ipBytes) {
            val remote = if (isClient) dst else src
            ipBytes[remote] = (ipBytes[remote] ?: 0) + payloadLen
        }
    }

    private fun parseHttp(payload: ByteArray, clientIp: String) {
        val text = String(payload, 0, minOf(payload.size, 512), Charsets.ISO_8859_1)
        if (text.isEmpty() || text[0] !in "GPHDOPT".toCharArray()) return
        val line = text.lineSequence().firstOrNull()?.trim() ?: return
        if (!line.contains("HTTP/")) return
        val host = text.lineSequence().firstOrNull { it.startsWith("Host:", true) }?.substringAfter(':')?.trim()
        val entry = if (host != null) "$line  Host: $host" else line
        httpReqs.addLast(entry.take(120))
        while (httpReqs.size > 200) httpReqs.removeFirst()
    }

    private fun parseSni(payload: ByteArray) {
        // TLS ClientHello 最小结构扫描: 0x16 0x03 ... handshake type 1
        if (payload.size < 44 || (payload[0].toInt() and 0xFF) != 0x16) return
        // 记录客户端宣告的 TLS 版本 (record version, bytes 1-2)
        if (payload.size >= 3) {
            val v = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
            val name = when (v) {
                0x0303 -> "TLS1.2"
                0x0304 -> "TLS1.3"
                0x0301 -> "TLS1.0"
                0x0302 -> "TLS1.1"
                else -> "0x${String.format("%04X", v)}"
            }
            synchronized(tlsVersions) { tlsVersions[name] = (tlsVersions[name] ?: 0) + 1 }
        }
        // JA3 风格指纹:版本 + 密码套件 + 扩展类型(仅首个完整 ClientHello)
        if (payload.size >= 44 && payload[5].toInt() and 0xFF == 1) {
            com.vicinityprobe.analysis.TlsClientHello.ja3Fingerprint(payload)?.let { fp ->
                synchronized(tlsJa3) { tlsJa3[fp] = (tlsJa3[fp] ?: 0) + 1 }
            }
            // 精确 SNI 解析(优于正则扫描)
            com.vicinityprobe.analysis.TlsClientHello.sni(payload)?.let { name ->
                if (name.length in 4..253) {
                    domains[name] = (domains[name] ?: 0) + 1
                }
                return
            }
        }
        // 简单方式:扫描 payload 中可打印 ASCII 域名段(长度 4..63, 字母数字-.)
        val text = String(payload, Charsets.ISO_8859_1)
        val m = SNI_REGEX.find(text, 40)
        val name = m?.value
        if (name != null && name.contains('.') && !name.contains(" ") && name.length > 3 && name.length < 64) {
            domains[name] = (domains[name] ?: 0) + 1
        }
    }

    private fun parseDnsRequest(payload: ByteArray) {
        if (payload.size < 12) return
        val qdcount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdcount == 0) return
        var pos = 12
        val sb = StringBuilder()
        var jumps = 0
        while (pos < payload.size && jumps < 32) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) { jumps++; pos += 2; break }
            pos++
            if (pos + len > payload.size) break
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) sb.append((payload[pos + i].toInt() and 0xFF).toChar())
            pos += len
        }
        val qname = sb.toString()
        if (qname.length in 4..253 && qname.contains('.')) {
            domains[qname] = (domains[qname] ?: 0) + 1
        }
    }

    private fun ipv4(buf: ByteArray, off: Int): String =
        "${buf[off].toInt() and 0xFF}.${buf[off + 1].toInt() and 0xFF}.${buf[off + 2].toInt() and 0xFF}.${buf[off + 3].toInt() and 0xFF}"

    private fun ipv6(buf: ByteArray, off: Int): String {
        val parts = (0 until 8).joinToString(":") { i ->
            ((buf[off + i * 2].toInt() and 0xFF) shl 8 or (buf[off + i * 2 + 1].toInt() and 0xFF)).toString(16)
        }
        return parts
    }

    private fun putIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putShortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}

/** 供 CaptureController 获取 Context */
object AppContextHolder {
    @Volatile var context: android.content.Context? = null
}
