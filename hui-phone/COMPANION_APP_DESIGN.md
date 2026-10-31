# Lightweight Android Companion App Design
**Goal:** Replace ADB polling with push-based events, keep server backend unchanged.

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│                         SERVER (unchanged)                          │
├────────────────────────────────────────────────────────────────────┤
│  phone_daemon.py                                                    │
│    │                                                                │
│    ├── HTTP/WebSocket Server  ◄── NEW: receives push events       │
│    │      │                                                         │
│    │      ├── /events/attention     (gaze+speech detected)         │
│    │      ├── /events/battery       (level, charging)              │
│    │      ├── /events/notification  (new notification)             │
│    │      ├── /events/screen        (on/off/unlock)                │
│    │      └── /events/audio         (raw audio chunks for STT)     │
│    │                                                                │
│    ├── PhoneAgent (ADB fallback)  ◄── still works for control      │
│    └── AttentionDetector          ◄── now receives push, not polls │
└────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │ HTTPS / WSS (TLS)
                                  │ JSON over WebSocket (primary)
                                  │ HTTP POST (fallback)
                                  ▼
┌────────────────────────────────────────────────────────────────────┐
│                     ANDROID APP (new, ~200KB)                       │
├────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │
│  │  Event Sources  │  │  Core Service   │  │  Transport      │    │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────┤    │
│  │ • CameraX       │──►│  EventBus       │──►│  WebSocket      │    │
│  │ • AudioRecord   │   │  (priority q)   │   │  (auto-reconnect)    │
│  │ • BatteryManager│   │  Batching       │   │  HTTP fallback  │    │
│  │ • Notification  │   │  Deduplication  │   │  mDNS discovery │    │
│  │   Listener      │   │  Backpressure   │   │  Token auth     │    │
│  │ • Screen        │   │                 │   │                 │    │
│  │   Observer      │   │                 │   │                 │    │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘    │
│         ▲                    ▲                    ▲                 │
│         │                    │                    │                 │
│         └────────────────────┴────────────────────┘                 │
│                          │                                          │
│                    ┌─────┴─────┐                                    │
│                    │ Commands  │                                    │
│                    │ Receiver  │                                    │
│                    │ (TTS,     │                                    │
│                    │  actions) │                                    │
│                    └───────────┘                                    │
└────────────────────────────────────────────────────────────────────┘
```

---

## Event Types (JSON)

```json
// Attention event (primary wake signal)
{
  "type": "attention",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "lg-k30-001",
  "payload": {
    "addressing": true,
    "looking": true,
    "speaking": true,
    "confidence": 0.94,
    "gaze_vector": [0.02, -0.01, 0.99],
    "audio_level_db": -12.3
  }
}

// Battery event
{
  "type": "battery",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "lg-k30-001",
  "payload": {
    "level": 0.73,
    "charging": false,
    "health": "good",
    "temperature_c": 31.5
  }
}

// Notification event
{
  "type": "notification",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "lg-k30-001",
  "payload": {
    "package": "com.whatsapp",
    "title": "John Doe",
    "text": "Hey, are you coming?",
    "priority": "HIGH",
    "category": "msg",
    "actions": ["reply", "mark_read"]
  }
}

// Screen state event
{
  "type": "screen",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "lg-k30-001",
  "payload": {
    "state": "UNLOCKED",  // OFF | ON_LOCKED | UNLOCKED
    "brightness": 0.8
  }
}

// Audio chunk (for server-side STT)
{
  "type": "audio",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "lg-k30-001",
  "payload": {
    "format": "pcm16",
    "sample_rate": 16000,
    "channels": 1,
    "duration_ms": 100,
    "data_b64": "base64encoded...",
    "sequence": 142
  }
}
```

---

## Server Commands (App receives)

```json
// TTS playback
{
  "type": "command",
  "command": "speak",
  "request_id": "req-123",
  "payload": {
    "text": "It's 6:47 PM",
    "voice": "soprano",
    "priority": "normal"
  }
}

// Screen action
{
  "type": "command",
  "command": "screen_action",
  "request_id": "req-124",
  "payload": {
    "action": "wake"  // wake | unlock | sleep
  }
}

