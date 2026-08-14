# 与 AI 的沟通日志（VicinityProbe 增强计划）

> 本文件用于在 Orca 资源管理器 / 本地文件系统中实时查看 AI 的进度。
> 更新时间：2026-08-14

## 当前状态

**阶段 1（摸底）已完成：**
- ✅ 单元测试全部通过（StatsTest / ReportTest / AnalysisEngineTest）
- ✅ lint 无错误
- ✅ 代码库：63 个 Kotlin 文件，约 12,690 行，版本 0.9.2

**阶段 2（可靠性加固）进行中 — 已修复 16/23 项，编译+测试通过：**

已修复 ✅：
- #1 SessionController 采样器改 Dispatchers.Default（修 ANR 根因）
- #2 CaptureController.tick() 加锁快照
- #3 RealTimeViewModel ring 缓冲加 ringLock 同步 + 去除 `!!` 崩溃点
- #4 WebServerService 路径穿越修复（canonicalPath + id 白名单校验）
- #5 连接池化（有界 32 线程）
- #6 serve() 失败时 running 复位 + volatile
- #7 respondFile 流式输出（防大 pcap OOM）
- #8 HTTP body 循环读满（防截断）
- #9 频谱模式忙等修复（读 0 时休眠退避 + 部分读累积）
- #10 pcap 写入加 BufferedOutputStream + tick 内 flush + 256MB 上限
- #11 报告导出移至 IO 线程 + 失败静默处理
- #12 HistoryManager index.json 加锁 + id 校验（防路径穿越）
- #13 MonitoringService interval 最小 5 分钟
- #14 音频线程 join(500) 防并发写 ring
- #15 SensorSampler thread.join(3000) 建立 happens-before
- #16 DNS 劫持检测：空结果也参与比对（修漏报）
- #17 ScanViewModel collectJob finally cancel
- #18 SNI 正则预编译（防每包编译）
- #19 TUN 死亡自动清理（关 fd + 停通知）
- #20 标定传感器缺失给出可见错误提示
- #21 端口扫描空端口表除零防护
- #22 volatile 可见性
- #23 JSON 严格转义

**修复 23/23 项完成** ✅（编译通过）

## 阶段 3：深度数据科学（已完成 ✅）

新增 3 个纯算法模块（均可单元测试）：
1. **`analysis/Fusion.kt`** — 传感器融合：
   - `ComplementaryFilter`：加速度+陀螺仪互补滤波 → 姿态角（含陀螺零偏估计）
   - `tiltCompensatedHeading()`：倾斜补偿航向角（不依赖 TYPE_GRAVITY 传感器，已接入 SensorSampler）
   - `MagCalib`：最小二乘硬铁偏移球心拟合（高斯消元求解）
2. **`analysis/Inference.kt`** — 统计推断：
   - `LinearTrend`：斜率/R²/显著性 p 值（A&S 正态 CDF 近似）
   - `Autocorrelation`：ACF + 周期检测（已接入 AnalysisEngine 振动分析）
   - `Moments`：偏度/超峰度
3. **`analysis/Spectral.kt` 增强** — `SpectralAnalysis`：
   - 谱峰检测（二次插值精确定频 + 显著度）
   - 谐波分析（2f..8f + THD% + 谐波丰富度，已接入音频/振动频谱）

UI 接入：TrendScreen 新增趋势推断卡片（斜率/天 + R² + p + 平稳性判定）
单元测试：新增 FusionTest（8 项）/ InferenceTest（8 项）/ MagCalibTest（2 项）/ SpectralExtTest（4 项），全部通过（共 35 项）

## 阶段 4：网络尖端（已完成 ✅）

新增 3 个探测项（93 → 96）：
1. **`net_arp_table`** ARP 邻居表：读 /proc/net/arp，IP/MAC/设备/状态 + 重复 MAC 检测 + 网关厂商识别
2. **`net_doh`** DNS over HTTPS：cloudflare/google 双端点解析 + 延迟 + 证书检查（宽松信任链检测中间人）
3. **`net_quic`** QUIC 连通性：手写 QUIC Initial 包（UDP 443）+ 版本/SCID 解析

