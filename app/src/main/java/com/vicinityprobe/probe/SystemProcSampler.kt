package com.vicinityprobe.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.BatteryManager
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
import java.util.Locale

/** 网络连接表:解析 /proc/net/tcp + tcp6 */
class ProcNetConnSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("proc_net_conn")!!

    private val states = mapOf(
        "01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV", "04" to "FIN_WAIT1",
        "05" to "FIN_WAIT2", "06" to "TIME_WAIT", "07" to "CLOSE", "08" to "CLOSE_WAIT",
        "09" to "LAST_ACK", "0A" to "LISTEN", "0B" to "CLOSING",
    )

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        val stateCounts = HashMap<String, Int>()
        val rows = ArrayList<String>()
        var readOk = false
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                val lines = File(path).readLines()
                readOk = readOk || lines.size > 1
                lines.drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 10) {
                        val state = states[parts[3]] ?: parts[3]
                        stateCounts[state] = (stateCounts[state] ?: 0) + 1
                        if (state == "ESTABLISHED") {
                            val local = parts[1]
                            val remote = parts[2]
                            val uid = parts[7]
                            rows.add("$local -> $remote uid=$uid")
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (!readOk) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "Android 10+ 多数设备禁止应用读取 /proc/net 连接表|/proc/net connection table is restricted on Android 10+")
        }
        attrs["connection_count"] = stateCounts.values.sum().toString()
        attrs["states"] = stateCounts.entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        if (rows.isNotEmpty()) attrs["established"] = rows.take(30).joinToString("\n")
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = rows.size))
    }
}

/** 内核内存明细:解析 /proc/meminfo */
class ProcMeminfoSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("proc_meminfo")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            val mem = HashMap<String, Long>()
            File("/proc/meminfo").readLines().forEach { line ->
                val parts = line.split(":")
                if (parts.size == 2) {
                    val kb = parts[1].trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                    if (kb != null) mem[parts[0].trim()] = kb
                }
            }
            fun gb(key: String): String? = mem[key]?.let { String.format(Locale.US, "%.2f GB", it / 1024.0 / 1024.0) }
            attrs["total"] = gb("MemTotal") ?: "?"
            attrs["free"] = gb("MemFree") ?: "?"
            attrs["available"] = gb("MemAvailable") ?: "?"
            attrs["buffers"] = gb("Buffers") ?: "?"
            attrs["cached"] = gb("Cached") ?: "?"
            attrs["swap_total"] = gb("SwapTotal") ?: "?"
            attrs["swap_free"] = gb("SwapFree") ?: "?"
            attrs["dirty"] = gb("Dirty") ?: "?"
            attrs["page_tables"] = gb("PageTables") ?: "?"
            attrs["committed_as"] = gb("Committed_AS") ?: "?"
            attrs["kernel_stack"] = gb("KernelStack") ?: "?"
            attrs["writeback"] = gb("Writeback") ?: "?"
            attrs["anon_pages"] = gb("AnonPages") ?: "?"
            attrs["mapped"] = gb("Mapped") ?: "?"
            attrs["shmem"] = gb("Shmem") ?: "?"
        } catch (_: Throwable) {}
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 逐核 CPU 使用率:每核心 /proc/stat 差分 */
class PerCoreCpuSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("cpu_per_core")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val cores = Runtime.getRuntime().availableProcessors()
        val recs = (0 until cores).map { ChannelRecorder("cpu$it") }
        val first = coreStats()
        if (first == null) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "/proc/stat 在本设备上禁止应用读取|/proc/stat is restricted on this device")
        }
        var last = first
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            val now = coreStats()
            if (last != null && now != null && now.size == cores) {
                val t = session.elapsedMs()
                for (i in 0 until cores) {
                    val total = (now[i].first - last[i].first).toDouble()
                    val idle = (now[i].second - last[i].second).toDouble()
                    if (total > 0) recs[i].add(t, ((1 - idle / total) * 100).toFloat())
                }
            }
            last = now
            delay(500)
        }
        val attrs = LinkedHashMap<String, String>()
        val stats = LinkedHashMap<String, ChannelStats>()
        recs.forEachIndexed { i, r ->
            val s = ChannelStats.compute(r.snapshot().map { it.second }.toFloatArray(), "%")
            stats["cpu$i"] = s
            attrs["cpu$i"] = String.format("%.1f%%", s.mean)
        }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs, stats = stats,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = recs.first().size()),
            series = stats.mapKeys { it.key }.mapValues { (k, _) -> recs[k.removePrefix("cpu").toInt()].decimate() }
                .filterValues { it.isNotEmpty() },
        )
    }

    private fun coreStats(): List<Pair<Long, Long>>? {
        return try {
            File("/proc/stat").readLines()
                .filter { it.startsWith("cpu") && it[3] != ' ' }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (parts.size < 4) null else (parts.sum() to (parts[3] + parts.getOrElse(4) { 0 }))
                }
        } catch (_: Throwable) { null }
    }
}

