/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 分析结果模型与容错解析:
 * AI 可能返回纯 JSON、被 ```json 包裹、或夹带前后说明文字,
 * 解析器提取第一个完整 JSON 对象,失败时降级为纯文本。
 */

@Serializable
data class AiFinding(
    val item: String = "",
    val detail: String = "",
    val severity: String = "info",
)

@Serializable
data class AiRisk(
    val risk: String = "",
    val level: String = "low",
    val suggestion: String = "",
)

@Serializable
data class AiTrend(
    val metric: String = "",
    val direction: String = "stable",
    val detail: String = "",
)

@Serializable
data class AiResult(
    val summary: String = "",
    val grade: String = "",              // 体检专用: A/B/C/D
    val verdict: String = "",            // 体检专用: 一句话结论
    val highlights: List<String> = emptyList(),   // 体检专用
    val concerns: List<String> = emptyList(),     // 体检专用
    val findings: List<AiFinding> = emptyList(),
    val risks: List<AiRisk> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val trends: List<AiTrend> = emptyList(),
) {
    val parsed: Boolean get() = summary.isNotEmpty() || findings.isNotEmpty() || risks.isNotEmpty() || recommendations.isNotEmpty() || trends.isNotEmpty() || grade.isNotEmpty()
}

object AiResultParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 从 AI 回复中提取结构化结果;解析失败返回 null(调用方降级为纯文本) */
    fun parse(raw: String): AiResult? {
        if (raw.isBlank()) return null
        // 1) 直接解析(AI 只回了 JSON)
        runCatching { return json.decodeFromString(AiResult.serializer(), raw) }
        // 2) 提取 ```json ... ``` 块
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        if (fenced != null) {
            runCatching { return json.decodeFromString(AiResult.serializer(), fenced) }
        }
        // 3) 找第一个 { 到最后一个 } 之间的内容
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return json.decodeFromString(AiResult.serializer(), raw.substring(start, end + 1)) }
        }
        return null
    }

    /** severity → 显示色/排序权重 */
    fun severityRank(s: String): Int = when (s.lowercase()) {
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }

    /** risk level → 显示色/排序权重 */
    fun riskRank(s: String): Int = when (s.lowercase()) {
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }
}
