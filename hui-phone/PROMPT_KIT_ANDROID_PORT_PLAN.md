# Path A: Port prompt-kit Design Patterns to Kotlin/Android (Strict Parity)
**Target:** hui-phone companion-app (Kotlin, XML, API 27)  
**Source:** prompt-kit component library (React/Next.js/Tailwind + shadcn/ui)  
**Rule:** Where our current design conflicts with prompt-kit, **prompt-kit wins**. No creative deviations.

---

## Critical Design Decisions from prompt-kit (Non-Negotiable)

| Aspect | prompt-kit Choice | Our Old Plan | Resolution |
|--------|------------------|--------------|------------|
| **Message bubbles** | No tails. `Avatar` + `MessageContent` (markdown) + `MessageActions`. `rounded-lg p-2 bg-secondary prose` | Traditional chat bubbles with tails, user right/assistant left | **Adopt prompt-kit**: flat cards, avatar for assistant, markdown prose styling |
| **Chat container** | `use-stick-to-bottom` library (smooth resize, instant initial) | Manual `RecyclerView.scrollToPosition()` | **Adopt prompt-kit**: implement stick-to-bottom logic in `RecyclerView.OnScrollListener` |
| **Input container** | `rounded-3xl border p-2 shadow-xs`, autosizing `Textarea` (min-h-44px), action buttons with tooltips | `LinearLayout` with `EditText` + `ImageButton`, `input_background` drawable | **Adopt prompt-kit**: `MaterialCardView` rounded-3xl, autosizing `EditText`, `TooltipCompat` for actions |
| **Markdown rendering** | `marked` + `react-markdown` + `remark-gfm` + `remark-breaks`, per-block memoization | Markwon (similar but different plugins) | **Adopt prompt-kit plugin set**: GFM + breaks, split into blocks, memoize per block |
| **Code blocks** | Shiki highlighter, `github-light` theme, `rounded-xl border border-border bg-card`, `text-[13px]` | Prettify/Highlight.js, generic styling | **Adopt prompt-kit**: Shiki via `shiki-java` or pre-highlight on server, exact theme + dimensions |
| **Tool calls** | Collapsible (`Collapsible` primitive), state icons (Loader2/Settings/CheckCircle/XCircle), state badges (Processing/Ready/Completed/Error), JSON input/output, toolCallId | Custom card design | **Adopt prompt-kit**: exact state machine, exact icons, exact badge colors, collapsible with animation |
| **Sources/Citations** | `HoverCard` + `HoverCardTrigger` (chip with favicon), `HoverCardContent` (title/description), Google favicon service | Custom chip + dialog | **Adopt prompt-kit**: `HoverCard` pattern, favicon from `google.com/s2/favicons`, hover→card |
| **Chain of Thought** | `Collapsible` steps, vertical connector bar (`bg-primary/20 w-[2px]`), `Circle` (2px) step indicator, `defaultOpen=true` | Custom reasoning view | **Adopt prompt-kit**: exact connector bar, circle indicators, collapsible steps |
| **Thinking bar** | `TextShimmer` on "Thinking" text, optional stop button (dotted border-b), chevron icon | Custom animated dots | **Adopt prompt-kit**: `TextShimmer` (CSS gradient animation), stop button with dotted border |
| **Response streaming** | Typewriter (default, speed 20) or Fade mode, `characterChunkSize`, `segmentDelay`, `fadeDuration` | Simple typewriter | **Adopt prompt-kit**: both modes, configurable speed/chunk/delay |
| **Loaders** | 11 variants: circular, classic, pulse, pulse-dot, dots, typing, wave, bars, terminal, text-blink, text-shimmer, loading-dots | Basic shimmer | **Adopt prompt-kit**: implement all variants as `Loader` class with `variant` enum |
| **TextShimmer** | CSS gradient: `linear-gradient(to right, var(--muted-foreground) X%, var(--foreground) 50%, var(--muted-foreground) Y%)`, `bg-size-[200%] bg-clip-text text-transparent animate-[shimmer_4s_infinite_linear]` | Custom shimmer | **Adopt prompt-kit**: exact CSS gradient approach via `Shader` + `TextPaint` |
| **Steps** | Same as ChainOfThought but triggers support `leftIcon`, `swapIconOnHover` | N/A | **Adopt prompt-kit**: reuse ChainOfThought infrastructure |