/** 磁盘 IO 统计:/proc/diskstats 差分 */
class DiskStatsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("disk_stats")!!

    private data class DiskSample(val reads: Long, val writes: Long, val sectors: Long)

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        var last = sample()
        val readRec = ChannelRecorder("reads")
        val writeRec = ChannelRecorder("writes")
        val sectorRec = ChannelRecorder("sectors")
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            val now = sample()
            if (last != null && now != null) {
                val t = session.elapsedMs()
                readRec.add(t, (now.reads - last.reads).toFloat())
                writeRec.add(t, (now.writes - last.writes).toFloat())
                sectorRec.add(t, (now.sectors - last.sectors).toFloat())
            }
            last = now
            delay(1000)
        }
        val attrs = LinkedHashMap<String, String>()
        val finalSample = sample()
        if (finalSample == null) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "Android 上 /proc/diskstats 通常禁止应用读取|/proc/diskstats is usually restricted for apps")
        }
        attrs["interval_s"] = "1"
        attrs["read_ops_total"] = finalSample.reads.toString()
        attrs["write_ops_total"] = finalSample.writes.toString()
        val stats = LinkedHashMap<String, ChannelStats>()
        if (readRec.size() > 0) {
            stats["reads_per_s"] = ChannelStats.compute(readRec.snapshot().map { it.second }.toFloatArray(), "ops/s")
            stats["writes_per_s"] = ChannelStats.compute(writeRec.snapshot().map { it.second }.toFloatArray(), "ops/s")
            stats["sectors_per_s"] = ChannelStats.compute(sectorRec.snapshot().map { it.second }.toFloatArray(), "sectors/s")
        }
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs, stats = stats,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = readRec.size()),
        )
    }

    private fun sample(): DiskSample? {
        return try {
            var reads = 0L; var writes = 0L; var sectors = 0L
            var found = false
            File("/proc/diskstats").readLines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                // 格式: 主:次 设备名 读完成 读合并 读扇区 读耗时ms 写完成 写合并 写扇区 写耗时ms ...
                val isNew = parts.size >= 18 && parts[2].toLongOrNull() == null   // 新版带设备名
                val base = if (isNew) 2 else 0
                if (parts.size >= base + 12) {
                    val readsC = parts.getOrNull(base + 2)?.toLongOrNull()
                    val writesC = parts.getOrNull(base + 6)?.toLongOrNull()
                    val sectorsR = parts.getOrNull(base + 4)?.toLongOrNull()
                    val sectorsW = parts.getOrNull(base + 8)?.toLongOrNull()
                    if (readsC != null && writesC != null) {
                        found = true
                        reads += readsC; writes += writesC
                        sectors += (sectorsR ?: 0) + (sectorsW ?: 0)
                    }
                }
            }
            if (!found) null else DiskSample(reads, writes, sectors)
        } catch (_: Throwable) { null }
    }
}

/** 开机与运行统计 */
class BootStatsSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("proc_uptime")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val attrs = LinkedHashMap<String, String>()
        try {
            val uptime = File("/proc/uptime").readText().trim().split(" ")
            if (uptime.size >= 2) {
                attrs["uptime_s"] = String.format("%.0f", uptime[0].toDouble())
                attrs["idle_time_s"] = String.format("%.0f", uptime[1].toDouble())
                val idleRatio = if (uptime[0].toDouble() > 0) uptime[1].toDouble() / uptime[0].toDouble() * 100 else 0.0
                attrs["idle_ratio_pct"] = String.format("%.1f", idleRatio)
            }
        } catch (_: Throwable) {}
        try {
            val hostname = File("/proc/sys/kernel/hostname").readText().trim()
            attrs["hostname"] = hostname
        } catch (_: Throwable) {}
        try {
            val osrelease = File("/proc/sys/kernel/osrelease").readText().trim()
            attrs["osrelease"] = osrelease
        } catch (_: Throwable) {}
        try {
            val ostype = File("/proc/sys/kernel/ostype").readText().trim()
            attrs["ostype"] = ostype
        } catch (_: Throwable) {}
        try {
            val entropy = File("/proc/sys/kernel/random/entropy_avail").readText().trim()
            attrs["entropy_avail"] = entropy
        } catch (_: Throwable) {}
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = 1))
    }
}

