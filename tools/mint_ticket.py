#!/usr/bin/env python3
"""Mint a one-use session ticket via the gateway's owner-only control socket.

Same-user peers may ask the owner for a session ticket (the control socket's
SO_PEERCRED gate is the trust boundary). The ticket works once, for 30 s, over
the loopback ticketed /api/ws path. Test helper for HUI.
"""
import json
import socket
import sys
import os
from pathlib import Path

home = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
sock_path = home / "gateway.sock"

def ask(request: dict) -> dict:
    # the control socket serves exactly one request line per connection
    peer = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    peer.settimeout(5)
    peer.connect(str(sock_path))
    try:
        peer.sendall(json.dumps(request).encode() + b"\n")
        data = bytearray()
        while b"\n" not in data:
            chunk = peer.recv(4096)
            if not chunk:
                raise RuntimeError("control socket closed")
            data.extend(chunk)
            if len(data) > 524288:
                raise RuntimeError("oversized control response")
        return json.loads(bytes(data).split(b"\n", 1)[0])
    finally:
        peer.close()


ident = ask({"protocol": 1, "verb": "identify", "id": 1})
if not ident.get("ok"):
    raise SystemExit(f"identify failed: {ident}")
result = ident["result"]
served = result.get("served_profiles") or []
profile_id = (served[0].get("profile_id") if served else None) or result.get("profile_id")
instance_id = result.get("instance_id")
origin = result.get("api_origin")
if not profile_id or not instance_id:
    raise SystemExit(f"identify response missing profile/instance: {result}")

grant = ask({"protocol": 1, "id": 2, "verb": "session-ticket", "params": {
    "profile_id": profile_id, "instance_id": instance_id, "purpose": "interactive"}})
if not grant.get("ok"):
    raise SystemExit(f"ticket mint failed: {grant}")
print(json.dumps({
    "ticket": grant["result"]["ticket"],
    "api_origin": origin,
    "profile_id": profile_id,
}))
