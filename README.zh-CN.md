# VicinityProbe

专业环境测量系统:通过手机几乎所有传感器与系统模块进行规范化数据采集,生成带数据质量门禁、原始样本存档与频谱分析的测量报告。

Copyright (C) 2026 Verlintas — GPL-3.0-or-later。参见 [LICENSE](LICENSE) 与源码中的版权头。

[English](README.md) · [探测体系文档](docs/PROBES.zh-CN.md)

## 功能

- **测量目录(44 项)**: 每项探测都是正式的 `ProbeSpec` 条目,定义被测量、SI 单位、标称采样率、量程 —— 覆盖运动学、环境物理量、磁场、生物信号、声学、定位与卫星、无线电、电气、系统资源、设备信息、上下文事件
- **能力预检**: 运行时枚举硬件支持(✅/⚠️缺权限/❌无硬件),自定义测量计划
- **数据质量门禁**: 每项测量输出 EXCELLENT/GOOD/DEGRADED/FAILED 等级 + 覆盖率 + 实际采样率 + 机器可读原因码
- **专业统计**: 每通道 min/max/mean/stddev/RMS/CV + 分位数 p1/p5/p25/p50/p75/p95/p99
- **声学**: AudioRecord 直接采集 PCM → LAeq / Lpeak / L10 / L50 / L90
- **频谱分析**: 手写 Radix-2 FFT(Hann 窗)→ 主导频率 / 频谱平坦度 / 频带能量
- **振动分析**: 主导频率 / RMS / 峰值因子 / ISO 2631 近似分级
- **原始样本存档**: 所有数值通道落盘 CSV(`samples/<probeId>/channel_<ch>.csv`)
- **报告导出**: 版本化 schema 的 JSON + Markdown + ZIP(报告 + 原始样本)
- **历史与对比**: 报告存档 / 重命名 / 删除,双报告统计量与属性逐项对比
- **连续监测**: 前台服务定时测量,质量趋势图
- 中英双语界面(跟随系统)

## 构建

```
# 环境要求: JDK 17+, Android SDK 36
./gradlew assembleRelease     # 产物: app/build/outputs/apk/release/VicinityProbe-<version>.apk
./gradlew testDebugUnitTest   # 单元测试(统计/FFT/分析/报告)
./gradlew lint                # 静态检查
```

## 权限

定位、邻近 WiFi 设备、蓝牙扫描/连接、麦克风、活动识别、生物传感器、通知、前台服务。所有权限应用内声明用途并可逐项拒绝;被拒的探测项在报告中标注 `PERMISSION_DENIED` 而不中断测量。

## 边界(报告如实标注)

- 声压级为**未校准参考值**(绝对声级需设备级校准)
- thermal / CPU 频率等 `/sys` 文件多数设备无读权限
- WiFi 扫描受系统节流限制
- 全部数据本地处理,无任何联网(无遥测、无云同步)
