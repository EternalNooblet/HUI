package com.southpawin.companion.voice

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.southpawin.companion.R
import com.southpawin.companion.db.MessageEntity
import com.southpawin.companion.db.MessageRepositoryProvider
import com.southpawin.companion.event.EventBus
import com.southpawin.companion.event.VoiceCancelEvent
import com.southpawin.companion.event.VoiceSendEvent
import com.southpawin.companion.event.VoiceStartEvent
import com.southpawin.companion.event.ChatEvent
import com.southpawin.companion.transport.CommandExecutor
import com.southpawin.companion.transport.Transport
import com.southpawin.companion.ui.PromptInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * VoiceUi — chat-mode voice input.
 *
 * Voice is an input method inside the chat flow, not a separate mode.
 * Tap the mic button → record → transcript lands in the text input → user edits/sends.
 */
object VoiceUi {

    private var activity: AppCompatActivity? = null
    private var chatRecyclerView: com.southpawin.companion.ui.ChatRecyclerView? = null
    private var promptInputLayout: PromptInputLayout? = null
    private var thinkingBar: com.southpawin.companion.ui.ThinkingBarView? = null
    private val thinkingHandler = Handler(Looper.getMainLooper())
    private val thinkingTimeout = Runnable { thinkingBar?.stop() }
    // Server reply timeout is 180s; hide the indicator at 90s so a lost
    // reply doesn't leave it spinning forever (a late reply still lands).
    private val THINKING_TIMEOUT_MS = 90_000L

    private var isListening = false
    private var voiceInput: VoiceInput? = null
    private var currentVoiceSession: String? = null
    private var chatAdapter: ChatListAdapter? = null
    private var messageRepository: com.southpawin.companion.db.MessageRepository? = null

    /** True while a voice recording session is active (mic button toggled on). */
    var voiceRecording = false
        private set

    fun attach(
        act: AppCompatActivity,
        chatRv: com.southpawin.companion.ui.ChatRecyclerView,
        promptInput: PromptInputLayout,
        eventBus: EventBus,
        transport: Transport,
        commandExecutor: CommandExecutor,
        thinking: com.southpawin.companion.ui.ThinkingBarView? = null
    ) {
        activity = act
        chatRecyclerView = chatRv
        promptInputLayout = promptInput
        thinkingBar = thinking

        messageRepository = MessageRepositoryProvider.getInstance(act)

        // Setup chat RecyclerView
        chatAdapter = ChatListAdapter(act)
        chatRecyclerView?.layoutManager = LinearLayoutManager(act)
        chatRecyclerView?.adapter = chatAdapter

        // Load existing messages
        loadMessages()

        // Setup PromptInputLayout callbacks
        promptInputLayout?.setOnSendListener { text ->
            sendChatMessage(text, eventBus, transport)
        }
        promptInputLayout?.setOnVoiceClickListener {
            toggleVoiceRecording(eventBus, transport)
        }
    }

    private fun loadMessages() {
        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            messageRepository?.observeSessionMessages(session)?.collect { messages ->
                chatAdapter?.submitList(messages.map { entity ->
                    com.southpawin.companion.voice.ChatMessage(
                        id = entity.id,
                        role = entity.role,
                        text = entity.content,
                        timestamp = entity.timestamp,
                        voiceSession = entity.voiceSession
                    )
                })
                scrollToBottom()
            }
        }
    }

    /** Toggle voice recording on/off. When stopped, transcript is inserted into the text input. */
    fun toggleVoiceRecording(eventBus: EventBus, transport: Transport) {
        if (voiceRecording) {
            stopVoiceRecording(eventBus, transport)
        } else {
            startVoiceRecording(eventBus, transport)
        }
    }

    private fun startVoiceRecording(eventBus: EventBus, transport: Transport) {
        val sessionId = VoiceInput.generateSessionId()
        currentVoiceSession = sessionId
        voiceInput = VoiceInput(activity!!, eventBus, sessionId) { rms ->
            // RMS callback retained for future waveform overlay; no UI element currently wired
        }
        voiceInput?.start()

        val payload = VoiceStartEvent.VoiceStartPayload(sessionId)
        eventBus.tryPost(VoiceStartEvent(payload))

        voiceRecording = true
        isListening = true
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = true
        }
        setStatus("Listening…")
    }

    private fun stopVoiceRecording(eventBus: EventBus, transport: Transport) {
        voiceInput?.postVoiceSend()
        voiceInput?.cancel()
        voiceInput = null

        voiceRecording = false
        isListening = false
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = false
        }
        setStatus("Transcribing…")
    }

    /** Called by CommandExecutor when a `speak` command arrives during voice recording.
     * Inserts the transcript into the text input instead of speaking it. */
    fun handleVoiceTranscript(text: String, voiceSession: String) {
        voiceRecording = false
        isListening = false
        activity?.runOnUiThread {
            promptInputLayout?.getVoiceButton()?.isPressed = false
            val current = promptInputLayout?.getText() ?: ""
            val newText = if (current.isNotEmpty()) "$current\n$text" else text
            promptInputLayout?.setText(newText)
        }
        setStatus("Transcript inserted")
    }

    fun handleVoiceState(state: String, voiceSession: String) {
        when (state) {
            "received" -> setStatus("Received, processing…")
            "transcribing" -> setStatus("Transcribing…")
            "failed" -> {
                setStatus("Failed — tap mic to retry")
                voiceRecording = false
                isListening = false
                activity?.runOnUiThread {
                    promptInputLayout?.getVoiceButton()?.isPressed = false
                }
            }
            else -> {}
        }
    }

    fun handleChatReply(text: String, voiceSession: String) {
        stopThinking()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("assistant", text, voiceSession)
        }
        setStatus("Reply received")
    }

    private fun sendChatMessage(
        text: String,
        eventBus: EventBus,
        transport: Transport
    ): Boolean {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return false

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

        // Add user message immediately to UI
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("user", trimmedText, session)
        }

        // Send chat event (input is cleared by PromptInputLayout on true)
        val payload = ChatEvent.ChatPayload(trimmedText, session)
        eventBus.tryPost(ChatEvent(payload))
        startThinking()
        setStatus("Sent…")
        return true
    }

    private fun startThinking() {
        thinkingHandler.removeCallbacks(thinkingTimeout)
        thinkingBar?.start()
        thinkingHandler.postDelayed(thinkingTimeout, THINKING_TIMEOUT_MS)
    }

    private fun stopThinking() {
        thinkingHandler.removeCallbacks(thinkingTimeout)
        thinkingBar?.stop()
    }

    private fun setStatus(text: String) {
    }

    private fun scrollToBottom() {
        activity?.runOnUiThread {
            chatRecyclerView?.scrollToPosition(chatAdapter?.itemCount?.minus(1) ?: 0)
        }
    }

    fun isActive(): Boolean = isListening
}