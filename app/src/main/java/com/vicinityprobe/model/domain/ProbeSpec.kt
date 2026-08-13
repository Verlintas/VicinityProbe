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

package com.vicinityprobe.model.domain

import kotlinx.serialization.Serializable

/** 被测量(Measurand):定义探测项测量的是什么物理量 */
@Serializable
enum class Measurand {
    ACCELERATION,          // 加速度
    ANGULAR_RATE,          // 角速度
    MAGNETIC_FLUX_DENSITY, // 磁通密度
    ILLUMINANCE,           // 照度
    DISTANCE,              // 距离
    PRESSURE,              // 压强
    RELATIVE_HUMIDITY,     // 相对湿度
    TEMPERATURE,           // 温度
    HEART_RATE,            // 心率
    ANGLE,                 // 角度(姿态/方位)
    ORIENTATION_QUATERNION,// 姿态四元数
    VELOCITY,              // 速度
    POSITION,              // 位置(坐标)
    SOUND_PRESSURE,        // 声压
    SOUND_PRESSURE_LEVEL,  // 声压级
    FREQUENCY,             // 频率
    ELECTRIC_POTENTIAL,    // 电压
    ELECTRIC_CURRENT,      // 电流
    ELECTRIC_CHARGE,       // 电荷量
    POWER,                 // 功率
    CAPACITY,              // 容量
    SIGNAL_POWER,          // 信号功率 (dBm)
    SIGNAL_QUALITY,        // 信号质量 (RSRP/RSRQ/SNR)
    COUNT,                 // 计数(步数/触发次数)
    RATIO,                 // 比值(电量百分比/湿度)
    RESOURCE_USAGE,        // 资源占用(CPU/内存/存储)
    TIME_SPAN,             // 时间跨度(运行时长)
    IDENTIFIER,            // 标识符(SSID/运营商等文本)
    BOOLEAN_STATE,         // 状态(开关/是否)
    OCCURRENCE,            // 事件发生(手势触发)
    GEOGRAPHY,             // 地理要素
    CELESTIAL_GEOMETRY,    // 卫星几何(GNSS)
    OTHER,
}

/** 单位:符号 + SI 表述 */
@Serializable
data class UnitDef(val symbol: String, val si: String = "") {
    override fun toString(): String = symbol

    companion object {
        val M_S2 = UnitDef("m/s²", "m·s⁻²")
        val RAD_S = UnitDef("rad/s", "rad·s⁻¹")
        val UT = UnitDef("µT", "T")
        val LUX = UnitDef("lx", "lx")
        val CM = UnitDef("cm", "m")
        val HPA = UnitDef("hPa", "Pa")
        val RH_PCT = UnitDef("%RH", "")
        val CELSIUS = UnitDef("°C", "K")
        val KELVIN = UnitDef("K", "K")
        val BPM = UnitDef("bpm", "min⁻¹")
        val DEG = UnitDef("°", "rad")
        val M_S = UnitDef("m/s", "m·s⁻¹")
        val M = UnitDef("m", "m")
        val DBA = UnitDef("dB(A)", "")
        val DBFS = UnitDef("dBFS", "")
        val DB = UnitDef("dB", "")
        val DBM = UnitDef("dBm", "")
        val HZ = UnitDef("Hz", "s⁻¹")
        val V = UnitDef("V", "V")
        val MV = UnitDef("mV", "V")
        val A = UnitDef("A", "A")
        val MA = UnitDef("mA", "A")
        val UA = UnitDef("µA", "A")
        val MAH = UnitDef("mAh", "C")
        val W = UnitDef("W", "W")
        val MHZ = UnitDef("MHz", "s⁻¹")
        val KS = UnitDef("kbps", "bit·s⁻¹")
        val PCT = UnitDef("%", "")
        val GB = UnitDef("GB", "B")
        val MIN = UnitDef("min", "s")
        val S = UnitDef("s", "s")
        val STEPS = UnitDef("steps", "")
        val CELLS = UnitDef("cells", "")
        val SATELLITES = UnitDef("sats", "")
        val CHANNELS = UnitDef("ch", "")
        val DEGC_PER_W = UnitDef("°C/W", "K·W⁻¹")
        val NONE = UnitDef("", "")
        val MS = UnitDef("ms", "s")
    }
}

