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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容聊天补全客户端(覆盖 OpenAI / DeepSeek / Moonshot / Ollama / Groq…)。
 * 零第三方依赖(HttpURLConnection + kotlinx.serialization)。
 */

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
    val max_tokens: Int = 2048,
    val stream: Boolean = false,
)

@Serializable
data class ChatChoice(val message: ChatMessage)

@Serializable
data class ChatResponse(val choices: List<ChatChoice> = emptyList())

class AiApiException(message: String, val statusCode: Int = 0) : Exception(message)

/** OpenAI 兼容客户端 */
class AiClient(private val config: AiConfigStore.Config) {

    /** 调用对话补全,返回助手回复文本 */
    fun complete(system: String, user: String, timeoutMs: Int = 60_000): String {
        val url = URL("${config.baseUrl}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        val body = Json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = config.model,
                messages = listOf(
                    ChatMessage("system", system),
                    ChatMessage("user", user),
                ),
                temperature = config.temperature,
                max_tokens = config.maxTokens,
            ),
        )
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                throw AiApiException("HTTP $code: ${respText.take(300)}", code)
            }
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(ChatResponse.serializer(), respText)
            return parsed.choices.firstOrNull()?.message?.content ?: throw AiApiException("空响应(无 choices)")
        } finally {
            conn.disconnect()
        }
    }

    /** 测试连接:发一个最小请求 */
    fun test(timeoutMs: Int = 30_000): String {
        return complete("You are a test.", "Reply with exactly: OK", timeoutMs)
    }

    companion object {
        /** 常见预设(供 UI 快速选择) */
        val PRESETS = listOf(
            Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Triple("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Triple("Ollama (本地)", "http://10.0.2.2:11434/v1", "llama3.2"),
            Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        )
    }
}
