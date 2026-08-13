package com.vicinityprobe.probe

import java.text.DecimalFormat
import kotlin.math.abs

object SystemClockCompat {
    fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

val fmtInt: DecimalFormat = DecimalFormat("0")
val fmt1: DecimalFormat = DecimalFormat("0.0")
val fmt2: DecimalFormat = DecimalFormat("0.00")

fun fmt(v: Double): String = if (abs(v) >= 1000) fmtInt.format(v) else fmt2.format(v)

/** 实时指标表(线程安全) */
class LiveMetrics {
    private val map = HashMap<String, Pair<String, String>>()

    @Synchronized
    fun set(id: String, label: String, value: String) {
        map[id] = label to value
    }

    @Synchronized
    fun snapshot(): Map<String, Pair<String, String>> = map.toMap()
}
