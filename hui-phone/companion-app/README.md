# Companion App — Android client

The Android half of [HUI Phone](../README.md). Connects to the laptop
server in [`../server/`](../server/) over WSS and pushes sensor events;
receives commands (speak, screen actions, app launch) and shows a
dual-mode voice/text chat UI.

## Build & install

```bash
cd hui-phone/companion-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk
./gradlew assembleRelease bundleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Needs Android 8.1+ (API 27), camera + microphone.

## Setup on the phone

1. Open the app and connect (QR or manual server URL).
2. Grant camera, microphone, and notification access when prompted.
3. The foreground service keeps the connection alive in the background.

## Events → server

| Event | Trigger |
|-------|---------|
| `attention` | Gaze/voice activity (on-device model) |
| `battery` | Every 60s + on change |
| `notification` | New notification from allowlisted apps |
| `screen` | Screen state change |
| `audio` | 100ms audio chunks for speech-to-text |

## Commands ← server

`speak` (TTS), `screen_action`, `launch_app`, `config` (timing intervals).

## License

GPL-3.0 — see the repo-root `LICENSE`.
