#!/usr/bin/env python3
"""
Revoke a phone QR/token (per-token deauthentication, option C).

Adds the token's jti (or SHA-256 fingerprint for legacy pre-jti tokens)
to server/revoked_tokens.json. companion_wss_server checks that file on
every WebSocket handshake (401 if revoked) and, via its revoked_watcher,
closes live connections within ~5s of revocation.

Usage:
    python3 hermes_revoke_token.py --jti <jti from QR payload>
    python3 hermes_revoke_token.py --token <signed token>
    python3 hermes_revoke_token.py --payload-file payload.json   # QR --json output
    python3 hermes_revoke_token.py --list                        # show revoked entries

The same QR code will then fail with 401 "revoked" until the phone is
re-scanned with a fresh hermes_auth_qr.py QR.
"""
import argparse
import base64
import hashlib
import json
import os
import sys

REVOKED_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "revoked_tokens.json")


def load():
    try:
        with open(REVOKED_FILE) as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    return {"jti": sorted(set(data.get("jti", []))),
            "fingerprints": sorted(set(data.get("fingerprints", [])))}


def save(data):
    tmp = REVOKED_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, REVOKED_FILE)


def fingerprint(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def claims_of(token: str) -> dict:
    try:
        raw = base64.b64decode(token + "=" * (-len(token) % 4))
        return json.loads(raw[:-32])
    except Exception:
        return {}


def main() -> None:
    ap = argparse.ArgumentParser(description="Revoke a phone QR/token")
    ap.add_argument("--jti", help="jti claim from the QR payload")
    ap.add_argument("--token", help="the signed token itself")
    ap.add_argument("--payload-file", help="JSON file with token/jti (hermes_auth_qr.py --json)")
    ap.add_argument("--list", action="store_true", help="list revoked jti/fingerprints")
    args = ap.parse_args()

    data = load()
    if args.list:
        print(json.dumps(data, indent=2) if (data["jti"] or data["fingerprints"])
              else "no revoked tokens")
        return

    jti = args.jti
    token = args.token
    if args.payload_file:
        with open(args.payload_file) as f:
            payload = json.load(f)
        jti = jti or payload.get("jti")
        token = token or payload.get("token")
        if not jti and token:
            jti = claims_of(token).get("jti")
    if token and not jti:
        jti = claims_of(token).get("jti")

    if not jti and not token:
        ap.error("give --jti, --token, or --payload-file (or --list)")

    changed = False
    if jti:
        if jti not in data["jti"]:
            data["jti"].append(jti)
            changed = True
        print(f"revoked jti: {jti}")
    if token:
        fp = fingerprint(token)
        if fp not in data["fingerprints"]:
            data["fingerprints"].append(fp)
            changed = True
        print(f"revoked fingerprint: {fp}")
    if changed:
        data["jti"] = sorted(set(data["jti"]))
        data["fingerprints"] = sorted(set(data["fingerprints"]))
        save(data)
        print(f"saved to {REVOKED_FILE} — live connections drop within ~5s, "
              "new handshakes get 401 revoked")


if __name__ == "__main__":
    main()
