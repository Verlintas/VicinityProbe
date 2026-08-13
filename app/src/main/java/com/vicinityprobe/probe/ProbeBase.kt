package com.vicinityprobe.probe

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import java.text.DecimalFormat

interface ProbeUnit {
    val id: String
    suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<com.vicinityprobe.model.ProbeResult>
}

suspend fun waitUntil(ctx: CoroutineContext, deadlineMs: Long, stepMs: Long = 200) {
    while (ctx.isActive && SystemClockCompat.elapsedRealtime() < deadlineMs) {
        ctx.ensureActive()
        delay(stepMs)
    }
}

suspend fun waitFor(ctx: CoroutineContext, timeoutMs: Long, condition: () -> Boolean) {
    val end = SystemClockCompat.elapsedRealtime() + timeoutMs
    while (ctx.isActive && SystemClockCompat.elapsedRealtime() < end && !condition()) {
        ctx.ensureActive()
        delay(100)
    }
}

object SystemClockCompat {
    fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

val fmtInt: DecimalFormat = DecimalFormat("0")
val fmt1: DecimalFormat = DecimalFormat("0.0")
val fmt2: DecimalFormat = DecimalFormat("0.00")

fun fmt(v: Double): String = if (abs(v) >= 1000) fmtInt.format(v) else fmt2.format(v)
