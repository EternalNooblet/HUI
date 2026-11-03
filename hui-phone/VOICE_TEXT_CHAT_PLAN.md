# hui-phone User-Ready v1 — Dual-Mode Voice/Text Chat Plan

**Created:** 2026-11-02  
**Status:** APPROVED — ready for implementation

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

## Implementation Phases

### Phase 1: Data & Protocol Foundation
- [ ] Room schema + DAO + Repository for `Message` (user/assistant, text, timestamp, voice_session)
- [ ] Add `chat` event + `chat_reply` command to `EventModels.kt` (both directions)
- [ ] Server: handle `chat` event → forward to Hermes `prompt.submit` → emit `chat_reply` to originating socket
- [ ] Server: add `tts_voices` to welcome config (propagate from Hermes TTS voices or static list)

### Phase 2: Dual-Mode Button & Mode Switching
- [ ] Extend `CircularTalkButton`: swipe detection (left/right), mode state (VOICE/CHAT), icon swap (mic ↔ chat bubble), subtle side hints
- [ ] Mode switch logic: Voice→Chat cancels STT (emit `voice_cancel`); Chat→Voice preserves draft text
- [ ] Connection accent ring (color driven by `Transport` connection state)

### Phase 3: Text Chat UI
- [ ] `ChatListAdapter` + `RecyclerView` (message bubbles, user/assistant styling, timestamps optional)
- [ ] Input field at bottom: multi-line, IME_ACTION_SEND → send, clears on send, persists draft on mode switch
- [ ] Wire send: button tap / Enter → `chat` event with current `voice_session` + text
- [ ] Wire receive: `chat_reply` → append to DB → UI updates via Flow

### Phase 4: History & Persistence
- [ ] Load history on app start (per `voice_session` or global)
- [ ] Survives process death / phone restart (Room handles)
- [ ] Clear history action (defer to v2 settings)

### Phase 5: Voice Choice Setting
- [ ] Settings entry point (gear icon top-right? long-press button? — pick one)
- [ ] Voice list from welcome config → SharedPreferences persistence
- [ ] Apply selected voice to `speak` commands (already supported in `CommandExecutor`)

### Phase 6: Polish & Error Handling
- [ ] Toast + boop on send/STT/TTS/network failures
- [ ] Subtle connection accent (already in Phase 2)
- [ ] Quick animations on mode switch (200ms)
- [ ] API 27 verification: all Views compile/run on Android 8.1

### Phase 7: Build & Device Test
- [ ] `./gradlew assembleRelease` → sign → `adb install -r`
- [ ] Grant permissions, start service
- [ ] End-to-end: voice mode (STT→TTS), text mode (bubbles), mode switching, history survive kill/restart, voice setting persists

---

## Open Decisions (quick picks needed)

1. **Settings entry** — gear icon top-right? Long-press button? Three-dot menu?
2. **Clear history** — in v1 or defer to v2 settings page?
3. **Timestamp visibility** — show on bubbles? Long-press to reveal? Hidden?
4. **Input field max lines** — single-line expandable? Fixed 4-line max?
5. **Empty state** — "No messages yet. Start a conversation." or blank?

---

## Risk Flags
- **Top-center button + bottom input** = unusual reach on tall phones. One-handed use may suffer. User said "try it" — we'll build it, they'll feel it.
- **Swipe on 80dp circle** — even with padding, may need generous `touchSlop` and visual feedback.
- **API 27** — `RecyclerView`, `Room`, `ConstraintLayout`, `GestureDetector` all fine. Custom button View already exists.
- **Server schema change** — `companion_wss_server.py` needs `chat`/`chat_reply` handling + `tts_voices` in welcome. Low risk.

---

## Estimated Effort
- Phases 1–3: core feature (~3–4 focused sessions)
- Phases 4–6: polish (~1–2 sessions)
- Phase 7: device verification (~1 session)

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