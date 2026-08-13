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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

private fun targetOf(ctx: Context): String? =
    ScanTargetConfig.target(ctx) ?: NetInfo.gatewayAndPrefix(ctx)?.first

/** 服务 Banner 抓取:对开放端口读服务横幅,识别服务版本 */
class BannerGrabSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_banner")!!

    private val targets = listOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 80 to "HTTP",
        443 to "HTTPS", 3306 to "MySQL", 5432 to "PostgreSQL", 6379 to "Redis", 8080 to "HTTP-alt",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val attrs = LinkedHashMap<String, String>()
        val grabbed = ArrayList<String>()
        for ((port, name) in targets) {
            val banner = withContext(Dispatchers.IO) { grabBanner(target, port) }
            if (banner != null) {
                grabbed.add("$port/$name: $banner")
                attrs["banner_$port"] = banner
            }
        }
        if (grabbed.isEmpty()) {
            return okMeasurement(spec, mapOf("target" to target, "banners" to "0"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        attrs["target"] = target
        attrs["banners"] = grabbed.size.toString()
        attrs["detail"] = grabbed.joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = grabbed.size))
    }

    private fun grabBanner(host: String, port: Int): String? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1500)
            s.soTimeout = 2000
            val probe = when (port) {
                80, 8080 -> "HEAD / HTTP/1.0\r\n\r\n"
                443 -> null
                else -> null
            }
            val out = s.getOutputStream()
            if (probe != null) {
                out.write(probe.toByteArray())
                out.flush()
            }
            val buf = ByteArray(512)
            val n = s.inputStream.read(buf)
            s.close()
            if (n <= 0) null else String(buf, 0, n).trim().lines().firstOrNull { it.isNotBlank() }?.take(160)
        } catch (_: Throwable) { null }
    }
}

/** HTTP 方法探测:OPTIONS/TRACE/PUT/DELETE 允许性 */
class HttpMethodsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_http_methods")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val methods = listOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "TRACE", "PATCH")
        val attrs = LinkedHashMap<String, String>()
        val allowed = ArrayList<String>()
        for (method in methods) {
            val result = withContext(Dispatchers.IO) { probeMethod(target, 80, method) }
            if (result != null) {
                allowed.add(method)
                attrs["method_$method"] = result
            }
        }
        attrs["target"] = target
        attrs["allowed_methods"] = allowed.joinToString(",")
        val risky = allowed.filter { it in setOf("PUT", "DELETE", "TRACE") }
        attrs["risky_allowed"] = risky.joinToString(",").ifEmpty { "none" }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = allowed.size))
    }

    private fun probeMethod(host: String, port: Int, method: String): String? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1200)
            s.soTimeout = 1500
            s.getOutputStream().write("$method / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val line = s.getInputStream().bufferedReader().readLine()
            s.close()
            line
        } catch (_: Throwable) { null }
    }
}

/** HTTP 安全头分析:检测缺失的安全响应头 */
class HttpSecuritySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_http_security")!!

    private val wanted = listOf(
        "Strict-Transport-Security", "X-Frame-Options", "X-Content-Type-Options",
        "Content-Security-Policy", "X-XSS-Protection", "Referrer-Policy", "Permissions-Policy",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val headers = LinkedHashMap<String, String>()
        withContext(Dispatchers.IO) { fetchHeaders(target, 80, headers) }
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        val present = wanted.filter { headers.containsKey(it) }
        val missing = wanted.filter { !headers.containsKey(it) }
        attrs["present_headers"] = present.joinToString(",").ifEmpty { "none" }
        attrs["missing_headers"] = missing.joinToString(",")
        headers.forEach { (k, v) -> if (k in wanted) attrs["hdr_$k"] = v.take(100) }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = headers.size))
    }

    private fun fetchHeaders(host: String, port: Int, out: MutableMap<String, String>) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1200)
            s.soTimeout = 1500
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val reader = s.inputStream.bufferedReader()
            reader.readLine()
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                line = reader.readLine()
            }
            s.close()
        } catch (_: Throwable) {}
    }
}

