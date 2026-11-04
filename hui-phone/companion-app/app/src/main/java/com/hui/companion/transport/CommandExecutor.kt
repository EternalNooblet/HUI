package com.hui.companion.transport

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hui.companion.util.Config
import timber.log.Timber

/**
 * Assistant-reply speech (DIRECT_PLAN.md): Android native TTS, voice-origin
 * turns only. The old bridge command channel (speak / chat_reply /
 * voice_state / voice_transcript / screen_action / launch_app / config)
 * is gone — replies arrive as message.complete on Transport, which calls
 * [speakText] for voice-origin turns (wired in MainActivity).
 */
class CommandExecutor(private val context: Context) {

    /** Speak [text] with the user's saved voice/pitch. No-op on blank. */
    fun speakText(text: String) {
        if (text.isBlank()) return
        val voice = Config.loadVoice()
        val pitch = Config.loadPitch()
        Timber.i("Speaking: $text")
        speakWithNativeTts(text, voice, pitch)
    }

    private fun speakWithNativeTts(text: String, voice: String?, pitch: Float?) {
        // Android native speech engine (phone TTS), single instance.
        val ref = arrayOfNulls<TextToSpeech>(1)
        val tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status != TextToSpeech.SUCCESS) {
                    Timber.e("TTS initialization failed: $status")
                    return
                }
                val engine = ref[0] ?: return
                voice?.let { applyVoice(engine, it) }
                pitch?.let { applyPitch(engine, it) }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "companion_tts")
            }
        })
        ref[0] = tts
    }

    private fun applyVoice(tts: TextToSpeech, code: String) {
        val parts = code.lowercase().split("-")
        val locale = if (parts.size >= 2)
            java.util.Locale(parts[0], parts[1].uppercase())
        else
            java.util.Locale(parts[0])
        val status = tts.setLanguage(locale)
        // 0 = applied; 1 = engine is initializing the voice asynchronously (first use downloads voice data) — still works
        if (status < 0) {
            Timber.w("TTS voice '$code' not applied (status $status); falling back to current engine language")
        } else {
            Timber.i("TTS voice set: $code (status $status)")
        }
    }

    private fun applyPitch(tts: TextToSpeech, multiplier: Float) {
        val clamped = if (multiplier < 0.5f) 0.5f else if (multiplier > 2.0f) 2.0f else multiplier
        val status = tts.setPitch(clamped)
        if (status < 0) {
            Timber.w("TTS pitch $clamped not applied (status $status)")
        } else {
            Timber.i("TTS pitch set: $clamped")
        }
    }
}
