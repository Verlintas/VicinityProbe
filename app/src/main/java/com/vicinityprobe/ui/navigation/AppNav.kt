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
import com.vicinityprobe.ui.history.HistoryScreen
import com.vicinityprobe.ui.home.HomeScreen
import com.vicinityprobe.ui.preflight.PreflightScreen
import com.vicinityprobe.ui.realtime.RealTimeScreen
import com.vicinityprobe.ui.report.ReportScreen
import com.vicinityprobe.ui.scanning.ScanningScreen
import com.vicinityprobe.ui.trend.TrendScreen
import com.vicinityprobe.ui.web.WebConsoleScreen

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
    }
}
