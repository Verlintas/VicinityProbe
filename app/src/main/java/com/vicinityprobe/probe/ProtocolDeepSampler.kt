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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 协议深探:在 Banner 之上做真实协议交互。
 * - TLS:协商密码套件/协议版本/ALPN
 * - SSH:版本 banner
 * - SMB2:协商握手,解析 dialect 与安全信息
 */

/** TLS 密码套件探测:抓取协商的 cipher suite / 协议 / ALPN */
class TlsCipherProbeSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_tls_cipher")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val attrs = LinkedHashMap<String, String>()
        for (port in intArrayOf(443, 8443, 8888)) {
            val info = withContext(Dispatchers.IO) { probeTls(target, port) }
            if (info != null) {
                attrs["tls_$port"] = info
            }
        }
        if (attrs.isEmpty()) return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No TLS service")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = attrs.size))
    }

    private fun probeTls(host: String, port: Int): String? {
        return try {
            val ssl = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port) as javax.net.ssl.SSLSocket
            ssl.soTimeout = 2500
            val params = ssl.sslParameters
            params.applicationProtocols = arrayOf("h2", "http/1.1")
            ssl.sslParameters = params
            ssl.startHandshake()
            val proto = ssl.session.protocol
            val cipher = ssl.session.cipherSuite
            val alpn = ssl.applicationProtocol
            ssl.close()
            "proto=$proto cipher=$cipher alpn=$alpn"
        } catch (_: Throwable) { null }
    }
}

