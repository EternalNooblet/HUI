#!/usr/bin/env python3
"""Drive hui against the fake gateway: answer approval, clarify, sudo prompts."""
import json
import os
import pty
import re
import select
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from hui_bin import hui_bin as HUI
fake = subprocess.Popen([sys.executable, os.path.join(HERE, "fakegw.py")],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
port = None
for _ in range(50):
    line = fake.stdout.readline()
    if line.startswith("PORT"):
        port = int(line.split()[1])
        break
if port is None:
    print("fake gateway failed to start")
    sys.exit(1)

pid, fd = pty.fork()
if pid == 0:
    os.execv(HUI(),
             ["hui", "-url", f"ws://127.0.0.1:{port}/api/ws", "-debug", "/tmp/hui-wire5.log"])

out = bytearray()


def pump(seconds=0.4):
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


def wait_for(pattern, timeout=15):
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        pump(0.3)
        if rx.search(out.decode("utf-8", "replace")):
            return True
    return False


pump(1.5)
text = out.decode("utf-8", "replace")
approval_ui = "approval" in text and "rm -rf /tmp/x" in text and "[1] once" in text

os.write(fd, b"1\r")            # approval → choice "once"
ok2 = wait_for(r"Which color\?", timeout=10)
os.write(fd, b"my answer\r")    # clarify answer
ok3 = wait_for(r"sudo password", timeout=10)
os.write(fd, b"s3cret\r")       # sudo value
pump(1.0)
os.write(fd, b"/exit\r")        # close the client so the fake gateway reports
wait_for(r"bye", timeout=10)
pump(1.0)

try:
    out_line = fake.communicate(timeout=10)[0]
except subprocess.TimeoutExpired:
    fake.terminate()
    out_line = fake.communicate()[0]
try:
    os.kill(pid, 9)
except OSError:
    pass

print("== ui approval rendered:", approval_ui, "==")
print("== clarify prompt reached:", ok2, "== sudo prompt reached:", ok3, "==")
print(out_line)
m = re.search(r"ANSWERS (.*)", out_line)
answers = json.loads(m.group(1)) if m else []
choice_ok = any(a.get("result") == {"choice": "once"} for a in answers)
clarify_ok = any(a.get("result") == {"answers": {"q1": "my answer"}} for a in answers)
sudo_ok = any(a.get("result") == {"value": "s3cret"} for a in answers)
ok = approval_ui and ok2 and ok3 and choice_ok and clarify_ok and sudo_ok
print("RESULT:", "PASS" if ok else "FAIL",
      f"(approval_ui={approval_ui} choice_once={choice_ok} clarify={clarify_ok} sudo_value={sudo_ok})")
sys.exit(0 if ok else 1)
