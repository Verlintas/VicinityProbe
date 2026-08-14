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

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.ChannelStats
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport
import com.vicinityprobe.model.domain.SeriesPt
import com.vicinityprobe.model.domain.SpectrumResult
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

/** 多探测项采样器:一次注册全部传感器,产出多条 Measurement */
interface BatchSampler {
    val specs: List<ProbeSpec>
    suspend fun run(ctx: Context, session: SessionContext): List<Measurement>
}

/** 传感器类型 → 目录规范映射(与 SDK 36 移除的常量兼容:运行时字符串匹配) */
private data class SensorBinding(
    val spec: ProbeSpec,
    val androidType: Int? = null,       // 仍公开的 TYPE_* 常量
    val stringType: String? = null,     // 已移除常量的传感器用 "android.sensor.xxx"
)

object RemovedSensorTypeStrings {
    const val ACTIVITY = "android.sensor.activity"
    const val DEVICE_ORIENTATION = "android.sensor.device_orientation"
    const val PICK_UP = "android.sensor.pick_up_gesture"
    const val WRIST_TILT = "android.sensor.wrist_tilt_gesture"
    const val WAKE = "android.sensor.wake_gesture"
    const val GLANCE = "android.sensor.glance_gesture"
    const val TILT = "android.sensor.tilt_detector"
    const val SHAKE = "android.sensor.shake"
    const val FLIP = "android.sensor.flip"
    const val FREE_FALL = "android.sensor.free_fall"
}

class SensorBatchSampler(override val specs: List<ProbeSpec>) : BatchSampler {

    private fun bindings(manager: SensorManager): List<Pair<SensorBinding, Sensor>> {
        val allSensors = manager.getSensorList(Sensor.TYPE_ALL)
        val byString = allSensors.associateBy { it.stringType }
        val byType = allSensors.associateBy { it.type }
        val out = ArrayList<Pair<SensorBinding, Sensor>>()
        for (spec in specs) {
            val sensor = when {
                spec.id == "sensor.activity" -> byString[RemovedSensorTypeStrings.ACTIVITY]
                spec.id == "sensor.device_orientation" -> byString[RemovedSensorTypeStrings.DEVICE_ORIENTATION]
                spec.id == "sensor.pick_up" -> byString[RemovedSensorTypeStrings.PICK_UP]
                spec.id == "sensor.wrist_tilt" -> byString[RemovedSensorTypeStrings.WRIST_TILT]
                spec.id == "sensor.wake" -> byString[RemovedSensorTypeStrings.WAKE]
                spec.id == "sensor.glance" -> byString[RemovedSensorTypeStrings.GLANCE]
                spec.id == "sensor.tilt" -> byString[RemovedSensorTypeStrings.TILT]
                spec.id == "sensor.shake" -> byString[RemovedSensorTypeStrings.SHAKE]
                spec.id == "sensor.flip" -> byString[RemovedSensorTypeStrings.FLIP]
                spec.id == "sensor.free_fall" -> byString[RemovedSensorTypeStrings.FREE_FALL]
                else -> byType[androidType(spec.id)]
            }
            if (sensor != null) {
                out.add(SensorBinding(spec, sensor.type, sensor.stringType) to sensor)
            }
        }
        return out
    }

    private fun androidType(id: String): Int = when (id) {
        "sensor.accelerometer" -> Sensor.TYPE_ACCELEROMETER
        "sensor.accelerometer_uncal" -> Sensor.TYPE_ACCELEROMETER_UNCALIBRATED
        "sensor.gyroscope" -> Sensor.TYPE_GYROSCOPE
        "sensor.gyroscope_uncal" -> Sensor.TYPE_GYROSCOPE_UNCALIBRATED
        "sensor.magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
        "sensor.magnetometer_uncal" -> Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
        "sensor.gravity" -> Sensor.TYPE_GRAVITY
        "sensor.linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
        "sensor.rotation_vector" -> Sensor.TYPE_ROTATION_VECTOR
        "sensor.game_rotation_vector" -> Sensor.TYPE_GAME_ROTATION_VECTOR
        "sensor.geomagnetic_rotation" -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        "sensor.orientation" -> Sensor.TYPE_ORIENTATION
        "sensor.light" -> Sensor.TYPE_LIGHT
        "sensor.proximity" -> Sensor.TYPE_PROXIMITY
        "sensor.pressure" -> Sensor.TYPE_PRESSURE
        "sensor.humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
        "sensor.temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
        "sensor.step_counter" -> Sensor.TYPE_STEP_COUNTER
        "sensor.step_detector" -> Sensor.TYPE_STEP_DETECTOR
        "sensor.significant_motion" -> Sensor.TYPE_SIGNIFICANT_MOTION
        "sensor.heart_rate" -> Sensor.TYPE_HEART_RATE
        "sensor.heart_beat" -> Sensor.TYPE_HEART_BEAT
        "sensor.offbody" -> Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT
        else -> -1
    }

