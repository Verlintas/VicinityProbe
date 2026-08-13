package com.vicinityprobe.probe

import android.content.Context
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 主动网络探测工具集(SECURITY 分类)。
 * 目标主机默认取默认网关,可在应用内配置。
 * 注意:此类探测在部分国家受网络安全法规约束,应用中已标注 ⚠️。
 */

/** 目标主机配置 */
object ScanTargetConfig {
    private const val PREF = "scan_config"
    const val KEY_TARGET = "scan_target"

    fun target(ctx: Context): String? {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TARGET, null)
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setTarget(ctx: Context, host: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_TARGET, host.trim()).apply()
    }
}

/** 网络辅助:网关/IP/子网信息 */
object NetInfo {
    fun gatewayAndPrefix(ctx: Context): Pair<String, Int>? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: return null
        val prefix = lp.routes.firstOrNull { it.isDefaultRoute }?.destination?.prefixLength ?: 24
        return gw to prefix
    }

    fun localIp(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.mapNotNull { it.address }.filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
    }
}

/** OUI 厂商库:常见厂商 MAC 前缀 */
object OuiDb {
    private val table = mapOf(
        "F8:E4:E3" to "Xiaomi", "F0:27:2D" to "Xiaomi", "F4:CF:E2" to "Xiaomi", "64:64:4A" to "Xiaomi", "34:CE:00" to "Xiaomi",
        "A4:9B:CD" to "Huawei", "F4:96:34" to "Huawei", "E8:6D:52" to "Huawei", "2C:AB:00" to "Huawei", "8C:34:FD" to "Huawei",
        "08:52:1D" to "Realme", "E4:B9:35" to "Realme", "F4:0B:93" to "OPPO", "60:89:B1" to "OPPO", "20:6B:E7" to "OPPO",
        "D8:6C:63" to "vivo", "90:20:C2" to "vivo", "1C:46:44" to "vivo",
        "3C:97:0E" to "Samsung", "8C:77:12" to "Samsung", "F8:BF:09" to "Samsung", "EC:22:80" to "Samsung",
        "C0:EE:FB" to "OnePlus", "30:3A:64" to "OnePlus",
        "D4:5A:5D" to "Honor", "00:E0:FC" to "Honor",
        "E4:02:9B" to "Honor", "70:20:84" to "Honor",
        "F4:4C:7F" to "TP-Link", "50:C7:BF" to "TP-Link", "D8:07:B6" to "TP-Link", "F8:8F:CA" to "TP-Link", "C8:3A:35" to "TP-Link",
        "A0:63:91" to "Xiaomi", "B4:75:0E" to "TP-Link",
        "8C:DE:F9" to "Asus", "24:4B:FE" to "Asus", "78:02:F8" to "Asus",
        "F4:F2:6D" to "D-Link", "C0:06:C3" to "D-Link", "28:10:7B" to "D-Link",
        "C8:3A:35" to "TP-Link", "9C:D3:6D" to "Netgear", "A0:40:A0" to "Netgear", "20:4E:7F" to "Netgear",
        "A4:2B:B0" to "Tenda", "C8:3A:6B" to "Tenda",
        "5C:02:14" to "Mercury", "00:1B:9E" to "Huawei", "00:0C:29" to "VMware", "00:50:56" to "VMware",
        "00:15:5D" to "Microsoft(Hyper-V)", "02:42" to "Docker", "02:AC" to "QEMU",
        "AC:37:43" to "Samsung", "D8:32:14" to "Apple", "A8:6B:AD" to "Apple", "3C:22:FB" to "Apple",
        "B8:87:1B" to "Apple", "F0:18:98" to "Apple", "AC:BC:32" to "Apple", "34:12:98" to "Apple",
        "7C:B3:7B" to "Google", "F8:5C:F9" to "Google", "F4:F5:D8" to "Google", "38:A4:ED" to "Google",
        "A4:C1:38" to "Google", "94:EB:2C" to "Google",
        "58:CB:52" to "Amazon", "A0:02:DC" to "Amazon", "FC:65:DE" to "Amazon",
        "34:12:98" to "Apple", "40:B4:CD" to "Samsung", "A4:77:33" to "Samsung", "2C:FD:A1" to "Samsung",
        "48:0F:CF" to "Xiaomi", "10:68:3F" to "Xiaomi", "8C:BE:BE" to "Xiaomi",
        "60:31:97" to "Realme", "A2:5C:1C" to "Realme", "30:6F:2B" to "OPPO", "F8:39:3D" to "OPPO",
        "DC:33:0B" to "TP-Link", "C4:6E:1F" to "TP-Link", "34:29:12" to "TP-Link", "E8:48:B8" to "TP-Link",
        "C8:34:8E" to "D-Link", "00:22:B0" to "D-Link",
        "50:64:2B" to "ZTE", "80:80:0D" to "ZTE",
        "48:7A:DA" to "Xiaomi", "04:21:3C" to "Honor", "E0:94:67" to "Honor",
        "00:1A:A9" to "Qualcomm", "9C:A6:20" to "Intel", "00:1B:21" to "Intel", "00:23:24" to "Intel",
        "DC:53:60" to "MediaTek", "00:0C:E7" to "MediaTek",
    )

