/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
