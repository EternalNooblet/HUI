#!/usr/bin/env python3
"""Visual screen test: run hui in a PTY against the fake gateway, capture every
byte written to the terminal, replay it on a virtual terminal grid and print
what the user would actually see at each step. No substring greps — this is a
real screen model: rows, columns, cursor, wrap, clears."""
import fcntl
import os
import pty
import re
import select
import struct
import subprocess
import sys
import termios
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from hui_bin import hui_bin as HUI
HUI = HUI()  # resolve once to a concrete path
PY = sys.executable

# ── virtual terminal ──────────────────────────────────────────────────────────
class VT:
    def __init__(self, rows=24, cols=80):
        self.rows, self.cols = rows, cols
        self.grid = [[" "] * cols for _ in range(rows)]
        self.r = self.c = 0
        self.alt = False
        self.saved = None
        self.sgr = ""
        self.raw = ""

    def put(self, ch):
        if ch == "\n":
            self.r = min(self.r + 1, self.rows - 1)
            return
        if ch == "\r":
            self.c = 0
            return
        if ch == "\t":
            self.c = min(self.c + 8 - self.c % 8, self.cols - 1)
            return
        if ch == "\b":
            self.c = max(0, self.c - 1)
            return
        if ord(ch) < 32 or ch == "\x7f":
            return
        if self.c >= self.cols:
            self.r = min(self.r + 1, self.rows - 1)
            self.c = 0
        self.grid[self.r][self.c] = ch
        self.c += 1

    def handle(self, data):
        self.raw += data
        i = 0
        while i < len(data):
            b = data[i]
            if b == "\x1b":
                m = re.match(r"\x1b\[([0-9;?]*)([A-Za-z@-~])", data[i:])
                if not m:
                    i += 1
                    continue
                params, final = m.group(1), m.group(2)
                i += m.end()
                self.csi(params, final)
            else:
                self.put(b)
                i += 1

    def csi(self, params, final):
        params = params.lstrip("?")  # private-mode params like ?1049h
        params = params.lstrip("<>")  # kitty push/pop CSI > 1u / < 1u
        raw = params.split(";") if params else []
        # For J/K an omitted or zero param means "clear from cursor" (0).
        if final in ("J", "K"):
            def get(n, d=0):
                if n < len(raw) and raw[n] != "":
                    return int(raw[n])
                return d
        else:
            def get(n, d=1):
                if n < len(raw) and raw[n] != "" and int(raw[n]) != 0:
                    return int(raw[n])
                return d
        if final in ("H", "f"):
            self.r = min(get(0) - 1, self.rows - 1)
            self.c = min(get(1) - 1, self.cols - 1)
        elif final == "A":
            self.r = max(0, self.r - get(0))
        elif final == "B":
            self.r = min(self.rows - 1, self.r + get(0))
        elif final == "C":
            self.c = min(self.cols - 1, self.c + get(0))
        elif final == "D":
            self.c = max(0, self.c - get(0))
        elif final == "G":
            self.c = min(get(0) - 1, self.cols - 1)
        elif final == "J":
            if get(0) == 2:
                self.grid = [[" "] * self.cols for _ in range(self.rows)]
                self.r = self.c = 0
            elif get(0) == 0:
                for rr in range(self.r, self.rows):
                    for cc in range(self.c if rr == self.r else 0, self.cols):
                        self.grid[rr][cc] = " "
        elif final == "K":
            if get(0) == 0:
                for cc in range(self.c, self.cols):
                    self.grid[self.r][cc] = " "
            elif get(0) == 2:
                for cc in range(self.cols):
                    self.grid[self.r][cc] = " "
        elif final == "m":
            self.sgr = params
        elif final in ("h", "l"):
            # alt-screen enter/exit: ?1049h saves the normal grid, ?1049l restores it
            if params.startswith("1049") or "1049" in params:
                if final == "h" and not self.alt:
                    self.normal_grid, self.normal_pos = self.grid, (self.r, self.c)
                    self.grid = [[" "] * self.cols for _ in range(self.rows)]
                    self.alt = True
                    self.r = self.c = 0
                elif final == "l" and self.alt:
                    self.grid, self.r, self.c = self.normal_grid, *self.normal_pos
                    self.alt = False
        elif final == "s":
            self.saved = (self.r, self.c)
        elif final == "u":
            if self.saved:
                self.r, self.c = self.saved
        elif final == "r":
            pass

    def screen(self):
        return "\n".join("".join(row).rstrip() for row in self.grid)

# ── harness ───────────────────────────────────────────────────────────────────
def main():
    gw = subprocess.Popen([PY, os.path.join(HERE, "fakegw.py")],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    port = None
    for _ in range(100):
        line = gw.stdout.readline()
        if line.startswith("PORT"):
            port = int(line.split()[1])
            break
    if not port:
        print("fakegw failed to start"); sys.exit(1)

    rows, cols = 24, 100
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    proc = subprocess.Popen([HUI, "-url", f"ws://127.0.0.1:{port}/api/ws"],
                            stdin=slave, stdout=slave, stderr=slave,
                            preexec_fn=os.setsid)
    os.close(slave)
    vt = VT(rows, cols)

    def pump(timeout=1.0):
        buf = b""
        while True:
            r, _, _ = select.select([master], [], [], timeout)
            if not r:
                break
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            buf += chunk
            timeout = 0.05
        if buf:
            vt.handle(buf.decode("utf-8", "replace"))
            with open(os.path.join(HERE, "screen_raw.log"), "ab") as f:
                f.write(b"=== pump ===\n" + buf)
        return buf

    def snapshot(name):
        pump(0.5)
        print(f"===== {name} (cursor r={vt.r},c={vt.c}) =====")
        print(vt.screen())
        print()
        sys.stdout.flush()

    def send(s, delay=0.3):
        os.write(master, s.encode())
        time.sleep(delay)
        pump(0.3)

    time.sleep(1.5)
    pump(1.0)
    snapshot("01 after connect + approval prompt")

    send("1\r")
    snapshot("02 after approval answer (clarify prompt shown)")

    send("my answer\r")
    snapshot("03 after clarify answer (sudo prompt shown)")

    send("s3cret\r")
    snapshot("04 after sudo answer")

    send("hello")
    snapshot("05 after typing 'hello'")
    send("\x1b[13;2u")
    send("world")
    snapshot("06 after shift+enter + 'world'")
    send("\r")
    snapshot("07 after Enter (message sent)")

    send("\x1b[A")
    snapshot("08 after Up (history)")
    send("\x1b[B")
    snapshot("09 after Down (history)")

    os.write(master, b"\x03")
    for _ in range(20):
        if proc.poll() is not None:
            break
        time.sleep(0.25)
        pump(0.25)
    snapshot("10 after Ctrl+C")
    print("EXITED" if proc.poll() is not None else "STILL-RUNNING")
    if proc.poll() is None:
        proc.kill()
    proc.wait(timeout=5)
    gw.terminate()
    try:
        gw.communicate(timeout=5)
    except Exception:
        pass
    print("RAW-END")

if __name__ == "__main__":
    main()
