#!/usr/bin/env python3
"""Drive hui in a pseudo-terminal: mint an access token, connect, send a prompt,
watch the streamed reply, then exercise slash commands and quit.

Remote path only: hermes serve on 9119, signed access blob via -token.
Host defaults to the verified LAN gateway; override with HUI_TEST_HOST."""
import os
import pty
import re
import select
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hui_bin import hui_bin as HUI

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable  # mint needs stdlib yaml — pinned Hermes builds lack it
mint = subprocess.run([PY, os.path.join(HERE, "mint_access_token.py")],
                      capture_output=True, text=True, check=True, timeout=10)
token = mint.stdout.strip()
if not token:
    raise SystemExit("mint_access_token.py produced no token")
lan_host = os.environ.get("HUI_TEST_HOST", "10.0.0.126")

pid, fd = pty.fork()
if pid == 0:  # child
    os.execv(HUI(),
             ["hui", "-host", f"{lan_host}:9119", "-token", token,
              "-debug", "/tmp/hui-wire3.log"])

os.environ.setdefault("TERM", "xterm-256color")
out = bytearray()
t0 = time.time()


def pump(seconds=0.5):
    end = time.time() + seconds
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], max(0.0, end - time.time()))
        if not r:
            continue
        try:
            data = os.read(fd, 65536)
        except OSError:
            return False
        if not data:
            return False
        out.extend(data)
    return True


def send(text):
    os.write(fd, text.encode())


def wait_for(pattern, timeout=90):
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        pump(0.5)
        m = rx.search(out.decode("utf-8", "replace"))
        if m:
            return m
    return None


pump(2.0)
print("== after startup ==")
print(out.decode("utf-8", "replace")[-500:])

send("Reply with exactly one word: HUI-OK\r")
m = wait_for(r"HUI-OK", timeout=120)
print("== got reply ==", bool(m))

send("/status\r")
pump(2.0)
send("/help\r")
pump(2.0)

# Shift+Enter via the kitty keyboard protocol: draft "say hi:[newline]now" —
# the wire log must show a real newline inside one prompt.submit text.
send("Say hi:\x1b[13;2umaybe\r")
pump(2.0)
wait_for(r"maybe", timeout=60)
pump(2.0)

send("/exit\r")
pump(2.0)

text = out.decode("utf-8", "replace")
tail = text[-3000:]
print("== final tail ==")
print(tail)
try:
    os.kill(pid, 9)
except OSError:
    pass

wire = open("/tmp/hui-wire3.log").read()
newline_ok = re.search(r'"text":"[^"]*\\n[^"]*"', wire) is not None
ok = bool(m) and "gateway  ws://" in text and "/exit" in text and newline_ok
print("RESULT:", "PASS" if ok else "FAIL", "(shift+enter newline on wire:", newline_ok, ")")
sys.exit(0 if ok else 1)
