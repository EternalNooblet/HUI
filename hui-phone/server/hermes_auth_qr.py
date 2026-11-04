#!/usr/bin/env python3
"""
Hermes auth QR for the HUI phone (companion-app Settings → Authentication).

Starts `hermes serve` if it isn't already listening, mints the signed
access token for hermes serve's /api/ws, detects the LAN IP, and prints
a scannable ASCII QR encoding:

    {"ws_url": "wss://<lan-ip>:8081/ws", "ip": "<lan-ip>",
     "port": 8081, "token": "<signed token>", "exp": <unix ts>}

ws_url/port point at the phone-facing companion WSS server
(server/companion_wss_server.py, default :8081), which bridges to
hermes serve internally — the phone never talks to :9119 directly.
SettingsActivity.applyQrPayload() stores ws_url (as server URL) + token
in EncryptedSharedPreferences via Config.setServerUrl/setAuthToken.

Usage:
    python3 hermes_auth_qr.py [--port 9119] [--wss-port 8081]
                              [--ttl 43200] [--no-start-serve]
                              [--json] [--no-qr]

Requires: pip install qrcode   (ASCII rendering; `pip install "qrcode[pil]"` optional)
Minting: reuses ~/projects/hui/hui-cli/tools/mint_access_token.py when
present (same script companion_wss_server.GatewayBridge uses), else
falls back to the identical inline HMAC-SHA256 mint from
~/.hermes/config.yaml dashboard.basic_auth.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import subprocess
import sys
import time
import uuid

MINT_SCRIPT = os.path.expanduser("~/projects/hui/hui-cli/tools/mint_access_token.py")
CFG = os.path.expanduser("~/.hermes/config.yaml")


def lan_ip() -> str:
    """Primary LAN IPv4 via the default-route trick; falls back to hostname."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())
    finally:
        s.close()


