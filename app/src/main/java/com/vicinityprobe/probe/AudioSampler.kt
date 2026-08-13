/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.vicinityprobe.analysis.SpectrumAnalyzer
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SeriesPt
import com.vicinityprobe.model.domain.SpectrumResult
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 专业声学采样器:AudioRecord 直接读取 PCM。
 * - 50ms 帧 RMS → 近似声压级 dB(参考值,未校准)
 * - 汇总 LAeq(等效连续声级)/ Lpeak / 统计声级 L10/L50/L90
 * - 保留 PCM 尾部(8192 采样)做 FFT 频谱分析
 * 采样率 44.1kHz、16bit 单声道。
 */
class AudioSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("noise")!!

    private companion object {
        const val SAMPLE_RATE = 44100
        const val FRAME_MS = 50
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val REF_FULL_SCALE = 32767.0
        // 典型麦克风灵敏度近似偏移:满幅对应约 94 dB SPL;未校准,输出为参考级
        const val CALIBRATION_OFFSET_DB = 94.0
        const val PCM_TAIL = 8192
    }

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return failed(QualityLevels.CODE_PERMISSION_DENIED, "缺少录音权限|RECORD_AUDIO permission denied")
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            return failed(QualityLevels.CODE_ACQUISITION_ERROR, "麦克风不可用|Microphone unavailable")
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FRAME_SAMPLES * 2),
            )
        } catch (e: Exception) {
            return failed(QualityLevels.CODE_ACQUISITION_ERROR, e.javaClass.simpleName)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return failed(QualityLevels.CODE_ACQUISITION_ERROR, "AudioRecord 初始化失败|AudioRecord init failed")
        }

        val frameDbs = ArrayList<Double>()
        val framePeaks = ArrayList<Double>()
        val rmsSeries = ArrayList<Pair<Long, Double>>()
        val pcm = FloatArray(PCM_TAIL)
        var pcmIdx = 0
        var pcmFilled = 0
        val buf = ShortArray(FRAME_SAMPLES)
        var frames = 0
        var readErrors = 0

        record.startRecording()
        try {
            while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
                val read = record.read(buf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    readErrors++
                    if (readErrors > 20) break
                    kotlinx.coroutines.delay(10)
                    continue
                }
                if (read == 0) continue
                var sumSq = 0.0
                var peak = 0
                for (i in 0 until read) {
                    val v = buf[i].toInt()
                    sumSq += v.toDouble() * v
                    if (abs(v) > peak) peak = abs(v)
                    pcm[pcmIdx] = (v / REF_FULL_SCALE).toFloat()
                    pcmIdx = (pcmIdx + 1) % PCM_TAIL
                    pcmFilled++
                }
                val rms = sqrt(sumSq / read)
                val dbRms = if (rms > 0) 20 * log10(rms / REF_FULL_SCALE) + CALIBRATION_OFFSET_DB else 0.0
                val dbPeak = if (peak > 0) 20 * log10(peak / REF_FULL_SCALE) + CALIBRATION_OFFSET_DB else 0.0
                val t = session.elapsedMs()
                frameDbs.add(dbRms)
                framePeaks.add(dbPeak)
                rmsSeries.add(t to dbRms)
                frames++
                session.live.set("noise", "SPL dB(A)", String.format("%.1f", dbRms))
            }
        } catch (_: Exception) {
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
        }

        if (frames < 3) {
            return failed(QualityLevels.CODE_INSUFFICIENT_SAMPLES, "采样帧数不足|Insufficient audio frames")
        }
        val sorted = frameDbs.sorted()
        val laeq = 10 * log10(sorted.sumOf { 10.0.pow(it / 10.0) } / sorted.size)
        val lpeak = framePeaks.maxOrNull() ?: 0.0
        val quantile = { q: Double -> sorted[((sorted.size - 1) * q).toInt()] }

        val coverage = (frames * FRAME_MS).toDouble() /
            session.remainingMs().let { if (it <= 0) 1 else it } * 100

        // 频谱分析(尾部 PCM)
        val spectrum: SpectrumResult? = if (pcmFilled >= 4096) {
            val ordered = ArrayList<Double>(PCM_TAIL)
            if (pcmFilled >= PCM_TAIL) {
                for (i in 0 until PCM_TAIL) ordered.add(pcm[(pcmIdx + i) % PCM_TAIL].toDouble())
            } else {
                ordered.addAll(pcm.take(pcmFilled).map { it.toDouble() })
            }
            SpectrumAnalyzer(SAMPLE_RATE.toDouble()).analyze(ordered)
        } else null

        return Measurement(
            spec = spec,
            status = QualityLevels.CODE_OK,
            stats = mapOf(
                "LAeq" to com.vicinityprobe.model.domain.ChannelStats.compute(frameDbs.map { it.toFloat() }.toFloatArray(), "dB(A)"),
                "Lpeak" to com.vicinityprobe.model.domain.ChannelStats.compute(framePeaks.map { it.toFloat() }.toFloatArray(), "dB(A)"),
            ),
            attributes = mapOf(
                "LAeq" to String.format("%.1f", laeq),
                "Lpeak" to String.format("%.1f", lpeak),
                "L10" to String.format("%.1f", quantile(0.9)),
                "L50" to String.format("%.1f", quantile(0.5)),
                "L90" to String.format("%.1f", quantile(0.1)),
                "frames" to frames.toString(),
                "frame_ms" to FRAME_MS.toString(),
                "sample_rate" to SAMPLE_RATE.toString(),
                "calibrated" to "false",
                "note" to "未校准,参考值|Uncalibrated reference level",
            ),
            quality = QualityReport(
                level = if (coverage >= 80) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = frames,
                achievedRateHz = 1000.0 / FRAME_MS, nominalRateHz = 1000.0 / FRAME_MS,
                coveragePct = coverage.coerceAtMost(100.0),
            ),
            samplesFile = null,
            series = mapOf("LAeq" to rmsSeries.map { SeriesPt(it.first, it.second) }),
            spectrum = spectrum,
        )
    }

    private fun failed(code: String, detail: String) = Measurement(
        spec = spec, status = code,
        quality = QualityReport(level = QualityLevel.FAILED, code = code, detail = detail),
    )
}