// App launch
{
  "type": "command",
  "command": "launch_app",
  "request_id": "req-125",
  "payload": {
    "package": "com.twitter.android",
    "intent_extras": {}
  }
}

// Media control
{
  "type": "command",
  "command": "media",
  "request_id": "req-126",
  "payload": {
    "action": "play_pause"  // play_pause | next | prev | stop
  }
}

// Config update
{
  "type": "command",
  "command": "config",
  "request_id": "req-127",
  "payload": {
    "gaze_check_interval_ms": 2000,
    "audio_chunk_ms": 100,
    "battery_report_interval_s": 60
  }
}
```

---

## Minimal Permissions (Android 8.1+)

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Notification access - user must grant in Settings -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- Optional: for screen state without polling -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

**No:** `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `BLUETOOTH`, `LOCATION`.

---

## Core Components

### 1. `CompanionService` (Foreground Service)
- Runs continuously, survives reboots
- Holds WebSocket connection
- Manages all event sources
- Shows persistent notification ("Companion active")

### 2. `AttentionDetector` (CameraX + AudioRecord)
```kotlin
// Runs at 2-5 fps, not continuous streaming
// Uses TensorFlow Lite model (quantized, ~2MB) ON DEVICE
// Output: {addressing, looking, speaking, confidence}
// Only sends event when addressing=true (reduces traffic 99%)
class AttentionDetector(private val eventBus: EventBus) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val interpreter: Interpreter  // TF Lite gaze model
    
    fun start() {
        cameraExecutor.execute { loop() }
    }
    
    private fun loop() {
        while (running) {
            val frame = captureFrame()  // CameraX ImageAnalysis
            val audio = readAudioChunk() // AudioRecord 100ms
            val result = interpreter.run(frame, audio)
            if (result.addressing) eventBus.post(AttentionEvent(result))
            Thread.sleep(config.gazeCheckIntervalMs)
        }
    }
}
```

### 3. `NotificationListener` (NotificationListenerService)
```kotlin
class CompanionNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (shouldForward(sbn)) {
            val event = NotificationEvent.from(sbn)
            EventBus.post(event)
        }
    }
    
    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        // Filter: only high-priority, messaging apps, etc.
        return sbn.notification.priority >= Notification.PRIORITY_HIGH
            && ALLOWED_PACKAGES.contains(sbn.packageName)
    }
}
```

### 4. `Transport` (WebSocket + HTTP)
```kotlin
class Transport(private val config: Config, private val eventBus: EventBus) {
    private var webSocket: WebSocket? = null
    private val reconnectBackoff = ExponentialBackoff(1s, 30s, 2.0)
    
    fun connect() {
        // Try mDNS first: _companion._tcp.local.
        // Fallback to configured server URL
        val url = discoverServer() ?: config.serverUrl
        webSocket = OkHttpClient().newWebSocket(Request.Builder().url(url).build(), listener)
    }
    
    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val cmd = Command.parse(text)
            eventBus.post(cmd)
        }
        override fun onClosed(...) { scheduleReconnect() }
        override fun onFailure(...) { scheduleReconnect() }
    }
    
    fun send(event: Event) {
        webSocket?.send(event.toJson()) ?: queueForHttpFallback(event)
    }
}
```

### 5. `CommandExecutor`
```kotlin
class CommandExecutor(private val context: Context) {
    fun execute(cmd: Command): Result {
        return when (cmd.command) {
            "speak" -> TtsPlayer.play(cmd.payload.text)
            "screen_action" -> ScreenController.doAction(cmd.payload.action)
            "launch_app" -> AppLauncher.launch(cmd.payload.package)
            "media" -> MediaController.doAction(cmd.payload.action)
            "config" -> Config.update(cmd.payload)
            else -> Result.unknownCommand()
        }
    }
}
```

---

## Discovery & Auth

### mDNS Discovery (Zero-config)
```kotlin
// Server advertises: _companion._tcp.local. port 8081
// App discovers via NsdManager
val discoveryListener = object : NsdManager.DiscoveryListener {
    override fun onServiceFound(service: NsdServiceInfo) {
        if (service.serviceType == "_companion._tcp") {
            config.serverUrl = "wss://${service.host}:${service.port}/ws"
            transport.connect()
        }
    }
}
```

