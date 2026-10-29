#!/usr/bin/env python3
"""Second PTY pass: /new behavior and Ctrl+C quit."""
import glob
import json
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
PY = (glob.glob(os.path.expanduser("~/.hermes/tools/python-3.14.9*/bin/python3"))
      or glob.glob(os.path.expanduser("~/.hermes/tools/python-3.14*/bin/python3")))[0]
mint = subprocess.run([PY, os.path.join(HERE, "mint_ticket.py")],
                      capture_output=True, text=True, check=True, timeout=10)
ticket = json.loads(mint.stdout)["ticket"]

pid, fd = pty.fork()
if pid == 0:
    os.execv(HUI(),
             ["hui", "-url", "ws://127.0.0.1:9119/api/ws", "-ticket", ticket,
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
first_sid = re.search(r"new session · (local-\S+)", out.decode("utf-8", "replace"))
print("first session:", first_sid.group(1) if first_sid else "NOT FOUND")

os.write(fd, b"/new\r")
ok_new = wait_for(r"── new session ──", timeout=15)
pump(1.5)
text = out.decode("utf-8", "replace")
second_sid = re.findall(r"local-[0-9a-f]+", text)
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
detach_new = wire.count('"end_reason":"new_session"')
detach_close = wire.count('"end_reason":"cli_close"')
print("detach end_reasons: new_session=%d cli_close=%d" % (detach_new, detach_close))

ok = ok_new and len(set(second_sid)) >= 2 and exited and detach_new >= 1
print("RESULT:", "PASS" if ok else "FAIL")
raise SystemExit(0 if ok else 1)
