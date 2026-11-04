# HUI Phone — Hermes Unofficial Interface App

<p align="center">
<img src="face%20only%20-%20canvas.png" width="420" alt="HUI app icon — face canvas">
</p>

<p align="center">Android app that talks directly to <code>hermes serve</code> over a plain LAN WebSocket — chat, voice input, and spoken replies, no middle server.</p>

## Features

- **Open source** — GPL-3.0 copyleft, derivatives must stay open, no device lockdown.
- **No background drain** — no sensors, no foreground service, no polling. The phone only talks when you use it.
- **Image and voice chat** — image input, voice input, spoken replies.
- **Old phone compatible** — Android 8.1+ (API 27, 2017+).
- **Authentication** — native Hermes integration using `hermes serve`. Easy QR pairing that works through any text terminal.
- **Encrypted storage** — the pairing token lives in EncryptedSharedPreferences, never in code.

https://github.com/user-attachments/assets/fbce7eac-1f51-4752-bd6c-aa67fb4100d8

## Setup

**Start with [`server/AI_SETUP.md`](server/AI_SETUP.md)** — it has the full
walkthrough with checks for every step (backend, auth, QR pairing).
That's the canonical guide; the summary below is just the outline.

1. Download the APK from the latest GitHub Release. (The release also
   ships a small QR-tooling zip — `server/` — for step 3.)
2. Start the backend: `hermes serve --host 0.0.0.0 --port 9119` (or let
   `hui-qr` start it for you).
3. Unzip the QR tooling and run `./install.sh` (deps + `hui-qr` launcher),
   then run `hui-qr` — it prints the QR code.
4. Install the APK on your phone, grant camera + mic when it asks.
5. Scan the QR in the app (Settings → Authentication).

## Run it

```bash
hui-qr   # starts serve if needed, prints QR — scan in app
```

Building the app from source instead of downloading the APK:
`cd hui-phone/companion-app && ./gradlew assembleRelease`. Without
keystore env vars (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`) the release build silently falls back to debug signing —
set them (see `app/build.gradle.kts`) for a properly signed APK, then
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
- 401/403 on connect: the QR token expired — scan a fresh QR (a lost phone's blob stays valid until expiry; rotate `dashboard.basic_auth.secret` to revoke it, then every phone rescans).

## License

GPL-3.0 — see the repo-root `LICENSE`.
