package com.vicinityprobe

import com.vicinityprobe.probe.Series
import com.vicinityprobe.probe.Welford
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun welford_matches_expected() {
        val w = Welford()
        listOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach { w.add(it) }
        assertEquals(5, w.n)
        assertEquals(1.0, w.min, 1e-9)
        assertEquals(5.0, w.max, 1e-9)
        assertEquals(3.0, w.avg(), 1e-9)
        assertEquals(5.0, w.last, 1e-9)
        assertEquals(1.414213562, w.stddev(), 1e-6)
    }

    @Test
    fun welford_empty_is_guarded() {
        val w = Welford()
        assertTrue(w.empty())
        assertEquals(0.0, w.avg(), 1e-9)
        assertEquals(0.0, w.stddev(), 1e-9)
    }

    @Test
    fun series_caps_and_decimates() {
        val s = Series(maxPoints = 100)
        var t = 0L
        repeat(1000) { s.add(t, it.toDouble()); t += 1 }
        assertTrue(s.list().size <= 100)
        assertEquals(0L, s.list().first().first)
    }
}
