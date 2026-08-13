package com.vicinityprobe.probe

import com.vicinityprobe.model.L
import com.vicinityprobe.model.bil

fun resultBuilder(
    id: String,
    group: String,
    name: L,
    status: com.vicinityprobe.model.ProbeStatus,
    note: String? = null,
    metrics: List<com.vicinityprobe.model.Metric> = emptyList(),
    series: Map<String, List<Pair<Long, Double>>> = emptyMap(),
): com.vicinityprobe.model.ProbeResult = com.vicinityprobe.model.ProbeResult(
    id = id,
    group = group,
    name = bil(name.zh, name.en),
    status = status,
    note = note,
    metrics = metrics,
    series = series.mapValues { it.value.map { p -> com.vicinityprobe.model.SeriesPoint(p.first, p.second) } },
)

fun metric(key: String, label: L, value: String, unit: String? = null, primary: Boolean = false) =
    com.vicinityprobe.model.Metric(key, bil(label.zh, label.en), value, unit, primary)
