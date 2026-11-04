package com.hui.companion.voice

import android.util.Base64
import com.google.gson.JsonParser
import com.hui.companion.util.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gateway STT (DIRECT_PLAN.md): POST the tap-stop WAV to
 * http://<lan>:9119/api/audio/transcribe {data_url} with the dashboard
 * blob as a Bearer header (probed live: Bearer works, query/no-auth 401).
 * Returns the transcript, or null on failure (caller shows failure UI).
 */
object Transcriber {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun transcribe(wav: File): String? {
        val base = Config.httpBaseUrl()
        if (base.isBlank()) {
            Timber.w("Transcribe skipped: no server URL configured")
            return null
        }
        val token = Config.authToken ?: Config.loadAuthToken()
        if (token.isNullOrEmpty()) {
            Timber.w("Transcribe skipped: not authenticated")
            return null
        }
        val bytes = try {
            wav.readBytes()
        } catch (e: Exception) {
            Timber.e(e, "Transcribe: cannot read WAV ${wav.path}")
            return null
        }
        if (bytes.isEmpty()) return null
        val dataUrl = "data:audio/wav;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = "{\"data_url\":\"$dataUrl\"}"
        val request = Request.Builder()
            .url(base + "/api/audio/transcribe")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Timber.w("Transcribe HTTP ${response.code}: ${text.take(200)}")
                    return null
                }
                val transcript = JsonParser.parseString(text).asJsonObject
                    .get("transcript")?.takeIf { it.isJsonPrimitive }?.asString
                Timber.i("Transcribe OK: ${transcript?.length ?: 0} chars")
                transcript
            }
        } catch (e: Exception) {
            Timber.e(e, "Transcribe failed")
            null
        }
    }
}