    private fun permissionOf(id: String): String? = when (id) {
        "sensor.step_counter", "sensor.step_detector", "sensor.significant_motion", "sensor.activity" ->
            android.Manifest.permission.ACTIVITY_RECOGNITION
        "sensor.heart_rate", "sensor.heart_beat", "sensor.offbody" ->
            android.Manifest.permission.BODY_SENSORS
        else -> null
    }

    override suspend fun run(ctx: Context, session: SessionContext): List<Measurement> {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val startMs = session.startRealtimeMs
        val results = ArrayList<Measurement>()

        val bound = bindings(manager)
        if (bound.isEmpty()) {
            // 全部无硬件
            return specs.map { spec ->
                Measurement(
                    spec = spec, status = QualityLevels.CODE_NO_HARDWARE,
                    quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_HARDWARE, "设备上没有这个传感器|Sensor not present"),
                )
            }
        }

        val permissionDenied = ArrayList<String>()
        val bindList = bound.filter { (b, _) ->
            val perm = permissionOf(b.spec.id)
            if (perm != null && ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionDenied.add(b.spec.id)
                false
            } else true
        }
        val permResults = permissionDenied.map { id ->
            val spec = specs.first { it.id == id }
            Measurement(
                spec = spec, status = QualityLevels.CODE_PERMISSION_DENIED,
                quality = QualityReport(QualityLevel.FAILED, QualityLevels.CODE_PERMISSION_DENIED, "需要权限|Permission required: $id"),
            )
        }
        results.addAll(permResults)
        if (bindList.isEmpty()) return results

        // 通道记录器
        data class SpecState(
            val binding: SensorBinding,
            val recorders: Map<String, ChannelRecorder>,
            val startCount: IntArray,          // 步数/触发等
            val activityCounts: HashMap<Int, Int>,
            var lastActivity: Int?,
            var heartReliability: String?,
            var worstAccuracy: Int,
            var nSamples: Int,
            var headingDeg: Double = 0.0,      // 磁力计指南针方位(最近)
        )
        val states = HashMap<Int, SpecState>()
        bindList.forEach { (b, sensor) ->
            val channels = b.spec.sampleChannels.ifEmpty { listOf("value") }
            states[sensor.type] = SpecState(
                binding = b,
                recorders = channels.associateWith { ChannelRecorder(it) },
                startCount = IntArray(2),
                activityCounts = HashMap(),
                lastActivity = null,
                heartReliability = null,
                worstAccuracy = Int.MAX_VALUE,
                nSamples = 0,
            )
        }

