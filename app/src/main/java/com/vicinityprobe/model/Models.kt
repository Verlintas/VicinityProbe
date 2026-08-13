package com.vicinityprobe.model

import kotlinx.serialization.Serializable

enum class ProbeStatus {
    OK,
    NO_HARDWARE,
    PERMISSION_MISSING,
    FEATURE_OFF,
    FAILED,
    SKIPPED,
}

@Serializable
data class Metric(
    val key: String,
    val label: String,
    val value: String,
    val unit: String? = null,
    val isPrimary: Boolean = false,
)

@Serializable
data class SeriesPoint(val tMs: Long, val v: Double)

@Serializable
data class ProbeResult(
    val id: String,
    val group: String,
    val name: String,
    val status: ProbeStatus,
    val note: String? = null,
    val metrics: List<Metric> = emptyList(),
    val series: Map<String, List<SeriesPoint>> = emptyMap(),
)

@Serializable
data class RadarAxis(val label: String, val score: Double)

@Serializable
data class WeatherComparison(
    val fetched: Boolean,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val pressureHpa: Double? = null,
    val windSpeedKph: Double? = null,
    val conditionCode: Int? = null,
    val conditionText: String? = null,
    val note: String? = null,
)

@Serializable
data class EnvironmentAnalysis(
    val scores: Map<String, Double>,
    val overallScore: Double,
    val scene: String,
    val weather: WeatherComparison? = null,
    val suggestions: List<String>,
    val radar: List<RadarAxis>,
)

@Serializable
data class ProbeReport(
    val id: String,
    val createdAt: Long,
    val scanDurationMs: Long,
    val mode: String,
    val deviceName: String,
    val results: List<ProbeResult>,
    val analysis: EnvironmentAnalysis? = null,
)

@Serializable
data class ReportMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val mode: String,
    val deviceName: String,
    val probeCount: Int,
    val okCount: Int,
    val overallScore: Double? = null,
    val scene: String? = null,
)

object Groups {
    const val SENSOR = "sensor"
    const val LOCATION = "location"
    const val NETWORK = "network"
    const val AUDIO = "audio"
    const val BATTERY = "battery"
    const val DEVICE = "device"

    val ordered = listOf(SENSOR, LOCATION, NETWORK, AUDIO, BATTERY, DEVICE)

    fun label(group: String): L = when (group) {
        SENSOR -> Labels.SENSORS
        LOCATION -> Labels.LOCATION_GROUP
        NETWORK -> Labels.NETWORK_GROUP
        AUDIO -> Labels.AUDIO_GROUP
        BATTERY -> Labels.BATTERY_GROUP
        DEVICE -> Labels.DEVICE_GROUP
        else -> L(group, group)
    }
}

data class L(val zh: String, val en: String)

object Labels {
    fun tr(lang: String, l: L): String = if (lang.startsWith("zh")) l.zh else l.en
    fun trFor(context: android.content.Context, l: L): String =
        tr(context.resources.configuration.locales[0].language, l)

    // ---- groups ----
    val SENSORS = L("传感器", "Sensors")
    val LOCATION_GROUP = L("位置与GNSS", "Location & GNSS")
    val NETWORK_GROUP = L("网络", "Network")
    val AUDIO_GROUP = L("音频", "Audio")
    val BATTERY_GROUP = L("电量", "Battery")
    val DEVICE_GROUP = L("设备与系统", "Device & System")

    // ---- common ----
    val OK = L("正常", "OK")
    val NO_HARDWARE = L("设备不支持", "Not supported on device")
    val PERMISSION_MISSING = L("缺少权限", "Permission required")
    val FEATURE_OFF = L("功能未开启", "Feature is off")
    val FAILED = L("采集失败", "Failed")
    val SKIPPED = L("未探测", "Skipped")
    val RUNNING = L("采集中…", "Sampling…")

