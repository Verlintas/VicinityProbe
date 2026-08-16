# Contributing to VicinityProbe

Thanks for contributing! This document describes how to build, test, and contribute code.

## Code of Conduct

Be respectful. This project is a security tool — keep discussions focused on technology and lawful, authorized use.

## Reporting bugs

- Check [existing issues](https://github.com/Verlintas/VicinityProbe/issues) first.
- Include: device model, Android version, build number (from the release page or `versionName`), steps to reproduce, and the affected report/JSON if any.
- For security issues, follow [SECURITY.md](SECURITY.md) — do not open public issues.

## Building

Requirements: JDK 17+, Android SDK 36 (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK → app/build/outputs/apk/release/VicinityProbe-<version>.apk
./gradlew testDebugUnitTest    # unit tests (statistics / FFT / analysis / report)
./gradlew lint                 # static analysis
```

## Adding a new probe

1. **Register in `ProbeCatalog`** (`model/domain/ProbeSpec.kt`) — this is the single source of truth: id, name (bilingual `bil("中文","English")`), category, measurand, unit, nominal rate, channels.
2. **Implement a `Sampler`** (or extend an existing one) in `probe/`.
3. **Register in `SessionController.buildUnits()`** and **`CapabilityProbe.enumerate()`**.
4. Add a compliance `riskNote` if the probe touches third-party/personal data.
5. Add a description to `ui/ProbeInfo.kt`.
6. Update `docs/PROBES.md` (EN) and `docs/PROBES.zh-CN.md` (ZH) and the probe count.
7. Add/adjust unit tests if the probe has pure logic.

## Conventions

- **All user-facing text is bilingual**: store as `bil("中文", "English")`; never hardcode single-language strings in reports.
- Numeric channels must go through `ChannelRecorder` (raw CSV archive); non-numeric info goes into `attributes`.
- Every measurement must carry a `QualityReport` (level + reason code); never silently swallow failures.
- The analysis engine outputs measurement-derived facts only — no subjective scores.
- SDK 36 removed several legacy APIs — `GnssStatus.timeToFirstFix`, `BatteryManager` voltage/temperature properties and the VPN foreground-service type are gone; use broadcast extras (`"voltage"`/`"temperature"`) and runtime checks instead.
- GitHub-facing content (README, docs, **release notes**) must be in English.

## Pull requests

- One logical change per PR.
- Run `./gradlew testDebugUnitTest lint` before submitting.
- Keep commits focused; follow the existing style (4-space indent, KDoc on public API).
- Update docs and tests along with code.

## Releases

Versioned `vX.Y.Z` with `VicinityProbe-<version>.apk` attached; release notes in English.
