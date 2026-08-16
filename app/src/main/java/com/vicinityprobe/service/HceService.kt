/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * HCE 卡模拟器:把手机模拟成一张 ISO 7816-4 卡片。
 * 用于测试读卡器/门禁系统的 APDU 交互(仅限自己拥有的读卡器)。
 *
 * 协议(简单):
 * - SELECT AID (F0010203040506) → 90 00,状态字 OK
 * - READ BINARY (00 B0 xx 00) → 返回 16 字节模拟数据(HEX ASCII 文本)
 * - 其他命令 → 6D 00 (指令不支持)
 */
class HceService : HostApduService() {

    companion object {
        const val AID = "F0010203040506"
        const val CATEGORY = "other"

        /** 状态:本服务是否收到过 SELECT(供 UI 显示) */
        @Volatile var selected = false
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.isEmpty()) return SW_UNKNOWN
        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        return when {
            // SELECT by AID
            cla == 0x00 && ins == 0xA4 -> {
                selected = true
                byteArrayOf(0x90.toByte(), 0x00)
            }
            // READ BINARY:返回 16 字节模拟数据(ASCII "VICINITY-PROBE-X")
            cla == 0x00 && ins == 0xB0 -> {
                byteArrayOf(
                    'V'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(),
                    'N'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'Y'.code.toByte(),
                    '-'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 'O'.code.toByte(),
                    'B'.code.toByte(), 'E'.code.toByte(), '-'.code.toByte(), 'X'.code.toByte(),
                )
            }
            // GET UID 风格命令(0xCA):返回虚拟 UID
            cla == 0x00 && ins == 0xCA -> {
                byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte())
            }
            else -> SW_UNKNOWN
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = false
    }

    private val SW_UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)
}
