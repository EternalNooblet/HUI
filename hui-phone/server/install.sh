#!/bin/sh
# HUI Phone server installer — idempotent, safe to re-run.
#   ./install.sh
# What it does:
#   1. Installs Python deps (into the Hermes venv python if found, else python3)
#   2. Installs qrterminal (pinned download, checksum-verified) if missing
#   3. Generates a self-signed TLS cert (server.crt/server.key) if missing,
#      with the LAN IP as a SAN entry
#   4. Prints the remaining manual steps (start hermes serve, run server, QR)
set -e
cd "$(dirname "$0")"

# --- 1. Python + deps ---
PY="$(command -v python3 || true)"
if [ -z "$PY" ]; then echo "error: python3 not found" >&2; exit 1; fi
# Prefer the Hermes venv python: voice-in imports tools.transcription_tools.
# The venv path is recorded in ~/.hermes/installs/*/facts.json (packages.venv).
HERMES_PY=""
for facts in "$HOME"/.hermes/installs/*/facts.json; do
  [ -f "$facts" ] || continue
  v="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('packages',{}).get('venv',{}).get('environment',''))" "$facts" 2>/dev/null || true)"
  [ -n "$v" ] && [ -x "$v/bin/python3" ] && HERMES_PY="$v/bin/python3" && break
done
if [ -n "$HERMES_PY" ] && [ -x "$HERMES_PY" ]; then
  PY="$HERMES_PY"
  echo "using Hermes venv python: $PY"
fi
"$PY" -m pip install -r requirements.txt

# --- 1c. hui-qr launcher -> ~/.local/bin (so the QR command is just `hui-qr`) ---
mkdir -p "$HOME/.local/bin"
ln -sf "$(pwd)/hui-qr" "$HOME/.local/bin/hui-qr"
echo "installed launcher to ~/.local/bin/hui-qr (symlink)"

# --- 1b. qrterminal binary (QR rendering) — download pinned, never bundle ---
QR_VERSION="v4.0.0"
QRT_SHA_X86_64="a182c40251fc9db9de276583011b0f0c48e88f1e533424d0ac56b39cfbdf9c00"
QRT_SHA_ARM64="3bcaea6fc0bf5ad8e52beb6c56323f3b3990d813ff72f0f87ba19977caaaa82f"
if command -v qrterminal >/dev/null 2>&1 || [ -x "$HOME/bin/qrterminal" ]; then
  echo "qrterminal already available — skipping download."
else
  case "$(uname -m)" in
    x86_64|amd64) ARCH=x86_64; SHA="$QRT_SHA_X86_64" ;;
    aarch64|arm64) ARCH=arm64; SHA="$QRT_SHA_ARM64" ;;
    *) echo "warning: unsupported arch $(uname -m) for qrterminal; QR will use ASCII fallback." ;;
  esac
  if [ -n "${ARCH:-}" ]; then
    url="https://github.com/mdp/qrterminal/releases/download/${QR_VERSION}/qrterminal_Linux_${ARCH}.tar.gz"
    tmp="$(mktemp -d)"
    echo "downloading qrterminal ${QR_VERSION} (${ARCH})..."
    curl -fsSL -o "$tmp/qrterminal.tar.gz" "$url"
    got="$(sha256sum "$tmp/qrterminal.tar.gz" | awk '{print $1}')"
    if [ "$got" != "$SHA" ]; then
      echo "error: qrterminal checksum mismatch ($got)" >&2; rm -rf "$tmp"; exit 1
    fi
    tar xzf "$tmp/qrterminal.tar.gz" -C "$tmp"
    mkdir -p "$HOME/bin"
    install -m 755 "$tmp/qrterminal" "$HOME/bin/qrterminal"
    rm -rf "$tmp"
    echo "installed qrterminal to ~/bin/qrterminal"
  fi
fi

# --- 2. TLS cert (only if missing; never overwrite an existing one) ---
if [ ! -f server.crt ] || [ ! -f server.key ]; then
  LAN_IP="$(ip route get 8.8.8.8 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p' || true)"
  [ -z "$LAN_IP" ] && LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  echo "generating server.crt/server.key (SAN IP: ${LAN_IP:-unknown})"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout server.key -out server.crt -days 825 \
    -subj "/CN=localhost" \
    -addext "subjectAltName=IP:${LAN_IP:-127.0.0.1},DNS:localhost"
  echo "no cert copy or APK rebuild needed: hermes_auth_qr.py puts the cert"
  echo "the app accepts this cert; the signed token authenticates the server."
else
  echo "server.crt/server.key already present — keeping them."
fi

echo
echo "Done. Next steps:"
echo "  1. hermes serve --host 0.0.0.0        # or let hermes_auth_qr.py start it"
echo "  2. $PY companion_wss_server.py      # WSS on :8081"
echo "  3. $PY hermes_auth_qr.py            # prints QR — scan in app Settings"
echo
echo "See AI_SETUP.md for the full checklist."
