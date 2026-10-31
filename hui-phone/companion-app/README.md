# Companion App - Android Client for Burner-Phone

Lightweight Android app that replaces ADB polling with push-based events.

## Features

- **On-device gaze detection** via TensorFlow Lite (~2MB model)
- **Push events** over WebSocket (attention, battery, notifications, screen state)
- **Receives commands** from server (TTS, screen actions, app launch, media)
- **Zero-config discovery** via mDNS (`_companion._tcp`)
- **Foreground service** survives Doze/standby, auto-starts on boot
- **Minimal permissions**: Camera, Mic, Notification Listener, Foreground Service
- **~180KB APK** (release, minified)

## Requirements

- Android 8.1+ (API 27)
- ARM 32-bit (armeabi-v7a) or ARM64
- Camera + Microphone hardware

## Build

```bash
cd /home/papa/burner-phone/companion-app
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Setup on Phone

1. Open **Companion** app
2. Tap **Connect & Start**
3. Grant all permissions:
   - Camera (gaze detection)
   - Microphone (speech detection)
   - Notification Access (Settings → Apps → Special access)
   - Ignore Battery Optimization (prompted automatically)
4. App discovers server via mDNS or uses configured URL
5. Persistent notification shows connection status

## Events Sent to Server

| Event | Trigger | Payload |
|-------|---------|---------|
| `attention` | Gaze+speech detected (on-device TF Lite) | `{addressing, looking, speaking, confidence, gaze_vector, audio_level_db}` |
| `battery` | Periodic (60s) + on change | `{level, charging, health, temperature_c}` |
| `notification` | New notification (allowlisted apps) | `{package, title, text, priority, category, actions}` |
| `screen` | State change (OFF/ON_LOCKED/UNLOCKED) | `{state, brightness}` |
| `audio` | Continuous 100ms chunks (for STT) | `{format, sample_rate, channels, duration_ms, data_b64, sequence}` |

## Commands Received from Server

| Command | Payload | Action |
|---------|---------|--------|
| `speak` | `{text, voice, priority}` | TTS playback |
| `screen_action` | `{action: wake|sleep|unlock}` | Screen control |
| `launch_app` | `{package, intent_extras}` | Launch app |
| `media` | `{action: play_pause|next|prev|stop}` | Media control |
| `config` | `{gaze_check_interval_ms, audio_chunk_ms, battery_report_interval_s}` | Update intervals |

## Server Integration

Add to `phone_daemon.py`:

```python
from aiohttp import web

async def ws_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    async for msg in ws:
        if msg.type == WSMsgType.TEXT:
            event = json.loads(msg.data)
            await handle_event(event)  # Route to AttentionDetector, etc.
    
    return ws

async def http_event_handler(request):
    event = await request.json()
    await handle_event(event)
    return web.json_response({"status": "ok"})

app = web.Application()
app.router.add_get('/ws', ws_handler)
app.router.add_post('/events/{type}', http_event_handler)

# Advertise via mDNS
import zeroconf
zeroconf.Zeroconf().register_service(zeroconf.ServiceInfo(
    "_companion._tcp.local.", "companion._companion._tcp.local.",
    port=8081, addresses=[socket.inet_aton(local_ip)]
))

web.run_app(app, host='0.0.0.0', port=8081)
```

## Migration from ADB Polling

| Phase | Change |
|-------|--------|
| 1 | Add WS endpoints to daemon (backward compatible) |
| 2 | Install companion app on phone |
| 3 | Daemon auto-detects app, prefers WS over ADB |
| 4 | Move gaze inference to app (TF Lite) |
| 5 | Full event parity + lower latency |
| 6 | Deprecate ADB backend |

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        SERVER (daemon)                          │
│  WebSocket Server ──► Event Queue ──► AttentionDetector        │
│       ▲                                                  │      │
│       │ WebSocket / HTTPS                                    │      │
│       ▼                                                  ▼      │
└────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │ TLS
┌────────────────────────────────────────────────────────────────┐
│                     ANDROID APP (this)                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ CameraX     │──►│ TF Lite     │──►│ EventBus    │──► WS     │
│  │ AudioRecord │   │ Model       │   │ (priority)  │           │
│  └─────────────┘  └─────────────┘  └──────┬──────┘           │
│         ▲                    ▲             │                  │
│         │                    │             │ Commands         │
│  ┌──────┴──────┐    ┌───────┴───────┐     ▼                  │
│  │BatteryManager│  │Notification   │  ┌─────────┐            │
│  │ScreenObserver│  │Listener       │  │Executor │            │
│  └─────────────┘  └───────────────┘  └────┬────┘            │
│                                            │                 │
│                                     ┌──────┴──────┐          │
│                                     │ TTS, Screen,│          │
│                                     │ App, Media  │          │
│                                     └─────────────┘          │
└────────────────────────────────────────────────────────────────┘
```

## Model Training (for gaze detection)

The TF Lite model (`gaze_detector.tflite`) needs to be trained separately:

1. Collect dataset: front-camera frames + audio + labels (addressing/looking/speaking)
2. Train a small CNN+RNN model (MobileNetV3 + GRU, ~2MB quantized)
3. Convert to TF Lite with quantization
4. Place in `app/models/gaze_detector.tflite`

Placeholder model included for build verification.

## Permissions Explained

| Permission | Purpose |
|------------|---------|
| `CAMERA` | Front-camera frames for gaze detection |
| `RECORD_AUDIO` | Audio chunks for speech detection + STT |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read notifications for proactive alerts |
| `FOREGROUND_SERVICE` | Keep service alive, show persistent notification |
| `FOREGROUND_SERVICE_CAMERA` | Required for camera access in foreground service (API 31+) |
| `FOREGROUND_SERVICE_MICROPHONE` | Required for mic access in foreground service (API 31+) |
| `WAKE_LOCK` | Keep CPU awake for detection loop |
| `RECEIVE_BOOT_COMPLETED` | Auto-start service on boot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent Doze from killing service |

## Troubleshooting

**"WebSocket connection failed"**
- Check server is running on port 8081
- Verify mDNS: `adb shell dumpsys connectivity` for `_companion._tcp`
- Check firewall on server

**"Notification listener not working"**
- Settings → Apps → Special access → Notification access → Enable Companion

**"Camera/Mic permission denied"**
- Settings → Apps → Companion → Permissions → Allow all

**"Service killed by system"**
- Enable "Ignore battery optimization" for Companion
- Lock app in recent apps (some OEMs)

## License

MIT - Part of burner-phone project