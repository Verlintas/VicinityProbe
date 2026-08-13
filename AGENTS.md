# AGENTS.md

## 构建与验证命令

- 构建 debug APK: `./gradlew assembleDebug`
- 单元测试: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lint`
- 本机 Android SDK 位于 `/opt/homebrew/share/android-commandlinetools`(见 local.properties)

## 项目结构

- `app/src/main/java/com/vicinityprobe/`
  - `model/` — 序列化数据模型 + 中英双语标签(Labels,格式 "zh|en")
  - `probe/` — 探测实现(SensorBatchProbe / LocationProbe / NetworkProbe / AudioProbe / BatteryProbe / DeviceProbe)、能力预检(CapabilityProbe)、控制器(ProbeController)
  - `analysis/` — 环境评分/场景推断/天气对比(Analyzer, WeatherClient)
  - `report/` — JSON/MD writer、历史管理、PNG 渲染、对比引擎
  - `service/` — 连续监测前台服务
  - `ui/` — 7 个屏幕(Home/Preflight/Scanning/Report/History/Compare/Trend)+ 图表组件

## 约定

- 报告中所有可读文本使用 `bil("中文","English")` 格式存储,UI 用 `trBilingual(s, lang)` 取当前语言
- 探测项 id 命名: `sensor.*` / `location` / `gnss` / `nmea` / `wifi` / `wifi_scan` / `cellular` / `connectivity` / `bluetooth` / `bt_paired` / `noise` / `audio_state` / `battery` / `device` / `system`
- 新增探测项需同步: SensorSpecs 或 probe 实现 + CapabilityProbe 枚举 + Labels 名称
- SDK 36 已移除部分废弃传感器常量(TYPE_ACTIVITY_RECOGNITION 等),用字符串查找 `RemovedSensorTypes`
- 分析器依赖的数值指标 key 见 Analyzer.num 的查找约定,新增指标注意保持数值开头