### Token Auth (Simple)
```
# Server generates on first pairing
# App stores in EncryptedSharedPreferences
Authorization: Bearer <device_token>
```

---

## Battery Optimization

| Strategy | Impact |
|----------|--------|
| **On-device gaze model** (TF Lite, 2MB) | No camera streaming → ~50mW vs ~500mW |
| **Event-driven, not polling** | CPU awake only on events |
| **Batched HTTP fallback** | Coalesce events, single radio wake |
| **Doze/Standby aware** | Foreground service + high-priority FCM for wake |
| **Configurable intervals** | Server tunes via `config` command |

**Estimated drain:** 1-2%/hour (vs 8%/hour for server-streaming)

---

## Build & Deploy

```bash
# Gradle (minSdk=27 for Android 8.1)
# Dependencies: okhttp, cameraX, tflite, gson, room (local queue)

./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk (~180KB)

# Install via ADB
adb install -r app-release.apk

# Grant notification access manually (Settings → Apps → Special access)
# Grant "Allow display over other apps" for TTS overlay if needed
```

---

## Server Changes Required (Minimal)

```python
# In phone_daemon.py - add WebSocket server
from aiohttp import web

async def ws_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    async for msg in ws:
        if msg.type == WSMsgType.TEXT:
            event = json.loads(msg.data)
            await handle_event(event)  # Route to AttentionDetector, etc.
    
    return ws

app = web.Application()
app.router.add_get('/ws', ws_handler)
app.router.add_post('/events/{type}', http_event_handler)

# Run alongside daemon
web.run_app(app, host='0.0.0.0', port=8081)
```

**Backward compatibility:** ADB backend remains as fallback. Daemon tries WebSocket first, falls back to ADB polling if no app connected.

---

## File Structure (App)

```
companion-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/southpawin/companion/
│   │   │   ├── CompanionApplication.kt
│   │   │   ├── service/
│   │   │   │   ├── CompanionService.kt          # Foreground service
│   │   │   │   ├── NotificationListener.kt      # NotificationListenerService
│   │   │   │   └── BootReceiver.kt              # Auto-start on boot
│   │   │   ├── detector/
│   │   │   │   ├── AttentionDetector.kt         # CameraX + TF Lite
│   │   │   │   └── model/                       # TF Lite model files
│   │   │   ├── transport/
│   │   │   │   ├── Transport.kt                 # WebSocket + HTTP
│   │   │   │   ├── Discovery.kt                 # mDNS
│   │   │   │   └── Auth.kt                      # Token management
│   │   │   ├── command/
│   │   │   │   ├── CommandExecutor.kt
│   │   │   │   ├── TtsPlayer.kt
│   │   │   │   ├── ScreenController.kt
│   │   │   │   ├── AppLauncher.kt
│   │   │   │   └── MediaController.kt
│   │   │   ├── event/
│   │   │   │   ├── EventBus.kt                  # Priority queue
│   │   │   │   └── models/*.kt                  # Data classes
│   │   │   └── util/
│   │   │       └── Config.kt
│   │   ├── res/
│   │   │   ├── layout/notification_layout.xml
│   │   │   ├── values/strings.xml
│   │   │   └── xml/notification_listener.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── models/                          # TF Lite models (git-lfs)
│       ├── gaze_detector.tflite
│       └── labels.txt
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Migration Path

| Phase | Server | Phone |
|-------|--------|-------|
| 0 (now) | ADB polling only | Nothing |
| 1 | Add WebSocket server + HTTP endpoints | Build & install companion app |
| 2 | Daemon prefers WS, falls back to ADB | App registers, starts pushing |
| 3 | Move gaze inference to app (TF Lite) | On-device model, only sends `addressing=true` |
| 4 | Add notification/battery/screen events | Full event parity + lower latency |
| 5 | Deprecate ADB backend for control | App executes all commands |

---

## Why This Works With Existing Backend

1. **Same event schema** — Daemon's `AttentionDetector.check_attention()` just reads from queue instead of polling
2. **Same command interface** — `_speak()`, `wake_screen()`, `tap()` become WS commands
3. **ADB stays as fallback** — No breaking changes, zero-downtime migration
4. **Server still owns logic** — App is thin; LLM, memory, TTS routing unchanged
5. **Config-driven** — Server tunes intervals, sensitivity via `config` command