    // ---- sensor names ----
    val ACCEL = L("加速度计", "Accelerometer")
    val ACCEL_UNCAL = L("加速度计(未校准)", "Accelerometer (uncalibrated)")
    val GYRO = L("陀螺仪", "Gyroscope")
    val GYRO_UNCAL = L("陀螺仪(未校准)", "Gyroscope (uncalibrated)")
    val MAG = L("磁力计", "Magnetometer")
    val MAG_UNCAL = L("磁力计(未校准)", "Magnetometer (uncalibrated)")
    val GRAVITY = L("重力", "Gravity")
    val LINEAR_ACC = L("线性加速度", "Linear acceleration")
    val ROTATION = L("旋转向量", "Rotation vector")
    val GAME_ROT = L("游戏旋转向量", "Game rotation vector")
    val GEO_ROT = L("地磁旋转向量", "Geomagnetic rotation vector")
    val ORIENTATION = L("方向(旧)", "Orientation (legacy)")
    val LIGHT = L("光照强度", "Ambient light")
    val PROXIMITY = L("距离传感器", "Proximity")
    val PRESSURE = L("气压计", "Barometer")
    val HUMIDITY = L("相对湿度", "Relative humidity")
    val TEMPERATURE = L("环境温度", "Ambient temperature")
    val STEP_COUNTER = L("计步器", "Step counter")
    val STEP_DETECTOR = L("单步检测", "Step detector")
    val SIGNIFICANT_MOTION = L("显著运动", "Significant motion")
    val ACTIVITY = L("活动识别", "Activity recognition")
    val HEART_RATE = L("心率", "Heart rate")
    val HEART_BEAT = L("心率波动", "Heart beat")
    val DEVICE_ORIENTATION = L("设备朝向检测", "Device orientation")
    val PICK_UP = L("拿起手势", "Pick up gesture")
    val SHAKE = L("摇晃检测", "Shake")
    val FLIP = L("翻转检测", "Flip")
    val FREE_FALL = L("自由落体", "Free fall")
    val TILT = L("倾斜检测", "Tilt detector")
    val WRIST_TILT = L("手腕倾斜", "Wrist tilt")
    val WAKE = L("唤醒手势", "Wake up gesture")
    val GLANCE = L("扫视手势", "Glance gesture")
    val FACE_DOWN = L("面朝下检测", "Face down")

    // ---- location ----
    val LOCATION = L("位置定位", "Location")
    val GNSS = L("GNSS 卫星详情", "GNSS satellites")
    val NMEA = L("NMEA 定位质量", "NMEA fix quality")

    // ---- network ----
    val WIFI = L("WiFi 当前连接", "WiFi connection")
    val WIFI_SCAN = L("附近 WiFi 扫描", "WiFi scan")
    val CELLULAR = L("蜂窝网络", "Cellular network")
    val CONNECTIVITY = L("网络与接口", "Connectivity & interfaces")
    val BLUETOOTH = L("附近蓝牙设备", "Bluetooth devices")
    val BT_PAIRED = L("已配对蓝牙设备", "Paired devices")

    // ---- audio ----
    val NOISE = L("环境噪音", "Ambient noise")
    val AUDIO_STATE = L("音频状态", "Audio state")

    // ---- battery / device ----
    val BATTERY = L("电池状态", "Battery")
    val DEVICE_INFO = L("设备信息", "Device info")
    val SYSTEM = L("系统运行状态", "System status")

