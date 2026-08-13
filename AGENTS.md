# AGENTS.md

## 构建与验证命令

- 构建 release APK: `./gradlew assembleRelease`(产物 `app/build/outputs/apk/release/VicinityProbe-<version>.apk`)
- 构建 debug APK: `./gradlew assembleDebug`
- 单元测试: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lint`
- 本机 Android SDK 位于 `/opt/homebrew/share/android-commandlinetools`(见 local.properties)

## 项目结构

- `app/src/main/java/com/vicinityprobe/`
  - `model/domain/` — 领域模型: 测量目录(ProbeCatalog/ProbeSpec/Measurand/UnitDef/Category)、统计量(ChannelStats)、质量门禁(QualityReport/QualityLevel)、测量报告(MeasurementReport/Measurement/Plan/Context/Analysis)
  - `probe/` — 采样器实现(SensorBatchSampler / LocationSampler / GnssSampler / NmeaSampler / WifiSampler / WifiScanSampler / CellularSampler / ConnectivitySampler / BluetoothSampler / PairedDevicesSampler / AudioSampler / AudioStateSampler / BatterySampler / DeviceSampler / SystemSampler)、能力预检(CapabilityProbe)、会话编排(SessionController)、通道记录器(ChannelRecorder, CSV 存档)
  - `analysis/` — FFT(Spectral.kt)、频谱/声学分析、分析引擎(AnalysisEngine: 声学/振动/定位/上下文分类)
  - `report/` — 报告协议(JsonReport)、Markdown 生成、历史管理、对比引擎、ZIP 导出、PNG 渲染
  - `service/` — 连续监测前台服务
  - `ui/` — 7 个屏幕 + 图表组件(折线图/质量徽章)

## 约定

- 所有可读文本使用 `bil("中文","English")` 格式存储,UI 用 `trBilingual(s, lang)` 取当前语言
- **测量目录是唯一规范源**:新增探测项必须先注册到 `ProbeCatalog`,再实现 Sampler,并在 `SessionController.buildUnits()` 与 `CapabilityProbe` 中登记
- 探测项 id 命名: `sensor.*` / `location` / `gnss` / `nmea` / `wifi` / `wifi_scan` / `cellular` / `connectivity` / `bluetooth` / `bt_paired` / `noise` / `audio_state` / `battery` / `device` / `system`
- 数值通道必须写 ChannelRecorder 并落盘 CSV;非数值信息放 `attributes`
- 每项测量必须给出 QualityReport(等级 + 原因码);禁止静默吞掉失败
- SDK 36 已移除部分废弃传感器常量,用 `Sensor.getStringType()` 运行时匹配(见 `SensorBatchSampler.bindings`)
- 分析引擎只输出测量派生指标,不输出主观评分
