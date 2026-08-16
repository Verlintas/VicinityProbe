/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.netmatrix

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class MatrixTarget(
    val name: String,
    val host: String,
    val latencyMs: Long = -1,   // -1 = 超时/不可达
    val reachable: Boolean = false,
)

data class NetMatrixState(
    val monitoring: Boolean = false,
    val round: Int = 0,
    val targets: List<MatrixTarget> = emptyList(),
)

class NetMatrixViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetMatrixState())
    val state: StateFlow<NetMatrixState> = _state

    private var job: Job? = null

    private fun defaultTargets(): List<Pair<String, String>> {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val gw = try {
            cm.getLinkProperties(cm.activeNetwork)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        } catch (_: Throwable) { null }
        return buildList {
            add("网关" to (gw ?: "192.168.1.1"))
            add("DNS" to "223.5.5.5")
            add("Cloudflare" to "1.1.1.1")
            add("Google" to "8.8.8.8")
            add("Github" to "github.com")
        }
    }

    fun start(intervalMs: Long = 2000) {
        if (job?.isActive == true) return
        _state.value = NetMatrixState(monitoring = true, targets = defaultTargets().map { MatrixTarget(it.first, it.second) })
        job = viewModelScope.launch {
            var round = 0
            while (isActive) {
                round++
                val targets = _state.value.targets
                val results = targets.map { t ->
                    async(Dispatchers.IO) { t.name to t.host to tcpPing(t.host, 443, 1500) }
                }.awaitAll()
                _state.value = _state.value.copy(
                    round = round,
                    targets = results.map { (info, lat) ->
                        MatrixTarget(info.first, info.second, lat, lat >= 0)
                    },
                )
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(monitoring = false)
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val t0 = System.nanoTime()
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Throwable) {
            -1L
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
