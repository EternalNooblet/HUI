# Companion App - Server Integration Guide

## Overview
This document describes how to integrate the Companion App (Android) with the `phone_daemon.py` server for secure, push-based communication.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        SERVER (phone_daemon.py)                          │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐    ┌─────────────────────────────────────────┐   │
│  │  WSS Server      │    │  Event Processing                        │   │
│  │  (aiohttp)       │◄───│  - Attention → activate conversation     │   │
│  │  Port 8081       │    │  - Battery → low battery alerts          │   │
│  │  TLS + Cert Pin  │    │  - Notifications → proactive summaries   │   │
│  └────────┬─────────┘    │  - Screen state → context awareness      │   │
│           │              │  - Audio chunks → STT processing         │   │
│           ▼              └─────────────────────────────────────────┘   │
│  ┌──────────────────┐    ┌─────────────────────────────────────────┐   │
│  │  Command Sender  │    │  Daemon Integration                      │   │
│  │  - speak()       │    │  - event_queue.put()                     │   │
│  │  - wake_screen() │    │  - _activate() on attention              │   │
│  │  - launch_app()  │    │  - TTS via speak skill                   │   │
│  │  - config()      │    │  - Memory persistence                    │   │
│  └──────────────────┘    └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │ WSS (TLS 1.3 + Cert Pinning)
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      ANDROID APP (Companion)                             │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐      │
│  │  Sensors         │  │  Event Bus       │  │  Transport       │      │
│  │  - CameraX (2fps)│──►│  (priority q)    │──►│  WSS Client      │      │
│  │  - AudioRecord   │   │  Batching        │   │  OkHttp + TLS    │      │
│  │  - BatteryManager│   │  Deduplication   │   │  Cert Pinning    │      │
│  │  - Notifications │   │  Backpressure    │   │  Auto-reconnect  │      │
│  │  - ScreenObserver│   │                  │   │  mDNS discovery  │      │
│  └──────────────────┘   └──────────────────┘   └────────┬─────────┘      │
│                                                         │                │
│  ┌─────────────────────────────────────────────────────┘                │
│  │                    Command Executor                                 │
│  │  - speak → TTS (Soprano 80M via system TTS)                        │
│  │  - screen_action → wake/sleep                                       │
│  │  - launch_app → PackageManager                                      │
│  │  - config → update intervals                                        │
│  └─────────────────────────────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────────────────┘
```

## Event Schema (App → Server)

### Attention Event (Primary Wake Signal)
```json
{
  "type": "attention",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "android-xxx",
  "payload": {
    "addressing": true,
    "looking": true,
    "speaking": true,
    "confidence": 0.94,
    "gaze_vector": [0.02, -0.01, 0.99],
    "audio_level_db": -12.3
  }
}
```

### Battery Event
```json
{
  "type": "battery",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "android-xxx",
  "payload": {
    "level": 0.73,
    "charging": false,
    "health": "good",
    "temperature_c": 31.5
  }
}
```

### Notification Event
```json
{
  "type": "notification",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "android-xxx",
  "payload": {
    "package": "com.whatsapp",
    "title": "John Doe",
    "text": "Hey, are you coming?",
    "priority": "HIGH",
    "category": "msg",
    "actions": ["reply", "mark_read"]
  }
}
```

### Screen State Event
```json
{
  "type": "screen",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "android-xxx",
  "payload": {
    "state": "UNLOCKED",
    "brightness": 0.8
  }
}
```

### Audio Chunk (for STT)
```json
{
  "type": "audio",
  "timestamp": "2026-10-31T18:47:22.123Z",
  "device_id": "android-xxx",
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

## Command Schema (Server → App)

### Speak (TTS)
```json
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
```

### Screen Action
```json
{
  "type": "command",
  "command": "screen_action",
  "request_id": "req-124",
  "payload": {
    "action": "wake"
  }
}
```

### Launch App
```json
{
  "type": "command",
  "command": "launch_app",
  "request_id": "req-125",
  "payload": {
    "package": "com.twitter.android",
    "intent_extras": {}
  }
}
```

### Media Control
```json
{
  "type": "command",
  "command": "media",
  "request_id": "req-126",
  "payload": {
    "action": "play_pause"
  }
}
```

### Config Update
```json
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

## Server Integration (Add to phone_daemon.py)

```python
# Add to phone_daemon.py imports
from aiohttp import web
import asyncio
import json

# Global event queue for daemon integration
event_queue = asyncio.Queue()

# Connected devices tracking
connected_devices = {}

async def ws_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    device_id = request.headers.get('X-Device-ID', 'unknown')
    auth_token = request.headers.get('Authorization', '').replace('Bearer ', '')
    
    logger.info(f"Device connected: {device_id}")
    connected_devices[device_id] = {
        'ws': ws,
        'connected_at': datetime.now().isoformat(),
        'auth_token': auth_token
    }
    
    # Send welcome/config
    await ws.send_json({
        'type': 'welcome',
        'device_id': device_id,
        'config': {
            'gaze_check_interval_ms': 2000,
            'audio_chunk_ms': 100,
            'battery_report_interval_s': 60,
            'notification_filter_packages': [
                'com.whatsapp', 'com.google.android.apps.messaging',
                'com.android.mms', 'com.google.android.gm',
                'com.microsoft.office.outlook', 'org.telegram.messenger'
            ]
        }
    })
    
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    event = json.loads(msg.data)
                    await handle_event(device_id, event)
                except json.JSONDecodeError as e:
                    logger.error(f"Invalid JSON from {device_id}: {e}")
            elif msg.type == WSMsgType.ERROR:
                logger.error(f"WS error for {device_id}: {ws.exception()}")
    finally:
        connected_devices.pop(device_id, None)
        logger.info(f"Device disconnected: {device_id}")
    
    return ws

async def handle_event(device_id: str, event: dict):
    """Process incoming events from companion app"""
    event_type = event.get('type')
    payload = event.get('payload', {})
    
    logger.info(f"Event from {device_id}: {event_type}")
    
    if event_type == 'attention':
        if payload.get('addressing'):
            logger.info(f"🎯 ATTENTION DETECTED from {device_id}: confidence={payload.get('confidence')}")
            # Put in queue for daemon
            await event_queue.put({
                'type': 'attention',
                'device_id': device_id,
                'payload': payload,
                'timestamp': event.get('timestamp')
            })
    
    elif event_type == 'battery':
        logger.info(f"🔋 Battery from {device_id}: level={payload.get('level')}, charging={payload.get('charging')}")
        await event_queue.put({
            'type': 'battery',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'notification':
        logger.info(f"🔔 Notification from {device_id}: {payload.get('package')} - {payload.get('title')}")
        await event_queue.put({
            'type': 'notification',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'screen':
        logger.info(f"📱 Screen from {device_id}: state={payload.get('state')}")
        await event_queue.put({
            'type': 'screen',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })
    
    elif event_type == 'audio':
        # Audio chunks for STT - queue for processing
        await event_queue.put({
            'type': 'audio',
            'device_id': device_id,
            'payload': payload,
            'timestamp': event.get('timestamp')
        })

async def http_event_handler(request):
    """HTTP fallback for events"""
    event = await request.json()
    device_id = request.headers.get('X-Device-ID', 'unknown')
    await handle_event(device_id, event)
    return web.json_response({"status": "ok"})

async def send_command(device_id: str, command: str, payload: dict):
    """Send command to device"""
    if device_id not in connected_devices:
        logger.warning(f"Device {device_id} not connected")
        return False
    
    ws = connected_devices[device_id]['ws']
    request_id = str(uuid.uuid4())[:8]
    
    message = {
        'type': 'command',
        'command': command,
        'request_id': request_id,
        'payload': payload
    }
    
    try:
        await ws.send_json(message)
        logger.info(f"Sent command {command} to {device_id} (req_id={request_id})")
        return True
    except Exception as e:
        logger.error(f"Failed to send command to {device_id}: {e}")
        return False

# Example usage functions
async def speak(device_id: str, text: str):
    return await send_command(device_id, 'speak', {'text': text})

async def wake_screen(device_id: str):
    return await send_command(device_id, 'screen_action', {'action': 'wake'})

async def launch_app(device_id: str, package: str):
    return await send_command(device_id, 'launch_app', {'package': package})

# Modified daemon _monitor_loop to use event queue
async def _monitor_loop(self):
    """Background task that monitors for attention via event queue"""
    self.log("INFO", "Attention monitoring started (WSS)")
    
    while self._running:
        if not self._conversation_active:
            try:
                event = await asyncio.wait_for(event_queue.get(), timeout=1.0)
                
                if event.get('type') == 'attention' and event.get('payload', {}).get('addressing'):
                    self._activate()
            except asyncio.TimeoutError:
                continue
        
        await asyncio.sleep(0.5)

# Start WSS server alongside daemon
async def start_wss_server():
    app = web.Application()
    app.router.add_get('/ws', ws_handler)
    app.router.add_post('/events/{type}', http_event_handler)
    app.router.add_post('/events', http_event_handler)
    
    # SSL context with self-signed cert
    ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    ssl_context.load_cert_chain('/home/papa/burner-phone/server.crt', '/home/papa/burner-phone/server.key')
    
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, '0.0.0.0', 8081, ssl_context=ssl_context)
    await site.start()
    
    logger.info("Companion WSS server running on wss://0.0.0.0:8081/ws")
    return runner
```

## Network Requirements

### Current Issue: AP Isolation
The router's AP/client isolation prevents direct device-to-device communication on the same WiFi network.

### Solutions (pick one):

1. **Disable AP Isolation on Router** (best)
   - Router admin → Wireless → Advanced → AP Isolation → Disable

2. **Laptop Hotspot** (no router access needed)
   ```bash
   # On laptop (Linux)
   nmcli device wifi hotspot con-name companion-hotspot ssid companion password "companion123"
   # Connect phone to "companion" WiFi
   ```

3. **USB Tethering** (most reliable)
   - Phone: Settings → Network → Hotspot & tethering → USB tethering
   - Laptop gets IP from phone, direct connection

4. **adb reverse** (for USB connection)
   ```bash
   adb reverse tcp:8081 tcp:8081
   # App connects to wss://localhost:8081
   ```

5. **Travel Router** (portable)
   - GL.iNet GL-MT1300 or similar
   - Creates isolated network for devices

## Certificate Management

### Generate Certificates
```bash
# Self-signed for development
openssl req -x509 -newkey rsa:2048 -keyout server.key -out server.crt -days 365 -nodes \
  -subj "/CN=companion-server" \
  -addext "subjectAltName=DNS:localhost,IP:YOUR_LAN_HOST,IP:127.0.0.1"

# Embed in app: app/src/main/res/raw/server_cert.der
openssl x509 -in server.crt -outform der -out app/src/main/res/raw/server_cert.der
```

### Certificate Pinning (in Transport.kt)
```kotlin
// Pinned certificate validation
val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
context.resources.openRawResource(R.raw.server_cert).use { inputStream ->
    val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(inputStream) as X509Certificate
    ks.setCertificateEntry("server_cert", cert)
}

val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
tmf.init(ks)
val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
```

## Testing

### Test WSS Server
```bash
# Start server
python3 companion_wss_server.py

# Test connection
python3 -c "
import asyncio, websockets, ssl, json
async def test():
    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE
    async with websockets.connect('wss://localhost:8081/ws', ssl=ssl_ctx) as ws:
        print(await ws.recv())
        await ws.send(json.dumps({'type':'attention','payload':{'addressing':True,'confidence':0.9}}))
        print(await ws.recv())
asyncio.run(test())
"
```

### Test Commands
```bash
# Send speak command via websocket
python3 -c "
import asyncio, websockets, ssl, json
async def test():
    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE
    async with websockets.connect('wss://localhost:8081/ws', ssl=ssl_ctx) as ws:
        await ws.recv()  # welcome
        await ws.send(json.dumps({
            'type':'command','command':'speak','request_id':'test-1',
            'payload':{'text':'Hello from secure server'}
        }))
asyncio.run(test())
"
```

## Phone Setup Checklist

- [ ] Install `app-release-signed.apk` via `adb install`
- [ ] Grant permissions: Camera, Microphone
- [ ] Enable Notification Access: Settings → Apps → Special access → Notification access
- [ ] Disable Battery Optimization for Companion
- [ ] Connect to same network as server (disable AP isolation or use hotspot)
- [ ] Start service: `adb shell am start-foreground-service -n com.southpawin.companion/.service.CompanionService`
- [ ] Verify persistent notification appears
- [ ] Check logs: `adb logcat -s "Transport:WSS:CompanionService"`

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Handshake failed" | Check cert matches, hostname in SAN |
| "Connection refused" | Server not running, wrong port/IP |
| "Cert validation failed" | Pin cert in app, check server.crt |
| "No device_id" | Check Config.deviceId initialization |
| "Service crashes" | Check logcat for NPE in Transport |
| "Network unreachable" | AP isolation, wrong network, firewall |

## Next Steps

1. **Fix network connectivity** (disable AP isolation or use hotspot)
2. **Verify WSS connection** - check logs for "WSS connected securely"
3. **Test attention detection** - look at phone, speak, verify "ATTENTION DETECTED" in server logs
4. **Integrate with daemon** - replace ADB polling with `event_queue` consumption
5. **Add STT pipeline** - process audio chunks via Vosk/Whisper
6. **Add proactive features** - battery alerts, notification summaries