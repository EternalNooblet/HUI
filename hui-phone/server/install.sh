#!/bin/sh
# HUI Phone server installer — idempotent, safe to re-run.
#   ./install.sh
# What it does:
#   1. Installs Python deps (into the Hermes venv python if found, else python3)
#   2. Generates a self-signed TLS cert (server.crt/server.key) if missing,
#      with the LAN IP as a SAN entry
#   3. Prints the remaining manual steps (start hermes serve, run server, QR)
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

# --- 2. TLS cert (only if missing; never overwrite an existing one) ---
if [ ! -f server.crt ] || [ ! -f server.key ]; then
  LAN_IP="$(ip route get 8.8.8.8 2>/dev/null | sed -n 's/.*src \([0-9.]*\).*/\1/p' || true)"
  [ -z "$LAN_IP" ] && LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  echo "generating server.crt/server.key (SAN IP: ${LAN_IP:-unknown})"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout server.key -out server.crt -days 825 \
    -subj "/CN=localhost" \
    -addext "subjectAltName=IP:${LAN_IP:-127.0.0.1},DNS:localhost"
  echo "note: the app pins this cert — copy server.crt to the app's"
  echo "      res/raw/server_cert.crt and rebuild the APK if you regenerate it."
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
