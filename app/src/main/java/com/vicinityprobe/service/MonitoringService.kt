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
import com.vicinityprobe.analysis.AnalysisEngine
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.probe.SessionController
import com.vicinityprobe.report.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
                intervalMinutes = (intent?.getLongExtra(EXTRA_INTERVAL, 10L) ?: 10L).coerceAtLeast(5L)
                startMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VicinityProbe", "monitoring idle"))
        if (job?.isActive == true) return
        job = scope.launch {
            var count = 0
            while (isActive) {
                count++
                val meta = runOneScan()
                val text = "$count scan done" + (meta?.let { " | EXC ${it.excellentCount} / OK ${it.okCount}" } ?: " | FAILED")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification("VicinityProbe · monitoring", text))
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

    private suspend fun runOneScan(): com.vicinityprobe.report.ReportMeta? {
        return try {
            val controller = SessionController(applicationContext, CORE_PROBES, SCAN_MS, "MONITOR")
            val report = controller.run(File(applicationContext.filesDir, "reports"))
            val analyzed = report.copy(analysis = AnalysisEngine.analyze(report))
            HistoryManager(applicationContext).save(analyzed)
        } catch (_: Throwable) { null }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Continuous monitoring", NotificationManager.IMPORTANCE_LOW,
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
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