/** 传感器校准分析:未校准 vs 校准版本差值统计(加速度/陀螺/磁力) */
class SensorCalibSampler : BatchSampler {
    override val specs: List<ProbeSpec> = listOf(
        ProbeCatalog.byId("sensor_calib")!!,
    )

    override suspend fun run(ctx: Context, session: SessionContext): List<Measurement> {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        data class PairSpec(val id: String, val calibType: Int, val uncalType: Int)
        val pairs = listOf(
            PairSpec("accel", Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED),
            PairSpec("gyro", Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
            PairSpec("mag", Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
        )
        data class DiffAcc(val x: WelfordDiff = WelfordDiff(), val y: WelfordDiff = WelfordDiff(), val z: WelfordDiff = WelfordDiff())
        val diffs = HashMap<String, DiffAcc>()
        val magOffsets = ArrayList<Float>()

        val thread = HandlerThread("calib-sampling")
        thread.start()
        val handler = Handler(thread.looper)
        var lastCalib: HashMap<String, FloatArray>? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = session.elapsedMs()
                val p = pairs.firstOrNull { e.sensor.type == it.calibType || e.sensor.type == it.uncalType } ?: return
                val diff = diffs.getOrPut(p.id) { DiffAcc() }
                if (e.sensor.type == p.calibType) {
                    lastCalib = (lastCalib ?: HashMap()).also { it[p.id] = e.values.copyOf() }
                } else {
                    val calib = lastCalib?.get(p.id) ?: return
                    if (e.values.size >= 3 && calib.size >= 3) {
                        diff.x.add(calib[0] - e.values[0])
                        diff.y.add(calib[1] - e.values[1])
                        diff.z.add(calib[2] - e.values[2])
                        if (p.id == "mag") {
                            val offset = kotlin.math.sqrt(
                                (calib[0] - e.values[0]).toDouble() * (calib[0] - e.values[0]) +
                                    (calib[1] - e.values[1]).toDouble() * (calib[1] - e.values[1]) +
                                    (calib[2] - e.values[2]).toDouble() * (calib[2] - e.values[2]),
                            )
                            magOffsets.add(offset.toFloat())
                        }
                    }
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        var registered = false
        for (p in pairs) {
            val calibSensor = manager.getDefaultSensor(p.calibType)
            val uncalSensor = manager.getDefaultSensor(p.uncalType)
            if (calibSensor != null) {
                try { manager.registerListener(listener, calibSensor, SensorManager.SENSOR_DELAY_NORMAL, handler); registered = true } catch (_: Throwable) {}
            }
            if (uncalSensor != null) {
                try { manager.registerListener(listener, uncalSensor, SensorManager.SENSOR_DELAY_NORMAL, handler); registered = true } catch (_: Throwable) {}
            }
        }
        if (!registered) {
            thread.quitSafely()
            return listOf(Measurement(specs[0], QualityLevels.CODE_NO_HARDWARE,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_HARDWARE, "缺少未校准传感器|No uncalibrated sensors")))
        }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) { delay(200) }
        manager.unregisterListener(listener)
        thread.quitSafely()

        val attrs = LinkedHashMap<String, String>()
        var samples = 0
        for ((id, d) in diffs) {
            val n = d.x.n.coerceAtLeast(1)
            samples += n
            val xAvg = d.x.mean(); val yAvg = d.y.mean(); val zAvg = d.z.mean()
            val bias = kotlin.math.sqrt(xAvg * xAvg + yAvg * yAvg + zAvg * zAvg)
            attrs["${id}_bias"] = String.format("%.4f", bias)
            attrs["${id}_bias_xyz"] = String.format("%.4f,%.4f,%.4f", xAvg, yAvg, zAvg)
            attrs["${id}_stddev"] = String.format("%.4f", (d.x.stddev() + d.y.stddev() + d.z.stddev()) / 3)
            attrs["${id}_samples"] = n.toString()
        }
        if (magOffsets.isNotEmpty()) {
            val s = ChannelStats.compute(magOffsets.toFloatArray(), "µT")
            attrs["mag_hard_iron_offset_ut"] = String.format("%.2f", s.mean)
            attrs["mag_offset_p99_ut"] = String.format("%.2f", s.p99)
        }
        return listOf(Measurement(
            spec = specs[0], status = if (samples > 0) QualityLevels.CODE_OK else QualityLevels.CODE_NO_DATA,
            attributes = attrs,
            quality = QualityReport(
                level = if (samples > 50) QualityLevel.EXCELLENT else QualityLevel.GOOD,
                code = QualityLevels.CODE_OK, sampleCount = samples,
                achievedRateHz = samples.toDouble() / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 50.0,
            ),
        ))
    }
}

class WelfordDiff {
    var n: Int = 0
        private set
    private var mean = 0.0
    private var m2 = 0.0

