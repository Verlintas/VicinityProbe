# VicinityProbe

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

Professional environmental measurement system: standardized data acquisition from virtually every sensor and system module of your phone, producing measurement reports with data quality gates, raw sample archives, and spectral analysis.

> **AI-generated code notice**: This codebase is substantially generated with the assistance of AI coding tools. Please review it before use in production or security-sensitive contexts.

[中文版](README.zh-CN.md) · [Probe docs (EN)](docs/PROBES.md) · [Device test checklist](docs/TESTING.md) · [CONTRIBUTING](CONTRIBUTING.md) · [SECURITY](SECURITY.md)

## Features

- **Measurement catalog (96 probes)**: every probe is a formal `ProbeSpec` entry defining measurand, SI unit, nominal sample rate, and range — covering motion, environmental quantities, magnetics, biosignals, acoustics, positioning & satellites, radio, electrical, system resources, device identity, and context events
- **Capability preflight**: runtime enumeration of hardware support (supported / missing permission / no hardware), custom measurement plans
- **Data quality gate**: every measurement carries EXCELLENT/GOOD/DEGRADED/FAILED level + sampling coverage + achieved rate + machine-readable reason code
- **Professional statistics**: per-channel min/max/mean/stddev/RMS/CV + quantiles p1/p5/p25/p50/p75/p95/p99
- **Statistical inference**: least-squares linear trend with R² and significance p-value, autocorrelation periodicity detection, skewness/kurtosis — wired into trend charts and vibration analysis
- **Sensor fusion**: complementary filter (accel+gyro attitude), tilt-compensated heading, least-squares magnetometer hard-iron calibration (sphere fit)
- **Sensor denoising**: rolling median filter (spike removal), exponential moving average, 1D Kalman filter — real-time smoothing toggle in the monitor
- **Acoustics**: AudioRecord direct PCM capture → LAeq / Lpeak / L10 / L50 / L90; **sound level recorder** (per-minute LAeq bins, session stats, 70 dB reference chart, CSV export)
- **Spectral analysis**: hand-written Radix-2 FFT (Hann window) → dominant frequency / spectral flatness / band energy, **spectral peaks** (parabolic-interpolated), **harmonic analysis with THD%** (2f–8f)
- **Vibration analysis**: dominant frequency / RMS / crest factor / THD / periodicity / ISO 2631 approximate level
- **Raw sample archive**: every numeric channel persisted to CSV (`samples/<probeId>/channel_<ch>.csv`)
- **Report export**: versioned schema JSON + Markdown + ZIP (report + raw samples)
- **History & comparison**: report archive / rename / delete, side-by-side diff of statistics and attributes
- **Root-free packet capture** (VPNService): protocol statistics (TCP/UDP/ICMP), connection flow table (5-tuple, bytes, state), DNS query domains, precise RFC 6066 SNI extraction, plaintext HTTP request parsing, **JA3-style TLS client fingerprinting**, **application-layer protocol identification** (DNS/DHCP/NTP/SSDP/mDNS/HTTP/HTTPS/SSH/SMTP…) — exportable as **standard pcap** openable in Wireshark
- **LAN web console**: built-in HTTP server — desktop-browser dashboard with all reports, raw CSV & pcap downloads, live capture stats, and **remote scan triggering** (LAN only)
- **Real-time monitor**: live oscilloscope for sensors (accel/gyro/mag/light/temp/pressure/noise), **attitude spirit level** (complementary-filtered roll/pitch bubble disc), audio **spectrum waterfall** (FFT), threshold alerts via notification (noise/temp/light)
- **Sensor calibration wizard**: guided magnetometer figure-8 (hard-iron offset), accelerometer gravity reference, gyroscope bias — outputs a shareable calibration report
- **Sensor RAW recorder**: max-rate raw stream capture (accel/gyro/mag/rotation/pressure) to CSV with live multi-channel waveforms and sample-rate counter
- **Security audit engine**: aggregates all security probes (ports/TLS/certificates/HTTP/WiFi/SMB/MQTT/SSH) into a leveled (INFO→CRITICAL) audit report — rendered in-app and shareable as Markdown
- **Protocol deep probing**: TLS negotiated cipher suite / protocol / ALPN extraction, SSH version banner, hand-written SMB2 NEGOTIATE (dialect + signing mode)
- **NFC card analyzer**: reader-mode tag discovery (ISO 14443 A/B, ISO 15693, FeliCa) → UID/ATQA/SAK, Mifare Classic/Ultralight/NTAG/DESFire type recognition, **Mifare default-key security audit** (KeyA+KeyB, per-sector unlock count, full data dump of unlocked sectors), NDEF parsing (TEXT/URI/Smart Poster), **NDEF writer** for owned test tags, risk rating (INFO/LOW/HIGH)
- **HCE card emulator**: HostApduService (AID F0010203040506) — SELECT/READ/GET_UID APDU handling to test readers you own
- **AI deep analysis (optional, BYO key)**: local anomaly detection + LLM interpretation of any report — OpenAI-compatible APIs (OpenAI/DeepSeek/Kimi/GLM/Qwen/Groq/Mistral/OpenRouter/xAI/local Ollama), structured JSON output rendered as cards, cached per report, multi-report **trend interpretation**, temperature/tokens/custom-prompt settings, Keystore-encrypted key, sanitization before sending
- **Security & pentest assist**: LAN host discovery (OUI vendor ID), full-subnet scan, port scan + service recognition, banner grabbing, HTTP/TLS fingerprinting (web-stack/certificate analysis), HTTP method & security-header tests, TLS version probing, MQTT broker probe, web path enumeration, concurrency test, NTP time offset, SSDP/UPnP discovery, DNS testing, TCP reachability — configurable target (default: gateway)
- **Packet sender tool**: custom UDP/TCP payloads in hex with response hex/ASCII echo — for protocol testing on authorized targets
- **Capture enrichment**: TLS version distribution (from ClientHello) + QUIC (UDP/443) detection + top-IP traffic ranking
- **Hardcore network detection**: **DNS hijack detection** (self-written DNS client, cross-resolver consistency), **ARP spoofing detection** (gateway MAC change monitoring), **ARP neighbor table** (/proc/net/arp with duplicate-MAC & vendor analysis), **DNS-over-HTTPS probe** (encrypted resolution with latency/cert check), **QUIC connectivity probe** (hand-written QUIC Initial over UDP 443), **mDNS service discovery**, **UPnP device deep-parse** (description XML: types/services/serial), **audio link loopback test** (speaker→mic latency)
- **More tools**: HTTP request tool (curl-style: method/headers/body/redirects, full response) + custom port-range scanner (start/end/concurrency/timeout)
- **Deep system analysis**: connection table, kernel memory detail, per-core CPU usage, disk IO stats, boot/run statistics
- **Network diagnostics**: **ping monitor** (continuous TCP ping with latency/jitter/loss series), **speed test** (Cloudflare edge — latency/jitter + 20 MB download + 16 MB upload with Mbps readout), **network health matrix** (parallel radar view of gateway/DNS/public endpoints)
- **Mobile diagnostics**: **GPS track recorder** (1 Hz track with distance/speed stats, speed-colored track map, KML/CSV export), **battery discharge logger** (voltage/current/temp curves, %/h rate, power draw, remaining-time estimate), **WiFi signal map** (location-tagged RSSI samples → heatmap), **GNSS satellite sky plot** (azimuth/elevation per constellation, C/N0 ranking), **Bluetooth deep analysis** (per-device RSSI distribution, OUI vendor, BLE/classic)
- **Calibration & electrical**: calibrated-vs-uncalibrated sensor bias analysis (mag hard-iron offset), battery drain rate & autonomy estimate, WiFi channel congestion analysis
- **Continuous monitoring**: foreground service with quality trend charts + **trend inference** (significant up/down/stationary verdict)
- **Theming & interaction**: Material 3 dynamic color (Material You on Android 12+), system/light/dark toggle, interactive charts with touch crosshair, haptic feedback on primary actions, screen keep-awake during active measurement, pull-to-refresh
- Bilingual UI (Chinese/English, follows system locale)