---

## shadcn/ui Design Tokens (Source of Truth)

prompt-kit builds on shadcn/ui. These CSS variables map directly to our design tokens:

```xml
<!-- res/values/design_tokens.xml -- PROMPT-KIT EXACT TOKENS -->
<resources>
    <!-- Colors (CSS variable names preserved for reference) -->
    <color name="pk_foreground">#FAFAFA</color>           <!-- --foreground -->
    <color name="pk_muted_foreground">#A1A1AA</color>     <!-- --muted-foreground -->
    <color name="pk_primary">#3B82F6</color>              <!-- --primary -->
    <color name="pk_primary_foreground">#FFFFFF</color>   <!-- --primary-foreground -->
    <color name="pk_border">#3F3F46</color>               <!-- --border -->
    <color name="pk_background">#0A0A0B</color>           <!-- --background -->
    <color name="pk_card">#18181B</color>                 <!-- --card -->
    <color name="pk_card_foreground">#FAFAFA</color>      <!-- --card-foreground -->
    <color name="pk_muted">#27272A</color>                <!-- --muted -->
    <color name="pk_destructive">#EF4444</color>          <!-- --destructive -->
    <color name="pk_ring">#3B82F6</color>                 <!-- --ring -->

    <!-- Semantic aliases for components -->
    <color name="pk_message_bg">@color/pk_muted</color>           <!-- MessageContent bg-secondary -->
    <color name="pk_message_text">@color/pk_foreground</color>    <!-- prose text-foreground -->
    <color name="pk_input_bg">@color/pk_background</color>        <!-- PromptInput bg-background -->
    <color name="pk_input_border">@color/pk_border</color>        <!-- PromptInput border-input -->
    <color name="pk_code_bg">@color/pk_card</color>               <!-- CodeBlock bg-card -->
    <color name="pk_code_border">@color/pk_border</color>         <!-- CodeBlock border-border -->
    <color name="pk_tool_bg">@color/pk_background</color>         <!-- Tool bg-background -->
    <color name="pk_tool_border">@color/pk_border</color>         <!-- Tool border-border -->
    <color name="pk_hover_bg">@color/pk_muted</color>             <!-- HoverCard bg-muted -->

    <!-- State colors (Tool badges) -->
    <color name="pk_state_streaming_bg">#1E3A5F</color>   <!-- bg-blue-900/30 -->
    <color name="pk_state_streaming_text">#60A5FA</color> <!-- text-blue-400 -->
    <color name="pk_state_ready_bg">#7C2D12</color>       <!-- bg-orange-900/30 -->
    <color name="pk_state_ready_text">#FBBF24</color>     <!-- text-orange-400 -->
    <color name="pk_state_done_bg">#064E3B</color>        <!-- bg-green-900/30 -->
    <color name="pk_state_done_text">#34D399</color>      <!-- text-green-400 -->
    <color name="pk_state_error_bg">#7F1D1D</color>       <!-- bg-red-900/30 -->
    <color name="pk_state_error_text">#F87171</color>     <!-- text-red-400 -->

    <!-- Connector bar (ChainOfThought/Steps) -->
    <color name="pk_connector_bar">#3B82F633</color>      <!-- bg-primary/20 -->

    <!-- Radii (exact from prompt-kit) -->
    <dimen name="pk_radius_sm">4dp</dimen>        <!-- rounded-sm -->
    <dimen name="pk_radius_md">8dp</dimen>        <!-- rounded-md -->
    <dimen name="pk_radius_lg">12dp</dimen>       <!-- rounded-lg -->
    <dimen name="pk_radius_xl">16dp</dimen>       <!-- rounded-xl -->
    <dimen name="pk_radius_3xl">24dp</dimen>      <!-- rounded-3xl -->
    <dimen name="pk_radius_full">9999dp</dimen>   <!-- rounded-full -->

    <!-- Spacing (4dp base) -->
    <dimen name="pk_space_1">4dp</dimen>
    <dimen name="pk_space_2">8dp</dimen>
    <dimen name="pk_space_3">12dp</dimen>
    <dimen name="pk_space_4">16dp</dimen>
    <dimen name="pk_space_5">20dp</dimen>
    <dimen name="pk_space_6">24dp</dimen>
    <dimen name="pk_space_8">32dp</dimen>

    <!-- Typography -->
    <dimen name="pk_text_xs">11sp</dimen>       <!-- text-xs -->
    <dimen name="pk_text_sm">13sp</dimen>       <!-- text-sm -->
    <dimen name="pk_text_base">15sp</dimen>     <!-- text-base -->
    <dimen name="pk_text_lg">17sp</dimen>       <!-- text-lg -->
    <dimen name="pk_text_code">13sp</dimen>     <!-- text-[13px] for code -->

    <!-- Shadows -->
    <dimen name="pk_shadow_xs">1dp</dimen>      <!-- shadow-xs -->

    <!-- Animation durations -->
    <integer name="pk_duration_fast">150</integer>    <!-- transition-colors duration-150 -->
    <integer name="pk_duration_normal">200</integer>
    <integer name="pk_duration_slow">300</integer>
    <integer name="pk_shimmer_duration">4000</integer> <!-- animate-[shimmer_4s_infinite_linear] -->
</resources>
```