def port_open(host: str, port: int, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def ensure_serve(port: int, host: str, start: bool) -> None:
    if port_open("127.0.0.1", port):
        print(f"hermes serve already listening on :{port}", file=sys.stderr)
        return
    if not start:
        print(f"hermes serve NOT listening on :{port} (--no-start-serve given, continuing)",
              file=sys.stderr)
        return
    print(f"hermes serve not listening on :{port} — starting "
          f"`hermes serve --host {host} --port {port}` …", file=sys.stderr)
    # bind 0.0.0.0 (not loopback): companion_wss_server bridges to
    # ws://<lan-ip>:9119/api/ws, so serve must be LAN-reachable.
    # dashboard.basic_auth in config.yaml provides the required auth provider.
    subprocess.Popen(
        ["hermes", "serve", "--host", host, "--port", str(port)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    deadline = time.time() + 30
    while time.time() < deadline:
        if port_open("127.0.0.1", port):
            print(f"hermes serve is up on :{port}", file=sys.stderr)
            return
        time.sleep(0.5)
    print(f"warning: hermes serve did not answer on :{port} within 30s "
          f"(check `hermes serve --status`); minting token anyway", file=sys.stderr)


def _secret_bytes(raw: str) -> bytes:
    for dec in (base64.b64decode, bytes.fromhex):
        try:
            d = dec(raw)
            if len(d) >= 16:
                return d
        except (ValueError, TypeError):
            pass
    return raw.encode("utf-8")


def _read_basic_auth():
    """(username, secret) from ~/.hermes/config.yaml dashboard.basic_auth.

    No PyYAML dependency: the user's interactive python3 may be the
    system interpreter without yaml (the Hermes venv python has it,
    /usr/bin/python3 does not), so parse the two scalar keys directly.
    """
    try:
        import yaml  # type: ignore
        section = yaml.safe_load(open(CFG))["dashboard"]["basic_auth"]
        return section["username"], section["secret"]
    except ImportError:
        pass
    except Exception:
        pass
    user = raw = None
    in_dashboard = in_basic = False
    with open(CFG) as f:
        for line in f:
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            indent = len(line) - len(line.lstrip())
            key, _, val = line.strip().partition(":")
            val = val.strip().strip('"').strip("'")
            if indent == 0:
                in_dashboard = key == "dashboard"
                in_basic = False
            elif in_dashboard and indent == 2:
                in_basic = key == "basic_auth"
            elif in_dashboard and in_basic and indent >= 4:
                if key == "username":
                    user = val
                elif key == "secret":
                    raw = val
    return user, raw


def mint_token(ttl: int) -> str:
    """Signed token via the shared mint script, else identical inline mint."""
    # mint_access_token.py imports yaml; under a system python without
    # PyYAML it fails with a misleading "missing basic_auth" message.
    # Skip it in that case and use the inline mint directly.
    try:
        import yaml  # type: ignore  # noqa: F401
        has_yaml = True
    except ImportError:
        has_yaml = False
    if has_yaml and os.path.exists(MINT_SCRIPT):
        env = dict(os.environ, TOKEN_TTL_SECONDS=str(ttl))
        out = subprocess.run([sys.executable, MINT_SCRIPT],
                             capture_output=True, text=True, timeout=20, env=env)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
        print(f"mint script failed ({out.stderr.strip()}); trying inline mint",
              file=sys.stderr)
    user, raw = _read_basic_auth()
    if not user or not raw:
        sys.exit("username/secret must both be non-empty in dashboard.basic_auth")
    exp = int(time.time()) + max(60, ttl)
    payload = json.dumps({"sub": user, "kind": "access", "exp": exp,
                          "jti": uuid.uuid4().hex},
                         separators=(",", ":")).encode()
    sig = hmac.new(_secret_bytes(raw), payload, hashlib.sha256).digest()
    return base64.b64encode(payload + sig).decode()


def cert_fingerprint() -> str:
    """SHA-256 (hex) of server.crt DER — the app pins this from the QR.

    No cert is ever baked into the APK; the fingerprint travels out-of-band
    in the QR payload so any user's server cert works with the same APK.
    """
    import hashlib
    import ssl
    cert_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.crt")
    try:
        with open(cert_path, "rb") as f:
            pem = f.read()
        # PEM -> DER via ssl (stdlib only)
        der_bytes = ssl.PEM_cert_to_DER_cert(pem.decode())
        return hashlib.sha256(der_bytes).hexdigest().upper()
    except Exception as e:
        print(f"warning: cannot read {cert_path} ({e}); QR will carry no cert fingerprint",
              file=sys.stderr)
        return ""


def token_claims(token: str) -> dict:
    """Decoded payload claims: token = base64(payload_json || 32-byte HMAC)."""
    try:
        raw = base64.b64decode(token + "=" * (-len(token) % 4))
        return json.loads(raw[:-32])
    except Exception:
        return {}


def token_exp(token: str) -> int:
    return int(token_claims(token).get("exp") or (int(time.time()) + 12 * 3600))


def ensure_qrcode():
    try:
        import qrcode  # noqa: F401
        return qrcode
    except ImportError:
        print("qrcode not installed — attempting to install it …", file=sys.stderr)
        # System python3 may have no pip module at all (Arch base):
        # bootstrap it via ensurepip, then pip install --user.
        if subprocess.run([sys.executable, "-m", "pip", "--version"],
                          capture_output=True).returncode != 0:
            subprocess.run([sys.executable, "-m", "ensurepip", "--default-pip"],
                           capture_output=True)
        subprocess.run([sys.executable, "-m", "pip", "install", "--user", "-q",
                        "--break-system-packages", "qrcode"], check=False)
        # --user installs may not be on sys.path in this process; add them
        import site
        for p in [site.getusersitepackages()]:
            if p not in sys.path:
                sys.path.append(p)
        try:
            import qrcode  # noqa: F401
            return qrcode
        except ImportError:
            sys.exit("missing dependency: pip install qrcode   "
                     "(then re-run; payload JSON is printed with --no-qr)")


def _reexec_with_qrcode_python() -> None:
    """If this interpreter lacks qrcode, re-exec with a python that has it.

    System python3 on Arch has no pip/qrcode/yaml (PEP 668); the Hermes
    venv python does. Re-exec avoids mutating system python.
    """
    if os.environ.get("HERMES_AUTH_QR_REEXEC"):
        return
    try:
        import qrcode  # noqa: F401
        return
    except ImportError:
        pass
    import glob
    candidates = sorted(
        glob.glob(os.path.expanduser("~/.hermes/installs/*/environments/*/venv/bin/python3"))
        + glob.glob(os.path.expanduser("~/.hermes/tools/*/bin/python3"))
    )
    for cand in candidates:
        if os.path.realpath(cand) == os.path.realpath(sys.executable):
            continue
        probe = subprocess.run([cand, "-c", "import qrcode"], capture_output=True)
        if probe.returncode == 0:
            print(f"qrcode not in {sys.executable}; re-running with {cand}",
                  file=sys.stderr)
            os.environ["HERMES_AUTH_QR_REEXEC"] = "1"
            os.execv(cand, [cand, os.path.abspath(__file__), *sys.argv[1:]])


def print_ascii_qr(qrcode, payload_str: str) -> None:
    # Preferred: qrterminal (ANSI truecolor square pixels — no font/glyph
    # dependence, always renders square). Falls back to half-block ASCII.
    import shutil
    import subprocess
    exe = shutil.which("qrterminal") or os.path.expanduser("~/bin/qrterminal")
    if os.path.exists(exe):
        try:
            # -q 4: QR spec minimum quiet zone. -l L: largest modules.
            r = subprocess.run([exe, "-q", "4", "-l", "L", payload_str],
                               capture_output=True, text=True, timeout=10)
            if r.returncode == 0 and "47m" in r.stdout:
                sys.stdout.write(r.stdout)
                return
        except Exception:
            pass
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L,
                       box_size=1, border=4)
    qr.add_data(payload_str)
    qr.make(fit=True)
    # print_ascii renders half-blocks; invert=True gives dark-on-light
    # modules suitable for phone cameras. Pad each line to full width with
    # braille blanks so trailing quiet-zone whites can't be trimmed away
    # (which made the right edge look ragged in chat/terminal copies).
    import io
    buf = io.StringIO()
    try:
        qr.print_ascii(out=buf, invert=True)
    except TypeError:
        qr.print_ascii(out=buf)
    lines = buf.getvalue().split("\n")
    width = max(len(line) for line in lines)
    for line in lines:
        print(line + "⠀" * (width - len(line)))


def main() -> None:
    ap = argparse.ArgumentParser(description="Hermes auth QR for the HUI phone")
    ap.add_argument("--port", type=int, default=9119, help="hermes serve port")
    ap.add_argument("--host", default="0.0.0.0", help="hermes serve bind host")
    ap.add_argument("--wss-port", type=int, default=8081,
                    help="companion WSS server port the phone connects to")
    ap.add_argument("--ip", default=None, help="override LAN IP detection")
    ap.add_argument("--ttl", type=int, default=12 * 3600, help="token TTL seconds")
    ap.add_argument("--no-start-serve", action="store_true",
                    help="don't start hermes serve if it's down")
    ap.add_argument("--json", action="store_true", help="print payload JSON only")
    ap.add_argument("--no-qr", action="store_true", help="print payload JSON, no QR")
    args = ap.parse_args()

    if not (args.json or args.no_qr):
        _reexec_with_qrcode_python()

    ensure_serve(args.port, args.host, start=not args.no_start_serve)

    ip = args.ip or lan_ip()
    token = mint_token(args.ttl)
    claims = token_claims(token)
    # Keep the payload minimal so the ASCII QR fits an 80-column terminal
    # (ip/port are derivable from ws_url; exp is unused by the app).
    payload = {
        "ws_url": f"wss://{ip}:{args.wss_port}/ws",
        "token": token,
        # SHA-256 of server.crt: the app pins the server cert from this
        # out-of-band value — nothing cert-specific is baked into the APK.
        "cert_sha256": cert_fingerprint(),
        # jti identifies this one QR/token for server-side revocation
        # (server/hermes_revoke_token.py --jti ...). Settings ignores
        # unknown fields, so older app builds still work.
        "jti": claims.get("jti", ""),
    }
    payload_str = json.dumps(payload, separators=(",", ":"))

    if args.json or args.no_qr:
        print(payload_str)
        return

    ensure_qrcode()
    print(f"Scan with the phone: Settings → Authentication → Scan QR code", file=sys.stderr)
    exp_ts = token_exp(token)
    print(f"Payload: ws_url={payload['ws_url']} exp={exp_ts} "
          f"({max(0, exp_ts - int(time.time()))}s left)", file=sys.stderr)
    print_ascii_qr(ensure_qrcode(), payload_str)


if __name__ == "__main__":
    main()
