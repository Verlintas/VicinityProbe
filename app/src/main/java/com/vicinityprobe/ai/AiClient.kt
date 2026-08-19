/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ai

import kotlinx.coroutines.withContext
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

    /**
     * 探测局域网内监听 11434 端口的 Ollama 主机。
     * 候选:网关 / 网关±1 / 本机同网段 .1-.8 / localhost。
     * 并行 Socket 探测(每地址 300ms 超时),返回第一个成功的 baseUrl。
     */
    suspend fun probeLocalOllama(context: android.content.Context): String? {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val candidates = LinkedHashSet<String>()
            candidates.add("127.0.0.1")
            try {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val lp = cm.getLinkProperties(cm.activeNetwork)
                lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress?.let { gw ->
                    candidates.add(gw)
                    val base = gw.substringBeforeLast('.', gw)
                    if (base != gw) {
                        listOf(1, 2, 3, 4, 5, 6, 7, 8).forEach { candidates.add("$base.$it") }
                    }
                }
                lp?.linkAddresses?.firstOrNull()?.address?.hostAddress?.let { ip ->
                    val base = ip.substringBeforeLast('.', ip)
                    if (base != ip) listOf(1, 2, 3, 4).forEach { candidates.add("$base.$it") }
                }
            } catch (_: Throwable) {}
            candidates.firstOrNull { host ->
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, 11434), 300)
                    }
                    true
                } catch (_: Throwable) { false }
            }?.let { "http://$it:11434/v1" }
        }
    }

    companion object {
        /** 预设服务:名称 / baseUrl / 可选模型列表(第一个为默认) */
        data class Preset(
            val name: String,
            val baseUrl: String,
            val models: List<String>,
        ) {
            val defaultModel: String get() = models.first()
        }

        val PRESETS = listOf(
            Preset("OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o3-mini")),
            Preset("DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner")),
            Preset("Kimi", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest")),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-4-flash", "glm-4-plus", "glm-4-long")),
            Preset("通义 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-plus", "qwen-turbo", "qwen-max")),
            Preset("Groq", "https://api.groq.com/openai/v1", listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768")),
            Preset("Mistral", "https://api.mistral.ai/v1", listOf("mistral-small-latest", "mistral-medium-latest", "open-mistral-nemo")),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", listOf("openai/gpt-4o-mini", "deepseek/deepseek-chat", "anthropic/claude-3.5-sonnet")),
            Preset("xAI Grok", "https://api.x.ai/v1", listOf("grok-beta", "grok-2-latest")),
            Preset("Ollama (本地)", ollamaBaseUrl(), listOf("llama3.2", "qwen2.5", "gemma2", "mistral")),
            Preset("LocalAI (本地)", if (isEmulator()) "http://10.0.2.2:8080/v1" else "http://127.0.0.1:8080/v1", listOf("gpt-4o-mini", "llama3.2")),
        )

        /** 模拟器检测:模拟器上 10.0.2.2 才是宿主机 */
        fun isEmulator(): Boolean {
            val fp = android.os.Build.FINGERPRINT
            return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk") ||
                android.os.Build.MODEL.contains("Emulator") || android.os.Build.MODEL.contains("sdk_gphone")
        }

        /** Ollama 本地默认地址:模拟器用 10.0.2.2,真机用 127.0.0.1(需 Ollama 监听本机) */
        fun ollamaBaseUrl(): String = if (isEmulator()) "http://10.0.2.2:11434/v1" else "http://127.0.0.1:11434/v1"
    }
}
