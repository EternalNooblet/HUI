package com.hui.companion.voice

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.hui.companion.db.ChatSessionSummary
import com.hui.companion.db.MessageRepositoryProvider
import com.hui.companion.transport.Transport
import com.hui.companion.ui.PromptInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * VoiceUi — one session, one window, all modalities.
 *
 * Voice is a turn origin inside the shared chat session, not a mode:
 * tap mic -> record WAV -> POST /api/audio/transcribe -> prompt.submit.
 * The reply always lands as an assistant bubble; native TTS read-out
 * happens only for voice-originated turns (MainActivity wires Transport's
 * onAssistantReply to CommandExecutor.speakText). Text/image turns arrive
 * as message.complete with voiceOrigin=false and never speak.
 *
 * The STT transcript lands as the user bubble (handleVoiceTranscript)
 * before the reply.
 */
object VoiceUi {

    private var activity: AppCompatActivity? = null
    private var chatRecyclerView: com.hui.companion.ui.ChatRecyclerView? = null
    private var promptInputLayout: PromptInputLayout? = null
    private var thinkingBar: com.hui.companion.ui.ThinkingBarView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thinkingTimeout = Runnable { thinkingBar?.stop() }
    private val recordingTimeout = Runnable { onRecordingAbandoned() }
    // Server reply timeout is 180s; hide the indicator at 90s so a lost
    // reply doesn't leave it spinning forever (a late reply still lands).
    private val THINKING_TIMEOUT_MS = 90_000L
    // 60s hard cap + 2s grace: the bar must not outlive a dead recorder.
    private val RECORDING_TIMEOUT_MS = 62_000L

    private var isListening = false
    private var loadJob: Job? = null
    private var voiceInput: VoiceInput? = null
    private var currentVoiceSession: String? = null
    private var chatAdapter: ChatListAdapter? = null
    private var messageRepository: com.hui.companion.db.MessageRepository? = null

    /** True while the mic is on (recording). Reply routing must NEVER read
     *  this — origin is carried by the command type (speak vs chat_reply). */
    var voiceRecording = false
        private set

    fun attach(
        act: AppCompatActivity,
        chatRv: com.hui.companion.ui.ChatRecyclerView,
        promptInput: PromptInputLayout,
        transport: Transport,
        thinking: com.hui.companion.ui.ThinkingBarView? = null
    ) {
        activity = act
        chatRecyclerView = chatRv
        promptInputLayout = promptInput
        thinkingBar = thinking

        messageRepository = MessageRepositoryProvider.getInstance(act)

        // Setup chat RecyclerView. Do NOT replace the layoutManager:
        // ChatRecyclerView creates its own in init and hands that exact
        // instance to StickToBottomScrollListener. Replacing it here
        // orphaned the listener (its manager had no children, so
        // at-bottom tracking and its scrolls were dead).
        chatAdapter = ChatListAdapter(act)
        chatRecyclerView?.adapter = chatAdapter

        // Load existing messages
        loadMessages()

        // Setup PromptInputLayout callbacks
        promptInputLayout?.setOnSendListener { text ->
            sendChatMessage(text, transport)
        }
        promptInputLayout?.setOnVoiceClickListener {
            toggleVoiceRecording(transport)
        }
    }

    private fun loadMessages() {
        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session
        loadJob?.cancel()
        val scope = CoroutineScope(Dispatchers.Main)
        loadJob = scope.launch {
            messageRepository?.observeSessionMessages(session)?.collect { messages ->
                // submitList is async (AsyncListDiffer): itemCount right
                // after the call is still the OLD list, so scrolling
                // immediately landed on the previous last item and the
                // new message stayed below the viewport. The Runnable
                // runs after the diff is committed and laid out.
                chatAdapter?.submitList(messages.map { entity ->
                    com.hui.companion.voice.ChatMessage(
                        id = entity.id,
                        role = entity.role,
                        text = entity.content,
                        timestamp = entity.timestamp,
                        voiceSession = entity.voiceSession
                    )
                }) {
                    // Own messages always jump to bottom: the user just
                    // tapped send, so their bubble must be visible even
                    // if they were scrolled up reading history. Assistant
                    // replies respect stick-to-bottom (FAB covers the
                    // scrolled-up case). The unconditional own-message
                    // path also bypasses an at-bottom race: user inserts
                    // land mid keyboard-hide resize, which can flip
                    // isAtBottom to false (last child partially visible
                    // while the RecyclerView height is transitioning)
                    // before this callback runs.
                    val lastRole = messages.lastOrNull()?.role
                    if (lastRole == "user" || chatRecyclerView?.isUserAtBottom() == true) {
                        scrollToBottom()
                    }
                }
            }
        }
    }

    /** Switch the chat window to an existing session (prompt-kit sidebar tap). */
    fun switchTo(sessionId: String) {
        if (sessionId == currentVoiceSession) return
        currentVoiceSession = sessionId
        chatAdapter?.submitList(emptyList())
        loadMessages()
    }

