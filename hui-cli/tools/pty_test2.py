#!/usr/bin/env python3
"""Second PTY pass: /new behavior and Ctrl+C quit.

Remote path only: hermes serve on 9119, signed access blob via -token.
Host defaults to the verified LAN gateway; override with HUI_TEST_HOST."""
import os
import pty
import re
import select
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from hui_bin import hui_bin as HUI
PY = sys.executable  # mint needs stdlib yaml — pinned Hermes builds lack it
mint = subprocess.run([PY, os.path.join(HERE, "mint_access_token.py")],
                      capture_output=True, text=True, check=True, timeout=10)
token = mint.stdout.strip()
if not token:
    raise SystemExit("mint_access_token.py produced no token")
lan_host = os.environ.get("HUI_TEST_HOST", "10.0.0.126")

pid, fd = pty.fork()
if pid == 0:
    os.execv(HUI(),
             ["hui", "-host", f"{lan_host}:9119", "-token", token,
              "-debug", "/tmp/hui-wire4.log"])

out = bytearray()


def pump(seconds=0.5):
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], max(0.0, end - time.time()))
        if r:
            try:
                data = os.read(fd, 65536)
            except OSError:
                return False
            if not data:
                return False
            out.extend(data)
    return True


def wait_for(pattern, timeout=30):
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        pump(0.3)
        if rx.search(out.decode("utf-8", "replace")):
            return True
    return False


pump(2.0)
first_sid = re.search(r"new session · ([0-9a-f]{6,})", out.decode("utf-8", "replace"))
print("first session:", first_sid.group(1) if first_sid else "NOT FOUND")

os.write(fd, b"/new\r")
ok_new = wait_for(r"── new session ──", timeout=15)
pump(1.5)
text = out.decode("utf-8", "replace")
second_sid = re.findall(r"new session · ([0-9a-f]{6,})", text)
print("after /new:", ok_new, "sessions seen:", len(set(second_sid)))

os.write(fd, b"\x03")  # Ctrl+C
pump(3.0)
try:
    wpid, status = os.waitpid(pid, os.WNOHANG)
    exited = wpid == pid
except ChildProcessError:
    exited = True
print("ctrl+c exit:", exited)

wire = open("/tmp/hui-wire4.log").read()
# serve emits no detach frames (skipped by design): /new closes the session and
# creates a fresh one, so the wire must show a second session.create.
creates = wire.count('"method":"session.create"')
print("session.create frames:", creates)

ok = ok_new and len(set(second_sid)) >= 2 and exited and first_sid and creates >= 2
print("RESULT:", "PASS" if ok else "FAIL")
raise SystemExit(0 if ok else 1)
