/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 音频链路测试:播放 1kHz 测试音滴声,麦克风同步录音,
 * 检测扬声器 → 麦克风回环延迟与电平。
 * 注意:需要开启扬声器;静音模式下无回环。
 */
class AudioLinkTestSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("audio_link_test")!!

    private companion object {
        const val SAMPLE_RATE = 44100
        const val TONE_FREQ = 1000
        const val TONE_DURATION_MS = 150
        const val CAPTURE_MS = 1200
        const val TRIES = 3
    }

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return failedMeasurement(spec, QualityLevels.CODE_PERMISSION_DENIED, "没给录音权限|RECORD_AUDIO required")
        }
        val result = withContext(Dispatchers.IO) { runLoopback() }
        if (result == null) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "音频链路不可用|Audio link unavailable")
        }
        val (latencyMs, peakLevel, detections) = result
        val attrs = LinkedHashMap<String, String>()
        attrs["tone_hz"] = TONE_FREQ.toString()
        attrs["loop_latency_ms"] = String.format("%.1f", latencyMs)
        attrs["peak_level_db"] = String.format("%.1f", peakLevel)
        attrs["detections"] = "$detections/$TRIES"
        attrs["method"] = "扬声器播放 1kHz 滴声,麦克风检测回环|Speaker 1 kHz tone, mic loopback detection"
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("latency" to ChannelStats.compute(floatArrayOf(latencyMs.toFloat()), "ms")),
            quality = QualityReport(
                level = if (detections > 0) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = detections,
            ),
        )
    }

    /** 返回 (平均延迟ms, 峰值dB, 检测次数) */
    @android.annotation.SuppressLint("MissingPermission")
    private fun runLoopback(): Triple<Double, Double, Int>? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(8192))
        } catch (_: Throwable) { null } ?: return null
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(8192)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Throwable) { null } ?: run { try { record.release() } catch (_: Throwable) {}; return null }

        // 生成 1kHz 滴声
        val toneSamples = TONE_DURATION_MS * SAMPLE_RATE / 1000
        val tone = ShortArray(toneSamples)
        for (i in toneSamples - 1 downTo 0) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = if (i < toneSamples * 0.1 || i > toneSamples * 0.9) 0.5 else 1.0
            tone[i] = (Short.MAX_VALUE * 0.6 * kotlin.math.sin(2 * kotlin.math.PI * TONE_FREQ * t) * env).toInt().toShort()
        }
        val totalSamples = CAPTURE_MS * SAMPLE_RATE / 1000
        val captureBuf = ShortArray(totalSamples)

        val latencies = ArrayList<Double>()
        var peakDb = 0.0
        var detections = 0
        try {
            record.startRecording()
            for (tryN in 0 until TRIES) {
                // 填静音,播放滴声,同时录音
                val pcm = ShortArray(totalSamples)
                // 播放起点:录音开始后 50ms
                val toneOffset = SAMPLE_RATE / 20
                System.arraycopy(tone, 0, pcm, toneOffset, toneSamples)
                track.write(pcm, 0, pcm.size)
                track.play()
                record.read(captureBuf, 0, totalSamples)
                track.stop()
                track.pause()
                // 找播放后 100ms 起的峰值窗口
                val searchStart = (SAMPLE_RATE / 10) + toneOffset
                var bestIdx = -1
                var bestAmp = 0
                for (i in searchStart until totalSamples) {
                    val a = abs(captureBuf[i].toInt())
                    if (a > bestAmp) { bestAmp = a; bestIdx = i }
                }
                if (bestIdx > 0 && bestAmp > Short.MAX_VALUE * 0.05) {
                    val latencySamples = bestIdx - toneOffset
                    latencies.add(latencySamples * 1000.0 / SAMPLE_RATE)
                    peakDb = 20 * kotlin.math.log10(bestAmp / 32767.0) + 94.0
                    detections++
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
        if (latencies.isEmpty()) return Triple(0.0, peakDb, 0)
        return Triple(latencies.average(), peakDb, detections)
    }
}
