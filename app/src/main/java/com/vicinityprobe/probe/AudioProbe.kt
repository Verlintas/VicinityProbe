package com.vicinityprobe.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.ln

object NoiseUtils {
    // 近似 dB SPL,非专业校准,仅供参考
    fun ampToDb(amp: Int): Double {
        if (amp <= 0) return 0.0
        val ratio = amp / 32767.0
        return (20 * ln(ratio) / ln(10.0) + 100).coerceIn(0.0, 120.0)
    }
}

class AudioProbe(private val selected: Set<String>) : ProbeUnit {
    override val id = "audio"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()
        if (selected.contains("noise")) results.addAll(NoiseUnit(ctx, deadlineMs, live).run())
        if (selected.contains("audio_state")) results.addAll(AudioStateUnit(ctx).run())
        return results
    }
}

private class NoiseUnit(
    private val ctx: Context,
    private val deadlineMs: Long,
    private val live: LiveMetrics,
) {
    suspend fun run(): List<ProbeResult> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return listOf(resultBuilder("noise", Groups.AUDIO, Labels.NOISE, ProbeStatus.PERMISSION_MISSING, note = Manifest.permission.RECORD_AUDIO))
        }
        val recorder = MediaRecorder()
        val start = SystemClockCompat.elapsedRealtime()
        val w = Welford()
        val series = Series()
        var failReason: String? = null
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(File(ctx.cacheDir, "noise_sample.aac"))
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            failReason = e.javaClass.simpleName
        }
        if (failReason == null) {
            while (SystemClockCompat.elapsedRealtime() < deadlineMs) {
                val amp = try { recorder.maxAmplitude } catch (_: Throwable) { 0 }
                val db = NoiseUtils.ampToDb(amp)
                w.add(db)
                series.add(SystemClockCompat.elapsedRealtime() - start, db)
                live.set("noise", Labels.NOISE.en, "${fmt(db)} dB")
                delay(200)
            }
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
            try { File(ctx.cacheDir, "noise_sample.aac").delete() } catch (_: Throwable) {}
        } else {
            try { recorder.release() } catch (_: Throwable) {}
        }
        if (failReason != null) {
            return listOf(resultBuilder("noise", Groups.AUDIO, Labels.NOISE, ProbeStatus.FAILED, note = failReason))
        }
        if (w.empty()) {
            return listOf(resultBuilder("noise", Groups.AUDIO, Labels.NOISE, ProbeStatus.FAILED, note = bil("无采样数据", "No samples")))
        }
        return listOf(resultBuilder(
            "noise", Groups.AUDIO, Labels.NOISE, ProbeStatus.OK,
            metrics = listOf(
                metric("avg", Labels.DB, fmt(w.avg()), "dB", primary = true),
                metric("min", Labels.MIN, fmt(w.min), "dB"),
                metric("max", Labels.MAX, fmt(w.max), "dB"),
                metric("last", Labels.LAST, fmt(w.last), "dB"),
                metric("note", L("说明", "Note"), bil("近似值,未经校准", "Approximate, uncalibrated")),
            ),
            series = mapOf("value" to series.list()),
        ))
    }
}

private class AudioStateUnit(private val ctx: Context) {
    suspend fun run(): List<ProbeResult> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val metrics = mutableListOf<com.vicinityprobe.model.Metric>()
        val volumes = ArrayList<String>()
        fun addVol(stream: Int, name: String) {
            try {
                val max = am.getStreamMaxVolume(stream)
                val cur = am.getStreamVolume(stream)
                volumes.add("$name: $cur/$max")
            } catch (_: Throwable) {}
        }
        addVol(AudioManager.STREAM_MUSIC, bil("媒体", "Media"))
        addVol(AudioManager.STREAM_RING, bil("铃声", "Ring"))
        addVol(AudioManager.STREAM_ALARM, bil("闹钟", "Alarm"))
        addVol(AudioManager.STREAM_NOTIFICATION, bil("通知", "Notification"))
        addVol(AudioManager.STREAM_SYSTEM, bil("系统", "System"))
        metrics.add(metric("volumes", Labels.VOLUME, volumes.joinToString(", "), primary = true))
        metrics.add(metric("ringer", Labels.RINGER, ringerName(am.ringerMode)))
        metrics.add(metric("music_active", L("媒体播放中", "Music playing"), if (am.isMusicActive) bil("是", "Yes") else bil("否", "No")))

        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
                val type = when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> bil("扬声器", "Speaker")
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> bil("听筒", "Earpiece")
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> bil("有线耳机", "Wired headset")
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bil("蓝牙音箱/耳机", "Bluetooth")
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                    android.media.AudioDeviceInfo.TYPE_TELEPHONY -> bil("通话", "Telephony")
                    else -> "type=${d.type}"
                }
                "$type(${d.sampleRates.firstOrNull() ?: 0}Hz/${d.channelCounts.firstOrNull() ?: 0}ch)"
            }
        } catch (_: Throwable) { emptyList() }
        metrics.add(metric("out_devices", Labels.OUT_DEVICES, outputs.ifEmpty { listOf(bil("无", "None")) }.joinToString(", ")))

        val inputs = try {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).size
        } catch (_: Throwable) { 0 }
        metrics.add(metric("in_devices", L("麦克风数", "Microphones"), inputs.toString()))

        val minBuf = try { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) } catch (_: Throwable) { null }
        if (minBuf != null) metrics.add(metric("sample_rate", L("采样率", "Sample rate"), "$minBuf Hz"))
        return listOf(resultBuilder("audio_state", Groups.AUDIO, Labels.AUDIO_STATE, ProbeStatus.OK, metrics = metrics))
    }

    private fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> bil("正常", "Normal")
        AudioManager.RINGER_MODE_SILENT -> bil("静音", "Silent")
        AudioManager.RINGER_MODE_VIBRATE -> bil("振动", "Vibrate")
        else -> "UNKNOWN"
    }
}
