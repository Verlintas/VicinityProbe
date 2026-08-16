/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

/**
 * TLS ClientHello 解析(纯字节操作,无 Android 依赖,可单元测试):
 * - [ja3Fingerprint] JA3 风格指纹(版本,密码套件,扩展)
 * - [sniFromClientHello] SNI 提取(安全解析扩展,不依赖正则扫描)
 */
object TlsClientHello {

    /**
     * JA3 风格指纹:
     * 输入 TLS 记录层 ClientHello 完整帧(以 0x16 开头),
     * 输出 "legacy_version,ciphers,extensions" 三元组;解析失败返回 null。
     */
    fun ja3Fingerprint(b: ByteArray): String? {
        if (b.size < 44 || (b[0].toInt() and 0xFF) != 0x16) return null
        if (b[5].toInt() and 0xFF != 1) return null          // 非 ClientHello
        var pos = 9
        if (pos + 34 > b.size) return null
        val ver = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 34                                             // random(32)
        if (pos >= b.size) return null
        val sidLen = b[pos].toInt() and 0xFF
        pos += 1 + sidLen
        if (pos + 2 > b.size) return null
        val csLen = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 2
        if (pos + csLen > b.size || csLen % 2 != 0) return null
        val ciphers = ArrayList<Int>(csLen / 2)
        for (i in 0 until csLen step 2) {
            ciphers.add(((b[pos + i].toInt() and 0xFF) shl 8) or (b[pos + i + 1].toInt() and 0xFF))
        }
        pos += csLen
        if (pos >= b.size) return null
        val compLen = b[pos].toInt() and 0xFF
        pos += 1 + compLen
        val extList = ArrayList<Int>()
        if (pos + 2 <= b.size) {
            val extLen = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            pos += 2
            val end = minOf(pos + extLen, b.size)
            while (pos + 4 <= end) {
                extList.add(((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF))
                val elen = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
                pos += 4 + elen
            }
        }
        if (ciphers.isEmpty()) return null
        val hex = { v: Int -> String.format("%04X", v) }
        val ciphersStr = ciphers.joinToString("-") { hex(it) }
        val extsStr = extList.joinToString("-") { hex(it) }
        return "${hex(ver)},$ciphersStr" + if (extsStr.isEmpty()) "" else ",$extsStr"
    }

    /** 解析 ClientHello 中的 SNI 扩展;失败返回 null */
    fun sni(b: ByteArray): String? {
        if (b.size < 44 || (b[0].toInt() and 0xFF) != 0x16 || (b[5].toInt() and 0xFF) != 1) return null
        var pos = 9
        if (pos + 34 > b.size) return null
        pos += 34
        if (pos >= b.size) return null
        pos += 1 + (b[pos].toInt() and 0xFF)                 // session_id
        if (pos + 2 > b.size) return null
        val csLen = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 2 + csLen
        if (pos >= b.size) return null
        pos += 1 + (b[pos].toInt() and 0xFF)                 // compression
        if (pos + 2 > b.size) return null
        val extLen = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        pos += 2
        val end = minOf(pos + extLen, b.size)
        while (pos + 4 <= end) {
            val type = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            val len = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            if (type == 0x0000 && pos + len <= end) {        // server_name
                // ServerNameList: 2 字节总长 + 条目(1 字节类型 + 2 字节长度 + 名称)
                if (pos + 5 > pos + len) return null
                val nameLen = ((b[pos + 3].toInt() and 0xFF) shl 8) or (b[pos + 4].toInt() and 0xFF)
                val nameStart = pos + 5
                if (nameStart + nameLen > pos + len) return null
                return String(b, nameStart, nameLen, Charsets.US_ASCII).let { if (it.isNotEmpty()) it else null }
            }
            pos += len
        }
        return null
    }
}