抓包引擎升级：
- **JA3 风格 TLS 指纹**：完整 ClientHello 解析（版本+密码套件+扩展），提取为纯函数 `TlsClientHello`（`analysis/TlsClientHello.kt`）并可单元测试
- **精确 SNI 解析**：替代原来的正则扫描（RFC 6066 结构解析）
- **应用层协议识别**：DNS/DHCP/NTP/SSDP/mDNS/HTTPS/HTTP/SSH/SMTP… 端口 → 协议计数
- Web 控制台 + CaptureScreen 显示新字段（协议分布 / TLS 指纹 TOP）

新增单元测试：TlsClientHelloTest（6 项，合成 ClientHello 验证）。共 41 项全通过。

## 阶段 4：网络尖端（已完成 ✅）

**性能与架构：**
- 全部 ViewModel 的文件 IO 移入 Dispatchers.IO（report/history/compare/trend/scan 存档）
- FFT 蝶形因子预计算表（twiddle cache，减少重复三角函数计算）
- 修复 FFT 溢出 bug（2 shl 30 → 负数）

**UI/UX：**
- Material 3 动态主题（Android 12+ Material You 取色，低版本品牌配色回退）
- 主题切换（系统/浅色/深色，持久化 + 首页顶栏图标）
- 图表触摸交互：十字线 + 数据点高亮 + 实时数值读数

## 阶段 7：最终验证（已完成 ✅）

- ✅ 单元测试 41 项全部通过（新增 22 项：融合/推断/标定/频谱/ClientHello）
- ✅ lint 干净
- ✅ assembleDebug 构建成功（VicinityProbe-0.9.2.apk）

## 附加任务：排查"看不到终端输出"（进行中，有重大发现）

**已定位 2 个问题：**

1. ✅ **已修复：root 占用的 opencode 锁目录**
   - `~/.local/state/opencode/` 被 root 拥有（7月29日安装时产生）
   - 导致 opencode 每次运行都报 `EACCES: permission denied, mkdir .../opencode/locks`
   - 已删除该空目录并重建为 apple 所有（后续 EACCES 错误应消失）

2. ⚠️ **待用户操作：opencode 版本过旧 + Orca 终端渲染**
   - 当前版本 **1.18.9**，最新版 **1.18.18**（差 9 个补丁版，可能含 TUI 渲染修复）
   - 终端程序 = **Orca 1.4.179**（TERM=xterm-256color, truecolor 正常）
   - AI 流式输出本身正常（日志显示 stream 事件正常），问题在 Orca 终端的 TUI 渲染层

**请你手动执行（需要密码）：**
```bash
# 1. 升级 opencode 到最新版（修复 TUI 渲染 bug 的最佳机会）
sudo npm i -g opencode-ai@latest

# 2. 验证升级后输出是否正常
opencode
```

**如果升级后仍看不到输出：**
```bash
# 在 macOS 自带"终端"或 iTerm2 中启动,排除 Orca 内嵌终端的问题
opencode
```
如果标准终端正常 → 是 Orca 内嵌终端与 opencode TUI 的兼容问题，可到
https://github.com/anomalyco/opencode/issues 反馈（附 TERM_PROGRAM=Orca 信息）。

## 变更文件清单

可靠性（23 项修复）：SessionController / PacketCaptureService / WebServerService / RealTimeViewModel / HistoryManager / MonitoringService / ScanViewModel / SensorSampler / CalibrationViewModel / PortScanToolScreen / ReportViewModel / ReportExporter / ReportScreen / HardcoreSampler / Inference

新功能：Fusion.kt / Inference.kt / Spectral 增强 / TlsClientHello.kt / NetEdgeSamplers.kt（ARP 表 / DoH / QUIC）/ ProbeSpec（+3 探测项）/ CaptureScreen / WebConsole

- 阶段 3：深度数据科学（传感器融合/STFT/统计推断）
- 阶段 4：网络尖端（TLS 指纹/QUIC/ARP 邻居表/DoT）
- 阶段 5：性能与架构（协程化/FFT 加速）
- 阶段 6：UI/UX（Material 3 动态主题/深色模式）
- 阶段 7：最终验证（测试 + lint + 构建）
