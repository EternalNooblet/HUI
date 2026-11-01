# Voice-in implementation progress (session 2026-10-31, completed)

Companion record to voice-in-design.md. Status below reflects only what was
actually run and seen in this session — nothing speculative.

## Done + verified (both halves complete)

### Server half (phase 1) — previously done
- /home/papa/projects/hui/hui-phone/server/companion_wss_server.py — REWRITTEN in place
  (the live 8081 server; the hui-phone app connects there). Adds:
  - voice_start / audio(+voice_session) / voice_send / voice_cancel handling;
    audio WITHOUT voice_session keeps the old queue path untouched.
  - assembly per (device_id, voice_session) keyed by `sequence`, never arrival
    order; gaps ≥ 30 s missing → voice_state failed; <30 s gaps transcribe what
    exists; no voice_send + 30 s idle → drop silently (sweeper every 5 s);
    assembled > 60 s → truncate → failed.
  - voice_state received → transcribing → (failed | speak{text,voice_session})
    dispatched to the socket that sent voice_start; POST /events falls back to
    the device's registered ws.
  - welcome config now carries "hotwords" — same list feeds whisper initial_prompt.
  - GatewayBridge: connects ws://10.0.0.126:9119/api/ws?token=<signed blob>
    (minted per dial via ~/projects/hui/hui-cli/tools/mint_access_token.py),
    session.create {source:"cli"} only, stores session_id + stored_session_id,
    resumes with stored id after every drop, prompt.submit {session_id,text,queued:true}
    NO submission_id, collects message.delta/complete → one reply, gateway.ping
    on 15 s silence, one retry on drop. websockets 15 accepted via kwargs (ssl= works).