## Build

```
# Requirements: JDK 17+, Android SDK 36
./gradlew assembleRelease     # output: app/build/outputs/apk/release/VicinityProbe-<version>.apk
./gradlew testDebugUnitTest   # unit tests (statistics/FFT/analysis/fusion/TLS/NFC)
./gradlew lint                # static analysis
```

## Permissions

Location, nearby WiFi devices, Bluetooth scan/connect, microphone, NFC, IR blaster, activity recognition, body sensors, notifications, foreground service. All permissions are declared with their purpose in-app and can be denied individually; denied probes are marked `PERMISSION_DENIED` in the report without aborting the session.

> **Compliance**: Use this software in compliance with all applicable local laws and regulations. You are responsible for how you use it.
>
> **Compliance-flagged probes**: the following collect data that is regulated or personal data in some jurisdictions and are marked ⚠️ in-app and in reports: WiFi scan/RTT/Direct/Aware, Bluetooth scan/classic discovery/paired list, cellular & cell identity, location & GNSS (incl. raw measurements), microphone SPL, NFC, heart rate, device serial, and active network probes (LAN host discovery / port scan / HTTP-TLS fingerprint / SSDP / DoH / QUIC). See [docs/PROBES.md](docs/PROBES.md) §7.5 for details.
>
> **NFC security-testing scope**: the NFC analyzer reads tag metadata and audits **default-key accessibility only** (no key recovery, no cloning); the NDEF writer and HCE emulator are for **tags and readers you own**. Breaking into others' cards is illegal — test responsibly.

## Limitations (honestly reported)

- `thermal` / CPU frequency sysfs files are unreadable on most devices
- WiFi scans are subject to system throttling
- No telemetry, no cloud sync — all data stays on-device; active network probes (port scan, packet capture, DNS comparison, LAN discovery…) run only when you trigger them
- **Hardware limits**: phones lack 125 kHz RFID antennas, Mifare offline key recovery and RF sniffing — dedicated hardware tools are required for those
- Sound pressure level is an **uncalibrated reference value**; magnetic-field level likewise