---

## Component Mapping (prompt-kit → Android)

| prompt-kit Component | Android Implementation | Key Classes/Files |
|---------------------|----------------------|-------------------|
| `Message` + `MessageAvatar` + `MessageContent` + `MessageActions` + `MessageAction` | `MessageViewHolder` (multi-type: USER/ASSISTANT/SYSTEM/TOOL/REASONING) | `MessageViewHolder.kt`, `item_message.xml`, `MessageAvatarView.kt` |
| `ChatContainer` (Root/Content/ScrollAnchor) | `ChatRecyclerView` + `StickToBottomScrollListener` | `ChatRecyclerView.kt`, `StickToBottomScrollListener.kt` |
| `PromptInput` + `PromptInputTextarea` + `PromptInputActions` + `PromptInputAction` | `PromptInputLayout` (MaterialCardView rounded-3xl) + `AutoSizingEditText` + `PromptInputActions` | `PromptInputLayout.kt`, `AutoSizingEditText.kt`, `PromptInputActions.kt` |
| `Markdown` | `MarkdownRenderer` (Markwon + remark-gfm + breaks, per-block memoization) | `MarkdownRenderer.kt`, `MarkdownBlockView.kt` |
| `CodeBlock` + `CodeBlockCode` + `CodeBlockGroup` | `CodeBlockView` (Shiki via `shiki-java` or server-prehighlighted) | `CodeBlockView.kt`, `shiki-java` dependency |
| `Tool` | `ToolCallView` (Collapsible, state icons, badges, JSON pretty) | `ToolCallView.kt`, `CollapsibleView.kt`, `ToolState` enum |
| `Source` + `SourceTrigger` + `SourceContent` | `CitationChip` (HoverCard pattern: chip + popup on long-press/hover) | `CitationChip.kt`, `CitationPopupWindow.kt` |
| `ChainOfThought` + `ChainOfThoughtStep` + `ChainOfThoughtTrigger` + `ChainOfThoughtContent` + `ChainOfThoughtItem` | `ReasoningView` (Collapsible steps, connector bar, circle indicators) | `ReasoningView.kt`, `ReasoningStepView.kt`, `CollapsibleView.kt` |
| `ThinkingBar` | `ThinkingBarView` (TextShimmer + optional stop button) | `ThinkingBarView.kt`, `TextShimmerTextView.kt` |
| `ResponseStream` + `useTextStream` | `TypewriterAnimator` (typewriter/fade modes, speed/chunk/delay config) | `TypewriterAnimator.kt`, `StreamConfig.kt` |
| `Loader` (11 variants) | `PromptKitLoader` (enum variant, size, text) | `PromptKitLoader.kt`, `LoaderVariant` enum |
| `TextShimmer` | `TextShimmerTextView` (Shader gradient animation) | `TextShimmerTextView.kt` |
| `Steps` + `StepsTrigger` + `StepsContent` + `StepsBar` + `StepsItem` | Reuse `ReasoningView` infrastructure (shared `CollapsibleView`) | `ReasoningView.kt` (extended) |
| `SystemMessage` | `SystemBannerView` (top of chat list, dismissible) | `SystemBannerView.kt`, `item_system_banner.xml` |
| `FileUpload` | `AttachmentPicker` (integrated in `PromptInputActions`) | `AttachmentPicker.kt`, `AttachmentPreviewView.kt` |
| `Image` | `ImageMessageView` (Coil, inside Markdown or standalone) | `ImageMessageView.kt` |
| `ScrollButton` | `StickToBottomFab` (shows when not at bottom) | `StickToBottomFab.kt` |
| `FeedbackBar` | `MessageAction` with feedback (long-press assistant message) | `MessageActionsView.kt` (feedback action) |

