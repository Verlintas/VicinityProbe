# VicinityProbe Probe System Reference

VicinityProbe is a **professional environmental measurement system**. Every probe is a formal entry in the measurement catalog (`ProbeCatalog`) defining measurand, SI unit, nominal sample rate, range, and data quality requirements. All data is acquired locally; raw samples are archived as CSV; reports follow a versioned schema.

[中文版](PROBES.zh-CN.md)

## 1. Measurement catalog (ProbeCatalog)

Each probe entry `ProbeSpec` defines:

| Field | Meaning |
|---|---|
| `id` | Globally unique identifier |
| `measurand` | Physical quantity (ACCELERATION / ILLUMINANCE / SOUND_PRESSURE_LEVEL …) |
| `unit` | Unit (symbol + SI representation) |
| `nominalRateHz` | Nominal sample rate; 0 = event-driven |
| `typicalRange` | System nominal range |
| `sampleChannels` | Measurement channels (e.g. x/y/z/magnitude) |
| `keepRawSamples` | Whether raw samples are archived |
| `requiredPermissions` | Required permissions |

61 probes across 11 categories (MOTION / ENVIRONMENT / MAGNETIC / BIOSIGNAL / AUDIO / POSITIONING / RADIO / ELECTRICAL / SYSTEM / DEVICE / CONTEXT / SECURITY).

## 2. Measurement pipeline

```
Capability preflight (CapabilityProbe) → Sampling (Sampler) → Statistics (ChannelStats) → Quality gate (QualityReport) → Report (schema v1)
```

- **Capability preflight**: enumerates device hardware at runtime (sensors matched by `Sensor.getStringType()`, compatible with SDK 36 which removed legacy constants), reporting SUPPORTED / NO_HARDWARE / PERMISSION_MISSING / FEATURE_OFF per probe.
- **Sampling**: samplers run concurrently within a measurement session (`SessionContext`) bounded by a deadline.
- **Statistics**: computed exactly from raw samples after the session (quantiles by sorting).
- **Quality gate**: every probe yields a `QualityReport` — level (EXCELLENT/GOOD/DEGRADED/FAILED), sampling coverage (achieved/nominal), sample count, achieved rate, and a machine-readable reason code.

## 3. Statistics definition (ChannelStats)

Per channel: sample count n, min, max, mean, stddev, RMS, coefficient of variation (CV), quantiles p1/p5/p25/p50 (median)/p75/p95/p99, and last value.

- Online phase: Welford's algorithm maintains running mean/variance (O(1) memory).
- Finalize phase: raw samples are sorted; quantiles are computed by the nearest-rank method.

## 4. Quality gate

| Level | Criterion |
|---|---|
| EXCELLENT | coverage ≥ 80% and good sensor accuracy |
| GOOD | coverage ≥ 50% |
| DEGRADED | coverage ≥ 10%, or insufficient samples, or sensor uncalibrated |
| FAILED | no hardware / permission denied / feature off / no fix / acquisition error / no data |

Reason codes (machine-readable): `OK / NO_HARDWARE / PERMISSION_DENIED / FEATURE_OFF / NO_FIX / INSUFFICIENT_SAMPLES / SAMPLE_RATE_LOW / SENSOR_UNCALIBRATED / ACQUISITION_ERROR / NO_DATA / SYSTEM_THROTTLED`

## 5. Probe details

### 5.1 Motion (MOTION)

| Probe | Measurand | Rate | Notes |
|---|---|---|---|
| `sensor.accelerometer` | ACCELERATION (m/s²) | 50 Hz | 3-axis + magnitude channels, range ±2~±16 g |
| `sensor.accelerometer_uncal` | ACCELERATION | 50 Hz | Uncalibrated raw values |
| `sensor.gyroscope` / `_uncal` | ANGULAR_RATE (rad/s) | 50 Hz | Angular velocity |
| `sensor.gravity` | ACCELERATION | 50 Hz | Gravity vector |
| `sensor.linear_acceleration` | ACCELERATION | 50 Hz | Gravity-compensated acceleration |
| `sensor.rotation_vector` / `game_rotation_vector` / `geomagnetic_rotation` | ORIENTATION_QUATERNION | 50 Hz | Quaternion → Euler angles (azimuth/pitch/roll) |
| `sensor.orientation` | ANGLE (°) | 50 Hz | Legacy orientation sensor |

