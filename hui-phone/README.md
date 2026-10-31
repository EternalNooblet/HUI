> **Part of the [HUI project](../README.md)** — this is `hui-phone`, the Android companion app.

# HUI Phone — Android Companion App

Secure Android companion app that connects to a WSS server on a laptop. The phone acts as a sensor client; all heavy compute runs on the server.

<img src="face%20only%20-%20canvas.png" width="160" alt="HUI app icon — face canvas" align="right">

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
| `CommandExecutor.kt` | Executes commands: speak, screen_action, launch_app, config |
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
Server uses `server.crt` / `server.key` (generate with OpenSSL).

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

MIT