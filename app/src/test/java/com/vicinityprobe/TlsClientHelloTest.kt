/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.analysis.TlsClientHello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class TlsClientHelloTest {

    /** 构造一个完整 ClientHello 帧 */
    private fun buildClientHello(
        legacyVersion: Int = 0x0303,
        ciphers: IntArray = intArrayOf(0x1301, 0x1302, 0x1303),
        sni: String? = "example.com",
        extraExtensions: List<Int> = listOf(0x002b, 0x0017),
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((legacyVersion shr 8) and 0xFF); body.write(legacyVersion and 0xFF)
        body.write(ByteArray(32))                       // random
        body.write(0)                                   // session_id len 0
        body.write(ciphers.size * 2 shr 8); body.write(ciphers.size * 2 and 0xFF)
        ciphers.forEach { body.write((it shr 8) and 0xFF); body.write(it and 0xFF) }
        body.write(1); body.write(0)                    // compression: null
        // extensions
        val extBytes = ByteArrayOutputStream()
        if (sni != null) {
            val name = sni.toByteArray(Charsets.US_ASCII)
            val sniBody = ByteArrayOutputStream()
            sniBody.write(0)                            // name type host_name
            sniBody.write(name.size shr 8); sniBody.write(name.size and 0xFF)
            sniBody.write(name)
            val entry = sniBody.toByteArray()
            extBytes.write(0x00); extBytes.write(0x00)  // server_name
            // 扩展长度 = 2 字节列表长度前缀 + 条目本体(RFC 6066)
            extBytes.write((2 + entry.size) shr 8); extBytes.write((2 + entry.size) and 0xFF)
            extBytes.write(entry.size shr 8); extBytes.write(entry.size and 0xFF)  // ServerNameList 长度
            extBytes.write(entry)
        }
        extraExtensions.forEach { t ->
            extBytes.write(t shr 8); extBytes.write(t and 0xFF)
            extBytes.write(0); extBytes.write(0)        // 空扩展
        }
        val ext = extBytes.toByteArray()
        body.write(ext.size shr 8); body.write(ext.size and 0xFF)
        body.write(ext)
        val hs = body.toByteArray()
        // 组帧
        val out = ByteArrayOutputStream()
        out.write(0x16)                                  // handshake record
        out.write(0x03); out.write(0x01)                 // record version
        out.write(hs.size + 4 shr 8); out.write(hs.size + 4 and 0xFF)
        out.write(0x01)                                  // ClientHello
        out.write(hs.size shr 16); out.write(hs.size shr 8); out.write(hs.size and 0xFF)
        out.write(hs)
        return out.toByteArray()
    }

    @Test
    fun `JA3指纹_标准ClientHello_格式正确`() {
        val ch = buildClientHello()
        val fp = TlsClientHello.ja3Fingerprint(ch)
        assertNotNull(fp)
        assertEquals("0303,1301-1302-1303,0000-002B-0017", fp)
    }

    @Test
    fun `JA3指纹_无扩展_仅含密码套件`() {
        val ch = buildClientHello(ciphers = intArrayOf(0x1301), sni = null, extraExtensions = emptyList())
        val fp = TlsClientHello.ja3Fingerprint(ch)
        assertNotNull(fp)
        assertEquals("0303,1301,", fp)
    }

    @Test
    fun `JA3指纹_非ClientHello_返回null`() {
        val bad = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x01, 0x02)
        assertNull(TlsClientHello.ja3Fingerprint(bad))
    }

    @Test
    fun `JA3指纹_截断包_返回null`() {
        val ch = buildClientHello()
        assertNull(TlsClientHello.ja3Fingerprint(ch.copyOf(20)))
    }

    @Test
    fun `SNI_提取域名`() {
        val ch = buildClientHello(sni = "api.github.com")
        assertEquals("api.github.com", TlsClientHello.sni(ch))
    }

    @Test
    fun `SNI_无扩展_返回null`() {
        val ch = buildClientHello(sni = null, extraExtensions = listOf(0x002b))
        assertNull(TlsClientHello.sni(ch))
    }
}
