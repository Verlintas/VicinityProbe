package com.vicinityprobe.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlin.math.abs
import kotlin.math.sqrt

enum class SensorKind { VEC3, SCALAR, STEP, ACTIVITY, HEART, HEARTBEAT, ROTATION, MAGNETIC, PROXIMITY, GESTURE, ORIENTATION, SIGNIFICANT }

data class SensorSpec(
    val id: String,
    val name: L,
    val kind: SensorKind,
    val permission: String? = null,
    val chart: Boolean = false,
    val type: Int? = null,
    val stringType: String? = null,
)

object RemovedSensorTypes {
    // SDK 36 已从公开 API 移除的传感器类型,使用稳定的类型字符串查找
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

object SensorSpecs {
    val all: List<SensorSpec> = listOf(
        SensorSpec("sensor.accelerometer", Labels.ACCEL, SensorKind.VEC3, chart = true, type = Sensor.TYPE_ACCELEROMETER),
        SensorSpec("sensor.accelerometer_uncal", Labels.ACCEL_UNCAL, SensorKind.VEC3, type = Sensor.TYPE_ACCELEROMETER_UNCALIBRATED),
        SensorSpec("sensor.gyroscope", Labels.GYRO, SensorKind.VEC3, type = Sensor.TYPE_GYROSCOPE),
        SensorSpec("sensor.gyroscope_uncal", Labels.GYRO_UNCAL, SensorKind.VEC3, type = Sensor.TYPE_GYROSCOPE_UNCALIBRATED),
        SensorSpec("sensor.magnetometer", Labels.MAG, SensorKind.MAGNETIC, type = Sensor.TYPE_MAGNETIC_FIELD),
        SensorSpec("sensor.magnetometer_uncal", Labels.MAG_UNCAL, SensorKind.VEC3, type = Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED),
        SensorSpec("sensor.gravity", Labels.GRAVITY, SensorKind.VEC3, type = Sensor.TYPE_GRAVITY),
        SensorSpec("sensor.linear_acceleration", Labels.LINEAR_ACC, SensorKind.VEC3, type = Sensor.TYPE_LINEAR_ACCELERATION),
        SensorSpec("sensor.rotation_vector", Labels.ROTATION, SensorKind.ROTATION, type = Sensor.TYPE_ROTATION_VECTOR),
        SensorSpec("sensor.game_rotation_vector", Labels.GAME_ROT, SensorKind.ROTATION, type = Sensor.TYPE_GAME_ROTATION_VECTOR),
        SensorSpec("sensor.geomagnetic_rotation", Labels.GEO_ROT, SensorKind.ROTATION, type = Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR),
        SensorSpec("sensor.orientation", Labels.ORIENTATION, SensorKind.ORIENTATION, type = Sensor.TYPE_ORIENTATION),
        SensorSpec("sensor.light", Labels.LIGHT, SensorKind.SCALAR, chart = true, type = Sensor.TYPE_LIGHT),
        SensorSpec("sensor.proximity", Labels.PROXIMITY, SensorKind.PROXIMITY, type = Sensor.TYPE_PROXIMITY),
        SensorSpec("sensor.pressure", Labels.PRESSURE, SensorKind.SCALAR, chart = true, type = Sensor.TYPE_PRESSURE),
        SensorSpec("sensor.humidity", Labels.HUMIDITY, SensorKind.SCALAR, chart = true, type = Sensor.TYPE_RELATIVE_HUMIDITY),
        SensorSpec("sensor.temperature", Labels.TEMPERATURE, SensorKind.SCALAR, chart = true, type = Sensor.TYPE_AMBIENT_TEMPERATURE),
        SensorSpec("sensor.step_counter", Labels.STEP_COUNTER, SensorKind.STEP, permission = Manifest.permission.ACTIVITY_RECOGNITION, type = Sensor.TYPE_STEP_COUNTER),
        SensorSpec("sensor.step_detector", Labels.STEP_DETECTOR, SensorKind.GESTURE, permission = Manifest.permission.ACTIVITY_RECOGNITION, type = Sensor.TYPE_STEP_DETECTOR),
        SensorSpec("sensor.significant_motion", Labels.SIGNIFICANT_MOTION, SensorKind.SIGNIFICANT, permission = Manifest.permission.ACTIVITY_RECOGNITION, type = Sensor.TYPE_SIGNIFICANT_MOTION),
        SensorSpec("sensor.activity", Labels.ACTIVITY, SensorKind.ACTIVITY, permission = Manifest.permission.ACTIVITY_RECOGNITION, stringType = RemovedSensorTypes.ACTIVITY),
        SensorSpec("sensor.heart_rate", Labels.HEART_RATE, SensorKind.HEART, permission = Manifest.permission.BODY_SENSORS, type = Sensor.TYPE_HEART_RATE),
        SensorSpec("sensor.heart_beat", Labels.HEART_BEAT, SensorKind.HEARTBEAT, permission = Manifest.permission.BODY_SENSORS, type = Sensor.TYPE_HEART_BEAT),
        SensorSpec("sensor.device_orientation", Labels.DEVICE_ORIENTATION, SensorKind.GESTURE, stringType = RemovedSensorTypes.DEVICE_ORIENTATION),
        SensorSpec("sensor.pick_up", Labels.PICK_UP, SensorKind.GESTURE, stringType = RemovedSensorTypes.PICK_UP),
        SensorSpec("sensor.shake", Labels.SHAKE, SensorKind.GESTURE, stringType = RemovedSensorTypes.SHAKE),
        SensorSpec("sensor.flip", Labels.FLIP, SensorKind.GESTURE, stringType = RemovedSensorTypes.FLIP),
        SensorSpec("sensor.free_fall", Labels.FREE_FALL, SensorKind.GESTURE, stringType = RemovedSensorTypes.FREE_FALL),
        SensorSpec("sensor.tilt", Labels.TILT, SensorKind.GESTURE, stringType = RemovedSensorTypes.TILT),
        SensorSpec("sensor.wrist_tilt", Labels.WRIST_TILT, SensorKind.GESTURE, stringType = RemovedSensorTypes.WRIST_TILT),
        SensorSpec("sensor.wake", Labels.WAKE, SensorKind.GESTURE, stringType = RemovedSensorTypes.WAKE),
        SensorSpec("sensor.glance", Labels.GLANCE, SensorKind.GESTURE, stringType = RemovedSensorTypes.GLANCE),
        SensorSpec("sensor.offbody", Labels.DEVICE_ORIENTATION, SensorKind.GESTURE, permission = Manifest.permission.BODY_SENSORS, type = Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT),
    )

