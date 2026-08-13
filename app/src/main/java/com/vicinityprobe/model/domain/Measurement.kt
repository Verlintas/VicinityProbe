package com.vicinityprobe.model.domain

import kotlinx.serialization.Serializable

/** 一次测量的完整结果 */
@Serializable
data class Measurement(
    val spec: ProbeSpec,
    val status: String,                 // "OK" | 失败原因码
    val stats: Map<String, ChannelStats> = emptyMap(),          // 数值通道统计
    val attributes: Map<String, String> = emptyMap(),           // 非数值属性(SSID/运营商/明细等)
    val quality: QualityReport,
    val samplesFile: String? = null,    // 相对路径 reports/<id>/samples/<probe>.csv
    val series: Map<String, List<SeriesPt>> = emptyMap(),       // 抽稀时序(供 UI 图表)
    val spectrum: SpectrumResult? = null,                       // 频谱分析结果(音频/振动)
)

@Serializable
data class SeriesPt(val tMs: Long, val v: Double)

/** 频谱分析结果 */
@Serializable
data class SpectrumResult(
    val method: String,                 // "FFT-1024-Hann"
    val dominantFrequencyHz: Double,
    val dominantAmplitude: Double,
    val flatness: Double,               // 频谱平坦度(几何均值/算术均值,0~1)
    val bandEnergy: Map<String, Double> = emptyMap(),  // 频带能量占比(如 low/mid/high)
)

/** 测量计划 */
@Serializable
data class MeasurementPlan(
    val planId: String,
    val createdAt: Long,
    val durationMs: Long,
    val probeIds: List<String>,
    val operator: String,
)

/** 测量环境上下文 */
@Serializable
data class SessionContextInfo(
    val device: String,                 // 制造商 型号
    val androidVersion: String,
    val apiLevel: Int,
    val kernel: String,
    val timezone: String,
    val locale: String,
    val elapsedRealtimeMs: Long,
    val batteryLevelPct: Double? = null,
)

/** 专业测量报告 */
@Serializable
data class MeasurementReport(
    val schemaVersion: Int = 1,
    val id: String,
    val plan: MeasurementPlan,
    val context: SessionContextInfo,
    val measurements: List<Measurement>,
    val analysis: AnalysisSummary? = null,
)

/** 分析摘要:声学/振动/频谱等专业指标 */
@Serializable
data class AnalysisSummary(
    val acoustics: AcousticsSummary? = null,
    val vibration: VibrationSummary? = null,
    val positioning: PositioningSummary? = null,
    val contextClassification: ContextClassification? = null,
)

@Serializable
data class AcousticsSummary(
    val laeqDBA: Double? = null,       // 等效连续 A 计权声级
    val lpeakDBA: Double? = null,      // 峰值声级
    val l90DBA: Double? = null,        // 统计声级(超过 90% 时间)
    val l50DBA: Double? = null,
    val l10DBA: Double? = null,
    val calibrated: Boolean = false,   // 是否经过校准(未校准为参考值)
)

@Serializable
data class VibrationSummary(
    val dominantFrequencyHz: Double? = null,   // 主导振动频率
    val rmsMs2: Double? = null,                // 振动加速度 RMS
    val crestFactor: Double? = null,           // 峰值因子
    val vibrationLevel: String? = null,        // ISO 2631 近似等级(微弱/温和/剧烈)
)

@Serializable
data class PositioningSummary(
    val horizontalAccuracyM: Double? = null,
    val satellitesUsed: Int? = null,
    val satellitesVisible: Int? = null,
    val hdop: Double? = null,
    val fixRatePct: Double? = null,     // 采样窗口内定位可用率
)

@Serializable
data class ContextClassification(
    val classId: String,                // stationary/walking/running/vehicle/motion
    val confidence: Double,             // 0~1
    val features: Map<String, String> = emptyMap(),  // 分类依据特征
)