        var gravity = floatArrayOf(0f, 0f, 0f)
        val thread = HandlerThread("sensor-sampling")
        thread.start()
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val st = states[e.sensor.type] ?: return
                val t = session.elapsedMs()
                st.nSamples++
                if (e.accuracy < st.worstAccuracy) st.worstAccuracy = e.accuracy
                if (e.sensor.type == Sensor.TYPE_GRAVITY && e.values.size >= 3) {
                    gravity = e.values.copyOf()
                }
                when (st.binding.spec.id) {
                    "sensor.step_counter" -> {
                        if (st.startCount[0] == 0 && e.values.isNotEmpty()) st.startCount[0] = e.values[0].toInt()
                        if (e.values.isNotEmpty()) st.startCount[1] = e.values[0].toInt()
                    }
                    "sensor.activity" -> {
                        if (e.values.isNotEmpty()) {
                            val c = e.values[0].toInt()
                            st.lastActivity = c
                            st.activityCounts[c] = (st.activityCounts[c] ?: 0) + 1
                        }
                    }
                    "sensor.heart_rate" -> {
                        if (e.values.isNotEmpty() && e.values[0] > 0) {
                            st.recorders["value"]?.add(t, e.values[0])
                            if (e.values.size > 1) {
                                st.heartReliability = when (e.values[1].toInt()) {
                                    1 -> "高|High"; 2 -> "中|Medium"; 3 -> "低|Low"
                                    else -> "未知|Unknown"
                                }
                            }
                        }
                    }
                    "sensor.heart_beat" -> {
                        if (e.values.isNotEmpty() && e.values[0] > 0) st.recorders["value"]?.add(t, e.values[0])
                    }
                    "sensor.proximity" -> {
                        if (e.values.isNotEmpty()) st.recorders["value"]?.add(t, e.values[0])
                    }
                    "sensor.magnetometer" -> {
                        val v = e.values
                        if (v.size >= 3) {
                            st.recorders["x"]?.add(t, v[0])
                            st.recorders["y"]?.add(t, v[1])
                            st.recorders["z"]?.add(t, v[2])
                            val mag = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])
                            st.recorders["magnitude"]?.add(t, mag.toFloat())
                            // 指南针方位:重力 + 磁力融合
                            if (gravity.size >= 3 && (gravity[0] != 0f || gravity[1] != 0f || gravity[2] != 0f)) {
                                val rm = FloatArray(9)
                                if (SensorManager.getRotationMatrix(rm, null, gravity, v)) {
                                    val o = FloatArray(3)
                                    SensorManager.getOrientation(rm, o)
                                    var d = Math.toDegrees(o[0].toDouble())
                                    if (d < 0) d += 360
                                    st.headingDeg = d
                                }
                            }
                        }
                    }
                    else -> {
                        val v = e.values
                        if (v.size >= 3 && st.binding.spec.sampleChannels.contains("x")) {
                            st.recorders["x"]?.add(t, v[0])
                            st.recorders["y"]?.add(t, v[1])
                            st.recorders["z"]?.add(t, v[2])
                            val mag = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])
                            st.recorders["magnitude"]?.add(t, mag.toFloat())
                        } else if (v.isNotEmpty()) {
                            st.recorders["value"]?.add(t, v[0])
                        }
                    }
                }
                session.live.set(st.binding.spec.id, st.binding.spec.name.split("|").last(), "${st.nSamples} samples")
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        // 注册:按规范标称采样率设置延迟
        bindList.forEach { (b, sensor) ->
            val delayUs = if (b.spec.nominalRateHz > 0) (1e6 / b.spec.nominalRateHz).toInt().coerceAtLeast(5_000) else 200_000
            try {
                manager.registerListener(listener, sensor, delayUs, handler)
            } catch (_: Throwable) {}
        }

        while (kotlin.coroutines.coroutineContext.isActive && SystemClockCompat.elapsedRealtime() < session.deadlineRealtimeMs) {
            kotlinx.coroutines.delay(200)
        }
        manager.unregisterListener(listener)
        thread.quitSafely()

        // 组装测量结果
        for ((sensor, st) in states) {
            val spec = st.binding.spec
            val elapsedSec = session.elapsedMs().toDouble() / 1000.0
            val achieved = if (elapsedSec > 0) st.nSamples / elapsedSec else 0.0
            val nominal = spec.nominalRateHz
            val coverage = if (nominal > 0) achieved / nominal * 100 else 100.0
            val stats = st.recorders.mapValues { (_, r) -> ChannelStats.compute(r.snapshot().map { it.second }.toFloatArray(), spec.unit.symbol) }

            val files = if (spec.keepRawSamples && st.nSamples > 0) {
                val dir = java.io.File(session.samplesDir, spec.id)
                dir.mkdirs()
                st.recorders.forEach { (ch, r) ->
                    if (r.size() > 0) r.writeCsv(java.io.File(dir, "channel_$ch.csv"), "t_ms,$ch")
                }
                spec.id
            } else null

            val attrs = LinkedHashMap<String, String>()
            attrs["samples"] = st.nSamples.toString()
            attrs["achieved_rate_hz"] = String.format("%.2f", achieved)
            attrs["sensor_accuracy"] = accuracyName(st.worstAccuracy)
            when (spec.id) {
                "sensor.step_counter" -> {
                    attrs["steps_delta"] = (st.startCount[1] - st.startCount[0]).coerceAtLeast(0).toString()
                    attrs["steps_total"] = st.startCount[1].toString()
                }
                "sensor.activity" -> {
                    attrs["activity"] = activityName(st.lastActivity)
                    attrs["activity_distribution"] = st.activityCounts.entries.joinToString(",") { "${activityName(it.key)}:${it.value}" }
                }
                "sensor.heart_rate" -> st.heartReliability?.let { attrs["reliability"] = it }
                "sensor.magnetometer" -> {
                    attrs["heading_deg"] = String.format("%.1f", st.headingDeg)
                    val avgMag = st.recorders["magnitude"]?.snapshot()?.map { it.second.toDouble() }?.average()
                    if (avgMag != null) {
                        attrs["radiation_level"] = when {
                            avgMag < 5 -> "very-low"
                            avgMag < 20 -> "low"
                            avgMag < 50 -> "moderate"
                            avgMag < 100 -> "elevated"
                            else -> "high"
                        }
                    }
                }
                "sensor.proximity" -> {
                    val last = st.recorders["value"]?.snapshot()?.lastOrNull()?.second
                    if (last != null) attrs["covered"] = if (last < 4.0f) bil("遮挡", "Covered") else bil("未遮挡", "Clear")
                }
            }

            val q = qualityOf(spec, st.nSamples, nominal, coverage, st.worstAccuracy)
            results.add(
                Measurement(
                    spec = spec,
                    status = if (q.level == QualityLevel.FAILED) q.code else QualityLevels.CODE_OK,
                    stats = stats,
                    attributes = attrs,
                    quality = q,
                    samplesFile = files,
                    series = st.recorders.mapValues { (k, r) -> r.decimate() }.filterValues { it.isNotEmpty() },
                    spectrum = null,
                ),
            )
        }
        return results
    }

    private fun qualityOf(spec: ProbeSpec, n: Int, nominal: Double, coverage: Double, worstAccuracy: Int): QualityReport {
        if (n == 0) {
            return QualityReport(QualityLevel.FAILED, QualityLevels.CODE_NO_DATA, "没有采到样本|No samples")
        }
        if (nominal > 0 && n < (spec.nominalRateHz * 2).toInt()) {
            return QualityReport(QualityLevel.DEGRADED, QualityLevels.CODE_INSUFFICIENT_SAMPLES,
                "样本太少|Insufficient samples", coveragePct = coverage, sampleCount = n, achievedRateHz = nominal * coverage / 100, nominalRateHz = nominal)
        }
        val level = when {
            coverage >= 80 && worstAccuracy <= 3 -> QualityLevel.EXCELLENT
            coverage >= 50 -> QualityLevel.GOOD
            coverage >= 10 -> QualityLevel.DEGRADED
            else -> QualityLevel.FAILED
        }
        val code = if (worstAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) QualityLevels.CODE_SENSOR_UNCALIBRATED
        else if (coverage < 50) QualityLevels.CODE_SAMPLE_RATE_LOW
        else QualityLevels.CODE_OK
        return QualityReport(level, code, "", coveragePct = coverage.coerceAtMost(100.0), sampleCount = n,
            achievedRateHz = nominal * coverage / 100, nominalRateHz = nominal)
    }

    private fun accuracyName(a: Int): String = when (a) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高|High"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中|Medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低|Low"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "不可靠|Unreliable"
        else -> "未知|Unknown"
    }

    private fun activityName(code: Int?): String = when (code) {
        1 -> "乘车/驾车|In vehicle"
        2 -> "骑行|On bicycle"
        3 -> "静止|Stationary"
        4 -> "未知|Unknown"
        5 -> "移动中|Moving"
        7 -> "步行|Walking"
        8 -> "跑步|Running"
        9 -> "倾斜|Tilting"
        else -> "unknown($code)"
    }
}