    fun add(v: Float) {
        n++
        val d = v - mean
        mean += d / n
        m2 += d * (v - mean)
    }

    fun mean(): Double = if (n == 0) 0.0 else mean
    fun stddev(): Double = if (n == 0) 0.0 else kotlin.math.sqrt(m2 / n)
}

/** 电池放电速率:会话内功率时序 */
class BatteryDrainSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("battery_drain")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val powerRec = ChannelRecorder("power_mw")
        var samples = 0
        // 电压只读一次(重复 registerReceiver 在部分系统返回 null)
        val intent = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (voltage == null || voltage <= 0) {
            return failedMeasurement(spec, QualityLevels.CODE_ACQUISITION_ERROR, "读取电池电压失败|Battery voltage unreadable")
        }
        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            try {
                val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (current != Int.MIN_VALUE) {
                    val powerMw = current.toDouble() / 1000.0 * voltage / 1000.0 // µA * V / 1000 = mW
                    powerRec.add(session.elapsedMs(), powerMw.toFloat())
                    samples++
                    session.live.set("battery_drain", "power mW", String.format("%.0f", powerMw))
                }
            } catch (_: Throwable) {}
            delay(500)
        }
        if (samples == 0) {
            return failedMeasurement(spec, QualityLevels.CODE_NO_DATA, "无法读取实时电流|Live current unavailable")
        }
        val stats = ChannelStats.compute(powerRec.snapshot().map { it.second }.toFloatArray(), "mW")
        val attrs = LinkedHashMap<String, String>()
        attrs["power_mean_mw"] = String.format("%.0f", stats.mean)
        attrs["power_min_mw"] = String.format("%.0f", stats.min)
        attrs["power_max_mw"] = String.format("%.0f", stats.max)
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (capacity != Int.MIN_VALUE && stats.mean > 0) {
            val remainingMwh = capacity / 1000.0
            attrs["est_remaining_mwh"] = String.format("%.0f", remainingMwh)
            attrs["est_autonomy_hours"] = String.format("%.1f", remainingMwh / stats.mean)
        }
        attrs["sample_count"] = samples.toString()
        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK, attributes = attrs,
            stats = mapOf("power_mw" to stats),
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = samples,
                achievedRateHz = samples.toDouble() / (session.elapsedMs().toDouble() / 1000), nominalRateHz = 2.0),
            series = mapOf("power_mw" to powerRec.decimate()),
        )
    }
}

/** WiFi 信道占用分析:信道分布 + 拥挤度 */
class WifiChannelSampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("wifi_channel")!!

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        try { wifi.startScan() } catch (_: Throwable) {}
        delay(1500)
        val results = try { wifi.scanResults } catch (_: Throwable) { emptyList() }
        if (results.isEmpty()) {
            return failedMeasurement(spec, QualityLevels.CODE_THROTTLED, "没有扫描结果(系统节流)|No results (throttled)")
        }
        val byChannel = HashMap<Int, MutableList<Int>>()
        var band24 = 0; var band5 = 0; var band6 = 0
        results.forEach { r ->
            val ch = ChannelOf.of(r.frequency)
            byChannel.getOrPut(ch) { ArrayList() }.add(r.level)
            when {
                r.frequency in 2412..2484 -> band24++
                r.frequency in 5170..5825 -> band5++
                else -> band6++
            }
        }
        val attrs = LinkedHashMap<String, String>()
        attrs["ap_total"] = results.size.toString()
        attrs["band_2_4g"] = band24.toString()
        attrs["band_5g"] = band5.toString()
        attrs["band_6g"] = band6.toString()
        val channelDetail = byChannel.entries.sortedBy { it.key }.joinToString("\n") { (ch, levels) ->
            val avg = levels.average()
            val density = if (ch in 1..13) levels.size else levels.size / 4
            "ch$ch: ${levels.size}AP avg=${"%.0f".format(avg)}dBm 拥挤度=$density"
        }
        attrs["channels"] = channelDetail
        val busiest = byChannel.entries.maxByOrNull { it.value.size }
        busiest?.let { attrs["busiest_channel"] = "ch${it.key} (${it.value.size} APs)" }
        return okMeasurement(spec, attrs,
            quality = QualityReport(QualityLevel.EXCELLENT, QualityLevels.CODE_OK, "", sampleCount = results.size))
    }
}
