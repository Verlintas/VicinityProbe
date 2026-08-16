/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.speedtest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.util.Random

/** 测速阶段 */
enum class SpeedPhase { IDLE, LATENCY, DOWNLOAD, UPLOAD, DONE }

data class SpeedResult(
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val phaseProgress: Float = 0f,          // 0..1
    val latencyMs: Long = 0,
    val jitterMs: Long = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val note: String? = null,
)

class SpeedTestViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SpeedResult())
    val state: StateFlow<SpeedResult> = _state

    private var job: Job? = null

    /** 测试服务器(Cloudflare 全球边缘) */
    private val downloadUrl = "https://speed.cloudflare.com/__down?bytes=20000000"
    private val uploadUrl = "https://speed.cloudflare.com/__up"

    fun start() {
        if (job?.isActive == true) return
        _state.value = SpeedResult(phase = SpeedPhase.LATENCY)
        job = viewModelScope.launch(Dispatchers.IO) {
            // 1) 延迟与抖动:5 次 TCP 连接 1.1.1.1:443
            val latencies = (0 until 5).map { _ ->
                try {
                    val t0 = System.nanoTime()
                    Socket().use { s -> s.connect(InetSocketAddress("1.1.1.1", 443), 3000) }
                    (System.nanoTime() - t0) / 1_000_000
                } catch (_: Throwable) { -1L }
            }
            val ok = latencies.filter { it >= 0 }
            val latency = if (ok.isEmpty()) 0L else ok.average().toLong()
            val jitter = if (ok.size >= 2) ok.zipWithNext().map { (a, b) -> kotlin.math.abs(a - b) }.average().toLong() else 0L
            _state.value = _state.value.copy(phase = SpeedPhase.DOWNLOAD, latencyMs = latency, jitterMs = jitter)

            // 2) 下载测速:拉取 20MB,5 秒上限
            val dl = measureTransfer(downloadUrl, upload = false, active = { job?.isActive == true })
            _state.value = _state.value.copy(phase = SpeedPhase.UPLOAD, downloadMbps = dl)

            // 3) 上传测速:POST 8MB 随机数据
            val ul = measureTransfer(uploadUrl, upload = true, active = { job?.isActive == true })
            _state.value = _state.value.copy(phase = SpeedPhase.DONE, uploadMbps = ul)
        }
    }

    /** 测量传输速率(Mbps),最多 maxSec 秒 */
    private fun measureTransfer(urlStr: String, upload: Boolean, maxSec: Int = 6, active: () -> Boolean = { true }): Double {
        return try {
            val conn = URL(urlStr).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            var totalBytes = 0L
            val startedAt = System.nanoTime()
            val deadline = startedAt + maxSec * 1_000_000_000L
            if (upload) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                val chunk = ByteArray(256 * 1024)
                Random().nextBytes(chunk)
                val out = BufferedOutputStream(conn.outputStream)
                while (active() && System.nanoTime() < deadline && totalBytes < 16L * 1024 * 1024) {
                    out.write(chunk)
                    totalBytes += chunk.size
                    _state.value = _state.value.copy(phaseProgress = (totalBytes.toFloat() / (16L * 1024 * 1024)))
                }
                out.flush()
                try { conn.inputStream.use { it.readBytes() } } catch (_: Throwable) {}
            } else {
                conn.connect()
                val `in` = BufferedInputStream(conn.inputStream)
                val buf = ByteArray(64 * 1024)
                while (active() && System.nanoTime() < deadline) {
                    val n = `in`.read(buf)
                    if (n < 0) break
                    totalBytes += n
                    _state.value = _state.value.copy(phaseProgress = ((System.nanoTime() - startedAt).toFloat() / (maxSec * 1_000_000_000f)))
                }
                `in`.close()
            }
            val elapsedSec = (System.nanoTime() - startedAt) / 1_000_000_000.0
            if (elapsedSec <= 0) 0.0 else totalBytes * 8.0 / elapsedSec / 1_000_000.0
        } catch (_: Throwable) {
            0.0
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
