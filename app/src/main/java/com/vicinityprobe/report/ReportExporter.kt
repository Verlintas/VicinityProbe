package com.vicinityprobe.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeStatus
import java.io.File

object ReportExporter {
    fun writeJson(context: Context, report: ProbeReport): File {
        val f = File(context.filesDir, "reports/${report.id}.json")
        f.writeText(JsonReport.encode(report))
        return f
    }

    fun writeMarkdown(context: Context, report: ProbeReport, lang: String): File {
        val f = File(context.filesDir, "reports/${report.id}.md")
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

    fun writePng(context: Context, report: ProbeReport): File {
        val bitmap = ReportImageRenderer.render(report, context)
        val f = File(context.filesDir, "reports/${report.id}.png")
        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return f
    }

    fun trStatus(context: Context, status: ProbeStatus): String {
        val lang = context.resources.configuration.locales[0].language
        val zh = lang.startsWith("zh")
        return when (status) {
            ProbeStatus.OK -> if (zh) "正常" else "OK"
            ProbeStatus.NO_HARDWARE -> if (zh) "设备不支持" else "Not supported"
            ProbeStatus.PERMISSION_MISSING -> if (zh) "缺少权限" else "Permission required"
            ProbeStatus.FEATURE_OFF -> if (zh) "功能未开启" else "Feature off"
            ProbeStatus.FAILED -> if (zh) "采集失败" else "Failed"
            ProbeStatus.SKIPPED -> if (zh) "未探测" else "Skipped"
        }
    }
}
