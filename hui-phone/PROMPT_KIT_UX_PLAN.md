# Using prompt-kit as the UX for hui-phone — Plan

**Created:** 2026-11-02  
**Status:** DRAFT — awaiting direction

---

## The Core Conflict

| hui-phone (current) | prompt-kit (target) |
|---------------------|---------------------|
| Native Android (Kotlin, XML layouts, RecyclerView) | Web (React 19, Next.js, Tailwind CSS) |
| Runs on device (the Android 8.1 test phone, Android 8.1, API 27) | Runs in browser / PWA / WebView |
| Voice-first, circular button, waveform | Chat-first, message bubbles, input field |
| Python WSS server (8081) + Hermes gateway (9119) | Same servers — protocol unchanged |

**You cannot import prompt-kit components into a Kotlin Android app.** They are React components.

---

## Three Viable Paths — Pick One

### Path A: Port prompt-kit *design patterns* to Android (Recommended for current architecture)
Keep the native Android app. Rebuild the prompt-kit component *behaviors and visual language* in Kotlin/Jetpack Compose or XML.

**What this means:**
- `Message` → `ChatMessage` + `ChatListAdapter` (already exist, restyle to match prompt-kit bubbles)
- `PromptInput` → multi-line `EditText` with send action (already exists in VOICE_TEXT_CHAT_PLAN)
- `ChatContainer` → `RecyclerView` with auto-scroll (already exists)
- `Tool` component → new Kotlin view for tool-call cards
- `Source` component → new Kotlin view for citation chips
- `ChainOfThought` → collapsible reasoning steps view
- `ThinkingBar` → "AI is thinking…" state in button/indicator
- `ResponseStream` → typewriter animation on text bubbles
- `Loader` / `TextShimmer` → skeleton shimmers in RecyclerView
- `FeedbackBar` → long-press or swipe action on assistant bubbles
- `SystemMessage` → banner view for connection/errors
- `FileUpload` → image/document picker + preview chips
- `Image` component → Coil/Glide image loading in bubbles
- `CodeBlock` → syntax-highlighted code in bubbles (highlight.kt or Skia)
- `Markdown` → markdown rendering in bubbles (Markwon or Jetpack Compose Markdown)
- `JSXPreview` → not applicable (no JSX in Android)
- `ScrollButton` → FAB "jump to bottom" (easy add)

**Effort:** 2–3 focused sessions for core chat parity; 1–2 more for advanced bits (tool calls, citations, reasoning).  
**Pros:** Keeps native performance, voice integration, device sensors, offline history.  
**Cons:** Manual port — no code reuse. Tailwind → XML/Compose translation by hand.

---

### Path B: Build a *web companion app* using prompt-kit (Next.js + React)
Create a separate `hui-web` app (Next.js) that speaks the same WSS protocol (8081) and Hermes gateway (9119). The Android app stays for voice-on-device; the web app becomes the rich chat/desktop interface.

**What this means:**
- `npx create-next-app hui-web --typescript --tailwind --eslint`
- `npx shadcn@latest add button input scroll-area textarea ...` (prompt-kit prerequisites)
- Install prompt-kit components individually: `npx shadcn@latest add "https://prompt-kit.com/components/prompt-input"` etc.
- Build pages: `/chat` (main), `/voice` (optional), `/settings`, `/history`
- Reuse the exact same wire protocol (`chat` / `chat_reply` events, `voice_start` / `audio` / `voice_send` for voice)
- Deploy to Vercel / self-host; access from phone browser or desktop

**Effort:** 3–4 sessions for working chat + voice; 1–2 for polish.  
**Pros:** Zero porting — use prompt-kit components directly. Single codebase for web/desktop/PWA. Rich markdown/code/tool rendering out of the box.  
**Cons:** Two UIs to maintain. Voice in browser needs MediaRecorder + WebSocket (doable, but separate from native AudioRecord path). No access to Android sensors/foreground service.

---

### Path C: Wrap the web app in a WebView (Hybrid)
Keep the Android shell (foreground service, permissions, native voice capture) but render the chat UI in a `WebView` loading the Next.js app (local or remote). Bridge native voice events ↔ WebView via `JavascriptInterface`.

**What this means:**
- Android app becomes thin shell: mic capture → base64 → `webview.evaluateJavascript("window.hui.sendAudio(...)")`
- WebView loads `hui-web` (Next.js) — full prompt-kit components work natively
- WebView receives `chat_reply` / `speak` via JS bridge → renders bubbles, plays TTS
- Native circular button can stay or move into web

**Effort:** 3–4 sessions (WebView + bridge + prompt-kit web app).  
**Pros:** Best of both — native voice, web UX.  
**Cons:** WebView on API 27 (Android 8.1) is old Chromium (v70-ish) — Tailwind/React 19 may not work. Need to test. Adds bridge complexity.

---

## Recommendation

**If voice-on-device + native sensors are non-negotiable → Path A.**  
Port the *design language* of prompt-kit to Kotlin. You already have 80% of the chat infrastructure (VOICE_TEXT_CHAT_PLAN ✅). The remaining 20% is visual polish + advanced components (tool calls, citations, reasoning, streaming).

**If you want the full prompt-kit experience with zero porting → Path B.**  
Build `hui-web` as a first-class citizen. The Android app stays as "voice remote"; the web app becomes the primary chat interface.

**Do not do Path C** unless you verify Next.js + React 19 + Tailwind runs on Android 8.1 WebView (it likely won't without polyfills/transpilation).

---

## Next Step

Tell me which path you want. I'll write the detailed implementation plan for that path.

- **Path A** → I'll spec the Kotlin component ports (file-by-file, styling tokens, animations)
- **Path B** → I'll scaffold the Next.js + prompt-kit project with WSS integration
- **Path C** → I'll spike WebView compatibility first (quick test)