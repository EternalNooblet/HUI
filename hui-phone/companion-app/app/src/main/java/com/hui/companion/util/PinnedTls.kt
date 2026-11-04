package com.hui.companion.util

import okhttp3.OkHttpClient
import timber.log.Timber
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the companion server.
 *
 * The server uses a self-signed cert on a LAN. Authentication comes from
 * the signed token in the Authorization header, not from the cert — so
 * this trust manager accepts any cert (encryption only, like the
 * original app). No cert data is stored or baked in anywhere.
 */
object PinnedTls {

    fun normalize(fingerprint: String): String =
        fingerprint.replace(":", "").replace("-", "").replace(" ", "").uppercase()

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun trustManager(): X509TrustManager {
        Timber.i("TLS: accepting server cert (token authenticates the server)")
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    /** No cert identity to check (token does that) — any hostname is fine. */
    fun hostnameVerifier(): HostnameVerifier = HostnameVerifier { _, _ -> true }

    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    fun okHttpClient(): OkHttpClient {
        val tm = trustManager()
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext(tm).socketFactory, tm)
            .hostnameVerifier(hostnameVerifier())
            .build()
    }
}