    fun vendor(mac: String): String? {
        val upper = mac.replace("-", ":").uppercase()
        for (len in listOf(8, 5)) {
            if (upper.length >= len) {
                table[upper.substring(0, len)]?.let { return it }
            }
        }
        return null
    }
}

/** 局域网主机发现:读 ARP 表 + 对子网内主机做 TCP 探测触发 ARP 更新 */
class ArpHostDiscoverySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_arp")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val pair = NetInfo.gatewayAndPrefix(ctx)
        val gw = pair?.first ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No gateway")
        val prefix = pair.second
        val base = gw.substringBeforeLast('.')
        val range = if (prefix >= 24) 2..60 else 2..40

        // 并行对子网主机做 TCP 探测,触发内核 ARP 解析
        withContext(Dispatchers.IO) {
            range.chunked(8).forEach { chunk ->
                chunk.forEach { last ->
                    launch {
                        val ip = "$base.$last"
                        for (port in intArrayOf(443, 80, 8080, 22, 53)) {
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(ip, port), 150)
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                delay(50)
            }
        }
        delay(500)
        val hosts = LinkedHashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6 && parts[2] == "0x2") {
                    val ip = parts[0]
                    val mac = parts[3]
                    val vendor = OuiDb.vendor(mac) ?: "unknown"
                    hosts[ip] = "$mac ($vendor)"
                }
            }
        } catch (_: Throwable) {}
        if (hosts.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No hosts found")
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["gateway"] = gw
        attrs["host_count"] = hosts.size.toString()
        attrs["detail"] = hosts.entries.joinToString("\n") { "${it.key} | ${it.value}" }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = hosts.size))
    }
}

/** 常用端口表 + 服务名 */
object PortServices {
    val ports = mapOf(
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
        80 to "http", 110 to "pop3", 135 to "msrpc", 139 to "netbios-ssn", 143 to "imap",
        443 to "https", 445 to "microsoft-ds", 993 to "imaps", 995 to "pop3s", 1433 to "mssql",
        1521 to "oracle", 2049 to "nfs", 2375 to "docker", 3000 to "http-alt", 3306 to "mysql",
        3389 to "rdp", 5432 to "postgresql", 5601 to "kibana", 5900 to "vnc", 6379 to "redis",
        7001 to "weblogic", 8000 to "http-alt", 8080 to "http-proxy", 8081 to "http-alt",
        8443 to "https-alt", 8888 to "http-alt", 9000 to "php-fpm", 9090 to "http-alt",
        9200 to "elasticsearch", 9300 to "elasticsearch", 11211 to "memcached", 1883 to "mqtt",
        8883 to "mqtts", 27017 to "mongodb", 5000 to "upnp/alt-http", 49152 to "dcom",
    )

    val orderedPorts = ports.keys.sorted()
}

/** 端口扫描:TCP connect 扫描 + 服务识别 */
class PortScanSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_portscan")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = ScanTargetConfig.target(ctx) ?: NetInfo.gatewayAndPrefix(ctx)?.first
            ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val open = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        withContext(Dispatchers.IO) {
            PortServices.orderedPorts.chunked(20).forEach { chunk ->
                chunk.forEach { port ->
                    launch {
                        try {
                            val start = System.nanoTime()
                            Socket().use { s ->
                                s.connect(InetSocketAddress(target, port), 500)
                                open[port] = (System.nanoTime() - start) / 1_000_000
                            }
                        } catch (_: Throwable) {}
                    }
                }
                delay(100)
            }
        }
        if (open.isEmpty()) {
            return okMeasurement(spec, mapOf("target" to target, "open_ports" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        attrs["open_ports"] = open.size.toString()
        val detail = open.entries.sortedBy { it.key }.joinToString("\n") { (p, ms) ->
            "${p}/tcp ${PortServices.ports[p] ?: "unknown"} (${ms}ms)"
        }
        attrs["detail"] = detail
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = open.size))
    }
}

