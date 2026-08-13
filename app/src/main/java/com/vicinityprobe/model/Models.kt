package com.vicinityprobe.model

fun bil(zh: String, en: String): String = "$zh|$en"

fun trBilingual(s: String, lang: String): String {
    val i = s.indexOf('|')
    return if (i >= 0) (if (lang.startsWith("zh")) s.substring(0, i) else s.substring(i + 1)) else s
}

fun langOf(context: android.content.Context): String = context.resources.configuration.locales[0].language

data class L(val zh: String, val en: String)

object Labels {
    fun tr(lang: String, l: L): String = if (lang.startsWith("zh")) l.zh else l.en
    fun trFor(context: android.content.Context, l: L): String =
        tr(context.resources.configuration.locales[0].language, l)
}
