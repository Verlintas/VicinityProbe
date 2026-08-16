/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.sensorrec

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.util.Locale

enum class RecSensor(val type: Int, val chCount: Int, val label: String) {
    ACCEL(Sensor.TYPE_ACCELEROMETER, 3, "accel"),
    GYRO(Sensor.TYPE_GYROSCOPE, 3, "gyro"),
    MAG(Sensor.TYPE_MAGNETIC_FIELD, 3, "mag"),
    ROTATION(Sensor.TYPE_ROTATION_VECTOR, 4, "rotation"),
    PRESSURE(Sensor.TYPE_PRESSURE, 1, "pressure"),
}

data class RecSample(val tMs: Long, val values: FloatArray)

data class RecorderState(
    val sensor: RecSensor = RecSensor.ACCEL,
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val sampleCount: Int = 0,
    val sampleRateHz: Double = 0.0,
    val liveSeries: List<FloatArray> = emptyList(),   // 最近 300 点/通道
    val recordedFile: String? = null,
    val error: String? = null,
)

class SensorRecorderViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    private var sensorThread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: SensorEventListener? = null
    private var writer: FileWriter? = null
    private var writerLock = Any()
    private val liveRing = HashMap<String, FloatArray>()
    private val liveIdx = HashMap<String, Int>()
    private val liveLock = Any()

    fun setSensor(s: RecSensor) {
        _state.value = _state.value.copy(sensor = s)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.recording) return
        val app = getApplication<Application>()
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        currentSensorManager = sm
        val sensor = sm.getDefaultSensor(_state.value.sensor.type) ?: run {
            _state.value = _state.value.copy(error = "传感器不可用|Sensor unavailable")
            return
        }
        val chs = _state.value.sensor.chCount
        val file = File(app.filesDir, "recordings").apply { mkdirs() }
            .resolve("rec_${_state.value.sensor.label}_${System.currentTimeMillis()}.csv")
        val w = FileWriter(file)
        w.write(if (chs == 4) "t_ms,w,x,y,z\n" else (0 until chs).joinToString(",", "t_ms,", "\n") { it.toString() })
        writer = w

        synchronized(liveLock) {
            liveRing.clear(); liveIdx.clear()
            for (i in 0 until chs) {
                liveRing["ch$i"] = FloatArray(300)
                liveIdx["ch$i"] = 0
            }
        }

        val thread = HandlerThread("sensor-rec").apply { start() }
        sensorThread = thread
        handler = Handler(thread.looper)
        val startedAt = System.currentTimeMillis()
        var count = 0
        listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = System.currentTimeMillis()
                val n = minOf(e.values.size, chs)
                synchronized(writerLock) {
                    try {
                        val sb = StringBuilder()
                        sb.append(t).append(',')
                        for (i in 0 until n) sb.append(String.format(Locale.US, "%.6f", e.values[i])).append(if (i < n - 1) ',' else '\n')
                        w.write(sb.toString())
                    } catch (_: Throwable) {}
                }
                count++
                synchronized(liveLock) {
                    for (i in 0 until n) {
                        val arr = liveRing["ch$i"] ?: continue
                        val idx = liveIdx["ch$i"] ?: 0
                        arr[idx] = e.values[i]
                        liveIdx["ch$i"] = (idx + 1) % 300
                    }
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed % 500 < 10) {
                    _state.value = _state.value.copy(
                        recording = true,
                        elapsedMs = elapsed,
                        sampleCount = count,
                        sampleRateHz = if (elapsed > 0) count * 1000.0 / elapsed else 0.0,
                    )
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        try { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler) } catch (_: Throwable) {}
        _state.value = _state.value.copy(recording = true, elapsedMs = 0, sampleCount = 0, recordedFile = file.name)
    }

    fun stop() {
        try {
            sensorThread?.let { currentSensorManager?.unregisterListener(listener) }
        } catch (_: Throwable) {}
        sensorThread?.quitSafely()
        sensorThread = null
        handler = null
        listener = null
        synchronized(writerLock) {
            try { writer?.flush(); writer?.close() } catch (_: Throwable) {}
            writer = null
        }
        _state.value = _state.value.copy(recording = false)
    }

    private var currentSensorManager: SensorManager? = null

    fun liveSnapshot(channels: Int): List<FloatArray> {
        return synchronized(liveLock) {
            (0 until channels).map { i ->
                val arr = liveRing["ch$i"] ?: FloatArray(0)
                val idx = liveIdx["ch$i"] ?: 0
                FloatArray(300) { k -> arr[(idx - 300 + k + 600) % 300] }
            }
        }
    }

    /** 回放:读回录制文件并返回采样数(用于分析) */
    fun analyze(fileName: String, onProgress: (Int) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(app.filesDir, "recordings/$fileName")
            if (!f.exists()) return@launch
            f.forEachLine { onProgress(it.split(',').size) }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
