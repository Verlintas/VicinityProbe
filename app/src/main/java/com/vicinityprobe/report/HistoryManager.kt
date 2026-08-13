package com.vicinityprobe.report

import android.content.Context
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ReportMeta
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class HistoryManager(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "reports")
    private val indexFile: File get() = File(dir, "index.json")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun save(report: ProbeReport): ReportMeta {
        dir.mkdirs()
        val meta = ReportMetaFactory.from(report)
        File(dir, "${report.id}.json").writeText(JsonReport.encode(report))
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

    private fun rescan() {
        dir.mkdirs()
        val metas = dir.listFiles { f -> f.extension == "json" && f.name != "index.json" }?.mapNotNull { f ->
            try {
                val r = JsonReport.decode(f.readText())
                ReportMetaFactory.from(r)
            } catch (_: Throwable) { null }
        } ?: emptyList()
        indexFile.writeText(json.encodeToString(metas.sortedByDescending { it.createdAt }))
    }

    fun load(id: String): ProbeReport? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            JsonReport.decode(f.readText())
        } catch (_: Throwable) { null }
    }

    fun rename(id: String, newName: String) {
        val metas = list().map {
            if (it.id == id) it.copy(name = newName.trim().ifEmpty { it.name }) else it
        }
        indexFile.writeText(json.encodeToString(metas))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        indexFile.writeText(json.encodeToString(list().filterNot { it.id == id }))
    }

    fun count(): Int = list().size
}
