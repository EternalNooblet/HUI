# HUI — Hermes Unofficial Interface

Two components, no shared deps, no shared builds.

| Component | What it is | Build |
|-----------|------------|-------|
| [hui-cli/](hui-cli/) | Go terminal client for a remote Hermes gateway | `./build.sh` → `builds/hui` |
| [hui-phone/](hui-phone/) | Android app — talks straight to `hermes serve` | `./gradlew assembleRelease` → APK |

### Demos

The phone app in action!

https://github.com/user-attachments/assets/fbce7eac-1f51-4752-bd6c-aa67fb4100d8

A full hui-cli session: connect, typed prompt, streamed reply, `/status`, an inline approval, clean exit.

https://github.com/user-attachments/assets/f7f06a7f-36cd-4025-932a-67bf193476c7

## Architecture

```
                        ┌──────────────────────────────┐
                        │        Hermes gateway        │  (remote, runs the agent)
                        │   chat · tools · sessions    │
                        │   STT · approvals · clarify  │
                        └──────▲───────────────▲───────┘
                    token auth │               │ token auth
                               │               │
        ┌──────────────────────┴───┐   ┌───────┴──────────────────────┐
        │   hui-cli (Go)   │   │  hui-phone app (Android)          │
        │   terminal chat TUI      │   │  ws://<lan>:9119/api/ws      │
        └──────────────────────────┘   │  chat · voice · approvals   │
                                       └─────────────────────────────┘
```

- **hui-cli** talks straight to the gateway — no server of its own.
- **hui-phone** is the same: the Android app talks straight to the gateway
  over a plain LAN WebSocket (chat, WAV voice input, spoken replies,
  approval/clarify dialogs). No middle server — see `hui-phone/DIRECT_PLAN.md`.

## Quick start

```bash
# hui-cli
cd hui-cli && ./build.sh
./builds/hui -host <gateway> -token <blob>

# hui-phone QR tooling (mints the pairing token; needs hermes serve up)
cd hui-phone/server && ./install.sh && hui-qr

# hui-phone app (needs JDK 17 + Android SDK, see hui-phone/README.md)
cd hui-phone/companion-app && ./gradlew assembleRelease
```

## License

GPL-3.0 — see `LICENSE` at the repo root.
