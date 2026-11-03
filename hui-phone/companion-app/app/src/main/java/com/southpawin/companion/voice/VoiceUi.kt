package com.southpawin.companion.voice

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
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
import com.southpawin.companion.util.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

object VoiceUi {

    private var activity: AppCompatActivity? = null
    private var statusText: TextView? = null
    private var waveView: VoiceWaveView? = null
    private var toggleButton: CircularTalkButton? = null
    private var chatRecyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var chatInputContainer: View? = null
    private var chatInputEditText: EditText? = null
    private var chatSendButton: ImageButton? = null
    private var voiceWaveView: View? = null
    private var voiceStatusText: TextView? = null

    private var isListening = false
    private var voiceInput: VoiceInput? = null
    private var currentVoiceSession: String? = null
    private var chatAdapter: ChatListAdapter? = null
    private var messageRepository: com.southpawin.companion.db.MessageRepository? = null
    private var draftText = ""
    private var currentMode = TalkMode.VOICE

    fun attach(
        act: AppCompatActivity,
        status: TextView?,
        wave: VoiceWaveView,
        btn: CircularTalkButton,
        chatRv: androidx.recyclerview.widget.RecyclerView,
        chatInput: View,
        chatEditText: EditText,
        chatSendBtn: ImageButton,
        voiceWave: View,
        voiceStatus: TextView,
        eventBus: EventBus,
        transport: Transport,
        commandExecutor: CommandExecutor
    ) {
        activity = act
        statusText = status
        waveView = wave
        toggleButton = btn
        chatRecyclerView = chatRv
        chatInputContainer = chatInput
        chatInputEditText = chatEditText
        chatSendButton = chatSendBtn
        voiceWaveView = voiceWave
        voiceStatusText = voiceStatus
        isListening = false
        currentMode = TalkMode.VOICE

        messageRepository = MessageRepositoryProvider.getInstance(act)

        // Setup chat RecyclerView
        chatAdapter = ChatListAdapter(act)
        chatRecyclerView?.layoutManager = LinearLayoutManager(act)
        chatRecyclerView?.adapter = chatAdapter

        // Load existing messages
        loadMessages()

        // Button click (tap = send/voice toggle)
        btn.setOnClickListener { onButtonTap(eventBus, transport) }

        // Mode change listener (swipe)
        btn.setOnModeChangeListener { mode: TalkMode ->
            onModeChanged(mode, eventBus, transport)
        }

        // Chat input: Enter to send
        chatInputEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage(eventBus, transport)
                true
            } else {
                false
            }
        }

        // Chat send button
        chatSendButton?.setOnClickListener { sendChatMessage(eventBus, transport) }

        // Track draft text
        chatInputEditText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draftText = s.toString()
            }
        })

        updateButton()
        updateModeUI()
        Timber.i("VoiceUi attached with dual-mode")
    }

    private fun loadMessages() {
        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            messageRepository?.observeSessionMessages(session)?.collect { messages ->
                chatAdapter?.submitList(messages.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        role = entity.role,
                        text = entity.text,
                        timestamp = entity.timestamp,
                        voiceSession = entity.voiceSession
                    )
                })
                scrollToBottom()
            }
        }
    }

    private fun onButtonTap(
        eventBus: EventBus,
        transport: Transport
    ) {
        when (currentMode) {
            TalkMode.VOICE -> {
                if (isListening) {
                    stopListening(eventBus)
                } else {
                    startListening(eventBus, transport)
                }
            }
            TalkMode.CHAT -> {
                sendChatMessage(eventBus, transport)
            }
        }
    }

    private fun onModeChanged(
        mode: TalkMode,
        eventBus: EventBus,
        transport: Transport
    ) {
        Timber.i("Mode changed: $mode")
        if (currentMode == TalkMode.VOICE && mode == TalkMode.CHAT) {
            // Voice -> Chat: cancel any active voice session
            if (isListening) {
                voiceInput?.postVoiceCancel()
                voiceInput?.cancel()
                voiceInput = null
                isListening = false
                waveView?.reset()
                Timber.i("Voice cancelled due to mode switch to Chat")
            }
        } else if (currentMode == TalkMode.CHAT && mode == TalkMode.VOICE) {
            // Chat -> Voice: preserve draft (already saved in draftText)
            Timber.i("Switching to Voice mode, draft preserved: $draftText")
        }
        currentMode = mode
        updateButton()
        updateModeUI()
    }

    private fun startListening(
        eventBus: EventBus,
        transport: Transport
    ) {
        val sessionId = VoiceInput.generateSessionId()
        currentVoiceSession = sessionId
        voiceInput = VoiceInput(activity!!, eventBus, sessionId) { rms ->
            activity?.runOnUiThread { feedRms(rms) }
        }
        voiceInput?.start()

        val payload = VoiceStartEvent.VoiceStartPayload(sessionId)
        eventBus.tryPost(VoiceStartEvent(payload))

        isListening = true
        waveView?.reset()
        updateButton()
        setStatus("Listening…")
        Timber.i("Voice started: $sessionId")
    }

    private fun stopListening(eventBus: EventBus) {
        voiceInput?.postVoiceSend()
        voiceInput?.cancel()
        voiceInput = null
        isListening = false
        waveView?.reset()
        updateButton()
        setStatus("Transcribing…")
        Timber.i("Voice stopped, sending")
    }

    fun handleVoiceState(state: String, voiceSession: String) {
        when (state) {
            "received" -> setStatus("Received, processing…")
            "transcribing" -> setStatus("Transcribing…")
            "failed" -> {
                setStatus("Failed — tap to retry")
                resetToIdle()
            }
            else -> {} // exhaustive
        }
        Timber.i("VoiceState: $state (session=$voiceSession)")
    }

    fun handleSpeak(text: String, voiceSession: String) {
        setStatus("Speaking…")
        activity?.runOnUiThread {
            Handler(Looper.getMainLooper()).postDelayed({
                resetToIdle()
            }, 2000)
        }
        Timber.i("Speak received for session=$voiceSession: $text")
    }

    fun handleChatReply(text: String, voiceSession: String) {
        // Add assistant message to chat
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("assistant", text, voiceSession)
        }
        setStatus("Reply received")
        Timber.i("ChatReply received for session=$voiceSession: $text")
    }

    fun feedRms(rms: Float) {
        waveView?.feedRms(rms)
    }

    private fun sendChatMessage(
        eventBus: EventBus,
        transport: Transport
    ) {
        val text = chatInputEditText?.text.toString().trim()
        if (text.isEmpty()) return

        val session = currentVoiceSession ?: VoiceInput.generateSessionId()
        currentVoiceSession = session

        // Add user message immediately to UI
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            messageRepository?.insertMessage("user", text, session)
        }

        // Clear input
        chatInputEditText?.setText("")
        draftText = ""

        // Send chat event
        val payload = ChatEvent.ChatPayload(text, session)
        eventBus.tryPost(ChatEvent(payload))

        setStatus("Sent…")
        Timber.i("Chat sent: $text (session=$session)")
    }

    private fun setStatus(text: String) {
        activity?.runOnUiThread { statusText?.text = text }
    }

    private fun updateButton() {
        activity?.runOnUiThread {
            toggleButton?.setButtonText(when {
                currentMode == TalkMode.VOICE && isListening -> "Send"
                currentMode == TalkMode.VOICE -> "Talk"
                currentMode == TalkMode.CHAT -> "Send"
                else -> "Talk"
            })
            toggleButton?.setPressedState(isListening)
        }
    }

    private fun updateModeUI() {
        activity?.runOnUiThread {
            val isChat = currentMode == TalkMode.CHAT
            chatRecyclerView?.visibility = if (isChat) View.VISIBLE else View.GONE
            chatInputContainer?.visibility = if (isChat) View.VISIBLE else View.GONE
            voiceWaveView?.visibility = if (isChat) View.GONE else View.VISIBLE
            voiceStatusText?.visibility = if (isChat) View.GONE else View.VISIBLE

            if (isChat) {
                scrollToBottom()
            }
        }
    }

    private fun scrollToBottom() {
        activity?.runOnUiThread {
            chatRecyclerView?.scrollToPosition(chatAdapter?.itemCount?.minus(1) ?: 0)
        }
    }

    private fun resetToIdle() {
        isListening = false
        voiceInput = null
        waveView?.reset()
        updateButton()
        setStatus("Idle — tap Talk to speak")
    }

    fun isActive(): Boolean = isListening
}