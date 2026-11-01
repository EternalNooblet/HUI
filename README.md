# HUI — Hermes Unofficial Interface

Two independent components under one roof. **No shared deps, no shared builds, no coupling.**

---

## Components

| Component | Language | Purpose | Build |
|-----------|----------|---------|-------|
| **[hui-cli/](hui-cli/)** | Go (stdlib only) | Terminal TUI client for remote Hermes gateway | `./build.sh` → `builds/hui` / `builds/hui.exe` |
| **[hui-phone/](hui-phone/)** | Kotlin/Gradle | Android companion app (sensor client → WSS server) | `./gradlew assembleRelease` → APK |

### Demo

A quick walkthrough of a full hui-cli session: connect, typed prompt, streamed reply, /status, an inline approval answered by choice number, and a clean exit.

https://github.com/user-attachments/assets/f7f06a7f-36cd-4025-932a-67bf193476c7

---

## Quick Start

### hui-cli (Go TUI)
```bash
cd hui-cli
./build.sh                    # -> builds/hui
./builds/hui -host <gateway> -token <blob>
```

### hui-phone (Android Companion App + laptop server)
```bash
cd hui-phone/server
python3 companion_wss_server.py   # WSS :8081 — the app connects here

cd hui-phone/companion-app
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk
./gradlew assembleRelease
# Sign + install APK (see hui-phone/README.md)
```

---

## Releases

- **hui-cli**: GitHub Actions (`.github/workflows/release.yml`) builds Linux/Windows binaries on tag push
- **hui-phone**: No automated release yet — local Gradle build only

---

## Philosophy

> **Separate by default.** Each component owns its deps, build, config, and runtime.  
> Merge only what you need later (shared tooling, CI, docs).

---

## License

GPL-3.0 — the repo-root `LICENSE` applies to all code in both `hui-cli/` and `hui-phone/`.