private fun Double.pow(e: Double): Double = Math.pow(this, e)

/** 音频设备状态采样器 */
class AudioStateSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("audio_state")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = LinkedHashMap<String, String>()
        val volumes = ArrayList<String>()
        fun addVol(stream: Int, name: String) {
            try { volumes.add("$name:${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}") } catch (_: Throwable) {}
        }
        addVol(android.media.AudioManager.STREAM_MUSIC, "media")
        addVol(android.media.AudioManager.STREAM_RING, "ring")
        addVol(android.media.AudioManager.STREAM_ALARM, "alarm")
        addVol(android.media.AudioManager.STREAM_NOTIFICATION, "notification")
        attrs["volumes"] = volumes.joinToString(",")
        attrs["ringer_mode"] = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_NORMAL -> "正常|Normal"
            android.media.AudioManager.RINGER_MODE_SILENT -> "静音|Silent"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "振动|Vibrate"
            else -> "unknown"
        }
        attrs["music_active"] = am.isMusicActive.toString()
        val outputs = try {
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { d ->
                val type = when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
                    android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
                    else -> "type${d.type}"
                }
                "$type(${d.sampleRates.firstOrNull() ?: 0}Hz/${d.channelCounts.firstOrNull() ?: 0}ch)"
            }
        } catch (_: Throwable) { "none" }
        attrs["output_devices"] = outputs
        val inputs = try { am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).size } catch (_: Throwable) { 0 }
        attrs["input_device_count"] = inputs.toString()
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            quality = com.vicinityprobe.model.domain.QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1),
        )
    }
}
