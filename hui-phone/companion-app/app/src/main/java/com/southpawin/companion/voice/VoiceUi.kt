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
        commandExecutor: CommandExecutor
    ) {
        activity = act
        chatRecyclerView = chatRv
        promptInputLayout = promptInput

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
    ) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return

        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session

        // Add user message immediately to UI
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("user", trimmedText, session)
        }

        // Clear input
        promptInputLayout?.clearText()

        // Send chat event
        val payload = ChatEvent.ChatPayload(trimmedText, session)
        eventBus.tryPost(ChatEvent(payload))
        setStatus("Sent…")
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