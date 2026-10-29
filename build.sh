#!/bin/sh
# Build HUI into builds/. The build timestamp is embedded (main.buildStamp) so
# each build has a unique identity (see /status and the startup banner).
#   ./build.sh                          -> builds/hui      (current platform)
#   GOOS=windows GOARCH=amd64 ./build.sh -> builds/hui.exe (cross-compile)
set -e
cd "$(dirname "$0")"

GO=$(command -v go || true)
if [ -z "$GO" ] && [ -x "$HOME/.local/opt/go/bin/go" ]; then
    GO="$HOME/.local/opt/go/bin/go"
fi
if [ -z "$GO" ]; then
    echo "error: go toolchain not found (looked in PATH and ~/.local/opt/go)" >&2
    exit 1
fi

mkdir -p builds

TS=$(date -u +%Y%m%d%H%M%S)
OUT=hui
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) OUT=hui.exe ;;
esac
if [ "${GOOS:-}" = "windows" ]; then
    OUT=hui.exe
fi
CGO_ENABLED=0 "$GO" build -trimpath -ldflags "-s -w -X main.buildStamp=$TS" -o "builds/$OUT" .
echo "built ./builds/$OUT (build $TS)"
