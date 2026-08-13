package com.vicinityprobe.probe

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/** 热状态:thermal zone 明细 + 系统热状态(反射 ThermalService) */
class ThermalSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("thermal")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val zones = ArrayList<String>()
        val rec = ChannelRecorder("value")
        try {
            File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                try {
                    val tempFile = File(zone, "temp")
                    if (!tempFile.exists() || !tempFile.canRead()) return@forEach
                    val t = tempFile.readText().trim().toIntOrNull() ?: return@forEach
                    val type = File(zone, "type").takeIf { it.exists() }?.readText()?.trim() ?: zone.name
                    val deg = if (t > 10000) t / 1000 else t
                    zones.add("$type=${deg / 10.0}°C")
                    rec.add(SystemClockCompat.elapsedRealtime() - session.startRealtimeMs, deg / 10.0f)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // 反射:SystemServiceRegistry 中的 thermalservice → getCurrentThermalStatus()
        var thermalStatus = "unreadable"
        var throttlingSeverity = "unreadable"
        try {
            val clazz = Class.forName("android.hardware.thermal.ThermalStatus")
            val svcClazz = Class.forName("android.os.ServiceManager")
            val get = svcClazz.getMethod("getService", String::class.java)
            val binder = get.invoke(null, "thermalservice")
            if (binder != null) {
                val stub = Class.forName("android.hardware.thermal.IThermalService\$Stub").getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, binder)
                val statusMethod = stub.javaClass.getMethod("getCurrentThermalStatus")
                val status = statusMethod.invoke(stub)
                val enum = clazz.getMethod("name").invoke(status) as? String
                thermalStatus = enum ?: "unknown"
                val severityMethod = stub.javaClass.getMethod("getCurrentThermalSeverity")
                val sev = severityMethod.invoke(stub)
                throttlingSeverity = sev?.toString() ?: "unknown"
            }
        } catch (_: Throwable) {}
        val attrs = LinkedHashMap<String, String>()
        attrs["thermal_status"] = thermalStatus
        attrs["throttling_severity"] = throttlingSeverity
        attrs["zones"] = zones.joinToString("\n").ifEmpty { "读不了(没权限)|Unreadable" }
        if (rec.size() == 0) {
            return okMeasurement(spec, attrs,
                quality = QualityReport(QualityLevel.DEGRADED, QualityLevels.CODE_OK, "热区读不了|Thermal zones unreadable"))
        }
        val stats = ChannelStats.compute(rec.snapshot().map { it.second }.toFloatArray(), "°C")
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("value" to stats),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = rec.size()),
            series = mapOf("value" to rec.decimate()),
        )
    }
}

/** CPU 电源状态:在线核心/调速器/频率档位 */
class PowerStateSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("power_state")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        val cores = Runtime.getRuntime().availableProcessors()
        attrs["cores_present"] = cores.toString()
        try {
            File("/sys/devices/system/cpu/present").takeIf { it.exists() }?.readText()?.trim()?.let { attrs["cpu_present"] = it }
            File("/sys/devices/system/cpu/possible").takeIf { it.exists() }?.readText()?.trim()?.let { attrs["cpu_possible"] = it }
        } catch (_: Throwable) {}
        val details = ArrayList<String>()
        for (core in 0 until cores) {
            try {
                val base = "/sys/devices/system/cpu/cpu$core"
                val online = File("$base/online").takeIf { it.exists() }?.readText()?.trim()
                val governor = File("$base/cpufreq/scaling_governor").takeIf { it.exists() }?.readText()?.trim()
                val minF = File("$base/cpufreq/cpuinfo_min_freq").takeIf { it.exists() }?.readText()?.trim()
                val maxF = File("$base/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()
                val curF = File("$base/cpufreq/scaling_cur_freq").takeIf { it.exists() }?.readText()?.trim()
                details.add(
                    "cpu$core:online=${online ?: "?"} gov=${governor ?: "?"} " +
                        "freq=${curF?.toLongOrNull()?.div(1000) ?: "?"}MHz " +
                        "range=${minF?.toLongOrNull()?.div(1000) ?: "?"}-${maxF?.toLongOrNull()?.div(1000) ?: "?"}MHz",
                )
            } catch (_: Throwable) {}
        }
        attrs["cores_detail"] = details.joinToString("\n").ifEmpty { "读不了|Unreadable" }
        // 深度睡眠统计
        try {
            val sched = File("/proc/schedstat").readLines().firstOrNull()
            if (sched != null) attrs["schedstat_summary"] = sched.trim()
        } catch (_: Throwable) {}
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = cores))
    }
}