/** HTTP/TLS 指纹:Server 头 + 证书分析 + 技术栈推断 */
class HttpFingerprintSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_http_fingerprint")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = ScanTargetConfig.target(ctx) ?: NetInfo.gatewayAndPrefix(ctx)?.first
            ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val attrs = LinkedHashMap<String, String>()
        val schemePorts = listOf(80 to false, 443 to true, 8080 to false, 8443 to true)

        for ((port, tls) in schemePorts) {
            try {
                val headers = LinkedHashMap<String, String>()
                val statusLine = withContext(Dispatchers.IO) {
                    httpProbe(target, port, tls, headers)
                }
                if (statusLine != null) {
                    attrs["http_${port}_status"] = statusLine
                    headers.entries.filter { it.key in setOf("Server", "X-Powered-By", "Location", "Via", "X-AspNet-Version", "X-Generator") }
                        .forEach { (k, v) -> attrs["http_${port}_$k"] = v }
                    headers["Server"]?.let { attrs["stack"] = inferStack(it) }
                    if (tls) {
                        val cert = withContext(Dispatchers.IO) { tlsCertInfo(target, port) }
                        cert?.let { attrs["tls_${port}_cert"] = it }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (attrs.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No HTTP service on target")
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = attrs.size))
    }

    private fun httpProbe(host: String, port: Int, tls: Boolean, out: MutableMap<String, String>): String? {
        val io: java.io.DataOutputStream
        val input: java.io.InputStream
        if (tls) {
            val ssl = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port) as javax.net.ssl.SSLSocket
            ssl.soTimeout = 2500
            ssl.startHandshake()
            io = java.io.DataOutputStream(ssl.outputStream)
            input = ssl.inputStream
        } else {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 1500)
            socket.soTimeout = 2500
            io = java.io.DataOutputStream(socket.getOutputStream())
            input = socket.getInputStream()
        }
        io.writeBytes("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: VicinityProbe/0.4\r\nConnection: close\r\n\r\n")
        io.flush()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
        val status = reader.readLine()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            line = reader.readLine()
        }
        return status
    }

    private fun tlsCertInfo(host: String, port: Int): String? {
        val ssl = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port) as javax.net.ssl.SSLSocket
        ssl.soTimeout = 2500
        ssl.startHandshake()
        val certs = ssl.session.peerCertificates
        val chain = certs.mapIndexed { i, c ->
            val x = c as java.security.cert.X509Certificate
            val cn = x.subjectX500Principal.name
            val issuer = x.issuerX500Principal.name
            val alg = x.sigAlgName
            val selfSigned = cn == issuer
            val expired = x.notAfter.before(java.util.Date())
            val weak = alg.contains("SHA1") || alg.contains("MD5")
            "chain[$i] CN=$cn issuer=$issuer sig=$alg${if (selfSigned) " SELF-SIGNED" else ""}${if (expired) " EXPIRED" else ""}${if (weak) " WEAK-SIG" else ""} validTo=${x.notAfter}"
        }
        try { ssl.close() } catch (_: Throwable) {}
        return chain.joinToString("\n")
    }

    private fun inferStack(server: String): String = when {
        server.contains("nginx", true) -> "nginx"
        server.contains("apache", true) -> "Apache"
        server.contains("iis", true) -> "IIS"
        server.contains("tomcat", true) -> "Tomcat"
        server.contains("node", true) -> "Node.js"
        server.contains("gunicorn", true) -> "Gunicorn"
        server.contains("openresty", true) -> "OpenResty"
        server.contains("caddy", true) -> "Caddy"
        server.contains("lighttpd", true) -> "Lighttpd"
        server.contains("cloudflare", true) -> "Cloudflare"
        else -> "unknown"
    }
}

