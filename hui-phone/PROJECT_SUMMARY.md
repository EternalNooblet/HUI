# Burner Phone Companion App - Project Summary

## Overview
Secure companion app for Android that connects to a WSS server on laptop. Phone acts as sensor client; all heavy compute runs on server.

## Architecture
```
[Phone: LG K30, Android 8.1, ARM32] ──WSS/TLS──▶ [Laptop: wss://10.0.0.126:8081/ws]
     │                                                 │
     ├─ Foreground Service (persistent)               ├─ Event Queue
     ├─ BatteryMonitor (real)                         ├─ Command Dispatch
     ├─ ScreenMonitor (real)                          ├─ Device Registry
     ├─ AttentionDetector (stub)                      └─ TLS/SSL Termination
     └─ NotificationListener (configured)
```

## Network
- Phone WiFi IP: `10.0.0.167`
- Laptop IP: `10.0.0.126` (same router, no isolation)
- WSS Port: `8081`
- ADB TCP: `5555` (enabled via `adb tcpip 5555` over USB)

## Key Components

### 1. WSS Server (`companion_wss_server.py`)
```python
# Running on: wss://0.0.0.0:8081/ws
# TLS: Self-signed cert (server.crt/server.key)
# Endpoints:
#   - GET  /ws          - WebSocket upgrade
#   - POST /events      - HTTP event fallback
#   - POST /events/{type} - Typed event HTTP
```

**Protocol:**
- Client sends: `{"type":"hello","device_id":"...","capabilities":[...]}`
- Server responds: `{"type":"welcome","device_id":"...","config":{...}}`
- Commands: `{"type":"command","command":"speak","request_id":"...","payload":{...}}`
- Events: `{"type":"battery","device_id":"...","payload":{...},"timestamp":"..."}`

### 2. Android App (`companion-app/`)
**Package:** `com.southpawin.companion`
**Min SDK:** 27 (Android 8.1)
**Target SDK:** 34

**Services:**
- `CompanionService` - Foreground, START_STICKY, camera|microphone
- `NotificationListener` - Requires user grant in Settings

**Detectors:**
| Detector | Status | Implementation |
|----------|--------|----------------|
| BatteryMonitor | ✅ Real | BroadcastReceiver + 60s poll |
| ScreenMonitor | ✅ Real | PowerManager + KeyguardManager + 5s poll |
| AttentionDetector | ⚠️ Stub | 2s tick log only |
| NotificationListener | ✅ Configured | Needs user grant |

**Transport (`Transport.kt`):**
- OkHttp WebSocket with custom SSLContext
- Base64 embedded self-signed cert (pinning attempted)
- Fallback: permissive X509TrustManager (works on Android 8.1+)
- Auto-reconnect with exponential backoff (1s → 30s max)
- Ping interval: 15s

**Commands Supported:**
- `speak` - TTS playback
- `screen_action` - wake/sleep (wakeLock)
- `launch_app` - by package name
- `config` - update intervals/thresholds

### 3. Build & Deploy
```bash
# Build
cd /home/papa/burner-phone/companion-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk
gradle assembleRelease

# Sign
apksigner sign --ks /tmp/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

# Install (USB device serial: LMX210APM62b4cc0a)
adb -s LMX210APM62b4cc0a install -r app-release-signed.apk

# Permissions
adb -s LMX210APM62b4cc0a shell pm grant com.southpawin.companion android.permission.CAMERA
adb -s LMX210APM62b4cc0a shell pm grant com.southpawin.companion android.permission.RECORD_AUDIO

# Start service
adb -s LMX210APM62b4cc0a shell am start-foreground-service \
  -n com.southpawin.companion/.service.CompanionService
```

### 4. Certificates
```bash
# Generate self-signed cert
openssl req -x509 -newkey rsa:2048 -nodes -keyout server.key \
  -out server.crt -days 365 -subj "/CN=10.0.0.126"

# SHA-256: 1D:F5:0C:5F:34:7F:1B:33:19:B2:3F:9F:A9:B9:EF:47:8A:11:AD:70:C8:6D:51:38:5A:C3:D7:8D:1C:F8:04:0B
```