/** 探测分类 */
@Serializable
enum class Category {
    MOTION,        // 运动学
    ENVIRONMENT,   // 环境物理量
    MAGNETIC,      // 磁场
    BIOSIGNAL,     // 生物信号
    AUDIO,         // 声学
    POSITIONING,   // 定位与卫星
    RADIO,         // 无线电(蜂窝/WiFi/蓝牙)
    ELECTRICAL,    // 电气
    SYSTEM,        // 系统资源
    DEVICE,        // 设备静态信息
    CONTEXT,       // 上下文事件
    SECURITY,      // 安全与渗透辅助(主动网络探测)
}

/**
 * 探测项规范:成体系的测量注册表条目。
 * 定义被测量、单位、标称采样率、量程、分辨率、依赖与权限。
 */
@Serializable
data class ProbeSpec(
    val id: String,
    val name: String,              // bil("中文","English")
    val category: Category,
    val measurand: Measurand,
    val unit: UnitDef,
    val nominalRateHz: Double = 0.0,   // 0 = 事件驱动/无连续流
    val typicalRange: String = "",     // 系统标称量程描述
    val resolution: String = "",       // 分辨率描述
    val requiredPermissions: List<String> = emptyList(),
    val sampleChannels: List<String> = emptyList(),  // 通道名(如 x/y/z/magnitude)
    val keepRawSamples: Boolean = true,              // 是否存档原始样本
    val notes: String = "",
    /** 合规风险标记:该探测项的采集数据在部分司法辖区可能受法律法规约束 */
    val complianceRisk: Boolean = false,
    /** 合规风险说明(bil 格式) */
    val riskNote: String = "",
)