    /** Start a fresh chat session (prompt-kit "New Chat" button). */
    fun newChat() {
        currentVoiceSession = VoiceInput.generateSessionId()
        chatAdapter?.submitList(emptyList())
        loadMessages()
    }

    fun currentSessionId(): String? = currentVoiceSession

    /** All chat sessions for the sidebar list, newest first. */
    fun observeSessions(): Flow<List<ChatSessionSummary>> =
        messageRepository?.observeSessions() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /** Tap mic: start recording; tap again: stop, transcribe, submit. */
    fun toggleVoiceRecording(transport: Transport) {
        if (voiceRecording) {
            stopVoiceRecording()
        } else {
            startVoiceRecording(transport)
        }
    }

    private fun startVoiceRecording(transport: Transport) {
        val act = activity ?: return
        // Same gate as sendChatMessage: starting while unreachable burns
        // the mic and drops every chunk locally with no feedback.
        if (!transport.isAuthenticated()) {
            act.runOnUiThread {
                android.widget.Toast.makeText(
                    act,
                    "Not authenticated — voice not sent. Scan a QR in Settings to reconnect.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (!transport.isConnected) {
            transport.connect()
            act.runOnUiThread {
                android.widget.Toast.makeText(
                    act,
                    "Connecting… voice not sent yet. Try again in a moment.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // Shared-session rule: reuse the visible session id. Never
        // generate here — a voice turn belongs in the same window and
        // Room history as the text turns around it.
        val sessionId = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = sessionId

        voiceInput = VoiceInput(
            act, sessionId,
            onWavReady = { wav -> transcribeAndSubmit(wav, sessionId, transport) },
            onAutoCancel = { mainHandler.post { onVoiceTerminal(cancelled = true) } },
            onHardCap = { mainHandler.post { onVoiceTerminal(cancelled = false, hardCapped = true) } },
            onError = { message -> mainHandler.post { onVoiceError(message) } }
        )
        voiceInput?.start()

        voiceRecording = true
        isListening = true
        act.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = true
        }
        showListening()
    }

    private fun stopVoiceRecording() {
        // Tap-stop: VoiceInput finalizes the WAV and calls onWavReady,
        // which transcribes + submits (async — UI already left listening).
        voiceInput?.stop()
        voiceInput = null

        voiceRecording = false
        isListening = false
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = false
        }
        showTranscribing()
    }

    // VoiceInput terminal callbacks. Each must leave the UI in a
    // non-listening state exactly once, whatever order they race in.
    private fun onVoiceTerminal(cancelled: Boolean, hardCapped: Boolean = false) {
        if (!voiceRecording && voiceInput == null) return
        voiceInput = null
        voiceRecording = false
        isListening = false
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = false
        }
        if (cancelled) {
            stopThinking()
            toast("No voice detected — recording cancelled")
        } else if (hardCapped) {
            showTranscribing()
        }
    }

    private fun onVoiceError(message: String) {
        voiceInput = null
        voiceRecording = false
        isListening = false
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = false
        }
        stopThinking()
        toast(message)
        boop()
    }

    private fun onRecordingAbandoned() {
        // The 62s handler: if VoiceInput died without a callback, don't
        // leave the bar spinning on a dead recorder.
        if (voiceRecording) {
            voiceInput?.cancel()
            onVoiceTerminal(cancelled = false)
        }
    }

    /**
     * WAV ready (tap-stop / hard cap): transcribe via the gateway, show the
     * transcript as the user bubble, then submit it as a voice turn.
     * Runs the POST off the main thread; UI hops back for the bubble.
     */
    private fun transcribeAndSubmit(wav: java.io.File, sessionId: String, transport: Transport) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val transcript = try {
                Transcriber.transcribe(wav)
            } catch (e: Exception) {
                null
            } finally {
                try { wav.delete() } catch (_: Exception) {}
            }
            mainHandler.post {
                if (transcript == null) {
                    stopThinking()
                    toast("Voice failed — check connection, tap mic to retry")
                    boop()
                    return@post
                }
                if (transcript.isBlank()) {
                    stopThinking()
                    toast("No speech detected — tap mic to retry")
                    boop()
                    return@post
                }
                handleVoiceTranscript(transcript, sessionId)
                if (!transport.submitPrompt(transcript, emptyList(), voiceOrigin = true)) {
                    stopThinking()
                    toast("Connecting… voice not sent yet. Try again in a moment.")
                }
            }
        }
    }

    /**
     * The user's own words from STT. Inserts the user bubble in the shared
     * session. Never touches the text input.
     */
    fun handleVoiceTranscript(text: String, voiceSession: String) {
        if (text.isBlank()) return
        val session = voiceSession.ifEmpty { currentVoiceSession ?: return }
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("user", text, session)
        }
        // STT done — the gateway is now thinking. Flip the bar so it
        // doesn't sit on "Transcribing…" through the Hermes turn.
        showThinking()
    }

    /** Keep the thinking bar alive while a reply streams (Transport
     *  onTurnProgress). Resets the hide timer without relabelling. */
    fun nudgeThinking() {
        mainHandler.removeCallbacks(thinkingTimeout)
        mainHandler.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS)
    }

    /** Voice-originated assistant reply: bubble + native TTS. The
     *  MainActivity wiring adds TTS via CommandExecutor.speakText. */
    fun handleVoiceReply(text: String, voiceSession: String) {
        handleAssistantBubble(text, voiceSession)
    }

    fun handleChatReply(text: String, voiceSession: String) {
        handleAssistantBubble(text, voiceSession)
    }

    private fun handleAssistantBubble(text: String, voiceSession: String) {
        stopThinking()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("assistant", text, voiceSession)
        }
    }

    private fun sendChatMessage(
        text: String,
        transport: Transport
    ): Boolean {
        val trimmedText = text.trim()
        // Peek first: takeAll() must only run after auth/connect checks
        // pass, otherwise a blocked send consumes (loses) the images.
        val pending = com.hui.companion.util.AttachmentState.peek()
        if (trimmedText.isEmpty() && pending.isEmpty()) return false
        if (com.hui.companion.util.AttachmentState.isUploading()) {
            activity?.runOnUiThread {
                android.widget.Toast.makeText(
                    activity, "Still uploading image…", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        // Unauthenticated: block BEFORE the local echo. Inserting first
        // (old behavior) put the message in the window looking delivered
        // even though Transport dropped it — the user could not tell it
        // was never received. Keep the text in the input, say why.
        if (!transport.isAuthenticated()) {
            activity?.runOnUiThread {
                android.widget.Toast.makeText(
                    activity,
                    "Not authenticated — message not sent. Scan a QR in Settings to reconnect.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
        if (!transport.isConnected) {
            // Authenticated but socket not up yet (e.g. right after a QR
            // scan): kick connect and don't echo yet — otherwise the
            // message lands in the window and then drops silently.
            transport.connect()
            activity?.runOnUiThread {
                android.widget.Toast.makeText(
                    activity,
                    "Connecting… message not sent yet. Try again in a moment.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return false
        }

        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session

        // Checks passed — now consume the attachments.
        val taken = com.hui.companion.util.AttachmentState.takeAll()
        // Local echo renders through MessageViewHolder markdown. The echo
        // uses the LOCAL content uri (Pending.id) so history images render
        // from the phone copy; the wire path (Pending.url, the gateway
        // path from /api/chat/image-upload) goes via image.attach inside
        // Transport — the prompt text itself carries no URLs.
        val imageMarkdown = taken.joinToString("\n") { "![${it.name}](${it.id})" }
        val echoText = when {
            trimmedText.isNotEmpty() && imageMarkdown.isNotEmpty() -> "$trimmedText\n\n$imageMarkdown"
            trimmedText.isNotEmpty() -> trimmedText
            else -> imageMarkdown
        }
        // Attachments ride as image.attach RPCs; text is just text.
        val payloadText = trimmedText.ifEmpty { "Describe the attached image." }

        // Add user message immediately to UI (markdown renders as an image)
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("user", echoText, session)
        }

        // Submit directly (input is cleared by PromptInputLayout on true).
        // Socket down -> restore the attachments so nothing is lost.
        val sent = transport.submitPrompt(payloadText, taken.map { it.url }, voiceOrigin = false)
        if (!sent) {
            com.hui.companion.util.AttachmentState.restore(taken)
            activity?.runOnUiThread {
                android.widget.Toast.makeText(
                    activity,
                    "Connecting… message not sent yet. Try again in a moment.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
        startThinking()
        return true
    }

    private fun showListening() {
        clearBars()
        thinkingBar?.setText("Listening…")
        thinkingBar?.start()
        mainHandler.postDelayed(recordingTimeout, RECORDING_TIMEOUT_MS)
    }

    private fun showTranscribing() {
        clearBars()
        thinkingBar?.setText("Transcribing…")
        thinkingBar?.start()
        mainHandler.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS)
    }

    private fun showThinking() {
        clearBars()
        thinkingBar?.setText("Thinking…")
        thinkingBar?.start()
        mainHandler.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS)
    }

    private fun startThinking() {
        showThinking()
    }

    private fun stopThinking() {
        clearBars()
        thinkingBar?.stop()
    }

    private fun clearBars() {
        mainHandler.removeCallbacks(thinkingTimeout)
        mainHandler.removeCallbacks(recordingTimeout)
    }

    private fun toast(message: String) {
        activity?.runOnUiThread {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun boop() {
        // Short ToneGenerator beep — no asset needed, same affordance as
        // the text-chat error path.
        runCatching {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 50)
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ANSWER, 150)
            tg.release()
        }
    }

    private fun scrollToBottom() {
        activity?.runOnUiThread {
            chatRecyclerView?.scrollToBottomInstant()
        }
    }

    fun isActive(): Boolean = isListening
}
