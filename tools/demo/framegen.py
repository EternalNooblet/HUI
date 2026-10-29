#!/usr/bin/env python3
"""Fake HUI terminal session -> PNG frames for a demo video.

Nothing here talks to a real app or gateway: the screen state is scripted.
Timeline follows narration durations in durations.json (one per segment).
"""
import json, math, os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FPS = 15
COLS = 110
ROWS = 30
FONT_SIZE = 19
CELL_H = 22
CANVAS_W, CANVAS_H = 1280, 720
BG = (16, 18, 23)
PAD = 0.6  # trailing silence per segment

MONO = "/usr/share/fonts/TTF/DejaVuSansMono.ttf"
MONO_BOLD = "/usr/share/fonts/TTF/DejaVuSansMono-Bold.ttf"
BAMUM = os.path.join(HERE, "NotoSansBamum-Regular.ttf")
PROMPT_GLYPH = "\U0001698F"  # 𖦏

from PIL import ImageFont as _IF
CELL_W = _IF.truetype(MONO, FONT_SIZE).getlength("M" * 100) / 100
MARGIN_X = (CANVAS_W - COLS * CELL_W) / 2
MARGIN_Y = (CANVAS_H - ROWS * CELL_H) / 2

STYLES = {
    "plain": ((215, 220, 228), False),
    "bold": ((242, 245, 250), True),
    "dim": ((124, 132, 142), False),
    "cyan": ((88, 180, 207), False),
    "yellow": ((230, 184, 102), False),
    "red": ((224, 105, 122), False),
}

SPECIAL = "—·✓›─"  # glyphs used in transcript text; verified against the mono font

def load_fonts():
    from fontTools.ttLib import TTFont
    cmap = TTFont(MONO, fontNumber=0).getBestCmap()
    missing = [ch for ch in SPECIAL if ord(ch) not in cmap]
    if missing:
        print(f"WARNING mono font lacks {missing}; substituting ascii fallbacks")
    subs = {"—": "-", "·": ".", "✓": "ok", "›": ">", "─": "-"}
    for ch in missing:
        SUB[ch] = subs[ch]
    fonts = {}
    for style, (color, bold) in STYLES.items():
        fonts[style] = ImageFont.truetype(MONO_BOLD if bold else MONO, FONT_SIZE)
    fonts["glyph"] = ImageFont.truetype(BAMUM, FONT_SIZE)
    return fonts

SUB = {}

def sub(text):
    for ch, rep in SUB.items():
        text = text.replace(ch, rep)
    return text

def wrap(text, cols):
    rows = []
    cur = ""
    w = 0
    for ch in text:
        if w + CELL_W > cols * CELL_W and cur:
            rows.append(cur)
            cur, w = "", 0
        cur += ch
        w += CELL_W
    rows.append(cur)
    return rows

class State:
    def __init__(self):
        self.lines = []          # (text, style) transcript lines
        self.rows = []           # wrapped transcript rows (style carried)
        self.label = ""
        self.draft = ""
        self.cursor_visible = True
        self.prompt_active = True
        self.rewrap()

    def add_line(self, text, style):
        text = sub(text)
        for r in wrap(text, COLS):
            self.rows.append((r, style))
        self.lines.append((text, style))

    def rewrap(self):
        self.rows = []
        for text, style in self.lines:
            for r in wrap(text, COLS):
                self.rows.append((r, style))

SESSION_ID = "4a9f3c7d1e2b8560f0d7a9c4"

def build_events(seg_starts, seg_durs):
    """Absolute-time event list; events = (t, kind, payload)."""
    ev = []
    def at(seg, off, kind, payload=None):
        ev.append((seg_starts[seg] + off, kind, payload))

    # S1: empty screen, prompt glyph alone
    at(0, 0.0, "label", PROMPT_GLYPH + " ")

    # S2: connect + session lines
    at(1, 0.4, "line", ("connected ws://cachyos-pc:9119/api/ws", "dim"))
    at(1, 1.6, "line", (f"new session · {SESSION_ID} · type /help for commands", "dim"))

    # S3: type a message
    msg = "hello hui — what can you do?"
    for i, ch in enumerate(msg):
        at(2, 0.5 + i * 0.12, "type", ch)

    # S4: submit + streamed reply
    at(3, 0.3, "submit", None)
    reply = ("Hermes runs server-side on the gateway. Ask it to run commands, edit "
             "files, search the web, or start new sessions. This client just renders "
             "the stream — nothing is stored here.")
    words = reply.split(" ")
    for i, w in enumerate(words):
        at(3, 0.5 + i * 0.2, "stream", w + (" " if i < len(words) - 1 else ""))

    # S5: tool lines + usage (appended into the streamed tail? no — new lines after turn)
    at(4, 0.5, "line", ("  · terminal run backend tests — 2 passed (1.4s)", "dim"))
    at(4, 1.6, "line", ("  · 1240 in / 412 out", "dim"))
    at(4, 2.4, "line", ("  · session titled: backend test run", "dim"))

    # S6: /status
    for i, ch in enumerate("/status"):
        at(5, 0.5 + i * 0.12, "type", ch)
    at(5, 1.7, "submit_cmd", None)
    at(5, 2.0, "line", ("── hui dev ──", "cyan"))
    at(5, 2.3, "line", ("gateway  ws://cachyos-pc:9119/api/ws", "dim"))
    at(5, 2.6, "line", (f"session  {SESSION_ID}", "dim"))
    at(5, 2.9, "line", ("model    hermes-4-mini", "dim"))
    at(5, 3.2, "line", ("profile  default", "dim"))
    at(5, 3.5, "line", ("runtime  describe failed: method not found (code -32601)", "dim"))

    # S7: approval prompt inline, answered with a choice number
    at(6, 0.4, "line", ("── approval ────────", "cyan"))
    at(6, 0.7, "line", ("rm -rf ./builds", "plain"))
    at(6, 1.0, "line", ("approval requested", "plain"))
    at(6, 1.3, "line", ("[1] once  [2] deny  ", "plain"))
    at(6, 1.6, "line", ("(1-2 or the choice name, Enter to skip)", "plain"))
    for i, ch in enumerate("1"):
        at(6, 2.1 + i * 0.12, "type", ch)
    at(6, 2.8, "answer", "✓ once")
    at(6, 3.4, "line", ("  · terminal rm -rf ./builds — done (0.3s)", "dim"))
    done = "Removed the stale builds directory."
    for i, w in enumerate(done.split(" ")):
        at(6, 3.8 + i * 0.2, "stream", w + (" " if i < len(done.split(" ")) - 1 else ""))

    # S8: /exit, bye, terminal restored
    for i, ch in enumerate("/exit"):
        at(7, 0.5 + i * 0.12, "type", ch)
    at(7, 1.6, "submit_cmd", None)
    at(7, 1.9, "line", ("bye", "dim"))
    at(7, 3.2, "restore", None)

    ev.sort(key=lambda e: e[0])
    return ev