- /home/papa/projects/hui/hui-phone/server/companion_stt.py — long-lived NDJSON helper
  run with the COMMITTED-venv python (resolved from ~/.hermes/installs/*/facts.json
  → packages.venv → bin/python3). Imports tools.transcription_tools._transcribe_local,
  model name read from stt.local.model (=base). Never pip-installs, never a 2nd model.
- /home/papa/projects/hui/hui-phone/server/companion_mock_gateway.py — verified-contract fixture, NO model.
  Modes ok|drop_after_submit(drops FIRST submit only — bridge retries once)|reject,
  shapes a|flat|msg; sessions live across connections; MOCK_FRAME_LOG = framing oracle.
- /home/papa/projects/hui/hui-phone/server/companion_voice_matrix.py — full matrix runner.
  Exit 0 iff all claims pass.
- companion_wss_server.py EDITED (bridge): GATEWAY_WS_URL env override at dial time;
  GATEWAY_FRAME_LOG raw-frame logger (default .voice_tmp/gateway_frames.jsonl — the
  hedge so the first REAL use is the shape observation); permissive message-text
  extraction across payload.text / params.text / params.message.text shapes.
Run: `python3 companion_voice_matrix.py` — RESULT: 38/38 PASS, exit 0, real output.
Proven by this run: M1 cancel silent, M2 gap≥30s failed-only, M3 >60s cap failed-only,
M4 unknown-session silent, M5 idle-drop silent + log-marked; B1 reply leg (received→
transcribing→EXACTLY ONE speak, voice_session carried) + POST /events fallback loop
+ session.create {source}-only + prompt.submit no-submission_id + raw-frame log
captured; B2 mid-turn drop → session.resume(STORED id st-…) → reply arrived (2 submits);
B3 flat/msg shape parsing via permissive extractor; B4 refused token → clean failed,
no hang, no prompt; B5 <30s gap → partial audio transcribed → reply; B6 silence →
empty transcript → failed, ZERO prompts. Local whisper STT ran real (not the thinking model).
STILL UNPROVEN (by design, per policy): real gateway's actual message.* nesting —
observe in .voice_tmp/gateway_frames.jsonl on first real use; fix extractor if differs.

### Kotlin app half (completed this session)
- EventModels.kt: AudioPayload.voiceSession + VoiceStartEvent/VoiceSendEvent/VoiceCancelEvent
  + VoiceStateCommand in Command.parse + CommandPayload.voiceSession
- voice/VoiceInput.kt: AudioRecord VOICE_COMMUNICATION 16k mono, 100 ms chunks,
  RMS callback → VoiceWaveView, auto-cancel -65 dB / 3 s, 60 s cap, session id "vs-"+8hex
- voice/VoiceWaveView.kt: custom View drawing 60 decaying bars from RMS history
- voice/VoiceUi.kt singleton: attaches to Activity views; tap toggles listen/stop;
  handles voice_state (received/transcribing/failed) + speak reply; idle reset
- MainActivity.kt: own EventBus/Transport/CommandExecutor (mirrors CompanionService);
  startVoiceLoops() with consumeEach; wires VoiceUi with RMS callback
- activity_main.xml: root ScrollView → ConstraintLayout; voice section between
  helpText and voiceTitleText; VoiceWaveView (80 dp) + voiceToggleButton; all @+id/
- CommandExecutor.kt: voice_state case reads command.payload.voiceSession
- Build: cd companion-app; JAVA_HOME=/usr/lib/jvm/java-17-openjdk
  ANDROID_HOME=/home/papa/Android/Sdk; ./gradlew assembleRelease → BUILD SUCCESSFUL
  (verified green on modified tree). Then apksigner + adb install -r.

## Test policy (user, 2026-10-31, HARD)
NEVER again: any test that queues a prompt to the real gateway and waits for the
agent's reply (real Hermes thinking-model turn, extra session, second chat).
STT (local faster-whisper) is fine — it is not the thinking model. All gateway-leg
testing is against a MOCK gateway that replays the already-verified contract
(skills hui-gateway-remote-test / hermes-stt-venv-safety). The raw-frame probe
and the long-wait re-run below are SUPERSEDED — do not do them.

## Mock-gateway plan (replaces items 1–2)
1. companion_mock_gateway.py — local WSS fixture speaking the verified contract
   as the bridge's oracle: signed-blob token param required (raw literal → 403),
   session.create accepts {source} only and returns session_id + stored id
   snapshot, prompt.submit accepts queued WITHOUT submission_id (request_id in
   submit → rejected), replies = scripted message.delta…message.complete (nested
   shape switchable between variants), fault injection on demand: mid-run drop
   (bridge must session.resume with the stored id; runtime id → 4007), token
   expiry, runtime.describe → -32601. Canned reply text embeds the received
   transcript so the test can assert speak text == mock's reply deterministically.
2. Bridge target via env var (default ws://10.0.0.126:9119/api/ws unchanged;
   no new welcome-config knobs — the design doc forbids them).
3. Test matrix (deterministic, seconds, no model anywhere):
   - happy path: shuffled chunks → sequence-ordered assembly → local whisper →
     bridge → mock 2×delta + complete → assert voice_state received, voice_state
     transcribing, EXACTLY ONE speak to the voice_start socket with voice_session.
   - fault inject: mock drop mid-session → resume(stored id) → reply still
     arrives; 4007 path; bad/expired token → re-mint path.
   - model-free branches: voice_cancel → no prompt; gaps ≥30 s → failed;
     <30 s gaps → transcribe partial; >60 s cap → truncate → failed;
     no voice_send + 30 s idle → silent drop; empty transcript → failed.
   - POST /events canned-base64 fallback path.
4. Kotlin half later tests against 8081 + mock gateway: canned PCM (espeak fixture)
   drives waveform/RMS/auto-cancel/60-cap with known numbers; device-side checks
   are permission + UI states only, reply line canned from the mock.
5. The ONE thing a mock cannot prove (real gateway message.* nesting) is NOT
   tested — GatewayBridge logs every raw gateway frame (hui -debug jsonl pattern)
   and parses permissively across shapes; first real use is the observation,
   fix from the log if it differs. Observation on use, not a test.

## Device facts learned (worth keeping)
- Phone: adb 10.0.0.167:5555, device_id android-80c7ff2b51dc9a7b (live from
  logcat), service pid alive. Its WSS previously HUNG after the server
  swap ~18:25:34: after "Connecting securely" no onOpen/onFailure ever logged
  (OkHttp handshake no-timeout on a half-open socket). Cannot recover without
  a fresh service: kill -9 → "Operation not permitted" (SELinux),
  am stop-foreground-service → not available, am start-foreground-service while
  old service alive → no-op. The adb install -r → start-service flow WILL
  restart it (proven earlier: 3204→3937 at 17:22).
- Repo's Kotlin is the "idealized but compiling" dialect (Kotlin 1.9.25 preview
  features: data class, val, object singleton, companion object, ?.let stdlib,
  this@X, fully-qualified java./android. names). .kt.bak files DON'T compile —
  they're reference-only; mirror only constructs that appear in COMPILED files.
- Design decision taken for ambiguity: empty transcript → voice_state failed
  (inference, not in the doc).
- Design decisions taken for gap handling: gaps < 30 s transcribe what exists
  (doc only forbids ≥30 s gaps); >60 s coverage → truncate → failed.
- **Auto-cancel threshold: -65 dB (empirical; -40 dB was too sensitive)**
- **Server multi-connection fix: connected_devices[device_id] = List[entry]; voice dispatch follows voice_start socket**

## Running processes (current)
- 8081 server: python3 companion_wss_server.py (pid 661632) — LISTEN on 0.0.0.0:8081
- 9119 gateway: hermes serve --host 0.0.0.0 --port 9119 (pid 663935) — LISTEN on 0.0.0.0:9119
- Phone: adb 10.0.0.167:5555 connected; app installed; both WSS connections active

## Phase-1 matrix — DONE (2026-10-31)
Files (all at /home/papa/projects/hui/hui-phone/server/):
- companion_mock_gateway.py NEW — verified-contract fixture, NO model.
- companion_voice_matrix.py NEW — full matrix runner. Exit 0 iff all claims pass.
- companion_wss_server.py EDITED: GATEWAY_WS_URL override, GATEWAY_FRAME_LOG, permissive extractor.
Run: `python3 companion_voice_matrix.py` — RESULT: 38/38 PASS, exit 0, real output.
Proven: M1–M5, B1–B6. Local whisper STT ran real (not the thinking model).
UNPROVEN: real gateway's actual message.* nesting — observe in .voice_tmp/gateway_frames.jsonl on first real use.

## Phase-2 (Kotlin) — COMPLETED
- App built, signed, installed, running on device
- Both WSS connections active (MainActivity + CompanionService)
- Waveform animates, auto-cancel threshold -65 dB works
- Ready for human e2e test: tap Listen → speak → tap Stop → expect TTS reply
