/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.calib

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.ui.realtime.SensorManagerHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class CalibStep { MAG, ACCEL, GYRO, DONE }

data class CalibResult(
    val step: CalibStep = CalibStep.MAG,
    val progressMs: Long = 0,
    val totalMs: Long = 20_000,
    val samples: Int = 0,
    val liveValue: String = "—",
    val error: String? = null,
    val magOffsetX: Double = 0.0,
    val magOffsetY: Double = 0.0,
    val magOffsetZ: Double = 0.0,
    val magMagnitudeRange: Double = 0.0,
    val accelGravityMs2: Double = 0.0,
    val accelBiasMs2: Double = 0.0,
    val gyroBiasX: Double = 0.0,
    val gyroBiasY: Double = 0.0,
    val gyroBiasZ: Double = 0.0,
    val gyroStddev: Double = 0.0,
    val complete: Boolean = false,
)

class CalibrationViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(CalibResult())
    val state: StateFlow<CalibResult> = _state

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: SensorEventListener? = null
    private var timerJob: Job? = null
    private var currentStep = CalibStep.MAG

    private val magSamples = java.util.Collections.synchronizedList(ArrayList<FloatArray>())
    private val accelSamples = java.util.Collections.synchronizedList(ArrayList<FloatArray>())
    private val gyroSamples = java.util.Collections.synchronizedList(ArrayList<FloatArray>())

    /** 传感器回调线程写入,定时器读取(10Hz 发布) */
    @Volatile private var latestSamples = 0
    @Volatile private var latestLive = "—"

    fun start() {
        val sm = SensorManagerHolder.manager ?: return
        currentStep = CalibStep.MAG
        magSamples.clear(); accelSamples.clear(); gyroSamples.clear()
        _state.value = CalibResult()
        startStep(sm, CalibStep.MAG, 20_000)
    }

    /** 传感器缺失时跳过当前步骤,继续后续步骤 */
    fun skipStep() {
        when (currentStep) {
            CalibStep.MAG -> advanceTo(CalibStep.ACCEL, 8_000)
            CalibStep.ACCEL -> advanceTo(CalibStep.GYRO, 8_000)
            CalibStep.GYRO -> _state.value = _state.value.copy(step = CalibStep.DONE, complete = true)
            CalibStep.DONE -> {}
        }
    }

    private fun advanceTo(next: CalibStep, durationMs: Long) {
        val sm = SensorManagerHolder.manager ?: return
        currentStep = next
        startStep(sm, next, durationMs)
    }

    private fun startStep(sm: SensorManager, step: CalibStep, durationMs: Long) {
        stopSampling()
        val type = when (step) {
            CalibStep.MAG -> Sensor.TYPE_MAGNETIC_FIELD
            CalibStep.ACCEL -> Sensor.TYPE_ACCELEROMETER
            CalibStep.GYRO -> Sensor.TYPE_GYROSCOPE
            CalibStep.DONE -> return
        }
        val sensor = sm.getDefaultSensor(type)
        if (sensor == null) {
            // 传感器缺失时给出可见反馈,不再静默返回
            _state.value = _state.value.copy(
                step = step,
                error = when (step) {
                    CalibStep.MAG -> "磁力计不可用|Magnetometer unavailable"
                    CalibStep.ACCEL -> "加速度计不可用|Accelerometer unavailable"
                    else -> "陀螺仪不可用|Gyroscope unavailable"
                },
            )
            return
        }
        val thread = HandlerThread("calib").apply { start() }
        this.thread = thread
        handler = Handler(thread.looper)
        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // 只累积样本,StateFlow 由 10Hz 定时器发布,避免 200Hz 全屏重组
                when (step) {
                    CalibStep.MAG -> {
                        magSamples.add(floatArrayOf(e.values[0], e.values[1], e.values[2]))
                        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                        latestSamples = magSamples.size
                        latestLive = String.format("%.1f µT", mag)
                    }
                    CalibStep.ACCEL -> {
                        accelSamples.add(floatArrayOf(e.values[0], e.values[1], e.values[2]))
                        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                        latestSamples = accelSamples.size
                        latestLive = String.format("%.3f m/s²", mag)
                    }
                    CalibStep.GYRO -> {
                        gyroSamples.add(floatArrayOf(e.values[0], e.values[1], e.values[2]))
                        latestSamples = gyroSamples.size
                        latestLive = String.format("%.4f, %.4f, %.4f rad/s", e.values[0], e.values[1], e.values[2])
                    }
                    CalibStep.DONE -> {}
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        try { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler) } catch (_: Throwable) {}

        _state.value = _state.value.copy(step = step, progressMs = 0, totalMs = durationMs, samples = 0, liveValue = "—")
        latestSamples = 0
        latestLive = "—"
        timerJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= durationMs) {
                    finishStep(sm)
                    break
                }
                _state.value = _state.value.copy(progressMs = elapsed, samples = latestSamples, liveValue = latestLive)
                delay(100)
            }
        }
    }

    private fun finishStep(sm: SensorManager) {
        stopSampling()
        when (currentStep) {
            CalibStep.MAG -> {
                // 硬铁偏移 = 各轴均值
                if (magSamples.size > 10) {
                    val n = magSamples.size
                    val sums = DoubleArray(3)
                    var minMag = Double.MAX_VALUE
                    var maxMag = 0.0
                    for (s in magSamples) {
                        sums[0] += s[0]; sums[1] += s[1]; sums[2] += s[2]
                        val m = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]).toDouble()
                        if (m < minMag) minMag = m
                        if (m > maxMag) maxMag = m
                    }
                    _state.value = _state.value.copy(
                        magOffsetX = sums[0] / n, magOffsetY = sums[1] / n, magOffsetZ = sums[2] / n,
                        magMagnitudeRange = maxMag - minMag,
                    )
                }
                currentStep = CalibStep.ACCEL
                startStep(sm, CalibStep.ACCEL, 8_000)
            }
            CalibStep.ACCEL -> {
                if (accelSamples.size > 10) {
                    var sumMag = 0.0
                    var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0
                    val n = accelSamples.size
                    for (s in accelSamples) {
                        sumMag += sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]).toDouble()
                        sumX += s[0]; sumY += s[1]; sumZ += s[2]
                    }
                    val g = sumMag / n
                    _state.value = _state.value.copy(
                        accelGravityMs2 = g,
                        accelBiasMs2 = g - 9.80665,
                    )
                }
                currentStep = CalibStep.GYRO
                startStep(sm, CalibStep.GYRO, 8_000)
            }
            CalibStep.GYRO -> {
                if (gyroSamples.size > 10) {
                    val n = gyroSamples.size
                    var sx = 0.0; var sy = 0.0; var sz = 0.0; var sq = 0.0
                    for (s in gyroSamples) {
                        sx += s[0]; sy += s[1]; sz += s[2]
                        sq += s[0] * s[0] + s[1] * s[1] + s[2] * s[2]
                    }
                    _state.value = _state.value.copy(
                        gyroBiasX = sx / n, gyroBiasY = sy / n, gyroBiasZ = sz / n,
                        gyroStddev = sqrt((sq / n - (sx * sx + sy * sy + sz * sz) / (n * n)).coerceAtLeast(0.0)),
                        step = CalibStep.DONE,
                        complete = true,
                    )
                } else {
                    _state.value = _state.value.copy(step = CalibStep.DONE, complete = true)
                }
            }
            CalibStep.DONE -> {}
        }
    }

    private fun stopSampling() {
        try { SensorManagerHolder.manager?.unregisterListener(listener) } catch (_: Throwable) {}
        thread?.quitSafely()
        thread = null
        handler = null
        listener = null
        timerJob?.cancel()
        timerJob = null
    }

    fun restart() = start()

    override fun onCleared() {
        stopSampling()
        super.onCleared()
    }
}