def apply_event(st, kind, payload):
    if kind == "label":
        st.label = payload
    elif kind == "line":
        st.add_line(payload[0], payload[1])
    elif kind == "type":
        st.draft += payload
    elif kind == "stream":
        # streaming text accumulates into a live tail line
        if not st.lines or st.lines[-1][1] != "stream":
            st.lines.append(("" , "stream"))
        text, _ = st.lines[-1]
        st.lines[-1] = (text + payload, "stream")
        st.rewrap()
    elif kind == "submit":
        echo = "› " + st.draft.replace("\n", " ⏎ ") if st.draft else ""
        st.draft = ""
        if echo:
            st.add_line(echo, "bold")
    elif kind == "submit_cmd":
        st.draft = ""
    elif kind == "answer":
        st.draft = ""
        st.add_line(payload, "dim")
    elif kind == "restore":
        st.lines, st.rows = [], []
        st.label = "$ "
        st.prompt_active = True

def draw_frame(st, fonts, t):
    img = Image.new("RGB", (CANVAS_W, CANVAS_H), BG)
    d = ImageDraw.Draw(img)

    prompt_text = st.label + st.draft
    p_rows = wrap(prompt_text, COLS)
    prompt_rows = len(p_rows) if st.prompt_active else 0
    avail = ROWS - prompt_rows

    body = st.rows[-avail:] if len(st.rows) > avail else st.rows
    top = avail - len(body)
    for i, (text, style) in enumerate(body):
        color, bold = STYLES["plain" if style == "stream" else style]
        y = MARGIN_Y + (top + i) * CELL_H
        d.text((MARGIN_X, y), text, font=fonts["bold" if bold else "plain"], fill=color)

    base_row = MARGIN_Y + avail * CELL_H
    for i, text in enumerate(p_rows):
        # label glyph drawn from Bamum font, rest from mono
        x = MARGIN_X
        if i == 0 and st.label.startswith(PROMPT_GLYPH):
            d.text((x, base_row + i * CELL_H), PROMPT_GLYPH, font=fonts["glyph"], fill=(215, 220, 228))
            x += CELL_W
            rest = text[len(PROMPT_GLYPH):]
            d.text((x, base_row + i * CELL_H), rest, font=fonts["plain"], fill=(215, 220, 228))
        else:
            d.text((x, base_row + i * CELL_H), text, font=fonts["plain"], fill=(215, 220, 228))

    # cursor block at the draft caret (blinks when draft is empty)
    caret = len(st.label) + len(st.draft)
    col = caret % COLS
    prow = caret // COLS
    if st.prompt_active and (st.draft or int(t * 2) % 2 == 0):
        cx = MARGIN_X + col * CELL_W
        cy = base_row + prow * CELL_H
        d.rectangle([cx, cy + 2, cx + CELL_W - 1, cy + FONT_SIZE], fill=(215, 220, 228))
    return img

def main():
    durs = [float(x) for x in open(os.path.join(HERE, "durations.json"))]
    seg_durs = [d + PAD for d in durs]
    starts = []
    acc = 0.0
    for d in seg_durs:
        starts.append(acc)
        acc += d
    total = acc
    events = build_events(starts, seg_durs)
    fonts = load_fonts()

    outdir = os.path.join(HERE, "frames")
    os.makedirs(outdir, exist_ok=True)
    n = math.ceil(total * FPS)
    ei = 0
    st = State()
    for f in range(n):
        t = f / FPS
        while ei < len(events) and events[ei][0] <= t:
            apply_event(st, events[ei][1], events[ei][2])
            ei += 1
        img = draw_frame(st, fonts, t)
        img.save(os.path.join(outdir, f"f{f:05d}.png"))
    print(f"frames={n} total={total:.2f}s")

if __name__ == "__main__":
    main()