/** SSH 版本探测:读取服务端 banner */
class SshVersionSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_ssh_ver")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val banner = withContext(Dispatchers.IO) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(target, 22), 2000)
                s.soTimeout = 2500
                val line = s.inputStream.bufferedReader().readLine()
                s.close()
                line
            } catch (_: Throwable) { null }
        }
        if (banner == null || !banner.startsWith("SSH-")) {
            return okMeasurement(spec, mapOf("target" to target, "ssh" to "none"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        // 解析版本与厂商
        val parts = banner.split("-")
        val vendor = parts.getOrNull(2) ?: "?"
        val version = parts.getOrNull(1) ?: "?"
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        attrs["banner"] = banner.take(100)
        attrs["vendor"] = vendor
        attrs["version"] = version
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** SMB2 协商探测:手写 SMB2 NEGOTIATE,解析 dialect */
class SmbProbeSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("net_smb")!!

    private val dialects = mapOf(
        0x0202 to "SMB 2.0.2", 0x0210 to "SMB 2.1", 0x0300 to "SMB 3.0",
        0x0302 to "SMB 3.0.2", 0x0311 to "SMB 3.1.1",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val target = targetOf(ctx) ?: return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "No target")
        val result = withContext(Dispatchers.IO) { negotiateSmb2(target, 445) }
        if (result == null) {
            return okMeasurement(spec, mapOf("target" to target, "smb" to "none"),
                quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 0))
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["target"] = target
        attrs["smb"] = "present"
        attrs["detail"] = result
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }

    private fun negotiateSmb2(host: String, port: Int): String? {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = 3000
            val out = s.getOutputStream()
            val input = s.getInputStream()
            // SMB2 NEGOTIATE 请求
            val header = ByteArray(64)
            header[0] = 0xFE.toByte(); header[1] = 'S'.code.toByte(); header[2] = 'M'.code.toByte(); header[3] = 'B'.code.toByte()
            // 64..68: StructureSize=64
            header[4] = 64; header[5] = 0
            // 68..70: CreditCharge=0, 70..72: ChannelSequence/Reserved, 72..76: Status, 76: Command=0(NEGOTIATE)
            header[12] = 0
            // 78..80: CreditRequest=1
            header[18] = 1
            // 80..88: Flags/NextCommand/MessageId
            // 88..96: ProcessId/TreeId
            // 96..104: SessionId
            // 104..112: Signature
            // body: StructureSize=36(0x24), DialectCount=4, SecurityMode=0x01, Reserved, Capabilities=0x0000007f,
            //       ClientGuid(16B), ClientStartTime(8B), Dialects: 0x0202,0x0210,0x0300,0x0302,0x0311
            val body = ByteArray(36 + 10)
            body[0] = 0x24; body[1] = 0
            body[2] = 5; body[3] = 0                 // DialectCount=5
            body[4] = 0x01; body[5] = 0              // SecurityMode=SigningEnabled
            body[6] = 0; body[7] = 0
            // Capabilities (8B) @8
            putInt(body, 8, 0x0000007f)
            putInt(body, 12, 0)
            // ClientGuid @16
            val guid = java.util.UUID.randomUUID()
            val gb = ByteArray(16)
            java.nio.ByteBuffer.wrap(gb).putLong(guid.mostSignificantBits).putLong(guid.leastSignificantBits)
            System.arraycopy(gb, 0, body, 16, 16)
            // ClientStartTime @32 (8B) = 0
            // Dialects @36
            val ds = intArrayOf(0x0202, 0x0210, 0x0300, 0x0302, 0x0311)
            ds.forEachIndexed { i, d -> body[36 + i * 2] = (d and 0xFF).toByte(); body[37 + i * 2] = ((d shr 8) and 0xFF).toByte() }

            val pkt = header + body
            // NetBIOS session header: 4 bytes (length)
            val nb = byteArrayOf(0, 0, 0, (pkt.size).toByte())
            out.write(nb)
            out.write(pkt)
            out.flush()

            // 读 NetBIOS + SMB2 header + body
            val lenBuf = ByteArray(4)
            if (input.read(lenBuf) != 4) { s.close(); return null }
            val msgLen = ((lenBuf[2].toInt() and 0xFF) shl 8) or (lenBuf[3].toInt() and 0xFF)
            if (msgLen < 68) { s.close(); return null }
            val resp = ByteArray(msgLen)
            var off = 0
            while (off < msgLen) {
                val n = input.read(resp, off, msgLen - off)
                if (n < 0) break
                off += n
            }
            s.close()
            if (off < 68) return null
            // 验证 SMB2 magic
            if (resp[0] != 0xFE.toByte() || resp[1] != 'S'.code.toByte()) return null
            val status = ((resp[16].toInt() and 0xFF) shl 24) or ((resp[17].toInt() and 0xFF) shl 16) or
                ((resp[18].toInt() and 0xFF) shl 8) or (resp[19].toInt() and 0xFF)
            if (status != 0) return "error status=0x${String.format("%08X", status)}"
            val bodyOff = 68
            if (off < bodyOff + 2) return null
            val structSize = (resp[bodyOff].toInt() and 0xFF) or ((resp[bodyOff + 1].toInt() and 0xFF) shl 8)
            if (structSize < 2 || off < bodyOff + structSize) return null
            val dialectCount = (resp[bodyOff + 2].toInt() and 0xFF) or ((resp[bodyOff + 3].toInt() and 0xFF) shl 8)
            var dialect = "unknown"
            if (dialectCount > 0 && off >= bodyOff + 4 + 2) {
                val d = (resp[bodyOff + 4].toInt() and 0xFF) or ((resp[bodyOff + 5].toInt() and 0xFF) shl 8)
                dialect = dialects[d] ?: "0x${String.format("%04X", d)}"
            }
            val mode = resp.getOrElse(bodyOff + 6) { 0 }.toInt()
            val signing = if (mode and 0x02 != 0) "signing-required" else if (mode and 0x01 != 0) "signing-enabled" else "no-signing"
            "SMB2 negotiate ok | dialect=$dialect | security=$signing | dialects_offered=$dialectCount"
        } catch (_: Throwable) { null }
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}

private fun targetOf(ctx: Context): String? =
    ScanTargetConfig.target(ctx) ?: NetInfo.gatewayAndPrefix(ctx)?.first
