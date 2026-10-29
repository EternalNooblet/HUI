HUI: Hermes Unofficial Interface
=================================

Demo
----

![HUI demo](hui-demo.gif)

A quick walkthrough of a full session: connect, typed prompt, streamed reply,
/status, an inline approval answered by choice number, and a clean exit.

A standalone terminal client for a remote Hermes gateway. One executable,
nothing installed: it connects to a gateway running elsewhere and gives you
a full-screen chat TUI — the conversation streams above, you edit your next
message below. All session state lives on the gateway; the client never
resends history.

Usage
-----

    hui -host <remote-host> -token <t>
    hui -url ws://<remote-host>:<port>/api/ws?token=<t>

One of -host or -url is required.

- -host <name>     backend host, optionally name:port (default port 9119).
                   The app fills in protocol and path.
- -url <url>       full WebSocket URL
- -token <t>       signed access blob (see the Token section below)
- -session <id>    resume an existing session instead of starting a new one
- -debug <file>    write every wire frame to a file

Token
-----

Start the backend on the network:

    hermes serve --host 0.0.0.0

The backend must have its auth config (username + password + signing secret).
The client token is a signed blob that mints in one command (~12 h validity):

    ./tools/mint_access_token.py > last-token.txt

Then connect:

    hui -host <lan-host-or-ip> -token "$(cat last-token.txt)"

When it expires, rerun the mint command. That signed blob is the only token
HUI ever needs.

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
