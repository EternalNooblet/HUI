package com.southpawin.companion.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.southpawin.companion.BuildConfig
import com.southpawin.companion.event.CommandPayload

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
        payload.gazeCheckIntervalMs?.let { gazeCheckIntervalMs = it }
        payload.audioChunkMs?.let { audioChunkMs = it }
        payload.batteryReportIntervalS?.let { batteryReportIntervalS = it }
    }
    
    fun setServerUrl(url: String) {
        serverUrl = url
        wsUrl = url.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
    }
    
    fun setAuthToken(token: String) {
        authToken = token
        getPrefs().edit().putString("auth_token", token).apply()
    }
    
    fun loadAuthToken(): String? {
        return getPrefs().getString("auth_token", null)?.also { authToken = it }
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