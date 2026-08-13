# VicinityProbe 探测项全解析

本文档逐一说明每个探测项的**探测内容**、**探测方式**(技术原理)与**结果计算**方法。所有探测数据在本地采集,除天气对比外不依赖任何网络服务。

---

## 目录

1. [传感器(Sensors,33 项)](#1-传感器)
2. [位置与 GNSS(3 项)](#2-位置与-gnss)
3. [网络(6 项)](#3-网络)
4. [音频(2 项)](#4-音频)
5. [电量(1 项)](#5-电量)
6. [设备与系统(2 项)](#6-设备与系统)
7. [环境分析层](#7-环境分析层)
8. [通用约定与边界](#8-通用约定与边界)

---

## 1. 传感器

所有传感器通过系统 `SensorManager` 注册监听,在用户选择的扫描时长(5/10/30/60 秒)内以 **20Hz(SENSOR_DELAY_NORMAL)** 采样,实时计算 **最小值 / 最大值 / 平均值 / 标准差 / 末值**。

- **探测方式**: 注册 `SensorEventListener`,回调在独立 HandlerThread 上处理,不阻塞 UI。
- **结果计算**: 每个轴使用 Welford 在线算法增量聚合(内存 O(1)),幅值 = √(x²+y²+z²);时序数据按需抽稀保存(上限 600 点)用于绘制折线图。
- **设备不支持**的传感器在预检页与报告中标注"设备不支持";需要权限的传感器(计步/活动/心率)若未授权则标注"缺少权限"。

### 1.1 运动类

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **加速度计** `sensor.accelerometer` | 三轴加速度(含重力),单位 m/s² | 注册 TYPE_ACCELEROMETER 监听 | 每轴聚合 min/max/avg/stddev + 幅值 min/max/avg,生成幅值时序折线图 |
| **加速度计(未校准)** | 未经过内置校准的原始三轴数据 | TYPE_ACCELEROMETER_UNCALIBRATED | 同加速度计 |
| **陀螺仪** `sensor.gyroscope` | 三轴角速度,单位 rad/s | TYPE_GYROSCOPE | 同加速度计 |
| **陀螺仪(未校准)** | 含偏置的原始角速度 | TYPE_GYROSCOPE_UNCALIBRATED | 同加速度计 |
| **重力** `sensor.gravity` | 三轴重力矢量,单位 m/s² | TYPE_GRAVITY | 同加速度计;同时为磁力计提供方位融合参考 |
| **线性加速度** `sensor.linear_acceleration` | 去除重力后的三轴加速度 | TYPE_LINEAR_ACCELERATION | 同加速度计,可直接观察"运动引起"的加速度 |
| **旋转向量** `sensor.rotation_vector` | 设备姿态四元数 | TYPE_ROTATION_VECTOR(软件融合) | 四元数经 getRotationMatrixFromVector + getOrientation 转欧拉角,聚合方位角/俯仰角/横滚角(度) |
| **游戏旋转向量** | 忽略地磁的姿态(适合游戏) | TYPE_GAME_ROTATION_VECTOR | 同旋转向量 |
| **地磁旋转向量** | 纯地磁融合的姿态 | TYPE_GEOMAGNETIC_ROTATION_VECTOR | 同旋转向量 |
| **方向(旧)** | 传统三轴方向角 | TYPE_ORIENTATION(已废弃) | 直接聚合 azimuth/pitch/roll(度) |

### 1.2 环境类

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **光照强度** `sensor.light` | 环境光亮度,单位 lux | TYPE_LIGHT | 聚合 avg/min/max/last + 时序折线图;avg 供"光照评分"与场景推断使用 |
| **距离传感器** `sensor.proximity` | 物体距屏幕距离,单位 cm | TYPE_PROXIMITY | 聚合距离值;以"最近一次采样 < 传感器最大量程"判定 遮挡/未遮挡 |
| **气压计** `sensor.pressure` | 大气压强,单位 hPa | TYPE_PRESSURE | 聚合 + 时序图;报告不额外换算海拔(原始值更可靠) |
| **相对湿度** `sensor.humidity` | 环境相对湿度,单位 % | TYPE_RELATIVE_HUMIDITY | 聚合 + 时序图;供"温湿度评分"与天气对比使用 |
| **环境温度** `sensor.temperature` | 环境温度,单位 °C | TYPE_AMBIENT_TEMPERATURE | 聚合 + 时序图;供"温湿度评分"、建议文案与天气对比使用 |

### 1.3 磁场与指南针

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **磁力计** `sensor.magnetometer` | 三轴磁场强度,单位 µT | TYPE_MAGNETIC_FIELD | 聚合三轴 + 幅值;报告"磁场强度等级"(极低 <5 / 低 <20 / 中等 <50 / 较高 <100 / 高 ≥100 µT,近似参考) |
| **磁力计(未校准)** | 原始磁场数据 | TYPE_MAGNETIC_FIELD_UNCALIBRATED | 同磁力计 |
| **指南针方位** | 设备朝向(0-360°) | 磁力计采样时与最近一次重力矢量做 getRotationMatrix 融合,再由 getOrientation 求方位角 | 聚合方位角 avg,负值 +360 归一化 |

### 1.4 计步与活动

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **计步器** `sensor.step_counter` | 开机以来累计步数 | TYPE_STEP_COUNTER(需 ACTIVITY_RECOGNITION 权限) | 记录首个样本为基准值,报告"本次步数 = 末值 - 基准值"与"开机累计步数" |
| **单步检测** | 每一步触发一次 | TYPE_STEP_DETECTOR | 统计扫描期内触发次数 |
| **显著运动** | 设备从静止到明显移动 | TYPE_SIGNIFICANT_MOTION | 统计触发次数(通常一次触发后停止上报) |
| **活动识别** `sensor.activity` | 当前活动类型 | TYPE_ACTIVITY_RECOGNITION 事件值映射:乘车/骑行/静止/移动/步行/跑步/倾斜/未知 | 记录最近一次活动 + 各活动类型样本数分布 |

### 1.5 生物传感器

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **心率** `sensor.heart_rate` | 心率,单位 bpm | TYPE_HEART_RATE(需 BODY_SENSORS 权限) | 过滤无效值(≤0)后聚合 avg/min/max + 最近一次可靠性等级(高/中/低,来自事件 values[1]) |
| **心率波动** | 心跳间期换算的瞬时心率 | TYPE_HEART_BEAT | 过滤无效值后聚合 avg |

> 说明:心率硬件仅在少数设备(如穿戴芯片机型)存在,预检页会如实标注"设备不支持"。

### 1.6 手势与状态类(动态传感器)

以下传感器硬件检测"是否发生",报告 **触发次数** 与 **已触发/未触发** 状态:

| 探测项 | 含义 |
|---|---|
| `sensor.device_orientation` 设备朝向检测 | 设备方向变化事件 |
| `sensor.pick_up` 拿起手势 | 拿起手机事件 |
| `sensor.shake` 摇晃检测 | 摇动手机事件 |
| `sensor.flip` 翻转检测 | 翻转手机事件 |
| `sensor.free_fall` 自由落体 | 检测到自由落体 |
| `sensor.tilt` 倾斜检测 | 设备角度变化事件 |
| `sensor.wrist_tilt` 手腕倾斜 | 抬腕事件 |
| `sensor.wake` 唤醒手势 | 唤醒手势事件 |
| `sensor.glance` 扫视手势 | 看向屏幕事件 |
| `sensor.offbody` 离身检测 | 设备离开身体事件(需 BODY_SENSORS) |

> 说明:SDK 36 已从公开 API 移除这些传感器的类型常量(TYPE_WAKE_GESTURE 等),本应用在运行时通过 `Sensor.getStringType()` 字符串匹配(`android.sensor.xxx`)探测硬件存在性,保证在最新系统上可用。

---

## 2. 位置与 GNSS

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **位置定位** `location` | 经纬度、海拔、速度、方位、精度 | 同时注册 GPS_PROVIDER 与 NETWORK_PROVIDER 监听;最多等待 8 秒首个定位 | 报告最后一次定位的全部字段;统计定位次数;水平/垂直精度按系统 API 读取(需定位权限,否则标注"缺少权限";定位服务关闭则"功能未开启") |
| **GNSS 卫星详情** `gnss` | 可见卫星数、参与定位卫星数、星座分布、每颗卫星信噪比/仰角 | registerGnssStatusCallback 持续接收卫星状态 | 每个状态快照统计:总数、参与定位数、最高 SNR、按星座(GPS/GLONASS/北斗/伽利略/QZSS/IRNSS/SBAS)计数;列表示例卫星(SVID/SNR/仰角,前 10 颗) |
| **NMEA 定位质量** `nmea` | GGA 语句中的定位质量、使用卫星数、HDOP 精度因子、海拔 | addNmeaListener 解析 NMEA 0183 GGA 语句 | 解析定位质量等级(1=GPS 定位/2=差分/3=推算/4=RTK)、卫星数、HDOP、海拔;统计语句数 |

---

## 3. 网络

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **WiFi 当前连接** `wifi` | 当前 SSID/BSSID/RSSI/频段/信道/链路速率/隐藏网络/IP | `WifiManager.getConnectionInfo()` | 直接映射为指标;信道 = 频率换算(2.4G: (f-2412)/5+1;5G: (f-5170)/5+34);未连接则标注"未连接" |
| **附近 WiFi 扫描** `wifi_scan` | 周边 AP 列表:SSID/BSSID/信号/频段/加密方式 | `startScan()` 后读取 `scanResults`(需 NEARBY_WIFI_DEVICES + 定位权限) | 按信号强度排序取前 15;**加密分析**:按 capabilities 字段判定 WPA3(SAE)/WPA3-WPA2/WPA2/WPA/WEP/开放;统计各加密数量与"开放网络数"(用于安全建议);报告系统节流时无结果的原因 |
| **蜂窝网络** `cellular` | 网络制式、运营商、SIM 国家、漫游、MCC/MNC、信号强度、小区信息 | `TelephonyManager` + `getAllCellInfo()` | 制式映射(5G NR/4G LTE/3G…);LTE 取 RSRP/RSRQ/SNR 与 CI/TAC/PCI/EARFCN;NR 取 SS-RSRP/SS-RSRQ/SS-SINR 与 NCI/PCI/NRARFCN;GSM/WCDMA/CDMA 取 RSSI/level;优先展示注册小区,邻区最多 5 条;另报系统信号等级(0-4) |
| **网络与接口** `connectivity` | 网络类型(WiFi/蜂窝/以太网/VPN)、上下行带宽、计费、IPv4/IPv6、DNS、网关、全部网络接口 | `ConnectivityManager`(NetworkCapabilities + LinkProperties)+ `NetworkInterface` 枚举 | 传输类型列表(含 VPN 检测);带宽 kbps;IPv4/IPv6 地址;DNS/网关;接口枚举(名称/状态/MTU/IP/MAC,接口级 MAC 多数设备不可读) |
| **附近蓝牙设备** `bluetooth` | 周边 BLE 设备:名称/地址/RSSI/服务 UUID/厂商数据 | `BluetoothLeScanner` 以低延迟模式扫描 3 秒 | 按 RSSI 排序取前 12;统计设备数;附厂商数据字节数;蓝牙关闭→"功能未开启",无硬件→"设备不支持" |
| **已配对蓝牙设备** `bt_paired` | 系统已配对设备列表 | `BluetoothAdapter.getBondedDevices()`(需 BLUETOOTH_CONNECT) | 数量 + 名称/地址列表 |

---

## 4. 音频

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **环境噪音** `noise` | 环境噪音近似等级,单位 dB | `MediaRecorder`(MIC 源,44.1kHz AAC)每 200ms 读取 `getMaxAmplitude()` | 振幅→dB 近似换算:`dB = 20·log10(amp/32767) + 100`,钳制 0-120;聚合 avg/min/max/last + 时序折线图;**注明为近似值未经校准**;麦克风被占用时报告失败原因 |
| **音频状态** `audio_state` | 各流音量、铃声模式、媒体播放、输出/输入设备、采样率 | `AudioManager` | 媒体/铃声/闹钟/通知/系统音量(当前/最大);铃声模式(正常/静音/振动);输出设备(扬声器/听筒/耳机/蓝牙/USB,含采样率与声道);麦克风数量 |

---

## 5. 电量

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **电池状态** `battery` | 电量、充电状态、充电方式、健康度、温度、电压、实时电流、累计充电量、额定容量 | `BatteryManager` sticky 广播 + `getIntProperty` + 反射 `PowerProfile` | 电量 = level/scale;充电状态/方式/健康度按枚举映射;温度(0.1°C 精度)/电压(0.001V 精度)换算;实时电流(µA→mA,部分设备返回 MIN_VALUE 则省略);额定容量经反射读取系统 PowerProfile(失败则省略) |

---

## 6. 设备与系统

| 探测项 | 探测内容 | 探测方式 | 结果计算 |
|---|---|---|---|
| **设备信息** `device` | 型号/品牌/系统版本/安全补丁/内核/ABI/编译指纹、屏幕(分辨率/密度/刷新率/HDR)、亮度、屏幕状态、运行时长、时区、语言、时间、摄像头、USB、振动器 | `Build`、`System.getProperty`、`WindowManager/DisplayMetrics`、`Settings.System`、`PowerManager`、`CameraManager`、`UsbManager`、`Vibrator` | 全部直接映射;摄像头枚举含前后置;USB 列出已连接设备名;HDR 按 Display 的 HDR 类型列表非空判断 |
| **系统运行状态** `system` | CPU 核心数/频率/使用率、负载均值、内存、存储、热区温度 | 核心数 `Runtime`;频率读 `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`;使用率解析 `/proc/stat` 两次采样(间隔 600ms)计算 `(1 - Δidle/Δtotal)·100%`;负载读 `/proc/loadavg`;内存 `ActivityManager.MemoryInfo`;存储 `StatFs`;热区读 `/sys/class/thermal/thermal_zone*/temp` | 如上逐项计算;多数设备对 thermal/CPU 频率文件无读权限,报告"不可读(无权限)" |

---

## 7. 环境分析层

**综合评分(0-100,加权平均)**,仅统计有数据的维度并自动重新归一:

| 维度 | 权重 | 计算方式 |
|---|---|---|
| 光照 | 25% | <20 lux→15 分;<50→30;<100→50;<300→75;≤1500→95;>1500→70 |
| 噪音 | 25% | <35dB→95;<45→85;<60→70;<75→45;≥75→20 |
| 温湿度 | 25% | 基准 95 分,温度每偏离 22°C ±4°C 内不扣分,±10/±16/更大分别扣 20/40/60;湿度偏离 50% ±10/±25/±45 分别扣 0/15/30/45 |
| 信号 | 12.5% | 蜂窝 RSRP ≥-90→95;-105→80;-115→60;更弱→35;无蜂窝时用 WiFi RSSI ≥-55→90;-70→75;-85→55;更弱→30 |
| 定位精度 | 12.5% | ≤10m→95;≤30m→80;≤100m→60;更差→35 |

**场景推断**:速度 >15m/s→乘车/驾车;>1.5m/s→移动中;活动识别含乘车→乘车;含步行/跑步/骑行→移动中;否则光照 <100lux→室内,其余→户外,无数据→未知。

**天气对比**:有定位时请求 Open-Meteo 当前天气(温度/湿度/气压/风速/天气现象),与本地传感器数据并列展示;离线时标注失败原因。**这是全应用唯一的联网功能**。

**建议文案**:根据阈值触发(光照<150lux、噪音>70dB、温度<16 或 >30°C、湿度<35% 或 >65%、RSRP<-115dBm、定位>100m、电量<20%、发现开放 WiFi),每条建议同时提供中英文。

---

## 8. 通用约定与边界

- **采样与聚合**:所有数值型指标采用 Welford 在线聚合(均值/标准差 O(1) 内存),时序图数据抽稀保存。
- **报告存储**:每次扫描保存为 `filesDir/reports/<id>.json`(完整结构化数据),历史页读取索引;导出支持 JSON / Markdown / PNG(离屏渲染)三种格式,经 FileProvider 分享。
- **双报告对比**:仅对比两份报告共有的指标项,标注差异值。
- **连续监测**:前台服务(dataSync 类型)按用户间隔(5/10/30/60 分钟)自动执行核心探测集(光照/温度/湿度/气压/加速度/噪音/定位/GNSS/电量),存档并在通知栏更新评分。
- **已知边界**:
  - 噪音 dB、磁场辐射等级为近似值,未使用专业声压计/高斯计校准。
  - 心率等生物传感器仅部分机型有硬件。
  - WiFi 扫描受系统节流(约 2 分钟 4 次)限制,受限时报告注明。
  - thermal / CPU 频率文件在多数设备无读权限。
  - GNSS 卫星详情与 NMEA 需要 GPS 开启且室外环境才能获得数据。
