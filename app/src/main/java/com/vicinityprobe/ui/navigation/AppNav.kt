package com.vicinityprobe.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vicinityprobe.ui.compare.CompareScreen
import com.vicinityprobe.ui.history.HistoryScreen
import com.vicinityprobe.ui.home.HomeScreen
import com.vicinityprobe.ui.preflight.PreflightScreen
import com.vicinityprobe.ui.report.ReportScreen
import com.vicinityprobe.ui.scanning.ScanningScreen
import com.vicinityprobe.ui.trend.TrendScreen

object Routes {
    const val HOME = "home"
    const val PREFLIGHT = "preflight"
    const val SCAN = "scan?ids={ids}&mode={mode}&durationMs={durationMs}"
    const val REPORT = "report/{reportId}"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    const val TREND = "trend"

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
    }
}
