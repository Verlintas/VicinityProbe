# VicinityProbe 探测体系全解析

VicinityProbe 是一套**专业环境测量系统**。每个探测项都是测量目录(ProbeCatalog)里的一个规范条目,写明被测量(Measurand)、单位(SI)、标称采样率、量程和数据质量要求。所有数据都在本地采集,原始样本存成 CSV,报告使用带版本号的 schema。

[English](PROBES.md)

---

## 1. 测量目录(ProbeCatalog)

每个探测项 `ProbeSpec` 定义:

| 字段 | 含义 |
|---|---|
| `id` | 全局唯一标识 |
| `measurand` | 被测量物理量(ACCELERATION / ILLUMINANCE / SOUND_PRESSURE_LEVEL…) |
| `unit` | 单位(符号 + SI 表述) |
| `nominalRateHz` | 标称采样率;0 = 事件驱动 |
| `typicalRange` | 系统标称量程 |
| `sampleChannels` | 测量通道(如 x/y/z/magnitude) |
| `keepRawSamples` | 是否存档原始样本 |
| `requiredPermissions` | 所需权限 |

共 **85 项**,分成 12 类(MOTION / ENVIRONMENT / MAGNETIC / BIOSIGNAL / AUDIO / POSITIONING / RADIO / ELECTRICAL / SYSTEM / DEVICE / CONTEXT / SECURITY)。

## 2. 测量流程

```
能力预检(CapabilityProbe) → 采样(Sampler) → 统计(ChannelStats) → 质量门禁(QualityGate) → 报告(schema v1)
```

- **能力预检**:运行时枚举设备硬件(传感器用 `Sensor.getStringType()` 匹配,兼容 SDK 36 已移除的常量),逐项给出 SUPPORTED / NO_HARDWARE / PERMISSION_MISSING / FEATURE_OFF。
- **采样**:各 Sampler 在测量会话(SessionContext)里并发运行,受会话截止时间约束。
- **统计**:每通道用原始样本精确计算(排序求分位数)。
- **质量门禁**:每项输出 QualityReport——等级(EXCELLENT/GOOD/DEGRADED/FAILED)、覆盖率(实际/标称采样率)、采样数、实际采样率、机器可读原因码。

## 3. 统计量定义(ChannelStats)

每通道输出:样本数 n、min、max、mean、stddev、RMS、变异系数 CV、分位数 p1/p5/p25/p50(中位数)/p75/p95/p99、末值。

- 在线阶段用 Welford 算法维护实时均值/方差(内存 O(1))。
- 会话结束后对原始样本排序,用最近秩法(nearest-rank)求分位数。

## 4. 质量门禁(QualityGate)

| 等级 | 判定 |
|---|---|
| EXCELLENT | 覆盖率 ≥80% 且传感器精度良好 |
| GOOD | 覆盖率 ≥50% |
| DEGRADED | 覆盖率 ≥10%,或样本数不足,或传感器未校准 |
| FAILED | 无硬件 / 权限拒绝 / 功能关闭 / 无定位 / 采集错误 / 无数据 |

原因码(机器可读):`OK / NO_HARDWARE / PERMISSION_DENIED / FEATURE_OFF / NO_FIX / INSUFFICIENT_SAMPLES / SAMPLE_RATE_LOW / SENSOR_UNCALIBRATED / ACQUISITION_ERROR / NO_DATA / SYSTEM_THROTTLED`

## 5. 各探测项明细

### 5.1 运动学(MOTION)

| 探测项 | 被测量 | 标称采样率 | 说明 |
|---|---|---|---|
| `sensor.accelerometer` | ACCELERATION (m/s²) | 50 Hz | 三轴 + 幅值通道,量程 ±2~±16g |
| `sensor.accelerometer_uncal` | ACCELERATION | 50 Hz | 未校准的原始数据 |
| `sensor.gyroscope` / `_uncal` | ANGULAR_RATE (rad/s) | 50 Hz | 角速度 |
| `sensor.gravity` | ACCELERATION | 50 Hz | 重力矢量 |
| `sensor.linear_acceleration` | ACCELERATION | 50 Hz | 去掉重力后的加速度 |
| `sensor.rotation_vector` / `game_rotation_vector` / `geomagnetic_rotation` | ORIENTATION_QUATERNION | 50 Hz | 四元数 → 欧拉角(方位/俯仰/横滚) |
| `sensor.orientation` | ANGLE (°) | 50 Hz | 传统方向传感器 |

