package com.hui.companion.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hui.companion.BuildConfig
import java.net.URLEncoder

/**
 * Direct-gateway config (DIRECT_PLAN.md): the phone talks straight to
 * `hermes serve` — ws://<host>:9119/api/ws?token=<blob>. No :8081 companion
 * server, no pinned TLS, no sensor uploads.
 *
 * [serverUrl] holds the HTTP base (http://<host>:9119); the WS URL is
 * derived from it plus the stored token. The QR payload is the only source
 * at runtime (BuildConfig.SERVER_URL stays a dev-only default, empty in
 * releases).
 */
object Config {
    // Device identification
    val deviceId: String by lazy {
        getOrCreateDeviceId()
    }

    // Server connection — empty until the user scans a QR code (Settings).
    // Never baked into the APK.
    var serverUrl: String = BuildConfig.SERVER_URL
        private set

    var authToken: String? = null
        private set

    // TTS voice selection (system voice locale code, e.g. "en-US", "fr-FR")
    var ttsVoice: String? = null
        private set

    // TTS pitch multiplier (0.5..2.0; 1.0 = neutral)
    var ttsPitch: Float? = null
        private set

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

    fun setServerUrl(url: String) {
        serverUrl = normalizeBase(url)
        getPrefs().edit().putString("server_url", serverUrl).commit()
    }

    /** Full WS URL incl. token query. Never log this (carries the blob). */
    fun wsUrlWithToken(): String {
        val base = serverUrl.ifBlank { loadServerUrl() }
        if (base.isBlank()) return ""
        val token = (authToken ?: loadAuthToken()) ?: ""
        val wsBase = base.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return wsBase + "/api/ws?token=" + URLEncoder.encode(token, "UTF-8")
    }

    /** Same as [wsUrlWithToken] with the token redacted — safe for logs/UI. */
    fun wsUrlForLog(): String {
        val base = serverUrl.ifBlank { loadServerUrl() }
        if (base.isBlank()) return ""
        return base.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/ws?token=***"
    }

    /** HTTP base for the audio + chat HTTP calls (plain http on LAN). */
    fun httpBaseUrl(): String {
        val base = serverUrl.ifBlank { loadServerUrl() }
        if (base.isBlank()) return ""
        return base.trimEnd('/')
            .replace("wss://", "https://")
            .replace("ws://", "http://")
    }

    fun loadServerUrl(): String {
        return getPrefs().getString("server_url", null)?.let {
            // Stored values from the old :8081 flow pointed at the companion
            // server; the direct flow always uses the gateway port, plain
            // http. Repair on read so an old install re-points (and
            // downgrades wss) without a rescan.
            val repaired = normalizeBase(it)
            if (repaired != it) {
                serverUrl = repaired
                getPrefs().edit().putString("server_url", repaired).commit()
            } else {
                serverUrl = it
            }
            repaired
        } ?: BuildConfig.SERVER_URL
    }

    /**
     * Normalize anything the QR/manual flow stored into an http gateway
     * base. Accepts: http://host:9119, https://host, ws://host:9119/api/ws,
     * wss://host (legacy companion QRs carried a bare ws_url).
     * First pass is plain ws/http on LAN (DIRECT_PLAN.md), so https/wss
     * bases are downgraded — TLS/Tailscale comes later as a network swap.
     */
    fun normalizeBase(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return u
        u = u.replace("wss://", "http://").replace("ws://", "http://")
        u = u.replace("https://", "http://")
        // Strip any path (…/api/ws, …/ws) — base is scheme://host[:port].
        val m = Regex("^(https?://[^/]+)").find(u)
        if (m != null) u = m.groupValues[1]
        // Legacy companion QRs pointed at :8081/ws. The gateway is :9119.
        u = repairLegacyBase(u)
        return u
    }

    /** :8081 companion bases become :9119 gateway bases (same host). */
    private fun repairLegacyBase(url: String): String {
        val m = Regex("^(https?://[^:/]+):8081$").find(url.trimEnd('/'))
        return if (m != null) m.groupValues[1] + ":9119" else url
    }

    /** Resolve a server-relative path to an absolute URL for Coil. */
    fun resolveImageUrl(path: String): String {
        val trimmed = path.trim()
        // Local / inline sources pass through untouched (Coil loads them).
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("content://") || trimmed.startsWith("file://") ||
            trimmed.startsWith("data:")
        ) return trimmed
        val base = httpBaseUrl()
        if (base.isBlank()) return trimmed
        return base + if (trimmed.startsWith("/")) trimmed else "/$trimmed"
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
