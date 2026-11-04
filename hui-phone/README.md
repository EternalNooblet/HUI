# HUI Phone — Hermes Unofficial Interface App

<p align="center">
<img src="face%20only%20-%20canvas.png" width="420" alt="HUI app icon — face canvas">
</p>

<p align="center">Android companion app. The phone is a sensor client; all heavy compute runs on a laptop server over WSS/TLS.</p>

```
[Phone: Android 8.1+] ──WSS/TLS──▶ [Laptop: wss://host:8081/ws]
  foreground service, battery/screen/notification monitors,
  dual-mode voice + text chat UI
```

## Setup

Full walkthrough (human + agent checklists): [`server/AI_SETUP.md`](server/AI_SETUP.md).
The short version:

1. **Start the Hermes backend:** `hermes serve --host 0.0.0.0` (or let the
   QR script start it for you). Check with `hermes serve --status`.
2. **Dashboard auth:** set `dashboard.basic_auth` (username + secret) in
   `~/.hermes/config.yaml` — without it, phone logins are refused.
3. **Install the server:** download `hui-phone-server-<tag>.zip` from the
   GitHub Release, unzip, run `./install.sh`, then start
   `companion_wss_server.py` (WSS on :8081).
4. **Voice-in only (optional):** set `stt.enabled: true` in the Hermes
   config; the speech model downloads on first use.
5. **Connect the phone:** run `hermes_auth_qr.py`, scan the QR in the app
   (Settings → Authentication). The APK has no server address baked in —
   the QR supplies it, so any network works.

## Run it

```bash
# 1. Server on the laptop:
cd hui-phone/server && python3 companion_wss_server.py

# 2. App on the phone — download the APK from the latest GitHub Release,
#    or build locally. The APK contains no server address: scan the QR
#    from hermes_auth_qr.py (Settings → Authentication) to connect.
cd hui-phone/companion-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk
./gradlew assembleRelease bundleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 3. Grant permissions and start:
adb shell pm grant com.hui.companion android.permission.CAMERA
adb shell pm grant com.hui.companion android.permission.RECORD_AUDIO
adb shell am start-foreground-service -n com.hui.companion/.service.CompanionService
```

Local builds without keystore settings sign with the debug key; CI releases sign with the real release key.

## Protocol (WSS)

- Phone → server: `hello`, then events (battery, screen, notification, audio)
- Server → phone: `welcome` (config), `command` (`speak`, `screen_action`, `launch_app`, `config`)

## Troubleshooting

- WSS won't connect: is the server running? `ss -tlnp | grep 8081`
- Service won't start: `adb logcat -s CompanionService:V Transport:V`
- Cert errors: the app trusts the server's self-signed cert in `server/` (gitignored) and the embedded copy in the app.

## License

GPL-3.0 — see the repo-root `LICENSE`.
