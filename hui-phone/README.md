# HUI Phone — Hermes Unofficial Interface App

<p align="center">
<img src="face%20only%20-%20canvas.png" width="420" alt="HUI app icon — face canvas">
</p>

<p align="center">Android companion app. The phone is a sensor client; all heavy compute runs on the server over WSS/TLS.</p>

```
[Phone: Android 8.1+] ──WSS/TLS──▶ [Server: wss://host:8081/ws]
  foreground service, battery/screen/notification monitors,
  dual-mode voice + text chat UI
```

## Setup

1. Download the APK and the server zip from the latest GitHub Release.
2. Unzip the server zip, run `./install.sh`, then start the server
   (`companion_wss_server.py`).
3. Install the APK on your phone, grant camera + mic when it asks.
4. On the server machine run `./hui-qr` - this will print an ascii QR code
5. Scan the QR in the app (Settings → Authentication).

Full details live in
[`server/AI_SETUP.md`](server/AI_SETUP.md).

## Run it

```bash
cd hui-phone/server && ./hui-qr   # starts serve if needed, prints QR — scan in app
```

Building the app from source instead of downloading the APK:
`cd hui-phone/companion-app && ./gradlew assembleRelease`, then
`adb install -r app/build/outputs/apk/release/app-release.apk`.

## Protocol (WSS)

- Phone → server: `hello`, then events (battery, screen, notification, audio)
- Server → phone: `welcome` (config), `command` (`speak`, `screen_action`, `launch_app`, `config`)

## Troubleshooting

- WSS won't connect: is the server running? `ss -tlnp | grep 8081`
- Service won't start: `adb logcat -s CompanionService:V Transport:V`
- Cert errors: the app accepts the server's self-signed cert (the signed token is what authenticates the server).

## License

GPL-3.0 — see the repo-root `LICENSE`.
