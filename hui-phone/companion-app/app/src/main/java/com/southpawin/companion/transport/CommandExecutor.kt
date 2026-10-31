package com.southpawin.companion.transport

import android.content.Context
import android.speech.tts.TextToSpeech
import com.southpawin.companion.util.Config
import com.southpawin.companion.event.Command
import com.southpawin.companion.event.Result
import timber.log.Timber

class CommandExecutor(private val context: Context) {
    fun execute(command: Command): Result {
        Timber.i("Executing command: ${command.command}, payload: ${command.payload}")
        return when (command.command) {
            "speak" -> {
                command.payload.text?.let { text ->
                    Timber.i("Speaking: $text")
                    val tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
                        override fun onInit(status: Int) {
                            if (status == TextToSpeech.SUCCESS) {
                                this@CommandExecutor.speakText(text)
                            } else {
                                Timber.e("TTS initialization failed: $status")
                            }
                        }
                    })
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
            else -> Result.UnknownCommand
        }
    }

    private fun speakText(text: String) {
        val ttsRef = arrayOfNulls<TextToSpeech>(1)
        ttsRef[0] = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    val tts = ttsRef[0] ?: TextToSpeech(context, null)
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "companion_tts")
                } else {
                    Timber.e("TTS initialization failed in speakText: $status")
                }
            }
        })
    }
}