/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/**
 * 统一触觉反馈:主要操作(开始/记录/停止)给轻震动,破坏性操作给重震动。
 * 基于 View.performHapticFeedback,与 Compose 版本无关。
 * ```
 * val haptics = rememberAppHaptics()
 * Button(onClick = { haptics.tap(); ... })
 * ```
 */
class AppHaptics(private val view: android.view.View) {
    /** 轻触:常规点击确认 */
    fun tap() {
        try { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) {}
    }

    /** 中等:开始/停止等状态切换 */
    fun confirm() {
        try { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) } catch (_: Throwable) {}
    }

    /** 重:清空/删除等破坏性操作 */
    fun heavy() {
        try { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
    }

    /** 拒绝/失败 */
    fun reject() {
        try { view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT) } catch (_: Throwable) {}
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics = AppHaptics(LocalView.current)

/**
 * 屏幕常亮:组合期间保持屏幕唤醒,离开时恢复。
 * 用于扫描/实时监测/抓包/记录类页面。
 */
@Composable
fun rememberKeepScreenOn() {
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

/**
 * 数字动画文本:数值变化时平滑过渡(1s 淡入淡出)。
 * 用于实时读数(声级/姿态/信号)等高频变化场景。
 */
@Composable
fun AnimatedNumber(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        },
        label = "value",
    ) { v ->
        androidx.compose.material3.Text(v, style = style, color = color)
    }
}
