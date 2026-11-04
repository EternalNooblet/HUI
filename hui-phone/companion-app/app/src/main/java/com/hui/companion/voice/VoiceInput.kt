package com.hui.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * VoiceInput — one-shot capture for a single voice turn (DIRECT_PLAN.md).
 *
 * Tap mic -> record full WAV to file -> tap again -> [stop] finalizes the
 * WAV and hands it to [onWavReady] (the caller POSTs it to
 * /api/audio/transcribe). No 100ms chunk streaming anywhere: the gateway
 * owns STT now.
 *
 * Lifetime: construct on mic tap, [start], then exactly one of [stop]
 * (user tap-stop or hard cap) or [cancel] (auto-cancel / error / abandoned).
 * [stop] and [cancel] are idempotent and safe from any thread; [onWavReady]
 * fires at most once and only for [stop].
 *
 * Terminal callbacks: [onAutoCancel] (3s sub-threshold RMS), [onHardCap]
 * (60s — still delivers the WAV via [onWavReady]), [onError] (permission /
 * AudioRecord / read failure — no WAV).
 */
class VoiceInput(
    private val context: Context,
    private val voiceSession: String,
    private val onWavReady: (File) -> Unit = {},
    private val onAutoCancel: () -> Unit = {},
    private val onHardCap: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var job: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var finished = false
    private var startTimeMs = 0L
    private var silentStartMs: Long? = null
    private var consecutiveReadErrors = 0

    // Accumulated PCM (s16le, 16 kHz mono) for the whole turn.
    private val pcm = ByteArrayOutputStream()
    private val pcmLock = Object()

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
        finished = false
        startTimeMs = System.currentTimeMillis()
        silentStartMs = null
        consecutiveReadErrors = 0
        synchronized(pcmLock) { pcm.reset() }

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

    /** Tap-stop: finalize the WAV file and deliver it via [onWavReady]. */
    fun stop() {
        if (finished) return
        finished = true
        job?.cancel()
        job = null
        stopAndRelease()
        val wav = writeWavFile()
        if (wav != null) {
            Timber.i("VoiceInput WAV ready: ${wav.length()} bytes, session=$voiceSession")
            onWavReady(wav)
        } else {
            onError("No audio recorded")
        }
    }

    /** Discard the recording (auto-cancel, error, abandoned). No WAV. */
    fun cancel() {
        if (finished) return
        finished = true
        job?.cancel()
        job = null
        stopAndRelease()
        synchronized(pcmLock) { pcm.reset() }
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

    private fun captureLoop() {
        val buffer = ShortArray(SAMPLES_PER_CHUNK)
        while (!finished && audioRecord != null) {
            val recorder = audioRecord ?: break
            val read = try {
                recorder.read(buffer, 0, SAMPLES_PER_CHUNK)
            } catch (e: Exception) {
                Timber.e(e, "VoiceInput: read threw")
                -1
            }
            if (finished) break
            if (read <= 0) {
                consecutiveReadErrors++
                Timber.w("VoiceInput: read=$read (errors=$consecutiveReadErrors)")
                if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                    cleanupAfterTerminal()
                    onError("Microphone read failed")
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
                    cleanupAfterTerminal()
                    onAutoCancel()
                    return
                }
            } else {
                silentStartMs = null
            }

            // Append this chunk's PCM to the whole-turn buffer.
            synchronized(pcmLock) {
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    pcm.write(v and 0xFF)
                    pcm.write((v shr 8) and 0xFF)
                }
            }

            if (now - startTimeMs >= MAX_DURATION_MS) {
                Timber.i("Hard cap reached: ${MAX_DURATION_MS}ms")
                // Deliver what we have (stop() path), then report the cap.
                finished = true
                job = null
                stopAndRelease()
                val wav = writeWavFile()
                if (wav != null) onWavReady(wav) else onError("No audio recorded")
                onHardCap()
                return
            }
            // read() is blocking (~100ms); no extra delay here or the
            // cadence doubles and wall-clock drift breaks gap math.
        }
    }

    private fun cleanupAfterTerminal() {
        finished = true
        job = null
        stopAndRelease()
    }

    /** Standard 44-byte RIFF header + the accumulated PCM, written to a
     *  cache file (the transcribe POST reads it back as a data_url). */
    private fun writeWavFile(): File? {
        val data: ByteArray = synchronized(pcmLock) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        val header = ByteArray(44)
        fun putAscii(off: Int, s: String) {
            for (i in s.indices) header[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + data.size)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)                       // PCM fmt chunk size
        putShort(20, 1)                      // audio format: PCM
        putShort(22, 1)                      // channels: mono
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)          // byte rate (mono, s16)
        putShort(32, 2)                      // block align
        putShort(34, 16)                     // bits per sample
        putAscii(36, "data")
        putInt(40, data.size)
        return try {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            val f = File(dir, "voice-$voiceSession-${System.currentTimeMillis()}.wav")
            f.outputStream().use { out ->
                out.write(header)
                out.write(data)
            }
            f
        } catch (e: Exception) {
            Timber.e(e, "VoiceInput: WAV write failed")
            null
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

    companion object {
        fun generateSessionId(): String {
            return "vs-${UUID.randomUUID().toString().substring(0, 8)}"
        }
    }
}
