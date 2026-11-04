> **Part of the [HUI project](../README.md)** — the Go terminal client.

# HUI: Hermes Unofficial Interface

![HUI demo](assets/hui-demo.gif)

A standalone terminal client for a remote Hermes gateway. One executable,
nothing to install: a full-screen chat TUI — replies stream above, you type
below. Session state lives on the gateway; the client never resends history.

## Usage

```bash
hui -host <remote-host> -token <blob>
hui -url ws://<remote-host>:<port>/api/ws?token=<blob>
```

One of `-host` / `-url` is required. Optional: `-session <id>` resumes a
session, `-debug <file>` writes every wire frame to a file.

## Token

Start the backend (`hermes serve --host 0.0.0.0`, with auth config set),
then mint a signed token (1-year validity):

```bash
./tools/mint_access_token.py > last-token.txt
hui -host <lan-host-or-ip> -token "$(cat last-token.txt)"
```

Re-run the mint command when it expires.

## Using it

- Type + Enter to send. Shift+Enter adds a newline to the draft.
- Up/Down recalls previous messages. Ctrl+C quits.
- Approval/clarify/sudo prompts appear inline — answer them where you sit.
- `/new` fresh session, `/status` build+gateway info, `/help` commands, `/exit` quit.

## Build

```bash
./build.sh                            # -> builds/hui
GOOS=windows GOARCH=amd64 ./build.sh  # -> builds/hui.exe
```

Windows: run `build.bat`. Needs a Go toolchain (PATH, then `~/.local/opt/go`).
One statically-linked binary, no cgo.

Engineering reference: `docs/SPEC.md` (wire protocol + verification record).
