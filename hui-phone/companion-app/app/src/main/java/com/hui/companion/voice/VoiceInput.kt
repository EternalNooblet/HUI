package com.hui.companion.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.hui.companion.util.Config
import com.hui.companion.event.EventBus
import com.hui.companion.event.AudioEvent
import com.hui.companion.event.VoiceStartEvent
import com.hui.companion.event.VoiceSendEvent
import com.hui.companion.event.VoiceCancelEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class VoiceInput(
    private val context: android.content.Context,
    private val eventBus: EventBus,
    private val voiceSession: String,
    private val onRms: ((Float) -> Unit)? = null
) {
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var sequence = 0L
    private var startTimeMs = 0L
    private var lastAudioTimeMs = 0L
    private var isCancelled = false
    private var silentStartMs: Long? = null

    private val audioExecutor = CoroutineScope(Dispatchers.IO)
    private val chunkQueue = Channel<Array<Byte>>(Channel.UNLIMITED)

    private val CHUNK_DURATION_MS = 100
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    // Lowered from -40 dB: typical phone mic at arm's length is -50 to -60 dB
    private val AUTO_CANCEL_THRESHOLD_DB = -65.0f
    private val AUTO_CANCEL_DURATION_MS = 3000
    private val MAX_DURATION_MS = 60000
    private val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000

    fun start() {
        if (job != null) return
        isCancelled = false
        sequence = 0
        startTimeMs = System.currentTimeMillis()
        lastAudioTimeMs = startTimeMs
        silentStartMs = null

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            .coerceAtLeast(SAMPLES_PER_CHUNK * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            bufferSize
        ).also { it.startRecording() }

        job = audioExecutor.launch {
            captureLoop()
        }

        audioExecutor.launch {
            sendLoop()
        }

        Timber.i("VoiceInput started: session=$voiceSession")
    }

    fun cancel() {
        isCancelled = true
        job?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        chunkQueue.close()
        Timber.i("VoiceInput cancelled: session=$voiceSession")
    }

    private suspend fun captureLoop() {
        val buffer = ShortArray(SAMPLES_PER_CHUNK)
        while (!isCancelled && audioRecord != null) {
            val read = audioRecord!!.read(buffer, 0, SAMPLES_PER_CHUNK)
            if (read > 0) {
                val now = System.currentTimeMillis()
                lastAudioTimeMs = now

                val rms = calculateRms(buffer, read)
                val rmsDb = 20 * Math.log10((rms + 1e-10)).toFloat()

                // Feed RMS to UI for waveform (normalized 0..1)
                onRms?.invoke(rms)

                if (rmsDb < AUTO_CANCEL_THRESHOLD_DB) {
                    if (silentStartMs == null) silentStartMs = now
                    else if (now - silentStartMs!! >= AUTO_CANCEL_DURATION_MS) {
                        Timber.i("Auto-cancel: sub-threshold RMS (%.1f dB) for ${AUTO_CANCEL_DURATION_MS}ms", rmsDb)
                        postVoiceCancel()
                        return
                    }
                } else {
                    silentStartMs = null
                }

                if (now - startTimeMs >= MAX_DURATION_MS) {
                    Timber.i("Hard cap reached: ${MAX_DURATION_MS}ms")
                    postVoiceSend()
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
            }
            delay(CHUNK_DURATION_MS.toLong())
        }
    }

    private suspend fun sendLoop() {
        for (chunk in chunkQueue) {
            // Chunks are sent via eventBus in captureLoop; this loop exists for backpressure if needed
        }
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