    fun byId(id: String): SensorSpec? = all.firstOrNull { it.id == id }

    fun lookup(manager: SensorManager, spec: SensorSpec): Sensor? {
        return try {
            spec.type?.let { manager.getDefaultSensor(it) }
                ?: spec.stringType?.let { st ->
                    manager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == st }
                }
        } catch (_: Throwable) { null }
    }
}

object ActivityNames {
    fun name(code: Float): String = when (code.toInt()) {
        1 -> bil("乘车/驾车", "In vehicle")
        2 -> bil("骑行", "On bicycle")
        3 -> bil("静止", "Stationary")
        4 -> bil("未知", "Unknown")
        5 -> bil("移动中", "Moving")
        7 -> bil("步行", "Walking")
        8 -> bil("跑步", "Running")
        9 -> bil("倾斜", "Tilting")
        else -> bil("未知(${code.toInt()})", "Unknown(${code.toInt()})")
    }
}

object MagneticLevels {
    fun classify(microTesla: Double): String = when {
        microTesla < 5 -> bil("极低", "Very low")
        microTesla < 20 -> bil("低", "Low")
        microTesla < 50 -> bil("中等", "Moderate")
        microTesla < 100 -> bil("较高", "Elevated")
        else -> bil("高", "High")
    }
}

interface SensorAcc {
    fun onSample(v: FloatArray, tMs: Long, accuracy: Int)
}

