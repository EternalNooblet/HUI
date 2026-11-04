package com.hui.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.hui.companion.event.AudioEvent
import com.hui.companion.event.EventBus
import com.hui.companion.event.VoiceCancelEvent
import com.hui.companion.event.VoiceSendEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * VoiceInput — one-shot PCM capture for a single voice turn.
 *
 * Lifetime: construct on mic tap, [start], then [cancel] (user stop,
 * auto-cancel, hard cap, or error). It owns its [AudioRecord] and its
 * single capture Job; [cancel] is idempotent and safe from any thread.
 *
 * Terminal conditions all fire the supplied callbacks exactly once:
 * [onAutoCancel] (3s sub-threshold RMS), [onHardCap] (60s),
 * [onError] (permission / AudioRecord / read failure). The caller
 * (VoiceUi) is responsible for posting no further sends after a
 * terminal callback — the recorder is already stopped by then.
 */
class VoiceInput(
    private val context: Context,
    private val eventBus: EventBus,
    private val voiceSession: String,
    private val onAutoCancel: () -> Unit = {},
    private val onHardCap: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var job: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var cancelled = false
    private var sequence = 0L
    private var startTimeMs = 0L
    private var silentStartMs: Long? = null
    private var consecutiveReadErrors = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val CHUNK_DURATION_MS = 100
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    // Lowered from -40 dB: typical phone mic at arm's length is -50 to -60 dB
    private val AUTO_CANCEL_THRESHOLD_DB = -65.0f
    private val AUTO_CANCEL_DURATION_MS = 3000
    private val MAX_DURATION_MS = 60000
    private val MAX_CONSECUTIVE_READ_ERRORS = 10
    private val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000

    fun start() {
        if (job != null) return
        cancelled = false
        sequence = 0
        startTimeMs = System.currentTimeMillis()
        silentStartMs = null
        consecutiveReadErrors = 0

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("VoiceInput: RECORD_AUDIO not granted")
            onError("Microphone permission missing")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBuffer <= 0) {
            Timber.e("VoiceInput: getMinBufferSize failed: $minBuffer")
            onError("Microphone unavailable")
            return
        }
        val bufferSize = minBuffer.coerceAtLeast(SAMPLES_PER_CHUNK * 2)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                ENCODING,
                bufferSize
            )
        } catch (e: Exception) {
            Timber.e(e, "VoiceInput: AudioRecord construction failed")
            onError("Microphone unavailable")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("VoiceInput: AudioRecord not initialized")
            try { recorder.release() } catch (_: Exception) {}
            onError("Microphone unavailable")
            return
        }
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Timber.e(e, "VoiceInput: startRecording failed")
            try { recorder.release() } catch (_: Exception) {}
            onError("Microphone unavailable")
            return
        }
        audioRecord = recorder
        job = scope.launch { captureLoop() }
        Timber.i("VoiceInput started: session=$voiceSession")
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        job?.cancel()
        job = null
        stopAndRelease()
        Timber.i("VoiceInput cancelled: session=$voiceSession")
    }

    private fun stopAndRelease() {
        val recorder = audioRecord
        audioRecord = null
        if (recorder == null) return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (e: Exception) {
            Timber.w(e, "VoiceInput: stop() ignored")
        }
        try {
            recorder.release()
        } catch (e: Exception) {
            Timber.w(e, "VoiceInput: release() ignored")
        }
    }

    private suspend fun captureLoop() {
        val buffer = ShortArray(SAMPLES_PER_CHUNK)
        while (!cancelled && audioRecord != null) {
            val recorder = audioRecord ?: break
            val read = try {
                recorder.read(buffer, 0, SAMPLES_PER_CHUNK)
            } catch (e: Exception) {
                Timber.e(e, "VoiceInput: read threw")
                -1
            }
            if (cancelled) break
            if (read <= 0) {
                consecutiveReadErrors++
                Timber.w("VoiceInput: read=$read (errors=$consecutiveReadErrors)")
                if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                    onError("Microphone read failed")
                    cleanupAfterTerminal()
                    return
                }
                continue
            }
            consecutiveReadErrors = 0
            val now = System.currentTimeMillis()

            val rms = calculateRms(buffer, read)
            val rmsDb = 20 * Math.log10((rms + 1e-10)).toFloat()

            if (rmsDb < AUTO_CANCEL_THRESHOLD_DB) {
                if (silentStartMs == null) silentStartMs = now
                else if (now - silentStartMs!! >= AUTO_CANCEL_DURATION_MS) {
                    Timber.i("Auto-cancel: sub-threshold RMS (%.1f dB) for ${AUTO_CANCEL_DURATION_MS}ms", rmsDb)
                    postVoiceCancel()
                    cleanupAfterTerminal()
                    onAutoCancel()
                    return
                }
            } else {
                silentStartMs = null
            }

            if (now - startTimeMs >= MAX_DURATION_MS) {
                Timber.i("Hard cap reached: ${MAX_DURATION_MS}ms")
                postVoiceSend()
                cleanupAfterTerminal()
                onHardCap()
                return
            }

            val chunk = ShortArray(read).apply { System.arraycopy(buffer, 0, this, 0, read) }
            val b64 = Base64.encodeToString(shortArrayToBytes(chunk), Base64.NO_WRAP)
            val payload = AudioEvent.AudioPayload(
                format = "pcm16",
                sampleRate = SAMPLE_RATE,
                channels = 1,
                durationMs = CHUNK_DURATION_MS,
                dataBase64 = b64,
                sequence = sequence++,
                voiceSession = voiceSession
            )
            eventBus.tryPost(AudioEvent(payload))
            // read() is blocking (~100ms); no extra delay here or the
            // cadence doubles and wall-clock drift breaks gap math.
        }
    }

    private fun cleanupAfterTerminal() {
        cancelled = true
        job = null
        stopAndRelease()
    }

    private fun calculateRms(buffer: ShortArray, len: Int): Float {
        var sum = 0L
        for (i in 0 until len) {
            val v = buffer[i].toLong()
            sum += v * v
        }
        return Math.sqrt(sum.toDouble() / len).toFloat() / 32768.0f
    }

    private fun shortArrayToBytes(arr: ShortArray): ByteArray {
        val bytes = ByteArray(arr.size * 2)
        for (i in arr.indices) {
            val v = arr[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun postVoiceCancel() {
        val payload = VoiceCancelEvent.VoiceCancelPayload(voiceSession)
        eventBus.tryPost(VoiceCancelEvent(payload))
    }

    fun postVoiceSend() {
        val durationMs = System.currentTimeMillis() - startTimeMs
        val payload = VoiceSendEvent.VoiceSendPayload(voiceSession, sequence - 1, durationMs)
        eventBus.tryPost(VoiceSendEvent(payload))
    }

    companion object {
        fun generateSessionId(): String {
            return "vs-${UUID.randomUUID().toString().substring(0, 8)}"
        }
    }
}
