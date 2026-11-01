package com.southpawin.companion.voice

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.southpawin.companion.R
import com.southpawin.companion.event.VoiceStartEvent
import com.southpawin.companion.event.VoiceSendEvent
import com.southpawin.companion.event.VoiceCancelEvent
import timber.log.Timber

object VoiceUi {
    private var activity: AppCompatActivity? = null
    private var statusText: TextView? = null
    private var waveView: VoiceWaveView? = null
    private var toggleButton: CircularTalkButton? = null
    private var isListening = false
    private var voiceInput: VoiceInput? = null

    fun attach(
        act: AppCompatActivity,
        status: TextView,
        wave: VoiceWaveView,
        btn: CircularTalkButton,
        eventBus: com.southpawin.companion.event.EventBus,
        transport: com.southpawin.companion.transport.Transport
    ) {
        activity = act
        statusText = status
        waveView = wave
        toggleButton = btn
        isListening = false

        btn.setOnClickListener { toggleVoice(eventBus, transport) }
        updateButton()
        Timber.i("VoiceUi attached")
    }

    fun toggleVoice(
        eventBus: com.southpawin.companion.event.EventBus,
        transport: com.southpawin.companion.transport.Transport
    ) {
        if (isListening) {
            stopListening(eventBus)
        } else {
            startListening(eventBus, transport)
        }
    }

    private fun startListening(
        eventBus: com.southpawin.companion.event.EventBus,
        transport: com.southpawin.companion.transport.Transport
    ) {
        val sessionId = VoiceInput.generateSessionId()
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

    private fun stopListening(eventBus: com.southpawin.companion.event.EventBus) {
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
        }
        Timber.i("VoiceState: $state (session=$voiceSession)")
    }

    fun handleSpeak(text: String, voiceSession: String) {
        setStatus("Speaking…")
        // SpeakCommandExecutor will handle TTS; we just reset UI after a delay
        activity?.runOnUiThread {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                resetToIdle()
            }, 2000)
        }
        Timber.i("Speak received for session=$voiceSession: $text")
    }

    fun feedRms(rms: Float) {
        waveView?.feedRms(rms)
    }

    private fun setStatus(text: String) {
        activity?.runOnUiThread { statusText?.text = text }
    }

    private fun updateButton() {
        activity?.runOnUiThread {
            toggleButton?.setButtonText(if (isListening) "Send" else "Talk")
            toggleButton?.setPressedState(isListening)
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