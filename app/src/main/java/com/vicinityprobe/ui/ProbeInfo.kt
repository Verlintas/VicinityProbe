package com.vicinityprobe.ui

import com.vicinityprobe.model.bil

/**
 * 探测项简介库:每项说明 用法(怎么测)/ 用处(能干什么)/ 原理(技术原理)。
 * 格式:bil("中文","English"),分号分隔三部分。
 */
object ProbeInfo {
    private val info = mapOf(
        // ---- MOTION ----
        "sensor.accelerometer" to bil(
            "用法:静止或握持手机,探测自动采样 50Hz 三轴加速度。用处:判断设备朝向、运动强度、振动源,是振动分析的数据源。原理:MEMS 电容式加速度计测量惯性力,含重力分量。",
            "Usage: hold still or in hand; samples 3-axis acceleration at 50 Hz. Purpose: orientation, motion intensity, vibration source. Principle: MEMS capacitive accelerometer senses inertial force incl. gravity.",
        ),
        "sensor.accelerometer_uncal" to bil(
            "用法:同加速度计,但输出未经内置校准的原始值。用处:对比校准/未校准差异,评估传感器漂移。原理:传感器直接输出含偏置的原始读数。",
            "Usage: like accelerometer but raw uncalibrated values. Purpose: assess sensor drift. Principle: raw readings include bias.",
        ),
        "sensor.gyroscope" to bil(
            "用法:测量三轴角速度(rad/s)。用处:设备旋转检测、游戏/AR 姿态跟踪、防抖分析。原理:MEMS 陀螺仪基于科里奥利效应感知角速度。",
            "Usage: measures 3-axis angular rate (rad/s). Purpose: rotation detection, AR pose tracking. Principle: MEMS gyroscope uses the Coriolis effect.",
        ),
        "sensor.gyroscope_uncal" to bil(
            "用法:同陀螺仪,输出含偏置的原始角速度。用处:评估陀螺漂移与校准质量。原理:原始读数 = 真实角速度 + 偏置。",
            "Usage: like gyroscope with bias included. Purpose: drift assessment. Principle: raw = true rate + bias.",
        ),
        "sensor.gravity" to bil(
            "用法:测量重力矢量。用处:确定设备绝对朝向,为磁力计方位计算提供参考。原理:软件融合加速度计低频分量输出。",
            "Usage: measures the gravity vector. Purpose: absolute orientation; reference for compass. Principle: software-fused low-frequency acceleration.",
        ),
        "sensor.linear_acceleration" to bil(
            "用法:测量去除重力后的纯运动加速度。用处:检测推拉动作、步态、冲击。原理:加速度计输出减去重力分量。",
            "Usage: acceleration without gravity. Purpose: gestures, gait, impacts. Principle: accelerometer minus gravity.",
        ),
        "sensor.rotation_vector" to bil(
            "用法:输出设备姿态四元数,本应用转成欧拉角。用处:精确朝向与姿态测量。原理:加速度计+陀螺仪+磁力计卡尔曼融合。",
            "Usage: device attitude quaternion, converted to Euler angles. Purpose: precise orientation. Principle: Kalman fusion of accel/gyro/magnetometer.",
        ),
        "sensor.game_rotation_vector" to bil(
            "用法:忽略地磁的姿态估计。用处:游戏等不需要磁北的场景,避免磁干扰抖动。原理:陀螺+加速度融合,无磁力计。",
            "Usage: attitude without magnetometer. Purpose: gaming (immune to magnetic noise). Principle: gyro+accel fusion only.",
        ),
        "sensor.geomagnetic_rotation" to bil(
            "用法:地磁姿态估计。用处:低功耗姿态,依赖磁力计。原理:地磁+加速度融合。",
            "Usage: geomagnetic attitude. Purpose: low-power orientation. Principle: magnetometer+accel fusion.",
        ),
        "sensor.orientation" to bil(
            "用法:传统三轴方向角。用处:兼容旧应用的方位测量。原理:磁力计+重力合成(已废弃)。",
            "Usage: legacy 3-axis orientation. Purpose: compatibility. Principle: magnetometer+gravity (deprecated).",
        ),
        // ---- ENVIRONMENT ----
        "sensor.light" to bil(
            "用法:自动采样环境照度(lux)。用处:判断室内外、检测光源、屏幕亮度参考。原理:光电二极管响应可见光强度。",
            "Usage: samples ambient illuminance (lux). Purpose: indoor/outdoor, light sources. Principle: photodiode responds to visible light.",
        ),
        "sensor.proximity" to bil(
            "用法:测量物体与屏幕距离。用处:通话熄屏检测、遮挡判断。原理:红外 LED 反射测距。",
            "Usage: distance to screen. Purpose: call screen-off, cover detection. Principle: IR LED reflection.",
        ),
        "sensor.pressure" to bil(
            "用法:采样大气压(hPa)。用处:海拔推算、天气趋势监测。原理:MEMS 压力膜应变测量。",
            "Usage: atmospheric pressure (hPa). Purpose: altitude, weather trends. Principle: MEMS strain gauge.",
        ),
        "sensor.humidity" to bil(
            "用法:采样相对湿度。用处:舒适度评估、防潮监测。原理:电容式湿度感应元件。",
            "Usage: relative humidity. Purpose: comfort, dampness. Principle: capacitive hygrometer.",
        ),
        "sensor.temperature" to bil(
            "用法:采样环境温度。用处:热环境评估、与气象对比。原理:热敏电阻/半导体测温。",
            "Usage: ambient temperature. Purpose: thermal assessment. Principle: thermistor/semiconductor.",
        ),
        // ---- MAGNETIC ----
        "sensor.magnetometer" to bil(
            "用法:采样三轴磁场强度(µT)。用处:指南针方位、电磁环境评估、金属探测。原理:霍尔效应/磁阻传感器。",
            "Usage: 3-axis magnetic flux (µT). Purpose: compass, EM environment, metal detection. Principle: Hall/magnetoresistive.",
        ),
        "sensor.magnetometer_uncal" to bil(
            "用法:未校准磁场原始值。用处:配合校准分析项评估硬铁偏移。原理:含环境偏置的原始读数。",
            "Usage: raw uncalibrated field. Purpose: hard-iron offset assessment. Principle: raw with environmental bias.",
        ),
        // ---- BIOSIGNAL ----
        "sensor.heart_rate" to bil(
            "用法:佩戴含心率硬件的设备时采样 bpm。用处:生物信号采集。原理:光电容积脉搏波(PPG)检测。",
            "Usage: samples bpm on devices with HR hardware. Purpose: biosignal acquisition. Principle: photoplethysmography (PPG).",
        ),
        "sensor.heart_beat" to bil(
            "用法:记录每次心跳的间期换算瞬时心率。用处:心率变异性(HRV)研究。原理:基于心跳间期(RR 间期)。",
            "Usage: beat-to-beat rate. Purpose: HRV analysis. Principle: RR intervals.",
        ),
        // ---- CONTEXT ----
        "sensor.step_counter" to bil(
            "用法:读取开机累计步数,本次增量=末值-初值。用处:活动量统计。原理:硬件计步器持续累计。",
            "Usage: steps since boot; delta = last - first. Purpose: activity volume. Principle: hardware step counter.",
        ),
        "sensor.step_detector" to bil("用法:每走一步触发一次。用处:精确步态事件。原理:步态事件检测。", "Usage: fires per step. Purpose: gait events. Principle: step-event detection."),
        "sensor.significant_motion" to bil("用法:检测从静止到明显移动。用处:低功耗运动唤醒。原理:单次触发事件。", "Usage: detects significant motion. Purpose: low-power wake. Principle: one-shot event."),
        "sensor.activity" to bil(
            "用法:输出当前活动类型(步行/跑步/骑行/乘车等)。用处:场景识别、行为分析。原理:传感器融合活动分类器。",
            "Usage: current activity class. Purpose: scene recognition. Principle: fused activity classifier.",
        ),
        "sensor.device_orientation" to bil("用法:检测设备方向变化事件。用处:交互场景。原理:方向变化触发。", "Usage: orientation-change events. Purpose: interaction. Principle: change-triggered."),
        "sensor.pick_up" to bil("用法:检测拿起手机动作。用处:免交互唤醒。原理:抬举手势识别。", "Usage: pick-up gesture. Purpose: touch-free wake. Principle: lift-gesture recognition."),
        "sensor.shake" to bil("用法:检测摇动。用处:摇一摇类交互。原理:加速度模式识别。", "Usage: shake detection. Purpose: shake interactions. Principle: accel pattern."),
        "sensor.flip" to bil("用法:检测翻转。用处:翻转静音等交互。原理:方向突变检测。", "Usage: flip detection. Purpose: flip-to-silence. Principle: orientation flip."),
        "sensor.free_fall" to bil("用法:检测自由落体。用处:跌落保护。原理:失重状态识别。", "Usage: free-fall detection. Purpose: drop protection. Principle: weightless state."),
        "sensor.tilt" to bil("用法:检测倾斜角度变化。用处:UI 朝向辅助。原理:角度变化事件。", "Usage: tilt changes. Purpose: UI orientation aid. Principle: angle events."),
        "sensor.wrist_tilt" to bil("用法:检测抬腕动作。用处:手表类交互。原理:腕部姿态识别。", "Usage: wrist-tilt. Purpose: watch interactions. Principle: wrist pose."),
        "sensor.wake" to bil("用法:检测唤醒手势。用处:抬手亮屏。原理:手势识别。", "Usage: wake gesture. Purpose: raise-to-wake. Principle: gesture recognition."),
        "sensor.glance" to bil("用法:检测看向屏幕。用处:智能亮屏。原理:视线方向传感。", "Usage: glance detection. Purpose: smart screen-on. Principle: gaze sensing."),
        "sensor.offbody" to bil("用法:检测设备离开身体。用处:穿戴设备防盗/省电。原理:离身传感。", "Usage: off-body detection. Purpose: wearables. Principle: proximity of body."),
        // ---- POSITIONING ----
        "location" to bil(
            "用法:连续采集 GPS/网络定位(500ms)。用处:位置、运动速度与轨迹分析。原理:卫星三角定位 + 基站/网络辅助定位。",
            "Usage: GPS/network fixes at 500 ms. Purpose: position, speed, tracks. Principle: satellite trilateration + network assist.",
        ),
        "gnss" to bil(
            "用法:监听卫星状态回调。用处:定位质量评估、星座覆盖分析。原理:解析 GNSS 引擎上报的每颗卫星信噪比/仰角/参与定位标志。",
            "Usage: satellite status callbacks. Purpose: fix quality, constellation coverage. Principle: per-satellite SNR/elevation/used-in-fix from the GNSS engine.",
        ),
        "nmea" to bil(
            "用法:解析 NMEA 0183 GGA 语句。用处:获取 HDOP 精度因子与定位质量等级。原理:定位引擎输出标准航海语句。",
            "Usage: parses NMEA GGA sentences. Purpose: HDOP & fix quality. Principle: standard maritime sentences from the engine.",
        ),
        "gnss_raw" to bil(
            "用法:注册 GNSS 原始测量回调。用处:GNSS Logger 级研究(载波相位/伪距率),可用于精密定位分析。原理:直接读取接收机原始观测量。",
            "Usage: raw measurement callbacks. Purpose: GNSS-Logger-grade research (carrier phase, pseudorange). Principle: direct receiver observables.",
        ),
        "gnss_hw" to bil(
            "用法:读取 GNSS 硬件型号/能力标志。用处:评估设备定位能力(是否支持原始测量/批量定位)。原理:查询 LocationManager 硬件接口。",
            "Usage: reads GNSS hardware model/capabilities. Purpose: assess positioning capability. Principle: LocationManager hardware queries.",
        ),
        // ---- RADIO ----
        "wifi" to bil(
            "用法:读取当前 WiFi 连接信息。用处:诊断连接质量(信号/速率/信道)。原理:查询 WifiManager 连接状态。",
            "Usage: current WiFi connection info. Purpose: link quality diagnosis. Principle: WifiManager queries.",
        ),
        "wifi_dynamic" to bil(
            "用法:会话期间 5Hz 连续采样 RSSI 与链路速率。用处:观察信号波动、干扰、弱覆盖。原理:周期性轮询 WifiInfo。",
            "Usage: 5 Hz RSSI/link-speed series. Purpose: signal fluctuation & interference. Principle: periodic WifiInfo polling.",
        ),
        "wifi_scan" to bil(
            "用法:扫描周边 AP 并分析加密方式。用处:网络环境测绘、WiFi 安全审计(发现开放网络)。原理:被动/主动扫描 + capabilities 字段解析。",
            "Usage: AP scan + security analysis. Purpose: RF survey, security audit (open networks). Principle: scan + capabilities parsing.",
        ),
        "wifi_rtt" to bil(
            "用法:对支持 802.11mc 的 AP 发起 FTM 测距。用处:精确室内定位、AP 距离测量。原理:往返时间(RTT)乘以光速。",
            "Usage: FTM ranging to 802.11mc APs. Purpose: indoor positioning, AP distances. Principle: round-trip time × speed of light.",
        ),
        "wifi_direct" to bil(
            "用法:发现附近 WiFi Direct 对等设备。用处:P2P 通信可行性评估。原理:Wi-Fi Direct 设备发现协议。",
            "Usage: discovers P2P peers. Purpose: ad-hoc comms feasibility. Principle: Wi-Fi Direct discovery.",
        ),
        "wifi_aware" to bil(
            "用法:检查 Wi-Fi Aware(NAN)能力并尝试订阅。用处:邻近感知应用可行性。原理:Wi-Fi 邻近感知网络协议。",
            "Usage: NAN capability + subscribe. Purpose: proximity-app feasibility. Principle: Wi-Fi Neighbor Awareness Networking.",
        ),
        "cellular" to bil(
            "用法:读取蜂窝网络参数与小区信息。用处:信号覆盖分析、运营商/制式识别、小区切换研究。原理:TelephonyManager + CellInfo 接口。",
            "Usage: cellular params & cell info. Purpose: coverage analysis, carrier/gen identification. Principle: TelephonyManager + CellInfo.",
        ),
        "cellular_series" to bil(
            "用法:2Hz 连续采样信号等级/dBm 并跟踪服务小区。用处:信号波动与弱覆盖时段分析。原理:周期性读取 SignalStrength 与注册小区。",
            "Usage: 2 Hz signal level/dBm series + cell tracking. Purpose: fluctuation & weak-coverage windows. Principle: periodic signal/cell reads.",
        ),
        "connectivity" to bil(
            "用法:读取网络类型/带宽/IP/DNS/接口。用处:网络栈诊断、VPN 检测、接口枚举。原理:ConnectivityManager + NetworkInterface。",
            "Usage: transports/bandwidth/IP/DNS/interfaces. Purpose: stack diagnosis, VPN detection. Principle: ConnectivityManager + NetworkInterface.",
        ),
        "network_stats" to bil(
            "用法:读取累计流量与套接字统计。用处:流量监控、连接数评估。原理:TrafficStats + /proc/net 解析。",
            "Usage: cumulative traffic & socket counts. Purpose: data usage, connection count. Principle: TrafficStats + /proc/net.",
        ),
        "bluetooth" to bil(
            "用法:BLE 扫描周边设备并解析广播包。用处:设备发现、信号强度测绘、广播数据分析。原理:BLE 扫描 + 广告数据解析。",
            "Usage: BLE scan + advertisement parsing. Purpose: device discovery, RSSI survey. Principle: BLE scan + adv-data parse.",
        ),
        "bt_classic" to bil(
            "用法:经典蓝牙发现(非 BLE)。用处:发现传统蓝牙设备(耳机/音箱)。原理:BR/EDR inquiry 过程。",
            "Usage: classic BT discovery. Purpose: legacy devices (headsets/speakers). Principle: BR/EDR inquiry.",
        ),
        "bt_paired" to bil(
            "用法:读取已配对设备列表。用处:设备清单管理。原理:查询适配器绑定列表。",
            "Usage: paired-device list. Purpose: inventory. Principle: adapter bonded-devices.",
        ),
        "nfc" to bil(
            "用法:检查 NFC 硬件与启用状态,尝试读取技术列表。用处:NFC 能力评估。原理:NfcAdapter 系统服务。",
            "Usage: NFC presence/enabled/tech list. Purpose: NFC capability. Principle: NfcAdapter service.",
        ),
        "fm_radio" to bil(
            "用法:枚举 FM 调谐器模块(反射 RadioManager)。用处:确认设备是否带 FM 收音机。原理:系统 radio 服务模块列表。",
            "Usage: enumerate FM tuner modules (reflection). Purpose: FM radio presence. Principle: system radio-service modules.",
        ),
        "infrared" to bil(
            "用法:检查红外发射器及载波频率范围。用处:确认能否用作遥控器。原理:ConsumerIr 硬件接口。",
            "Usage: IR emitter + carrier range. Purpose: remote-control capability. Principle: ConsumerIr hardware.",
        ),
        "wifi_channel" to bil(
            "用法:统计各信道 AP 分布与拥挤度。用处:选信道优化、干扰分析。原理:基于扫描结果按频率映射信道。",
            "Usage: per-channel AP distribution & congestion. Purpose: channel planning, interference. Principle: scan results → channel map.",
        ),
        // ---- ELECTRICAL ----
        "battery" to bil(
            "用法:读取电池全部电气参数。用处:电池健康与状态评估。原理:BatteryManager 广播与属性接口。",
            "Usage: full battery electrical params. Purpose: health & state. Principle: BatteryManager broadcast/properties.",
        ),
        "battery_drain" to bil(
            "用法:会话内 2Hz 采样电流×电压算实时功率。用处:功耗评估、续航估算。原理:P=UI,电流计积分。",
            "Usage: 2 Hz power (I×V) series. Purpose: power draw, autonomy estimate. Principle: P = U·I.",
        ),
        // ---- SYSTEM / DEVICE ----
        "device" to bil(
            "用法:采集设备静态信息。用处:设备身份与硬件清单。原理:Build/系统服务只读查询。",
            "Usage: static device info. Purpose: identity & hardware inventory. Principle: Build/system-service reads.",
        ),
        "system" to bil(
            "用法:采集 CPU/内存/存储使用率。用处:系统负载监测。原理:/proc 与 ActivityManager 统计。",
            "Usage: CPU/memory/storage usage. Purpose: load monitoring. Principle: /proc + ActivityManager.",
        ),
        "thermal" to bil(
            "用法:逐热区温度 + 系统热状态(反射)。用处:发热与降频监控。原理:thermal_zone sysfs + ThermalService。",
            "Usage: per-zone temps + thermal status (reflection). Purpose: heat/throttling. Principle: thermal_zone sysfs + ThermalService.",
        ),
        "power_state" to bil(
            "用法:读取核心在线状态/调速器/频率。用处:CPU 电源管理分析。原理:cpufreq sysfs。",
            "Usage: core online/governor/frequencies. Purpose: CPU PM analysis. Principle: cpufreq sysfs.",
        ),
        "kernel" to bil(
            "用法:读取内核与安全信息。用处:SELinux 状态/引导信息审计。原理:sysfs + Build 字段 + 反射。",
            "Usage: kernel & security info. Purpose: SELinux/boot audit. Principle: sysfs + Build + reflection.",
        ),
        "display" to bil(
            "用法:读取刷新率模式/HDR 类型/亮度设置。用处:显示能力评估。原理:Display/DisplayManager + Settings。",
            "Usage: refresh modes/HDR/brightness. Purpose: display capability. Principle: Display + Settings.",
        ),
        "storage" to bil(
            "用法:枚举存储卷与容量。用处:存储空间审计。原理:StorageManager volumes。",
            "Usage: storage volumes & capacity. Purpose: space audit. Principle: StorageManager volumes.",
        ),
        "proc_net_conn" to bil(
            "用法:解析网络连接表。用处:连接状态与进程网络活动分析。原理:/proc/net/tcp(多数新系统对应用禁用)。",
            "Usage: connection-table parse. Purpose: connection state analysis. Principle: /proc/net/tcp (restricted on most modern Android).",
        ),
        "proc_meminfo" to bil(
            "用法:解析内核内存明细。用处:内存压力与缓存分析。原理:/proc/meminfo。",
            "Usage: kernel memory detail. Purpose: memory pressure/cache. Principle: /proc/meminfo.",
        ),
        "cpu_per_core" to bil(
            "用法:逐核使用率时序(2Hz)。用处:异构核心调度分析。原理:/proc/stat 各 cpuN 行差分。",
            "Usage: per-core usage series (2 Hz). Purpose: big.LITTLE scheduling. Principle: per-cpuN /proc/stat deltas.",
        ),
        "disk_stats" to bil(
            "用法:磁盘 IO 速率统计。用处:存储性能与 IO 负载。原理:/proc/diskstats 差分(通常被禁用)。",
            "Usage: disk IO rates. Purpose: storage performance. Principle: /proc/diskstats deltas (usually restricted).",
        ),
        "proc_uptime" to bil(
            "用法:开机时长/空闲占比/主机名/熵。用处:系统运行统计。原理:/proc/uptime 等。",
            "Usage: uptime/idle/hostname/entropy. Purpose: run statistics. Principle: /proc/uptime etc.",
        ),
        "sensor_calib" to bil(
            "用法:同一会话对比校准/未校准传感器。用处:评估传感器偏置与磁硬铁偏移,指导磁场校准。原理:校准版=未校准版-偏差。",
            "Usage: calibrated-vs-uncalibrated delta analysis. Purpose: bias & hard-iron offset, magnetometer calibration. Principle: calibrated = raw − bias.",
        ),
        // ---- SECURITY ----
        "net_arp" to bil(
            "用法:对子网主机做 TCP 探测触发 ARP,再读 ARP 表。用处:局域网设备发现与厂商识别。原理:内核 ARP 缓存 + OUI 前缀匹配。",
            "Usage: TCP-probe to trigger ARP, then read the ARP table. Purpose: LAN device discovery + vendor ID. Principle: kernel ARP cache + OUI lookup.",
        ),
        "net_portscan" to bil(
            "用法:对目标扫描 40+ 常用端口。用处:服务暴露面评估。原理:TCP connect 半握手判定端口开放。",
            "Usage: scans 40+ well-known ports on target. Purpose: exposed-service assessment. Principle: TCP connect handshake.",
        ),
        "net_http_fingerprint" to bil(
            "用法:抓取 HTTP 响应头与 TLS 证书链。用处:识别 Web 技术栈与证书配置(自签/过期/弱签名)。原理:HTTP 协议交互 + X.509 解析。",
            "Usage: HTTP headers + TLS certificate chain. Purpose: web-stack & certificate config ID. Principle: HTTP exchange + X.509 parse.",
        ),
        "net_dns" to bil(
            "用法:测试常见域名解析延迟与公共 DNS 连通。用处:DNS 性能与故障诊断。原理:系统解析器 + TCP/53 探测。",
            "Usage: domain resolution latency + public DNS reachability. Purpose: DNS performance diagnosis. Principle: resolver + TCP/53 probe.",
        ),
        "net_ssdp" to bil(
            "用法:UDP 组播 M-SEARCH 发现 UPnP 设备。用处:发现智能家居/媒体设备。原理:SSDP 组播发现协议。",
            "Usage: SSDP multicast discovery. Purpose: smart-home/media device discovery. Principle: SSDP protocol.",
        ),
        "net_ping" to bil(
            "用法:对目标做 TCP 方式 RTT 测试。用处:网关连通性与延迟基线。原理:TCP connect 计时(非 ICMP,无需 root)。",
            "Usage: TCP-based RTT to target. Purpose: gateway reachability baseline. Principle: TCP connect timing (no root).",
        ),
        "net_banner" to bil(
            "用法:对开放端口读取服务横幅。用处:服务版本识别。原理:连接后读取服务首条响应文本。",
            "Usage: reads service banners. Purpose: service version ID. Principle: read first response line.",
        ),
        "net_http_methods" to bil(
            "用法:测试 HTTP 方法允许性。用处:发现危险方法(TRACE/PUT)。原理:逐方法请求观察响应。",
            "Usage: tests allowed HTTP methods. Purpose: risky methods (TRACE/PUT). Principle: per-method requests.",
        ),
        "net_http_security" to bil(
            "用法:检查安全响应头是否缺失。用处:Web 安全基线审计。原理:对比标准安全头清单。",
            "Usage: security-header presence check. Purpose: web security baseline. Principle: compare against standard header list.",
        ),
        "net_tls_versions" to bil(
            "用法:尝试 TLSv1~1.3 握手。用处:评估 TLS 配置强度。原理:按版本构造 SSLContext 握手。",
            "Usage: handshake attempts for TLSv1~1.3. Purpose: TLS config strength. Principle: per-version SSLContext handshake.",
        ),
        "net_ntp" to bil(
            "用法:对公共 NTP 服务器测时钟偏移。用处:本机时钟偏差评估。原理:NTP v4 时间戳交换。",
            "Usage: clock offset vs public NTP servers. Purpose: local clock skew. Principle: NTP v4 timestamp exchange.",
        ),
        "net_proxy" to bil(
            "用法:读取系统代理配置。用处:网络出口诊断。原理:LinkProperties.httpProxy。",
            "Usage: system proxy config. Purpose: egress diagnosis. Principle: LinkProperties.httpProxy.",
        ),
        "net_subnet_scan" to bil(
            "用法:扫描网段全部 254 主机常见端口。用处:全网资产发现。原理:并发 TCP 探测。",
            "Usage: scans all 254 subnet hosts. Purpose: full asset discovery. Principle: concurrent TCP probes.",
        ),
        "net_mqtt" to bil(
            "用法:对 1883 端口发 MQTT CONNECT。用处:发现 IoT/消息 Broker。原理:MQTT 握手协议。",
            "Usage: MQTT CONNECT to 1883. Purpose: IoT/broker discovery. Principle: MQTT handshake.",
        ),
        "net_http_paths" to bil(
            "用法:探测常见 Web 路径状态码。用处:Web 目录枚举辅助。原理:逐个 GET 记录响应码。",
            "Usage: common-path status codes. Purpose: web enumeration assist. Principle: per-path GET.",
        ),
        "net_tcp_concurrency" to bil(
            "用法:同时建立 16 条连接测成功率。用处:目标并发处理能力。原理:并发 TCP 连接计数。",
            "Usage: 16 simultaneous connections. Purpose: concurrency capacity. Principle: concurrent TCP count.",
        ),
        "audio_state" to bil(
            "用法:读取音量/铃声模式/输出设备。用处:音频环境配置。原理:AudioManager 查询。",
            "Usage: volumes/ringer/output devices. Purpose: audio config. Principle: AudioManager queries.",
        ),
    )

    /** 返回 bil 格式简介;无条目时返回通用说明 */
    fun of(probeId: String, name: String): String =
        info[probeId] ?: bil(
            "用法:按测量目录规范自动采样。用处:参见报告中的测量结果。原理:见 docs/PROBES.md。",
            "Usage: sampled per the measurement catalog. Purpose: see measurement results. Principle: see docs/PROBES.md.",
        )
}
