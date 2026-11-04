package com.hui.companion.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hui.companion.event.ChatEvent
import com.hui.companion.event.Event
import com.hui.companion.util.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct Hermes JSON-RPC transport (DIRECT_PLAN.md): plain ws to
 * `hermes serve` at ws://<host>:9119/api/ws?token=<blob>. No :8081 companion
 * server, no pinned TLS, no hello/events protocol, no sensor uploads.
 *
 * Wire contract (verified, skills hui-gateway-remote-test):
 *  - first frame inbound is the `gateway.ready` event (send nothing first)
 *  - then `client.capabilities {server_requests:true}` (advertise srq replies)
 *  - `session.create {source}` only (request_id rejected); result carries
 *    session_id (runtime — prompt.submit names this) + stored_session_id
 *  - after EVERY ws drop: `session.resume {session_id: stored_session_id}`
 *    (a runtime id answers 4007 "session not found" — expected, not a bug;
 *    on 4007 we fall back to a fresh create)
 *  - `prompt.submit {session_id, text, queued:true}` — NO submission_id
 *  - replies stream as message.start / message.delta / message.complete
 *  - server→client requests arrive as frames with id `srq-…` + method;
 *    they must always be answered (result, or -32601 to decline)
 *
 * One gateway session serves the whole chat window (same as the old bridge).
 * The UI's local Room session ids only group bubbles on the phone.
 */
class Transport(private val context: Context) {

    /** Completed assistant reply. voiceOrigin=true came from a voice turn
     *  (bubble + Android TTS), false from text (bubble only). Main thread. */
    var onAssistantReply: ((text: String, voiceOrigin: Boolean) -> Unit)? = null

    /** A turn is streaming (message.start/delta). Main thread. */
    var onTurnProgress: (() -> Unit)? = null

    /** A server→client request (srq-…) needs a user answer. Main thread. */
    var onServerRequest: ((ServerRequest) -> Unit)? = null

    data class ServerRequest(val id: String, val method: String, val params: JsonObject)

    private var webSocket: WebSocket? = null
    private val client: OkHttpClient = buildClient()
    // HTTP base the live socket was dialed with. A QR rescan changes
    // Config.serverUrl in prefs — onResume checks needsReconnect() so a
    // rescan actually re-points the socket instead of leaving it on the
    // old server while new HTTP calls (transcribe/upload) hit the new one.
    @Volatile private var connectedBase: String? = null
    private var reconnectDelay = 1000L
    private var isConnecting = false
    // True after disconnect() / 401-403: onClosed/onFailure must not schedule
    // a reconnect. Cleared by connect().
    private var intentionalDisconnect = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcIdSeq = AtomicInteger(0)
    // rpc id -> method, for routing responses.
    private val pendingRpc = mutableMapOf<String, String>()

    // Gateway session state. session_id names prompt.submit; the stored id
    // (from the create snapshot) is what session.resume must present.
    private var sessionId: String? = null
    private var storedSessionId: String? = null
    @Volatile var sessionReady: Boolean = false
        private set
    private var readySeen = false   // gateway.ready handled on this connection
    private var submittedThisConnection = false

    private data class PendingTurn(
        val text: String,
        val imagePaths: List<String>,
        val voiceOrigin: Boolean,
        var retried: Boolean = false
    )
    private var pendingTurn: PendingTurn? = null
    private val streamBuffer = StringBuilder()

    @Volatile private var lastFrameAtMs = 0L
    private val watchdog = Runnable { watchdogTick() }

    /** Public auth check for UI send paths (chat/voice). */
    fun isAuthenticated(): Boolean = hasAuthToken()

    /** Auth gate: no stored token = not authenticated. Checked on every
     *  connect/send so clearing the token in Settings blocks traffic
     *  immediately, even on an already-open socket. */
    private fun hasAuthToken(): Boolean =
        !(Config.authToken ?: Config.loadAuthToken()).isNullOrEmpty()

    /** True when the socket is open but was dialed against a different
     *  base than the current config (QR rescan) — caller should
     *  disconnect + connect. */
    fun needsReconnect(): Boolean =
        webSocket != null && connectedBase != null &&
            connectedBase != Config.httpBaseUrl()

