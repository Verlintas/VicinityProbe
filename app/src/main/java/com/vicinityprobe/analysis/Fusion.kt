/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.analysis

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 传感器融合算法(纯数学,无 Android 依赖,可单元测试)。
 *
 * 1) [complementary] 互补滤波器:加速度计(低频可信的姿态参考) + 陀螺仪(高频角速度积分),
 *    输出 roll/pitch,同时估计陀螺零偏,抑制漂移。
 * 2) [tiltCompensatedHeading] 倾斜补偿方位角:仅用加速度 + 磁力计计算指南针航向,
 *    手机任意姿态(非水平)时仍准确。
 */

/** 姿态角结果(弧度) */
data class Attitude(
    val roll: Double,    // 绕 x 轴(横滚),弧度
    val pitch: Double,   // 绕 y 轴(俯仰),弧度
    val yaw: Double,     // 绕 z 轴(偏航),弧度
)

/** 互补滤波器状态机(单实例按采样周期推进) */
class ComplementaryFilter(private val alpha: Double = 0.02) {
    private var roll = 0.0
    private var pitch = 0.0
    private var gyroBiasX = 0.0
    private var gyroBiasY = 0.0
    private var initialized = false

    /** 加速度计姿态参考(静止时重力即参考) */
    fun accelAttitude(ax: Float, ay: Float, az: Float): Attitude {
        val rollRef = atan2(ay.toDouble(), az.toDouble())
        val pitchRef = atan2(-ax.toDouble(), sqrt(ay.toDouble() * ay + az.toDouble() * az))
        return Attitude(rollRef, pitchRef, 0.0)
    }

    /**
     * 单步更新。
     * @param dt 距上次采样的秒数
     * @param gx gy  gz 陀螺仪角速度 rad/s
     * @param ax  ay  az 加速度 m/s²
     */
    fun update(dt: Double, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float): Attitude {
        val ref = accelAttitude(ax, ay, az)
        if (!initialized) {
            roll = ref.roll
            pitch = ref.pitch
            gyroBiasX = gx.toDouble()
            gyroBiasY = gy.toDouble()
            initialized = true
        }
        // 去零偏的角速度
        val wx = gx - gyroBiasX
        val wy = gy - gyroBiasY
        // 陀螺仪积分(欧拉近似,小幅角运动适用)
        roll += wx * dt
        pitch += wy * dt
        // 互补融合:高频信陀螺,低频信加速度
        roll = (1 - alpha) * roll + alpha * ref.roll
        pitch = (1 - alpha) * pitch + alpha * ref.pitch
        return Attitude(roll, pitch, 0.0)
    }

    fun state(): Attitude = Attitude(roll, pitch, 0.0)

    companion object {
        /** 批量处理一段同步采样(需三个传感器同频近似),返回姿态序列 */
        fun batch(
            accel: List<FloatArray>,
            gyro: List<FloatArray>,
            dtSeconds: Double,
        ): List<Attitude> {
            val filter = ComplementaryFilter()
            val n = minOf(accel.size, gyro.size)
            if (n == 0) return emptyList()
            val out = ArrayList<Attitude>(n)
            for (i in 0 until n) {
                val a = accel[i]
                val g = gyro[i]
                if (a.size < 3 || g.size < 3) continue
                out.add(filter.update(dtSeconds, g[0], g[1], g[2], a[0], a[1], a[2]))
            }
            return out
        }
    }
}

/**
 * 倾斜补偿方位角(纯数学):
 * 1) 由加速度得到姿态旋转矩阵的滚转/俯仰角
 * 2) 用该姿态把磁力计矢量投影回水平面
 * 3) 水平面磁分量夹角即航向(磁北)
 *
 * @return 方位角(度,0=磁北,顺时针);输入无效返回 null
 */
fun tiltCompensatedHeading(ax: Float, ay: Float, az: Float, mx: Float, my: Float, mz: Float): Double? {
    val g = sqrt(ax.toDouble() * ax + ay.toDouble() * ay + az.toDouble() * az)
    if (g < 1e-3) return null
    val roll = atan2(ay.toDouble(), az.toDouble())
    val pitch = atan2(-ax.toDouble(), sqrt(ay.toDouble() * ay + az.toDouble() * az))
    val cr = kotlin.math.cos(roll)
    val sr = kotlin.math.sin(roll)
    val cp = kotlin.math.cos(pitch)
    val sp = kotlin.math.sin(pitch)
    // 磁矢量旋转回水平面(先 roll 再 pitch)
    val mxh = mx.toDouble() * cp + my.toDouble() * sp * sr + mz.toDouble() * sp * cr
    val myh = my.toDouble() * cr - mz.toDouble() * sr
    val heading = Math.toDegrees(atan2(myh, mxh))
    return (heading + 360.0) % 360.0
}

/**
 * 磁力计标定:最小二乘硬铁偏移估计(球心拟合)。
 * 对一组全姿态样本求解 [Σ(||v - c||²)] 最小化的中心 c。
 * 简化求解:对线性方程组 v·v = 2c·v + (||c||² - r²) 做最小二乘。
 */
object MagCalib {
    data class Result(val offsetX: Double, val offsetY: Double, val offsetZ: Double, val radius: Double, val samples: Int)

    fun fit(samples: List<FloatArray>): Result? {
        if (samples.size < 9) return null
        val n = samples.size
        // 正规方程(4 未知:cx, cy, cz, d = ||c||² - r²)
        // 每行: [2x, 2y, 2z, 1] * [cx,cy,cz,d]ᵀ = x²+y²+z²
        val a = Array(4) { DoubleArray(4) }
        val b = DoubleArray(4)
        for (s in samples) {
            if (s.size < 3) continue
            val x = s[0].toDouble(); val y = s[1].toDouble(); val z = s[2].toDouble()
            val row = doubleArrayOf(2 * x, 2 * y, 2 * z, 1.0)
            val rhs = x * x + y * y + z * z
            for (i in 0 until 4) {
                b[i] += row[i] * rhs
                for (j in 0 until 4) a[i][j] += row[i] * row[j]
            }
        }
        val sol = gaussianSolve(a, b) ?: return null
        val cx = sol[0]; val cy = sol[1]; val cz = sol[2]; val d = sol[3]
        // 展开方程:x²+y²+z² = 2c·v + d,其中 d = r² - ||c||² → r² = ||c||² + d
        val r2 = cx * cx + cy * cy + cz * cz + d
        if (r2 <= 0 || !r2.isFinite()) return null
        return Result(cx, cy, cz, sqrt(r2), n)
    }

    /** 高斯消元(部分选主元)解 4×4 线性方程组,主元过小返回 null */
    private fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(4) { i -> DoubleArray(5) { j -> if (j < 4) a[i][j] else b[i] } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[piv][col])) piv = r
            val pv = m[piv][col]
            if (kotlin.math.abs(pv) < 1e-12) return null
            if (piv != col) {
                val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            }
            val pivot = m[col][col]
            for (j in col..4) m[col][j] /= pivot
            for (r in 0 until 4) if (r != col) {
                val f = m[r][col]
                if (f != 0.0) for (j in col..4) m[r][j] -= f * m[col][j]
            }
        }
        return DoubleArray(4) { m[it][4] }
    }
}