class Vec3Acc(private val spec: SensorSpec) : SensorAcc {
    val x = Welford(); val y = Welford(); val z = Welford(); val mag = Welford()
    val series = if (spec.chart) Series() else null

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.size < 3) return
        x.add(v[0].toDouble()); y.add(v[1].toDouble()); z.add(v[2].toDouble())
        val m = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])
        mag.add(m)
        series?.add(tMs, m)
    }
}

class ScalarAcc(private val spec: SensorSpec) : SensorAcc {
    val w = Welford()
    val series = if (spec.chart) Series() else null

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty()) return
        w.add(v[0].toDouble())
        series?.add(tMs, v[0].toDouble())
    }
}

class StepAcc : SensorAcc {
    var initial: Double? = null
        private set
    var last: Double = 0.0
        private set

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty()) return
        if (initial == null) initial = v[0].toDouble()
        last = v[0].toDouble()
    }
}

class ActivityAcc : SensorAcc {
    val counts = HashMap<Int, Int>()
    var lastCode: Int? = null
        private set

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty()) return
        val c = v[0].toInt()
        lastCode = c
        counts[c] = (counts[c] ?: 0) + 1
    }
}

class HeartAcc : SensorAcc {
    val bpm = Welford()
    var lastReliability: String? = null
        private set

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty() || v[0] <= 0) return
        bpm.add(v[0].toDouble())
        if (v.size > 1) {
            lastReliability = when (v[1].toInt()) {
                1 -> bil("高", "High")
                2 -> bil("中", "Medium")
                3 -> bil("低", "Low")
                else -> bil("未知", "Unknown")
            }
        }
    }
}

class HeartbeatAcc : SensorAcc {
    val bpm = Welford()

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty() || v[0] <= 0) return
        bpm.add(v[0].toDouble())
    }
}

class RotationAcc : SensorAcc {
    val azimuth = Welford(); val pitch = Welford(); val roll = Welford()
    private val rm = FloatArray(9)
    private val o = FloatArray(3)

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.size < 3) return
        SensorManager.getRotationMatrixFromVector(rm, v)
        SensorManager.getOrientation(rm, o)
        azimuth.add(deg(o[0])); pitch.add(deg(o[1])); roll.add(deg(o[2]))
    }

    private fun deg(r: Float): Double = Math.toDegrees(r.toDouble())
}

class MagneticAcc : SensorAcc {
    val x = Welford(); val y = Welford(); val z = Welford(); val mag = Welford()
    val heading = Welford()

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.size < 3) return
        x.add(v[0].toDouble()); y.add(v[1].toDouble()); z.add(v[2].toDouble())
        val m = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])
        mag.add(m)
        GravityHolder.gravity?.let { g ->
            if (g.size >= 3) {
                val rm = FloatArray(9)
                if (SensorManager.getRotationMatrix(rm, null, g, v)) {
                    val o = FloatArray(3)
                    SensorManager.getOrientation(rm, o)
                    var d = Math.toDegrees(o[0].toDouble())
                    if (d < 0) d += 360
                    heading.add(d)
                }
            }
        }
    }
}

object GravityHolder {
    @Volatile var gravity: FloatArray? = null
}

class ProximityAcc(private val maxRange: Float) : SensorAcc {
    val w = Welford()
    var covered: Boolean? = null
        private set

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        if (v.isEmpty()) return
        w.add(v[0].toDouble())
        covered = v[0] < maxRange
    }
}

class GestureAcc : SensorAcc {
    var count = 0
        private set

    override fun onSample(v: FloatArray, tMs: Long, accuracy: Int) {
        count++
    }
}