    /** True when a WebSocket session is currently open. */
    val isConnected: Boolean
        get() = webSocket != null

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING }
    private var connectionState = ConnectionState.DISCONNECTED
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    private fun setConnectionState(state: ConnectionState) {
        if (connectionState != state) {
            connectionState = state
            Timber.i("Transport state: $state")
            // onFailure/onOpen run on OkHttp threads; the callback touches
            // views (SystemBannerView), so it must run on the main thread
            // (CalledFromWrongThreadException otherwise — crash on any
            // reconnect, e.g. server restart).
            mainHandler.post { onConnectionStateChanged?.invoke(state) }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("WS open, code: ${response.code}")
            this@Transport.webSocket = webSocket
            reconnectDelay = 1000
            isConnecting = false
            readySeen = false
            submittedThisConnection = false
            sessionReady = false
            lastFrameAtMs = System.currentTimeMillis()
            setConnectionState(ConnectionState.CONNECTED)
            // Nothing to send yet: the first inbound frame is gateway.ready,
            // and bootstrap (capabilities + session) runs from there.
            startWatchdog()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastFrameAtMs = System.currentTimeMillis()
            handleFrame(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.d("WS received binary: ${bytes.size} bytes (ignored)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.w("WS closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.w("WS closed: $code $reason")
            onSocketDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.e(t, "WS failure, response: ${response?.code}")
            val code = response?.code
            if (code == 401 || code == 403) {
                // Token rejected (expired/revoked): retrying with the same
                // token is pointless — wait for a fresh QR scan in Settings.
                Timber.w("WS $code unauthorized — not reconnecting; scan a fresh QR in Settings")
                isConnecting = false
                intentionalDisconnect = true
                onSocketDown(reschedule = false)
                return
            }
            onSocketDown()
        }
    }

    private fun onSocketDown(reschedule: Boolean = true) {
        webSocket = null
        connectedBase = null
        sessionReady = false
        streamBuffer.setLength(0)
        stopWatchdog()
        setConnectionState(ConnectionState.DISCONNECTED)
        // Mid-turn drop: the resume path resubmits the pending turn once.
        val p = pendingTurn
        if (p != null && submittedThisConnection) {
            if (p.retried) {
                pendingTurn = null
                deliverReply("Connection lost during reply — please try again.", p.voiceOrigin)
            } else {
                p.retried = true
                Timber.i("WS dropped mid-turn; will resume + resubmit once")
            }
        }
        if (reschedule && !isConnecting && !intentionalDisconnect) scheduleReconnect()
    }

    fun connect() {
        if (isConnecting || webSocket != null) return
        if (!hasAuthToken()) {
            // Unauthenticated: do not even open a socket. The UI banner
            // (via DISCONNECTED) makes clear nothing is being received.
            Timber.w("Not authenticated — connect() blocked; scan a QR in Settings")
            setConnectionState(ConnectionState.DISCONNECTED)
            return
        }
        val url = Config.wsUrlWithToken()
        if (url.isBlank()) {
            Timber.w("No server URL configured")
            return
        }
        intentionalDisconnect = false
        isConnecting = true
        setConnectionState(ConnectionState.CONNECTING)
        connectedBase = Config.httpBaseUrl()
        Timber.i("Connecting to ${Config.wsUrlForLog()}")

        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, listener)
    }

    fun disconnect() {
        intentionalDisconnect = true
        isConnecting = false
        stopWatchdog()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        connectedBase = null
        sessionReady = false
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    // ------------------------------------------------------------- prompts

    /**
     * Submit a user turn. Returns false only when the socket is down (the
     * caller should restore attachments / keep the text). When the socket
     * is up but the gateway session isn't bootstrapped yet, the turn is
     * queued and flushed right after session.create/resume lands.
     */
    fun submitPrompt(text: String, imagePaths: List<String>, voiceOrigin: Boolean): Boolean {
        if (!hasAuthToken()) {
            Timber.w("Not authenticated — prompt dropped; scan a QR in Settings")
            disconnect()
            return false
        }
        if (webSocket == null) {
            Timber.w("WS not connected, prompt dropped")
            return false
        }
        pendingTurn = PendingTurn(text, imagePaths, voiceOrigin)
        streamBuffer.setLength(0)
        if (sessionReady) {
            submitNow(pendingTurn!!)
        } else {
            Timber.i("Prompt queued until session bootstrap completes")
        }
        return true
    }

    private fun submitNow(turn: PendingTurn) {
        val sid = sessionId ?: run {
            Timber.w("submitNow without session_id — queued")
            return
        }
        // Attach queued images first (gateway-visible paths from
        // /api/chat/image-upload); the text turn itself carries no URLs.
        for (path in turn.imagePaths) {
            val params = JsonObject().apply {
                addProperty("session_id", sid)
                addProperty("path", path)
            }
            rpc("image.attach", params)
        }
        // NO submission_id (the gateway 4000s if always sent); queued IS allowed.
        val params = JsonObject().apply {
            addProperty("session_id", sid)
            addProperty("text", turn.text)
            addProperty("queued", true)
        }
        rpc("prompt.submit", params)
        submittedThisConnection = true
        Timber.i("prompt.submit sent (voiceOrigin=${turn.voiceOrigin}, images=${turn.imagePaths.size})")
    }

    /** Legacy EventBus path: only ChatEvent still means something. Sensor /
     *  audio events are dropped — direct mode has no sensor upload. */
    fun send(event: Event): Boolean {
        return when (event) {
            is ChatEvent -> {
                val paths = event.payload.imageUrl?.let { listOf(it) } ?: emptyList()
                submitPrompt(event.payload.text, paths, voiceOrigin = false)
            }
            else -> {
                Timber.d("Event dropped in direct mode: ${event.type} (no sensor/audio upload)")
                false
            }
        }
    }

    // ------------------------------------------------------- server replies

    /** Answer a server→client request (srq-…). Never leave one unanswered. */
    fun respondServerRequest(id: String, result: JsonObject) {
        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("result", result)
        }
        webSocket?.send(frame.toString())
        Timber.i("srq answered: $id")
    }

    /** Decline a server→client request the phone has no handler for. */
    fun respondServerRequestError(id: String, code: Int, message: String) {
        val err = JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        }
        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            add("error", err)
        }
        webSocket?.send(frame.toString())
        Timber.i("srq declined: $id ($message)")
    }

    // ------------------------------------------------------------ wire core

    private fun rpc(method: String, params: JsonObject): String {
        val id = "c-${rpcIdSeq.incrementAndGet()}"
        pendingRpc[id] = method
        val frame = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val sent = webSocket?.send(frame.toString()) == true
        if (!sent) {
            Timber.w("RPC $method ($id) not sent — socket down")
            pendingRpc.remove(id)
        }
        return id
    }

    private fun handleFrame(text: String) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Timber.w("Non-JSON frame ignored: ${text.take(120)}")
            return
        }
        val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        when {
            method == "event" -> handleEvent(obj.getAsJsonObject("params"))
            method != null && id != null && obj.has("method") -> handleServerRequest(id, method, obj.getAsJsonObject("params"))
            id != null && pendingRpc.containsKey(id) -> handleRpcResponse(id, obj)
            else -> Timber.d("Unhandled frame: ${text.take(160)}")
        }
    }

    private fun handleEvent(params: JsonObject) {
        val type = params.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        when (type) {
            "gateway.ready" -> {
                if (readySeen) return
                readySeen = true
                Timber.i("gateway.ready received")
                bootstrap()
            }
            "message.start" -> postTurnProgress()
            "message.delta" -> {
                val t = permissiveText(params)
                if (t.isNullOrEmpty()) {
                    // Missing / empty delta text = paragraph break.
                    streamBuffer.append("\n\n")
                } else {
                    streamBuffer.append(t)
                }
                postTurnProgress()
            }
            "message.interim" -> {
                val t = permissiveText(params)
                if (!t.isNullOrEmpty()) {
                    streamBuffer.append(t).append("\n\n")
                }
                postTurnProgress()
            }
            "message.complete" -> {
                val payload = if (params.has("payload") && params.get("payload").isJsonObject)
                    params.getAsJsonObject("payload") else params
                val text = payload.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                val reused = payload.get("response_reused")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
                    payload.get("response_previewed")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                val status = payload.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                // response_reused/previewed: the complete frame adds no text —
                // the streamed buffer already IS the reply. Print it once.
                val finalText = when {
                    reused -> streamBuffer.toString().trim()
                    !text.isNullOrEmpty() -> text
                    else -> streamBuffer.toString().trim()
                }
                Timber.i("message.complete status=$status reused=$reused len=${finalText.length}")
                completeTurn(finalText, status)
            }
            "error" -> {
                val payload = if (params.has("payload") && params.get("payload").isJsonObject)
                    params.getAsJsonObject("payload") else params
                val msg = payload.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: permissiveText(params) ?: "gateway error"
                Timber.e("gateway error event: $msg")
                completeTurn("Error: $msg", "error")
            }
            "request.cancel" -> Timber.i("srq withdrawn by server: ${params}")
            else -> Timber.d("event ignored: $type")
        }
    }

    private fun completeTurn(finalText: String, status: String?) {
        val turn = pendingTurn
        pendingTurn = null
        streamBuffer.setLength(0)
        val text = finalText.ifBlank {
            if (status == "error") "Turn failed." else "(empty reply)"
        }
        deliverReply(text, turn?.voiceOrigin ?: false)
    }

    private fun deliverReply(text: String, voiceOrigin: Boolean) {
        mainHandler.post { onAssistantReply?.invoke(text, voiceOrigin) }
    }

    private fun postTurnProgress() {
        mainHandler.post { onTurnProgress?.invoke() }
    }

    /** Permissive text extraction across the message.* nesting shapes:
     *  payload.text | params.text | params.message.text. */
    private fun permissiveText(params: JsonObject): String? {
        val payload = if (params.has("payload") && params.get("payload").isJsonObject)
            params.getAsJsonObject("payload") else null
        for (src in listOfNotNull(payload, params)) {
            val t = src.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            if (t != null) return t
            val m = src.get("message")
            if (m != null && m.isJsonObject) {
                val mt = m.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                if (mt != null) return mt
            }
        }
        return null
    }

    private fun bootstrap() {
        // Advertise srq support first — without it the server fails every
        // server→client request fast (client predates server requests).
        rpc("client.capabilities", JsonObject().apply { addProperty("server_requests", true) })
        val stored = storedSessionId
        if (stored != null) {
            rpc("session.resume", JsonObject().apply { addProperty("session_id", stored) })
        } else {
            rpc("session.create", JsonObject().apply { addProperty("source", "cli") })
        }
    }

    private fun handleRpcResponse(id: String, obj: JsonObject) {
        val method = pendingRpc.remove(id) ?: return
        val err = obj.get("error")
        if (err != null && err.isJsonObject) {
            val code = err.asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
            val msg = err.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            Timber.w("RPC $method error: $code $msg")
            when (method) {
                "session.resume" -> {
                    // Stored id unknown to the server (e.g. it restarted):
                    // fall back to a fresh create instead of dead-ending.
                    storedSessionId = null
                    sessionId = null
                    rpc("session.create", JsonObject().apply { addProperty("source", "cli") })
                }
                "prompt.submit" -> completeTurn("Error: ${msg ?: "submit refused"}", "error")
                else -> Unit
            }
            return
        }
        val result = if (obj.has("result") && obj.get("result").isJsonObject)
            obj.getAsJsonObject("result") else null
        when (method) {
            "session.create", "session.resume" -> {
                val sid = result?.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString
                val stored = result?.get("stored_session_id")?.takeIf { it.isJsonPrimitive }?.asString
                if (!sid.isNullOrEmpty()) sessionId = sid
                if (!stored.isNullOrEmpty()) storedSessionId = stored
                sessionReady = true
                Timber.i("session ready: session_id=$sid stored=$stored")
                val p = pendingTurn
                if (p != null) {
                    submitNow(p)
                }
            }
            "prompt.submit" -> Timber.d("prompt.submit receipt: $result")
            "client.capabilities" -> Timber.i("client.capabilities ack: $result")
            else -> Timber.d("RPC $method ok")
        }
    }

    private fun handleServerRequest(id: String, method: String, params: JsonObject?) {
        Timber.i("server request $id method=$method")
        if (params == null) {
            respondServerRequestError(id, -32602, "missing params")
            return
        }
        mainHandler.post { onServerRequest?.invoke(ServerRequest(id, method, params)) }
    }

    // -------------------------------------------------------------- watchdog

    private fun startWatchdog() {
        stopWatchdog()
        mainHandler.postDelayed(watchdog, 15_000)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdog)
    }

    private fun watchdogTick() {
        if (webSocket == null) return
        val silentMs = System.currentTimeMillis() - lastFrameAtMs
        if (silentMs > 30_000) {
            Timber.i("15s+ silence ($silentMs ms) — gateway.ping")
            rpc("gateway.ping", JsonObject())
        }
        mainHandler.postDelayed(watchdog, 15_000)
    }

    private fun scheduleReconnect() {
        setConnectionState(ConnectionState.RECONNECTING)
        Timber.i("Scheduling reconnect in ${reconnectDelay}ms")
        mainHandler.postDelayed({ connect() }, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000)
    }

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
}