### 5.2 环境物理量(ENVIRONMENT)

| 探测项 | 被测量 | 采样率 | 说明 |
|---|---|---|---|
| `sensor.light` | ILLUMINANCE (lx) | 20 Hz | 光照强度 |
| `sensor.proximity` | DISTANCE (cm) | 20 Hz | 遮挡状态 |
| `sensor.pressure` | PRESSURE (hPa) | 20 Hz | 气压,量程 300~1100 hPa |
| `sensor.humidity` | RELATIVE_HUMIDITY (%RH) | 20 Hz | 相对湿度 |
| `sensor.temperature` | TEMPERATURE (°C) | 20 Hz | 环境温度 |

### 5.3 磁场(MAGNETIC)

| 探测项 | 被测量 | 采样率 | 说明 |
|---|---|---|---|
| `sensor.magnetometer` / `_uncal` | MAGNETIC_FLUX_DENSITY (µT) | 50 Hz | 磁场强度;幅值用来做电磁环境评估 |

### 5.4 生物信号(BIOSIGNAL)

| 探测项 | 被测量 | 采样率 | 说明 |
|---|---|---|---|
| ⚠️ `sensor.heart_rate` | HEART_RATE (bpm) | 1 Hz | 需要 BODY_SENSORS 权限;过滤无效值,报告可靠性 |
| ⚠️ `sensor.heart_beat` | HEART_RATE (bpm) | 1 Hz | 由心跳间期换算 |

### 5.5 上下文事件(CONTEXT)

| 探测项 | 说明 |
|---|---|
| `sensor.step_counter` | 步数(本次增量 = 末值 − 初值;还有累计值) |
| `sensor.step_detector` / `significant_motion` / `device_orientation` / `pick_up` / `shake` / `flip` / `free_fall` / `tilt` / `wrist_tilt` / `wake` / `glance` / `offbody` | 事件触发计数(事件驱动) |
| `sensor.activity` | 活动识别:静止/步行/跑步/骑行/乘车/倾斜,含分布 |

### 5.6 声学(AUDIO)

| 探测项 | 说明 |
|---|---|
| ⚠️ `noise` | **AudioRecord 直接读 PCM**(44.1kHz/16bit 单声道):50ms 一帧算 RMS → 近似声压级;输出 LAeq(等效连续声级)、Lpeak、统计声级 L10/L50/L90;保留 PCM 尾部(8192 个采样)做 FFT 频谱分析。**未校准,输出是参考级** |
| `audio_state` | 音量/铃声模式/输出设备(含采样率与声道)/输入设备数 |

声学指标:LAeq = 10·log₁₀(Σ10^(Lᵢ/10)/n),能量平均。频谱:FFT-1024~16384 + Hann 窗,输出主导频率、频谱平坦度、低频/中频/高频能量占比。

### 5.7 定位与卫星(POSITIONING)

| 探测项 | 说明 |
|---|---|
| ⚠️ `location` | GPS+网络定位,500ms 采样:经纬度/海拔/精度(水平+垂直)/速度/方位;统计精度与速度分布;首个定位 10s 超时 |
| ⚠️ `gnss` | 卫星状态快照:可见数/参与定位数/星座分布(GPS/GLONASS/北斗/伽利略/QZSS/IRNSS/SBAS)/最佳信噪比 |
| ⚠️ `nmea` | GGA 语句解析:定位质量(1=GPS/2=差分/4=RTK)、使用卫星数、HDOP |
| ⚠️ `gnss_raw` | **GNSS 原始观测量**(GNSS Logger 级别):历元数、载波相位观测、伪距率有效性、多径标志、CN0 统计、星座构成 |
| ⚠️ `gnss_hw` | **GNSS 硬件信息**:硬件型号/年代、能力标志(反射 GnssCapabilities)、天线数与载波频率 |

### 5.8 无线电(RADIO)

