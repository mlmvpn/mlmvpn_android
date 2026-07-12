package com.mlmvpn.scanner.engines.game

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Client for OUR OWN UAE Dedicated DNS resolver (uae-dns.service on the UAE server, HTTPS :8443).
 *
 * Same job/output as [DedicatedDnsResolver] (resolve a game hostname to A-record IPs) but against a
 * server we run ourselves -- free, unlimited, UAE-geo-steered. The server uses a self-signed cert
 * (no domain, so no public CA), which we secure by PINNING its public key here: only a cert whose
 * SubjectPublicKeyInfo matches [PINNED_SPKI_SHA256] is accepted, so DPI/MITM can't substitute one.
 * Trust is bound to the key, not the hostname, hence the permissive hostname verifier.
 *
 * If the server's key.pem is ever regenerated, recompute the pin with:
 *   openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | openssl enc -base64
 */
object UaeDnsResolver {

    private const val TAG = "UaeDnsResolver"
    const val BASE = "https://194.50.233.133:8443"

    // SHA-256 of the server cert's SubjectPublicKeyInfo (base64). Pins the KEY, so rotating the
    // cert with the same key keeps working. MUST match /opt/uae-dns/cert.pem on the server.
    private const val PINNED_SPKI_SHA256 = "zS/5yEIhnnvcUErhM+jERSJcCTI3gf+rUXEXQ3OSmkQ="

    private val pinnedTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("UAE DNS: no cert presented")
            val spki = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            val b64 = Base64.encodeToString(spki, Base64.NO_WRAP)
            if (b64 != PINNED_SPKI_SHA256) {
                throw CertificateException("UAE DNS: cert public-key pin mismatch")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val client by lazy {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(pinnedTrustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, pinnedTrustManager)
            .hostnameVerifier { _, _ -> true } // trust is pinned to the key, not the hostname
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One GET to the UAE resolver's /resolve endpoint. Returns the A-record IPs for [hostname], or
     * empty on any failure (pin mismatch, timeout, server down). [region] is accepted for parity
     * with the Cloudflare worker API; the UAE server currently answers from its own vantage point.
     */
    suspend fun resolve(hostname: String, region: String = "AE"): List<String> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/resolve?domain=$hostname&region=$region"
            try {
                val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrEmpty()) return@withContext emptyList()
                val answers = JSONObject(body).optJSONArray("Answer") ?: return@withContext emptyList()
                val ips = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") == 1) ips.add(a.getString("data"))
                }
                ips
            } catch (e: Exception) {
                // SECURITY: don't log e.message -- it embeds the UAE server IP:port. Leaking it to
                // logcat would let the address be discovered and filtered.
                Log.d(TAG, "resolve($hostname) failed (${e.javaClass.simpleName})")
                emptyList()
            }
        }
}
