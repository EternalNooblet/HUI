# Voice-in fix plan — hui-phone (2026-11-04)

Status: IMPLEMENTED + VERIFIED (debug build green, matrix 43/43).
Based on code as-is today (package `com.hui.companion`).

## What voice-in is supposed to be *now* (SUPERSEDES the transcript-into-input model)

The prompt-kit code (commits 0782f76 / 9dd5d96) made `VoiceUi` a
mic -> transcript-to-text-input flow. That is REPLACED by this spec
(2026-11-04): all modalities share one session, one window, and the
reply is ALWAYS shown as a bubble.

    voice turn :  mic -> VoiceInput streams audio (voice_start/audio/send)
                -> server STT -> bridge -> `speak` {reply, voice_session}
                -> phone: assistant bubble (always) + TTS read-out
    text/image turn: input/send -> `chat` {text, voice_session}
                -> server bridge -> `chat_reply` {reply, voice_session}
                -> phone: assistant bubble (always), NO TTS

Rules (user, 2026-11-04):
- Voice, image, and text all share the SAME session id and ONE chat
  window; turns can mix freely in any order.
- Reply text ALWAYS shows as an assistant bubble (voice turns included).
- TTS read-out happens IFF the turn that produced the reply started
  with voice-in. Text and/or image turns never speak.
- TTS engine (confirmed in code): `CommandExecutor.speakText` uses
  Android's native `android.speech.tts.TextToSpeech` (phone speech
  engine) — `setLanguage` from `Config.loadVoice()` (tts_voice) +
  `setPitch` from `Config.loadPitch()` (tts_pitch), overridable per
  command via payload `voice`/`pitch` with `QUEUE_FLUSH`. No server-side
  TTS, no streamed audio.

So the fix target is "voice reply = bubble + TTS; text/image reply =
bubble only", NOT "transcript lands in the input, editable, then sent",
and NOT the dead CircularTalkButton / Talk-Send loop. All items below
implement this spec.

## What's broken (checked in code, not guessed)

### 1. `voice_state` is always a no-op (client) — CommandPayload has no `state`
- Server sends: `command voice_state, payload {state, voice_session}`.
- `EventModels.kt CommandPayload` has `text, voice, pitch, ..., voice_session`
  — no `state` field, so Gson drops it.
- `CommandExecutor.handleVoiceState()` reads `payload.text` as the state,
  which is always empty. `VoiceUi.handleVoiceState("", ...)` hits `else -> {}`.
- Effect: received/transcribing/failed are never shown; the only resets
  that *do* run come from `handleVoiceState` itself, so failed is also invisible.

### 2. Voice reply handling is reversed (per new spec)
- Server voice path: STT transcript -> bridge -> ONE `speak` {reply}
  (the assistant reply, not the transcript). Server text/image path:
  `chat` -> `chat_reply` {reply}. The two command types already encode
  turn origin — that part is accidentally correct.
- Current client treats `speak` as "transcript to insert into the text
  input" (`handleVoiceTranscript` writes it into the textarea) and
  gates it on `VoiceUi.voiceRecording` — which is false by the time the
  reply arrives, so the fallback branch TTSes the reply instead.
- Per new spec the route must be: `speak` -> assistant bubble
  (identical to `handleChatReply`) + TTS via native `TextToSpeech`;
  `chat_reply` -> assistant bubble only, never TTS. Today voice
  replies get neither a bubble nor (reliably) TTS.
- Related gap: the user's voice transcript itself never reaches the
  phone (server drops it after STT). For a mixed session to be
  coherent, the user bubble should show that transcript.

### 3. Auto-cancel leaves the UI stuck "listening"
- `VoiceInput.captureLoop` on 3 s of sub-threshold RMS calls
  `postVoiceCancel()` and returns, but never notifies `VoiceUi`.
- `VoiceUi.voiceRecording` stays true, mic button stays pressed,
  `VoiceInput` is never nulled, and the next tap calls
  `stopVoiceRecording` -> `postVoiceSend()` on a stale session.
- Server drops the session on `voice_cancel`, so the later `voice_send`
  has nothing to finalize (silent drop in `_voice_sweep`).

