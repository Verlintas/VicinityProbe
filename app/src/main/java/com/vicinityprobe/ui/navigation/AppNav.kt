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

package com.vicinityprobe.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vicinityprobe.ui.calib.CalibrationScreen
import com.vicinityprobe.ui.capture.CaptureScreen
import com.vicinityprobe.ui.compare.CompareScreen
import com.vicinityprobe.ui.gnss.GnssScreen
import com.vicinityprobe.ui.history.HistoryScreen
import com.vicinityprobe.ui.home.HomeScreen
import com.vicinityprobe.ui.preflight.PreflightScreen
import com.vicinityprobe.ui.realtime.RealTimeScreen
import com.vicinityprobe.ui.report.ReportScreen
import com.vicinityprobe.ui.scanning.ScanningScreen
import com.vicinityprobe.ui.soundlevel.SoundLevelScreen
import com.vicinityprobe.ui.tools.HttpRequestScreen
import com.vicinityprobe.ui.tools.PacketSenderScreen
import com.vicinityprobe.ui.tools.PortScanToolScreen
import com.vicinityprobe.ui.trend.TrendScreen
import com.vicinityprobe.ui.web.WebConsoleScreen
import com.vicinityprobe.ui.wifimap.WifiMapScreen
import com.vicinityprobe.ui.btanalysis.BtAnalysisScreen
import com.vicinityprobe.ui.pingmonitor.PingMonitorScreen
import com.vicinityprobe.ui.speedtest.SpeedTestScreen
import com.vicinityprobe.ui.gpstrack.GpsTrackScreen
import com.vicinityprobe.ui.sensorrec.SensorRecorderScreen
import com.vicinityprobe.ui.batterylog.BatteryLoggerScreen
import com.vicinityprobe.ui.netmatrix.NetMatrixScreen

object Routes {
    const val HOME = "home"
    const val PREFLIGHT = "preflight"
    const val SCAN = "scan?ids={ids}&mode={mode}&durationMs={durationMs}"
    const val REPORT = "report/{reportId}"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    const val TREND = "trend"
    const val CAPTURE = "capture"
    const val WEB = "web"
    const val REALTIME = "realtime"
    const val CALIB = "calib"
    const val PACKET = "packet"
    const val HTTPTOOL = "httptool"
    const val PORTSCAN = "portscan"
    const val SOUNDLEVEL = "soundlevel"
    const val WIFIMAP = "wifimap"
    const val GNSS = "gnss"
    const val BTANALYSIS = "btanalysis"
    const val PINGMONITOR = "pingmonitor"
    const val SPEEDTEST = "speedtest"
    const val GPSTRACK = "gpstrack"
    const val SENSORREC = "sensorrec"
    const val BATTERYLOG = "batterylog"
    const val NETMATRIX = "netmatrix"

    fun report(reportId: String) = "report/$reportId"
    fun scan(ids: List<String>, mode: String, durationMs: Long) =
        "scan?ids=${ids.joinToString(",").replace("%", "%25").replace(",", "%2C")}&mode=$mode&durationMs=$durationMs"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.PREFLIGHT) { PreflightScreen(nav) }
        composable(
            Routes.SCAN,
            arguments = listOf(
                navArgument("ids") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType },
                navArgument("durationMs") { type = NavType.LongType },
            ),
        ) { entry ->
            val ids = entry.arguments?.getString("ids")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val mode = entry.arguments?.getString("mode") ?: "SELECTED"
            val duration = entry.arguments?.getLong("durationMs") ?: 10_000L
            ScanningScreen(nav, ids.toSet(), mode, duration)
        }
        composable(
            Routes.REPORT,
            arguments = listOf(navArgument("reportId") { type = NavType.StringType }),
        ) { entry ->
            ReportScreen(nav, entry.arguments?.getString("reportId") ?: "")
        }
        composable(Routes.HISTORY) { HistoryScreen(nav) }
        composable(Routes.COMPARE) { CompareScreen(nav) }
        composable(Routes.TREND) { TrendScreen(nav) }
        composable(Routes.CAPTURE) { CaptureScreen(nav) }
        composable(Routes.WEB) { WebConsoleScreen(nav) }
        composable(Routes.REALTIME) { RealTimeScreen(nav) }
        composable(Routes.CALIB) { CalibrationScreen(nav) }
        composable(Routes.PACKET) { PacketSenderScreen(nav) }
        composable(Routes.HTTPTOOL) { HttpRequestScreen(nav) }
        composable(Routes.PORTSCAN) { PortScanToolScreen(nav) }
        composable(Routes.SOUNDLEVEL) { SoundLevelScreen(nav) }
        composable(Routes.WIFIMAP) { WifiMapScreen(nav) }
        composable(Routes.GNSS) { GnssScreen(nav) }
        composable(Routes.BTANALYSIS) { BtAnalysisScreen(nav) }
        composable(Routes.PINGMONITOR) { PingMonitorScreen(nav) }
        composable(Routes.SPEEDTEST) { SpeedTestScreen(nav) }
        composable(Routes.GPSTRACK) { GpsTrackScreen(nav) }
        composable(Routes.SENSORREC) { SensorRecorderScreen(nav) }
        composable(Routes.BATTERYLOG) { BatteryLoggerScreen(nav) }
        composable(Routes.NETMATRIX) { NetMatrixScreen(nav) }
    }
}