---

## New Dependencies (Exact prompt-kit Stack)

```kotlin
// build.gradle.kts
dependencies {
    // Markdown: marked + react-markdown + remark-gfm + remark-breaks equivalents
    implementation("io.github.noties.markwon:markwon:4.6.2")
    implementation("io.github.noties.markwon:markwon-ext-strikethrough:4.6.2")
    implementation("io.github.noties.markwon:markwon-ext-tables:4.6.2")
    implementation("io.github.noties.markwon:markwon-ext-tasklist:4.6.2")
    implementation("io.github.noties.markwon:markwon-ext-gfm:4.6.2")        // GFM = remark-gfm
    implementation("io.github.noties.markwon:markwon-ext-breaks:4.6.2")     // remark-breaks

    // Code highlighting: Shiki (prompt-kit uses shiki, not prettify)
    implementation("com.github.shiqi47:shiki-java:1.0.0")  // or similar JVM port
    // If no JVM Shiki: pre-highlight on server (Python shiki) → send HTML to app

    // Collapsible (prompt-kit uses @/components/ui/collapsible)
    implementation("com.google.android.material:material:1.12.0")  // has Collapsible via MotionLayout or custom

    // HoverCard / Tooltip (prompt-kit uses @/components/ui/hover-card + tooltip)
    implementation("androidx.tooltip:tooltip:1.0.0")  // or custom PopupWindow-based

    // Image loading (prompt-kit Image component)
    implementation("io.coil-kt:coil:2.5.0")

    // Animations (shimmer, fade, collapsible)
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    // Icons (prompt-kit uses lucide-react)
    implementation("com.github.vipulasri:lucide-android:1.0.0")  // or material icons subset

    // Avatar (prompt-kit uses @/components/ui/avatar)
    implementation("com.github.islamkhsh:CircleImageView:3.0.1")  // or Material ShapeableImageView
}
```

---

## Data Model (prompt-kit Compatible)

```kotlin
// MessageEntity.kt - matches prompt-kit message structure
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,                    // "user" | "assistant" | "system" | "tool"
    val content: String,                 // markdown content (prompt-kit: children string)
    val timestamp: Long,
    val voiceSession: String?,

    // Tool calls (prompt-kit ToolPart[])
    val toolCallsJson: String? = null,   // List<ToolPart>

    // Sources (prompt-kit Source[])
    val citationsJson: String? = null,   // List<Citation>

    // Reasoning (prompt-kit ChainOfThought steps)
    val reasoningJson: String? = null,   // List<ReasoningStep>

    // Streaming state
    val isStreaming: Boolean = false,
    val streamMode: String = "typewriter", // "typewriter" | "fade"
    val streamSpeed: Int = 20,           // 1-100
)

// ToolPart (exact from prompt-kit/tool.tsx)
data class ToolPart(
    val type: String,
    val state: ToolState,                // INPUT_STREAMING | INPUT_AVAILABLE | OUTPUT_AVAILABLE | OUTPUT_ERROR
    val input: Map<String, Any>? = null,
    val output: Map<String, Any>? = null,
    val toolCallId: String? = null,
    val errorText: String? = null
)
enum class ToolState { INPUT_STREAMING, INPUT_AVAILABLE, OUTPUT_AVAILABLE, OUTPUT_ERROR }

// Citation (exact from prompt-kit/source.tsx)
data class Citation(
    val href: String,
    val title: String,
    val description: String
)

// ReasoningStep (exact from prompt-kit/chain-of-thought.tsx)
data class ReasoningStep(
    val id: String,
    val title: String,        // ChainOfThoughtTrigger content
    val content: String,      // ChainOfThoughtItem content
    val isLast: Boolean = false,
    val isOpen: Boolean = true  // defaultOpen=true
)
```

---

## Implementation Order (Strict prompt-kit Parity)

