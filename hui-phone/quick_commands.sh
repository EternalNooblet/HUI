#!/bin/bash
# Quick commands for burner-phone companion app

set -e

PHONE_SERIAL="LMX210APM62b4cc0a"
PROJECT_DIR="/home/papa/burner-phone"
COMPANION_APP_DIR="$PROJECT_DIR/companion-app"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/home/papa/Android/Sdk

build() {
    echo "Building APK..."
    cd "$COMPANION_APP_DIR"
    gradle assembleRelease
}

sign() {
    echo "Signing APK..."
    cd "$COMPANION_APP_DIR"
    apksigner sign --ks /tmp/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --out app-release-signed.apk \
        app/build/outputs/apk/release/app-release-unsigned.apk
}

install() {
    echo "Installing on phone ($PHONE_SERIAL)..."
    adb -s "$PHONE_SERIAL" install -r "$COMPANION_APP_DIR/app-release-signed.apk"
}

perms() {
    echo "Granting permissions..."
    adb -s "$PHONE_SERIAL" shell pm grant com.southpawin.companion android.permission.CAMERA
    adb -s "$PHONE_SERIAL" shell pm grant com.southpawin.companion android.permission.RECORD_AUDIO
}

start() {
    echo "Starting CompanionService..."
    adb -s "$PHONE_SERIAL" shell am start-foreground-service \
        -n com.southpawin.companion/.service.CompanionService
}

stop() {
    echo "Stopping CompanionService..."
    adb -s "$PHONE_SERIAL" shell am force-stop com.southpawin.companion
}

logs() {
    echo "Showing logs (Ctrl+C to exit)..."
    adb -s "$PHONE_SERIAL" logcat -s "CompanionService:V" "Transport:V" "Command:V" "AttentionDetector:V" "BatteryMonitor:V" "ScreenMonitor:V"
}

server() {
    echo "Starting WSS server..."
    cd "$PROJECT_DIR"
    python3 companion_wss_server.py
}

test_wss() {
    echo "Testing WSS connection..."
    python3 << 'EOF'
import asyncio, websockets, ssl, json
async def test():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    async with websockets.connect('wss://10.0.0.126:8081/ws', ssl=ctx,
                                   additional_headers={'X-Device-ID': 'test-cli'}) as ws:
        await ws.send(json.dumps({'type': 'hello', 'device_id': 'test-cli', 'capabilities': []}))
        msg = await ws.recv()
        print(f"Welcome: {msg}")
asyncio.run(test())
EOF
}

rebuild() {
    stop
    build
    sign
    install
    perms
    start
    sleep 3
    logs
}

case "$1" in
    build) build ;;
    sign) sign ;;
    install) install ;;
    perms) perms ;;
    start) start ;;
    stop) stop ;;
    logs) logs ;;
    server) server ;;
    test) test_wss ;;
    rebuild) rebuild ;;
    *) echo "Usage: $0 {build|sign|install|perms|start|stop|logs|server|test|rebuild}" ;;
esac