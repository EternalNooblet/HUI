package com.hui.companion.util

import okhttp3.OkHttpClient
import timber.log.Timber
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for the companion server.
 *
 * Nothing server-specific is baked into the APK. The server's self-signed
 * cert is authenticated by its SHA-256 fingerprint, delivered out-of-band
 * in the QR code the user scans (stored in encrypted prefs via Config).
 * With no fingerprint stored, platform default trust applies (works for
 * servers with publicly trusted certs).
 */
object PinnedTls {

    fun normalize(fingerprint: String): String =
        fingerprint.replace(":", "").replace("-", "").replace(" ", "").uppercase()

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun trustManager(): X509TrustManager {
        val pinned = Config.certFingerprint ?: Config.loadCertFingerprint()
        if (pinned.isNullOrBlank()) {
            Timber.i("TLS: no pinned fingerprint, using platform trust")
            return defaultTrustManager()
        }
        val expected = normalize(pinned)
        val fallback = defaultTrustManager()
        Timber.i("TLS: pinning server cert fingerprint $expected")
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                fallback.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Empty certificate chain")
                val actual = fingerprintOf(leaf)
                if (actual != expected) {
                    throw CertificateException(
                        "Server cert fingerprint mismatch (expected $expected, got $actual)"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                fallback.acceptedIssuers
        }
    }

    /** With a pinned fingerprint the cert itself is the authentication, so
     *  any hostname is fine. Without one, use the platform verifier. */
    fun hostnameVerifier(): HostnameVerifier {
        val pinned = Config.certFingerprint ?: Config.loadCertFingerprint()
        return if (!pinned.isNullOrBlank()) {
            HostnameVerifier { _, _ -> true }
        } else {
            HttpsURLConnection.getDefaultHostnameVerifier()
        }
    }

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
