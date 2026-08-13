# VicinityProbe 探测体系全解析

VicinityProbe 是一套**专业环境测量系统**:每个探测项都是测量目录(ProbeCatalog)中的一个规范条目,定义被测量(Measurand)、单位(SI)、标称采样率、量程与数据质量要求。所有采集数据在本地完成,原始样本以 CSV 存档,报告遵循版本化 schema。

[English](PROBES.md) · Copyright (C) 2026 Verlintas — GPL-3.0-or-later

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

共 **44 项**,分为 11 类(MOTION / ENVIRONMENT / MAGNETIC / BIOSIGNAL / AUDIO / POSITIONING / RADIO / ELECTRICAL / SYSTEM / DEVICE / CONTEXT)。

## 2. 测量流程

```
能力预检(CapabilityProbe) → 采样(Sampler) → 统计(ChannelStats) → 质量门禁(QualityGate) → 报告(schema v1)
```

- **能力预检**:运行时枚举设备硬件(传感器用 `Sensor.getStringType()` 匹配,兼容 SDK 36 已移除的常量),逐项给出 SUPPORTED / NO_HARDWARE / PERMISSION_MISSING / FEATURE_OFF。
- **采样**:各 Sampler 在测量会话(SessionContext)内并发运行,受会话截止时间约束。
- **统计**:每通道由原始样本精确计算(排序求分位数)。
- **质量门禁**:每项输出 QualityReport —— 等级(EXCELLENT/GOOD/DEGRADED/FAILED)、覆盖率(实际/标称采样率)、采样数、实际采样率、机器可读原因码。

## 3. 统计量定义(ChannelStats)

每通道输出:样本数 n、min、max、mean、stddev、RMS、变异系数 CV、分位数 p1/p5/p25/p50(中位数)/p75/p95/p99、末值。

- 在线阶段用 Welford 算法维护实时均值/方差(内存 O(1))。
- 会话结束后对原始样本排序,按最近秩法(nearest-rank)求分位数。

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
| `sensor.accelerometer_uncal` | ACCELERATION | 50 Hz | 未校准原始数据 |
| `sensor.gyroscope` / `_uncal` | ANGULAR_RATE (rad/s) | 50 Hz | 角速度 |
| `sensor.gravity` | ACCELERATION | 50 Hz | 重力矢量 |
| `sensor.linear_acceleration` | ACCELERATION | 50 Hz | 去重力加速度 |
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
| `sensor.magnetometer` / `_uncal` | MAGNETIC_FLUX_DENSITY (µT) | 50 Hz | 磁场强度;幅值供电磁环境评估 |

### 5.4 生物信号(BIOSIGNAL)

| 探测项 | 被测量 | 采样率 | 说明 |
|---|---|---|---|
| `sensor.heart_rate` | HEART_RATE (bpm) | 1 Hz | 需 BODY_SENSORS;过滤无效值,报告可靠性 |
| `sensor.heart_beat` | HEART_RATE (bpm) | 1 Hz | 心跳间期换算 |

### 5.5 上下文事件(CONTEXT)

| 探测项 | 说明 |
|---|---|
| `sensor.step_counter` | 步数(本次增量 = 末值 − 初值;累计值) |
| `sensor.step_detector` / `significant_motion` / `device_orientation` / `pick_up` / `shake` / `flip` / `free_fall` / `tilt` / `wrist_tilt` / `wake` / `glance` / `offbody` | 事件触发计数(事件驱动) |
| `sensor.activity` | 活动识别:静止/步行/跑步/骑行/乘车/倾斜,含分布 |

### 5.6 声学(AUDIO)

| 探测项 | 说明 |
|---|---|
| `noise` | **AudioRecord 直接读取 PCM**(44.1kHz/16bit 单声道):50ms 帧 RMS → 近似声压级;输出 LAeq(等效连续声级)、Lpeak、统计声级 L10/L50/L90;保留 PCM 尾部(8192 采样)做 FFT 频谱分析。**未校准,输出为参考级** |
| `audio_state` | 音量/铃声模式/输出设备(含采样率与声道)/输入设备数 |

声学指标:LAeq = 10·log₁₀(Σ10^(Lᵢ/10)/n),能量平均。频谱:FFT-1024~16384-Hann 窗,输出主导频率、频谱平坦度、低频/中频/高频能量占比。

