Hermes Unofficial Interface (HUI)
=================================

A standalone terminal client that talks to a remote Hermes gateway over WebSocket JSON-RPC. Zero Hermes code is installed on the client machine — it is a single executable that connects to a Hermes gateway running elsewhere.

What it does
------------
- Connects to a remote Hermes gateway (`ws://<host>:<port>/api/ws`)
- Creates/resumes a server-side session (history lives on the gateway)
- Sends messages via `prompt.submit` and streams assistant responses in real time
- Full-screen TUI: transcript streams in above, editable prompt below; double-buffered frame renderer (alt-screen, diff-based repaint) — fast even on Windows
- Supports slash commands (`/new`, `/reset`, `/status`, `/help`, etc.)
- Answers inline approval / clarify / sudo prompts from the gateway
- `𖦏` prompt on its own line; Enter sends; Shift+Enter inserts a newline into your draft (client-side only); Up/Down recall input history

Usage
-----

    hui -host cachyos-pc -token <t>
    hui -url ws://<remote-host>:<port>/api/ws?token=<t>

One of `-host` or `-url` is required.

- `-host <name>`: gateway host name (optionally `name:port`); the app fills in
  the protocol, the port (default: the gateway's own, 41841), and the path
- `-url <url>`: full WebSocket URL, if you prefer to spell it out
- `-token <t>`: the gateway auth token
- `-session <id>`: resume an existing session instead of starting a new one
- `-debug <file>`: write every wire frame to a file

Build
-----

    ./build.sh            # -> builds/hui (current platform, static)
    GOOS=windows GOARCH=amd64 ./build.sh   # -> builds/hui.exe (cross-compile)

Requires the Go toolchain (looked up in `PATH`, then `~/.local/opt/go`). Produces a single statically-linked executable into `builds/`; the build timestamp is embedded at link time so each build has a unique hash (see `/status`).

Windows 11: run `build.bat` in a terminal (produces `builds\hui.exe`, same static single-binary build). The Windows build uses the Windows Console API directly via `syscall` — no cgo, no dependencies: VT input/output, the UTF-8 codepage, win32-input-mode and the kitty keyboard protocol are enabled at startup, so the same ANSI rendering and key decoding work. Shift+Enter needs Windows Terminal 1.22+ (kitty keyboard protocol) or win32-input-mode support in any modern terminal.

Keybindings
-----------
- Type a message and press **Enter** to send it.
- **Shift+Enter** inserts a newline into your draft without sending.
- **Ctrl+Enter** same as Shift+Enter (fallback on terminals without kitty/win32 support).
- **Up/Down** recall previously submitted messages (input history).
- **Ctrl+A/E** home/end, **Ctrl+U** clear line, **Ctrl+W** delete word, **Ctrl+C** quit.
- Type `/new`, `/reset`, `/status`, `/help`, `/exit` for commands. Unknown `/x` is sent to the agent verbatim.

Testing
-------
`tools/` holds:
- `fakegw.py` — scripted fake gateway for approval/clarify/sudo prompt flows without touching a real gateway
- `pty_test*.py` — PTY harnesses against the live gateway (streaming, slash commands, Shift+Enter, /new, Ctrl+C)
- `screen_test.py` — runs the real binary in a PTY and replays the bytes onto a virtual terminal grid, printing the actual rendered screen at each step (this is what verified the TUI)
- `hui_bin.py` — resolves the binary from `builds/` for the harnesses

Run them with `python3 tools/<test>.py` from the project root.

Requirements
------------
- Remote Hermes gateway running and reachable on the network
- Gateway serving WebSocket at `/api/ws` on a fixed port
- The client machine only needs network access to that URL — no Hermes install, no Python, no Hermes config files, no Hermes libraries.

Notes
-----
- Session state stays on the server. The client sends only new text per turn plus the `session_id` — it never resends the full chat.
- `/new` creates a brand-new session on the gateway; the previous session is closed server-side.
- The build timestamp is embedded so each build has a unique hash.
- Full protocol and engineering details live in `SPEC.md`.