class SensorBatchProbe(private val specs: List<SensorSpec>) : ProbeUnit {
    override val id = "sensor.batch"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val startMs = SystemClockCompat.elapsedRealtime()
        val results = ArrayList<ProbeResult>()
        val accs = HashMap<String, SensorAcc>()
        val byType = HashMap<Int, SensorSpec>()

        for (spec in specs) {
            val sensor = SensorSpecs.lookup(manager, spec)
            if (sensor == null) {
                results.add(resultBuilder(spec.id, Groups.SENSOR, spec.name, ProbeStatus.NO_HARDWARE))
                continue
            }
            if (spec.permission != null &&
                ContextCompat.checkSelfPermission(ctx, spec.permission) != PackageManager.PERMISSION_GRANTED
            ) {
                results.add(resultBuilder(spec.id, Groups.SENSOR, spec.name, ProbeStatus.PERMISSION_MISSING, note = spec.permission))
                continue
            }
            val acc = createAcc(spec, sensor)
            accs[spec.id] = acc
            byType[sensor.type] = spec
            live.set(spec.id, spec.name.en, "sampling")
        }

        val thread = HandlerThread("sensor-sampling")
        thread.start()
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val spec = byType[e.sensor.type] ?: return
                if (spec.type == Sensor.TYPE_GRAVITY && e.values.size >= 3) GravityHolder.gravity = e.values.copyOf()
                val acc = accs[spec.id] ?: return
                acc.onSample(e.values, SystemClockCompat.elapsedRealtime() - startMs, e.accuracy)
                if (spec.chart) live.set(spec.id, spec.name.en, fmt(accLast(acc)))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        if (accs.isNotEmpty()) {
            for (spec in byType.values) {
                val sensor = SensorSpecs.lookup(manager, spec) ?: continue
                try {
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                } catch (_: Throwable) { }
            }
            waitUntil(kotlin.coroutines.coroutineContext, deadlineMs)
            manager.unregisterListener(listener)
        } else {
            // nothing registered; still honor cancellation
            waitUntil(kotlin.coroutines.coroutineContext, deadlineMs)
        }
        thread.quitSafely()
        GravityHolder.gravity = null

