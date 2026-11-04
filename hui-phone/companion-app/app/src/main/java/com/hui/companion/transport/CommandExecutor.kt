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
                // Voice-originated reply: bubble (always) + native TTS read-out.
                command.payload.text?.let { text ->
                    val voiceSession = command.payload.voiceSession ?: ""
                    VoiceUi.handleVoiceReply(text, voiceSession)
                    val voice = command.payload.voice ?: Config.loadVoice()
                    val pitch = command.payload.pitch ?: Config.loadPitch()
                    Timber.i("Speaking: $text")
                    speakWithNativeTts(text, voice, pitch)
                }
                Result.Success()
            }
            "voice_transcript" -> {
                // User's own words, echoed by the server before the reply.
                command.payload.text?.let { text ->
                    VoiceUi.handleVoiceTranscript(text, command.payload.voiceSession ?: "")
                }
                Result.Success()
            }
            "chat_reply" -> {
                // Text/image-originated reply: bubble only, never TTS.
                command.payload.text?.let { text ->
                    VoiceUi.handleChatReply(text, command.payload.voiceSession ?: "")
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
        val state = command.payload.state ?: command.payload.text ?: ""
        val voiceSession = command.payload.voiceSession ?: ""
        VoiceUi.handleVoiceState(state, voiceSession)
        return Result.Success()
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
