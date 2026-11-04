# HUI Phone — Hermes Unofficial Interface App

<p align="center">
<img src="face%20only%20-%20canvas.png" width="420" alt="HUI app icon — face canvas">
</p>

<p align="center">Android app that talks directly to <code>hermes serve</code> over a plain LAN WebSocket — chat, voice input, and spoken replies, no middle server.</p>

## Features

- **Open source** — GPL-3.0 copyleft, derivatives must stay open, no device lockdown.
- **Battery efficient** — heavy compute runs on the server. Events batched, no server-side polling.
- **Image and voice chat** — image input, voice input, spoken replies.
- **Old phone compatible** — Android 8.1+ (API 27, 2017+).
- **Authentication** — native Hermes integration using `hermes serve`. Easy QR pairing that works through any text terminal.
- **Encrypted storage** — the pairing token lives in EncryptedSharedPreferences, never in code.

https://github.com/user-attachments/assets/fbce7eac-1f51-4752-bd6c-aa67fb4100d8

## Setup

**Start with [`server/AI_SETUP.md`](server/AI_SETUP.md)** — it has the full
walkthrough with checks for every step (backend, auth, server, voice, QR).
That's the canonical guide; the summary below is just the outline.

1. Download the APK and the server zip from the latest GitHub Release.
2. Unzip the server zip and run `./install.sh` (deps + QR launcher).
3. Start the backend: `hermes serve --host 0.0.0.0 --port 9119` (or let
   `hui-qr` start it for you).
4. Install the APK on your phone, grant camera + mic when it asks.
5. On the server machine run `hui-qr` — this prints the QR code.
6. Scan the QR in the app (Settings → Authentication).

## Run it

```bash
hui-qr   # starts serve if needed, prints QR — scan in app
```

Building the app from source instead of downloading the APK:
`cd hui-phone/companion-app && ./gradlew assembleRelease`, then
`adb install -r app/build/outputs/apk/release/app-release.apk`.

## Protocol (direct to hermes serve)

- Phone → `ws://<lan>:9119/api/ws?token=<blob>`: JSON-RPC —
  `client.capabilities`, `session.create`/`session.resume`,
  `prompt.submit`, `image.attach`.
- Serve → phone: `gateway.ready`, `message.start/delta/complete`,
  server requests (`approval`, `clarify`, `sudo`, `secret`, `vault.*`).
- Voice: WAV → `POST /api/audio/transcribe` (Bearer blob); images →
  `POST /api/chat/image-upload`.

## Troubleshooting

- Won't connect: is hermes serve up? `ss -tlnp | grep 9119`
- App logs: `adb logcat -s Transport:V VoiceInput:V Transcriber:V`
- 401/403 on connect: the QR token expired or was revoked — scan a fresh QR.

## License

GPL-3.0 — see the repo-root `LICENSE`.
