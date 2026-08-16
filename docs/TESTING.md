# Device Acceptance Checklist (v1.1)

Manual test plan for the 1.0/1.1 changes. Run on a physical device with
network access (Wi-Fi + cellular) and a stable position (e.g., a desk).

## 1. Scan reliability (regression)

- [ ] Full scan (10 s) completes without UI freeze or ANR — the scan page
      keeps updating while samples are collected
- [ ] A 60 s full scan completes; report appears in History
- [ ] Cancel mid-scan returns to home without crash
- [ ] Sensor probes (accel/gyro/mag) show `EXCELLENT` with coverage ≥ 80%
- [ ] Noise probe reports LAeq with spectrum (dominant frequency + THD visible
      in the vibration/acoustics section of the report)

## 2. New probes (1.0)

- [ ] **ARP neighbor table** (`net_arp_table`): report shows neighbors
      (IP/MAC/device), gateway MAC + vendor resolved
- [ ] **DNS-over-HTTPS** (`net_doh`): EXCELLENT with latency + IPs for both
      Cloudflare and Google endpoints
- [ ] **QUIC connectivity** (`net_quic`): EXCELLENT with version + response
      bytes for cloudflare.com (UDP 443 reachable)
- [ ] DNS hijack probe: consistent across resolvers on a normal network

## 3. Capture engine (1.0)

- [ ] Start capture, browse a few HTTPS sites, stop
- [ ] `TLS fingerprints` section lists at least one JA3 fingerprint
- [ ] `Protocols` section counts DNS/HTTPS/QUIC traffic
- [ ] pcap export opens in Wireshark with valid IP packets
- [ ] Start capture → kill the app → restart: no stuck "capturing" state,
      notification gone

## 4. Real-time monitor (1.0/1.1)

- [ ] Accel/gyro/mag waveforms render without jank
- [ ] Spectrum waterfall updates ~10 fps with no CPU busy-spin (check battery
      heat on the phone)
- [ ] **Smooth toggle**: waveform visibly smoother with Kalman ON; toggle
      while running does not crash or glitch
- [ ] Rapidly switching modes (accel → noise → spectrum) 10× does not crash
      and data does not cross-contaminate
- [ ] Threshold alerts (noise/temp/light) fire at most once per 5 s

## 5. Web console (LAN)

- [ ] Start web console, open `http://<phone-ip>:8080` from a laptop
- [ ] Remote scan triggers and a new report appears within ~20 s
- [ ] `download/samples/<id>/<file>` downloads CSV without path traversal
      (try `../../captures/…` → 404)
- [ ] `/api/capture` returns valid JSON including `protocols` and `ja3`
- [ ] Downloading a large pcap streams (no phone OOM)

## 6. Reports & export

- [ ] Report contains new spectrum fields (topPeaks / THD / harmonics) when
      audio probe included
- [ ] JSON/MD/ZIP export share successfully (buttons disable while exporting)
- [ ] Old reports (created before v1.0) still open — JSON backward compatible

## 7. Theme & interaction (1.1)

- [ ] Theme icon on home toggles system → light → dark; applies instantly
- [ ] Brand palette is used by default (consistent look); Material You dynamic
      color only when explicitly enabled (prefs flag)
- [ ] Charts: tap a line chart → crosshair + value readout appears
- [ ] Start/record buttons give haptic feedback
- [ ] Screen stays awake during scan / real-time monitor / capture

## 8. NFC security testing

- [ ] **NFC analyzer**: hold a Mifare card to the back → UID/ATQA/SAK/type
      recognized (hex values correct, no FFFFFFFF garbage)
- [ ] Default-key audit: a factory card reports unlocked sectors + HIGH risk;
      a card with changed keys reports modified keys
- [ ] **Full dump** of unlocked sectors exports as hex text
- [ ] **NDEF writer**: write text to an owned NTAG, verify with another reader
- [ ] **HCE emulator**: a second phone in reader mode SELECTs
      F0010203040506 and reads "VICINITY-PROBE-X"
- [ ] HCE: 1-byte APDU to the emulator does not crash the app

## 9. New tools (1.1+)

- [ ] **Sound level recorder**: 5 min run yields per-minute bins + stats
- [ ] **Sensor RAW recorder**: accel stream ~200 Hz with live waveform,
      CSV opens correctly with channel headers
- [ ] **Ping monitor**: latency/jitter/loss series renders; timeouts shown red
- [ ] **Speed test**: download + upload complete with Mbps ratings
- [ ] **Network matrix**: 5 nodes monitored, radar view updates every 2 s
- [ ] **GPS track**: record 100 m walk → distance ≈ walked, KML opens in
      Google Earth
- [ ] **Battery logger**: 10 min run shows voltage/temp curves + %/h rate
- [ ] **WiFi map**: 3+ samples produce a heatmap (live scan refresh works)
- [ ] **GNSS view**: outdoors shows satellites in sky plot, used-in-fix rings
- [ ] **BT analysis**: 10 s scan lists devices with RSSI ranges + vendors

## 10. Battery drain

- [ ] 10 s scan consumes no noticeable battery (< 0.5%)
- [ ] 1 h of periodic monitoring (10 min interval) drains < 5%
- [ ] Battery logger left running does not burn CPU (no busy loop)
