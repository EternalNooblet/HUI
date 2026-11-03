package com.hui.companion.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hui.companion.BuildConfig
import com.hui.companion.event.CommandPayload

object Config {
    // Device identification
    val deviceId: String by lazy {
        getOrCreateDeviceId()
    }
    
    // Server connection
    var serverUrl: String = BuildConfig.SERVER_URL
        private set
    
    var wsUrl: String = BuildConfig.SERVER_URL.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        private set
    
    var authToken: String? = null
        private set
    
    // TTS voice selection (system voice locale code, e.g. "en-US", "fr-FR")
    var ttsVoice: String? = null
        private set
    
    // TTS pitch multiplier (0.5..2.0; 1.0 = neutral)
    var ttsPitch: Float? = null
        private set
    
    // Detection intervals (configurable from server)
    var gazeCheckIntervalMs: Long = 2000
    var audioChunkMs: Int = 100
    var batteryReportIntervalS: Long = 60
    var notificationFilterPackages: Set<String> = setOf(
        "com.whatsapp", "com.google.android.apps.messaging",
        "com.android.mms", "com.google.android.gm",
        "com.microsoft.office.outlook", "org.telegram.messenger"
    )
    
    // Attention detection sensitivity
    var gazeConfidenceThreshold: Float = 0.7f
    var audioLevelThresholdDb: Float = -20.0f
    
    // Local queue settings
    var maxQueueSize: Int = 1000
    var flushIntervalMs: Long = 5000
    
    private fun getOrCreateDeviceId(): String {
        val prefs = getPrefs()
        return prefs.getString("device_id", "").let { id ->
            if (id.isNullOrEmpty()) {
                val newId = "android-${Settings.Secure.getString(
                    CompanionAppContext.instance?.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: java.util.UUID.randomUUID().toString()}"
                prefs.edit().putString("device_id", newId).apply()
                newId
            } else {
                id
            }
        }
    }
    
    fun updateFromCommand(payload: CommandPayload) {
        payload.voice?.let { setVoice(it) }
        payload.pitch?.let { setPitch(it) }
        payload.gazeCheckIntervalMs?.let { gazeCheckIntervalMs = it }
        payload.audioChunkMs?.let { audioChunkMs = it }
        payload.batteryReportIntervalS?.let { batteryReportIntervalS = it }
    }
    
    fun setServerUrl(url: String) {
        serverUrl = url
        wsUrl = url.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        getPrefs().edit().putString("server_url", url).commit()
    }

    fun loadServerUrl(): String {
        return getPrefs().getString("server_url", null)?.let {
            setServerUrl(it)
            it
        } ?: BuildConfig.SERVER_URL
    }
    
    fun setAuthToken(token: String) {
        authToken = token
        getPrefs().edit().putString("auth_token", token).commit()
    }
    
    fun loadAuthToken(): String? {
        return getPrefs().getString("auth_token", null)?.also { authToken = it }
    }

    /** Local sign-out: forget the token on this phone. Does not revoke it
     *  server-side (that is hermes_revoke_token.py) and does not clear the
     *  server URL, so a fresh QR scan reconnects to the same server. */
    fun clearAuthToken() {
        authToken = null
        getPrefs().edit().remove("auth_token").commit()
    }
    
    fun setVoice(code: String) {
        ttsVoice = code
        getPrefs().edit().putString("tts_voice", code).commit()
    }
    
    fun loadVoice(): String? {
        return getPrefs().getString("tts_voice", null)?.also { ttsVoice = it }
    }
    
    fun setPitch(multiplier: Float) {
        ttsPitch = multiplier
        getPrefs().edit().putFloat("tts_pitch", multiplier).apply()
    }
    
    fun loadPitch(): Float? {
        val stored = getPrefs().getFloat("tts_pitch", 0.0f)
        if (stored > 0.0f) {
            ttsPitch = stored
        }
        return if (stored > 0.0f) stored else ttsPitch
    }
    
    private fun getPrefs(): SharedPreferences {
        val context = CompanionAppContext.instance
            ?: throw IllegalStateException("CompanionAppContext.instance not initialized")
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "companion_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

// Singleton accessor for application context
object CompanionAppContext {
    lateinit var instance: android.app.Application
        internal set
}