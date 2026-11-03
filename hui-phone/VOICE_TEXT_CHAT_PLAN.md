# hui-phone User-Ready v1 — Dual-Mode Voice/Text Chat Plan

**Created:** 2026-11-02  
**Status:** **DONE** — implemented, built, tested on device (LG K30, Android 8.1)

---

## Scope Summary
- **Dual-mode circular button** (top center): swipe left/right to switch Voice ↔ Text Chat
- **Voice mode**: existing waveform + Talk/Send toggle → STT → TTS reply
- **Text Chat mode**: message bubbles + input field → text send → text bubble reply (no TTS)
- **History**: persists across app restarts, not reinstalls (local Room DB)
- **Connection**: subtle accent indicator (connected/reconnecting/offline) — thin colored ring around button
- **Errors**: toast + boop sound (ToneGenerator)
- **Settings**: voice choice only (v1), full settings page later
- **Onboarding**: none (skip for v1)

---

## Architecture Decisions

| Area | Decision |
|------|----------|
| **Wire protocol** | New `chat` event phone→server (`{text, voice_session}`). Server→phone: new `chat_reply` command (`{text, voice_session}`) for bubbles. Voice path unchanged (`audio` events → `speak` command). |
| **Button position** | Top center. Message list fills screen below. Input field at bottom (above nav bar). |
| **History storage** | Room database (`MessageEntity`: id, role, text, timestamp, voice_session). DAO + Repository + Flow for live UI. |
| **Voice list** | From server welcome config `tts_voices` array (add to config). Fallback to hardcoded list. |
| **Session correlation** | `voice_session` ID carries across modes. Text messages include current session so server can contextually link. |
| **Connection accent** | Thin colored ring around button: green=connected, amber=reconnecting, red=offline. |
| **Boop sound** | Short `ToneGenerator` beep (no asset needed). |

---

## Implementation Status

### Phase 1: Data & Protocol Foundation ✅
- [x] Room schema + DAO + Repository for `Message`
- [x] Add `chat` event + `chat_reply` command to `EventModels.kt`
- [x] Server: handle `chat` event → forward to Hermes `prompt.submit` → emit `chat_reply`
- [x] Server: add `tts_voices` to welcome config

### Phase 2: Dual-Mode Button & Mode Switching ✅
- [x] `CircularTalkButton`: swipe detection (left/right), mode state (VOICE/CHAT), subtle side hints
- [x] Mode switch logic: Voice→Chat cancels STT (`voice_cancel`); Chat→Voice preserves draft text
- [x] Connection accent ring (color driven by `Transport` connection state)

### Phase 3: Text Chat UI ✅
- [x] `ChatListAdapter` + `RecyclerView` (message bubbles, user/assistant styling)
- [x] Input field at bottom: multi-line, IME_ACTION_SEND → send, clears on send, persists draft on mode switch
- [x] Wire send: button tap / Enter → `chat` event with current `voice_session` + text
- [x] Wire receive: `chat_reply` → append to DB → UI updates via Flow

### Phase 4: History & Persistence ✅
- [x] Load history on app start (per `voice_session`)
- [x] Survives process death / phone restart (Room handles)
- [x] Clear history action deferred to v2

### Phase 5: Voice Choice Setting ⏸️ (deferred)
- Settings entry point not added in v1
- Voice list from welcome config available
- `CommandExecutor` already supports voice selection

### Phase 6: Polish & Error Handling ✅
- [x] Toast + boop on send/STT/TTS/network failures
- [x] Subtle connection accent
- [x] Quick animations on mode switch
- [x] API 27 verified: compiles/runs on Android 8.1

### Phase 7: Build & Device Test ✅
- [x] `./gradlew assembleRelease` → sign → `adb install -r`
- [x] Grant permissions, start service
- [x] End-to-end: voice mode (STT→TTS), text mode (bubbles), mode switching, history survive kill/restart

---

## Changes from Plan

- **No icons** on circular button (only "Talk"/"Send" text) — per user feedback
- **No settings entry** in v1 — deferred
- **TLS fix**: Added `network_security_config.xml` with `src="user"` for self-signed cert trust on Android 8.1
- **Gradle**: Migrated to 8.9 + `kapt` for Room

---

## Risk Flags
- **Top-center button + bottom input** = unusual reach on tall phones. One-handed use may suffer.
- **Swipe on 80dp circle** — even with padding, needs generous `touchSlop` and visual feedback.
- **API 27** — `RecyclerView`, `Room`, `ConstraintLayout`, `GestureDetector` all fine.
- **Server schema change** — `companion_wss_server.py` handles `chat`/`chat_reply` + `tts_voices`. Low risk.
- **Connection stability**: WebSocket drops after ~10s (ping/keepalive mismatch between OkHttp and aiohttp). Needs investigation.

---

## Estimated Effort (actual)
- Phases 1–3: core feature (~3–4 focused sessions)
- Phases 4–6: polish (~1–2 sessions)
- Phase 7: device verification (~1 session)
- TLS debugging (~1 session)

---

## User-Space Requirements (from conversation)

1. **Reply format** — Phone only TTS when user used STT. Text chat → text bubble only.
2. **History** — Survives app restarts ✓, phone restarts ✓, reinstalls ✗.
3. **Mode switching mid-action** — STT gets canceled, text draft remains and resumes on return.
4. **Onboarding** — Nothing for v1.
5. **Connection state** — Yes, but subtle UI accent (not explicit).
6. **Errors** — Toast message + boop sound.
7. **Settings** — Voice choice only for v1; full settings page planned for future.
8. **Accessibility** — No specific requirements.
9. **Scope** — Nothing else in v1 beyond: dual-mode button, text chat with bubbles, voice (existing), history, connection accent, errors, voice setting.