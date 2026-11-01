package com.mlmvpn.core.tunnel

import android.content.Context
import com.mlmvpn.scanner.store.tr
import org.json.JSONObject
import java.io.File

/**
 * «کاندوئیت شخصی»: a friend's private Conduit station, from its pairing link.
 *
 * Conduit (Psiphon's volunteer-proxy app) can share a PRIVATE station: «Personal Pairing» hands out
 * `psiphon://pair/<token>` (or `https://…/pair/<token>`), the token being base64url of
 * `{"v":"1","data":{"id":"<43-char base64 compartment id>","name":"<station>"}}` (Psiphon-Inc/conduit
 * src/pairing/token.ts). The id goes into `InproxyClientPersonalCompartmentID`, and from then on the
 * core uses ONLY in-proxy, ONLY through stations holding that id.
 *
 * The same parser as the desktop's (psiphon-manager.js › parsePairingLink), so a link that pairs
 * one pairs the other.
 */
object PsiphonPairing {

    data class Pairing(val id: String, val name: String, val addedAt: Long = 0L)

    /** A link that is not a pairing link; the message is a sentence for the user. */
    class PairingError(message: String) : Exception(message)

    private const val FILE = "psiphon/pairing.json"
    private val COMPARTMENT = Regex("^[A-Za-z0-9+/]{43}$")
    private val LINK = Regex("""(?:^psiphon://pair/|/pair/)([A-Za-z0-9_-]+)""")
    private val BARE_TOKEN = Regex("^([A-Za-z0-9_-]{40,})$")

    /** A link, a bare token or a bare compartment id → the pairing, or [PairingError] in the user's language. */
    fun parse(text: String): Pairing {
        val raw = text.trim()
        if (raw.isEmpty()) throw PairingError(tr("لینک خالی است.", "The link is empty."))
        if (COMPARTMENT.matches(raw) && decode(raw)?.size == 32) return Pairing(raw, "")
        val token = LINK.find(raw)?.groupValues?.get(1) ?: BARE_TOKEN.find(raw)?.groupValues?.get(1)
            ?: throw PairingError(tr("این لینک جفت‌سازی کاندوئیت نیست — باید با psiphon://pair/ شروع شود.",
                "This is not a Conduit pairing link -- it starts with psiphon://pair/."))
        val payload = runCatching { JSONObject(String(decode(token) ?: error("base64"), Charsets.UTF_8)) }.getOrNull()
            ?: throw PairingError(tr("لینک ناقص است — کامل کپی‌اش کنید.", "The link is incomplete -- copy all of it."))
        val data = payload.optJSONObject("data")
        val id = data?.optString("id").orEmpty().trim()
        if (payload.optString("v") != "1" || !COMPARTMENT.matches(id) || decode(id)?.size != 32) {
            throw PairingError(tr("نسخه یا شناسهٔ این لینک شناخته‌شده نیست.", "This link's version or id is not one this app knows."))
        }
        return Pairing(id, data?.optString("name").orEmpty().take(60))
    }

    fun read(ctx: Context): Pairing? = runCatching {
        val o = JSONObject(file(ctx).readText())
        val id = o.optString("id")
        if (COMPARTMENT.matches(id)) Pairing(id, o.optString("name"), o.optLong("addedAt")) else null
    }.getOrNull()

    /** Parse and keep. Throws [PairingError] and keeps nothing when the link is not one. */
    fun save(ctx: Context, text: String): Pairing {
        val p = parse(text).copy(addedAt = System.currentTimeMillis())
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("id", p.id).put("name", p.name).put("addedAt", p.addedAt).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return p
    }

    fun clear(ctx: Context) { file(ctx).delete() }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /**
     * Standard or URL-safe base64, padded or not. Written out rather than taken from
     * android.util.Base64 (a stub under unit tests) or java.util.Base64 (API 26; this app runs on 24).
     */
    internal fun decode(s: String): ByteArray? {
        val clean = s.trimEnd('=').replace('-', '+').replace('_', '/')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in clean) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