| Session | prompt-kit Components | Deliverable |
|---------|----------------------|-------------|
| 1 | **Design Tokens + Message + ChatContainer** | `design_tokens.xml`, `MessageViewHolder` (Avatar + Content + Actions), `StickToBottomScrollListener`, `ChatRecyclerView` |
| 2 | **Markdown + CodeBlock** | `MarkdownRenderer` (GFM + breaks, per-block memo), `CodeBlockView` (Shiki, github-light, rounded-xl) |
| 3 | **PromptInput + PromptInputTextarea + PromptInputActions** | `PromptInputLayout` (rounded-3xl, shadow-xs), `AutoSizingEditText` (min-h-44px), action chips with Tooltips |
| 4 | **Tool + Source** | `ToolCallView` (Collapsible, state icons/badges, JSON), `CitationChip` (HoverCard: chip + popup with favicon/title/desc) |
| 5 | **ChainOfThought + Steps** | `ReasoningView` (Collapsible steps, connector bar bg-primary/20, Circle 2px, defaultOpen=true) |
| 6 | **ThinkingBar + ResponseStream + Loader + TextShimmer** | `ThinkingBarView` (TextShimmer + stop button dotted), `TypewriterAnimator` (typewriter/fade, speed/chunk/delay), `PromptKitLoader` (11 variants), `TextShimmerTextView` (Shader gradient) |
| 7 | **SystemMessage + FileUpload + Image + ScrollButton + Feedback** | `SystemBannerView`, `AttachmentPicker` in PromptInputActions, `ImageMessageView` (Coil), `StickToBottomFab`, feedback action in MessageActions |
| 8 | **Integration + Device Test** | Full flow: all components working, history persists, API 27 verified |

---

## Files to Create/Modify (Exact Count)

### New Files (28)
```
res/values/design_tokens.xml
res/layout/item_message.xml                 # Message (Avatar + Content + Actions)
res/layout/item_message_avatar.xml          # MessageAvatar
res/layout/item_message_content.xml         # MessageContent (markdown container)
res/layout/item_message_actions.xml         # MessageActions
res/layout/item_tool_call.xml               # Tool (Collapsible)
res/layout/item_citation_chip.xml           # SourceTrigger (chip)
res/layout/item_citation_popup.xml          # SourceContent (HoverCard)
res/layout/item_reasoning_step.xml          # ChainOfThoughtStep
res/layout/item_system_banner.xml           # SystemMessage
res/layout/item_attachment_preview.xml      # FileUpload preview
res/drawable/bg_message_content.xml         # rounded-lg bg-secondary
res/drawable/bg_prompt_input.xml            # rounded-3xl border shadow-xs
res/drawable/bg_tool_card.xml               # rounded-lg border
res/drawable/bg_citation_chip.xml           # rounded-full bg-muted
res/drawable/bg_code_block.xml              # rounded-xl border-border bg-card
res/drawable/connector_bar.xml              # w-2px bg-primary/20
res/drawable/step_circle.xml                # size-2 fill-current
src/main/java/.../ui/MessageViewHolder.kt
src/main/java/.../ui/MessageAvatarView.kt
src/main/java/.../ui/MarkdownRenderer.kt
src/main/java/.../ui/MarkdownBlockView.kt
src/main/java/.../ui/CodeBlockView.kt
src/main/java/.../ui/PromptInputLayout.kt
src/main/java/.../ui/AutoSizingEditText.kt
src/main/java/.../ui/PromptInputActions.kt
src/main/java/.../ui/ToolCallView.kt
src/main/java/.../ui/CollapsibleView.kt
src/main/java/.../ui/CitationChip.kt
src/main/java/.../ui/CitationPopupWindow.kt
src/main/java/.../ui/ReasoningView.kt
src/main/java/.../ui/ReasoningStepView.kt
src/main/java/.../ui/ThinkingBarView.kt
src/main/java/.../ui/TextShimmerTextView.kt
src/main/java/.../ui/TypewriterAnimator.kt
src/main/java/.../ui/PromptKitLoader.kt
src/main/java/.../ui/SystemBannerView.kt
src/main/java/.../ui/AttachmentPicker.kt
src/main/java/.../ui/StickToBottomScrollListener.kt
src/main/java/.../ui/StickToBottomFab.kt
src/main/java/.../db/MessageEntity.kt (replace)
```

