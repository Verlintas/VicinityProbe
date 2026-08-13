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
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DeviceProbe(private val selected: Set<String>) : ProbeUnit {
    override val id = "device"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()
        if (selected.contains("device")) results.add(deviceInfo(ctx))
        if (selected.contains("system")) results.add(systemStatus(ctx))
        return results
    }

    private suspend fun deviceInfo(ctx: Context): ProbeResult {
        val metrics = mutableListOf<com.vicinityprobe.model.Metric>(
            metric("model", Labels.MODEL, "${Build.MANUFACTURER} ${Build.MODEL}", primary = true),
            metric("brand", L("品牌", "Brand"), Build.BRAND),
            metric("device", L("设备代号", "Device codename"), "${Build.DEVICE} (${Build.PRODUCT})"),
            metric("os", Labels.OS_VERSION, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            metric("security_patch", Labels.SECURITY_PATCH, Build.VERSION.SECURITY_PATCH),
            metric("kernel", Labels.KERNEL, System.getProperty("os.version") ?: "?"),
            metric("abis", Labels.ABIS, Build.SUPPORTED_ABIS.joinToString(", ")),
            metric("fingerprint", L("编译指纹", "Build fingerprint"), Build.FINGERPRINT.take(120)),
        )
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = ctx.resources.displayMetrics
            metrics.add(metric("screen", Labels.SCREEN, "${dm.widthPixels}×${dm.heightPixels} ${dm.densityDpi}dpi"))
            val refresh = wm.defaultDisplay?.refreshRate ?: 60
            metrics.add(metric("refresh", Labels.REFRESH, "${refresh} Hz"))
            val hdr = wm.defaultDisplay?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
            metrics.add(metric("hdr", Labels.HDR, if (hdr) "HDR10/HDR10+/Dolby" else bil("不支持", "Not supported")))
        } catch (_: Throwable) {}
        try {
            val brightness = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (brightness >= 0) metrics.add(metric("brightness", Labels.BRIGHTNESS, "$brightness/255"))
        } catch (_: Throwable) {}
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        metrics.add(metric("screen_on", Labels.SCREEN_ON, if (pm.isInteractive) bil("亮屏", "On") else bil("灭屏", "Off")))
        metrics.add(metric("uptime", Labels.UPTIME, fmtHms(SystemClock.elapsedRealtime())))
        metrics.add(metric("timezone", Labels.TIMEZONE, java.util.TimeZone.getDefault().id))
        metrics.add(metric("locale", Labels.LOCALE, Locale.getDefault().toLanguageTag()))
        metrics.add(metric("time", L("当前时间", "Current time"), java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())))

        try {
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cameraManager.cameraIdList
            val desc = ids.map { id ->
                val facing = try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                } catch (_: Throwable) { null }
                "$id:${if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) bil("前", "front") else bil("后", "back")}"
            }
            metrics.add(metric("cameras", Labels.CAMERAS, desc.joinToString(", ").ifEmpty { "0" }))
        } catch (_: Throwable) {}

        try {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val list = usb.deviceList.values.map { it.deviceName }
            metrics.add(metric("usb", Labels.USB, if (list.isEmpty()) bil("无", "None") else list.joinToString(", ")))
        } catch (_: Throwable) {}

        try {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            metrics.add(metric("vibrator", Labels.VIBRATOR, if (vibrator.hasVibrator()) bil("支持", "Supported") else bil("不支持", "Not supported")))
        } catch (_: Throwable) {}

        return resultBuilder("device", Groups.DEVICE, Labels.DEVICE_INFO, ProbeStatus.OK, metrics = metrics)
    }

    private suspend fun systemStatus(ctx: Context): ProbeResult {
        val metrics = mutableListOf<com.vicinityprobe.model.Metric>()
        metrics.add(metric("cores", Labels.CPU_CORES, Runtime.getRuntime().availableProcessors().toString(), primary = true))
        val freqs = readCpuFreqs()
        metrics.add(metric("cpu_freq", Labels.CPU_FREQ, freqs))
        val usage = cpuUsagePct()
        metrics.add(metric("cpu_usage", Labels.CPU_USAGE, "${fmt(usage)}%"))
        val load = readLoadAvg()
        if (load != null) metrics.add(metric("load", Labels.LOAD_AVG, load))

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            metrics.add(metric("mem_total", Labels.MEM_TOTAL, bytesG(mi.totalMem)))
            metrics.add(metric("mem_avail", Labels.MEM_AVAIL, bytesG(mi.availMem), primary = true))
            metrics.add(metric("low_mem", Labels.LOW_MEM, if (mi.lowMemory) bil("是", "Yes") else bil("否", "No")))
        } catch (_: Throwable) {}

        try {
            val data = File(Environment.getDataDirectory().path)
            val stat = android.os.StatFs(data.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            metrics.add(metric("storage_int", Labels.STORAGE_INT, "总 ${bytesG(total)} / 可用 ${bytesG(avail)}"))
        } catch (_: Throwable) {}
        try {
            val ext = File(Environment.getExternalStorageDirectory().path)
            val stat = android.os.StatFs(ext.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            metrics.add(metric("storage_ext", Labels.STORAGE_EXT, "总 ${bytesG(total)} / 可用 ${bytesG(avail)}"))
        } catch (_: Throwable) {}

        val thermal = readThermal()
        metrics.add(metric("thermal", Labels.THERMAL, thermal))

        return resultBuilder("system", Groups.DEVICE, Labels.SYSTEM, ProbeStatus.OK, metrics = metrics)
    }

    private fun fmtHms(ms: Long): String {
        val s = ms / 1000
        return "${s / 86400}d ${(s % 86400) / 3600}h ${(s % 3600) / 60}m ${s % 60}s"
    }

    private fun bytesG(b: Long): String = String.format(Locale.US, "%.1f GB", b / 1e9)

    private fun readCpuFreqs(): String {
        return try {
            val cores = Runtime.getRuntime().availableProcessors()
            val parts = (0 until cores).map { core ->
                val maxF = File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }
                    ?.readText()?.trim()
                if (maxF != null) "${core}:${(maxF.toLongOrNull() ?: 0) / 1000}MHz" else "$core:-"
            }
            parts.joinToString(", ")
        } catch (_: Throwable) { bil("不可读", "Unreadable") }
    }

    private suspend fun cpuUsagePct(): Double {
        data class CpuSample(val total: Long, val idle: Long)
        fun sample(): CpuSample {
            val lines = File("/proc/stat").readLines()
            val cpu = lines.firstOrNull { it.startsWith("cpu ") } ?: return CpuSample(0, 0)
            val parts = cpu.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return CpuSample(0, 0)
            val idle = parts[3] + parts.getOrElse(4) { 0 } // idle + iowait
            val total = parts.sum()
            return CpuSample(total, idle)
        }
        return withContext(Dispatchers.IO) {
            try {
                val a = sample()
                delay(600)
                val b = sample()
                if (b.total - a.total <= 0) 0.0 else (1.0 - (b.idle - a.idle).toDouble() / (b.total - a.total)) * 100
            } catch (_: Throwable) { -1.0 }
        }
    }

    private fun readLoadAvg(): String? {
        return try {
            val f = File("/proc/loadavg")
            if (f.exists()) f.readText().trim().split(" ").take(3).joinToString(" ") else null
        } catch (_: Throwable) { null }
    }

    private fun readThermal(): String {
        return try {
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
            if (readable.isEmpty()) bil("不可读(无权限)", "Unreadable") else readable.take(8).joinToString(", ")
        } catch (_: Throwable) { bil("不可读(无权限)", "Unreadable") }
    }
}
