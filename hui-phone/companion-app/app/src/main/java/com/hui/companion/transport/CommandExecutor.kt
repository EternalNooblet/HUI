package com.hui.companion.transport

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hui.companion.util.Config
import com.hui.companion.event.Command
import com.hui.companion.event.Result
import com.hui.companion.voice.VoiceUi
import timber.log.Timber

class CommandExecutor(private val context: Context) {
    fun execute(command: Command): Result {
        Timber.i("Executing command: ${command.command}, payload: ${command.payload}")
        return when (command.command) {
            "speak" -> {
                command.payload.text?.let { text ->
                    if (VoiceUi.voiceRecording) {
                        VoiceUi.handleVoiceTranscript(text, command.payload.voiceSession ?: "")
                    } else {
                        val voice = command.payload.voice ?: Config.loadVoice()
                        val pitch = command.payload.pitch ?: Config.loadPitch()
                        Timber.i("Speaking: $text")
                        val tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
                            override fun onInit(status: Int) {
                                if (status == TextToSpeech.SUCCESS) {
                                    this@CommandExecutor.speakText(text, voice, pitch)
                                } else {
                                    Timber.e("TTS initialization failed: $status")
                                }
                            }
                        })
                    }
                }
                Result.Success()
            }
            "chat_reply" -> {
                command.payload.text?.let { text ->
                    val voiceSession = command.payload.voiceSession ?: ""
                    VoiceUi.handleChatReply(text, voiceSession)
                }
                Result.Success()
            }
            "screen_action" -> {
                command.payload.action?.let { action ->
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    when (action) {
                        "wake" -> {
                            val wakeLock = pm.newWakeLock(
                                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "companion:wake"
                            )
                            wakeLock.acquire(5000)
                        }
                        "sleep" -> {
                            Timber.w("Screen sleep requested but requires system permission")
                        }
                    }
                }
                Result.Success()
            }
            "launch_app" -> {
                command.payload.packageName?.let { pkg ->
                    context.packageManager.getLaunchIntentForPackage(pkg)?.also { intent ->
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                Result.Success()
            }
            "config" -> {
                Config.updateFromCommand(command.payload)
                Result.Success()
            }
            "voice_state" -> handleVoiceState(command)
            else -> Result.UnknownCommand
        }
    }

    private fun handleVoiceState(command: Command): Result {
        val state = command.payload.text ?: ""
        val voiceSession = command.payload.voiceSession ?: ""
        VoiceUi.handleVoiceState(state, voiceSession)
        return Result.Success()
    }

    private fun speakText(text: String, voice: String?, pitch: Float?) {
        val ttsRef = arrayOfNulls<TextToSpeech>(1)
        ttsRef[0] = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    val tts = ttsRef[0] ?: TextToSpeech(context, null)
                    voice?.let { applyVoice(tts, it) }
                    pitch?.let { applyPitch(tts, it) }
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "companion_tts")
                } else {
                    Timber.e("TTS initialization failed in speakText: $status")
                }
            }
        })
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