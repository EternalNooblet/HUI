# AI Setup — bring up the HUI Phone stack on a fresh machine

For an AI agent (or a human) installing HUI Phone from scratch. Work top to
bottom; each step has a check. Everything here is verified against a real
install — nothing is assumed.

Direct mode (DIRECT_PLAN.md): the phone talks **straight to `hermes serve`**
over a plain LAN WebSocket. There is no companion server, no TLS cert, no
sensor upload, and no second STT copy — the gateway owns STT.

Prereqs: Hermes Agent installed (`hermes` on PATH, `~/.hermes/` exists),
an Android phone (8.1+), server machine + phone on the same LAN.

## 1. Start `hermes serve`

`hermes serve` is the JSON-RPC/WebSocket backend the phone connects to
directly. It is **not** a systemd service — it runs as soon as something
starts it and keeps running (one machine-level server; later launches
attach to it).

```bash
hermes serve --host 0.0.0.0 --port 9119   # default port is 9119, default bind 127.0.0.1
hermes serve --status                      # has it started? (lists running servers)
hermes serve --stop                        # stop it
```

- Bind `0.0.0.0` (LAN-reachable for the phone) requires an auth provider
  (step 2). On loopback it works without one, but the phone needs LAN.
- You don't strictly need to start it yourself: `hui-qr` (step 5)
  auto-starts it if nothing is listening on :9119.
- Don't confuse it with `hermes-gateway.service` (messaging-platform
  gateway) — different process, not required here.

Check: `ss -tlnp | grep 9119` shows a listener.

## 2. Configure dashboard auth (required)

`hermes serve` on a non-loopback bind refuses to work without
`dashboard.basic_auth` in `~/.hermes/config.yaml`:

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
- Without this, gated mode rejects every phone handshake with HTTP 403
  and token minting throws.

Check: `grep -A3 basic_auth ~/.hermes/config.yaml` shows all three keys.

## 3. Install the QR tooling

Download the APK and the QR-tooling zip (`hui-phone-server-<tag>.zip`)
from the GitHub Release (or clone the repo and use `hui-phone/server/`).
**Install location is `$HOME/hui-phone`** — app and tooling kept together,
mirroring the repo:

```bash
mkdir -p ~/hui-phone
unzip hui-phone-server-<tag>.zip -d ~/hui-phone/server
cp hui-phone-v<tag>.apk ~/hui-phone/
cd ~/hui-phone/server && ./install.sh
```

`install.sh` is idempotent. It installs `aiohttp` + `qrcode`
(`requirements.txt`) into the Hermes venv Python (found via
`~/.hermes/installs/*/facts.json` → `packages.venv.environment`), installs
the `hui-qr` launcher to `~/.local/bin`, and fetches `qrterminal` if
missing. There is no server process to run — `hermes serve` (step 1) is
the only backend.

## 4. STT — nothing to install

STT is the gateway's own (`POST /api/audio/transcribe`). The phone records
a WAV and POSTs it; no local whisper, no model download, no second copy.
The gateway's configured STT provider (`stt:` in config.yaml) does the
transcription.

## 5. Phone login via QR

```bash
hui-qr
```

The launcher finds the Hermes venv Python and prints the QR as colored
square pixels (qrterminal) directly into the terminal. It starts `serve`
if needed, mints a signed token (1-year expiry), and encodes
`ws://<lan>:9119/api/ws` + `http://<lan>:9119` + token. On the phone:
Settings → Authentication → scan. The URL and token land in encrypted
storage; no IP is baked into the app, so this works on any network.
Re-scanning a new QR re-points the app's socket automatically.

Lost phone? A blob is valid until its expiry (1 year) — the gateway
accepts any correctly HMAC'd token. True revocation means rotating
`dashboard.basic_auth.secret` (invalidates every outstanding blob; all
phones must rescan). `hermes_revoke_token.py` only records the jti in
`revoked_tokens.json` — nothing enforces that list in direct mode.

## Done check

- [ ] `hermes serve --status` shows a server (bound 0.0.0.0)
- [ ] `dashboard.basic_auth` present in config
- [ ] Phone scans QR → app shows connected, chat replies stream back
- [ ] Mic tap → speak → transcript bubble + spoken reply

## Optional: no-model test fixture

`companion_mock_gateway.py` speaks the gateway contract without any model
turn (scripted message.* replies). Run it and scan a QR pointing at it to
test the phone wire without spending a real turn. Not needed for normal use.
