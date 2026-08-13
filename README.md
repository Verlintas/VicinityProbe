# VicinityProbe

通过手机的几乎所有传感器与系统模块采集周遭环境信息,一键生成可视化环境数据报告。

## 功能

- **全量探测**: 传感器(加速度/陀螺/磁力/光照/气压/湿度/温度/计步/心率/手势等)、GPS+GNSS 卫星、WiFi/蜂窝/蓝牙、环境噪音、电池、设备系统共 40+ 项
- **能力预检页**: 探测前枚举设备支持情况(✅支持 / ⚠️缺权限 / ❌无硬件),自定义勾选探测项
- **环境分析**: 分维度评分(光照/噪音/温湿度/信号/定位)、综合环境分、场景推断(室内/户外/移动中)、Open-Meteo 天气对比、健康建议
- **报告导出**: 应用内可视化 + JSON / Markdown / PNG 三种格式分享
- **历史与对比**: 报告自动存档,支持重命名/删除/双报告对比
- **连续监测**: 前台服务定时扫描,生成趋势图
- 中英双语(跟随系统)

> 每个探测项的探测内容、技术原理与结果计算方式详见 [docs/PROBES.md](docs/PROBES.md)。

## 构建

```
# 环境要求: JDK 17+, Android SDK 36 (platforms;android-36)
export ANDROID_HOME=<sdk路径>   # 或写入 local.properties: sdk.dir=...
./gradlew assembleDebug         # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest     # 单元测试
./gradlew lint                  # 静态检查
```

## 权限

定位(FINE/COARSE)、邻近 WiFi 设备(NEARBY_WIFI_DEVICES)、蓝牙(BLUETOOTH_SCAN/CONNECT)、麦克风(RECORD_AUDIO)、活动识别(ACTIVITY_RECOGNITION)、生物传感器(BODY_SENSORS)、通知(POST_NOTIFICATIONS)、前台服务。所有权限均在应用内声明用途并可逐项拒绝,拒绝的模块会在报告中标注"权限缺失"而不中断扫描。

## 说明与边界

- 噪音 dB 与磁场辐射等级为近似值(未专业校准),报告中已注明
- 心率等生物传感器仅部分设备支持,预检页会如实标注
- WiFi 扫描受系统节流限制(2 分钟 4 次),受限时报告会注明
- thermal / CPU 频率等系统文件在多数设备上不可读,报告标注"权限受限"
- 天气对比仅此一项联网(Open-Meteo,免费无 Key),其余数据全部本地采集
