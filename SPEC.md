HUI — Hermes Unofficial Interface: Full Specification
=====================================================

Status: implemented and verified against a live gateway (ws://127.0.0.1:9119/api/ws).
This document is the complete spec: wire protocol, terminal behavior, build
system, and every lesson learned while building it. The README.md stays the
user-facing quickstart; this file is the engineering reference.

1. Overview
-----------

HUI is a standalone terminal client for a remote Hermes gateway. Constraints:

- Zero Hermes code on the client machine. The deliverable is ONE executable
  per platform (`hui` on Linux/macOS, `hui.exe` on Windows), no Python, no
  config files, no Hermes libraries.
- Single Go binary, STDLIB ONLY. No third-party modules. The WebSocket layer
  is a hand-rolled RFC 6455 client in `ws.go` (~340 lines).
- Session state lives on the gateway. The client sends only new text per turn
  plus the `session_id` — it never resends chat history.
- The 𖦏 glyph is the input prompt. It appears on its own line after every
  response.

Source layout
~~~~~~~~~~~~~

    build.sh          build script (CGO_ENABLED=0, embeds build timestamp,
                      outputs into builds/)
    build.bat         Windows build script (produces builds/hui.exe)
    main.go           wire types, gateway connection, app state machine, events,
                      prompts (approval/clarify/sudo/secret), slash commands,
                      reconnect/shutdown
    ws.go             RFC 6455 WebSocket client (handshake, masking, fragmentation,
                      ping/pong, close), ws:// and wss://
    keys.go           stdin key decoder (raw bytes -> key events), kitty protocol,
                      win32-input-mode decoder
    render.go         double-buffered frame renderer (alt-screen, diff-based)
    term_linux.go     raw-mode termios via syscall (linux build tag)
    term_darwin.go    same for darwin
    term_windows.go   raw mode via the Windows Console API (windows build tag)
    builds/           build output (hui / hui.exe), kept out of the source tree
    tools/            mint_ticket.py, fakegw.py, hui_bin.py, pty_test*.py,
                      screen_test.py (test harnesses)

2. Transport
------------

WebSocket, RFC 6455, text frames only (binary frames are refused by the
client). Path is `/api/ws` on the gateway's fixed port.

Handshake
~~~~~~~~~
- `Sec-WebSocket-Version: 13`, random 16-byte `Sec-WebSocket-Key`, standard
  `Sec-WebSocket-Accept` verification (SHA-1 of key + GUID `258EAFA5-E914-...`,
  base64 — RFC 6455 mandates SHA-1 here, not a security choice).
- `Sec-WebSocket-Protocol` is used for AUTH, see §3.
- Any HTTP status other than 101 fails with a clear error.
- Default port: 80 for ws://, 443 for wss:// if no port in the URL.

Framing
~~~~~~~
- Client frames are ALWAYS masked (RFC 6455 requirement); fresh mask per frame.
- Server→client messages may be fragmented; the client reassembles until FIN.
- Ping (0x9) is answered with pong (0xA) carrying the same payload.
- Max message size: 16 MiB (matching the gateway's own receive cap). Larger
  frames/messages abort the connection.
- Close: client sends a masked empty close frame (0x88 0x80 + 4 zero mask
  bytes) with a 2s write deadline, then closes the TCP socket.

3. Authentication
-----------------

Two paths, chosen by how the gateway is reachable:

- TICKETED (loopback / same-host): the client passes a one-use ticket via
  subprotocol. The subprotocols offered are
  `["hermes-gateway-v1", "hermes-gateway-ticket.<ticket>"]`.
  The ticket is minted by the gateway's OWNER-ONLY control socket
  (`~/.hermes/gateway.sock`, gated by SO_PEERCRED — same-user peers only):
  see `tools/mint_ticket.py`. One use, 30 s validity. The user-facing gateway
  CLI uses the same mechanism (`-ticket` flag on the local `hermes` CLI).
- TOKEN (remote / non-loopback bind): the gateway gates remote clients. A
  client connects with `?token=<dashboard session token>` in the URL, or the
  gateway operator sets `HERMES_GATEWAY_ALLOW_PUBLIC_TOKEN_AUTH=1` to allow
  token auth without pre-registration. (Exact env var name: check
  `hermes gateway --help` / the gateway source before relying on it.)
  HUI exposes this two ways: `-token <t>` (the app appends `?token=` to
  whatever URL it resolves; an explicit `token=` already in `-url` wins) and
  `-host <name>[:<port>]` (a bare host name is expanded to
  `ws://<host>:<port>/api/ws` with a compiled-in default port for the
  owner's gateway; `main.defaultGatewayPort`). `-url` and `-host` are
  mutually exclusive.

Practical rule verified on this machine: a loopback connection without a
ticket is refused; a non-loopback bind (0.0.0.0:9119) is gated for remote
clients. A non-loopback URL with an empty token fails auth.

4. Wire protocol (JSON-RPC 2.0)
-------------------------------

One JSON-RPC object per line, newline-delimited, inside WebSocket text frames.
A single WS message may carry several lines; the client splits on `\n`, skips
blank lines, and ignores unparseable lines (does not kill the connection).
Gateway's own client does the same — this is the established framing, not a
guess.

Envelope
~~~~~~~~
- Request:  `{"jsonrpc":"2.0","id":N,"method":"...","params":{...}}`
- Response: `{"jsonrpc":"2.0","id":N,"result":{...}}` or `{"error":{...}}`
- Event:    `{"jsonrpc":"2.0","method":"event","params":{"type":"...","payload":{...}}}`
- Server request: like a request but with a string id `srq-<...>`; the client
  MUST answer it with a matching-id result frame.

Connection lifecycle
~~~~~~~~~~~~~~~~~~~~
1. Dial + upgrade.
2. The gateway sends `event gateway.ready` right after accept. The client
   waits up to 15 s; absence of gateway.ready = connect failure.
3. `session.create` (new) or `session.resume` (existing) — see §5.
4. Heartbeat loop: `gateway.ping` every 15 s (id -1). If nothing is received
   for 45 s, the socket is considered dead and the connection is closed
   locally (the gateway answers within one send deadline, so 45 s silence
   means a dead peer).
5. On exit or /new: `session.detach`.

Client→server RPCs (all used by HUI)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

gateway.ping (heartbeat)
    params: none. Answer is ignored.

session.create
    params: `{"request_id": "<random-hex>", "source": "cli"}`
    result: `{"session_id": "...", "subscription_id": "...", "info": {...},
             "prompts": [...]}` — `info` carries `model`, `profile_name`.

session.resume
    params: `{"session_id": "...", "display_projection": true}`
    result: same shape plus `display_messages` (or `messages`): rows of
    `{"role":"user"|"assistant","text":"...", "name":"...", "context":"..."}`.
    Rows with `name` set are tool/aux events rendered as dim `· name context`.
    `display_projection: true` asks for the rendered history.
    Also returns any pending `prompts` queue entries so prompts that arrived
    while the client was away are restored.

prompt.submit
    params: `{"session_id": "...", "text": "...", "submission_id":
             "<random-hex>", "queued": true}`
    Multi-line text: real `\n` inside the text (from Shift+Enter). Verified
    on the wire. The result arrives before the stream starts; streaming then
    arrives as events. `queued: true` lets the gateway queue the turn if the
    session is busy.

session.detach
    params: `{"session_id": "...", "subscription_id": "...", "end_reason": "..."}`
    end_reason values used: `cli_close` (client quitting) and `new_session`
    (/new, /reset). Timeout 5 s — never block shutdown on it.

approval.respond
    params: `{"session_id": "...", "execution_generation": N, "prompt_id":
             "...", "choice": "once"|"deny"|<choice name>}`
    Used for queue prompts (kind "approval"). `execution_generation` comes
    from the prompt payload.

clarify.respond
    params: `{"session_id": "...", "execution_generation": N, "prompt_id":
             "...", "answer": "<text>"}`
    Used for queue prompts (kind "clarify").

runtime.describe (via /status)
    params: `{}`. result: `{"capabilities": [...], "instance_id": "..."}`.

Server→client events (params.type)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Rendered by HUI:
    message.delta       streamed assistant text in params.payload.text
    message.complete    turn end; payload.text/status/warning/usage/response_reused
    tool.complete       payload.name/summary/result_text/duration_s -> dim line
    status.update       payload.text -> dim line
    error               payload.text|message|error -> red `! ...` line
    notice              payload.text -> dim line
    session.title       payload.title -> dim line
    approval.request    payload = queue prompt (kind approval) -> prompt UI
    clarify.request     payload = queue prompt (kind clarify) -> prompt UI
    approval.settled / clarify.settled
                        payload.prompt_id -> remove the prompt from the UI
    request.cancel      payload.id -> remove prompt
    session.replay_gap  warn: event gap; reconnect to resync
    session.reclaimed   warn: gateway reclaimed the session

Ignored by design (they exist on the wire; HUI stays silent):
    reasoning.delta, thinking.delta, reasoning.available, tool.start,
    tool.generating, tool.progress, tool.output_risk, message.start,
    message.interim, session.usage, skin.changed, gateway.ready (after first)

CRITICAL message.delta semantics (learned the hard way):
- `payload.text` containing a NON-EMPTY string = a chunk of assistant text.
- `payload.text` ABSENT or EMPTY string = segment boundary: the reply box
  closes (paragraph break), NOT empty text. Treat presence/absence, not
  string emptiness, as the discriminator. HUI checks `has && (!isStr || s=="")`
  first — a missing key closes the box; an empty string is also a boundary.

message.complete semantics:
- `payload.text` is the final text. If the client already streamed the same
  text, do NOT print it again (compare with what was accumulated). If
  `response_reused` is true, the final text is a cache hit and printing it
  would duplicate the transcript.
- `payload.status`: "", "ok", "complete", "completed", "done" = success;
  anything else prints a yellow `· turn ended: <status>`.
- `payload.usage` (map with input/output token counts) prints `· N in / N out`.

Server requests (srq-*)
~~~~~~~~~~~~~~~~~~~~~~~
The gateway sends JSON-RPC requests with ids prefixed `srq-`. HUI answers
these inline with a result frame:

    approval   -> result {"choice": "<once|deny|...>"}  (default choices once/deny)
    clarify    -> result {"answers": {"<qid>": "<answer>"|null}}
                  A clarify batch may carry several questions; each is
                  answered on its own screen, then the whole batch is sent
                  once: result frame {"answers": {...}}.
    sudo       -> result {"value": "<password>"}  (secret input, masked)
    secret     -> result {"value": "<text>"}      (prompt/env_var in params)
    vault.unlock_prompt / vault.code
               -> result {"value": "<text>"}      (masked if unlock_prompt)

    anything else -> JSON-RPC error -32601 "not supported by this client"
                     ALWAYS answer with an error rather than leaving the
                     request unanswered: an unanswered srq blocks the gateway's
                     turn until its timeout path fires.

5. Session lifecycle and reconnect
----------------------------------

- Startup: connect -> gateway.ready -> session.create (or resume if -session
  given). History is rendered from the snapshot's `display_messages`.
- /new, /reset: `session.detach` with end_reason=new_session, then a fresh
  session.create. The old session is closed server-side.
- Reconnect: on socket death (heartbeat timeout or read error), the client
  reconnects up to 5 times with 2 s backoff, resuming the SAME session_id
  (session.resume with display_projection=false). Resume failure on the
  reconnect path does NOT silently create a new session — it errors out and
  keeps the session id so the user can resume manually (`-session <id>`).
  The "reattach to session" error text is matched; on it, the client quits
  instead of looping.
- Quit (Ctrl+C, Ctrl+D, /exit, /quit, EOF): detach with end_reason=cli_close,
  erase the draft, restore the terminal, exit 0. Second Ctrl+C during cleanup
  force-exits with 130. A single cleanup flag (`cleaned`) guards against the
  shutdown path running twice — an early bug: the signal handler and the main
  loop could both run the cleanup and clobber the restored terminal.

Timings
~~~~~~~
    readyTimeout       15 s (gateway.ready)
    defaultRPCTimeout  30 s (generic RPCs)
    attachRPCTimeout   120 s (session.create/resume/describe)
    detach             5 s
    heartbeat          15 s ping / 45 s silence = dead
    reconnect          5 attempts, 2 s backoff

6. Terminal behavior
--------------------

Raw mode
~~~~~~~~
- termios is set raw via ioctl (TCGETS/TCSETS): ICANON, ECHO, ISIG, IEXTEN
  off, OPOST off, CS8, VMIN=1, VTIME=0. Ctrl+C arrives as byte 0x03 and is
  handled by the app (so it can detach cleanly) — ISIG is OFF, no SIGINT
  from the kernel.
- Non-TTY stdin (pipe mode): no raw mode; a line scanner feeds submitted
  lines. Pipe mode still renders streamed output.

Raw mode on Windows 11 (term_windows.go)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
termios has no Windows equivalent; term_windows.go goes through kernel32.dll
via syscall.NewLazyDLL — stdlib only, no cgo, no external modules:

- stdin: ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT off;
  ENABLE_VIRTUAL_TERMINAL_INPUT on; ENABLE_EXTENDED_FLAGS on with
  ENABLE_QUICK_EDIT_MODE off (quick-edit would block reads on a click).
- THE KEY TRICK: ENABLE_VIRTUAL_TERMINAL_INPUT makes the Windows console
  deliver the same VT/CSI byte sequences a Unix terminal sends. keys.go's
  decoder and render.go's ANSI output therefore work unchanged — only the
  raw-mode switches are platform-specific.
- Ctrl+C arrives as byte 0x03 exactly like on Unix (processed input off), so
  the app detaches cleanly; ISIG-equivalent behavior is identical.
- stdout: ENABLE_VIRTUAL_TERMINAL_PROCESSING on so the renderer's ANSI
  escapes render (conhost Win10+ and Windows Terminal both support this).
- Console codepages switched to UTF-8 (65001) at startup so multi-byte runes
  (𖦏, CJK) display correctly; both saved and restored on exit.
- terminalSize: GetConsoleScreenBufferInfo. On Windows the INPUT handle is
  not a screen buffer, so the call falls back to the stdout handle; 24x80 if
  both fail.
- Everything is saved in termSaver and restored by restore() — the same
  shutdown path used on Unix.
- Shift+Enter works only where the terminal speaks the kitty protocol
  (Windows Terminal 1.22+); otherwise Alt+Enter (ESC CR) is the fallback.
  This matches the Unix behavior.

Key decoding (keys.go)
~~~~~~~~~~~~~~~~~~~~~~
- Escape sequences are decoded chunk-wise: a terminal delivers a complete
  sequence in one read; a partial tail is carried to the next read.
- Ctrl+C (0x03) quit, Ctrl+D (0x04) quit, Ctrl+U (0x15) clear line,
  Ctrl+W (0x17) delete word, Ctrl+A (0x01) home, Ctrl+E (0x05) end.
- Backspace: 0x7f or 0x08.
- CSI sequences: arrows A/B/C/D, Home H/F, ~-params 1/7 Home, 3 Delete,
  4/8 End.
- KITTY KEYBOARD PROTOCOL: HUI enables `CSI > 1 u` (disambiguate mode) on
  start and disables with `CSI < u` on exit. In this mode Enter is
  `CSI 13 u` and Shift+Enter is `CSI 13;<mods>u` where the mods field is
  (bitfield+1): bit0 = shift, bit1 = alt. HUI maps:
    13;... shift set, ctrl not set -> newline into the draft (Shift+Enter)
    13;...   alt set               -> newline fallback (Alt+Enter)
    13 plain                       -> send
    57414 plain (keypad enter)     -> send
  Alt+Enter also works without the kitty protocol via ESC CR.
- Alt+rune: the rune is treated as plain input.
- Double-ESC: swallow one, ignore the rest.
- SS3 `O A/B/C/D`: arrows.
- Multi-byte UTF-8 runes that straddle a read boundary are buffered until
  complete (or EOF).

Renderer (render.go)
~~~~~~~~~~~~~~~~~~~~
- Double-buffered full-screen frame renderer, not append-only inline editing:
  the terminal is owned via the alternate screen buffer (`\x1b[?1049h` on
  start, `\x1b[?1049l` + a transcript-tail dump on exit).
- The transcript is a logical line list; lines are wrapped to the current
  width (ANSI escapes preserved, active SGR re-applied across wrap points).
  The input line (label + draft) is a fixed footer region.
- flush() computes the desired frame (transcript tail + prompt rows), diffs it
  against the last painted frame and emits only changed rows via absolute
  cursor positioning (`\x1b[<row>;1H` + `\x1b[K`), then positions the edit
  cursor. Cursor position is computed from the model every frame — never
  guessed from prior writes — so wrapping drift cannot accumulate or erase
  transcript lines (the old tracked-cursor renderer wiped the transcript and
  duplicated the prompt on Windows; this architecture cannot).
- Terminal resize is detected per flush (terminalSize) and triggers a rewrap.
- Kit/win32 protocol pops and the alt-screen exit happen in renderer.close(),
  which restoreTerm() calls on every exit path (also on Ctrl+C via os.Exit
  paths that skip main's defers).
- Wide-runewidth handling: CJK/fullwidth = 2 cells, combining/zero-width = 0.
- Secret prompts: echo off, input renders as `*`.

Prompt UI (approval/clarify/sudo/secret)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Prompts queue FIFO. The first one is shown; the rest wait. When the gateway
settles a prompt (approval.settled / clarify.settled / request.cancel), it is
removed from the queue and the next one is shown.

- Approval: header `── approval ...`, detail (command/description), label
  `approval requested`, choices listed as `[1] once  [2] deny`, hint
  `(1-N or the choice name, Enter to skip)`. Input mapping: number -> choice,
  y/yes -> once, n/no -> deny, case-insensitive choice name, anything else
  passed to the server verbatim (the server refuses unknown choices
  explicitly). Empty input = skip (choice "" → prompt removed).
- Clarify: header `── clarify ...`, question text, optional choices, hint
  `(type an answer, a choice number, or a choice name; Enter to skip)`.
  srq clarify batches walk through each question, locking answers into
  `batchANS[qid]` (nil = skipped), then answer the whole batch in one frame.
- Value prompts (sudo/secret/vault): label, masked input, hint
  `(type it here; Enter to skip)`.

Keybindings (app loop)
~~~~~~~~~~~~~~~~~~~~~~
    Enter            submit the draft (or answer the open prompt)
    Shift+Enter      insert a newline in the draft (client-side only)
    Alt+Enter        same as Shift+Enter (fallback)
    Ctrl+C / Ctrl+D  quit
    Ctrl+U           clear the draft
    Ctrl+W           delete word left
    Ctrl+A / Ctrl+E  home / end
    Backspace / Delete / Left / Right
    Up / Down        reserved (no history yet)
    Ctrl+C also cancels a draft while a prompt is up (quit wins)

Slash commands
~~~~~~~~~~~~~~
    /new /reset   detach (new_session) + fresh session
    /status       client build stamp, gateway URL, session id, model,
                  profile, runtime.describe caps/instance
    /help         command list
    /exit /quit   quit
    unknown /xxx  prints a dim note AND is sent to the agent verbatim

7. Build
--------

`./build.sh`:

    CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.buildStamp=$TS" -o builds/hui .

- All builds land in `builds/` (auto-created); the source tree stays clean.
- CGO_ENABLED=0 -> fully static binary (verified: `file` reports "statically
  linked, stripped", 5.4 MB vs 8.4 MB dynamic+debug).
- `-X main.buildStamp=$TS` embeds a UTC build timestamp (unique per build,
  shown by /status).
- Go toolchain looked up in PATH, then `~/.local/opt/go/bin/go`.
- Go 1.22+ semantics; no cgo, no vendored deps (go.mod has zero requires).

Windows 11
~~~~~~~~~~
- `build.bat` (run from cmd/PowerShell in the project dir): finds go.exe in
  PATH, then %ProgramFiles%\Go and %LOCALAPPDATA%\Programs\Go; takes the
  timestamp from PowerShell ([DateTime]::UtcNow); builds builds\hui.exe with
  the same flags (CGO_ENABLED=0, -trimpath, -s -w, buildStamp).
- Cross-compile from Linux/macOS: `GOOS=windows GOARCH=amd64 ./build.sh`
  (build.sh switches the output name to hui.exe when GOOS=windows, and
  build.sh also produces hui.exe when run under Git Bash/MSYS on Windows).
- The output is a PE32+ console executable for x86-64, statically linked,
  stripped. It only imports always-present system DLLs (kernel32, ws2_32).

8. Test tooling (tools/)
------------------------

- mint_ticket.py  — mints a one-use gateway session ticket via the control
                    socket (same-user only, SO_PEERCRED gate): identify ->
                    profile/instance -> session-ticket verb. Prints JSON with
                    ticket/api_origin/profile_id.
- fakegw.py       — a scripted fake gateway for testing the prompt flows
                    without a real gateway: speaks the wire protocol
                    (newline-delimited JSON-RPC) and issues approval/clarify/
                    sudo srq prompts, checks the client's answers.
- hui_bin.py      — resolves the binary from builds/ (fallback: legacy ./hui)
                    so the harnesses do not hardcode a path. Override with
                    HUI_BIN.
- pty_test*.py    — PTY harnesses that drive the real binary: key decoding
                    (Shift+Enter, kitty protocol), slash commands, Ctrl+C
                    shutdown, and the fake-gateway prompt flows.
- screen_test.py  — the visual TUI verifier: runs the real binary in a PTY,
                    captures every byte, and replays them onto a virtual
                    terminal grid (rows/cols/cursor, ESC[K/J, alt-screen 1049)
                    printing the actual rendered screen at each interaction
                    step (connect, prompts, typing, multiline draft, history,
                    Ctrl+C exit). This is what verified the renderer — plain
                    substring greps on output are NOT sufficient for a TUI.

Verification performed (live gateway + fake gateway + PTY):
- connect -> gateway.ready -> session.create -> streamed reply -> usage
- /status, /help, /exit, session.detach end_reason=cli_close on the wire
- /new -> fresh session, previous detached with end_reason=new_session
- Shift+Enter: real `\n` inside one prompt.submit text
- Ctrl+C: clean quit, single detach, terminal restored
- approval/clarify/sudo answered with the right result shapes
  ({"choice":"once"}, {"answers":{"q1":…}}, {"value":…})

Windows 11 verification:
- Cross-compile: GOOS=windows GOARCH=amd64 produces a PE32+ x86-64 console
  executable (static, stripped); go vet clean for the windows target.
- Linux regression suite rerun after the Windows changes: /new + Ctrl+C
  (detach reasons new_session + cli_close) and the fake-gateway prompt flows
  all PASS — the shared code paths are untouched.
- Not yet run on native Windows 11 hardware: the console-API switches
  (VT input/output, codepage, screen-buffer size) are implemented per
  Microsoft docs but await a real Windows session to confirm.

Known gaps (not yet live-tested):
- approval.respond / clarify.respond against a REAL gateway approval queue
  (the fake gateway covers framing + UI only).
- wss:// (TLS path) not exercised against a real gateway.

9. Lessons learned (build-time)
-------------------------------

- READ THE GATEWAY SOURCE FIRST. The protocol (`/api/ws`, subprotocol
  `hermes-gateway-v1`, newline-delimited JSON-RPC, `prompt.submit`,
  `session.*`, srq ids) was recovered from `~/.hermes/hermes-agent/gateway/`
  source rather than guessed. Session_api.py / session_controls.py are the
  authoritative references for request shapes.
- terminalSize returns (rows, cols) — the assignment MUST be
  `h, w := terminalSize(...)`. Swapping them made the renderer think a 24x100
  terminal was 100 rows tall: it cleared rows 1-100 and wrapped at 24 cols.
  This survived all substring-based tests because none of them looked at the
  actual screen.
- prompt.submit must be fire-and-forget (use gateway.send, never
  rpcSession/call): waiting for its receipt blocks the render/key loop on a
  slow or non-responding gateway, which made every keypress appear delayed and
  Ctrl+C dead. The receipt carries nothing the UI uses — the reply streams in
  as events. Never block the UI thread on a network RPC.
- Screen-model testing is mandatory for a TUI: tools/screen_test.py runs the
  real binary in a PTY, captures every byte, replays it onto a virtual
  terminal grid (rows, columns, cursor, ESC[K/J, alt-screen) and prints the
  actual rows. Substring greps on output are NOT enough — they passed while
  the screen was garbage. The model must treat an omitted/zero param on J/K
  as 0 (clear from cursor), and must handle alt-screen ?1049h/l correctly.
- Loopback auth is via the control socket, not a password. The gateway mints
  one-use tickets over `~/.hermes/gateway.sock` (owner-only). Remote binds
  are gated; token auth needs ?token= or an env var.
- message.delta: absent/empty `text` = segment boundary, NOT empty content.
  Getting this wrong inserts phantom paragraph breaks.
- message.complete: compare final text with what was streamed; duplicate
  printing of the same final text is a real failure mode (the gateway
  re-sends full text in some paths).
- The kitty keyboard protocol is the clean way to distinguish Shift+Enter
  from Enter in a raw terminal; Alt+Enter (ESC CR) is the fallback for
  terminals without it. Enable `CSI >1u` at startup, `CSI <u` at exit.
- Raw mode means Ctrl+C is a byte, not a signal: the app handles 0x03 itself
  so it can detach cleanly. ISIG must be off.
- Shutdown needs a single-flight flag. The signal-handler cleanup and the
  main-loop cleanup can race; without a `cleaned` guard the terminal gets
  restored twice or the detach fires twice.
- Always answer srq requests, even unsupported ones (-32601). An unanswered
  server request blocks the gateway's turn until its timeout path resolves.
- Reconnect resume: on reconnect, resume the same session id; if resume
  fails, fail loudly and keep the session id — do NOT silently create a new
  session (that would fork the user's history).
- Session.detach has a 5s timeout; never let shutdown block on it.
- Heartbeat: the gateway answers ping within one send deadline, so 45 s of
  silence is a reliable dead-socket signal. Cheaper and more honest than
  TCP-level probes.
- Verifying a terminal client requires a PTY, not a pipe: key decoding
  (kitty CSI u sequences), redraws, and the prompt glyph only behave
  correctly under a real PTY. pty_test*.py harnesses proved Shift+Enter
  wiring and the Ctrl+C shutdown path.
- A fake gateway (fakegw.py) is the fastest way to iterate on prompt flows
  (approval/clarify/sudo) without burning real model turns or polluting the
  user's session history on the gateway.
- Build for static from the start: CGO_ENABLED=0 + -trimpath + -s -w gives
  one file that runs anywhere (no libc dependency, no debug info).
- Tools that get installed on the fly (a Go toolchain fetched to
  ~/.local/opt) are fine as a fallback but build.sh should always find
  whatever go is in PATH first.
- Windows: ENABLE_VIRTUAL_TERMINAL_INPUT is the whole game — it makes the
  console speak the same VT byte sequences as a Unix terminal, so the entire
  key decoder and renderer are shared and only the raw-mode switches are
  platform-specific (a third ~150-line build-tag file, no duplicated logic).
- syscall.NewLazyDLL returns *syscall.LazyProc, NOT *syscall.Proc — the
  helper that wraps proc.Call must take the LazyProc type (first build failed
  on exactly this).
- On Windows, terminal size must come from the OUTPUT handle
  (GetConsoleScreenBufferInfo); the stdin handle is not a screen buffer, so
  terminalSize falls back to os.Stdout.
- Switch the console codepage to 65001 (UTF-8) on Windows or multi-byte
  glyphs render as mojibake; save the old codepages and restore on exit.
- ENABLE_QUICK_EDIT_MODE must be cleared together with ENABLE_EXTENDED_FLAGS
  on Windows, or a user click freezes the input loop while the console is in
  selection mode.
- build.bat must name the output hui.exe (Windows CreateProcess needs the
  .exe extension); build.sh detects GOOS=windows / MSYS uname and switches
  the name automatically so cross-compile and Git-Bash builds both work.

10. Client-side state
---------------------

    app.sessionID / subscriptionID / snapshotInfo
    app.prompts        FIFO of open prompts (approval/clarify/value)
    app.streamed       text accumulated this turn (for message.complete dedup)
    app.pInTurn        whether a reply is streaming
    app.usage          last usage map
    app.quit / cleaned guarded by a mutex

Renderer owns the draft ([]rune) and cursor; the app edits it through the
handleKey path only.

11. Open items
--------------

- Approval/clarify against a real gateway queue (needs a real approval flow).
- wss:// against a real gateway.
- Native Windows 11 console session (built and vetted for windows/amd64; the
  console-API switches await a real Windows run).
- Up/Down history (input history) — not implemented.
