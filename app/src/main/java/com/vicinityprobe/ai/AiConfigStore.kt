/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 配置存储:API Key 用 Android Keystore 加密(AES-GCM)后落盘,不明文。
 * 配置项:baseUrl / apiKey / model / sanitize / temperature。
 */
object AiConfigStore {
    private const val PREFS = "ai_config"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vicinityprobe_ai_key"
    private const val GCM_TAG = 128

    data class Config(
        val baseUrl: String = "https://api.openai.com/v1",
        val apiKey: String = "",
        val model: String = "gpt-4o-mini",
        val sanitize: Boolean = true,
        val temperature: Double = 0.3,
        val maxTokens: Int = 2048,
    )

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = p.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            apiKey = decrypt(p.getString("apiKey", "") ?: ""),
            model = p.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
            sanitize = p.getBoolean("sanitize", true),
            temperature = p.getFloat("temperature", 0.3f).toDouble(),
            maxTokens = p.getInt("maxTokens", 2048),
        )
    }

    fun save(context: Context, config: Config) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putString("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .putString("apiKey", encrypt(config.apiKey))
            .putString("model", config.model.trim())
            .putBoolean("sanitize", config.sanitize)
            .putFloat("temperature", config.temperature.toFloat())
            .putInt("maxTokens", config.maxTokens)
            .apply()
    }

    fun configured(context: Context): Boolean = load(context).apiKey.isNotBlank()

    /** 加密:Keystore 密钥 + AES/GCM */
    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plain   // 降级:Keystore 不可用时明文(极少见)
        }
    }

    /** 解密 */
    private fun decrypt(data: String): String {
        if (data.isEmpty()) return ""
        return try {
            val raw = Base64.decode(data, Base64.NO_WRAP)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Throwable) {
            data
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return kg.generateKey()
    }
}
