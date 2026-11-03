package com.southpawin.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.southpawin.companion.util.Config
import com.southpawin.companion.service.CompanionService
import com.southpawin.companion.BuildConfig
import com.southpawin.companion.event.EventBus
import com.southpawin.companion.transport.Transport
import com.southpawin.companion.transport.CommandExecutor
import com.southpawin.companion.voice.VoiceWaveView
import com.southpawin.companion.voice.CircularTalkButton
import com.southpawin.companion.voice.VoiceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var voiceStatusText: TextView
    private lateinit var voiceWaveView: VoiceWaveView
    private lateinit var voiceToggleButton: CircularTalkButton
    private lateinit var buildNameText: TextView
    private lateinit var chatRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var chatInputContainer: android.view.View
    private lateinit var chatInputEditText: android.widget.EditText
    private lateinit var chatSendButton: android.widget.ImageButton

    private val eventBus = EventBus()
    private lateinit var transport: Transport
    private lateinit var commandExecutor: CommandExecutor
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    private var voiceLoopStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        buildNameText = findViewById(R.id.buildNameText)
        voiceStatusText = findViewById(R.id.voiceStatusText)
        voiceWaveView = findViewById(R.id.voiceWaveView)
        voiceToggleButton = findViewById(R.id.voiceToggleButton)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputContainer = findViewById(R.id.chatInputContainer)
        chatInputEditText = findViewById(R.id.chatInputEditText)
        chatSendButton = findViewById(R.id.chatSendButton)

        commandExecutor = CommandExecutor(this)
        transport = Transport(this, eventBus, commandExecutor)

        VoiceUi.attach(
            this,
            null,  // statusText removed
            voiceWaveView,
            voiceToggleButton,
            chatRecyclerView,
            chatInputContainer,
            chatInputEditText,
            chatSendButton,
            voiceWaveView,
            voiceStatusText,
            eventBus,
            transport,
            commandExecutor
        )

        startVoiceLoops()
        requestPermissionsAndAutoStart()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startVoiceLoops() {
        if (voiceLoopStarted) return
        voiceLoopStarted = true

        scope.launch {
            eventBus.events.consumeEach { event ->
                transport.send(event)
            }
        }

        scope.launch {
            eventBus.commands.consumeEach { command ->
                val result = commandExecutor.execute(command)
                Timber.d("Voice command executed: ${command.command} -> $result")
            }
        }

        transport.connect()
        Timber.i("Voice loops started")
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == CompanionService::class.java.name }
    }

    private fun requestPermissionsAndAutoStart() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        } else {
            startServiceOnly()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startServiceOnly()
        } else {
            Timber.w("Permissions denied")
        }
    }

    private fun startServiceOnly() {
        val intent = Intent(this, CompanionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}