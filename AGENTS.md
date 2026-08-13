# AGENTS.md

## Build & verification commands

- Release APK: `./gradlew assembleRelease` (output `app/build/outputs/apk/release/VicinityProbe-<version>.apk`)
- Debug APK: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lint`
- Local Android SDK: `/opt/homebrew/share/android-commandlinetools` (see `local.properties`)

## Project structure

- `app/src/main/java/com/vicinityprobe/`
  - `model/domain/` — domain model: measurement catalog (ProbeCatalog/ProbeSpec/Measurand/UnitDef/Category), statistics (ChannelStats), quality gate (QualityReport/QualityLevel), measurement report (MeasurementReport/Measurement/Plan/Context/Analysis)
  - `probe/` — samplers (SensorBatchSampler / LocationSampler / GnssSampler / NmeaSampler / WifiSampler / WifiScanSampler / CellularSampler / ConnectivitySampler / BluetoothSampler / PairedDevicesSampler / AudioSampler / AudioStateSampler / BatterySampler / DeviceSampler / SystemSampler), capability preflight (CapabilityProbe), session orchestration (SessionController), channel recorder (ChannelRecorder, CSV archive)
  - `analysis/` — FFT (Spectral.kt), spectral/acoustic analysis, analysis engine (AnalysisEngine: acoustics/vibration/positioning/context classification)
  - `report/` — report protocol (JsonReport), Markdown generation, history management, compare engine, ZIP export, PNG rendering
  - `service/` — continuous-monitoring foreground service
  - `ui/` — 7 screens + chart components (line chart / quality badge)

## Conventions

- All human-readable text is stored as `bil("中文","English")`; UI resolves it via `trBilingual(s, lang)`
- **ProbeCatalog is the single source of truth**: new probes MUST be registered in `ProbeCatalog` first, then implemented as a Sampler and registered in `SessionController.buildUnits()` and `CapabilityProbe`
- Probe id naming: `sensor.*` / `location` / `gnss` / `nmea` / `wifi` / `wifi_scan` / `cellular` / `connectivity` / `bluetooth` / `bt_paired` / `noise` / `audio_state` / `battery` / `device` / `system`
- Numeric channels MUST go through ChannelRecorder and be persisted to CSV; non-numeric info goes to `attributes`
- Every measurement MUST carry a QualityReport (level + reason code); never swallow failures silently
- SDK 36 removed several deprecated sensor constants — match at runtime via `Sensor.getStringType()` (see `SensorBatchSampler.bindings`)
- The analysis engine only outputs measurement-derived indicators, never subjective scores