### 4. No user feedback at all — `setStatus()` is an empty stub
- `VoiceUi.setStatus()` is `private fun setStatus(text: String) { }`.
- `received` / `transcribing` / `failed` / `Listening...` / `Transcript inserted`
  all go nowhere. No ThinkingBar, no Toast, no SystemBanner.
- User-facing result: silent button presses, no idea whether STT is running
  (~4 s for a 10 s utterance) or failed.

### 5. Waveform is dead code
- `VoiceWaveView` exists (60 decaying bars, `feedRms`/`reset`), but:
  - it is not inflated anywhere in `activity_main.xml` or
    `layout_prompt_input.xml`;
  - `VoiceUi.startVoiceRecording` passes an RMS lambda that is explicitly
    a no-op ("no UI element currently wired").
- The design's "one capture, three consumers (visual, stream, STT)" has
  collapsed to stream+STT only. Nothing shows the mic is live.

### 6. VoiceInput lifecycle leaks / timing bugs
`VoiceInput.kt`:
- `start()` launches two coroutines but stores only the first in `job`.
  The second (`sendLoop`) is never cancelled. `sendLoop` itself is dead —
  it iterates a `chunkQueue` that `captureLoop` never writes to.
- `cancel()` closes `chunkQueue` (first use after that would throw),
  calls `audioRecord.stop()`/`release()` from any thread with no
  `state != RECORDING` guard — calling `stop()` on a non-recording
  AudioRecord throws `IllegalStateException`.
- `delay(CHUNK_DURATION_MS)` *after* a blocking 100 ms `read()` makes the
  real cadence ~200 ms, not 100 ms. Server assembly still works because
  it keys by `sequence`, but wall-clock duration drifts from chunk count.
- `read() <= 0` is ignored (no `ERROR_*` handling), and
  `getMinBufferSize` failure is not checked.
- No `RECORD_AUDIO` check before constructing `AudioRecord`; construction
  itself is inside `start()` with no try/catch.

### 7. Voice turns break the shared session
- Spec now requires one session for voice/image/text. Current code
  violates it: `VoiceUi.startVoiceRecording` generates a new `vs-…` id
  and overwrites `currentVoiceSession`, which is also the Room
  chat-session key used by `loadMessages()`, the sidebar, and the
  `chat` event payload.
- Pressing the mic therefore switches the visible chat window to a
  fresh empty history, and the voice turn lives in a different
  session from the text turns around it.

### 8. No auth/connect gate on start (chat send has one, voice does not)
- `sendChatMessage` checks `transport.isAuthenticated()` and `isConnected`
  before sending (with honest Toasts). `startVoiceRecording` checks neither.
- Unauthenticated/disconnected start burns AudioRecord + buffers that
  `Transport.send` will then drop locally.

### 9. Hard cap path also strands the UI
- On 60 s, `captureLoop` calls `postVoiceSend()` and returns — same
  missing callback to `VoiceUi` as auto-cancel. `voiceRecording` stays
  true until the user taps again.

### 10. Dual transport (pre-existing, not voice-only)
- `MainActivity` and `CompanionService` each build their own
  `EventBus`/`Transport`/`CommandExecutor` and both connect WSS.
- Voice is driven only from `MainActivity`, so this doesn't break voice
  directly, but both sockets get commands and `speak` TTS can fire twice
  in the fallback path. Worth noting, not in scope to unify here.

## Server side — verified OK, one caveat
- `server/companion_wss_server.py`: `voice_start` / `audio(+voice_session)` /
  `voice_send` / `voice_cancel` -> assembly by `sequence` -> wav -> STT ->
  bridge -> ONE `speak{voice_session}` (or `voice_state` transitions).
  Matrix `companion_voice_matrix.py`: 38/38 PASS vs mock gateway.
- Gap math: `missing_ms = missing * nominal_ms` where nominal is
  `duration_ms / (total_seq+1)`. With the ~200 ms real cadence above,
  `duration_ms` (wall clock) and chunk count drift apart, so the
  estimate is fuzzier than it looks. Fixing #6 (cadence) also fixes
  the semantics here — no server change needed for the fix.
- Still unproven by design: real gateway's `message.*` nesting. Guarded by
  `GATEWAY_FRAME_LOG` raw-frame logging (`.voice_tmp/gateway_frames.jsonl`)
  and a permissive extractor. First real run is the observation.
