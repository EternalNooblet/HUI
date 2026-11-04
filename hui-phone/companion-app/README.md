# Companion App — Android client

The Android half of [HUI Phone](../README.md). Connects directly to
`hermes serve` (plain LAN WebSocket, `ws://<lan>:9119/api/ws?token=…`)
and shows a chat UI with voice input and spoken replies. QR pairing
lives in [`../server/`](../server/) (`hui-qr`).

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

1. Open the app and connect (QR scan in Settings).
2. Grant camera + microphone when prompted.
3. Chat away — the app owns the one gateway socket while it's open.

## Wire (direct to hermes serve)

| Direction | What |
|-----------|------|
| Phone → gateway | `client.capabilities`, `session.create`/`session.resume`, `prompt.submit`, `image.attach` |
| Gateway → phone | `gateway.ready`, `message.start/delta/complete`, server requests (`approval`, `clarify`, `sudo`, `secret`, `vault.*`) |
| Voice | WAV → `POST /api/audio/transcribe` (Bearer blob) |
| Images | → `POST /api/chat/image-upload` (Bearer blob) |
| Replies | Assistant bubble; Android TTS read-out for voice-origin turns only |

See [`DIRECT_PLAN.md`](../DIRECT_PLAN.md) for the full contract.

## License

GPL-3.0 — see the repo-root `LICENSE`.
