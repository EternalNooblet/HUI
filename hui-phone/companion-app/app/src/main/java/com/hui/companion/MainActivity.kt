package com.hui.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.ImageButton
import android.text.TextWatcher
import android.text.Editable
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.hui.companion.util.Config
import com.hui.companion.service.CompanionService
import com.hui.companion.BuildConfig
import com.hui.companion.event.EventBus
import com.hui.companion.transport.Transport
import com.hui.companion.transport.CommandExecutor
import com.hui.companion.ui.ChatRecyclerView
import com.hui.companion.ui.PromptInputLayout
import com.hui.companion.ui.SystemBannerView
import com.hui.companion.ui.AttachmentPicker
import com.hui.companion.ui.StickToBottomFab
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.Button
import com.hui.companion.ui.SessionListAdapter
import com.hui.companion.ui.toSessionRows
import com.hui.companion.voice.VoiceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var buildNameText: TextView
    private lateinit var chatRecyclerView: ChatRecyclerView
    private lateinit var promptInputLayout: PromptInputLayout
    private lateinit var systemBannerView: SystemBannerView
    private lateinit var stickToBottomFab: StickToBottomFab
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionAdapter: SessionListAdapter
    private var sessionCache = listOf<com.hui.companion.db.ChatSessionSummary>()
    private lateinit var attachmentPicker: AttachmentPicker

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
            drawerLayout = findViewById(R.id.drawerLayout)
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
            toolbar.setNavigationOnClickListener { drawerLayout.open() }

            buildNameText = findViewById(R.id.buildNameText)
            chatRecyclerView = findViewById(R.id.chatRecyclerView)
            promptInputLayout = findViewById(R.id.promptInputLayout)
            systemBannerView = findViewById(R.id.systemBannerView)
            stickToBottomFab = findViewById(R.id.stickToBottomFab)

            commandExecutor = CommandExecutor(this)
            transport = Transport(this, eventBus, commandExecutor)

            // --- Session 7: new component wiring ---

            // SystemBannerView: show connection status (auto-dismiss transient states)
            systemBannerView.setOnDismissListener {
                Timber.i("SystemBanner dismissed by user")
            }
            // Honest initial banner: unauthenticated = Disconnected, not Connected.
            val initialAuthed = !(com.hui.companion.util.Config.authToken
                ?: com.hui.companion.util.Config.loadAuthToken()).isNullOrEmpty()
            if (initialAuthed) {
                systemBannerView.show("Connected to server", SystemBannerView.Variant.INFO, 4000)
            } else {
                systemBannerView.show("Disconnected from server", SystemBannerView.Variant.ERROR, 4000)
            }

            // Transport connection state → SystemBannerView (dynamic)
            transport.onConnectionStateChanged = { state ->
                when (state) {
                    Transport.ConnectionState.CONNECTED ->
                        systemBannerView.show("Connected to server", SystemBannerView.Variant.INFO, 4000)
                    Transport.ConnectionState.DISCONNECTED ->
                        systemBannerView.show("Disconnected from server", SystemBannerView.Variant.ERROR, 4000)
                    Transport.ConnectionState.RECONNECTING ->
                        systemBannerView.show("Reconnecting…", SystemBannerView.Variant.WARNING, 4000)
                    Transport.ConnectionState.CONNECTING ->
                        systemBannerView.show("Connecting…", SystemBannerView.Variant.INFO, 4000)
                }
            }

            // StickToBottomFab: attach to chat RecyclerView
            stickToBottomFab.attach(chatRecyclerView)

            // AttachmentPicker: pick → upload to server → insert markdown image into input
            attachmentPicker = AttachmentPicker(this, promptInputLayout) { uri ->
                Timber.i("Attachment selected: %s", uri.toString())
                uploadAttachment(uri) { url ->
                    runOnUiThread {
                        val filename = url.substringAfterLast('/')
                        val currentText = promptInputLayout.getText()
                        val newText = if (currentText.isNotEmpty()) "$currentText\n" else ""
                        promptInputLayout.setText("$newText![$filename]($url)")
                    }
                }
            }
            promptInputLayout.setOnAttachmentClickListener {
                attachmentPicker.pickAttachment()
            }

            // VoiceUi attach — chat mode with voice input via mic button
            VoiceUi.attach(
                this,
                chatRecyclerView,
                promptInputLayout,
                eventBus,
                transport,
                commandExecutor,
                findViewById(R.id.thinkingBarView)
            )

            // Send button enable/disable based on input text
            val sendBtn = findViewById<ImageButton>(R.id.promptInputSendBtn)
            val textarea = promptInputLayout.getTextarea()
            textarea?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val hasText = s?.isNotEmpty() == true
                    sendBtn.isEnabled = hasText
                    sendBtn.alpha = if (hasText) 1.0f else 0.5f
                }
            })

            wireSessions(); startVoiceLoops()
            requestPermissionsAndAutoStart()
        }


    private fun wireSessions() {
        val rv = findViewById<RecyclerView>(R.id.sessionRecyclerView)
        sessionAdapter = SessionListAdapter(this) { sid ->
            VoiceUi.switchTo(sid)
            sessionAdapter.setActive(sid)
            drawerLayout.close()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = sessionAdapter
        // Observe sessions (prompt-kit Sidebar history)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            VoiceUi.observeSessions().collect { sessions ->
                sessionCache = sessions
                sessionAdapter.setActive(VoiceUi.currentSessionId())
                sessionAdapter.submitList(toSessionRows(sessions, findViewById<EditText>(R.id.sessionSearchInput).text.toString()))
            }
        }
        findViewById<Button>(R.id.newChatBtn).setOnClickListener {
            VoiceUi.newChat()
            sessionAdapter.setActive(VoiceUi.currentSessionId())
            drawerLayout.close()
        }
        val searchInput = findViewById<EditText>(R.id.sessionSearchInput)
        findViewById<ImageButton>(R.id.sidebarSearchBtn).setOnClickListener {
            searchInput.visibility = if (searchInput.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
            if (searchInput.visibility == android.view.View.VISIBLE) searchInput.requestFocus()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sessionAdapter.submitList(toSessionRows(sessionCache, s.toString()))
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Returning from Settings after sign-out (or a revoked token):
        // close this activity's socket so the banner honestly shows
        // Disconnected and no event can be sent until a fresh QR scan.
        // Guarded by ::transport.isInitialized — onResume can fire before
        // onCreate finishes in some launch paths.
        if (::transport.isInitialized) {
            val token = com.hui.companion.util.Config.authToken
                ?: com.hui.companion.util.Config.loadAuthToken()
            if (token.isNullOrEmpty()) {
                transport.disconnect()
                // disconnect() may be a no-op state-wise (already DISCONNECTED),
                // so show the banner directly to keep the UI honest.
                if (::systemBannerView.isInitialized) {
                    systemBannerView.show("Disconnected from server", SystemBannerView.Variant.ERROR, 4000)
                }
            } else if (!transport.isConnected) {
                // Authenticated (e.g. just scanned a QR) but the socket was
                // never opened — connect() was blocked at startup for lack
                // of a token. Connect now, or sends drop silently.
                transport.connect()
            }
        }
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

    /** Handle attachment picker result (from AttachmentPicker). */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (attachmentPicker.onActivityResult(requestCode, resultCode, data)) {
            return // handled by AttachmentPicker
        }
    }

    /** Upload a picked attachment to the server, then callback with the public URL. */
    private fun uploadAttachment(uri: android.net.Uri, callback: (String) -> Unit) {
        // Copy content URI to a temp cache file for OkHttp
        val cacheFile = java.io.File(cacheDir, "attachment_${System.currentTimeMillis()}")
        cacheDir.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        } ?: return

        val client = com.hui.companion.util.PinnedTls.okHttpClient()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", cacheFile.name,
                RequestBody.create(null, cacheFile))
            .build()

        // Runtime URL (QR/manual), never a build-time default.
        val baseUrl = Config.serverUrl.ifBlank { Config.loadServerUrl() }
        if (baseUrl.isBlank()) {
            Timber.w("Upload skipped: no server URL configured")
            callback("")
            return
        }
        val uploadUrl = baseUrl.replace("wss://", "https://").replace("ws://", "http://") + "/upload"
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Timber.e(e, "Attachment upload failed")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Timber.w("Upload failed: ${response.code}")
                        return@use
                    }
                    val body = response.body?.string() ?: return@use
                    try {
                        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                        val url = json.get("url").asString
                        runOnUiThread { callback(url) }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse upload response: $body")
                    }
                }
            }
        })
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