| 探测项 | 说明 |
|---|---|
| `wifi` | 连接信息:SSID/BSSID/RSSI/频段/信道/链路速率/IP |
| ⚠️ `wifi_dynamic` | 链路动态:会话期间 RSSI 时序(5Hz)+ 上下行链路速率 + 认证状态 + 热点状态(反射)+ 接口 MAC |
| ⚠️ `wifi_scan` | 环境扫描:AP 数量、**安全分析**(WPA3(SAE)/WPA3-WPA2/WPA2/WPA/WEP/开放)、RSSI 分布统计、明细 |
| ⚠️ `wifi_rtt` | **IEEE 802.11mc FTM 测距**:对支持 RTT 的 AP 测量距离(±标准差、RSSI) |
| ⚠️ `wifi_direct` | **WiFi Direct(P2P)对等发现**:对等设备、设备类型、组所有者 |
| ⚠️ `wifi_aware` | **Wi-Fi Aware(NAN)**:能力特征(服务名长度限制)+ attach/订阅状态 |
| ⚠️ `cellular` | 制式(5G NR/4G LTE/3G)、运营商、MCC/MNC、漫游、**服务小区与邻区**:LTE(RSRP/RSRQ/SNR/CI/TAC/PCI/EARFCN/**带宽**/**反射时序提前**)、NR(SS-RSRP/SS-RSRQ/SS-SINR/NCI/NRARFCN/**频带**)、GSM/WCDMA/CDMA |
| ⚠️ `cellular_series` | **信号时序**:等级(0-4)和 dBm 按 2Hz 全会话采样,跟踪服务小区 |
| `connectivity` | 传输类型(含 VPN 检测)、上下行带宽、IPv4/IPv6、DNS、网关、接口枚举 |
| `network_stats` | **流量与套接字**:开机累计 Rx/Tx 字节与包数(TrafficStats)、逐接口计数(`/proc/net/dev`)、TCP/UDP 套接字数量 |
| ⚠️ `bluetooth` | BLE 扫描:设备数/RSSI 分布/服务 UUID/厂商数据/**广播标志、发射功率、广播长度** |
| ⚠️ `bt_classic` | **经典蓝牙发现**(startDiscovery):名称/地址/设备类别 |
| ⚠️ `bt_paired` | 已配对设备列表 |
| ⚠️ `nfc` | NFC:启用状态、NDEF 推送、**技术列表(反射)** |
| `fm_radio` | **FM 调谐器**(RadioManager 反射,SDK 36 已移除公开 API):模块 id/厂商/硬件/属性 |
| `infrared` | **红外发射器**:是否存在 + 载波频率范围 |

### 5.9 电气(ELECTRICAL)

| 探测项 | 说明 |
|---|---|
| `battery` | 电量、充电状态/方式、健康度、温度、电压、实时电流、累计充电量、额定容量(反射 PowerProfile) |

### 5.10 系统资源与设备(SYSTEM / DEVICE)

| 探测项 | 说明 |
|---|---|
| `system` | CPU 核心数/频率(`/sys` 读取,多数设备不可读会标注)、CPU 使用率(`/proc/stat` 两次采样差分)、负载均值、内存(总量/可用,500ms 周期采样)、存储(内部/外部)、热区温度(thermal_zone,无权限会标注) |
| `thermal` | **热状态**:逐热区温度(sysfs)+ 系统热状态/降频等级(反射 IThermalService) |
| `power_state` | **CPU 电源状态**:在线/存在/可能核心列表、逐核调速器 + 频率范围 + 当前频率(sysfs)、schedstat |
| ⚠️ `kernel` | **内核与安全**:SELinux 强制状态(sysfs)、`/proc/version`、引导加载程序/硬件/修订(反射)、构建标签/类型、序列号(反射,通常受限) |
| `display` | **显示能力**:支持的刷新率模式、当前模式(反射)、HDR 类型、自动亮度/自动旋转/息屏超时 |
| `storage` | **存储卷**(StorageManager):逐卷 UUID/状态/模拟/可移除/容量 |
| `device` | 静态信息:型号/系统版本/安全补丁/内核/ABI、屏幕(分辨率/密度/刷新率/HDR)、亮度、摄像头枚举、USB、振动器、时区/语言/运行时长 |

### 5.11 安全与渗透辅助(SECURITY)—— 主动网络探测

| 探测项 | 说明 |
|---|---|
| ⚠️ `net_arp` | **局域网主机发现**:先对子网内的主机做 TCP 探测,让内核完成 ARP 解析,再读 `/proc/net/arp` 拿到 IP/MAC 列表,并**识别厂商**(内置 OUI 数据库) |
| ⚠️ `net_portscan` | **端口扫描**:对目标主机(默认网关或自定义)做 40+ 常用端口的 TCP connect 扫描,记录每端口延迟并识别服务 |
| ⚠️ `net_http_fingerprint` | **HTTP/TLS 指纹**:抓 HTTP 响应头(Server/X-Powered-By),推断 Web 技术栈(nginx/Apache/IIS/Tomcat…),解析 TLS 证书链(CN/颁发者/签名算法/自签名/是否过期) |
| `net_dns` | **DNS 解析测试**:常见域名的解析延迟、本机 DNS 列表、公共 DNS 连通性(TCP/53) |
| ⚠️ `net_ssdp` | **SSDP/UPnP 设备发现**:UDP 组播 M-SEARCH,列出响应设备(ST/LOCATION/SERVER) |
| `net_ping` | **网关连通性测试**:对目标做 TCP 方式 RTT(最小/平均/最大 + 丢包率),不需要 root |

> 目标主机在首页可配置(默认用网关)。所有安全类探测都是主动网络行为,详见 §7.5。

### 5.12 安全探测扩展(SECURITY)

| 探测项 | 说明 |
|---|---|
| ⚠️ `net_banner` | **服务 Banner 抓取**:读常用端口(FTP/SSH/Telnet/SMTP/HTTP/MySQL/Redis…)的服务横幅,识别版本 |
| ⚠️ `net_http_methods` | **HTTP 方法探测**:目标允许哪些方法(OPTIONS/TRACE/PUT/DELETE) |
| ⚠️ `net_http_security` | **安全头分析**:检查 HSTS/X-Frame-Options/CSP/X-Content-Type-Options 等是否缺失 |
| ⚠️ `net_tls_versions` | **TLS 版本探测**:对 443 尝试 TLSv1/1.1/1.2/1.3 握手 |
| ⚠️ `net_ntp` | **NTP 时间偏移**:对公共 NTP 服务器(阿里/国家授时中心/Google/Pool/腾讯)测时钟偏移 |
| `net_proxy` | **系统代理配置**:HTTP 代理主机/端口/排除列表 + Java 代理属性 |
| ⚠️ `net_subnet_scan` | **全子网扫描**:网段内全部 254 台主机,Web/SSH/SMB 端口探测 |
| ⚠️ `net_mqtt` | **MQTT Broker 探测**:1883 CONNECT/CONNACK 握手 |
| ⚠️ `net_http_paths` | **Web 路径枚举**:/robots.txt /admin /api /phpinfo.php /.git/HEAD 等路径状态码 |
| ⚠️ `net_tcp_concurrency` | **并发连接测试**:同时对目标 443 开 16 条连接,成功率与延迟 |

### 5.13 系统深层分析(SYSTEM)

| 探测项 | 说明 |
|---|---|
| `proc_net_conn` | **网络连接表**:解析 `/proc/net/tcp(+6)`,按状态统计,已建立连接含本地/远端地址与 UID |
| `proc_meminfo` | **内核内存明细**:MemTotal/Free/Available/Buffers/Cached/Swap/Dirty/PageTables/Committed_AS 等 |
| `cpu_per_core` | **逐核 CPU 使用率**:/proc/stat 各 cpuN 行差分(2Hz)→ 逐核百分比时序 |
| `disk_stats` | **磁盘 IO 统计**:/proc/diskstats 差分 → 每秒读写次数与扇区吞吐 |
| `proc_uptime` | **开机与运行统计**:开机时长/空闲占比、主机名、osrelease/ostype、熵 |

### 5.14 校准与电气分析

| 探测项 | 说明 |
|---|---|
| `sensor_calib` | **传感器校准分析**:同一会话对比校准/未校准采样(加速度/陀螺/磁力)→ 逐轴偏差、偏移幅值、磁硬铁偏移估计 |
| `battery_drain` | **电池放电速率**:实时电流×电压 → 功率时序(mW),输出均值/最小/最大功率,按电量计估算续航 |
| `wifi_channel` | **WiFi 信道分析**:各信道 AP 分布、2.4/5/6GHz 频段占比、每信道平均 RSSI 与拥挤度 |

## 6. 分析层(AnalysisEngine)

只根据测量值算专业摘要,**不出主观评分**:

| 模块 | 内容 |
|---|---|
| 声学 | LAeq / Lpeak / L10 / L50 / L90 |
| 振动 | 主导频率(加速度幅值频谱)、RMS 加速度、峰值因子、ISO 2631 近似分级 |
| 定位 | 水平精度、参与定位卫星、HDOP |
| 上下文分类 | 静止/移动/乘车 分类 + 置信度 + 分类特征(speed_ms / accel_rms / activity_sensor) |

## 7. 报告协议(schema v1)

- **JSON**:`MeasurementReport`——测量计划(planId/时长/模式/探测项)、仪器与上下文(设备/系统/内核/时区/电量)、逐项测量(统计量/属性/质量/频谱)、分析摘要。存在 `reports/<id>/report.json`
- **原始样本**:`reports/<id>/samples/<probeId>/channel_<通道>.csv`(`t_ms,值` 格式,逗号分隔,浮点 6 位精度)
- **导出**:JSON / Markdown(完整统计表+质量报告)/ **ZIP(报告+全部原始样本 CSV)**
- **数据质量**:每项带 EXCELLENT/GOOD/DEGRADED/FAILED 等级和原因码

## 7.5 标注了合规风险的探测项

> **合规声明**:请遵守当地法律法规合法使用本软件,使用方式由使用者自行负责。

带 ⚠️ 的探测项,测出来的数据在部分国家和地区可能受法律约束,或属于个人数据(比如欧盟 GDPR、一些国家对位置/GNSS 数据的专门规定):

| 探测项 | 风险 |
|---|---|
| ⚠️ `wifi_scan` | 扫别人的 SSID/BSSID,部分地区按个人数据管 |
| ⚠️ `wifi_dynamic` | 连接信息里带网络标识 |
| ⚠️ `wifi_rtt` | 对周边 AP 实测距离,可能归到位置数据管理 |
| ⚠️ `wifi_direct` / `wifi_aware` | 设备发现会接触到第三方设备信息 |
| ⚠️ `bluetooth` / `bt_classic` | 扫别人的设备 MAC/名称,部分地区按个人数据管 |
| ⚠️ `bt_paired` | 已配对设备列表属于个人数据 |
| ⚠️ `cellular` / `cellular_series` | 采小区标识,部分国家有专门规定 |
| ⚠️ `location` / `gnss` / `nmea` / `gnss_raw` | 高精度定位/GNSS 数据,部分国家管得严 |
| ⚠️ `gnss_hw` | 硬件信息能看出设备的定位能力 |
| ⚠️ `noise` | 用麦克风采声学数据,注意当地录音和隐私规定 |
| ⚠️ `nfc` | 读标签可能碰到别人的设备信息 |
| ⚠️ `sensor.heart_rate` / `sensor.heart_beat` | 心率属于健康数据,个人数据保护规则管得严 |
| ⚠️ `kernel` | 设备序列号属于个人标识符 |
| ⚠️ `net_arp` / `net_portscan` | 主动探测局域网、端口扫描,部分国家按网络安全法规管 |
| ⚠️ `net_http_fingerprint` / `net_ssdp` | 指纹识别、组播发现会碰到第三方服务/设备信息 |
| ⚠️ `net_banner` / `net_http_methods` / `net_http_security` / `net_tls_versions` / `net_http_paths` | 对第三方服务做主动安全测试,部分国家有规定 |
| ⚠️ `net_subnet_scan` | 全子网扫描属高强度主动探测,部分国家按网络安全法规管 |
| ⚠️ `net_mqtt` / `net_tcp_concurrency` / `net_ntp` | 对第三方服务做主动探测 |

这些项在应用里(预检页和报告页)以及导出报告里都会标注;在预检页取消勾选,或者拒绝对应权限,就能把它们排除出测量会话。

## 8. 已知边界(报告里会如实注明)

- 声压级是**未校准参考值**(麦克风灵敏度未知);磁场强度同理,只是参考级
- thermal、CPU 频率这些 `/sys` 文件,多数设备上应用读不了 → 质量标 DEGRADED 并注明
- WiFi 扫描受系统节流(约 2 分钟 4 次) → 原因码 `SYSTEM_THROTTLED`
- GNSS/NMEA 需要 GPS 开启,还得在室外
- 心率这类生物传感器只有少数机型有硬件(能力预检会如实标注)
