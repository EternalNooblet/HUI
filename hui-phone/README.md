# <p align='center'>HUI Phone — Hermes Unofficial Interface App</p>

<p align="center">
<img src="face%20only%20-%20canvas.png" width="420" alt="HUI app icon — face canvas">
</p>

<p align='center'>Secure Android companion app that connects to a WSS server on a laptop. The phone acts as a sensor client; all heavy compute runs on the server.</p>

## Architecture

```
[Phone: Android 8.1+] ──WSS/TLS──▶ [Laptop: wss://host:8081/ws]
     │                                    │
     ├─ Foreground Service (persistent)   ├─ Event Queue
     ├─ BatteryMonitor                    ├─ Command Dispatch
     ├─ ScreenMonitor                     ├─ Device Registry
     ├─ AttentionDetector (stub)          └─ TLS/SSL Termination
     └─ NotificationListener (configured)
```

## Network

- Phone WiFi IP: `YOUR_PHONE_IP` (example)
- Laptop IP: `YOUR_LAN_HOST` (same router, no isolation)
- WSS Port: `8081`
- ADB TCP: `5555` (enabled via `adb tcpip 5555` over USB)

## Laptop Server

The server half of the pipeline lives in [`server/`](server/):

| File | Purpose |
|------|---------|
| `companion_wss_server.py` | WSS server (:8081): events, commands, voice assembly → STT → gateway bridge → speak dispatch |
| `companion_stt.py` | Long-lived NDJSON transcription helper (committed-venv python, faster-whisper base) |
| `companion_mock_gateway.py` | Verified-contract gateway fixture (no model) for testing |
| `companion_voice_matrix.py` | Full voice-in matrix runner — exit 0 iff all claims pass |
| `server.crt` / `server.key` | TLS cert+key (loaded from the server's own directory; gitignored) |

```bash
cd hui-phone/server
python3 companion_wss_server.py
# Run tests: python3 companion_voice_matrix.py  (needs port 8081 free)
```

## Build & Deploy

```bash
cd hui-phone/companion-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk
./gradlew assembleRelease

# Sign
apksigner sign --ks /tmp/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

# Install (USB device)
adb install -r app-release-signed.apk

# Permissions
adb shell pm grant com.southpawin.companion android.permission.CAMERA
adb shell pm grant com.southpawin.companion android.permission.RECORD_AUDIO

# Start service
adb shell am start-foreground-service \
  -n com.southpawin.companion/.service.CompanionService
```

## Key Components

| File | Purpose |
|------|---------|
| `CompanionService.kt` | Foreground service orchestration (START_STICKY) |
| `Transport.kt` | WSS client with TLS (OkHttp, self-signed cert pinning) |
| `CommandExecutor.kt` | Executes commands: speak (honors `voice` locale code, e.g. "fr-FR"), screen_action, launch_app, config (persists `voice` via `voice` field) |
| `BatteryMonitor.kt` | Battery level, charging state (BroadcastReceiver + 60s poll) |
| `ScreenMonitor.kt` | Screen ON/OFF/LOCKED (PowerManager + KeyguardManager) |
| `AttentionDetector.kt` | Gaze detection stub (2s tick log only) |
| `NotificationListener.kt` | Notification monitoring (requires user grant) |

## Protocol (WSS)

- Client hello: `{"type":"hello","device_id":"...","capabilities":[...]}`
- Server welcome: `{"type":"welcome","device_id":"...","config":{...}}`
- Commands: `{"type":"command","command":"speak","payload":{"text":"..."}}`
- Events: battery, notification, screen, attention, audio

## Certificates

Self-signed TLS cert in `app/src/main/res/raw/server_cert.crt` (embedded in app).  
Server uses `server.crt` / `server.key` in `server/` (generate with OpenSSL; SAN must cover the laptop IP).

## Configuration (from server welcome)

```json
{
  "gaze_check_interval_ms": 2000,
  "audio_chunk_ms": 100,
  "battery_report_interval_s": 60,
  "notification_filter_packages": [
    "com.whatsapp", "com.google.android.apps.messaging",
    "com.android.mms", "com.google.android.gm",
    "com.microsoft.office.outlook", "org.telegram.messenger"
  ]
}
```

## Troubleshooting

- **Cert pinning fails**: Permissive trust manager used (Transport.kt line 147)
- **WSS not connecting**: Verify server running (`ss -tlnp | grep 8081`)
- **Service not starting**: Check `adb logcat -s CompanionService:V Transport:V`
- **Events not received**: Verify `eventBus.tryPost()` in detectors, `transport.send()` in service

## License

GPL-3.0 — see the repo-root `LICENSE` (covers all code in this monorepo, including hui-phone)
