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

            // ---- RADIO ----
            add(ProbeSpec("wifi", "WiFi 连接|WiFi connection", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("wifi_scan", "WiFi 环境扫描|WiFi scan", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("cellular", "蜂窝网络|Cellular network", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("connectivity", "网络接口|Connectivity & interfaces", Category.RADIO, Measurand.OTHER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("bluetooth", "蓝牙设备扫描|Bluetooth scan", Category.RADIO, Measurand.SIGNAL_POWER, UnitDef.DBM, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))
            add(ProbeSpec("bt_paired", "已配对蓝牙|Paired devices", Category.RADIO, Measurand.IDENTIFIER, UnitDef.NONE, nominalRateHz = 0.0, keepRawSamples = false, sampleChannels = emptyList()))

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