- Hard policy (unchanged): NEVER test against the real thinking-model
  gateway. Mock gateway is the contract oracle.

## Fix plan (ordered, smallest-first)

### Phase A — Wire correctness (do first, all low-risk)
1. Add `state: String?` to `CommandPayload` in `EventModels.kt`
   (`@SerializedName("state")`). Keep `text` — do not overload it.
2. Fix `CommandExecutor.handleVoiceState` to read `payload.state`
   (fall back to `payload.text` once, for log comparison, then drop).
3. Per-turn origin tracking (replaces the `voiceRecording` flag):
   command type already encodes origin — voice replies arrive as
   `speak`, text/image replies as `chat_reply`. Make routing follow
   the spec instead of a transient flag:
   - `VoiceUi` keeps ONE `currentSession` (Room + sidebar + all event
     payloads — no second id; see Phase B #4).
   - On `voice_start`, mark the turn's origin; on `chat` send, mark
     it text. Because the server serializes turns via `_bridge_lock`,
     a per-turn `origin` flag on `VoiceUi` (not a map) suffices for
     v1: `speak` -> bubble + TTS; `chat_reply` -> bubble only.
   - `speak` handler: insert assistant bubble via the same
     `messageRepository.insertMessage("assistant", …)` path as
     `handleChatReply`, THEN call the existing native
     `TextToSpeech` path (`Config.loadVoice()/loadPitch()`). No new
     TTS code — reuse `CommandExecutor.speakText`.
   - `chat_reply` handler: bubble only, never TTS (already the case;
     keep it).
   - Delete `handleVoiceTranscript`'s textarea insert and the
     `VoiceUi.voiceRecording` check in `CommandExecutor`. This also
     resolves #2's double-TTS risk.

3b. Voice user bubble (server + client, small):
   - Server `_voice_finalize`: after successful STT, send the
     transcript to the phone BEFORE the final `speak`, e.g. a new
     `voice_transcript {text: transcript, voice_session}` command;
     the phone inserts it as the user bubble in the shared session.
   - Client: add `VoiceTranscriptCommand` to `Command.parse`
     alongside `VoiceStateCommand`/`ChatReplyCommand`, and route to
     a `handleVoiceTranscript` that inserts a user message (NOT
     into the textarea).
   - Note: server Matrix `companion_voice_matrix.py` stays 38/38
     (no finalize shape change for mock); add one Matrix case:
     transcript command precedes the single `speak`.

### Phase B — State ownership + feedback
4. One session id for everything in `VoiceUi`:
   - Keep the existing `currentVoiceSession` as THE session id
     (Room + sidebar + `chat`/`voice_start`/`audio`/`voice_send`
     payloads) — rename to `currentSession` for clarity only.
   - `startVoiceRecording` MUST NOT generate a new id and MUST NOT
     call `loadMessages`. Mic tap reuses the visible session, so a
     voice turn lands inside the same window as the text turns
     around it (spec: mix freely, same window).
   - `voiceRecording` is a transient recording-state boolean only;
     reply routing must never read it (see Phase A #3).
5. Introduce a single `VoiceInput` callback interface
   (`onAutoCancel`, `onHardCap`, `onError`) instead of posting events
   only. `VoiceUi` handles all three: null the recorder, clear the
   pressed state, hide the ThinkingBar (Phase D), and drop this
   turn's origin flag. No separate pending-map.
6. Implement feedback via the existing `ThinkingBarView` (Phase D);
   `setStatus()` is deleted rather than wired to a new label:
   - `Listening…` -> bar `start()` (recording timeout, not 90 s);
   - `Transcribing…` -> same bar, new text (90 s timeout reused);
   - `Failed` -> bar `stop()` + Toast + short ToneGenerator boop
     (same error affordance the text-chat plan specified);
   - reply bubble landed / transcript insert -> bar `stop()`.
7. Add the auth/connect gate from `sendChatMessage` to
   `startVoiceRecording`: block start with a Toast if
   `!transport.isAuthenticated()` or `!transport.isConnected`
   (call `transport.connect()` in the second case, mirroring chat).

### Phase C — VoiceInput hardening
8. Remove `chunkQueue`/`sendLoop` entirely. One capture coroutine.
   Store both it and the AudioRecord in owned fields; `cancel()` must
   work from any thread and be idempotent.
9. Drop the trailing `delay(100)`; rely on the blocking `read()`.
   Keep wall-clock duration as the source of truth for cap math.
10. Guard `#6`'s call sites: check `RECORD_AUDIO` permission first,
    validate `getMinBufferSize` result, handle `read() <= 0`
    (log + continue; give up after N consecutive errors), guard
    `audioRecord.state`, wrap `stop()`/`release()` in try/catch.
11. On auto-cancel/hard-cap, complete cleanup inside `VoiceInput`
    (stop/release) and fire the Phase B callback in the same pass —
    no path that leaves the recorder running.

### Phase D — Recording indicator (ThinkingBar reuse, NO waveform)
- No live waveform. `VoiceWaveView` is dead — delete the file and drop
  the RMS-to-UI callback; RMS stays only for the integer auto-cancel
  guard inside `VoiceInput`.
- Reuse the existing `ThinkingBarView` slot (already constrained
  between `ChatRecyclerView` and `PromptInputLayout` in
  `activity_main.xml` — the exact spot above the input):
  - Recording: `thinkingBar.setText("Listening…")` + `thinkingBar.start()`
    — shimmer + running dots unchanged, zero new views/layouts.
  - On stop/cap: same bar, text switches to "Transcribing…" (no flicker —
    stay visible through the STT wait), then `thinkingBar.stop()` on
    transcript insert or `failed`.
  - Failed: `ThinkingBar.stop()` + Toast/boop (Phase B #6).
  - Text-chat thinking is unaffected: only one bar, same slot,
    different text. Keep neutral colors (no new recording tint) so
    "Listening…/Transcribing…" vs "Thinking" read as one state
    machine, not two competing indicators.
  - Timeouts: recording needs its own handler (60 s cap + ~2 s), not
    the 90 s thinking timeout — stop the bar on cap/auto-cancel via
    the Phase B callback in the same pass.
12. `VoiceUi.startVoiceRecording` / `stopVoiceRecording` drive the bar
    directly (already has the `thinking: ThinkingBarView?` ref);
    `setStatus()` is deleted, not implemented as a separate label.
13. Mic button keeps `isPressed` while recording (existing) as the
    only other affordance; no new color/animation work.

### Phase E — Verify (mock-first, per policy)
14. `python3 server/companion_voice_matrix.py` — must stay 38/38.
15. New matrix cases (mock gateway only):
    - speak-with-voice_session -> assistant bubble insert + TTS branch taken
      (client-side; server asserts single `speak` after transcript cmd);
    - chat_reply -> bubble only, TTS branch NOT taken (text/image turn);
    - mixed order in one session: voice turn, then text turn -> two bubbles,
      only the voice turn's reply speaks;
    - voice_state received/transcribing/failed all reach the callback;
    - auto-cancel -> UI reset without a following send;
    - speech-then-cancel ordering (no orphan session).
16. Device pass (the Android 8.1 test phone, API 27): build `assembleRelease`, sign,
    `adb install -r`, record a short utterance:
    - the ThinkingBar above the input says Listening…, then Transcribing…,
      then hides (no waveform to check);
    - user voice transcript appears as a user bubble, assistant reply
      appears as a bubble AND is spoken aloud (native TTS);
    - send a text message next in the same window -> reply bubble only,
      NO speech;
    - send a text message with an image attached -> reply bubble only,
      NO speech;
    - 3 s silence -> auto-cancel visible, button resets;
    - airplane-mode / mid-record reconnect -> honest failure, no stuck UI.
17. First REAL gateway run: observe `.voice_tmp/gateway_frames.jsonl`
    for the actual `message.*` shape. Observation only, not a test.

## Explicitly out of scope
- Reverting to CircularTalkButton / Talk-Send toggle / direct TTS reply
  (superseded by the prompt-kit chat model).
- Unifying MainActivity + CompanionService transports (#10) — real issue,
  but separate concern; note in follow-up plan.
- Voice choice settings page (deferred in VOICE_TEXT_CHAT_PLAN.md Phase 5).
- Any test against the real thinking-model gateway.

## Build command
    cd hui-phone/companion-app
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
    # sign with <keystore path> + <password file>
    # (never delete .pass — keep reusing it), then adb install -r app-release-signed.apk
