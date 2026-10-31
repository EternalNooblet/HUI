# HUI — Hermes Unofficial Interface

Two independent components under one roof. **No shared deps, no shared builds, no coupling.**

---

## Components

| Component | Language | Purpose | Build |
|-----------|----------|---------|-------|
| **[hui-cli/](hui-cli/)** | Go (stdlib only) | Terminal TUI client for remote Hermes gateway | `./build.sh` → `builds/hui` / `builds/hui.exe` |
| **[hui-phone/](hui-phone/)** | Python + Kotlin | 24/7 Android AI agent + companion app | `pip install -r requirements.txt` → `python3 phone_daemon.py --daemon` |

---

## Quick Start

### hui-cli (Go TUI)
```bash
cd hui-cli
./build.sh                    # -> builds/hui
./builds/hui -host <gateway> -token <blob>
```

### hui-phone (Python Agent)
```bash
cd hui-phone
pip install -r requirements.txt
cp config.example.yaml config.yaml
# edit config.yaml for your device
python3 phone_daemon.py --test   # test attention detection
python3 phone_daemon.py --daemon # run 24/7 daemon
```

---

## Releases

- **hui-cli**: GitHub Actions (`.github/workflows/release.yml`) builds Linux/Windows binaries on tag push
- **hui-phone**: No automated release yet — local builds only (`pip wheel .`, `./gradlew assembleRelease`)

---

## Philosophy

> **Separate by default.** Each component owns its deps, build, config, and runtime.  
> Merge only what you need later (shared tooling, CI, docs).