package com.hui.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.ImageButton
import android.text.TextWatcher
import android.text.Editable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.hui.companion.util.Config
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
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private lateinit var transport: Transport
    private lateinit var commandExecutor: CommandExecutor

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
            transport = Transport(this)

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

            // AttachmentPicker (prompt-kit FileUpload): pick → upload to
            // server → AttachmentState. Preview chips live above the
            // textarea; send is gated while an upload is in flight.
            attachmentPicker = AttachmentPicker(
                this, promptInputLayout,
                onAttachmentSelected = { uri ->
                    Timber.i("Attachment selected: %s", uri.toString())
                    com.hui.companion.util.AttachmentState.setUploading(1)
                    attachmentPicker.setUploading(uri, true)
                    uploadAttachment(uri) { url ->
                        com.hui.companion.util.AttachmentState.setUploading(-1)
                        attachmentPicker.setUploading(uri, false)
                        if (url.isNotEmpty()) {
                            val filename = try { java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") } catch (e: Exception) { url.substringAfterLast('/') }
                            // Thumbnail chip is the UI; keep the input clean. The
                            // server-relative URL travels in the chat payload, not
                            // as ugly markdown text in the user-visible input.
                            com.hui.companion.util.AttachmentState.add(uri.toString(), url, filename)
                        } else {
                            android.widget.Toast.makeText(
                                this, "Image upload failed — attachment removed",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            attachmentPicker.removeChip(uri.toString())
                        }
                    }
                },
                onAttachmentRemoved = { id ->
                    com.hui.companion.util.AttachmentState.remove(id)
                }
            )
            promptInputLayout.setOnAttachmentClickListener {
                attachmentPicker.pickAttachment()
            }
            promptInputLayout.setAttachmentStateProviders(
                hasAttachment = { com.hui.companion.util.AttachmentState.hasReady() },
                isUploading = { com.hui.companion.util.AttachmentState.isUploading() }
            )
            promptInputLayout.setOnSendSuccessListener {
                attachmentPicker.clearAll()
            }

            // VoiceUi attach — chat mode with voice input via mic button
            VoiceUi.attach(
                this,
                chatRecyclerView,
                promptInputLayout,
                transport,
                findViewById(R.id.thinkingBarView)
            )

            // Send enable/disable: owned by PromptInputLayout now
            // (text OR ready attachment, gated while uploading). Attachment
            // changes re-evaluate via AttachmentState listener.
            com.hui.companion.util.AttachmentState.addListener {
                promptInputLayout.refreshSendState()
            }

            wireSessions(); wireTransport()
            requestPermissions()
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
            } else if (transport.needsReconnect()) {
                // QR rescan changed the target while the old socket was
                // still open — re-point it (transcribe/upload already use
                // the new base, so a stale socket splits the app).
                Timber.i("Server changed via QR — reconnecting transport")
                transport.disconnect()
                transport.connect()
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

    /**
     * Wire Transport callbacks and open the one gateway socket. Replies land
     * here: voice-origin turns get bubble + Android TTS, text turns bubble
     * only. Server requests (srq-…) are answered via dialogs below.
     */
    private fun wireTransport() {
        transport.onAssistantReply = { text, voiceOrigin ->
            val sid = VoiceUi.currentSessionId() ?: ""
            if (voiceOrigin) {
                VoiceUi.handleVoiceReply(text, sid)
                commandExecutor.speakText(text)
            } else {
                VoiceUi.handleChatReply(text, sid)
            }
        }
        transport.onTurnProgress = { VoiceUi.nudgeThinking() }
        transport.onServerRequest = { req -> handleServerRequest(req) }
        transport.connect()
        Timber.i("Transport wired")
    }

    // ------------------------------------------------- server requests

    private fun handleServerRequest(req: Transport.ServerRequest) {
        if (isFinishing || isDestroyed) {
            declineServerRequest(req, "activity gone")
            return
        }
        when (req.method) {
            "approval" -> showApprovalDialog(req)
            "clarify" -> showClarifyDialog(req)
            "sudo", "secret", "vault.unlock_prompt", "vault.save_login", "vault.code" ->
                showValueDialog(req)
            // Desktop-only bridges (setup_beat, preview/terminal/window reads,
            // tour, theme): decline per the contract (-32601), never hang.
            else -> {
                Timber.i("srq declined (no phone handler): ${req.method}")
                transport.respondServerRequestError(req.id, -32601, "method not supported on phone client")
            }
        }
    }

    private fun declineServerRequest(req: Transport.ServerRequest, why: String) {
        when (req.method) {
            "approval" -> transport.respondServerRequest(
                req.id, JsonObject().apply { addProperty("choice", "deny") })
            "clarify" -> {
                val answers = JsonObject()
                req.params.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.forEach { q ->
                        q.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("qid")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.let { answers.add(it, com.google.gson.JsonNull.INSTANCE) }
                    }
                transport.respondServerRequest(
                    req.id, JsonObject().apply { add("answers", answers) })
            }
            else -> transport.respondServerRequest(
                req.id, JsonObject().apply { addProperty("value", "") })
        }
        Timber.i("srq safe-declined (${req.method}, $why)")
    }

    private fun showApprovalDialog(req: Transport.ServerRequest) {
        val p = req.params
        val desc = p.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val cmd = p.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val message = buildString {
            if (desc.isNotEmpty()) appendLine(desc)
            if (cmd.isNotEmpty()) append(cmd)
        }.ifBlank { "A command needs approval." }
        AlertDialog.Builder(this)
            .setTitle("Approval requested")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Allow once") { _, _ ->
                transport.respondServerRequest(
                    req.id, JsonObject().apply { addProperty("choice", "once") })
            }
            .setNegativeButton("Deny") { _, _ ->
                transport.respondServerRequest(
                    req.id, JsonObject().apply { addProperty("choice", "deny") })
            }
            .show()
    }

    private fun showClarifyDialog(req: Transport.ServerRequest) {
        val questions = req.params.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: run {
                transport.respondServerRequest(
                    req.id, JsonObject().apply { add("answers", JsonObject()) })
                return
            }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val inputs = mutableListOf<Pair<String, EditText>>()
        for (q in questions) {
            val qo = q.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val qid = qo.get("qid")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val question = qo.get("question")?.takeIf { it.isJsonPrimitive }?.asString ?: qid
            val choices = qo.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.joinToString("\n") { it.takeIf { e -> e.isJsonPrimitive }?.asString ?: "" }
            val label = TextView(this).apply {
                text = if (choices.isNullOrEmpty()) question else "$question\n$choices"
            }
            val input = EditText(this).apply {
                hint = "Answer (blank = skip)"
                isSingleLine = false
            }
            layout.addView(label)
            layout.addView(input)
            inputs.add(qid to input)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this)
            .setTitle("Clarification needed")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("Submit") { _, _ ->
                val answers = JsonObject()
                for ((qid, input) in inputs) {
                    val v = input.text.toString().trim()
                    if (v.isEmpty()) answers.add(qid, com.google.gson.JsonNull.INSTANCE)
                    else answers.addProperty(qid, v)
                }
                transport.respondServerRequest(
                    req.id, JsonObject().apply { add("answers", answers) })
            }
            .setNegativeButton("Skip all") { _, _ ->
                val answers = JsonObject()
                for ((qid, _) in inputs) answers.add(qid, com.google.gson.JsonNull.INSTANCE)
                transport.respondServerRequest(
                    req.id, JsonObject().apply { add("answers", answers) })
            }
            .show()
    }

    private fun showValueDialog(req: Transport.ServerRequest) {
        val p = req.params
        fun s(key: String): String =
            p.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val (title, message, password) = when (req.method) {
            "sudo" -> Triple("Sudo password", s("command").ifBlank { "sudo needs a password" }, true)
            "secret" -> Triple("Secret needed", s("prompt").ifBlank { s("env_var") }, true)
            "vault.unlock_prompt" -> Triple(
                "Unlock vault", "Unlock ${s("display_name").ifBlank { s("backend") }}".trim(), true)
            "vault.save_login" -> Triple(
                "Save login", "Save a login for ${s("site").ifBlank { s("origin") }}", false)
            else -> Triple(
                "One-time code", buildString {
                    val site = s("site"); if (site.isNotEmpty()) append(site)
                    val hint = s("hint"); if (hint.isNotEmpty()) append(" — $hint")
                }.ifBlank { "Enter the code from your device" }, false)
        }
        val input = EditText(this).apply {
            if (password) inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Value (blank = skip)"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ ->
                transport.respondServerRequest(
                    req.id, JsonObject().apply { addProperty("value", input.text.toString()) })
            }
            .setNegativeButton("Skip") { _, _ ->
                transport.respondServerRequest(
                    req.id, JsonObject().apply { addProperty("value", "") })
            }
            .show()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Timber.i("Permissions granted")
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

    /** Upload a picked attachment to the gateway image store
     *  (POST /api/chat/image-upload, Bearer blob — probed live), then
     *  callback with the gateway-visible path for image.attach. */
    private fun uploadAttachment(uri: android.net.Uri, callback: (String) -> Unit) {
        // Copy content URI to a temp cache file for the data_url encode.
        // Keep the original display name when possible.
        val rawName = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)) else null
            }
        }.getOrNull() ?: "attachment_${System.currentTimeMillis()}.jpg"
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace("[^A-Za-z0-9._-]", "_").takeIf { it.isNotBlank() } ?: "attachment.jpg"
        val filename = if (safeName.contains('.')) safeName else "$safeName.jpg"
        val cacheFile = java.io.File(cacheDir, filename)
        cacheDir.mkdirs()
        // Every path must invoke callback exactly once — the caller
        // decrements uploadingCount and removes the chip on "".
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            } != null
        }.getOrElse { e ->
            Timber.e(e, "Attachment copy failed")
            false
        }
        if (!copied || cacheFile.length() == 0L) {
            runOnUiThread { callback("") }
            return
        }

        val mime = contentResolver.getType(uri)
            ?: when (filename.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
        val dataUrl = "data:$mime;base64," +
            android.util.Base64.encodeToString(cacheFile.readBytes(), android.util.Base64.NO_WRAP)
        // filename may contain chars needing JSON escaping; Gson builds it.
        val payload = JsonObject().apply {
            addProperty("data_url", dataUrl)
            addProperty("filename", filename)
        }

        val client = OkHttpClient()
        // Runtime URL (QR), never a build-time default.
        val baseUrl = Config.httpBaseUrl()
        val token = Config.authToken ?: Config.loadAuthToken()
        if (baseUrl.isBlank() || token.isNullOrEmpty()) {
            Timber.w("Upload skipped: no server URL/token configured")
            callback("")
            return
        }
        val request = Request.Builder()
            .url(baseUrl + "/api/chat/image-upload")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Timber.e(e, "Attachment upload failed")
                runOnUiThread { callback("") }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                var path = ""
                response.use {
                    if (!response.isSuccessful) {
                        Timber.w("Upload failed: ${response.code}")
                        return@use
                    }
                    val body = response.body?.string() ?: return@use
                    try {
                        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                        path = json.get("path")?.takeIf { e -> e.isJsonPrimitive }?.asString ?: ""
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse upload response: $body")
                    }
                }
                runOnUiThread { callback(path) }
            }
        })
    }
}