### 5.2 Environmental quantities (ENVIRONMENT)

| Probe | Measurand | Rate | Notes |
|---|---|---|---|
| `sensor.light` | ILLUMINANCE (lx) | 20 Hz | Ambient illuminance |
| `sensor.proximity` | DISTANCE (cm) | 20 Hz | Covered/clear state |
| `sensor.pressure` | PRESSURE (hPa) | 20 Hz | Barometric pressure, range 300~1100 hPa |
| `sensor.humidity` | RELATIVE_HUMIDITY (%RH) | 20 Hz | Relative humidity |
| `sensor.temperature` | TEMPERATURE (°C) | 20 Hz | Ambient temperature |

### 5.3 Magnetics (MAGNETIC)

| Probe | Measurand | Rate | Notes |
|---|---|---|---|
| `sensor.magnetometer` / `_uncal` | MAGNETIC_FLUX_DENSITY (µT) | 50 Hz | Magnetic field; magnitude channel for EM environment assessment |

### 5.4 Biosignals (BIOSIGNAL)

| Probe | Measurand | Rate | Notes |
|---|---|---|---|
| ⚠️ `sensor.heart_rate` | HEART_RATE (bpm) | 1 Hz | Requires BODY_SENSORS; invalid values filtered, reliability reported |
| ⚠️ `sensor.heart_beat` | HEART_RATE (bpm) | 1 Hz | Derived from beat intervals |

### 5.5 Context events (CONTEXT)

| Probe | Notes |
|---|---|
| `sensor.step_counter` | Step delta (last − first) and total since boot |
| `sensor.step_detector` / `significant_motion` / `device_orientation` / `pick_up` / `shake` / `flip` / `free_fall` / `tilt` / `wrist_tilt` / `wake` / `glance` / `offbody` | Event trigger counts (event-driven) |
| `sensor.activity` | Activity recognition: stationary/walking/running/cycling/vehicle/tilting, with distribution |

### 5.6 Acoustics (AUDIO)

| Probe | Notes |
|---|---|
| ⚠️ `noise` | **AudioRecord direct PCM capture** (44.1 kHz / 16-bit mono): 50 ms frame RMS → approximate SPL; outputs LAeq (equivalent continuous level), Lpeak, statistical levels L10/L50/L90; retains the PCM tail (8192 samples) for FFT spectral analysis. **Uncalibrated — reference level only** |
| `audio_state` | Volumes / ringer mode / output devices (with sample rate & channels) / input device count |

Acoustic metrics: LAeq = 10·log₁₀(Σ10^(Lᵢ/10)/n) (energy average). Spectrum: FFT-1024~16384 with Hann window; outputs dominant frequency, spectral flatness, and low/mid/high band energy ratios.

### 5.7 Positioning & satellites (POSITIONING)

| Probe | Notes |
|---|---|
| ⚠️ `location` | GPS + network location at 500 ms intervals: lat/lon/altitude/accuracy (horizontal+vertical)/speed/bearing; accuracy & speed distributions; 10 s first-fix timeout |
| ⚠️ `gnss` | Satellite status snapshots: visible/used-in-fix counts, constellation distribution (GPS/GLONASS/BeiDou/Galileo/QZSS/IRNSS/SBAS), best SNR |
| ⚠️ `nmea` | GGA sentence parsing: fix quality (1=GPS/2=DGPS/4=RTK), satellites used, HDOP |
| ⚠️ `gnss_raw` | **GNSS raw measurements** (Android GNSS Logger grade): epochs, carrier-phase observations, pseudorange-rate validity, multipath flags, CN0 statistics, constellation mix |
| ⚠️ `gnss_hw` | **GNSS hardware info**: hardware model/year, capabilities flags (reflection over GnssCapabilities), antenna count & carrier frequencies |

### 5.8 Radio (RADIO)

