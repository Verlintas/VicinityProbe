/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe

import com.vicinityprobe.ai.AiResultParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResultParserTest {

    @Test
    fun `解析_纯JSON`() {
        val raw = """{"summary":"总体正常","findings":[{"item":"噪声","detail":"58dB","severity":"low"}],"risks":[{"risk":"磁场","level":"medium","suggestion":"远离"}],"recommendations":["复测"]}"""
        val r = AiResultParser.parse(raw)
        assertNotNull(r)
        assertEquals("总体正常", r!!.summary)
        assertEquals(1, r.findings.size)
        assertEquals("low", r.findings[0].severity)
        assertEquals("medium", r.risks[0].level)
        assertEquals("复测", r.recommendations[0])
    }

    @Test
    fun `解析_Markdown包裹JSON`() {
        val raw = """好的,以下是分析结果:

```json
{"summary":"趋势平稳","trends":[{"metric":"噪声","direction":"stable","detail":"无明显变化"}]}
```

如有疑问请告知。"""
        val r = AiResultParser.parse(raw)
        assertNotNull(r)
        assertEquals("趋势平稳", r!!.summary)
        assertEquals("stable", r.trends[0].direction)
    }

    @Test
    fun `解析_前后有说明文字`() {
        val raw = """分析完成。{"summary":"结论","findings":[],"risks":[],"recommendations":["a","b"]} 以上。"""
        val r = AiResultParser.parse(raw)
        assertNotNull(r)
        assertEquals("结论", r!!.summary)
        assertEquals(listOf("a", "b"), r.recommendations)
    }

    @Test
    fun `解析_非JSON_返回null`() {
        assertNull(AiResultParser.parse("抱歉,我无法分析这段数据"))
        assertNull(AiResultParser.parse(""))
    }

    @Test
    fun `解析_缺字段_有默认值`() {
        val raw = """{"summary":"只有结论"}"""
        val r = AiResultParser.parse(raw)
        assertNotNull(r)
        assertTrue(r!!.findings.isEmpty())
        assertTrue(r.risks.isEmpty())
        assertTrue(r.parsed)
    }

    @Test
    fun `排序权重_高大于低`() {
        assertTrue(AiResultParser.severityRank("high") > AiResultParser.severityRank("medium"))
        assertTrue(AiResultParser.severityRank("medium") > AiResultParser.severityRank("low"))
        assertEquals(0, AiResultParser.riskRank("unknown"))
    }
}
