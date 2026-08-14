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
import android.content.Intent
import androidx.core.content.FileProvider
import com.vicinityprobe.model.domain.MeasurementReport
import java.io.File

object ReportExporter {
    fun writeJson(context: Context, report: MeasurementReport): File {
        val f = File(context.filesDir, "reports/${report.id}/report.json")
        f.parentFile?.mkdirs()
        f.writeText(JsonReport.encode(report))
        return f
    }

    fun writeMarkdown(context: Context, report: MeasurementReport, lang: String): File {
        val f = File(context.filesDir, "reports/${report.id}/${report.id}.md")
        f.parentFile?.mkdirs()
        f.writeText(MarkdownWriter.write(report, lang))
        return f
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 导出报告目录(JSON + 原始样本 CSV)为 zip 共享 */
    fun shareZip(context: Context, report: MeasurementReport): File? {
        val reportDir = File(context.filesDir, "reports/${report.id}")
        if (!reportDir.exists()) return null
        val zipFile = File(context.cacheDir, "${report.id}.zip")
        zipFile.outputStream().use { out ->
            java.util.zip.ZipOutputStream(out).use { zos ->
                reportDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val entryName = f.relativeTo(reportDir).path
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return zipFile
    }
}
