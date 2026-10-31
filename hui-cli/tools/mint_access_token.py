#!/usr/bin/env python3
"""Mint a signed access blob for hermes serve's /api/ws (README "Token" section).

Reads the backend's auth config (username + signing secret) from
~/.hermes/config.yaml (dashboard.basic_auth keys) and prints the token on
stdout. Mirrors the provider's verification exactly:
base64url(compact-JSON payload + HMAC-SHA256(payload, secret)).

Env override: TOKEN_TTL_SECONDS (default 12 h).
"""
import json
import time
import base64
import hmac
import hashlib
import os
import sys

CFG = os.path.expanduser("~/.hermes/config.yaml")


def secret_bytes(raw: str) -> bytes:
    """Decode per the provider's _resolve_secret fallback chain (b64 -> hex -> raw)."""
    for dec in (base64.b64decode, bytes.fromhex):
        try:
            d = dec(raw)
            if len(d) >= 16:
                return d
        except (ValueError, TypeError):
            pass
    return raw.encode("utf-8")


def main() -> None:
    try:
        import yaml

        section = yaml.safe_load(open(CFG))["dashboard"]["basic_auth"]
        user, raw = section["username"], section["secret"]
    except Exception:
        sys.exit(f"missing dashboard.basic_auth username/secret in {CFG} - "
                 "set the auth config on the backend first")
    if not user or not raw:
        sys.exit("username/secret must both be non-empty in the auth config")

    ttl = int(os.environ.get("TOKEN_TTL_SECONDS", str(12 * 3600)))
    exp = int(time.time()) + max(60, ttl)
    payload = json.dumps({"sub": user, "kind": "access", "exp": exp},
                         separators=(",", ":")).encode()
    sig = hmac.new(secret_bytes(raw), payload, hashlib.sha256).digest()
    sys.stdout.write(base64.b64encode(payload + sig).decode() + "\n")


if __name__ == "__main__":
    main()
