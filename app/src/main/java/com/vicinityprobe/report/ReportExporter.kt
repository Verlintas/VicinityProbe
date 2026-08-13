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
        }
        context.startActivity(Intent.createChooser(intent, null))
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