### Modified Files (6)
```
res/values/colors.xml       → reference design_tokens
res/values/themes.xml       → use token colors
res/layout/activity_main.xml → replace chat UI with prompt-kit structure
build.gradle.kts            → add dependencies
src/main/java/.../voice/VoiceUi.kt → integrate new components
src/main/java/.../voice/ChatListAdapter.kt → replace with multi-view-type using MessageViewHolder
```

### Deleted/Replaced Files (4)
```
res/layout/item_chat_message.xml        → replaced by item_message.xml
res/drawable/bubble_user.xml            → deleted (no bubble tails)
res/drawable/bubble_assistant.xml       → deleted
src/main/java/.../voice/ChatMessage.kt  → replaced by MessageEntity
```

---

## Risk Flags (Resolved by prompt-kit Choices)

| Risk | prompt-kit Resolution |
|------|----------------------|
| API 27 Material Collapsible | Use custom `CollapsibleView` (MotionLayout or simple expand/collapse) — prompt-kit uses Radix Collapsible, we replicate behavior |
| Shiki on Android | Option A: `shiki-java` (JVM port). Option B: Server pre-highlights code blocks → sends HTML. Option B preferred (smaller APK, matches prompt-kit SSR pattern). |
| HoverCard on touch | Long-press → show `PopupWindow` (HoverCardContent). Desktop: hover via `OnHoverListener` (API 27+). |
| TextShimmer gradient | `Shader` + `LinearGradient` on `TextPaint`, `ValueAnimator` for translation — exact CSS gradient math. |
| Stick-to-bottom | `RecyclerView.OnScrollListener` + `smoothScrollToPosition` with `resize="smooth"` logic — replicate `use-stick-to-bottom` algorithm. |
| Autosizing EditText | `TextViewCompat.setAutoSizeTextTypeWithDefaults` + `OnTextChangedListener` for height adjustment — matches `PromptInputTextarea` logic. |

---

## Success Criteria (prompt-kit Parity Checklist)

- [ ] **Message**: Avatar (assistant only), Content (markdown prose), Actions (tooltip actions), no bubble tails
- [ ] **ChatContainer**: Stick-to-bottom on resize/new message, smooth scroll, instant initial
- [ ] **PromptInput**: rounded-3xl, border, shadow-xs, autosizing textarea (min 44px), action buttons with tooltips
- [ ] **Markdown**: GFM + breaks, split into blocks, memoized per block, inline code `bg-primary-foreground rounded-sm px-1 font-mono text-sm`
- [ ] **CodeBlock**: Shiki github-light, rounded-xl border-border bg-card, text-[13px], overflow-x-auto
- [ ] **Tool**: Collapsible, state icons (Loader2/Settings/CheckCircle/XCircle), state badges (exact colors), JSON input/output, toolCallId
- [ ] **Source**: Chip (favicon + domain, rounded-full bg-muted), long-press/hover → card (favicon + title + description)
- [ ] **ChainOfThought**: Collapsible steps, connector bar (w-2px bg-primary/20), Circle (2px), defaultOpen=true
- [ ] **ThinkingBar**: TextShimmer on "Thinking", optional stop button (dotted border-b text-sm)
- [ ] **ResponseStream**: Typewriter (speed 20, chunkSize) + Fade (word segments, fadeDuration, segmentDelay)
- [ ] **Loader**: All 11 variants, size sm/md/lg, text support
- [ ] **TextShimmer**: Exact CSS gradient: `linear-gradient(to right, muted-foreground X%, foreground 50%, muted-foreground Y%)`, 4s linear infinite
- [ ] **Steps**: Same as ChainOfThought, triggers support leftIcon
- [ ] **SystemMessage**: Top banner, dismissible, variants (info/warning/error)
- [ ] **FileUpload**: Integrated in PromptInputActions, preview chips
- [ ] **Image**: Coil in markdown + standalone
- [ ] **ScrollButton**: FAB shows when not at bottom, taps → smooth scroll to bottom
- [ ] **Feedback**: Long-press assistant message → feedback actions

---

## Next Action

Say **"go"** and I'll start **Session 1**: Design tokens + Message + ChatContainer (Avatar, Content, Actions, StickToBottomScrollListener).