    // ---- metric labels ----
    val X_AXIS = L("X 轴", "X axis")
    val Y_AXIS = L("Y 轴", "Y axis")
    val Z_AXIS = L("Z 轴", "Z axis")
    val MAGNITUDE = L("幅值", "Magnitude")
    val MIN = L("最小", "Min")
    val MAX = L("最大", "Max")
    val AVG = L("平均", "Avg")
    val LAST = L("当前", "Current")
    val STDDEV = L("标准差", "Std dev")
    val HEADING = L("指南针方位", "Compass heading")
    val RADIATION = L("磁场强度等级", "Magnetic field level")
    val STEPS = L("步数(本次)", "Steps (this scan)")
    val TOTAL_STEPS = L("开机累计步数", "Total steps since boot")
    val ALTITUDE_EST = L("估算海拔", "Estimated altitude")
    val LAT = L("纬度", "Latitude")
    val LON = L("经度", "Longitude")
    val ACCURACY = L("水平精度", "Accuracy")
    val SPEED = L("速度", "Speed")
    val BEARING = L("方位角", "Bearing")
    val FIX_TIME = L("定位时刻", "Fix time")
    val FIX_COUNT = L("定位次数", "Fix count")
    val PROVIDER = L("定位来源", "Provider")
    val SATS_TOTAL = L("可见卫星数", "Satellites in view")
    val SATS_USED = L("参与定位卫星", "Used in fix")
    val SNR_TOP = L("最高信噪比", "Best SNR")
    val HDOP = L("HDOP 精度因子", "HDOP")
    val FIX_QUALITY = L("定位质量", "Fix quality")
    val SSID = L("SSID", "SSID")
    val BSSID = L("BSSID", "BSSID")
    val RSSI = L("信号强度", "Signal strength")
    val FREQ = L("频段", "Frequency")
    val CHANNEL = L("信道", "Channel")
    val LINK_SPEED = L("链路速率", "Link speed")
    val IP_ADDR = L("IP 地址", "IP address")
    val AP_COUNT = L("可见热点数", "AP count")
    val OPEN_NETWORKS = L("开放网络数", "Open networks")
    val SECURITY = L("加密方式", "Security")
    val NET_TYPE = L("网络制式", "Network type")
    val OPERATOR = L("运营商", "Operator")
    val SIM_COUNTRY = L("SIM 国家", "SIM country")
    val ROAMING = L("漫游状态", "Roaming")
    val MCC_MNC = L("MCC/MNC", "MCC/MNC")
    val RSRP = L("RSRP 参考信号功率", "RSRP")
    val RSRQ = L("RSRQ", "RSRQ")
    val SNR = L("SNR 信噪比", "SNR")
    val CELL_ID = L("小区 ID", "Cell ID")
    val TAC = L("TAC 跟踪区", "TAC")
    val PCI = L("PCI", "PCI")
    val EARFCN = L("EARFCN", "EARFCN")
    val TRANSPORTS = L("网络类型", "Transports")
    val DNS = L("DNS 服务器", "DNS servers")
    val GATEWAY = L("网关", "Gateway")
    val VPN = L("VPN 检测", "VPN detected")
    val DB = L("噪音等级", "Noise level")
    val VOLUME = L("音量", "Volume")
    val RINGER = L("铃声模式", "Ringer mode")
    val OUT_DEVICES = L("输出设备", "Output devices")
    val LEVEL = L("电量", "Level")
    val CHARGING = L("充电状态", "Charging")
    val PLUG = L("充电方式", "Plugged type")
    val HEALTH = L("电池健康", "Health")
    val VOLTAGE = L("电压", "Voltage")
    val TEMP_C = L("温度", "Temperature")
    val CURRENT = L("实时电流", "Current")
    val CHARGE_COUNTER = L("累计充电量", "Charge counter")
    val CAPACITY = L("额定容量", "Rated capacity")
    val MODEL = L("型号", "Model")
    val MANUFACTURER = L("制造商", "Manufacturer")
    val OS_VERSION = L("系统版本", "OS version")
    val SECURITY_PATCH = L("安全补丁", "Security patch")
    val KERNEL = L("内核版本", "Kernel")
    val ABIS = L("ABI 架构", "ABIs")
    val CPU_CORES = L("CPU 核心数", "CPU cores")
    val CPU_USAGE = L("CPU 使用率", "CPU usage")
    val LOAD_AVG = L("负载均值", "Load average")
    val CPU_FREQ = L("CPU 频率", "CPU frequency")
    val MEM_TOTAL = L("内存总量", "Total RAM")
    val MEM_AVAIL = L("可用内存", "Available RAM")
    val LOW_MEM = L("内存不足", "Low memory")
    val STORAGE_INT = L("内部存储", "Internal storage")
    val STORAGE_EXT = L("外部存储", "External storage")
    val SCREEN = L("屏幕", "Display")
    val REFRESH = L("刷新率", "Refresh rate")
    val HDR = L("HDR 支持", "HDR")
    val BRIGHTNESS = L("屏幕亮度", "Brightness")
    val SCREEN_ON = L("屏幕状态", "Screen state")
    val UPTIME = L("系统运行时长", "Uptime")
    val TIMEZONE = L("时区", "Time zone")
    val LOCALE = L("语言区域", "Locale")
    val CAMERAS = L("摄像头", "Cameras")
    val USB = L("USB 设备", "USB devices")
    val VIBRATOR = L("振动器", "Vibrator")
    val THERMAL = L("热区温度", "Thermal zones")

    // ---- analysis ----
    val SCORE_LIGHT = L("光照", "Lighting")
    val SCORE_NOISE = L("噪音", "Noise")
    val SCORE_CLIMATE = L("温湿度", "Climate")
    val SCORE_SIGNAL = L("信号", "Signal")
    val SCORE_GPS = L("定位精度", "GPS accuracy")
    val OVERALL = L("综合环境评分", "Overall score")
    val SCENE_INDOOR = L("室内", "Indoor")
    val SCENE_OUTDOOR = L("户外", "Outdoor")
    val SCENE_MOTION = L("移动中", "In motion")
    val SCENE_VEHICLE = L("乘车/驾车中", "In vehicle")
    val SCENE_UNKNOWN = L("未知", "Unknown")
    val WEATHER = L("气象对比", "Weather comparison")
    val SUGGESTIONS = L("环境建议", "Suggestions")
}