### 5.7 定位与卫星(POSITIONING)

| 探测项 | 说明 |
|---|---|
| `location` | GPS+网络定位,500ms 采样:经纬度/海拔/精度(水平+垂直)/速度/方位;统计精度与速度分布;首个定位超时 10s |
| `gnss` | 卫星状态快照:可见数/参与定位数/星座分布(GPS/GLONASS/北斗/伽利略/QZSS/IRNSS/SBAS)/最佳信噪比 |
| `nmea` | GGA 语句解析:定位质量(1=GPS/2=差分/4=RTK)、使用卫星数、HDOP |

### 5.8 无线电(RADIO)

| 探测项 | 说明 |
|---|---|
| `wifi` | 连接信息:SSID/BSSID/RSSI/频段/信道/链路速率/IP |
| `wifi_scan` | 环境扫描:AP 数量、**安全分析**(WPA3(SAE)/WPA3-WPA2/WPA2/WPA/WEP/开放)、RSSI 分布统计、明细 |
| `cellular` | 制式(5G NR/4G LTE/3G)、运营商、MCC/MNC、漫游、**服务小区与邻区**:LTE(RSRP/RSRQ/SNR/CI/TAC/PCI/EARFCN)、NR(SS-RSRP/SS-RSRQ/SS-SINR/NCI/NRARFCN)、GSM/WCDMA/CDMA |
| `connectivity` | 传输类型(含 VPN 检测)、上下行带宽、IPv4/IPv6、DNS、网关、接口枚举 |
| `bluetooth` | BLE 扫描 3s:设备数/RSSI 分布/服务 UUID/厂商数据 |
| `bt_paired` | 已配对设备列表 |

### 5.9 电气(ELECTRICAL)

| 探测项 | 说明 |
|---|---|
| `battery` | 电量、充电状态/方式、健康度、温度、电压、实时电流、累计充电量、额定容量(反射 PowerProfile) |

### 5.10 系统资源与设备(SYSTEM / DEVICE)

| 探测项 | 说明 |
|---|---|
| `system` | CPU 核心数/频率(`/sys` 读取,多数设备不可读则标注)、CPU 使用率(`/proc/stat` 两次采样差分)、负载均值、内存(总量/可用,500ms 周期采样)、存储(内部/外部)、热区温度(thermal_zone,无权限则标注) |
| `device` | 静态信息:型号/系统版本/安全补丁/内核/ABI、屏幕(分辨率/密度/刷新率/HDR)、亮度、摄像头枚举、USB、振动器、时区/语言/运行时长 |

## 6. 分析层(AnalysisEngine)

基于测量值计算专业摘要,**不输出主观评分**:

| 模块 | 内容 |
|---|---|
| 声学 | LAeq / Lpeak / L10 / L50 / L90 |
| 振动 | 主导频率(加速度幅值频谱)、RMS 加速度、峰值因子、ISO 2631 近似分级 |
| 定位 | 水平精度、参与定位卫星、HDOP |
| 上下文分类 | 静止/移动/乘车 分类 + 置信度 + 分类特征(speed_ms / accel_rms / activity_sensor) |

## 7. 报告协议(schema v1)

- **JSON**:`MeasurementReport` —— 测量计划(planId/时长/模式/探测项)、仪器与上下文(设备/系统/内核/时区/电量)、逐项测量(统计量/属性/质量/频谱)、分析摘要。`reports/<id>/report.json`
- **原始样本**:`reports/<id>/samples/<probeId>/channel_<通道>.csv`(`t_ms,值` 格式,逗号分隔,浮点 6 位精度)
- **导出**:JSON / Markdown(完整统计表+质量报告)/ **ZIP(报告+全部原始样本 CSV)**
- **数据质量**:每项携带 EXCELLENT/GOOD/DEGRADED/FAILED 等级与原因码

## 8. 已知边界(如实标注)

- 声压级为**未校准参考值**(麦克风灵敏度未知);磁场强度同样为参考级
- thermal / CPU 频率等 `/sys` 文件在多数设备无读权限 → 质量 DEGRADED 并注明
- WiFi 扫描受系统节流(≈2 分钟 4 次) → 原因码 `SYSTEM_THROTTLED`
- GNSS/NMEA 需 GPS 开启且室外环境
- 心率等生物传感器仅少数机型有硬件(能力预检如实标注)
