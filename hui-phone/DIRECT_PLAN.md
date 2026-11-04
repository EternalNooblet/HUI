# HUI Phone - Direct Hermes Plan (LAN first pass)

Goal: Least extra shit between phone and Hermes OOTB. Keep STT. No Tailscale in first pass.

## Target architecture
Phone -> ws://<lan-ip>:9119/api/ws?token=<blob> directly to `hermes serve --host 0.0.0.0 --port 9119` (same as `hermes dashboard` backend, headless mode). No :8081 companion_wss_server.py. No sensors / notifications in first pass.

## Transport rewrite
- Replace Transport.kt hello/events + PinnedTls + Bearer-to-:8081 with Hermes JSON-RPC over WS.
- Auth: reuse existing QR token (minted HMAC blob from dashboard.basic_auth.secret via hui-cli/tools/mint_access_token.py). Do NOT switch to password-login + ws-ticket in first pass - blob already verified to get gateway.ready and session.create.
- On connect: wait gateway.ready -> session.create {source} only -> save session_id + stored_session_id from snapshot.
- Chat: prompt.submit {session_id, text, queued:true} (no submission_id, 4000 if always sent). Reply arrives as message.start / message.delta / message.complete. Message delta semantics: payload.text missing / empty string = paragraph break, non-empty = chunk - do NOT print complete twice if response_reused.
- Reconnect/resume: after every WS drop, session.resume with stored_session_id (runtime id answers 4007, expected). Reuse VoiceUi.handleChatReply / handleVoiceReply + CommandExecutor speak TTS for assistant bubbles - keep UI, change wire only.
- Server requests (srq-*): approval -> {choice: once|deny}, clarify -> {answers:{qid:answer}}, sudo/secret/vault -> {value}. Never leave unanswered.
- Config: deriveWsUrl must become ws://<host>:9119/api/ws?token=... not wss://.../ws. Settings QR applyQrPayload must store Hermes base + token, not :8081. Keep QR flow so no manual entry needed.
- Plain ws (not wss) on LAN first pass. Drop PinnedTls/self-signed cert fork. Tailscale/HTTPS comes later as network swap, not app change.

## STT without double server
Gateway already has STT (voice.client_direct default true, tools.transcription_tools / voice_mode). The bridge-local faster-whisper copy is redundant.
- Do NOT keep 100ms AudioEvent chunk streaming to :8081.
- VoiceInput: record full WAV to file on tap-stop (tap mic -> record -> tap again -> send), not 100ms base64 chunks.
- POST to http://<lan>:9119/api/audio/transcribe {data_url: data:audio/wav;base64,...} with dashboard auth (verify cookie/token acceptance for /api/audio/* - same gateway auth profile, check _ws_auth_ok equivalent for HTTP). On {transcript} -> prompt.submit transcript as user text, show VoiceUi.handleVoiceTranscript before reply.
- Alternative if POST auth fails: WS /api/audio/transcribe-stream {sample_rate} then PCM frames then {eos:true} -> partial/final. Pick one after auth probe, not both.
- TTS stays Android native speech via CommandExecutor for voice-origin turns. Do NOT add gateway speak-stream in first pass.

## Cuts (first pass)
- BatteryMonitor (60s), ScreenMonitor (5s), AttentionDetector (2s), NotificationListener <- sensor events to :8081. No battery/screen/notification/attention upload.
- No screen_action/launch_app/config commands from bridge. No voice_state / voice_transcript command relay - STT transcript now comes from /api/audio/transcribe response, not command channel.
- CompanionService foreground WSS keep-alive for sensors goes away - MainActivity Transport owns the one /api/ws socket.
- Install: hermes serve --host 0.0.0.0 --port 9119 + APK + QR scan + mic grant. Tailscale explicitly out of scope - same LAN host:port swap later.

## Build / install on real phone (no mistakes)
- Keep signing: use <keystore path> (PKCS12, alias <alias>) and <password file> as-is. Do NOT delete or regenerate pass. Build with gradle assembleRelease using KEYSTORE_FILE/PASSWORD/ALIAS env, verify SHA256 <certificate fingerprint> before install.
- WiFi ADB: adb connect YOUR_PHONE_IP:5555 (device <test-device> already seen, host YOUR_LAN_HOST). Verify adb devices -l shows device before install. Install via adb -s YOUR_PHONE_IP:5555 install -r app/build/outputs/apk/release/*.apk. Grant CAMERA + RECORD_AUDIO, restart service, check logcat for gateway.ready + session.create, not "WSS not connected, event dropped: battery".
- Verify: hermes serve --status shows 1 process, ss -tlnp | grep 9119 LISTEN, phone chat send streams message.delta -> message.complete, voice tap records wav -> transcribe -> prompt.submit.

## Non-goals first pass
- Tailscale, public https, Cloudflare Access token flow — later.
- Migrating to password-login + ws-ticket + cookies. Minted blob is least change and already proven end-to-end (hui-cli 121 frames, exit 0, resume after drops).

## Status (2026-11-04) — IMPLEMENTED

All sections above are implemented in companion-app + server QR/mock.
Verified on device (<test-device>): release-signed APK (SHA-256
<certificate fingerprint>), ws://YOUR_LAN_HOST:9119/api/ws?token=*** open ->
gateway.ready -> client.capabilities ack -> session.create stored id.
POST /api/audio/transcribe + /api/chat/image-upload probed live (Bearer
blob 200; no-auth/query 401). Chat/voice round-trips need a QR scan +
send on the phone (mock gateway :9207 for no-model tests; real QR for
live voice). companion_voice_matrix M-group 401s on clean tree too
(pre-existing; needs :8081 companion server + auth env).