| Probe | Notes |
|---|---|
| `wifi` | Connection info: SSID/BSSID/RSSI/band/channel/link speed/IP |
| ⚠️ `wifi_dynamic` | Link dynamics over the session: RSSI time series (5 Hz) + Rx/Tx link speeds + supplicant state + hotspot state (reflection) + interface MAC |
| ⚠️ `wifi_scan` | Environment scan: AP count, **security analysis** (WPA3(SAE)/WPA3-WPA2/WPA2/WPA/WEP/OPEN), RSSI distribution, details |
| ⚠️ `wifi_rtt` | **IEEE 802.11mc FTM ranging**: distance to RTT-capable APs (distance ± stddev, RSSI) |
| ⚠️ `wifi_direct` | **WiFi Direct (P2P) peer discovery**: peers, device type, group owners |
| ⚠️ `wifi_aware` | **Wi-Fi Aware (NAN)**: capability characteristics (service name length limits) + attach/subscribe status |
| ⚠️ `cellular` | Generation (5G NR/4G LTE/3G), operator, MCC/MNC, roaming, **serving & neighbor cells**: LTE (RSRP/RSRQ/SNR/CI/TAC/PCI/EARFCN/**bandwidth**/**timing advance via reflection**), NR (SS-RSRP/SS-RSRQ/SS-SINR/NCI/NRARFCN/**bands**), GSM/WCDMA/CDMA |
| ⚠️ `cellular_series` | **Signal time series**: level (0–4) and dBm sampled at 2 Hz over the session, serving cell tracking |
| `connectivity` | Transports (incl. VPN detection), uplink/downlink bandwidth, IPv4/IPv6, DNS, gateway, interface enumeration |
| `network_stats` | **Traffic & sockets**: total Rx/Tx bytes & packets since boot (TrafficStats), per-interface counters (`/proc/net/dev`), TCP/UDP socket counts |
| ⚠️ `bluetooth` | BLE scan: device count / RSSI distribution / service UUIDs / manufacturer data / **adv flags, Tx power, adv length** |
| ⚠️ `bt_classic` | **Classic Bluetooth discovery** (startDiscovery): device name/address/device class |
| ⚠️ `bt_paired` | Paired device list |
| ⚠️ `nfc` | NFC adapter: enabled state, NDEF push, **technology list (reflection)** |
| `fm_radio` | **FM radio tuners** (RadioManager via reflection — API removed in SDK 36): module id/vendor/hw/properties |
| `infrared` | **IR emitter**: presence + carrier frequency ranges |

### 5.9 Electrical (ELECTRICAL)

| Probe | Notes |
|---|---|
| `battery` | Level, charging state/type, health, temperature, voltage, live current, charge counter, rated capacity (PowerProfile reflection) |

### 5.10 System resources & device (SYSTEM / DEVICE)

| Probe | Notes |
|---|---|
| `system` | CPU cores/frequencies (sysfs read; unreadable on most devices — reported), CPU usage (`/proc/stat` two-sample delta), load average, memory (total/available, 500 ms polling), storage (internal/external), thermal zones (thermal_zone sysfs; no permission — reported) |
| `thermal` | **Thermal status**: per-zone temperatures (sysfs) + system thermal status / throttling severity (IThermalService via reflection) |
| `power_state` | **CPU power state**: online/present/possible core lists, per-core governor + frequency range + current frequency (sysfs), schedstat |
| ⚠️ `kernel` | **Kernel & security**: SELinux enforcing state (sysfs), `/proc/version`, bootloader/hardware/revision (reflection), build tags/type, serial (reflection, usually restricted) |
| `display` | **Display capabilities**: supported refresh modes, current mode (reflection), HDR types, auto-brightness/auto-rotate/screen-off timeout |
| `storage` | **Storage volumes** (StorageManager): per-volume UUID/state/emulated/removable/capacity |
| `device` | Static info: model/OS version/security patch/kernel/ABIs, display (resolution/density/refresh rate/HDR), brightness, camera enumeration, USB, vibrator, timezone/locale/uptime |

### 5.11 Security & pentest assist (SECURITY) — active network probing

| Probe | Notes |
|---|---|
| ⚠️ `net_arp` | **LAN host discovery**: TCP-probes subnet hosts to trigger kernel ARP resolution, then reads `/proc/net/arp` → IP/MAC list with **vendor identification** (built-in OUI database) |
| ⚠️ `net_portscan` | **Port scan**: TCP-connect scan of 40+ well-known ports on the target (default gateway or configured host), per-port latency + service identification |
| ⚠️ `net_http_fingerprint` | **HTTP/TLS fingerprint**: HTTP response headers (Server/X-Powered-By), web-stack inference (nginx/Apache/IIS/Tomcat/…), TLS certificate chain analysis (CN/issuer/signature algorithm/self-signed/expired) |
| `net_dns` | **DNS resolution test**: resolution latency for common domains, local DNS servers, public-DNS reachability (TCP/53) |
| ⚠️ `net_ssdp` | **SSDP/UPnP discovery**: UDP multicast M-SEARCH → responding devices (ST/LOCATION/SERVER) |
| `net_ping` | **Gateway reachability**: TCP-based RTT to target (min/avg/max + loss), no root required |

> Target host is configurable on the Home screen (defaults to the gateway). All security probes are active network actions — see §7.5.

### 5.12 Security probes — extended (SECURITY)

| Probe | Notes |
|---|---|
| ⚠️ `net_banner` | **Service banner grab**: read service banners on well-known ports (FTP/SSH/Telnet/SMTP/HTTP/MySQL/Redis…), version inference |
| ⚠️ `net_http_methods` | **HTTP method probe**: OPTIONS/TRACE/PUT/DELETE allow-listing per target |
| ⚠️ `net_http_security` | **Security-header analysis**: presence check of HSTS/X-Frame-Options/CSP/X-Content-Type-Options… |
| ⚠️ `net_tls_versions` | **TLS version probe**: handshake attempts for TLSv1/1.1/1.2/1.3 on 443 |
| ⚠️ `net_ntp` | **NTP time offset**: clock offset vs public NTP servers (Aliyun/NTSC/Google/Pool/Tencent) |
| `net_proxy` | **System proxy config**: HTTP proxy host/port/exclusions + Java proxy properties |
| ⚠️ `net_subnet_scan` | **Full subnet scan**: all 254 hosts, web/SSH/SMB ports (80/443/8080/22/445) |
| ⚠️ `net_mqtt` | **MQTT broker probe**: CONNECT/CONNACK handshake on 1883 |
| ⚠️ `net_http_paths` | **Web path enumeration**: status codes for /robots.txt /admin /api /phpinfo.php /\.git/HEAD… |
| ⚠️ `net_tcp_concurrency` | **Concurrency test**: 16 simultaneous connections to target 443, success rate + RTT |

### 5.13 Deep system probes (SYSTEM)

| Probe | Notes |
|---|---|
| `proc_net_conn` | **Connection table**: `/proc/net/tcp(+6)` parse — per-state counts, established connections with local/remote addr & UID |
| `proc_meminfo` | **Kernel memory detail**: MemTotal/Free/Available/Buffers/Cached/Swap/Dirty/PageTables/Committed_AS… |
| `cpu_per_core` | **Per-core CPU usage**: per-cpuN `/proc/stat` deltas at 2 Hz → per-core % time series |
| `disk_stats` | **Disk IO stats**: `/proc/diskstats` deltas → read/write ops per s, sector throughput |
| `proc_uptime` | **Boot & run stats**: uptime/idle ratio, hostname, osrelease/ostype, entropy |

### 5.14 Calibration & electrical analysis

| Probe | Notes |
|---|---|
| `sensor_calib` | **Sensor calibration analysis**: calibrated-vs-uncalibrated sample deltas (accel/gyro/mag) → per-axis bias, offset magnitude, mag hard-iron offset estimate |
| `battery_drain` | **Battery drain rate**: live current×voltage power time series (mW) → mean/min/max power, estimated autonomy from charge counter |
| `wifi_channel` | **WiFi channel analysis**: AP distribution per channel, 2.4/5/6 GHz band split, per-channel avg RSSI + congestion |

## 5.15 Root-free packet capture (VPNService)

| Item | Description |
|---|---|
| Engine | System VPN tunnel (`VpnService`) takes over all traffic; IP packets parsed in-process — **no root required** |
| Protocols | IPv4/IPv6 → TCP / UDP / ICMP; per-protocol packet & byte counters |
| Flow table | TCP & UDP 5-tuple flows (client/server, sent/recv bytes, packet count, state via SYN/FIN/RST), 45 s idle expiry |
| DNS | Client→53 query names decoded (label parsing with compression-pointer support) |
| TLS SNI | Server-name extracted from ClientHello; counts toward domain ranking |
| HTTP | Plaintext requests on 80/8080 (method, path, Host) |
| Export | Standard **pcap** file (LINKTYPE_RAW), shareable → open directly in Wireshark |
| Enrichment | TLS version distribution extracted from ClientHello; QUIC (UDP/443) detection |
| UI | Live stats: protocol cards, top flows, top domains, HTTP request log; start/stop with system VPN authorization dialog |

> ⚠️ **Compliance**: capturing collects plaintext traffic and DNS — high-risk probe; only capture networks you are authorized to inspect. In-app notice shown on the capture screen.

## 5.16 LAN web console (built-in HTTP server)

| Item | Description |
|---|---|
| Transport | Minimal HTTP server (ServerSocket, no dependencies), port 8080, LAN only |
| Dashboard | `/` — dark-themed console: report list with quality badges, live capture stats, probe capabilities |
| APIs | `/api/reports`, `/api/report/{id}`, `/api/capture`, `/api/capabilities` (JSON) |
| Downloads | `/download/report/{id}`, `/download/samples/{id}/…` (CSV), `/download/pcap` |
| Remote scan | `POST /api/scan` with `ids` + `duration` → runs a session in the foreground service, report saved to history |
| Notice | ⚠️ LAN only — never expose to the public internet |

## 5.17 Real-time monitor

| Item | Description |
|---|---|
| Oscilloscope | Live waveform (FASTEST sampling) for accelerometer / gyroscope / magnetometer / light / temperature / pressure / SPL; 800-point ring buffer, 3 channels (x/y/z) |
| Spectrum waterfall | AudioRecord 4096-pt FFT → 64 log-magnitude bins (0-8 kHz), 40-row waterfall |
| Alerts | Thresholds (noise dB(A) / temp °C / light lx) checked every 120 ms → high-priority notification (once per 5 s per metric), configurable and toggleable |

## 5.18 Sensor calibration wizard

| Step | Procedure | Output |
|---|---|---|
| 1. Magnetometer | Figure-8 motion covering all orientations (~20 s) | Hard-iron offset x/y/z (µT), magnitude range |
| 2. Accelerometer | Rest flat on a table (~8 s) | Measured gravity vs 9.80665 m/s², bias |
| 3. Gyroscope | Keep perfectly still (~8 s) | Per-axis bias (rad/s), combined stddev |
| Final | Shareable text report | All parameters above |

### 5.19 Protocol deep probing (SECURITY)

| Probe | Notes |
|---|---|
| ⚠️ `net_tls_cipher` | TLS handshake on 443/8443/8888 → **negotiated cipher suite**, protocol version, ALPN (h2/http1.1) |
| ⚠️ `net_ssh_ver` | SSH banner on 22 → vendor + version (e.g. OpenSSH_9.2p1) |
| ⚠️ `net_smb` | Hand-written **SMB2 NEGOTIATE** (NetBIOS framing) → negotiated dialect (2.0.2–3.1.1), signing mode (none/enabled/required) |

### 5.20 Security audit engine

| Item | Description |
|---|---|
| Input | Aggregates measurements from all security probes in a report |
| Findings | Leveled INFO → CRITICAL: open risky ports (db/RDP/SMB/Docker…), TRACE/PUT allowed, missing security headers, TLSv1.0/1.1, expired/self-signed/weak-signature certificates, anonymous MQTT, SMB no-signing, open WiFi networks |
| Output | In-app audit card on the report page (counts + details) + shareable Markdown audit report |
| Principle | Objective facts only — no subjective scoring |

### 5.21 Packet sender (tool)

| Item | Description |
|---|---|
| Target | Host + port, TCP or UDP |
| Payload | Hex string (optional) |
| Response | HEX + ASCII echo, truncated at 512 B |
| Notice | ⚠️ active network activity — authorized targets only |

### 5.22 Hardcore network detection (SECURITY / AUDIO)

| Probe | Notes |
|---|---|
| ⚠️ `net_dns_hijack` | Self-written DNS client queries 8.8.8.8 / 1.1.1.1 / 223.5.5.5 for 5 domains → cross-resolver consistency verdict (possible hijack / split-DNS) |
| ⚠️ `net_arp_spoof` | Gateway MAC sampled at 2 Hz across the session → change detection (network switch or ARP spoofing) |
| ⚠️ `net_mdns` | Multicast PTR query `_services._dns-sd._udp.local` → service instance enumeration (compression-pointer aware) |
| ⚠️ `net_upnp_detail` | SSDP discovery then fetches each device's description XML → deviceType / friendlyName / model / manufacturer / serial / services |
| ⚠️ `audio_link_test` | Speaker plays 1 kHz tone bursts, mic captures loopback → speaker→mic latency, peak level, detection count |

### 5.23 Tools

| Tool | Notes |
|---|---|
| HTTP request | curl-style: method / headers / body / redirects → status line, full headers, body (8 KB cap), final URL |
| Port-range scanner | custom start/end ports, 40-thread concurrency, timeout — service names via well-known table |
| Packet sender | (see §5.21) |

## 6. Analysis layer (AnalysisEngine)

Measurement-derived summaries only — **no subjective scoring**:

| Module | Content |
|---|---|
| Acoustics | LAeq / Lpeak / L10 / L50 / L90 |
| Vibration | Dominant frequency (acceleration magnitude spectrum), RMS acceleration, crest factor, ISO 2631 approximate level |
| Positioning | Horizontal accuracy, satellites used in fix, HDOP |
| Context classification | stationary/motion/vehicle classification + confidence + features (speed_ms / accel_rms / activity_sensor) |

## 7. Report protocol (schema v1)

- **JSON**: `MeasurementReport` — measurement plan (planId/duration/mode/probe list), instrument & context (device/OS/kernel/timezone/battery), per-probe measurements (statistics/attributes/quality/spectrum), analysis summary. Stored at `reports/<id>/report.json`
- **Raw samples**: `reports/<id>/samples/<probeId>/channel_<ch>.csv` (`t_ms,value`, comma-separated, 6-digit float precision)
- **Export**: JSON / Markdown (full statistics tables + quality report) / **ZIP (report + all raw sample CSVs)**
- **Data quality**: every probe carries EXCELLENT/GOOD/DEGRADED/FAILED level with reason code

## 7.5 Compliance-marked probes

> **Compliance**: Use this software in compliance with all applicable local laws and regulations; you are responsible for how you use it.

Probes marked ⚠️ collect data that is regulated or considered personal data in some jurisdictions (e.g., EU GDPR, national location/GNSS regulations):

| Probe | Risk |
|---|---|
| ⚠️ `wifi_scan` | Third-party SSID/BSSID collection may be regulated as personal data |
| ⚠️ `wifi_dynamic` | Connection info contains network identifiers |
| ⚠️ `wifi_rtt` | Physical ranging of nearby APs may fall under location-data regulations |
| ⚠️ `wifi_direct` / `wifi_aware` | Peer/device discovery involves third-party device info |
| ⚠️ `bluetooth` / `bt_classic` | Third-party device MAC/name collection may be personal data |
| ⚠️ `bt_paired` | Paired-device list is personal data |
| ⚠️ `cellular` / `cellular_series` | Cell-tower identity collection is regulated in some jurisdictions |
| ⚠️ `location` / `gnss` / `nmea` / `gnss_raw` | High-precision positioning/GNSS data is regulated in some jurisdictions |
| ⚠️ `gnss_hw` | GNSS hardware info may reveal device positioning capability |
| ⚠️ `noise` | Microphone acquisition — mind local recording & privacy laws |
| ⚠️ `nfc` | Tag reading may involve third-party device info |
| ⚠️ `sensor.heart_rate` / `sensor.heart_beat` | Heart rate is health data under strict data-protection rules |
| ⚠️ `kernel` | Device serial is a personal identifier |
| ⚠️ `net_arp` / `net_portscan` | Active LAN probing / port scanning is regulated by network-security laws in some countries |
| ⚠️ `net_http_fingerprint` / `net_ssdp` | Fingerprinting & multicast discovery touch third-party service/device info |
| ⚠️ `net_banner` / `net_http_methods` / `net_http_security` / `net_tls_versions` / `net_http_paths` | Active security testing of third-party services — regulated in some countries |
| ⚠️ `net_subnet_scan` | Full-subnet scanning is high-intensity probing, regulated in some countries |
| ⚠️ `net_mqtt` / `net_tcp_concurrency` / `net_ntp` | Active probing of third-party services |

These probes are flagged in-app (preflight & report) and in exported reports. Denying a permission or deselecting a probe in preflight excludes it from the session.

## 8. Known limitations (honestly reported)

- Sound pressure level is an **uncalibrated reference value** (microphone sensitivity unknown); magnetic field strength is likewise reference-level
- `thermal` / CPU frequency sysfs files are unreadable on most devices → DEGRADED with note
- WiFi scans are subject to system throttling (≈2 scans/2 min) → `SYSTEM_THROTTLED`
- GNSS/NMEA require GPS enabled and outdoor conditions
- Biosensors (heart rate) exist on few devices (preflight reports honestly)
