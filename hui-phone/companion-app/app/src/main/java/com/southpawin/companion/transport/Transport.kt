package com.southpawin.companion.transport

import android.content.Context
import android.content.res.Resources
import android.util.Base64
import com.southpawin.companion.R
import com.southpawin.companion.util.Config
import com.southpawin.companion.event.Command
import com.southpawin.companion.event.Event
import com.southpawin.companion.event.EventBus
import com.southpawin.companion.event.Result
import okhttp3.*
import okio.ByteString
import timber.log.Timber
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class Transport(
    private val context: Context,
    private val eventBus: EventBus,
    private val commandExecutor: CommandExecutor
) {
    private var webSocket: WebSocket? = null
    private val client = buildClient()
    private var reconnectDelay = 1000L
    private var isConnecting = false
    // True after disconnect() / 401 / unauthenticated drop: onClosed/onFailure
    // must not schedule a reconnect. Cleared by connect().
    private var intentionalDisconnect = false

    /** Public auth check for UI send paths (chat/voice). */
    fun isAuthenticated(): Boolean = hasAuthToken()

    /** Auth gate: no stored token = not authenticated. Checked on every
     *  connect/send so clearing the token in Settings blocks traffic
     *  immediately, even on an already-open socket. */
    private fun hasAuthToken(): Boolean =
        !(Config.authToken ?: Config.loadAuthToken()).isNullOrEmpty()

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
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onConnectionStateChanged?.invoke(state)
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("WSS connected securely, protocol: ${response.header("Sec-WebSocket-Protocol")}, code: ${response.code}")
            this@Transport.webSocket = webSocket
            reconnectDelay = 1000
            isConnecting = false
            setConnectionState(ConnectionState.CONNECTED)
            sendHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Timber.d("WSS received: $text")
            try {
                val command = Command.parse(text)
                if (command != null) {
                    eventBus.tryPostCommand(command)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse command")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.d("WSS received binary: ${bytes.size} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.w("WSS closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.w("WSS closed: $code $reason")
            this@Transport.webSocket = null
            setConnectionState(ConnectionState.DISCONNECTED)
            if (!isConnecting && !intentionalDisconnect) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.e(t, "WSS failure, response: ${response?.code}")
            this@Transport.webSocket = null
            setConnectionState(ConnectionState.DISCONNECTED)
            if (response?.code == 401) {
                // Token rejected (expired/revoked): retrying with the same
                // token is pointless — wait for a fresh QR scan in Settings.
                Timber.w("WSS 401 unauthorized — not reconnecting; scan a fresh QR in Settings")
                isConnecting = false
                intentionalDisconnect = true
                return
            }
            if (!isConnecting && !intentionalDisconnect) scheduleReconnect()
        }
    }

    fun connect() {
        if (isConnecting || webSocket != null) return
        if (Config.wsUrl.isBlank()) {
            Timber.w("No server URL configured")
            return
        }
        if (!hasAuthToken()) {
            // Unauthenticated: do not even open a socket. The UI banner
            // (via DISCONNECTED) makes clear nothing is being received.
            Timber.w("Not authenticated — connect() blocked; scan a QR in Settings")
            setConnectionState(ConnectionState.DISCONNECTED)
            return
        }
        intentionalDisconnect = false
        isConnecting = true
        setConnectionState(ConnectionState.CONNECTING)
        Timber.i("Connecting securely to ${Config.wsUrl}")

        val request = Request.Builder()
            .url(Config.wsUrl)
            .addHeader("Authorization", "Bearer " + (Config.authToken ?: ""))
            .addHeader("X-Device-ID", Config.deviceId)
            .build()

        client.newWebSocket(request, listener)
    }

    private fun sendHello() {
        val hello = "{\"type\":\"hello\",\"device_id\":\"${Config.deviceId}\",\"capabilities\":[\"attention\",\"battery\",\"notification\",\"screen\",\"audio\"]}"
        webSocket?.send(hello)
    }

    /** @return true if the event was actually handed to the socket. */
    fun send(event: Event): Boolean {
        if (!hasAuthToken()) {
            // Blocked: unauthenticated devices must not get events received.
            // Drop locally, flip the UI to DISCONNECTED, and close any
            // stale socket so the server stops seeing this device too.
            Timber.w("Not authenticated — event dropped: ${event.type}; scan a QR in Settings")
            disconnect()
            return false
        }
        val sent = webSocket?.send(event.toJson())
        if (sent == true) {
            Timber.d("Sent event: ${event.type}")
        } else {
            Timber.w("WSS not connected, event dropped: ${event.type}")
        }
        return sent == true
    }

    private fun scheduleReconnect() {
        setConnectionState(ConnectionState.RECONNECTING)
        Timber.i("Scheduling reconnect in ${reconnectDelay}ms")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ connect() }, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000)
    }

    fun disconnect() {
        intentionalDisconnect = true
        isConnecting = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun buildClient(): OkHttpClient {
        val trustManager = createTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ ->
                hostname == "YOUR_LAN_HOST" || hostname == "localhost" || hostname == "127.0.0.1"
            }
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private fun createTrustManager(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val certFactory = CertificateFactory.getInstance("X.509")

        val inputStream: InputStream = context.resources.openRawResource(R.raw.server_cert)
        val cert = certFactory.generateCertificate(inputStream) as X509Certificate
        inputStream.close()
        ks.setCertificateEntry("server_cert", cert)
        Timber.i("Loaded pinned certificate from raw resource")

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: throw IllegalStateException("No X509TrustManager found")

        Timber.i("Using pinned certificate trust manager")
        return trustManager
    }
}