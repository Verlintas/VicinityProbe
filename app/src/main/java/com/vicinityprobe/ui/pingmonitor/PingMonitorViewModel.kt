/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.pingmonitor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PingStats(
    val samples: List<Long> = emptyList(),   // 毫秒,失败为 -1
    val sent: Int = 0,
    val lost: Int = 0,
    val minMs: Long = 0,
    val avgMs: Long = 0,
    val maxMs: Long = 0,
    val jitterMs: Long = 0,                  // 相邻延迟差平均
    val running: Boolean = false,
    val target: String = "",
)

class PingMonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PingStats())
    val state: StateFlow<PingStats> = _state

    private var job: Job? = null

    fun defaultTarget(): String {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.getLinkProperties(cm.activeNetwork)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "1.1.1.1"
        } catch (_: Throwable) { "1.1.1.1" }
    }

    fun start(target: String, intervalMs: Long = 500, maxSamples: Int = 240) {
        if (job?.isActive == true) return
        val host = target.trim().ifEmpty { defaultTarget() }
        _state.value = PingStats(running = true, target = host)
        job = viewModelScope.launch {
            while (isActive) {
                val latency = withContext(Dispatchers.IO) { tcpPing(host, port = 443, timeoutMs = 1500) }
                val cur = _state.value
                val samples = (cur.samples + latency).takeLast(maxSamples)
                val ok = samples.filter { it >= 0 }
                val lost = samples.count { it < 0 }
                val jitter = if (ok.size >= 2) {
                    ok.zipWithNext().map { (a, b) -> kotlin.math.abs(a - b) }.average().toLong()
                } else 0L
                _state.value = cur.copy(
                    samples = samples,
                    sent = cur.sent + 1,
                    lost = lost,
                    minMs = ok.minOrNull() ?: 0,
                    avgMs = if (ok.isEmpty()) 0 else ok.average().toLong(),
                    maxMs = ok.maxOrNull() ?: 0,
                    jitterMs = jitter,
                )
                delay(intervalMs)
            }
        }
    }

    /** TCP ping:连接目标 443 端口测 RTT(ICMP 需要 root) */
    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val t0 = System.nanoTime()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.nanoTime() - t0) / 1_000_000
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
