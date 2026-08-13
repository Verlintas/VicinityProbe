package com.vicinityprobe.report

import com.vicinityprobe.model.domain.MeasurementReport
import kotlinx.serialization.json.Json

object JsonReport {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(report: MeasurementReport): String = json.encodeToString(report)
    fun decode(text: String): MeasurementReport = json.decodeFromString<MeasurementReport>(text)
}
