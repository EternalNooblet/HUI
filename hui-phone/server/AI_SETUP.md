# AI Setup — bring up the HUI Phone stack on a fresh machine

For an AI agent (or a human) installing HUI Phone from scratch. Work top to
bottom; each step has a check. Everything here is verified against a real
install — nothing is assumed.

Prereqs: Hermes Agent installed (`hermes` on PATH, `~/.hermes/` exists),
an Android phone (8.1+), laptop + phone on the same LAN.

## 1. Start `hermes serve`

`hermes serve` is the JSON-RPC/WebSocket backend the phone stack talks to.
It is **not** a systemd service — it runs as soon as something starts it and
keeps running (one machine-level server; later launches attach to it).

```bash
hermes serve --host 0.0.0.0 --port 9119   # default port is 9119, default bind 127.0.0.1
hermes serve --status                      # has it started? (lists running servers)
hermes serve --stop                        # stop it
```

- Bind `0.0.0.0` requires an auth provider (step 2). On loopback it works
  without one, but the phone server bridges to it locally, so loopback is
  enough — use `--host 0.0.0.0` only if something else needs LAN access.
- You don't strictly need to start it yourself: `hermes_auth_qr.py` (step 6)
  auto-starts it if nothing is listening on :9119.
- Don't confuse it with `hermes-gateway.service` (messaging-platform
  gateway) — different process, not required here.

Check: `ss -tlnp | grep 9119` shows a listener.

## 2. Configure dashboard auth (required)

Both `hermes serve` (on a non-loopback bind) and the companion server refuse
to work without `dashboard.basic_auth` in `~/.hermes/config.yaml`:

```yaml
dashboard:
  basic_auth:
    username: admin
    password_hash: scrypt...        # set via the dashboard login
    secret: <base64 or hex, >= 16 bytes>   # HMAC key for phone tokens
```

- `secret` signs the phone tokens — generate it once
  (`openssl rand -base64 32`) and never change it (existing phones break).
- `password_hash` is what the dashboard login checks; set it through the
  dashboard UI (`hermes dashboard`) the first time you log in.
- Without this, the companion server fails closed: every phone handshake
  is rejected and token minting throws.

Check: `grep -A3 basic_auth ~/.hermes/config.yaml` shows all three keys.

## 3. Install the phone server

Download `hui-phone-server-<tag>.zip` from the GitHub Release (or clone the
repo and use `hui-phone/server/`), unzip, then:

```bash
cd hui-phone-server && ./install.sh
```

`install.sh` is idempotent. It installs `aiohttp`, `websockets`, `qrcode`
(`requirements.txt`) into the Hermes venv Python (found via
`~/.hermes/installs/*/facts.json` → `packages.venv.environment`) — voice-in
imports Hermes's own `tools.transcription_tools`, so it must be that Python,
not system Python — and generates `server.crt`/`server.key` if missing.

Run it:

```bash
<venv-python> companion_wss_server.py    # WSS on :8081, bridges to serve
```

Check: `ss -tlnp | grep 8081` shows a listener; log says
"Companion WSS server running on wss://0.0.0.0:8081/ws".

## 4. STT — only for voice-in (optional)

Text chat needs nothing here. For voice input:

```yaml
# ~/.hermes/config.yaml
stt:
  enabled: true
  # local model defaults to faster-whisper `base` (~350MB, downloads on first use)
```

First voice message is slow (model download + load); after that it's cached
in-process — one model total, no second copy.

## 5. TLS certs — see the cert note in the README

`install.sh` generates the cert with the LAN IP as a SAN entry. The app
pins this cert, so if you regenerate it, copy `server.crt` to the app's
`res/raw/server_cert.crt` and rebuild the APK.

## 6. Phone login via QR

```bash
<venv-python> hermes_auth_qr.py
```

Starts `serve` if needed, mints a signed token (12h expiry), prints a QR
encoding the server URL + token. On the phone: Settings → Authentication
→ scan. The URL and token land in encrypted storage; no IP is baked into
the app, so this works on any network.

Revoke a lost phone: `hermes_revoke_token.py --jti <jti>` (or `--list`).

## Done check

- [ ] `hermes serve --status` shows a server
- [ ] `dashboard.basic_auth` present in config
- [ ] Companion server listening on :8081
- [ ] Phone scans QR → app shows connected, chat replies stream back