/** DNS 解析测试:公共 DNS + 常见域名解析延迟 */
class DnsProbeSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_dns")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val domains = listOf("www.baidu.com", "github.com", "www.google.com", "www.cloudflare.com", "dns.google")
        val latencies = ArrayList<Long>()
        val attrs = LinkedHashMap<String, String>()
        for (domain in domains) {
            try {
                val start = System.nanoTime()
                val addrs = withContext(Dispatchers.IO) { InetAddress.getAllByName(domain) }
                val ms = (System.nanoTime() - start) / 1_000_000
                latencies.add(ms)
                attrs["dns_$domain"] = "${addrs.first().hostAddress} (${ms}ms)"
            } catch (_: Throwable) {
                attrs["dns_$domain"] = "NXDOMAIN/timeout"
            }
        }
        // 本机 DNS 服务器
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.take(3)
            if (dns != null && dns.isNotEmpty()) attrs["local_dns"] = dns.joinToString(",")
        } catch (_: Throwable) {}
        // 公共 DNS 连通性(TCP 53)
        for (dns in listOf("223.5.5.5", "8.8.8.8", "1.1.1.1")) {
            try {
                val start = System.nanoTime()
                withContext(Dispatchers.IO) {
                    Socket().use { s -> s.connect(InetSocketAddress(dns, 53), 800) }
                }
                attrs["dns_${dns}_reachable"] = "${(System.nanoTime() - start) / 1_000_000}ms"
            } catch (_: Throwable) {
                attrs["dns_${dns}_reachable"] = "unreachable"
            }
        }
        if (latencies.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "DNS resolution failed")
        }
        val stats = ChannelStats.compute(latencies.map { it.toFloat() }.toFloatArray(), "ms")
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("latency" to stats),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = latencies.size),
        )
    }
}

/** SSDP/UPnP 设备发现:UDP 组播 */
class SsdpDiscoverySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_ssdp")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val responses = java.util.Collections.synchronizedList(ArrayList<String>())
        val socket = java.net.DatagramSocket()
        socket.soTimeout = 2000
        val request = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: ssdp:all\r\n\r\n"
        val packet = java.net.DatagramPacket(request.toByteArray(), request.length, InetAddress.getByName("239.255.255.250"), 1900)
        try {
            repeat(3) {
                socket.send(packet)
                kotlinx.coroutines.delay(100)
            }
            val buf = ByteArray(4096)
            val recv = java.net.DatagramPacket(buf, buf.size)
            val end = SystemClockCompat.elapsedRealtime() + 2500
            while (SystemClockCompat.elapsedRealtime() < end) {
                try {
                    socket.receive(recv)
                    val data = String(recv.data, 0, recv.length)
                    val location = data.lineSequence().firstOrNull { it.startsWith("LOCATION", true) }?.substringAfter(':')?.trim()
                    val server = data.lineSequence().firstOrNull { it.startsWith("SERVER", true) }?.substringAfter(':')?.trim()
                    val st = data.lineSequence().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim()
                    responses.add("${recv.address.hostAddress} | ${st ?: "?"} | ${location ?: "?"} | ${server ?: "?"}")
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}
        val list = synchronized(responses) { responses.distinct().toList() }
        if (list.isEmpty()) {
            return okMeasurement(spec, mapOf("devices" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["devices"] = list.size.toString()
        attrs["detail"] = list.joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = list.size))
    }
}

/** 网关连通性测试:TCP ping(非 ICMP,无 root 可用) */
class PingSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_ping")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = ScanTargetConfig.target(ctx) ?: NetInfo.gatewayAndPrefix(ctx)?.first
            ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val latencies = ArrayList<Long>()
        val ports = listOf(443, 80, 22, 53)
        for (round in 1..4) {
            for (port in ports) {
                try {
                    val start = System.nanoTime()
                    withContext(Dispatchers.IO) {
                        Socket().use { s -> s.connect(InetSocketAddress(target, port), 800) }
                    }
                    latencies.add((System.nanoTime() - start) / 1_000_000)
                } catch (_: Throwable) {}
            }
        }
        if (latencies.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "Target unreachable")
        }
        val stats = ChannelStats.compute(latencies.map { it.toFloat() }.toFloatArray(), "ms")
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        attrs["method"] = bil("TCP 探测(非 ICMP,无需 root)", "TCP probe (not ICMP, no root)")
        attrs["rtt_min_ms"] = String.format("%.1f", stats.min)
        attrs["rtt_avg_ms"] = String.format("%.1f", stats.mean)
        attrs["rtt_max_ms"] = String.format("%.1f", stats.max)
        attrs["packet_loss"] = "${(1 - latencies.size / 16.0) * 100}%"
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("rtt" to stats),
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = latencies.size),
        )
    }
}
