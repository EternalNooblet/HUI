# Voice-in design — HUI Phone (agreed 2026-10-31, implemented this session)

Status: **IMPLEMENTED** — Kotlin app half + server half both complete and tested against mock gateway (38/38 PASS). Loop shape confirmed: closed-loop TTS — speak on phone → transcript to Hermes → agent reply comes back as TTS via the existing `speak` command. No chat UI needed.

## Interaction model
- One centered button, tap = toggle. First tap starts listening, second tap sends the message.
- While listening: a simple live waveform (decaying envelope of rolling max-RMS per ~20 ms), ~30 fps, small canvas. NOT a spectrogram (FFT cost, no benefit).
- The RMS feeding the waveform comes from the same PCM chunks streamed to the server — one capture, three consumers (visual, stream, STT).
- Auto-cancel guard: ~3 s of sub-threshold RMS → app emits `voice_cancel` (never user-triggered). Threshold: **-65 dB** (empirical; typical phone mic at arm's length is -50 to -60 dB).
- Hard cap: 60 s of streaming, enforced phone-side (server backstop truncates → `failed`).
- App state machine on the one button: idle → listening (waveform live) → transcribing (one text line, "Working…" is enough) → `speak` reply = back to idle. `failed` shows one line, resets to idle.
- **Talk button**: circular custom View (`CircularTalkButton`); text toggles "Talk" ↔ "Send"; pressed state darker blue + ripple.

## What already exists (verified in code — reuse, don't rebuild)
- `event/EventModels.kt` already has `AudioEvent` ("Audio chunk for STT"): pcm16, 16 kHz, mono, `data_b64`, `sequence`. **Added `voice_session` nullable field.**
- Mic capture proven path: `AttentionDetector.kt.bak` uses `MediaRecorder.AudioSource.VOICE_COMMUNICATION` + `AudioRecord` reading raw PCM chunks. Reused that pattern; do NOT use `MediaRecorder` itself (compressed, won't chunk-stream).
- `companion_wss_server.py` (now `hui-phone/server/`, 8081) already accepts/queues `audio` events and config has `audio_chunk_ms: 100`. **Consumer implemented: assembly → STT → gateway bridge → speak dispatch.**
- `RECORD_AUDIO` is already requested in MainActivity's permission flow. No new permission gate.
- `CommandExecutor.kt` already handles `speak` (honors `voice` locale, `pitch`). Reused for reply delivery.

## Wire protocol
Slotted into the existing hello/welcome/command/event scheme. Three new event types phone→server, one new command server→phone. Everything else reused. No new config knobs (`audio_chunk_ms: 100` stays).

Phone → server events:

    {"type":"voice_start","device_id":"...","payload":{"voice_session":"vs-8f3a1c","format":"pcm16","sample_rate":16000,"channels":1}}
    {"type":"audio","device_id":"...","payload":{"voice_session":"vs-8f3a1c","format":"pcm16","sample_rate":16000,"channels":1,"duration_ms":100,"data_b64":"...","sequence":42}}   // existing AudioEvent + voice_session
    {"type":"voice_send","device_id":"...","payload":{"voice_session":"vs-8f3a1c","last_sequence":87,"duration_ms":8700}}
    {"type":"voice_cancel","device_id":"...","payload":{"voice_session":"vs-8f3a1c"}}   // only from the auto-silence guard

Server assembly rules:
- Buffer per (device_id, voice_session), keyed by `sequence` — never arrival order (ws drops are a known hazard on this transport).
- `voice_send` finalizes: gapless sequence run → one pcm16 blob. Sequence gaps covering ≥ 30 s of missing coverage → fail (`voice_state failed`).
- Session with no `voice_send` and no activity for 30 s → drop silently (idle-timeout; covers a ws drop between last chunk and the tap frame).

Server → phone commands:

    {"type":"command","command":"voice_state","request_id":"...","payload":{"state":"transcribing","voice_session":"vs-8f3a1c"}}
    // states: received | transcribing | failed
    {"type":"command","command":"speak","request_id":"...","payload":{"text":"<agent reply>","voice_session":"vs-8f3a1c"}}   // existing speak + voice_session pairing

## Bridge to Hermes (the one new wire)
- Companion server opens its own WSS to `hermes serve` :9119 as a mini-client, following the verified gateway contract (skills `hui-gateway-remote-test`, `hermes-stt-venv-safety`):
  - auth = signed access blob from `tools/mint_access_token.py` (`-token`/`token=` param); raw literal tokens can NEVER pass; ~12 h expiry → re-mint on expiry.
  - `session.create` sends only `{source}` (request_id rejected); after every ws drop → `session.resume` with the stored `session_id` from the create snapshot (runtime ids answer 4007 "session not found", not a bug).
  - `prompt.submit` with the transcript, NO `submission_id` (queued IS allowed). `runtime.describe` → -32601 is expected, not a bug.
- Reply arrives as `message.*` stream → accumulate full text → ONE final `speak` down. No partial-sentence TTS.
- Transcription reuses Hermes's configured faster-whisper `base` (config `stt.local.model`, CPU int8, ~353 MB peak, ~0.4× realtime) in Hermes's committed venv (`~/.hermes/installs/<hash>/facts.json` → packages.venv). One model total — do NOT load a second copy; do NOT pip-install into the managed venv.
- RAM rule (hard): CPU-only, strata-host pins ~28 GiB of 31, headroom ~2 GB; keep loads ≤ base footprint.
- Latency is fine for this loop but the app MUST show the `transcribing` state (~0.4× realtime on a 10 s utterance ≈ 4 s) or it feels broken.

## Extras / pitfalls
- Whisper `base` mangles proper nouns: add a small `hotwords` field to the `config` welcome → feeds `initial_prompt` hints. User corrections are ground truth, not the hinted re-run (see STT skill).
- WSS frame sizes are safe: 100 ms of 16 kHz mono pcm16 ≈ 3.2 KB raw, ~4.3 KB base64.
- Device specifics from skill `burner-phone-companion`: the Android 8.1 test phone, Android 8.1 (minSdk 27), device_id regenerates per install — never hardcode; phone IP YOUR_PHONE_IP, laptop YOUR_LAN_HOST:8081; cert pinned as raw resource; restart the foreground service after every `adb install -r`.
- If hui-phone's server is repointed or a fresh server exists, keep the 8081 companion protocol as the contract; the 9119 gateway wire is strictly the companion→Hermes bridge (localhost bridge between the two laptop processes is fine — the remote-only constraint applies to the HUI desktop client, not this).
- **API 27 (Android 8.1) constraints**: Material `shapeAppearanceOverlay` broken → custom View required for circular button; `min()` on floats needs explicit type; notification channels immutable → must bump channel ID; `isNotificationListenerAccessGranted` unreliable.

## Implementation order (completed)
1. Server consumption path: audio queue → assemble → transcribe (committed-venv python subprocess) → :9119 gateway prompt → speak dispatch. Tested end-to-end by POSTing canned base64 audio.
2. App half: VoiceInput.kt (AudioRecord VOICE_COMMUNICATION + chunk stream + RMS), VoiceWaveView.kt (custom View), CircularTalkButton.kt (custom circular View), VoiceUi.kt (singleton UI controller), MainActivity wiring, activity_main.xml voice section.
3. Verified each half against mock gateway (`companion_voice_matrix.py`: 38/38 PASS).

## Build/deploy facts
- Build: `cd hui-phone/companion-app; ./gradlew assembleRelease` (JAVA_HOME=java-17, ANDROID_HOME=~/Android/Sdk), sign with apksigner, `adb install -r`, grant CAMERA/RECORD_AUDIO, start foreground service (see README.md).
- Servers required: Hermes gateway on 0.0.0.0:9119, Companion WSS on 0.0.0.0:8081
- First real run: observe `.voice_tmp/gateway_frames.jsonl` for actual `message.*` frame shape (mock cannot prove this one)
- Version format: `1.0.0-<codename>-<unix_timestamp>` (e.g., `1.0.0-athena-1793501381`)