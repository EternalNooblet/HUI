package com.southpawin.companion

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.southpawin.companion.util.Config
import timber.log.Timber

class SettingsActivity : AppCompatActivity() {

    private lateinit var voiceStatusText: TextView
    private lateinit var voiceEnUsButton: Button
    private lateinit var voiceEnGbButton: Button
    private lateinit var voiceEnInButton: Button
    private lateinit var voiceDeDeButton: Button
    private lateinit var voiceFrFrButton: Button
    private lateinit var voiceEsEsButton: Button
    private lateinit var voiceEsUsButton: Button
    private lateinit var voiceItItButton: Button
    private lateinit var voicePtBrButton: Button
    private lateinit var voiceRoRoButton: Button
    private lateinit var voiceRuRuButton: Button
    private lateinit var voicePlPlButton: Button
    private lateinit var voiceCsCzButton: Button
    private lateinit var voiceZhCnButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup toolbar with back button
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        voiceStatusText = findViewById(R.id.voiceStatusText)
        voiceEnUsButton = findViewById(R.id.voiceEnUsButton)
        voiceEnGbButton = findViewById(R.id.voiceEnGbButton)
        voiceEnInButton = findViewById(R.id.voiceEnInButton)
        voiceDeDeButton = findViewById(R.id.voiceDeDeButton)
        voiceFrFrButton = findViewById(R.id.voiceFrFrButton)
        voiceEsEsButton = findViewById(R.id.voiceEsEsButton)
        voiceEsUsButton = findViewById(R.id.voiceEsUsButton)
        voiceItItButton = findViewById(R.id.voiceItItButton)
        voicePtBrButton = findViewById(R.id.voicePtBrButton)
        voiceRoRoButton = findViewById(R.id.voiceRoRoButton)
        voiceRuRuButton = findViewById(R.id.voiceRuRuButton)
        voicePlPlButton = findViewById(R.id.voicePlPlButton)
        voiceCsCzButton = findViewById(R.id.voiceCsCzButton)
        voiceZhCnButton = findViewById(R.id.voiceZhCnButton)

        attachVoiceHandlers()
        updateVoiceStatus()
    }

    private fun attachVoiceHandlers() {
        voiceEnUsButton.setOnClickListener { selectVoice("en-US") }
        voiceEnGbButton.setOnClickListener { selectVoice("en-GB") }
        voiceEnInButton.setOnClickListener { selectVoice("en-IN") }
        voiceDeDeButton.setOnClickListener { selectVoice("de-DE") }
        voiceFrFrButton.setOnClickListener { selectVoice("fr-FR") }
        voiceEsEsButton.setOnClickListener { selectVoice("es-ES") }
        voiceEsUsButton.setOnClickListener { selectVoice("es-US") }
        voiceItItButton.setOnClickListener { selectVoice("it-IT") }
        voicePtBrButton.setOnClickListener { selectVoice("pt-BR") }
        voiceRoRoButton.setOnClickListener { selectVoice("ro-RO") }
        voiceRuRuButton.setOnClickListener { selectVoice("ru-RU") }
        voicePlPlButton.setOnClickListener { selectVoice("pl-PL") }
        voiceCsCzButton.setOnClickListener { selectVoice("cs-CZ") }
        voiceZhCnButton.setOnClickListener { selectVoice("zh-CN") }
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