        for (spec in specs) {
            val acc = accs[spec.id] ?: continue
            results.add(buildResult(spec, acc))
        }
        return results
    }

    private fun accLast(acc: SensorAcc): Double = when (acc) {
        is Vec3Acc -> acc.mag.last
        is ScalarAcc -> acc.w.last
        else -> 0.0
    }

    private fun createAcc(spec: SensorSpec, sensor: Sensor): SensorAcc = when (spec.kind) {
        SensorKind.VEC3 -> Vec3Acc(spec)
        SensorKind.SCALAR -> ScalarAcc(spec)
        SensorKind.STEP -> StepAcc()
        SensorKind.ACTIVITY -> ActivityAcc()
        SensorKind.HEART -> HeartAcc()
        SensorKind.HEARTBEAT -> HeartbeatAcc()
        SensorKind.ROTATION, SensorKind.ORIENTATION -> RotationAcc()
        SensorKind.MAGNETIC -> MagneticAcc()
        SensorKind.PROXIMITY -> ProximityAcc(sensor.maximumRange)
        SensorKind.GESTURE, SensorKind.SIGNIFICANT -> GestureAcc()
    }

    private fun buildResult(spec: SensorSpec, acc: SensorAcc): ProbeResult = when (acc) {
        is Vec3Acc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("x_avg", Labels.X_AXIS, fmt(acc.x.avg()), unitOf(spec)),
                metric("y_avg", Labels.Y_AXIS, fmt(acc.y.avg()), unitOf(spec)),
                metric("z_avg", Labels.Z_AXIS, fmt(acc.z.avg()), unitOf(spec)),
                metric("mag_avg", Labels.MAGNITUDE, fmt(acc.mag.avg()), unitOf(spec), primary = true),
                metric("mag_min", Labels.MIN, fmt(acc.mag.min)),
                metric("mag_max", Labels.MAX, fmt(acc.mag.max)),
            ),
            series = if (spec.chart) mapOf("magnitude" to (acc.series?.list() ?: emptyList())) else emptyMap(),
        )
        is ScalarAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("avg", Labels.AVG, fmt(acc.w.avg()), unitOf(spec), primary = true),
                metric("min", Labels.MIN, fmt(acc.w.min), unitOf(spec)),
                metric("max", Labels.MAX, fmt(acc.w.max), unitOf(spec)),
                metric("last", Labels.LAST, fmt(acc.w.last), unitOf(spec)),
            ),
            series = if (spec.chart) mapOf("value" to (acc.series?.list() ?: emptyList())) else emptyMap(),
        )
        is StepAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("steps", Labels.STEPS, fmt(maxOf(0.0, acc.last - (acc.initial ?: acc.last))), primary = true),
                metric("total", Labels.TOTAL_STEPS, fmt(acc.last)),
            ),
        )
        is ActivityAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("activity", Labels.ACTIVITY, ActivityNames.name((acc.lastCode ?: 0).toFloat()), primary = true),
                metric("events", L("样本数", "Samples"), acc.counts.values.sum().toString()),
            ),
        )
        is HeartAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("bpm", Labels.HEART_RATE, fmt(acc.bpm.avg()), "bpm", primary = true),
                metric("min", Labels.MIN, fmt(acc.bpm.min), "bpm"),
                metric("max", Labels.MAX, fmt(acc.bpm.max), "bpm"),
                metric("reliability", L("可靠性", "Reliability"), acc.lastReliability ?: bil("未知", "Unknown")),
            ),
        )
        is HeartbeatAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(metric("bpm", Labels.HEART_BEAT, fmt(acc.bpm.avg()), "bpm", primary = true)),
        )
        is RotationAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("azimuth", Labels.HEADING, fmt(acc.azimuth.avg()), "°", primary = true),
                metric("pitch", L("俯仰角", "Pitch"), fmt(acc.pitch.avg()), "°"),
                metric("roll", L("横滚角", "Roll"), fmt(acc.roll.avg()), "°"),
            ),
        )
        is MagneticAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("mag_avg", Labels.MAGNITUDE, fmt(acc.mag.avg()), "µT", primary = true),
                metric("x_avg", Labels.X_AXIS, fmt(acc.x.avg()), "µT"),
                metric("y_avg", Labels.Y_AXIS, fmt(acc.y.avg()), "µT"),
                metric("z_avg", Labels.Z_AXIS, fmt(acc.z.avg()), "µT"),
                metric("heading", Labels.HEADING, fmt(acc.heading.avg()) + "°"),
                metric("radiation", Labels.RADIATION, MagneticLevels.classify(acc.mag.avg())),
            ),
        )
        is ProximityAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("last", Labels.LAST, fmt(acc.w.last), "cm", primary = true),
                metric("min", Labels.MIN, fmt(acc.w.min), "cm"),
                metric("max", Labels.MAX, fmt(acc.w.max), "cm"),
                metric("covered", L("遮挡状态", "Covered"), if (acc.covered == true) bil("遮挡", "Covered") else bil("未遮挡", "Clear")),
            ),
        )
        is GestureAcc -> resultBuilder(
            spec.id, Groups.SENSOR, spec.name, ProbeStatus.OK,
            metrics = listOf(
                metric("count", L("触发次数", "Trigger count"), acc.count.toString(), primary = true),
                metric("state", L("状态", "State"), if (acc.count > 0) bil("已触发", "Triggered") else bil("未触发", "Not triggered")),
            ),
        )
        else -> resultBuilder(spec.id, Groups.SENSOR, spec.name, ProbeStatus.FAILED)
    }

    private fun unitOf(spec: SensorSpec): String = when (spec.type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> "m/s²"
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
        Sensor.TYPE_LIGHT -> "lux"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_PROXIMITY -> "cm"
        else -> ""
    }
}
