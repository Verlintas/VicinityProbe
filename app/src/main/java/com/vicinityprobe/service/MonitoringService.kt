package com.vicinityprobe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vicinityprobe.MainActivity
import com.vicinityprobe.R
import com.vicinityprobe.analysis.Analyzer
import com.vicinityprobe.analysis.WeatherClient
import com.vicinityprobe.probe.ProbeController
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {
    companion object {
        const val ACTION_START = "com.vicinityprobe.action.START_MONITOR"
        const val ACTION_STOP = "com.vicinityprobe.action.STOP_MONITOR"
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 2
        const val EXTRA_INTERVAL = "interval_minutes"
        private const val SCAN_MS = 10_000L

        private val CORE_PROBES = setOf(
            "sensor.light", "sensor.temperature", "sensor.humidity", "sensor.pressure",
            "sensor.accelerometer", "noise", "location", "gnss", "battery",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var intervalMinutes = 10L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring()
            else -> {
                intervalMinutes = intent?.getLongExtra(EXTRA_INTERVAL, 10L) ?: 10L
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VicinityProbe", "0 次监测"))
        if (job?.isActive == true) return
        job = scope.launch {
            var count = 0
            while (isActive) {
                count++
                val meta = runOneScan()
                val score = meta?.overallScore
                val text = "${count} 次监测完成" + (score?.let { " | 评分 ${"%.0f".format(it)}" } ?: "")
                updateNotification(count, score)
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    private fun stopMonitoring() {
        job?.cancel()
        job = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runOneScan(): com.vicinityprobe.model.ReportMeta? {
        return try {
            val controller = ProbeController(applicationContext, CORE_PROBES, SCAN_MS, "MONITOR")
            val report = controller.run()
            val lat = report.results.firstOrNull { it.id == "location" }
                ?.metrics?.firstOrNull { it.key == "lat" }?.value
                ?.let { Regex("-?\\d+\\.?\\d*").find(it)?.value?.toDoubleOrNull() }
            val lon = report.results.firstOrNull { it.id == "location" }
                ?.metrics?.firstOrNull { it.key == "lon" }?.value
                ?.let { Regex("-?\\d+\\.?\\d*").find(it)?.value?.toDoubleOrNull() }
            val weather = if (lat != null && lon != null) WeatherClient.fetch(lat, lon) else null
            val analyzed = report.copy(analysis = Analyzer.analyze(report, weather))
            HistoryManager(applicationContext).save(analyzed)
        } catch (_: Throwable) { null }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "连续监测", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int, score: Double?) {
        val text = "$count 次监测完成" + (score?.let { " | 评分 ${"%.0f".format(it)}" } ?: "")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("VicinityProbe · 连续监测中", text))
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
