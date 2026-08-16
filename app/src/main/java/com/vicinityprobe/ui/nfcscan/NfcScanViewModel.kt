/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.nfcscan

import android.app.Application
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcV
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 卡片分析结果 */
data class NfcCardReport(
    val uid: String = "?",
    val technologies: List<String> = emptyList(),
    val cardType: String = "?",
    val atqa: String = "?",
    val sak: String = "?",
    val historicalBytes: String = "?",
    val maxTransceive: Int = 0,
    // Mifare Classic
    val mifareSectors: Int = 0,
    val defaultKeyUsed: Boolean = false,
    val keyBUnlocked: Boolean = false,
    val unlockedSectors: Int = 0,
    val readSector0: String = "?",
    val dumpLines: List<String> = emptyList(),   // "sN.bb: hex" 完整转储
    // Ultralight/NTAG
    val ulPages: List<String> = emptyList(),
    // NDEF
    val ndefPresent: Boolean = false,
    val ndefRecords: List<String> = emptyList(),
    val ndefWritable: Boolean = false,
    val ndefSize: Int = 0,
    // 写入
    val writeResult: String? = null,
    // 安全评估
    val riskLevel: String = "INFO",   // INFO / LOW / HIGH
    val findings: List<String> = emptyList(),
    val scanning: Boolean = false,
    val lastError: String? = null,
)

class NfcScanViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NfcCardReport())
    val state: StateFlow<NfcCardReport> = _state

    /** Mifare Classic 常见默认密钥(公开已知) */
    private val defaultKeys = arrayOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()),
        byteArrayOf(0x1A.toByte(), 0x2B.toByte(), 0x3C.toByte(), 0x4D.toByte(), 0x5E.toByte(), 0x6F.toByte()),
        byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0x4D.toByte(), 0x3A.toByte(), 0x99.toByte(), 0xC3.toByte(), 0x51.toByte(), 0xDD.toByte()),
        byteArrayOf(0x1A.toByte(), 0x98.toByte(), 0x2C.toByte(), 0x7E.toByte(), 0x45.toByte(), 0x9A.toByte()),
        byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
    )

    private var lastTag: Tag? = null

    /** 处理新发现的标签(Reader Mode 回调) */
    fun onTagDiscovered(tag: Tag) {
        lastTag = tag
        analyze(tag, null)
    }

    /** 处理新发现的标签(Intent 方式) */
    fun onTagDiscovered(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val ndef = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()
        analyze(tag, ndef)
    }

    private val analyzeMutex = kotlinx.coroutines.sync.Mutex()

    private fun analyze(tag: Tag, ndefMessages: List<NdefMessage>?) {
        viewModelScope.launch(Dispatchers.IO) {
            analyzeMutex.withLock {
            _state.value = NfcCardReport(scanning = true)
            val report = try {
                analyzeTag(tag, ndefMessages)
            } catch (e: Exception) {
                _state.value.copy(lastError = e.message ?: "error")
            }
            _state.value = report.copy(scanning = false)
            }
        }
    }

    private fun analyzeTag(tag: Tag, ndefMessages: List<NdefMessage>?): NfcCardReport {
        val techs = tag.techList.toList()
        val uid = tag.id.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        val findings = ArrayList<String>()
        var defaultKeyUsed = false
        var unlockedSectors = 0
        var readSector0 = "?"
        var mifareSectors = 0
        val ulPages = ArrayList<String>()

        val r = NfcCardReport(
            uid = uid,
            technologies = techs,
            scanning = true,
        )

        // NfcA 协议信息
        var atqa = "?"; var sak = "?"; var historical = "?"; var maxTrans = 0
        runCatching {
            NfcA.get(tag)?.let { nfcA ->
                nfcA.connect()
                atqa = nfcA.atqa.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
                sak = String.format("%02X", nfcA.sak.toInt() and 0xFF)
                maxTrans = nfcA.maxTransceiveLength
                nfcA.close()            }
        }

        // Mifare Classic:双密钥(KeyA/KeyB)默认密钥检测 + 完整扇区转储
        var keyBUnlocked = false
        val dumpLines = ArrayList<String>()
        runCatching {
            MifareClassic.get(tag)?.let { mfc ->
                mfc.connect()
                mifareSectors = mfc.sectorCount
                for (sector in 0 until minOf(mfc.sectorCount, 40)) {
                    // KeyA 尝试
                    var authedA = false
                    for (key in defaultKeys) {
                        try {
                            if (mfc.authenticateSectorWithKeyA(sector, key)) { authedA = true; break }
                        } catch (_: Throwable) {}
                    }
                    var authedB = false
                    if (!authedA) {
                        for (key in defaultKeys) {
                            try {
                                if (mfc.authenticateSectorWithKeyB(sector, key)) { authedB = true; keyBUnlocked = true; break }
                            } catch (_: Throwable) {}
                        }
                    }
                    val unlocked = authedA || authedB
                    if (unlocked) {
                        defaultKeyUsed = true
                        unlockedSectors++
                    }
                    if (!unlocked && sector == 0) {
                        findings.add("扇区 0 无法用默认密钥认证(密钥已修改)|Sector 0 not authenticated with default keys (key changed)")
                    }
                    // 已解锁扇区:读取全部数据块
                    if (unlocked) {
                        try {
                            val blocks = mfc.getBlockCountInSector(sector)
                            val firstBlock = mfc.sectorToBlock(sector)
                            for (b in 0 until blocks) {
                                val data = mfc.readBlock(firstBlock + b)
                                dumpLines.add("s${sector}.${b}: " + data.joinToString("") { String.format("%02X", it.toInt() and 0xFF) })
                            }
                        } catch (_: Throwable) {}
                    }
                }
                // 块 0(UID + BCC + SAK + ATQA 制造信息)
                if (unlockedSectors > 0) {
                    try {
                        val block = mfc.readBlock(0)
                        readSector0 = block.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
                    } catch (_: Throwable) {}
                }
                mfc.close()
            }
        }

        // Ultralight / NTAG
        runCatching {
            MifareUltralight.get(tag)?.let { ul ->
                ul.connect()
                for (page in 0 until minOf(ul.maxTransceiveLength / 4, 32)) {
                    runCatching {
                        val d = ul.readPages(page)
                        ulPages.add("p$page: " + d.joinToString("") { String.format("%02X", it.toInt() and 0xFF) })
                    }
                }
                ul.close()
            }
        }
        // NfcV / NfcB / NfcF 存在性
        val hasV = techs.any { it == NfcV::class.java.name }
        val hasB = techs.any { it == NfcB::class.java.name }
        val hasF = techs.any { it == NfcF::class.java.name }

        // 卡片类型识别(基于 SAK + 技术)
        val cardType = when {
            techs.any { it == MifareClassic::class.java.name } -> when (sak) {
                "08", "09" -> "Mifare Classic 1K"
                "18", "19" -> "Mifare Classic 4K"
                else -> "Mifare Classic"
            }
            techs.any { it == MifareUltralight::class.java.name } -> "Mifare Ultralight / NTAG"
            hasV -> "ISO 15693 (NFC-V)"
            hasB -> "ISO 14443B"
            hasF -> "FeliCa (ISO 18092)"
            techs.any { it == IsoDep::class.java.name } -> "ISO 14443-4 (DESFire/兼容)"
            else -> "未知协议|Unknown"
        }

        // NDEF 解析
        var ndefPresent = false
        var ndefWritable = false
        var ndefSize = 0
        val ndefRecords = ArrayList<String>()
        ndefMessages?.firstOrNull()?.let { msg ->
            ndefPresent = true
            msg.records.forEach { rec -> ndefRecords.add(parseNdefRecord(rec)) }
        }
        if (ndefMessages.isNullOrEmpty()) {
            runCatching {
                Ndef.get(tag)?.let { nd ->
                    nd.connect()
                    nd.ndefMessage?.records?.forEach { rec -> ndefRecords.add(parseNdefRecord(rec)) }
                    ndefPresent = nd.ndefMessage != null
                    ndefWritable = nd.isWritable
                    ndefSize = nd.maxSize
                    nd.close()
                }
            }
            runCatching {
                NdefFormatable.get(tag)?.let { _ -> ndefWritable = true }
            }
        }

        // 安全评估
        if (defaultKeyUsed) {
            findings.add(0, "使用默认密钥的扇区: $unlockedSectors 个(可被克隆/重放风险)|Default-key sectors: $unlockedSectors (cloneable)")
        }
        if (ndefWritable) findings.add("NDEF 区可写(可被篡改)|NDEF writable (tamperable)")
        val risk = when {
            defaultKeyUsed && unlockedSectors >= 5 -> "HIGH"
            keyBUnlocked -> "HIGH"
            defaultKeyUsed || ndefWritable -> "LOW"
            else -> "INFO"
        }

        return r.copy(
            atqa = atqa, sak = sak, historicalBytes = historical, maxTransceive = maxTrans,
            mifareSectors = mifareSectors, defaultKeyUsed = defaultKeyUsed,
            keyBUnlocked = keyBUnlocked,
            unlockedSectors = unlockedSectors, readSector0 = readSector0,
            dumpLines = dumpLines,
            ulPages = ulPages, ndefPresent = ndefPresent, ndefRecords = ndefRecords,
            ndefWritable = ndefWritable, ndefSize = ndefSize,
            cardType = cardType, riskLevel = risk, findings = findings,
        )
    }

    /** NDEF 记录解析:文本/URI/智能海报 */
    private fun parseNdefRecord(rec: NdefRecord): String {
        return try {
            when (rec.tnf) {
                NdefRecord.TNF_WELL_KNOWN -> when {
                    rec.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                        val payload = rec.payload
                        val langLen = payload[0].toInt() and 0x3F
                        val text = String(payload, langLen + 1, payload.size - langLen - 1)
                        "TEXT: $text"
                    }
                    rec.type.contentEquals(NdefRecord.RTD_URI) -> {
                        val payload = rec.payload
                        val prefix = when (payload[0].toInt() and 0xFF) {
                            0x01 -> "http://www."; 0x02 -> "https://www."; 0x03 -> "http://"
                            0x04 -> "https://"; 0x05 -> "tel:"; 0x06 -> "mailto:"
                            else -> ""
                        }
                        "URI: $prefix${String(payload, 1, payload.size - 1)}"
                    }
                    rec.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> "SMART_POSTER: ${rec.payload.size} bytes"
                    else -> "WELL_KNOWN[${String(rec.type)}]: ${rec.payload.size} bytes"
                }
                NdefRecord.TNF_MIME_MEDIA -> "MIME[${String(rec.type)}]: ${rec.payload.size} bytes"
                NdefRecord.TNF_ABSOLUTE_URI -> "ABSOLUTE_URI: ${String(rec.payload)}"
                NdefRecord.TNF_EXTERNAL_TYPE -> "EXTERNAL[${String(rec.type)}]: ${rec.payload.size} bytes"
                else -> "tnf=${rec.tnf} type=${String(rec.type)} bytes=${rec.payload.size}"
            }
        } catch (_: Throwable) {
            "记录解析失败|unparsable record"
        }
    }

    /** 向标签写入文本 NDEF(仅限自己的测试标签) */
    fun writeNdefText(text: String) {
        val tag = lastTag ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val msg = NdefMessage(
                    arrayOf(NdefRecord.createTextRecord("zh", text)),
                )
                val ndef = Ndef.get(tag)
                if (ndef != null) {
                    ndef.connect()
                    ndef.writeNdefMessage(msg)
                    ndef.close()
                    "OK"
                } else {
                    val fmt = NdefFormatable.get(tag)
                    if (fmt != null) {
                        fmt.connect()
                        fmt.format(msg)
                        fmt.close()
                        "OK (formatted)"
                    } else "N/A"
                }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            _state.value = _state.value.copy(writeResult = result)
        }
    }

    /** 完整转储导出(hex + ASCII) */
    fun exportDump(): String {
        val r = _state.value
        val sb = StringBuilder()
        sb.appendLine("VicinityProbe NFC dump")
        sb.appendLine("UID: ${r.uid}")
        sb.appendLine("Type: ${r.cardType}")
        sb.appendLine("ATQA: ${r.atqa}  SAK: ${r.sak}")
        sb.appendLine("Unlocked sectors (default keys): ${r.unlockedSectors}/${r.mifareSectors}" + if (r.keyBUnlocked) " (KeyB!)" else "")
        sb.appendLine("---")
        r.dumpLines.forEach { sb.appendLine(it) }
        if (r.ulPages.isNotEmpty()) {
            sb.appendLine("--- pages ---")
            r.ulPages.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    fun reset() {
        _state.value = NfcCardReport()
    }
}
