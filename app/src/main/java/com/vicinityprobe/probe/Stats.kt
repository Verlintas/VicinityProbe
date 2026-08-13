package com.vicinityprobe.probe

import kotlin.math.sqrt

class Welford {
    var n: Int = 0
        private set
    private var mean = 0.0
    private var m2 = 0.0
    var min: Double = Double.MAX_VALUE
        private set
    var max: Double = -Double.MAX_VALUE
        private set
    var last: Double = 0.0
        private set

    fun add(v: Double) {
        n++
        if (v < min) min = v
        if (v > max) max = v
        last = v
        val d = v - mean
        mean += d / n
        m2 += d * (v - mean)
    }

    fun avg(): Double = if (n == 0) 0.0 else mean
    fun stddev(): Double = if (n == 0) 0.0 else sqrt(m2 / n)
    fun empty(): Boolean = n == 0
}

class AxisStats(val x: Welford, val y: Welford, val z: Welford, val magnitude: Welford)

class Series(maxPoints: Int = 600) {
    private val max = maxPoints
    private val points = ArrayList<Pair<Long, Double>>()
    private var step = 1

    fun add(tMs: Long, v: Double) {
        if (points.size >= max) {
            step = 2
            for (i in 0 until points.size / 2) {
                points[i] = points[i * 2]
            }
            points.subList(points.size / 2, points.size).clear()
        }
        if (points.isEmpty() || (tMs - points.last().first) >= step) {
            points.add(tMs to v)
        }
    }

    fun list(): List<Pair<Long, Double>> = points
}

class LiveMetrics {
    private val map = HashMap<String, Pair<String, String>>() // id -> (label, value)

    @Synchronized
    fun set(id: String, label: String, value: String) {
        map[id] = label to value
    }

    @Synchronized
    fun snapshot(): Map<String, Pair<String, String>> = map.toMap()
}
