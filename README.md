# HUI — Hermes Unofficial Interface

Two components, no shared deps, no shared builds.

| Component | What it is | Build |
|-----------|------------|-------|
| [hui-cli/](hui-cli/) | Go terminal client for a remote Hermes gateway | `./build.sh` → `builds/hui` |
| [hui-phone/](hui-phone/) | Android companion app + laptop WSS server | `./gradlew assembleRelease` → APK |

### Demo

A full hui-cli session: connect, typed prompt, streamed reply, `/status`, an inline approval, clean exit.

https://github.com/user-attachments/assets/f7f06a7f-36cd-4025-932a-67bf193476c7

## Quick start

```bash
# hui-cli
cd hui-cli && ./build.sh
./builds/hui -host <gateway> -token <blob>

# hui-phone server (run on the laptop the phone connects to)
cd hui-phone/server && python3 companion_wss_server.py

# hui-phone app (needs JDK 17 + Android SDK, see hui-phone/README.md)
cd hui-phone/companion-app && ./gradlew assembleRelease
```

## Releases

One `v*` tag builds everything and publishes a single GitHub Release:

```bash
git tag v0.1.11 && git push origin v0.1.11
```

That produces the Go binaries (Linux/macOS/Windows) plus the signed
Android APK and AAB. Details in [hui-phone/README.md](hui-phone/README.md).

## License

GPL-3.0 — see `LICENSE` at the repo root.