## Device ID
Generated from Android ID: `android-<ANDROID_ID>` or random UUID fallback.
Stored encrypted in EncryptedSharedPreferences.

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

## Event Types
| Type | Payload Fields |
|------|----------------|
| attention | addressing, looking, speaking, confidence, gaze_vector?, audio_level_db? |
| battery | level (0-1), charging, health, temperature_c |
| notification | package, title, text, priority, category, actions[] |
| screen | state (OFF/ON_LOCKED/UNLOCKED), brightness (0-1) |
| audio | format=pcm16, sample_rate=16000, channels=1, duration_ms, data_b64, sequence |

## Command Types
| Command | Payload Fields |
|---------|----------------|
| speak | text, voice?, priority? |
| screen_action | action (wake/sleep) |
| launch_app | package |
| config | gaze_check_interval_ms, audio_chunk_ms, battery_report_interval_s |

## Troubleshooting
- **Cert pinning fails**: Permissive trust manager used (check Transport.kt line 147)
- **WSS not connecting**: Verify server running (`ss -tlnp | grep 8081`)
- **Service not starting**: Check `adb logcat -s CompanionService:V Transport:V`
- **Events not received**: Verify `eventBus.tryPost()` in detectors, `transport.send()` in service

## Future Work
1. Implement real AttentionDetector (CameraX + ML Kit Face Detection)
2. Implement AudioEvent (AudioRecord 16kHz mono + base64)
3. Add proper certificate pinning (fix Base64 decode)
4. Add notification listener grant flow in MainActivity
5. Implement media control commands (play/pause/next/prev)

## Files Reference
```
/home/papa/burner-phone/
├── companion_wss_server.py              # Server (135 lines)
├── server.crt / server.key              # TLS cert/key
├── companion-app/                       # Android Gradle project
│   ├── app/src/main/java/com/southpawin/companion/
│   │   ├── CompanionApplication.kt      # App init + Timber
│   │   ├── MainActivity.kt              # UI + notification listener grant
│   │   ├── service/
│   │   │   ├── CompanionService.kt      # Foreground service orchestration
│   │   │   └── NotificationListener.kt  # Notification monitoring
│   │   ├── transport/
│   │   │   ├── Transport.kt             # WSS client + TLS
│   │   │   └── CommandExecutor.kt       # Command execution
│   │   ├── detector/
│   │   │   ├── AttentionDetector.kt     # Gaze detection (stub)
│   │   │   ├── BatteryMonitor.kt        # Battery state
│   │   │   └── ScreenMonitor.kt         # Screen on/off/locked
│   │   ├── event/
│   │   │   ├── EventModels.kt           # Event/Command sealed classes + Gson parsing
│   │   │   └── EventBus.kt              # Coroutine channels
│   │   └── util/
│   │       ├── Config.kt                # Device ID, URLs, intervals
│   │       └── CompanionAppContext.kt   # App context singleton
│   ├── app/src/main/res/                # Layouts, drawables, strings
│   └── app/build.gradle.kts             # Dependencies + SERVER_URL
├── SERVER_INTEGRATION_GUIDE.md          # Full integration docs
└── PROJECT_SUMMARY.md                   # This file
```

## Verification Commands
```bash
# Server running?
ss -tlnp | grep 8081

# Phone connected?
adb devices

# Service running?
adb -s LMX210APM62b4cc0a shell ps | grep companion

# WSS logs
adb -s LMX210APM62b4cc0a logcat -s "Transport:V" "CompanionService:V"

# Test WSS from laptop
python3 -c "
import asyncio, websockets, ssl, json
async def t():
    ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
    async with websockets.connect('wss://10.0.0.126:8081/ws', ssl=ctx, additional_headers={'X-Device-ID':'test'}) as ws:
        await ws.send(json.dumps({'type':'hello','device_id':'test','capabilities':[]}))
        print(await ws.recv())
asyncio.run(t())
"
```