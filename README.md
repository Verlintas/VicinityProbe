# VicinityProbe

Professional environmental measurement system: standardized data acquisition from virtually every sensor and system module of your phone, producing measurement reports with data quality gates, raw sample archives, and spectral analysis.

> **AI-generated code notice**: This codebase is substantially generated with the assistance of AI coding tools. Please review it before use in production or security-sensitive contexts.

[中文版](README.zh-CN.md) · [Probe system documentation (EN)](docs/PROBES.md)

## Features

- **Measurement catalog (85 probes)**: every probe is a formal `ProbeSpec` entry defining measurand, SI unit, nominal sample rate, and range — covering motion, environmental quantities, magnetics, biosignals, acoustics, positioning & satellites, radio, electrical, system resources, device identity, and context events
- **Capability preflight**: runtime enumeration of hardware support (supported / missing permission / no hardware), custom measurement plans
- **Data quality gate**: every measurement carries EXCELLENT/GOOD/DEGRADED/FAILED level + sampling coverage + achieved rate + machine-readable reason code
- **Professional statistics**: per-channel min/max/mean/stddev/RMS/CV + quantiles p1/p5/p25/p50/p75/p95/p99
- **Acoustics**: AudioRecord direct PCM capture → LAeq / Lpeak / L10 / L50 / L90
- **Spectral analysis**: hand-written Radix-2 FFT (Hann window) → dominant frequency / spectral flatness / band energy
- **Vibration analysis**: dominant frequency / RMS / crest factor / ISO 2631 approximate level
- **Raw sample archive**: every numeric channel persisted to CSV (`samples/<probeId>/channel_<ch>.csv`)
- **Report export**: versioned schema JSON + Markdown + ZIP (report + raw samples)
- **History & comparison**: report archive / rename / delete, side-by-side diff of statistics and attributes
  - **Security & pentest assist**: LAN host discovery (OUI vendor ID), full-subnet scan, port scan + service recognition, banner grabbing, HTTP/TLS fingerprinting (web-stack/certificate analysis), HTTP method & security-header tests, TLS version probing, MQTT broker probe, web path enumeration, concurrency test, NTP time offset, SSDP/UPnP discovery, DNS testing, TCP reachability — configurable target (default: gateway)
  - **Deep system analysis**: connection table, kernel memory detail, per-core CPU usage, disk IO stats, boot/run statistics
  - **Calibration & electrical**: calibrated-vs-uncalibrated sensor bias analysis (mag hard-iron offset), battery drain rate & autonomy estimate, WiFi channel congestion analysis
- **Continuous monitoring**: foreground service with quality trend charts
- Bilingual UI (Chinese/English, follows system locale)

## Build

```
# Requirements: JDK 17+, Android SDK 36
./gradlew assembleRelease     # output: app/build/outputs/apk/release/VicinityProbe-<version>.apk
./gradlew testDebugUnitTest   # unit tests (statistics/FFT/analysis/report)
./gradlew lint                # static analysis
```

## Permissions

Location, nearby WiFi devices, Bluetooth scan/connect, microphone, activity recognition, body sensors, notifications, foreground service. All permissions are declared with their purpose in-app and can be denied individually; denied probes are marked `PERMISSION_DENIED` in the report without aborting the session.

> **Compliance**: Use this software in compliance with all applicable local laws and regulations. You are responsible for how you use it.
>
> **Compliance-flagged probes**: the following collect data that is regulated or personal data in some jurisdictions and are marked ⚠️ in-app and in reports: WiFi scan/RTT/Direct/Aware, Bluetooth scan/classic discovery/paired list, cellular & cell identity, location & GNSS (incl. raw measurements), microphone SPL, NFC, heart rate, device serial, and active network probes (LAN host discovery / port scan / HTTP-TLS fingerprint / SSDP). See [docs/PROBES.md](docs/PROBES.md) §7.5 for details.

## Limitations (honestly reported)

- Sound pressure level is an **uncalibrated reference value** (absolute levels require device calibration)
- `thermal` / CPU frequency sysfs files are unreadable on most devices
- WiFi scans are subject to system throttling
- All data is processed locally — no network access, no telemetry, no cloud sync
