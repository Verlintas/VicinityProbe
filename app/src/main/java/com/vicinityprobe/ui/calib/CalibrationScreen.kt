/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.calib

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.vicinityprobe.model.L
import com.vicinityprobe.model.langOf
import com.vicinityprobe.ui.components.KeyValueRow
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(nav: NavController) {
    val context = LocalContext.current
    val lang = langOf(context)
    val t = { l: L -> if (lang.startsWith("zh")) l.zh else l.en }
    val vm: CalibrationViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t(L("传感器标定", "Sensor calibration"))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 步骤指示
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CalibStep.MAG to t(L("磁力计", "Mag")), CalibStep.ACCEL to t(L("加速度", "Accel")), CalibStep.GYRO to t(L("陀螺仪", "Gyro"))).forEach { (s, label) ->
                    val active = st.step == s
                    val done = st.step.ordinal > s.ordinal || st.complete
                    OutlinedCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text(
                                when {
                                    done -> "✓"
                                    active -> "●"
                                    else -> "○"
                                },
                                color = if (done || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            when (st.step) {
                CalibStep.MAG -> {
                    Text(t(L("手持手机画 8 字,覆盖所有姿态(约 20 秒)", "Move the phone in a figure-8 covering all orientations (~20 s)")), style = MaterialTheme.typography.bodyMedium)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            KeyValueRow(t(L("当前磁场幅值", "Current magnitude")), st.liveValue, primary = true)
                            KeyValueRow(t(L("样本数", "Samples")), st.samples.toString())
                            LinearProgressIndicator(progress = { (st.progressMs.toFloat() / st.totalMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                CalibStep.ACCEL -> {
                    Text(t(L("将手机静置在水平桌面(约 8 秒)", "Rest the phone flat on a table (~8 s)")), style = MaterialTheme.typography.bodyMedium)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            KeyValueRow(t(L("当前重力幅值", "Current gravity")), st.liveValue, primary = true)
                            KeyValueRow(t(L("样本数", "Samples")), st.samples.toString())
                            LinearProgressIndicator(progress = { (st.progressMs.toFloat() / st.totalMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                CalibStep.GYRO -> {
                    Text(t(L("保持手机完全静止(约 8 秒)", "Keep the phone perfectly still (~8 s)")), style = MaterialTheme.typography.bodyMedium)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            KeyValueRow(t(L("当前角速度 x,y,z", "Current rate x,y,z")), st.liveValue, primary = true)
                            KeyValueRow(t(L("样本数", "Samples")), st.samples.toString())
                            LinearProgressIndicator(progress = { (st.progressMs.toFloat() / st.totalMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                CalibStep.DONE -> {
                    Text(t(L("标定完成", "Calibration complete")), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t(L("磁力计(硬铁偏移, µT)", "Magnetometer (hard-iron offset, µT)")), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow("x", String.format("%.2f", st.magOffsetX))
                            KeyValueRow("y", String.format("%.2f", st.magOffsetY))
                            KeyValueRow("z", String.format("%.2f", st.magOffsetZ))
                            KeyValueRow(t(L("幅值范围", "Magnitude range")), String.format("%.2f µT", st.magMagnitudeRange))
                            Text(t(L("加速度计", "Accelerometer")), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow(t(L("实测重力", "Measured gravity")), String.format("%.3f m/s²", st.accelGravityMs2))
                            KeyValueRow(t(L("基准偏差", "Bias vs 9.80665")), String.format("%+.3f m/s²", st.accelBiasMs2))
                            Text(t(L("陀螺仪零偏(rad/s)", "Gyro bias (rad/s)")), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow("x,y,z", String.format("%.5f, %.5f, %.5f", st.gyroBiasX, st.gyroBiasY, st.gyroBiasZ))
                            KeyValueRow(t(L("综合标准差", "Combined stddev")), String.format("%.5f", st.gyroStddev))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.restart() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null); Text(t(L("重新标定", "Recalibrate")))
                        }
                        Button(onClick = {
                            val report = buildString {
                                appendLine("VicinityProbe calibration report")
                                appendLine("mag hard-iron offset (µT): ${String.format("%.2f, %.2f, %.2f", st.magOffsetX, st.magOffsetY, st.magOffsetZ)}")
                                appendLine("mag magnitude range (µT): ${String.format("%.2f", st.magMagnitudeRange)}")
                                appendLine("accel measured gravity (m/s²): ${String.format("%.3f", st.accelGravityMs2)}")
                                appendLine("accel bias (m/s²): ${String.format("%+.3f", st.accelBiasMs2)}")
                                appendLine("gyro bias (rad/s): ${String.format("%.5f, %.5f, %.5f", st.gyroBiasX, st.gyroBiasY, st.gyroBiasZ)}")
                                appendLine("gyro combined stddev: ${String.format("%.5f", st.gyroStddev)}")
                            }
                            val f = File(context.cacheDir, "calibration_report.txt")
                            f.writeText(report)
                            com.vicinityprobe.report.ReportExporter.shareFile(context, f, "text/plain")
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, contentDescription = null); Text(t(L("分享报告", "Share")))
                        }
                    }
                }
            }

            if (st.step != CalibStep.DONE) {
                CircularProgressIndicator(modifier = Modifier.height(48.dp).padding(vertical = 4.dp))
            }
        }
    }
}