/** 内核与安全:SELinux/内核版本/引导加载程序 */
class KernelSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("kernel")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            File("/sys/fs/selinux/enforce").takeIf { it.exists() }?.readText()?.trim()?.let {
                attrs["selinux_enforcing"] = if (it == "1") "true" else "false"
            }
        } catch (_: Throwable) { attrs["selinux_enforcing"] = "unreadable" }
        try {
            File("/proc/version").takeIf { it.exists() }?.readText()?.trim()?.let { attrs["proc_version"] = it.take(300) }
        } catch (_: Throwable) {}
        attrs["bootloader"] = Build.BOOTLOADER
        attrs["hardware"] = Build.HARDWARE
        attrs["revision"] = try { Build::class.java.getField("REVISION").get(null).toString() } catch (_: Throwable) { "?" }
        attrs["boot_image_time"] = Build.TIME.toString()
        attrs["build_tags"] = Build.TAGS
        attrs["build_type"] = Build.TYPE
        attrs["java_vendor"] = System.getProperty("java.vm.name", "?")
        attrs["art_version"] = System.getProperty("java.vm.version", "?")
        // 反射读取序列号(多数设备受限)
        try {
            val getSerial = Build::class.java.getMethod("getSerial")
            attrs["serial"] = getSerial.invoke(null) as? String ?: "restricted"
        } catch (_: Throwable) {
            attrs["serial"] = "restricted"
        }
        // 电池: 通过 BatteryManager 充电循环等 —— 略
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 显示能力:刷新率模式/HDR 类型/颜色 */
class DisplaySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("display")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            val modes = display.supportedModes
            attrs["refresh_modes"] = modes.joinToString("\n") { m ->
                "id=${m.modeId} ${m.physicalWidth}x${m.physicalHeight}@${m.refreshRate}Hz"
            }
            try {
                val cur = display.javaClass.getMethod("getCurrentMode").invoke(display)
                val id = cur?.javaClass?.getMethod("getModeId")?.invoke(cur)
                val rate = cur?.javaClass?.getMethod("getRefreshRate")?.invoke(cur)
                attrs["current_mode"] = "id=$id rate=$rate"
            } catch (_: Throwable) {}
            val hdrTypes = display.hdrCapabilities?.supportedHdrTypes
            if (hdrTypes != null && hdrTypes.isNotEmpty()) {
                attrs["hdr_types"] = hdrTypes.joinToString(",") {
                    when (it) {
                        android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DolbyVision"
                        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                        android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                        else -> "type$it"
                    }
                }
            }
        } catch (_: Throwable) {}
        try {
            val brightnessMode = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            attrs["auto_brightness"] = (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC).toString()
            val rotation = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
            attrs["auto_rotate"] = (rotation == 1).toString()
            val timeout = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            attrs["screen_off_timeout_ms"] = timeout.toString()
        } catch (_: Throwable) {}
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 存储卷:StorageManager 卷列表 */
class StorageSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("storage")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            val volumes = sm.storageVolumes
            attrs["volume_count"] = volumes.size.toString()
            val details = volumes.map { v ->
                val path = try { v.directory?.path ?: "?" } catch (_: Throwable) { "?" }
                val total = try { android.os.StatFs(path).totalBytes / 1e9 } catch (_: Throwable) { 0.0 }
                val avail = try { android.os.StatFs(path).availableBytes / 1e9 } catch (_: Throwable) { 0.0 }
                "${v.uuid ?: "primary"}|${v.state}|emulated=${v.isEmulated}|removable=${v.isRemovable}|" +
                    "${if (v.isPrimary) "primary" else "secondary"}|${"%.1f".format(total)}GB/avail ${"%.1f".format(avail)}GB"
            }
            attrs["volumes"] = details.joinToString("\n")
        } catch (_: Throwable) {
            attrs["volumes"] = "读不了|Unreadable"
        }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}
