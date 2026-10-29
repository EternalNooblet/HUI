Hermes Unofficial Interface (HUI)
=================================

A standalone terminal client for a remote Hermes gateway. One executable,
nothing installed: it connects to a gateway running elsewhere and gives you
a full-screen chat TUI — the conversation streams above, you edit your next
message below. All session state lives on the gateway; the client never
resends history.

Usage
-----

    hui -host cachyos-pc -token <t>
    hui -url ws://<remote-host>:<port>/api/ws?token=<t>

One of -host or -url is required.

- -host <name>     gateway host, optionally name:port (default port 41841;
                   the app fills in protocol and path)
- -url <url>       full WebSocket URL
- -token <t>       gateway auth token
- -session <id>    resume an existing session instead of starting a new one
- -debug <file>    write every wire frame to a file

Using the app
-------------

Type a message and press Enter to send it. After every reply a 𖦏 prompt
appears on its own line.

- Shift+Enter (or Alt+Enter) adds a newline to your draft without sending.
- Up/Down recall previously submitted messages; the selected one becomes
  your draft, so you can edit and resubmit it.
- Editing: Ctrl+A/E go to line start/end, Ctrl+U clear, Ctrl+W delete word.
- Ctrl+C quits.
- Approval, clarify and sudo prompts from the gateway appear inline; answer
  them where you sit.
- Commands: /new starts a fresh session, /status shows build, gateway,
  session and model info, /help lists commands, /exit quits. Any other
  /text is sent to the agent as-is.

Build
-----

    ./build.sh                            # -> builds/hui
    GOOS=windows GOARCH=amd64 ./build.sh  # -> builds/hui.exe

Windows 11: run build.bat in a terminal. Needs a Go toolchain (found in
PATH, then ~/.local/opt/go); produces one statically-linked binary.

Engineering reference: SPEC.md — wire protocol, terminal behavior, and the
full verification record.
