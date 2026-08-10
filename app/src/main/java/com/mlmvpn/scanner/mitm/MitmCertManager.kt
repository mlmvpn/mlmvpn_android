package com.mlmvpn.scanner.mitm

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Creates and tracks the per-device root certificate used by the MITM domain-fronting
 * profile (see [MitmProfile]).
 *
 * Why we generate on-device instead of shipping one: the private key that pairs with this
 * certificate can decrypt every TLS session of any device that trusts the certificate. A
 * certificate shipped inside the APK would hand that capability to anyone who unzips it --
 * i.e. every user could passively read every other user's banking/mail traffic. Upstream
 * (@patterniha) warns about exactly this twice in the README. So each install mints its own
 * key pair, it never leaves the device, and it is never uploaded anywhere.
 *
 * The certificate has to be *written in the format Xray itself produces* (`xray tls cert -ca`),
 * because the same core parses it back:
 *   - certificate: PEM `CERTIFICATE`
 *   - private key: PEM `RSA PRIVATE KEY` (PKCS#1)
 * Android's `KeyPairGenerator` hands us a PKCS#8 encoding, so [pkcs1FromPkcs8] unwraps it.
 * The AAR's string table contains "RSA PRIVATE KEY" but not "EC PRIVATE KEY", which is why
 * this is RSA-2048 and not an EC key.
 *
 * There is no BouncyCastle on the classpath, so the X.509 structure is assembled by hand as
 * DER below. It is a self-signed v3 CA with basicConstraints/keyUsage/subjectKeyIdentifier --
 * the minimum Android's certificate installer and Go's x509 verifier both require.
 */
object MitmCertManager {

    private const val PREFS = "mitm_cert_prefs"
    private const val KEY_CN = "cert_cn"
    private const val KEY_CREATED = "cert_created_at"

    const val CERT_FILE_NAME = "mlmvpn_mitm.crt"
    const val KEY_FILE_NAME = "mlmvpn_mitm.key"

    /** Validity window. UTCTime cannot express years past 2049, so 20 years it is. */
    private const val VALID_YEARS = 20

    fun certFile(context: Context): File = File(context.filesDir, CERT_FILE_NAME)
    fun keyFile(context: Context): File = File(context.filesDir, KEY_FILE_NAME)

    /** True when both files exist locally (says nothing about OS trust -- see [isTrusted]). */
    fun exists(context: Context): Boolean =
        certFile(context).let { it.exists() && it.length() > 0 } &&
            keyFile(context).let { it.exists() && it.length() > 0 }

    /** Common name of the generated certificate, so the UI can tell the user what to look for. */
    fun commonName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CN, null)

    fun createdAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CREATED, 0L)

    /** Generates the pair only if missing. Returns true when usable files are on disk. */
    fun ensure(context: Context): Boolean = if (exists(context)) true else generate(context)

    /** Deletes the local pair (the copy inside the OS trust store is the user's to remove). */
    fun deleteLocal(context: Context) {
        runCatching { certFile(context).delete() }
        runCatching { keyFile(context).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CN).remove(KEY_CREATED).apply()
    }

    /**
     * Mints a fresh self-signed root CA and writes both PEM files. Overwrites any existing pair.
     *
     * The CN embeds a short random suffix so two devices never produce visually identical
     * entries, and so a user who installs a second one can tell them apart in Android's
     * "trusted credentials" list.
     */
    fun generate(context: Context): Boolean = try {
        val suffix = BigInteger(24, java.security.SecureRandom()).toString(16).uppercase(Locale.US)
        val cn = "MLM VPN Local CA $suffix"

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = buildSelfSignedCa(cn, kp.public.encoded, kp.private)

        certFile(context).writeText(pem("CERTIFICATE", der))
        keyFile(context).writeText(pem("RSA PRIVATE KEY", pkcs1FromPkcs8(kp.private.encoded)))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CN, cn)
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .apply()
        true
    } catch (e: Exception) {
        com.mlmvpn.scanner.engines.gst.GstLog.e("MitmCert", "generate failed: ${e.message}")
        false
    }

    /**
     * Whether the OS currently trusts our certificate.
     *
     * The default `TrustManagerFactory` on API 24+ is backed by system *and* user-added CAs
     * (this app ships no `networkSecurityConfig`, so the platform default applies), which makes
     * the accepted-issuer list an accurate answer to "did the install go through?". Compared by
     * full encoded bytes rather than by subject name, so an unrelated CA with the same name
     * cannot produce a false positive.
     */
    fun isTrusted(context: Context): Boolean {
        return try {
            val ours = loadCert(context) ?: return false
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            tmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .any { tm -> tm.acceptedIssuers.any { it.encoded.contentEquals(ours.encoded) } }
        } catch (_: Exception) {
            false
        }
    }

    fun loadCert(context: Context): X509Certificate? = try {
        certFile(context).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    } catch (_: Exception) {
        null
    }

    /** SHA-256 fingerprint, colon-separated -- shown in the UI so the user can verify the entry. */
    fun fingerprint(context: Context): String? = loadCert(context)?.let { c ->
        MessageDigest.getInstance("SHA-256").digest(c.encoded)
            .joinToString(":") { "%02X".format(it) }
    }

    /** Name the certificate is saved under in Downloads -- recognisable in a file picker. */
    const val EXPORT_FILE_NAME = "MLM-VPN-Certificate.crt"

    /**
     * True when the platform still lets an app hand a CA certificate to the system installer.
     *
     * Android 11 (API 30) closed that door: `KeyChain.createInstallIntent()` with
     * `EXTRA_CERTIFICATE` now just shows "CA certificates could not be installed -- this
     * certificate from null must be installed in Settings". So on API 30+ the only working route
     * is the user picking a file from Settings themselves, which is why [exportToDownloads]
     * exists. Testing the version instead of trying and reading the dialog, because the failure
     * is a message inside the system UI that we cannot observe.
     */
    val canUseDirectInstaller: Boolean
        get() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R

    /**
     * Intent that opens Android's own "install a CA certificate" dialog with our certificate
     * already loaded. Only meaningful below API 30 -- see [canUseDirectInstaller].
     *
     * Even there, installing a trust anchor is gated behind a system-owned confirmation screen
     * on every non-rooted device, by design. No app can do it silently.
     */
    fun installIntent(context: Context): Intent? = try {
        val cert = certFile(context)
        if (!cert.exists() || !canUseDirectInstaller) null
        else KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, cert.readBytes())
            putExtra(KeyChain.EXTRA_NAME, commonName(context) ?: "MLM VPN Local CA")
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Copies the certificate into the shared Downloads folder so the user can select it from
     * Settings' "install a certificate" file picker -- the only route left on API 30+.
     *
     * Uses MediaStore on API 29+, which needs no storage permission. Any previous copy under the
     * same name is replaced first, so regenerating the certificate cannot leave the user
     * choosing between two identically named files. Returns the visible file name on success.
     */
    fun exportToDownloads(context: Context): String? {
      return try {
        val bytes = certFile(context).readBytes()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // Drop a stale copy from an earlier run/regeneration.
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(EXPORT_FILE_NAME),
                    null
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) {
                        resolver.delete(
                            android.content.ContentUris.withAppendedId(collection, c.getLong(idCol)),
                            null, null
                        )
                    }
                }
            }

            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_FILE_NAME)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        } else {
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, EXPORT_FILE_NAME).writeBytes(bytes)
        }
        EXPORT_FILE_NAME
      } catch (e: Exception) {
        com.mlmvpn.scanner.engines.gst.GstLog.e("MitmCert", "export failed: ${e.message}")
        null
      }
    }

    /**
     * Best available deep link to where a CA certificate can be installed.
     *
     * There is no public action that lands directly on "Install a certificate" on every OEM, so
     * this tries the security-settings screen and falls back to the top-level settings app. The
     * wizard text carries the remaining taps.
     */
    fun securitySettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun allSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------------------------------------------------------- X.509 / DER

    private fun buildSelfSignedCa(cn: String, spki: ByteArray, privateKey: java.security.PrivateKey): ByteArray {
        val algId = seq(oid(OID_SHA256_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(
            rdn(OID_CN, cn),
            rdn(OID_O, "MLM VPN")
        )

        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, -1) // tolerate a device clock that is slightly behind
        }
        val until = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.YEAR, VALID_YEARS)
        }

        // Positive serial: strip nothing, but prepend 0x00 when the top bit is set.
        var serial = BigInteger(64, java.security.SecureRandom()).toByteArray()
        if (serial.isEmpty()) serial = byteArrayOf(1)
        if (serial[0].toInt() and 0x80 != 0) serial = byteArrayOf(0) + serial

        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // v3
            tlv(0x02, serial),
            algId,
            name,                                 // issuer == subject (self-signed)
            seq(utcTime(now), utcTime(until)),
            name,
            spki,                                 // already a DER SubjectPublicKeyInfo
            tlv(0xA3, seq(extBasicConstraints(), extKeyUsage(), extSubjectKeyId(spki)))
        )

        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        return seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
    }

    /** critical basicConstraints = CA:TRUE (no pathLen limit). */
    private fun extBasicConstraints(): ByteArray =
        seq(oid(OID_BASIC_CONSTRAINTS), bool(true), tlv(0x04, seq(bool(true))))

    /** critical keyUsage = digitalSignature | keyCertSign | cRLSign. */
    private fun extKeyUsage(): ByteArray =
        seq(oid(OID_KEY_USAGE), bool(true), tlv(0x04, tlv(0x03, byteArrayOf(1, 0x86.toByte()))))

    /** subjectKeyIdentifier = SHA-1 of the public key BIT STRING contents (RFC 5280 method 1). */
    private fun extSubjectKeyId(spki: ByteArray): ByteArray {
        val keyBits = publicKeyBits(spki)
        val id = MessageDigest.getInstance("SHA-1").digest(keyBits)
        return seq(oid(OID_SUBJECT_KEY_ID), tlv(0x04, tlv(0x04, id)))
    }

    /** Pulls the raw key bits out of a SubjectPublicKeyInfo: SEQUENCE { AlgId, BIT STRING }. */
    private fun publicKeyBits(spki: ByteArray): ByteArray {
        val r = DerReader(spki)
        val body = r.readTlv(0x30)
        val inner = DerReader(body)
        inner.readTlv(0x30)                       // AlgorithmIdentifier
        val bits = inner.readTlv(0x03)
        return bits.copyOfRange(1, bits.size)     // drop the unused-bits byte
    }

    /**
     * PKCS#8 PrivateKeyInfo -> the PKCS#1 RSAPrivateKey it wraps.
     * SEQUENCE { INTEGER version, SEQUENCE AlgId, OCTET STRING privateKey }
     */
    private fun pkcs1FromPkcs8(pkcs8: ByteArray): ByteArray {
        val r = DerReader(pkcs8)
        val inner = DerReader(r.readTlv(0x30))
        inner.readTlv(0x02)   // version
        inner.readTlv(0x30)   // algorithm identifier
        return inner.readTlv(0x04)
    }

    private fun rdn(oidBytes: ByteArray, value: String): ByteArray =
        tlv(0x31, seq(oid(oidBytes), tlv(0x0C, value.toByteArray(Charsets.UTF_8))))

    private fun utcTime(c: Calendar): ByteArray {
        val s = String.format(
            Locale.US, "%02d%02d%02d%02d%02d%02dZ",
            c.get(Calendar.YEAR) % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
        return tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    }

    private fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    private fun oid(content: ByteArray) = tlv(0x06, content)
    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(*parts))

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val len = when {
            body.size < 0x80 -> byteArrayOf(body.size.toByte())
            body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            body.size < 0x10000 -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            else -> byteArrayOf(
                0x83.toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()
            )
        }
        return byteArrayOf(tag.toByte()) + len + body
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Sequential DER walker; only what the two unwrap helpers above need. */
    private class DerReader(private val buf: ByteArray) {
        private var pos = 0
        fun readTlv(expectedTag: Int): ByteArray {
            val tag = buf[pos++].toInt() and 0xFF
            require(tag == expectedTag) { "DER tag mismatch: got $tag, want $expectedTag" }
            var len = buf[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (buf[pos++].toInt() and 0xFF) }
            }
            val body = buf.copyOfRange(pos, pos + len)
            pos += len
            return body
        }
    }

    private fun pem(label: String, der: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END ").append(label).append("-----\n")
        }
    }

    private val OID_SHA256_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 1, 1, 11)
    private val OID_CN = byteArrayOf(0x55, 0x04, 0x03)
    private val OID_O = byteArrayOf(0x55, 0x04, 0x0A)
    private val OID_KEY_USAGE = byteArrayOf(0x55, 0x1D, 0x0F)
    private val OID_SUBJECT_KEY_ID = byteArrayOf(0x55, 0x1D, 0x0E)
    private val OID_BASIC_CONSTRAINTS = byteArrayOf(0x55, 0x1D, 0x13)
}
