/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.soundlevel

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt

/** 声级记录器状态 */
data class SoundLevelState(
    val running: Boolean = false,
    val elapsedSec: Long = 0,
    val totalSec: Long = 300,
    val currentDb: Double = 0.0,          // 当前 1s 滑动 LAeq
    val currentMinuteDb: Double = 0.0,    // 当前分钟累计 LAeq
    val minuteBins: List<Double> = emptyList(),  // 已完成分钟 LAeq
    val peakDb: Double = 0.0,
    val error: String? = null,
)

class SoundLevelViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SoundLevelState())
    val state: StateFlow<SoundLevelState> = _state

    private var job: Job? = null
    private var record: AudioRecord? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun start(totalSec: Long) {
        if (job?.isActive == true) return
        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            _state.value = _state.value.copy(error = "AudioRecord 初始化失败|AudioRecord init failed")
            return
        }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(16384))
        } catch (_: Throwable) { null }
        if (rec == null) {
            _state.value = _state.value.copy(error = "麦克风不可用|Microphone unavailable")
            return
        }
        record = rec
        _state.value = SoundLevelState(running = true, totalSec = totalSec)
        job = viewModelScope.launch(Dispatchers.Default) {
            rec.startRecording()
            val buf = ShortArray(4410)   // 100ms 帧
            val frameEnergy = ArrayList<Double>(60)   // 每帧 RMS²,1s 滑动窗
            val minuteEnergy = ArrayList<Double>(600) // 当前分钟各帧
            val minuteBins = ArrayList<Double>()
            val startedAt = System.currentTimeMillis()
            var lastMinute = 0
            var peakDb = 0.0
            while (isActive && System.currentTimeMillis() - startedAt < totalSec * 1000) {
                val read = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                if (read < 0) break
                var sumSq = 0.0
                for (i in 0 until read) sumSq += buf[i].toDouble() * buf[i]
                val rms = sqrt(sumSq / read)
                val db = if (rms > 0) 20 * ln(rms / 32767.0) / ln(10.0) + 94.0 else 0.0
                if (db > peakDb) peakDb = db
                frameEnergy.add(db * db * 0.0001 + 1e-12)
                minuteEnergy.add(db * db * 0.0001 + 1e-12)
                if (frameEnergy.size > 10) frameEnergy.removeAt(0)
                val slideDb = 10 * ln(frameEnergy.average()) / ln(10.0)
                // 分钟推进
                val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
                val minute = (elapsedSec / 60).toInt()
                if (minute > lastMinute) {
                    if (minuteEnergy.isNotEmpty()) {
                        minuteBins.add((10 * ln(minuteEnergy.average()) / ln(10.0)).coerceAtLeast(0.0))
                    }
                    minuteEnergy.clear()
                    lastMinute = minute
                }
                val minuteDb = if (minuteEnergy.isNotEmpty()) 10 * ln(minuteEnergy.average()) / ln(10.0) else 0.0
                _state.value = SoundLevelState(
                    running = true, elapsedSec = elapsedSec, totalSec = totalSec,
                    currentDb = slideDb, currentMinuteDb = minuteDb,
                    minuteBins = minuteBins.toList(), peakDb = peakDb,
                )
            }
            // 收尾:剩余不足 60s 的也记为一分钟
            if (minuteEnergy.isNotEmpty() && minuteBins.size < totalSec / 60) {
                minuteBins.add((10 * ln(minuteEnergy.average()) / ln(10.0)).coerceAtLeast(0.0))
            }
            val finalBins = minuteBins.toList()
            _state.value = SoundLevelState(
                running = false, elapsedSec = totalSec, totalSec = totalSec,
                currentDb = 0.0, currentMinuteDb = 0.0,
                minuteBins = finalBins, peakDb = peakDb,
            )
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        _state.value = _state.value.copy(running = false)
    }

    /** 导出分钟级 LAeq CSV */
    fun exportCsv(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("minute,LAeq_dBA")
        s.minuteBins.forEachIndexed { i, v -> sb.appendLine("$i,${String.format("%.1f", v)}") }
        sb.appendLine("peak,${String.format("%.1f", s.peakDb)}")
        return sb.toString()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
