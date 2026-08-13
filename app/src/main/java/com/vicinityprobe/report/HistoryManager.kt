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

package com.vicinityprobe.report

import android.content.Context
import com.vicinityprobe.model.domain.MeasurementReport
import com.vicinityprobe.model.domain.QualityLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ReportMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val mode: String,
    val deviceName: String,
    val probeCount: Int,
    val okCount: Int,
    val excellentCount: Int,
    val degradedCount: Int,
    val failedCount: Int,
    val samplesKept: Boolean,
)

class HistoryManager(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "reports")
    private val indexFile: File get() = File(dir, "index.json")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun save(report: MeasurementReport): ReportMeta {
        dir.mkdirs()
        val reportDir = File(dir, report.id)
        reportDir.mkdirs()
        File(reportDir, "report.json").writeText(JsonReport.encode(report))
        val meta = metaOf(report)
        val index = list().filterNot { it.id == meta.id } + meta
        indexFile.writeText(json.encodeToString(index.sortedByDescending { it.createdAt }))
        return meta
    }

    fun list(): List<ReportMeta> {
        if (!indexFile.exists()) rescan()
        return try {
            json.decodeFromString<List<ReportMeta>>(indexFile.readText())
        } catch (_: Throwable) {
            rescan()
            emptyList()
        }
    }

    private fun rescan(): List<ReportMeta> {
        dir.mkdirs()
        val metas = dir.listFiles { f -> f.isDirectory && f.name != "index.json" }?.mapNotNull { rd ->
            val f = File(rd, "report.json")
            if (!f.exists()) return@mapNotNull null
            try {
                metaOf(JsonReport.decode(f.readText()))
            } catch (_: Throwable) { null }
        } ?: emptyList()
        val sorted = metas.sortedByDescending { it.createdAt }
        try { indexFile.writeText(json.encodeToString(sorted)) } catch (_: Throwable) {}
        return sorted
    }

    fun load(id: String): MeasurementReport? {
        val f = File(dir, "$id/report.json")
        if (!f.exists()) return null
        return try {
            JsonReport.decode(f.readText())
        } catch (_: Throwable) { null }
    }

    fun samplesDir(id: String): File? {
        val d = File(dir, "$id/samples")
        return if (d.exists()) d else null
    }

    fun rename(id: String, newName: String) {
        val metas = list().map { if (it.id == id) it.copy(name = newName.trim().ifEmpty { it.name }) else it }
        indexFile.writeText(json.encodeToString(metas))
    }

    fun delete(id: String) {
        File(dir, id).deleteRecursively()
        indexFile.writeText(json.encodeToString(list().filterNot { it.id == id }))
    }

    private fun metaOf(report: MeasurementReport): ReportMeta {
        val qs = report.measurements.map { it.quality.level }
        return ReportMeta(
            id = report.id,
            name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.plan.createdAt)),
            createdAt = report.plan.createdAt,
            durationMs = report.plan.durationMs,
            mode = report.plan.operator,
            deviceName = report.context.device,
            probeCount = report.measurements.size,
            okCount = qs.count { it != QualityLevel.FAILED },
            excellentCount = qs.count { it == QualityLevel.EXCELLENT },
            degradedCount = qs.count { it == QualityLevel.DEGRADED },
            failedCount = qs.count { it == QualityLevel.FAILED },
            samplesKept = report.measurements.any { it.samplesFile != null },
        )
    }
}
