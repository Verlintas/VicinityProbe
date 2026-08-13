package com.vicinityprobe.report

import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.trBilingual

data class CompareRow(
    val group: String,
    val probeName: String,
    val metricLabel: String,
    val valueA: String,
    val valueB: String,
    val changed: Boolean,
)

data class CompareResult(
    val scoreA: Double?,
    val scoreB: Double?,
    val okCountA: Int,
    val okCountB: Int,
    val rows: List<CompareRow>,
)

object CompareEngine {
    fun compare(a: ProbeReport, b: ProbeReport): CompareResult {
        val rows = ArrayList<CompareRow>()
        val byIdA = a.results.associateBy { it.id }
        val byIdB = b.results.associateBy { it.id }
        val ids = (byIdA.keys + byIdB.keys)
            .filter { it in byIdA && it in byIdB }
            .sortedBy { Groups.ordered.indexOf(byIdA[it]!!.group) }

        for (id in ids) {
            val ra = byIdA[id]!!
            val rb = byIdB[id]!!
            val aMetrics = ra.metrics.associateBy { it.key }
            val bMetrics = rb.metrics.associateBy { it.key }
            val keys = aMetrics.keys + bMetrics.keys
            for (key in keys) {
                val ma = aMetrics[key]
                val mb = bMetrics[key]
                if (ma == null || mb == null) continue
                val va = ma.value.trim()
                val vb = mb.value.trim()
                if (va == vb) continue
                rows.add(
                    CompareRow(
                        group = ra.group,
                        probeName = ra.name,
                        metricLabel = ma.label,
                        valueA = va,
                        valueB = vb,
                        changed = true,
                    )
                )
            }
            if (ra.status != rb.status) {
                rows.add(CompareRow(ra.group, ra.name, "status", ra.status.name, rb.status.name, true))
            }
        }
        return CompareResult(
            scoreA = a.analysis?.overallScore,
            scoreB = b.analysis?.overallScore,
            okCountA = a.results.count { it.status == ProbeStatus.OK },
            okCountB = b.results.count { it.status == ProbeStatus.OK },
            rows = rows.sortedBy { it.probeName },
        )
    }
}