/** 探测目录:全系统唯一注册表 */
object ProbeCatalog {
    val all: List<ProbeSpec> by lazy {
        buildList {
            // ---- MOTION ----
            addAll(sensor(
                "sensor.accelerometer", "加速度计|Accelerometer", Category.MOTION, Measurand.ACCELERATION, UnitDef.M_S2,
                nominal = 50.0, range = "±2~±16g", channels = listOf("x", "y", "z", "magnitude"),
            ))
            addAll(sensor("sensor.accelerometer_uncal", "加速度计(未校准)|Accelerometer (uncalibrated)", Category.MOTION, Measurand.ACCELERATION, UnitDef.M_S2, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.gyroscope", "陀螺仪|Gyroscope", Category.MOTION, Measurand.ANGULAR_RATE, UnitDef.RAD_S, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.gyroscope_uncal", "陀螺仪(未校准)|Gyroscope (uncalibrated)", Category.MOTION, Measurand.ANGULAR_RATE, UnitDef.RAD_S, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.gravity", "重力|Gravity", Category.MOTION, Measurand.ACCELERATION, UnitDef.M_S2, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.linear_acceleration", "线性加速度|Linear acceleration", Category.MOTION, Measurand.ACCELERATION, UnitDef.M_S2, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.rotation_vector", "旋转向量|Rotation vector", Category.MOTION, Measurand.ORIENTATION_QUATERNION, UnitDef.NONE, nominal = 50.0, channels = listOf("azimuth", "pitch", "roll")))
            addAll(sensor("sensor.game_rotation_vector", "游戏旋转向量|Game rotation vector", Category.MOTION, Measurand.ORIENTATION_QUATERNION, UnitDef.NONE, nominal = 50.0, channels = listOf("azimuth", "pitch", "roll")))
            addAll(sensor("sensor.geomagnetic_rotation", "地磁旋转向量|Geomagnetic rotation vector", Category.MOTION, Measurand.ORIENTATION_QUATERNION, UnitDef.NONE, nominal = 50.0, channels = listOf("azimuth", "pitch", "roll")))
            addAll(sensor("sensor.orientation", "方向(旧)|Orientation (legacy)", Category.MOTION, Measurand.ANGLE, UnitDef.DEG, nominal = 50.0, channels = listOf("azimuth", "pitch", "roll")))

            // ---- ENVIRONMENT ----
            addAll(sensor("sensor.light", "光照强度|Ambient light", Category.ENVIRONMENT, Measurand.ILLUMINANCE, UnitDef.LUX, nominal = 20.0, range = "0.01~120k lx", channels = listOf("value")))
            addAll(sensor("sensor.proximity", "距离传感器|Proximity", Category.ENVIRONMENT, Measurand.DISTANCE, UnitDef.CM, nominal = 20.0, channels = listOf("value")))
            addAll(sensor("sensor.pressure", "气压计|Barometer", Category.ENVIRONMENT, Measurand.PRESSURE, UnitDef.HPA, nominal = 20.0, range = "300~1100 hPa", channels = listOf("value")))
            addAll(sensor("sensor.humidity", "相对湿度|Relative humidity", Category.ENVIRONMENT, Measurand.RELATIVE_HUMIDITY, UnitDef.RH_PCT, nominal = 20.0, channels = listOf("value")))
            addAll(sensor("sensor.temperature", "环境温度|Ambient temperature", Category.ENVIRONMENT, Measurand.TEMPERATURE, UnitDef.CELSIUS, nominal = 20.0, channels = listOf("value")))

            // ---- MAGNETIC ----
            addAll(sensor("sensor.magnetometer", "磁力计|Magnetometer", Category.MAGNETIC, Measurand.MAGNETIC_FLUX_DENSITY, UnitDef.UT, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))
            addAll(sensor("sensor.magnetometer_uncal", "磁力计(未校准)|Magnetometer (uncalibrated)", Category.MAGNETIC, Measurand.MAGNETIC_FLUX_DENSITY, UnitDef.UT, nominal = 50.0, channels = listOf("x", "y", "z", "magnitude")))

            // ---- BIOSIGNAL ----
            addAll(sensor("sensor.heart_rate", "心率|Heart rate", Category.BIOSIGNAL, Measurand.HEART_RATE, UnitDef.BPM, nominal = 1.0, channels = listOf("value")))
            addAll(sensor("sensor.heart_beat", "心率波动|Heart beat", Category.BIOSIGNAL, Measurand.HEART_RATE, UnitDef.BPM, nominal = 1.0, channels = listOf("value")))

            // ---- CONTEXT ----
            addAll(sensor("sensor.step_counter", "计步器|Step counter", Category.CONTEXT, Measurand.COUNT, UnitDef.STEPS, nominal = 1.0, channels = emptyList()))
            addAll(sensor("sensor.step_detector", "单步检测|Step detector", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.significant_motion", "显著运动|Significant motion", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.activity", "活动识别|Activity recognition", Category.CONTEXT, Measurand.IDENTIFIER, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.device_orientation", "设备朝向检测|Device orientation", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.pick_up", "拿起手势|Pick up gesture", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.shake", "摇晃检测|Shake", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.flip", "翻转检测|Flip", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.free_fall", "自由落体|Free fall", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.tilt", "倾斜检测|Tilt detector", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.wrist_tilt", "手腕倾斜|Wrist tilt", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.wake", "唤醒手势|Wake up gesture", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.glance", "扫视手势|Glance gesture", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))
            addAll(sensor("sensor.offbody", "离身检测|Off-body detect", Category.CONTEXT, Measurand.OCCURRENCE, UnitDef.NONE, nominal = 0.0, channels = emptyList()))

            // ---- POSITIONING ----
            add(ProbeSpec("location", "位置定位|Location", Category.POSITIONING, Measurand.POSITION, UnitDef.M, nominalRateHz = 1.0,
                keepRawSamples = false, sampleChannels = listOf("latitude", "longitude", "altitude", "accuracy", "speed", "bearing")))
            add(ProbeSpec("gnss", "GNSS 卫星|GNSS satellites", Category.POSITIONING, Measurand.CELESTIAL_GEOMETRY, UnitDef.SATELLITES, nominalRateHz = 1.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("nmea", "NMEA 定位质量|NMEA fix quality", Category.POSITIONING, Measurand.CELESTIAL_GEOMETRY, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("gnss_raw", "GNSS 原始观测量|GNSS raw measurements", Category.POSITIONING, Measurand.CELESTIAL_GEOMETRY, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("gnss_hw", "GNSS 硬件信息|GNSS hardware info", Category.POSITIONING, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))

            // ---- RADIO ----
            add(ProbeSpec("wifi", "WiFi 连接|WiFi connection", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("wifi_dynamic", "WiFi 链路动态|WiFi link dynamics", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 5.0,
                sampleChannels = listOf("rssi")))
            add(ProbeSpec("wifi_scan", "WiFi 环境扫描|WiFi scan", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("wifi_rtt", "WiFi RTT 测距|WiFi RTT ranging", Category.RADIO, Measurand.DISTANCE, UnitDef.M, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("wifi_direct", "WiFi Direct 对等发现|WiFi Direct peers", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("wifi_aware", "Wi-Fi Aware 感知能力|Wi-Fi Aware capability", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("cellular", "蜂窝网络|Cellular network", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("cellular_series", "蜂窝信号时序|Cellular signal series", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 2.0,
                sampleChannels = listOf("level", "dbm")))
            add(ProbeSpec("connectivity", "网络接口|Connectivity & interfaces", Category.RADIO, Measurand.OTHER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("network_stats", "流量与套接字统计|Traffic & socket stats", Category.RADIO, Measurand.COUNT, UnitDef.GB, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("bluetooth", "蓝牙设备扫描|Bluetooth scan", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("bt_classic", "经典蓝牙发现|Classic BT discovery", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("bt_paired", "已配对蓝牙|Paired devices", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("nfc", "NFC 能力与标签|NFC capability & tags", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("fm_radio", "FM 调谐器|FM radio tuner", Category.RADIO, Measurand.FREQUENCY, UnitDef.MHZ, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("infrared", "红外发射器|IR emitter", Category.RADIO, Measurand.FREQUENCY, UnitDef.HZ, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))

            // ---- AUDIO ----
            add(ProbeSpec("noise", "环境声压级|Ambient SPL", Category.AUDIO, Measurand.SOUND_PRESSURE_LEVEL, UnitDef.DBA, nominalRateHz = 100.0,
                typicalRange = "0~120 dB(A)", sampleChannels = listOf("LAeq", "Lpeak")))
            add(ProbeSpec("audio_state", "音频设备状态|Audio device state", Category.AUDIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))

            // ---- ELECTRICAL / SYSTEM / DEVICE ----
            add(ProbeSpec("battery", "电池电气参数|Battery electrical", Category.ELECTRICAL, Measurand.ELECTRIC_POTENTIAL, UnitDef.V, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("device", "设备静态信息|Device identity", Category.DEVICE, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("system", "系统资源状态|System resources", Category.SYSTEM, Measurand.RESOURCE_USAGE, UnitDef.PCT, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("thermal", "热状态|Thermal status", Category.SYSTEM, Measurand.TEMPERATURE, UnitDef.CELSIUS, nominalRateHz = 1.0,
                sampleChannels = listOf("value")))
            add(ProbeSpec("power_state", "CPU 电源状态|CPU power state", Category.SYSTEM, Measurand.FREQUENCY, UnitDef.MHZ, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("kernel", "内核与安全|Kernel & security", Category.SYSTEM, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("display", "显示能力|Display capabilities", Category.DEVICE, Measurand.FREQUENCY, UnitDef.HZ, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("storage", "存储卷|Storage volumes", Category.SYSTEM, Measurand.RESOURCE_USAGE, UnitDef.GB, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("proc_net_conn", "网络连接表|Network connection table", Category.SYSTEM, Measurand.COUNT, UnitDef.CHANNELS, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("proc_meminfo", "内核内存明细|Kernel memory detail", Category.SYSTEM, Measurand.RESOURCE_USAGE, UnitDef.GB, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("cpu_per_core", "逐核 CPU 使用率|Per-core CPU usage", Category.SYSTEM, Measurand.RESOURCE_USAGE, UnitDef.PCT, nominalRateHz = 2.0,
                sampleChannels = emptyList()))
            add(ProbeSpec("disk_stats", "磁盘 IO 统计|Disk IO stats", Category.SYSTEM, Measurand.COUNT, UnitDef.NONE, nominalRateHz = 1.0,
                sampleChannels = emptyList()))
            add(ProbeSpec("proc_uptime", "开机与运行统计|Boot & run stats", Category.SYSTEM, Measurand.TIME_SPAN, UnitDef.S, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("sensor_calib", "传感器校准分析|Sensor calibration analysis", Category.MOTION, Measurand.ACCELERATION, UnitDef.M_S2, nominalRateHz = 50.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("battery_drain", "电池放电速率|Battery drain rate", Category.ELECTRICAL, Measurand.POWER, UnitDef.W, nominalRateHz = 2.0,
                sampleChannels = listOf("power_mw")))
            add(ProbeSpec("wifi_channel", "WiFi 信道占用分析|WiFi channel analysis", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))

            // ---- SECURITY 安全与渗透辅助(主动网络探测) ----
            add(ProbeSpec("net_arp", "局域网主机发现|LAN host discovery", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_portscan", "端口扫描|Port scan", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_http_fingerprint", "HTTP/TLS 指纹|HTTP/TLS fingerprint", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_dns", "DNS 解析测试|DNS resolution test", Category.SECURITY, Measurand.TIME_SPAN, UnitDef.MS, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_ssdp", "SSDP/UPnP 设备发现|SSDP/UPnP discovery", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_ping", "网关连通性测试|Gateway reachability", Category.SECURITY, Measurand.TIME_SPAN, UnitDef.MS, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_banner", "服务 Banner 抓取|Service banner grab", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_http_methods", "HTTP 方法探测|HTTP methods probe", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_http_security", "HTTP 安全头分析|HTTP security headers", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_tls_versions", "TLS 版本探测|TLS version probe", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_ntp", "NTP 时间偏移|NTP time offset", Category.SECURITY, Measurand.TIME_SPAN, UnitDef.MS, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_proxy", "系统代理配置|System proxy config", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_subnet_scan", "全子网存活扫描|Full subnet scan", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_mqtt", "MQTT Broker 探测|MQTT broker probe", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_http_paths", "Web 路径探测|Web path probe", Category.SECURITY, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("net_tcp_concurrency", "并发连接测试|TCP concurrency test", Category.SECURITY, Measurand.COUNT, UnitDef.CHANNELS, nominalRateHz = 0.0,
                keepRawSamples = false, sampleChannels = emptyList()))
        }.map { spec ->
            // 合规风险标注:采集数据在部分司法辖区可能受法律法规约束
            val risks = mapOf(
                "wifi_scan" to "扫到周边网络的 SSID/BSSID,部分地区按个人数据管|Scanning nearby SSIDs/BSSIDs may be treated as personal data in some regions",
                "wifi_dynamic" to "连接信息里带网络标识,敏感场合慎用|Connection info contains network identifiers; use with care",
                "wifi_rtt" to "对周边 AP 实测距离,可能归到位置数据管理|Measuring distance to nearby APs may fall under location-data rules",
                "wifi_direct" to "设备发现会碰到第三方设备信息|P2P discovery exposes third-party device info",
                "wifi_aware" to "感知发现周边设备,可能涉及个人数据|Aware discovery may involve personal data",
                "bluetooth" to "扫别人的设备 MAC/名称,部分地区按个人数据管|Scanning third-party device MAC/names may be personal data in some regions",
                "bt_classic" to "扫别人的设备 MAC/名称,部分地区按个人数据管|Scanning third-party device MAC/names may be personal data in some regions",
                "bt_paired" to "已配对设备列表属于个人数据|Paired-device list is personal data",
                "cellular" to "采小区标识,部分国家有专门规定|Cell identity collection is regulated in some countries",
                "cellular_series" to "采小区标识和信号时序,部分国家有规定|Cell identity & signal series are regulated in some countries",
                "location" to "高精度定位数据,部分国家管得严|High-precision location data is strictly regulated in some countries",
                "gnss" to "GNSS 卫星观测数据,部分国家有规定|GNSS satellite data is regulated in some countries",
                "nmea" to "NMEA 定位数据,部分国家有规定|NMEA positioning data is regulated in some countries",
                "gnss_raw" to "GNSS 原始观测量属于高精度定位数据,部分国家管得严|GNSS raw measurements are high-precision positioning data, strictly regulated in some countries",
                "gnss_hw" to "硬件信息能看出设备的定位能力|Hardware info reveals device positioning capability",
                "noise" to "用麦克风采声学数据,注意当地录音和隐私规定|Microphone acquisition — mind local recording & privacy laws",
                "nfc" to "读标签可能碰到别人的设备信息|Tag reading may touch third-party device info",
                "sensor.heart_rate" to "心率属于健康数据,个人数据保护规则管得严|Heart rate is health data under strict data-protection rules",
                "sensor.heart_beat" to "心率数据,个人数据保护规则管得严|Heart-rate data under strict data-protection rules",
                "kernel" to "设备序列号属于个人标识符|Device serial is a personal identifier",
                "net_arp" to "主动探测局域网设备,部分国家受网络安全法规约束|Active LAN probing may be regulated by network-security laws in some countries",
                "net_portscan" to "端口扫描属主动网络探测,部分国家受网络安全法规约束|Port scanning is active network probing, regulated in some countries",
                "net_http_fingerprint" to "指纹识别可能触及第三方服务信息|Fingerprinting may touch third-party service info",
                "net_ssdp" to "组播发现会暴露周边设备信息|Multicast discovery exposes nearby device info",
                "net_banner" to "服务识别可能触及第三方服务版本信息|Service identification may touch third-party version info",
                "net_http_methods" to "方法探测属主动安全测试行为|HTTP method probing is an active security test",
                "net_http_security" to "安全头分析属主动安全测试行为|Security-header analysis is an active security test",
                "net_tls_versions" to "TLS 版本探测属主动安全测试行为|TLS version probing is an active security test",
                "net_ntp" to "NTP 探测可能泄露本地时间信息|NTP probing may reveal local time info",
                "net_subnet_scan" to "全子网扫描属高强度主动探测,部分国家受网络安全法规约束|Full subnet scanning is high-intensity probing, regulated in some countries",
                "net_mqtt" to "MQTT 探测可能触及第三方服务|MQTT probing may touch third-party services",
                "net_http_paths" to "路径枚举属主动安全测试行为|Path enumeration is an active security test",
                "net_tcp_concurrency" to "并发连接测试属主动网络行为|Concurrency testing is active network behavior",
            )
            risks[spec.id]?.let { note -> spec.copy(complianceRisk = true, riskNote = note) } ?: spec
        }
    }

    private fun sensor(
        id: String, name: String, category: Category, measurand: Measurand, unit: UnitDef,
        nominal: Double, channels: List<String>, range: String = "",
        permissions: List<String> = emptyList(), keepRaw: Boolean = true,
    ): List<ProbeSpec> = listOf(
        ProbeSpec(
            id = id, name = name, category = category, measurand = measurand, unit = unit,
            nominalRateHz = nominal, typicalRange = range, sampleChannels = channels,
            keepRawSamples = keepRaw, requiredPermissions = permissions,
        ),
    )

    fun byId(id: String): ProbeSpec? = all.firstOrNull { it.id == id }

    fun sensorSpecs(): List<ProbeSpec> = all.filter { it.category != Category.DEVICE && it.category != Category.SYSTEM && it.id.startsWith("sensor.") }
}