/** TLS 版本探测:尝试 1.2/1.3 及旧版本握手 */
class TlsVersionsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_tls_versions")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val attrs = LinkedHashMap<String, String>()
        val versions = listOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")
        for (v in versions) {
            val ok = withContext(Dispatchers.IO) { tryTls(target, 443, v) }
            attrs["tls_$v"] = ok.toString()
        }
        val supported = versions.filter { attrs["tls_$it"] == "true" }
        attrs["supported"] = supported.joinToString(",")
        if (supported.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No TLS service on 443")
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = supported.size))
    }

    private fun tryTls(host: String, port: Int, version: String): Boolean {
        return try {
            val ctx = javax.net.ssl.SSLContext.getInstance(version)
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }), null)
            val s = ctx.socketFactory.createSocket(host, port)
            s.soTimeout = 2000
            (s as javax.net.ssl.SSLSocket).startHandshake()
            val proto = s.session.protocol
            s.close()
            proto.startsWith(version.replace("TLSv", "TLS"))
        } catch (_: Throwable) { false }
    }
}

/** NTP 时间偏移:对公共 NTP 服务器测时钟偏移 */
class NtpSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_ntp")!!

    private val servers = listOf(
        "203.107.6.88" to "Aliyun", "ntp.ntsc.ac.cn" to "NTSC", "216.239.35.0" to "Google",
        "pool.ntp.org" to "Pool", "120.25.115.20" to "Tencent",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        val offsets = ArrayList<Double>()
        for ((host, name) in servers) {
            val offset = withContext(Dispatchers.IO) { ntpOffset(host) }
            if (offset != null) {
                offsets.add(offset)
                attrs["ntp_$name"] = String.format("%+.0f ms", offset)
            } else {
                attrs["ntp_$name"] = "unreachable"
            }
        }
        if (offsets.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No NTP server reachable")
        }
        val stats = ChannelStats.compute(offsets.map { it.toFloat() }.toFloatArray(), "ms")
        attrs["offset_mean_ms"] = String.format("%+.0f", stats.mean)
        attrs["offset_abs_max_ms"] = String.format("%.0f", kotlin.math.abs(stats.max).coerceAtLeast(kotlin.math.abs(stats.min)))
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("offset" to stats),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = offsets.size),
        )
    }

    private fun ntpOffset(host: String): Double? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 2000
            val request = ByteArray(48)
            request[0] = 0x1B.toByte() // NTP v4, client mode
            val t1 = System.currentTimeMillis()
            val send = DatagramPacket(request, 48, InetAddress.getByName(host), 123)
            socket.send(send)
            val resp = ByteArray(48)
            socket.receive(DatagramPacket(resp, 48))
            val t4 = System.currentTimeMillis()
            socket.close()
            if (resp.size < 48) return null
            // transmit timestamp (bytes 40-43): seconds since 1900
            val seconds = ((resp[40].toLong() and 0xFF) shl 24) or ((resp[41].toLong() and 0xFF) shl 16) or
                ((resp[42].toLong() and 0xFF) shl 8) or (resp[43].toLong() and 0xFF)
            val t2 = (seconds - 2208988800L) * 1000.0
            // 简化偏移:远端服务器发送时刻与本地当前时刻比较
            val roundTrip = t4 - t1
            t2 - (t4 - roundTrip / 2)
        } catch (_: Throwable) { null }
    }
}

