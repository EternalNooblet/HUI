package com.hui.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.hui.companion.util.Config
import timber.log.Timber

class SettingsActivity : AppCompatActivity() {

    private lateinit var voiceStatusText: TextView
    private lateinit var voiceSpinner: Spinner
    private lateinit var authStatusText: TextView
    private lateinit var authScanButton: Button
    private lateinit var authSignOutButton: Button

    // code -> display label; first entry is system default
    private val voices = listOf(
        "" to "System default",
        "en-US" to "en-US - English (US)",
        "en-GB" to "en-GB - English (UK)",
        "en-IN" to "en-IN - English (India)",
        "de-DE" to "de-DE - German",
        "fr-FR" to "fr-FR - French",
        "es-ES" to "es-ES - Spanish (Spain)",
        "es-US" to "es-US - Spanish (US)",
        "it-IT" to "it-IT - Italian",
        "pt-BR" to "pt-BR - Portuguese (Brazil)",
        "ro-RO" to "ro-RO - Romanian",
        "ru-RU" to "ru-RU - Russian",
        "pl-PL" to "pl-PL - Polish",
        "cs-CZ" to "cs-CZ - Czech",
        "zh-CN" to "zh-CN - Chinese",
    )

    private val cameraPermissionContract = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScan() else {
            Toast.makeText(this, "Camera permission required to scan QR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup toolbar with back button
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        voiceStatusText = findViewById(R.id.voiceStatusText)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        authStatusText = findViewById(R.id.authStatusText)
        authScanButton = findViewById(R.id.authScanButton)
        authSignOutButton = findViewById(R.id.authSignOutButton)

        // Authentication: QR scan button
        authScanButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScan()
            } else {
                cameraPermissionContract.launch(Manifest.permission.CAMERA)
            }
        }
        // Unauthenticate: local sign-out (server-side revocation is
        // hermes_revoke_token.py). Shown only once authenticated.
        // MainActivity.onResume closes the socket on return.
        authSignOutButton.setOnClickListener {
            Config.clearAuthToken()
            updateAuthStatus()
            Toast.makeText(this, "Unauthenticated", Toast.LENGTH_SHORT).show()
            Timber.i("Unauthenticated (token cleared)")
        }
        updateAuthStatus()

        val labels = voices.map { it.second }
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown_voice,
            android.R.id.text1,
            labels
        )
        adapter.setDropDownViewResource(R.layout.item_dropdown_voice)
        voiceSpinner.adapter = adapter

        // Pre-select current voice before attaching listener so init is not treated as user choice
        val current = Config.ttsVoice ?: Config.loadVoice()
        val selectedIndex = voices.indexOfFirst { it.first == (current ?: "") }
        voiceSpinner.setSelection(if (selectedIndex >= 0) selectedIndex else 0, false)

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectVoice(voices[position].first)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        updateVoiceStatus()
    }

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra("qr")
            if (!raw.isNullOrEmpty()) applyQrPayload(raw)
        }
    }

    private fun launchScan() {
        qrLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    /**
     * Direct QR payload (hermes_auth_qr.py):
     *   {"ws_url":"ws://<lan>:9119/api/ws","base_url":"http://<lan>:9119",
     *    "token":"<signed blob>",...}
     * Legacy companion payloads (wss://<lan>:8081/ws) are accepted and
     * re-pointed at the gateway: the old port is repaired to :9119 by
     * Config.normalizeBase, so an old QR still works.
     */
    private fun applyQrPayload(raw: String?) {
        if (raw.isNullOrEmpty()) return
        try {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val wsUrl = obj.get("ws_url")?.asString
            val baseUrl = obj.get("base_url")?.asString
            val token = obj.get("token")?.asString
            val ip = obj.get("ip")?.asString
            val port = obj.get("port")?.asInt
            // Prefer the explicit base; else derive from ws_url; else
            // ip + (gateway port 9119 — never the legacy 8081).
            val serverUrl = when {
                !baseUrl.isNullOrEmpty() -> baseUrl
                !wsUrl.isNullOrEmpty() -> wsUrl
                !ip.isNullOrEmpty() -> "http://$ip:${port ?: 9119}"
                else -> ""
            }
            if (serverUrl.isNotEmpty()) Config.setServerUrl(serverUrl)
            if (!token.isNullOrEmpty()) Config.setAuthToken(token)
            updateAuthStatus()
            // MainActivity.onResume connects (or reconnects) now that a
            // token exists — no service intent anymore.
            Toast.makeText(this, "Authenticated", Toast.LENGTH_SHORT).show()
            Timber.i("QR auth applied")
        } catch (e: Exception) {
            Timber.e(e, "Bad QR payload")
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAuthStatus() {
        val token = Config.authToken ?: Config.loadAuthToken()
        val url = Config.wsUrlForLog()
        val authed = !token.isNullOrEmpty()
        authStatusText.text = if (authed) "Status: Connected to $url" else "Status: Not authenticated"
        authSignOutButton.visibility = if (authed) View.VISIBLE else View.GONE
    }

    private fun selectVoice(code: String) {
        Config.setVoice(code)
        Timber.i("Voice selected: $code")
        updateVoiceStatus()
    }

    private fun updateVoiceStatus() {
        val selectedVoice = Config.ttsVoice ?: Config.loadVoice()
        voiceStatusText.text = if (selectedVoice.isNullOrEmpty())
            "Current voice: system default"
        else
            "Current voice: $selectedVoice"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
