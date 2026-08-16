/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.realtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.R
import com.vicinityprobe.analysis.Fft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ln

enum class WaveMode(val id: String) {
    ACCEL("sensor.accelerometer"),
    GYRO("sensor.gyroscope"),
    MAG("sensor.magnetometer"),
    LIGHT("sensor.light"),
    TEMP("sensor.temperature"),
    PRESSURE("sensor.pressure"),
    NOISE("noise"),
    SPECTRUM("spectrum"),
    ATTITUDE("attitude"),
}

data class WaveSnapshot(
    val mode: WaveMode,
    val series: List<FloatArray>,   // 每通道最近 N 点
    val labels: List<String>,
    val values: List<String>,       // 当前值显示
    val spectrum: List<FloatArray>? = null,  // 瀑布图行(每行对数幅度)
    val alert: Boolean = false,
    val attitude: AttitudeSnapshot? = null,  // 姿态模式:roll/pitch/稳定度
)

/** 姿态快照(互补滤波融合结果,弧度) */
data class AttitudeSnapshot(
    val rollDeg: Double,
    val pitchDeg: Double,
    val stability: Float,   // 0..1,静止程度(加速度方差倒数)
)

data class AlertSettings(
    val noiseDb: Int = 70,
    val tempMax: Int = 35,
    val lightMin: Int = 10,
    val enabled: Boolean = true,
)

class RealTimeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    val settings: AlertSettings
        get() = AlertSettings(
            noiseDb = prefs.getInt("noise", 70),
            tempMax = prefs.getInt("temp", 35),
            lightMin = prefs.getInt("light", 10),
            enabled = prefs.getBoolean("enabled", true),
        )

    fun saveSettings(s: AlertSettings) {
        prefs.edit().putInt("noise", s.noiseDb).putInt("temp", s.tempMax)
            .putInt("light", s.lightMin).putBoolean("enabled", s.enabled).apply()
    }

    private val _snapshot = MutableStateFlow(WaveSnapshot(WaveMode.ACCEL, emptyList(), emptyList(), emptyList()))
    val snapshot: StateFlow<WaveSnapshot> = _snapshot

    private val _smoothing = MutableStateFlow(true)
    val smoothing: StateFlow<Boolean> = _smoothing

    /** 每个通道一个卡尔曼滤波器(发布间保持状态) */
    private val kalmanMap = HashMap<String, com.vicinityprobe.analysis.Denoise.Kalman1D>()
    private val kalmanLock = Any()

    fun setSmoothing(on: Boolean) { _smoothing.value = on }

    fun toggleSmoothing() { _smoothing.value = !_smoothing.value }

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var audioThread: Thread? = null
    private var tickJob: Job? = null
    private var sensorListener: SensorEventListener? = null
    private var currentMode = WaveMode.ACCEL

    /** 环形缓冲:每通道 800 点。跨线程访问必须持有 ringLock */
    private val ringLock = Any()
    private val ring = HashMap<String, FloatArray>()
    private val ringIdx = HashMap<String, Int>()
    private var ringCount = 0
    private val ringSize = 800

    private var audioRecord: AudioRecord? = null
    private val spectrumRows = ArrayDeque<FloatArray>()
    @Volatile private var lastNoiseDb = 0.0
    @Volatile private var lastTemp = 0.0
    @Volatile private var lastLight = 0.0

    fun start(mode: WaveMode) {
        currentMode = mode
        stopInternal()
        tickJob?.cancel()
        tickJob = null
        synchronized(kalmanLock) { kalmanMap.clear() }
        if (mode == WaveMode.SPECTRUM) {
            startSpectrum()
        } else if (mode == WaveMode.ATTITUDE) {
            startAttitude()
        } else {
            startSensor(mode)
        }
        tickJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                publish()
                checkAlerts()
                delay(120)
            }
        }
    }

    fun stop() {
        stopInternal()
        tickJob?.cancel()
        tickJob = null
    }

    private fun stopInternal() {
        sensorThread?.let {
            SensorManagerHolder.manager?.unregisterListener(sensorListener)
            it.quitSafely()
        }
        sensorThread = null
        sensorHandler = null
        sensorListener = null
        // 先停止录音使 read() 返回,再中断并 join 采集线程,防旧线程继续写 ring
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioThread?.interrupt()
        try { audioThread?.join(500) } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        audioThread = null
    }

    private fun startSensor(mode: WaveMode) {
        val sm = SensorManagerHolder.manager ?: return
        val type = when (mode) {
            WaveMode.ACCEL -> Sensor.TYPE_ACCELEROMETER
            WaveMode.GYRO -> Sensor.TYPE_GYROSCOPE
            WaveMode.MAG -> Sensor.TYPE_MAGNETIC_FIELD
            WaveMode.LIGHT -> Sensor.TYPE_LIGHT
            WaveMode.TEMP -> Sensor.TYPE_AMBIENT_TEMPERATURE
            WaveMode.PRESSURE -> Sensor.TYPE_PRESSURE
            WaveMode.NOISE -> Sensor.TYPE_ACCELEROMETER // noise 用麦克风, 占位
            else -> Sensor.TYPE_ACCELEROMETER
        }
        if (mode == WaveMode.NOISE) {
            startNoise()
            return
        }
        val sensor = sm.getDefaultSensor(type) ?: return
        synchronized(ringLock) {
            ring.clear(); ringIdx.clear(); ringCount = 0
        }
        spectrumRows.clear()
        val chCount = if (mode == WaveMode.LIGHT || mode == WaveMode.TEMP || mode == WaveMode.PRESSURE) 1 else 3
        synchronized(ringLock) {
            for (i in 0 until chCount) {
                ring["ch$i"] = FloatArray(ringSize)
                ringIdx["ch$i"] = 0
            }
        }
        val thread = HandlerThread("realtime-sensor")
        thread.start()
        sensorThread = thread
        sensorHandler = Handler(thread.looper)
        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = if (e.values.size >= 3 && chCount == 3) 3 else 1
                synchronized(ringLock) {
                    for (i in 0 until n) {
                        val arr = ring["ch$i"] ?: continue
                        val idx = ringIdx["ch$i"] ?: 0
                        arr[idx] = e.values[i]
                        ringIdx["ch$i"] = (idx + 1) % ringSize
                    }
                    if (ringCount < ringSize) ringCount++
                }
                if (mode == WaveMode.TEMP) lastTemp = e.values[0].toDouble()
                if (mode == WaveMode.LIGHT) lastLight = e.values[0].toDouble()
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        try { sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler) } catch (_: Throwable) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startNoise() {
        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        synchronized(ringLock) {
            ring.clear(); ringIdx.clear(); ringCount = 0
        }
        spectrumRows.clear()
        synchronized(ringLock) {
            ring["ch0"] = FloatArray(ringSize); ringIdx["ch0"] = 0
        }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(8192))
        } catch (_: Throwable) { null } ?: return
        audioRecord = rec
        audioThread = Thread {
            rec.startRecording()
            val buf = ShortArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                val read = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                if (read <= 0) break
                var sumSq = 0.0
                for (i in 0 until read) sumSq += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sumSq / read)
                val db = if (rms > 0) 20 * ln(rms / 32767.0) / ln(10.0) + 94.0 else 0.0
                lastNoiseDb = db
                synchronized(ringLock) {
                    val arr = ring["ch0"] ?: return@synchronized
                    val idx = ringIdx["ch0"] ?: 0
                    arr[idx] = db.toFloat()
                    ringIdx["ch0"] = (idx + 1) % ringSize
                    if (ringCount < ringSize) ringCount++
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 姿态模式:互补滤波(加速度 + 陀螺仪)融合出滚转/俯仰 */
    private var attitudeFilter: com.vicinityprobe.analysis.ComplementaryFilter? = null
    private var lastGyroNs = 0L
    private var lastGyroEvent = longArrayOf(0L, 0L, 0L)
    @Volatile private var lastAttitude: AttitudeSnapshot = AttitudeSnapshot(0.0, 0.0, 0f)

    private fun startAttitude() {
        val sm = SensorManagerHolder.manager ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        synchronized(ringLock) { ring.clear(); ringIdx.clear(); ringCount = 0 }
        attitudeFilter = com.vicinityprobe.analysis.ComplementaryFilter(alpha = 0.03)
        val thread = HandlerThread("realtime-attitude")
        thread.start()
        sensorThread = thread
        sensorHandler = Handler(thread.looper)
        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.nanoTime()
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER && e.values.size >= 3) {
                    val dt = if (lastGyroNs > 0) (now - lastGyroNs) / 1e9 else 0.01
                    val filter = attitudeFilter ?: return
                    val att = if (lastGyroEvent.any { it != 0L }) {
                        filter.update(dt, lastGyroEvent[0].toFloat() / 1e6f, lastGyroEvent[1].toFloat() / 1e6f, lastGyroEvent[2].toFloat() / 1e6f, e.values[0], e.values[1], e.values[2])
                    } else filter.accelAttitude(e.values[0], e.values[1], e.values[2])
                    // 稳定度:加速度幅值相对 9.81 的偏差
                    val mag = kotlin.math.sqrt(e.values[0].toDouble() * e.values[0] + e.values[1].toDouble() * e.values[1] + e.values[2].toDouble() * e.values[2])
                    val stab = (1.0 - (kotlin.math.abs(mag - 9.81) / 9.81).coerceAtMost(1.0)).toFloat()
                    lastAttitude = AttitudeSnapshot(
                        rollDeg = Math.toDegrees(att.roll),
                        pitchDeg = Math.toDegrees(att.pitch),
                        stability = stab,
                    )
                } else if (e.sensor.type == Sensor.TYPE_GYROSCOPE && e.values.size >= 3) {
                    lastGyroNs = now
                    lastGyroEvent = longArrayOf(
                        (e.values[0] * 1e6).toLong(),
                        (e.values[1] * 1e6).toLong(),
                        (e.values[2] * 1e6).toLong(),
                    )
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        try {
            sm.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
            sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        } catch (_: Throwable) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startSpectrum() {        val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        synchronized(ringLock) {
            ring.clear(); ringIdx.clear(); ringCount = 0
        }
        spectrumRows.clear()
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(16384))
        } catch (_: Throwable) { null } ?: return
        audioRecord = rec
        audioThread = Thread {
            rec.startRecording()
            val fftSize = 4096
            val buf = ShortArray(fftSize)
            var fill = 0
            while (!Thread.currentThread().isInterrupted) {
                val want = fftSize - fill
                val read = try { rec.read(buf, fill, want) } catch (_: Throwable) { -1 }
                if (read < 0) break
                if (read == 0) {
                    // 麦克风忙/出错:休眠退避,避免 100% CPU 空转
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                fill += read
                if (fill < fftSize) continue
                fill = 0
                val samples = DoubleArray(fftSize) { buf[it] / 32767.0 }
                val (_, power) = Fft.powerSpectrum(samples, 44100.0)
                // 取 0-8kHz 对数幅度,64 个 bin
                val bins = 64
                val row = FloatArray(bins)
                var sumSq = 0.0
                for (i in 0 until fftSize) sumSq += buf[i].toDouble() * buf[i]
                val db = if (sumSq > 0) 20 * ln(kotlin.math.sqrt(sumSq / fftSize) / 32767.0) / ln(10.0) + 94.0 else 0.0
                lastNoiseDb = db
                val maxBin = kotlin.math.min(power.size, 8 * 1024 / (44100 / fftSize))
                for (b in 0 until bins) {
                    val start = b * maxBin / bins
                    val end = ((b + 1) * maxBin / bins).coerceAtLeast(start + 1)
                    var e = 0.0
                    for (i in start until end) e += power[i]
                    row[b] = (10 * ln(e + 1e-12) / ln(10.0)).toFloat()
                }
                synchronized(spectrumRows) {
                    spectrumRows.addLast(row)
                    while (spectrumRows.size > 40) spectrumRows.removeFirst()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun publish() {
        if (currentMode == WaveMode.ATTITUDE) {
            val a = lastAttitude
            _snapshot.value = WaveSnapshot(
                mode = WaveMode.ATTITUDE,
                series = emptyList(),
                labels = listOf("roll", "pitch", "稳定"),
                values = listOf(
                    String.format("%+.1f°", a.rollDeg),
                    String.format("%+.1f°", a.pitchDeg),
                    String.format("%.0f%%", a.stability * 100),
                ),
                attitude = a,
            )
            return
        }
        val labels = when (currentMode) {
            WaveMode.ACCEL -> listOf("x", "y", "z")
            WaveMode.GYRO -> listOf("x", "y", "z")
            WaveMode.MAG -> listOf("x", "y", "z")
            WaveMode.LIGHT -> listOf("lux")
            WaveMode.TEMP -> listOf("°C")
            WaveMode.PRESSURE -> listOf("hPa")
            WaveMode.NOISE -> listOf("dB(A)")
            WaveMode.SPECTRUM -> listOf("SPL")
            WaveMode.ATTITUDE -> listOf("roll", "pitch", "stability")
        }
        val series = if (currentMode == WaveMode.SPECTRUM) {
            listOf(floatArrayOf(lastNoiseDb.toFloat()))
        } else {
            val (idxs, count) = synchronized(ringLock) {
                val idx = HashMap<String, Int>(ringIdx)
                val c = minOf(ringCount, ringSize)
                idx to c
            }
            (0 until labels.size).map { i ->
                val arr = synchronized(ringLock) { ring["ch$i"] } ?: FloatArray(0)
                val startIdx = idxs["ch$i"] ?: 0
                val n = minOf(count, arr.size)
                val raw = FloatArray(n) { arr[(startIdx - n + it + arr.size) % arr.size] }
                if (_smoothing.value && n > 1) {
                    val key = "${currentMode.id}/ch$i"
                    val kf = synchronized(kalmanLock) {
                        kalmanMap.getOrPut(key) { com.vicinityprobe.analysis.Denoise.Kalman1D(q = 1e-2, r = 0.05) }
                    }
                    FloatArray(n) { idx -> kf.update(raw[idx].toDouble()).toFloat() }
                } else {
                    raw
                }
            }
        }
        val values = series.map { s -> if (s.isEmpty()) "—" else String.format("%.2f", s.last()) }
        val spectrum = if (currentMode == WaveMode.SPECTRUM) synchronized(spectrumRows) { spectrumRows.toList() } else null
        _snapshot.value = WaveSnapshot(currentMode, series, labels, values, spectrum)
    }

    private fun checkAlerts() {
        val s = settings
        if (!s.enabled) return
        var msg: String? = null
        when (currentMode) {
            WaveMode.NOISE, WaveMode.SPECTRUM -> if (lastNoiseDb >= s.noiseDb) msg = "Noise ${"%.0f".format(lastNoiseDb)} dB(A) ≥ ${s.noiseDb}"
            WaveMode.TEMP -> if (lastTemp >= s.tempMax) msg = "Temp ${"%.1f".format(lastTemp)}°C ≥ ${s.tempMax}"
            WaveMode.LIGHT -> if (lastLight <= s.lightMin) msg = "Light ${"%.0f".format(lastLight)} lx ≤ ${s.lightMin}"
            else -> {}
        }
        msg?.let { notifyAlert(it) }
    }

    private var lastNotifyAt = 0L

    private fun notifyAlert(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 5000) return
        lastNotifyAt = now
        try {
            val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("alerts", "Threshold alerts", NotificationManager.IMPORTANCE_HIGH))
            nm.notify(
                5,
                NotificationCompat.Builder(getApplication(), "alerts")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("VicinityProbe alert")
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        } catch (_: Throwable) {}
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** 全局 SensorManager 缓存 */
object SensorManagerHolder {
    @Volatile var manager: SensorManager? = null
}
