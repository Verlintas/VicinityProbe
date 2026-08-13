package com.vicinityprobe.probe

import android.content.Context
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.SeriesPt
import java.io.File
import java.util.Locale

/**
 * 通道原始样本记录器:在线记录 + 会话结束时落盘 CSV + 计算精确统计。
 * 内存上限保护:超过 MAX_RAW 时按比例降采样继续记录。
 */
class ChannelRecorder(private val channel: String) {
    private val raw = ArrayList<Pair<Long, Float>>()
    private var step = 1

    companion object {
        const val MAX_RAW = 60_000
    }

    @Synchronized
    fun add(tMs: Long, v: Float) {
        if (raw.size >= MAX_RAW) {
            step = 2
            for (i in 0 until raw.size / 2) raw[i] = raw[i * 2]
            raw.subList(raw.size / 2, raw.size).clear()
        }
        raw.add(tMs to v)
    }

    @Synchronized
    fun size(): Int = raw.size

    @Synchronized
    fun snapshot(): List<Pair<Long, Float>> = raw

    @Synchronized
    fun writeCsv(file: File, header: String = "t_ms,$channel") {
        file.parentFile?.mkdirs()
        file.writer().use { w ->
            w.write(header + "\n")
            raw.forEach { (t, v) -> w.write(String.format(Locale.US, "%d,%.6f%n", t, v)) }
        }
    }

    @Synchronized
    fun decimate(maxPoints: Int = 600): List<SeriesPt> {
        if (raw.size <= maxPoints) return raw.map { SeriesPt(it.first, it.second.toDouble()) }
        val k = raw.size / maxPoints + 1
        return raw.filterIndexed { i, _ -> i % k == 0 }.map { SeriesPt(it.first, it.second.toDouble()) }
    }
}

/** 采样会话上下文:截止时间、原始样本目录、实时指标 */
class SessionContext(
    val startRealtimeMs: Long,
    val deadlineRealtimeMs: Long,
    val samplesDir: File,
    val live: LiveMetrics,
) {
    fun elapsedMs(now: Long = SystemClockCompat.elapsedRealtime()): Long = now - startRealtimeMs
    fun remainingMs(now: Long = SystemClockCompat.elapsedRealtime()): Long = (deadlineRealtimeMs - now).coerceAtLeast(0)
}

/** 采样器:每个探测项的实现,接收应用 Context 与会话上下文 */
interface Sampler {
    val spec: ProbeSpec
    suspend fun run(ctx: Context, session: SessionContext): Measurement
}