/** 系统代理配置 */
class ProxyConfigSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_proxy")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            val httpProxy = lp?.httpProxy
            if (httpProxy != null) {
                attrs["proxy_host"] = httpProxy.host ?: "?"
                attrs["proxy_port"] = httpProxy.port.toString()
                attrs["proxy_exclusion_list"] = httpProxy.exclusionList.joinToString(",")
            } else {
                attrs["proxy"] = "none"
            }
        } catch (_: Throwable) {}
        try {
            System.getProperties().entries.filter { it.key.toString().contains("proxy", true) }.forEach {
                attrs["java_${it.key}"] = it.value.toString()
            }
        } catch (_: Throwable) {}
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 全子网存活扫描:网段内全部主机 Web 端口探测 */
class SubnetScanSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_subnet_scan")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val pair = NetInfo.gatewayAndPrefix(ctx)
        val gw = pair?.first ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No gateway")
        val prefix = pair.second
        // 蜂窝等非标准前缀回退 /24 推断
        val effectivePrefix = if (prefix >= 24 && prefix <= 28) prefix else 24
        val base = gw.substringBeforeLast('.')
        val alive = java.util.concurrent.ConcurrentHashMap<String, String>()
        withContext(Dispatchers.IO) {
            (1..254).chunked(32).forEach { chunk ->
                chunk.forEach { last ->
                    launch {
                        val ip = "$base.$last"
                        for (port in intArrayOf(80, 443, 8080, 22, 445)) {
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(ip, port), 250)
                                    alive[ip] = alive[ip]?.let { "$it,$port" } ?: "$port"
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                delay(300)
            }
        }
        if (alive.isEmpty()) {
            return okMeasurement(spec, mapOf("alive_hosts" to "0", "gateway" to gw),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["gateway"] = gw
        attrs["alive_hosts"] = alive.size.toString()
        attrs["detail"] = alive.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} open=[${it.value}]" }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = alive.size))
    }
}

/** MQTT Broker 探测:发送 CONNECT 读 CONNACK */
class MqttProbeSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_mqtt")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val result = withContext(Dispatchers.IO) { mqttConnect(target, 1883) }
        if (result == null) {
            return okMeasurement(spec, mapOf("target" to target, "broker" to "none"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        return okMeasurement(spec, mapOf("target" to target, "broker" to result),
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }

    private fun mqttConnect(host: String, port: Int): String? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1500)
            s.soTimeout = 2000
            // MQTT 3.1.1 CONNECT: fixed header + remaining length + variable header + payload
            val proto = "MQTT".toByteArray()
            val payload = byteArrayOf(0, 4) + proto + byteArrayOf(4, 0) // protocol level 4, flags
            val remLen = 10 + payload.size
            val pkt = ByteArray(2 + remLen)
            pkt[0] = 0x10
            pkt[1] = remLen.toByte()
            System.arraycopy(payload, 0, pkt, 2, payload.size)
            s.getOutputStream().write(pkt)
            s.getOutputStream().flush()
            val resp = ByteArray(4)
            val n = s.inputStream.read(resp)
            s.close()
            if (n >= 2 && resp[0].toInt() == 0x20) {
                val code = resp[2].toInt()
                val accepted = code == 0
                "present (CONNACK=${if (accepted) "accepted" else "refused:$code"})"
            } else null
        } catch (_: Throwable) { null }
    }
}

/** Web 路径探测:常见路径状态码 */
class HttpPathSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_http_paths")!!

    private val paths = listOf(
        "/", "/robots.txt", "/admin", "/login", "/api", "/status", "/health",
        "/wp-admin", "/phpinfo.php", "/server-status", "/config.json", "/.git/HEAD", "/api/v1",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val attrs = LinkedHashMap<String, String>()
        val hits = ArrayList<String>()
        for (path in paths) {
            val status = withContext(Dispatchers.IO) { httpStatus(target, 80, path) }
            if (status != null) {
                hits.add("$path -> $status")
                attrs["path_${path.replace('/', '_').replace('.', '_')}"] = status
            }
        }
        attrs["target"] = target
        attrs["responded_paths"] = hits.size.toString()
        if (hits.isNotEmpty()) attrs["detail"] = hits.joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = hits.size))
    }

    private fun httpStatus(host: String, port: Int, path: String): String? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 1200)
            s.soTimeout = 1200
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val line = s.inputStream.bufferedReader().readLine()
            s.close()
            line
        } catch (_: Throwable) { null }
    }
}

/** 并发连接测试:目标并发连接能力 */
class TcpConcurrencySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_tcp_concurrency")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val success = java.util.concurrent.atomic.AtomicInteger(0)
        val latencies = ArrayList<Long>()
        withContext(Dispatchers.IO) {
            (1..16).forEach {
                launch {
                    try {
                        val start = System.nanoTime()
                        Socket().use { s -> s.connect(InetSocketAddress(target, 443), 1500) }
                        synchronized(latencies) { latencies.add((System.nanoTime() - start) / 1_000_000) }
                        success.incrementAndGet()
                    } catch (_: Throwable) {}
                }
            }
        }
        val stats = if (latencies.isNotEmpty()) ChannelStats.compute(latencies.map { it.toFloat() }.toFloatArray(), "ms") else null
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        attrs["attempts"] = "16"
        attrs["success"] = success.toString()
        attrs["success_rate"] = "${success.get() * 100 / 16}%"
        stats?.let {
            attrs["rtt_avg_ms"] = String.format("%.1f", it.mean)
            attrs["rtt_max_ms"] = String.format("%.1f", it.max)
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = success.get()))
    }
}
