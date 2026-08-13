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

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

/** 设备静态信息采样器(无采样率,事件性) */
class DeviceSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("device")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        attrs["manufacturer"] = Build.MANUFACTURER
        attrs["model"] = Build.MODEL
        attrs["brand"] = Build.BRAND
        attrs["device_codename"] = "${Build.DEVICE} (${Build.PRODUCT})"
        attrs["android_version"] = Build.VERSION.RELEASE
        attrs["api_level"] = Build.VERSION.SDK_INT.toString()
        attrs["security_patch"] = Build.VERSION.SECURITY_PATCH
        attrs["kernel"] = System.getProperty("os.version") ?: "?"
        attrs["abis"] = Build.SUPPORTED_ABIS.joinToString(",")
        attrs["build_fingerprint"] = Build.FINGERPRINT.take(120)
        attrs["uptime_ms"] = SystemClock.elapsedRealtime().toString()
        attrs["timezone"] = java.util.TimeZone.getDefault().id
        attrs["locale"] = Locale.getDefault().toLanguageTag()
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = ctx.resources.displayMetrics
            attrs["screen"] = "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi"
            attrs["refresh_rate_hz"] = (wm.defaultDisplay?.refreshRate ?: 60f).toString()
            attrs["hdr"] = (wm.defaultDisplay?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true).toString()
        } catch (_: Throwable) {}
        try {
            val b = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (b >= 0) attrs["screen_brightness"] = "$b/255"
        } catch (_: Throwable) {}
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        attrs["screen_on"] = pm.isInteractive.toString()
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            attrs["cameras"] = cm.cameraIdList.joinToString(",") { id ->
                val facing = try {
                    cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                } catch (_: Throwable) { null }
                "$id:${if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"}"
            }
        } catch (_: Throwable) {}
        try {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            attrs["usb_devices"] = usb.deviceList.values.joinToString(",") { it.deviceName }
        } catch (_: Throwable) {}
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            attrs["vibrator"] = vib.hasVibrator().toString()
        } catch (_: Throwable) {}
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1),
        )
    }
}

/** 系统资源采样器:CPU/内存/存储,周期采样 */
class SystemSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("system")!!

    private companion object {
        const val POLL_MS = 500L
    }

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuSamples = ArrayList<Float>()
        val memAvailSamples = ArrayList<Float>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        var lastTotal = 0L
        var lastIdle = 0L
        var init = false
        val stats = LinkedHashMap<String, com.vicinityprobe.model.domain.ChannelStats>()

        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            try {
                // CPU 使用率
                val parts = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
                    ?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: emptyList()
                if (parts.size >= 4) {
                    val total = parts.sum()
                    val idle = parts[3] + parts.getOrElse(4) { 0 }
                    if (init && total > lastTotal) {
                        val usage = (1.0 - (idle - lastIdle).toDouble() / (total - lastTotal)) * 100
                        cpuSamples.add(usage.toFloat())
                    }
                    lastTotal = total; lastIdle = idle; init = true
                }
            } catch (_: Throwable) {}
            try {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                memAvailSamples.add((mi.availMem / 1e6).toFloat())  // MB
            } catch (_: Throwable) {}
            session.live.set("system", "cpu", "${cpuSamples.lastOrNull()?.let { String.format("%.0f%%", it) } ?: "…"}")
            delay(POLL_MS)
        }

        val attrs = LinkedHashMap<String, String>()
        attrs["cpu_cores"] = cores.toString()
        attrs["cpu_freq_mhz"] = readCpuFreqs()
        attrs["load_avg"] = readLoadAvg() ?: "读不了|Unreadable"
        try {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            attrs["mem_total_mb"] = String.format("%.0f", mi.totalMem / 1e6)
            attrs["mem_low"] = mi.lowMemory.toString()
        } catch (_: Throwable) {}
        try {
            val data = android.os.StatFs(File(Environment.getDataDirectory().path).path)
            attrs["storage_internal_gb"] = String.format("%.1f/%.1f",
                data.availableBlocksLong * data.blockSizeLong / 1e9, data.blockCountLong * data.blockSizeLong / 1e9)
        } catch (_: Throwable) {}
        try {
            val ext = android.os.StatFs(File(Environment.getExternalStorageDirectory().path).path)
            attrs["storage_external_gb"] = String.format("%.1f/%.1f",
                ext.availableBlocksLong * ext.blockSizeLong / 1e9, ext.blockCountLong * ext.blockSizeLong / 1e9)
        } catch (_: Throwable) {}
        attrs["thermal"] = readThermal()

        if (cpuSamples.isNotEmpty()) stats["cpu_usage"] = com.vicinityprobe.model.domain.ChannelStats.compute(cpuSamples.toFloatArray(), "%")
        if (memAvailSamples.isNotEmpty()) stats["mem_available"] = com.vicinityprobe.model.domain.ChannelStats.compute(memAvailSamples.toFloatArray(), "MB")

        val n = cpuSamples.size.coerceAtLeast(memAvailSamples.size)
        val q = when {
            n == 0 -> QualityLevel.DEGRADED
            n >= 3 -> QualityLevel.EXCELLENT
            else -> QualityLevel.GOOD
        }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            attributes = attrs, stats = stats,
            quality = QualityReport(q, QualityLevels.CODE_OK, "", sampleCount = n,
                achievedRateHz = n.toDouble() / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 1000.0 / POLL_MS),
        )
    }

    private fun readCpuFreqs(): String = try {
        (0 until Runtime.getRuntime().availableProcessors()).joinToString(",") { core ->
            val maxF = File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }
                ?.readText()?.trim()
            if (maxF != null) "$core:${(maxF.toLongOrNull() ?: 0) / 1000}MHz" else "$core:-"
        }
    } catch (_: Throwable) { "读不了|Unreadable" }

    private fun readLoadAvg(): String? = try {
        val f = File("/proc/loadavg")
        if (f.exists()) f.readText().trim().split(" ").take(3).joinToString(" ") else null
    } catch (_: Throwable) { null }

    private fun readThermal(): String = try {
        val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
        val readable = zones.mapNotNull { zone ->
            try {
                val tempFile = File(zone, "temp")
                if (!tempFile.exists() || !tempFile.canRead()) return@mapNotNull null
                val t = tempFile.readText().trim().toIntOrNull() ?: return@mapNotNull null
                val typeFile = File(zone, "type")
                val type = if (typeFile.exists()) typeFile.readText().trim() else zone.name
                val deg = if (t > 10000) t / 1000 else t
                "$type:${deg / 10.0}°C"
            } catch (_: Throwable) { null }
        }
        if (readable.isEmpty()) "读不了(没权限)|Unreadable" else readable.take(8).joinToString(", ")
    } catch (_: Throwable) { "读不了(没权限)|Unreadable" }
}
