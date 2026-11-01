package com.mlmvpn.core.urnetwork

import com.mlmvpn.scanner.utils.killHard
import com.mlmvpn.scanner.utils.waitUpTo
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.mlmvpn.core.tunnel.SecureStore
import com.mlmvpn.scanner.store.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * «URnetwork»: URnetwork's own network of provider devices, on URnetwork's own SDK
 * (github.com/urnetwork/sdk, MPL-2.0), built as a core of its own -- liburnetwork.so, arm64, the
 * source in third_party/urnetwork-core -- and run as a child process, never in the app's process
 * (a Go crash ends the core, not the app; and no gomobile clash with libv2ray's AAR).
 *
 * One account, made only when the user accepts URnetwork's terms on the screen: an instant one,
 * with no email or password and a recovery phrase. Its network token is a file every core reads
 * ([accountFile]) and a sealed copy in [SecureStore] (the backup's, and the file's source after a
 * restore); the phrase is sealed only.
 *
 * Each job -- the tile, MAE, Scout -- runs its core in a folder of its own and is a device of its
 * own in the account, so none ever takes another's place. The tile's core carries the VPN's TUN on
 * the SDK's own packet path (the official apps' path); MAE and Scout use its local SOCKS port.
 */
object UrnetworkEngine {

    private const val TAG = "Urnetwork"
    const val CORE_FILE = "liburnetwork.so"
    private const val SEED_KEY = "urnetwork_seed"
    private const val JWT_KEY = "urnetwork_jwt"

    /** The core is built for arm64 only (Go cannot build android/arm without cgo). */
    fun supported(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    fun core(context: Context): File = File(context.applicationInfo.nativeLibraryDir, CORE_FILE)

    fun available(context: Context): Boolean = supported() && core(context).isFile

    fun base(context: Context): File = File(context.applicationContext.filesDir, "urnetwork")

    /** The account's network token, as the cores read it. */
    fun accountFile(context: Context): File = File(base(context), "account/jwt")

    /** A job's own folder: its device's sign-in and settings, kept by the SDK. */
    fun roleDir(context: Context, role: String): File = File(base(context), role)

    /** The 1.2.42 builds before this one kept the token in the tile's folder. */
    private fun legacyToken(context: Context): File = File(roleDir(context, "tile"), "jwt")

    /**
     * Whether there is an account on this phone. Cheap enough for the main thread: no Keystore
     * call, only whether a sealed value or a token file is there.
     */
    fun hasAccount(context: Context): Boolean =
        accountFile(context).length() > 0 || SecureStore.hasSecret(context, JWT_KEY) || legacyToken(context).length() > 0

    /** The recovery phrase URnetwork gave when the account was made, or null. Off the main thread. */
    fun seed(context: Context): String? = SecureStore.getSecret(context, SEED_KEY).ifBlank { null }

    /** Why URnetwork cannot connect here, in the user's language; null when it can. */
    fun unavailableReason(context: Context): String? = when {
        !supported() -> tr(
            "URnetwork روی این گوشی اجرا نمی‌شود: هسته‌اش فقط برای گوشی‌های ۶۴ بیتی (arm64) ساخته شده است.",
            "URnetwork does not run on this phone: its core is built for 64-bit (arm64) phones only.",
        )
        !available(context) -> tr("هستهٔ URnetwork در این نسخهٔ برنامه نیست.", "URnetwork's core is not in this build.")
        !hasAccount(context) -> tr(
            "اول حساب URnetwork را بسازید: صفحهٔ URnetwork را باز کنید، قوانین را بپذیرید و «ساخت حساب کاربری» را بزنید.",
            "Make the URnetwork account first: open URnetwork's page, accept the terms and tap «Create account».",
        )
        else -> null
    }

    // ── the account ───────────────────────────────────────────────────────────────────────────

    /**
     * Brings the token file and its sealed copy into line, before a core reads the file. The file
     * wins when it is the later token (the SDK renews it there); the sealed copy fills a missing
     * file (a backup restored onto this phone). Off the main thread: it may open the Keystore.
     */
    fun syncAccount(context: Context) {
        val file = accountFile(context)
        val legacy = legacyToken(context)
        if (file.length() == 0L && legacy.length() > 0) {
            runCatching { write(file, legacy.readText().trim()) }
        }
        val onDisk = runCatching { file.readText().trim() }.getOrDefault("")
        val sealed = SecureStore.getSecret(context, JWT_KEY)
        when {
            onDisk.isEmpty() && sealed.isNotEmpty() -> write(file, sealed)
            onDisk.isNotEmpty() && sealed.isEmpty() -> SecureStore.putSecret(context, JWT_KEY, onDisk)
            onDisk.isNotEmpty() && onDisk != sealed -> {
                val sameNetwork = claim(onDisk, "network_id") == claim(sealed, "network_id")
                if (sameNetwork && expiry(sealed) > expiry(onDisk)) write(file, sealed)
                else SecureStore.putSecret(context, JWT_KEY, onDisk)
            }
        }
        if (legacy.exists() && file.length() > 0) legacy.delete()
    }

    private fun write(file: File, token: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(token)
        tmp.renameTo(file)
    }

    /** One claim of a token, unverified -- only to compare two tokens this phone already holds. */
    private fun claim(token: String, name: String): String =
        payload(token)?.optString(name).orEmpty()

    private fun expiry(token: String): Long = payload(token)?.optLong("exp", 0L) ?: 0L

    private fun payload(token: String): JSONObject? = runCatching {
        val part = token.split('.').getOrNull(1) ?: return null
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    }.getOrNull()

    /** The account's name in URnetwork ("network name"), when the token carries it. */
    fun accountName(context: Context): String? =
        runCatching { accountFile(context).readText().trim() }.getOrNull()?.let { claim(it, "network_name") }?.ifBlank { null }

    /**
     * Makes the instant account. Call only after the user accepted URnetwork's terms on the screen.
     * Blocking (up to a minute and a half). Returns null on success, else the reason.
     */
    fun createAccount(context: Context): String? {
        val out = runOnce(context, listOf("-create"), stdin = null, timeoutS = 100)
        val token = runCatching { accountFile(context).readText().trim() }.getOrDefault("")
        if (token.isEmpty()) {
            log("account not made: exit ${out.code}, ${out.error}")
            return out.error?.let { readable(it) } ?: tr("حساب ساخته نشد.", "The account was not made.")
        }
        SecureStore.putSecret(context, JWT_KEY, token)
        out.lines.firstOrNull { it.startsWith("seed ") }?.removePrefix("seed ")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { SecureStore.putSecret(context, SEED_KEY, it) }
        resetUsage(context)
        log("account made")
        return null
    }

    /**
     * Signs in with the recovery phrase of an account made before, in place of the one on this
     * phone. Every job notices the other account at its next start and registers again.
     */
    fun restoreAccount(context: Context, phrase: String): String? {
        val words = phrase.trim().split(Regex("\\s+")).joinToString(" ")
        if (words.isBlank()) return tr("عبارت بازیابی خالی است.", "The recovery phrase is empty.")
        val before = runCatching { accountFile(context).readText() }.getOrNull()
        // Written by the core only on success: a refusal leaves the current account as it was.
        val out = runOnce(context, listOf("-restore"), stdin = words, timeoutS = 100)
        val token = runCatching { accountFile(context).readText().trim() }.getOrDefault("")
        if (out.code != 0 || token.isEmpty() || token == before?.trim()) {
            return out.error?.let { readable(it) } ?: tr("این عبارت بازیابی پذیرفته نشد.", "The recovery phrase was not accepted.")
        }
        SecureStore.putSecret(context, JWT_KEY, token)
        SecureStore.putSecret(context, SEED_KEY, words)
        resetUsage(context)
        log("account restored")
        return null
    }

    /**
     * Signs the account in again with the recovery phrase kept on this phone, after URnetwork refused
     * its sign-in (a token that expired unused, a session the server ended). The same account, so
     * every job keeps its folder; a job whose own device was refused registers a new one. True when
     * a new token is in place.
     */
    fun reauth(context: Context): Boolean {
        synchronized(accountLock) {
            val words = seed(context)
            if (words == null) {
                log("sign-in refused, and no recovery phrase on this phone to sign in again")
                return false
            }
            val before = runCatching { accountFile(context).readText().trim() }.getOrNull()
            val out = runOnce(context, listOf("-restore"), stdin = words, timeoutS = 100)
            val token = runCatching { accountFile(context).readText().trim() }.getOrDefault("")
            if (out.code != 0 || token.isEmpty() || token == before) {
                log("signing in again failed: exit ${out.code}, ${out.error}")
                return false
            }
            SecureStore.putSecret(context, JWT_KEY, token)
            log("signed in again with the recovery phrase")
            return true
        }
    }

    /**
     * After a core ended on a refused sign-in: whether starting it again can work. The account's
     * token refused at registration means signing in again first ([reauth]); a refused device of
     * its own was already cleared by the SDK, and the next start registers a new one.
     */
    fun recover(context: Context, proc: UrnetworkProcess): Boolean =
        if (proc.accountRefused) reauth(context) else true

    sealed class Api {
        data class Ok(val json: JSONObject) : Api()
        /** The account's sign-in was refused, and signing in again with the recovery phrase failed. */
        object SignedOut : Api()
        data class Failed(val reason: String) : Api()
    }

    /** One read-only call with the account's token: "balance" or "locations". Blocking. */
    fun api(context: Context, what: String): Api = api(context, what, retried = false)

    private fun api(context: Context, what: String, retried: Boolean): Api {
        syncAccount(context)
        val out = File(context.cacheDir, "urnetwork-$what-${SystemClock.elapsedRealtimeNanos()}.json")
        try {
            val r = runOnce(context, listOf("-api", what, "-out", out.absolutePath), stdin = null, timeoutS = 75)
            if (r.code == EXIT_LOGGED_OUT) {
                return if (!retried && reauth(context)) api(context, what, retried = true) else Api.SignedOut
            }
            if (r.lines.any { it.startsWith("account renewed") }) syncAccount(context)
            if (r.code != 0 || !out.isFile) return Api.Failed(r.error?.let { readable(it) } ?: tr("پاسخی نیامد.", "No answer."))
            return Api.Ok(JSONObject(out.readText()))
        } catch (e: Exception) {
            return Api.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            out.delete()
        }
    }

    private class Once(val code: Int, val lines: List<String>, val error: String?)

    /**
     * The account's short jobs run one at a time: a token is renewed or replaced by one of them
     * only, never by two at once with the same old token.
     */
    private val accountLock = Any()

    /** The mobile network's country, as the official apps report it to the SDK: only on mobile data. */
    internal fun networkCountry(context: Context): String = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val cellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        UrnetworkHost.countryFor(cellular, if (cellular) context.getSystemService(android.telephony.TelephonyManager::class.java)?.networkCountryIso else null)
    }.getOrDefault("")

    /** One short job of the core in the "api" folder: create, restore, a call. */
    private fun runOnce(context: Context, args: List<String>, stdin: String?, timeoutS: Long): Once {
        synchronized(accountLock) { return runOnceLocked(context, args, stdin, timeoutS) }
    }

    private fun runOnceLocked(context: Context, args: List<String>, stdin: String?, timeoutS: Long): Once {
        if (!available(context)) return Once(-1, emptyList(), unavailableReason(context))
        val dir = roleDir(context, "api").apply { mkdirs() }
        val lines = ArrayList<String>()
        var error: String? = null
        return try {
            val p = ProcessBuilder(
                listOf(core(context).absolutePath, "-state", dir.absolutePath, "-account", accountFile(context).absolutePath,
                    "-dns", com.mlmvpn.core.lantern.LanternEngine.systemDns(context).joinToString(","),
                    "-country", networkCountry(context)) + args,
            ).directory(dir).redirectErrorStream(true).also { env(it, context, dir) }.start()
            p.outputStream.use { o -> if (stdin != null) { o.write((stdin + "\n").toByteArray()); o.flush() } }
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).useLines { all ->
                        all.forEach { raw ->
                            val line = raw.trim()
                            if (!line.startsWith("[ur] ")) return@forEach
                            val ev = line.removePrefix("[ur] ")
                            synchronized(lines) { lines += ev }
                            if (ev.startsWith("error ")) error = ev.removePrefix("error ")
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!p.waitUpTo(timeoutS, TimeUnit.SECONDS)) {
                p.killHard()
                return Once(-1, lines, tr("URnetwork در زمان مقرر جواب نداد.", "URnetwork did not answer in time."))
            }
            reader.join(2_000)
            Once(p.exitValue(), synchronized(lines) { lines.toList() }, error)
        } catch (e: Exception) {
            Once(-1, lines, e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun env(pb: ProcessBuilder, context: Context, dir: File) {
        val tmp = File(context.cacheDir, "urnetwork-tmp").apply { mkdirs() }
        pb.environment().apply {
            put("TMPDIR", tmp.absolutePath)
            put("HOME", dir.absolutePath)
        }
    }

    /** The core's own words for a failure, put in the user's language where they are known. */
    fun readable(error: String): String {
        val e = error.lowercase()
        return when {
            e.contains("no account") -> tr("حساب URnetwork روی این گوشی نیست.", "There is no URnetwork account on this phone.")
            e.startsWith("auth") || e.contains("401") || e.contains("403") ->
                tr("URnetwork ورود این حساب را دیگر نمی‌پذیرد؛ با عبارت بازیابی دوباره وارد شوید.", "URnetwork no longer accepts this account's sign-in; sign in again with the recovery phrase.")
            e.contains("client_limit") -> tr("حساب به سقف تعداد دستگاه‌هایش رسیده است.", "The account has reached its limit of devices.")
            e.contains("upgrade_required") -> tr("طرح رایگان این حساب اجازهٔ دستگاه دیگری نمی‌دهد.", "This account's free plan allows no more devices.")
            e.contains("no answer") || e.contains("timeout") || e.contains("deadline") ->
                tr("سرور URnetwork از اینترنت شما جواب نداد.", "URnetwork's server did not answer from your internet.")
            else -> error
        }
    }

    fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /**
     * What the routing rules depend on, cheap to read: they are written again only when it moves
     * (the monitor asks every few seconds, so a switch thrown in Settings mid-session applies).
     */
    fun rulesKey(context: Context): String {
        val iran = com.mlmvpn.core.tunnel.SmartIranRoute.enabled(context)
        val stamp = if (iran) com.mlmvpn.core.tunnel.SmartIranLists.cidrFile(context).lastModified() else 0L
        val prefs = com.mlmvpn.core.tunnel.TunnelPreferences(context)
        return "$iran|$stamp|${prefs.routeDirect}|${prefs.routeBlock}"
    }

    /**
     * The routing rules as the core reads them, applied by URnetwork's own SDK (its per-host
     * routing overrides) on the packet path and by the core's local proxy:
     *
     *  - `local`: leaves on the phone's own connection and never reaches a provider -- with
     *    «مسیر هوشمند ایران» on, every Iranian range of the daily list (IPv4 and IPv6, all of them,
     *    not only the large ones the VPN builder can take out) and every `.ir` name; and the
     *    transport's own «outside the tunnel» list;
     *  - `block`: the transport's «blocked» list.
     *
     * Null when there is no rule at all.
     */
    fun rulesFile(context: Context): File? = runCatching {
        val sb = StringBuilder()
        if (com.mlmvpn.core.tunnel.SmartIranRoute.enabled(context)) {
            com.mlmvpn.core.tunnel.SmartIranLists.ensureSeeded(context)
            val f = com.mlmvpn.core.tunnel.SmartIranLists.cidrFile(context)
            val text = if (f.exists() && f.length() > 0) f.readText()
                else context.assets.open(com.mlmvpn.core.tunnel.SmartIranLists.SEED_CIDR).bufferedReader().use { it.readText() }
            val (v4, v6) = com.mlmvpn.core.tunnel.IranCidr.parse(text)
            sb.append("local **.ir\n")
            v4.forEach { sb.append("local ").append(com.mlmvpn.core.tunnel.IranCidr.text(it.start)).append('/').append(it.prefix).append('\n') }
            v6.forEach { sb.append("local ").append(it.address).append('/').append(it.prefix).append('\n') }
        }
        val prefs = com.mlmvpn.core.tunnel.TunnelPreferences(context)
        routeEntries(prefs.routeDirect).forEach { sb.append("local ").append(it).append('\n') }
        routeEntries(prefs.routeBlock).forEach { sb.append("block ").append(it).append('\n') }
        if (sb.isEmpty()) null else File(base(context).apply { mkdirs() }, "rules.txt").apply { writeText(sb.toString()) }
    }.getOrNull()

    /**
     * One of the transport's per-destination lists ("example.com, *.bank.ir, 1.2.3.0/24") in the
     * SDK's patterns: an address or a range as it is; a name, and `*.name`, as the name with
     * everything under it (`**.name`) -- what someone who types a site's name means.
     */
    internal fun routeEntries(list: String): List<String> =
        list.split(',', ';', '\n', ' ', '\t').map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.mapNotNull { e ->
            when {
                e.contains('/') || e.all { it.isDigit() || it == '.' } || e.contains(':') -> e
                e.startsWith("**.") -> e.takeIf { it.length > 3 }
                e.startsWith("*.") -> e.removePrefix("*.").takeIf { it.isNotEmpty() }?.let { "**.$it" }
                e.startsWith(".") -> e.removePrefix(".").takeIf { it.isNotEmpty() }?.let { "**.$it" }
                e.any { it == '*' } -> null
                else -> "**.$e"
            }
        }.distinct()

    // ── the tile's session, as the screen shows it ─────────────────────────────────────────────

    enum class Phase { IDLE, STARTING, REGISTERING, FINDING, TESTING, TUNNEL, CONNECTED, RECOVERING, FAILED }

    data class Window(
        val target: Int = 0,
        val added: Int = 0,
        val evaluating: Int = 0,
        val evalFailed: Int = 0,
        val notAdded: Int = 0,
        val removed: Int = 0,
        val minSatisfied: Boolean = false,
        val stall: String = "",
        val failed: Boolean = false,
        val ipv6: Boolean = false,
    )

    data class Country(val code: String, val name: String, val count: Int)

    data class Live(
        val phase: Phase = Phase.IDLE,
        val window: Window = Window(),
        val providers: Int = 0,
        val countries: List<Country> = emptyList(),
        val insufficient: Boolean = false,
        val limitUntil: Long = 0L,
        val blocker: Boolean = false,
        val blocked: Long = 0L,
        val location: String? = null,
        val log: List<String> = emptyList(),
        val error: String? = null,
        val since: Long = 0L,
        /** The furthest step this session reached, so a failure shows where it stopped. */
        val reached: Phase = Phase.IDLE,
        /** This session registered the phone in the account (its first connect, or after a sign-in change). */
        val registering: Boolean = false,
        /** Why the core is in the SDK's power-saving mode now (see UrnetworkHost); empty when it is not. */
        val saving: List<String> = emptyList(),
        /** The last data check failed and the server connection is being redialed (see [recoverTransports]). */
        val stalled: Boolean = false,
    ) {
        val running: Boolean get() = phase != Phase.IDLE && phase != Phase.FAILED
    }

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    /** When [recoverTransports] last redialed the server connection (0 = not this session). */
    @Volatile private var lastRedial = 0L
    private const val REDIAL_EVERY_MS = 180_000L

    fun beginSession(location: String?, blocker: Boolean) {
        lastRedial = 0L
        _live.value = Live(phase = Phase.STARTING, location = location, blocker = blocker, since = SystemClock.elapsedRealtime(), reached = Phase.STARTING)
        record(tr("راه‌اندازی هستهٔ URnetwork", "Starting URnetwork's core"))
    }

    fun setPhase(phase: Phase) = _live.update {
        it.copy(
            phase = phase,
            error = if (phase == Phase.FAILED) it.error else null,
            reached = if (phase.ordinal > it.reached.ordinal && phase != Phase.FAILED && phase != Phase.RECOVERING) phase else it.reached,
            registering = it.registering || phase == Phase.REGISTERING,
        )
    }

    fun setError(message: String) = _live.update { it.copy(phase = Phase.FAILED, error = message) }

    fun endSession() = _live.update { it.copy(phase = Phase.IDLE) }

    fun record(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _live.update { it.copy(log = (it.log + "$stamp  $line").takeLast(60)) }
        log(line)
    }

    /** A core's event, into the tile's live state ([forTile]) and the log line it deserves. */
    fun onTileEvent(kind: String, ev: JSONObject) {
        when (kind) {
            "stage" -> when (ev.optString("s")) {
                "register" -> { setPhase(Phase.REGISTERING); record(tr("ثبت این گوشی در حساب URnetwork", "Registering this phone in the URnetwork account")) }
                "registered" -> record(tr("گوشی در حساب ثبت شد", "The phone is registered in the account"))
                "account-switch" -> record(tr("حساب عوض شده — این گوشی در حساب تازه ثبت می‌شود", "The account changed -- registering this phone in the new one"))
                "device" -> record(tr("دستگاه URnetwork آماده شد", "URnetwork's device is ready") +
                    (if (ev.optBoolean("local")) tr(" · مسیر مستقیم (ایران هوشمند / بیرون از تونل) روشن", " · direct routes (smart Iran / outside the tunnel) on") else "") +
                    (if (ev.optBoolean("block")) tr(" · فهرست مسدود روشن", " · blocked list on") else ""))
                "connect" -> {
                    val loc = ev.optJSONObject("location")
                    val where = if (loc == null || loc.optBoolean("best")) tr("بهترین مکان در دسترس", "the best available location")
                        else loc.optString("name").ifBlank { loc.optString("cc").uppercase() }
                    setPhase(if (_live.value.phase == Phase.CONNECTED) Phase.RECOVERING else Phase.FINDING)
                    record(tr("یافتن ارائه‌دهنده‌ها در $where", "Finding providers in $where"))
                }
            }
            "window" -> {
                val w = Window(
                    target = ev.optInt("target"), added = ev.optInt("added"), evaluating = ev.optInt("eval"),
                    evalFailed = ev.optInt("evalFailed"), notAdded = ev.optInt("notAdded"), removed = ev.optInt("removed"),
                    minSatisfied = ev.optBoolean("min"), stall = ev.optString("stall"), failed = ev.optBoolean("failed"),
                    ipv6 = ev.optBoolean("v6"),
                )
                val before = _live.value.window
                _live.update { it.copy(window = w) }
                if (w.added != before.added && w.added > 0 && before.added == 0) {
                    record(tr("اولین ارائه‌دهنده وصل شد", "The first provider connected"))
                }
                if (w.failed && !before.failed) record(tr("هنوز هیچ ارائه‌دهنده‌ای وصل نشد — تلاش ادامه دارد", "No provider connected yet -- still trying"))
                // The SDK's diagnosis is for a window still forming: with providers in, it is noise.
                if (w.added == 0 && w.stall.isNotBlank() && w.stall != before.stall && w.stall != "evaluating") stallText(w.stall)?.let { record(it) }
            }
            "providers" -> {
                val list = ev.optJSONArray("countries")
                val countries = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    list?.optJSONObject(i)?.let { Country(it.optString("cc"), it.optString("name"), it.optInt("n")) }
                }
                _live.update { it.copy(providers = ev.optInt("n"), countries = countries) }
            }
            "contract" -> {
                val insufficient = ev.optBoolean("insufficient")
                if (insufficient && !_live.value.insufficient) record(tr("موجودی داده‌ی حساب تمام شده است", "The account's data balance is used up"))
                _live.update { it.copy(insufficient = insufficient) }
            }
            "limit" -> {
                val until = if (ev.optBoolean("exceeded")) ev.optLong("retry") else 0L
                if (until > 0 && _live.value.limitUntil == 0L) record(tr("سرور URnetwork این دستگاه را موقتاً نگه داشته (سقف دستگاه‌های هم‌زمان حساب)", "URnetwork's server is holding this device for now (the account's concurrent device limit)"))
                _live.update { it.copy(limitUntil = until) }
            }
            "blocker" -> {
                val on = ev.optBoolean("on")
                if (on != _live.value.blocker) record(if (on) tr("مسدودسازی تبلیغات و ردیاب‌ها روشن شد", "Ad and tracker blocking turned on") else tr("مسدودسازی تبلیغات و ردیاب‌ها خاموش شد", "Ad and tracker blocking turned off"))
                _live.update { it.copy(blocker = on) }
            }
            "blocks" -> _live.update { it.copy(blocked = ev.optLong("blocked")) }
            "tun" -> if (ev.optBoolean("up")) record(tr("تونل گوشی به هسته سپرده شد", "The phone's tunnel was handed to the core"))
                else ev.optString("error").takeIf { it.isNotBlank() }?.let { record(tr("خطای تونل: ", "Tunnel error: ") + it) }
            "logout" -> record(tr("URnetwork ورود این دستگاه را نپذیرفت", "URnetwork refused this device's sign-in"))
            "refused" -> record(readable(ev.optString("why")))
            "rules" -> record(tr("قواعد مسیر به‌روز شد", "Routing rules updated") +
                (if (ev.optBoolean("local")) tr(" · مستقیم روشن", " · direct on") else tr(" · مستقیم خاموش", " · direct off")))
        }
    }

    /**
     * The tile's data path did not answer. As the official app recovers: every exit is checked
     * again now and the connection to URnetwork's server is redialed -- the providers stay; the
     * core is never rebuilt for it (a rebuild throws the whole window away).
     */
    fun recoverTransports() {
        val t = tile ?: return
        t.command("probe")
        // A redial drops every server connection the SDK is still making, which a window that is
        // filling needs: at most one every three minutes (the official app redials once per wake;
        // every 45 s kept a window empty for minutes on the owner's phone).
        val now = SystemClock.elapsedRealtime()
        if (lastRedial == 0L || now - lastRedial >= REDIAL_EVERY_MS) {
            lastRedial = now
            t.command("network")
        }
        if (_live.value.stalled) return
        _live.update { it.copy(stalled = true) }
        record(tr("مسیر داده جواب نداد — اتصال به سرور URnetwork دوباره برقرار می‌شود و ارائه‌دهنده‌ها می‌مانند",
            "The data path did not answer -- redialing URnetwork's server; the providers stay"))
    }

    /** A data check passed again after [recoverTransports]. */
    fun dataRestored() {
        if (!_live.value.stalled) return
        _live.update { it.copy(stalled = false) }
        record(tr("مسیر داده دوباره برقرار شد", "The data path is back"))
    }

    /** The phone's power or network state put the tile's core in the SDK's power-saving mode, or took it out (UrnetworkHost). */
    fun savingChanged(why: List<String>) {
        val before = _live.value.saving
        if (before == why) return
        _live.update { it.copy(saving = why) }
        if (why.isNotEmpty()) {
            record(tr("حالت کم‌مصرف روشن (${why.joinToString("، ")}): ارائه‌دهنده‌های بیکار سه برابر کمتر بررسی می‌شوند",
                "Power saving on (${why.joinToString(", ")}): idle providers are checked three times less often"))
        } else {
            record(tr("حالت کم‌مصرف خاموش شد", "Power saving off"))
        }
    }

    /** What a stalled window says, for the user. */
    fun stallText(stall: String): String? = when (stall) {
        "platform-unreachable" -> tr("سرور URnetwork از اینترنت شما در دسترس نیست", "URnetwork's server cannot be reached from your internet")
        "providers-unresponsive" -> tr("ارائه‌دهنده‌ها جواب نمی‌دهند", "The providers do not answer")
        "rate-limited" -> tr("سرور URnetwork موقتاً درخواست‌ها را محدود کرده است", "URnetwork's server is limiting requests for now")
        "auth-failing" -> tr("حساب پذیرفته نشد", "The account was not accepted")
        else -> null
    }

    /** Why a connect that saw no provider failed, from what the core reported. */
    fun failureReason(): String {
        val l = _live.value
        return when {
            l.insufficient -> tr("موجودی داده‌ی حساب URnetwork تمام شده است؛ با شروع دورهٔ بعد دوباره پر می‌شود.", "The URnetwork account's data balance is used up; it fills again when the next period starts.")
            l.limitUntil > System.currentTimeMillis() -> tr("سرور URnetwork این دستگاه را موقتاً نگه داشته است (سقف دستگاه‌های هم‌زمان حساب). چند دقیقهٔ دیگر دوباره امتحان کنید.", "URnetwork's server is holding this device for now (the account's concurrent device limit). Try again in a few minutes.")
            l.window.stall.isNotBlank() && l.window.stall != "evaluating" -> stallText(l.window.stall) ?: tr("هیچ ارائه‌دهنده‌ای وصل نشد.", "No provider connected.")
            l.window.evalFailed > 0 -> tr("${fa(l.window.evalFailed)} ارائه‌دهنده بررسی شد ولی هیچ‌کدام از اینترنت شما وصل نشد.", "${l.window.evalFailed} providers were tried but none connected from your internet.")
            else -> tr("هیچ ارائه‌دهنده‌ای از اینترنت شما وصل نشد.", "No provider connected from your internet.")
        }
    }

    // ── the tile's core ────────────────────────────────────────────────────────────────────────

    @Volatile var tile: UrnetworkProcess? = null
        private set

    /** The tile's core: SOCKS on a free port (LAN sharing, the data-path probe) and the TUN socket. */
    fun startTile(context: Context, onExit: (Int) -> Unit): UrnetworkProcess {
        tile?.stop()
        syncAccount(context)
        val dir = roleDir(context, "tile")
        val p = UrnetworkProcess(
            role = "tile",
            dir = dir,
            socksPort = freePort(),
            tunSocket = File(dir, "tun.sock"),
            location = UrnetworkSettings.location(context),
            blocker = UrnetworkSettings.blocker(context),
            rules = rulesFile(context),
            memoryMb = 64,
            onEvent = { kind, ev -> onTileEvent(kind, ev) },
            onExit = onExit,
        )
        p.start(context)
        tile = p
        return p
    }

    fun stopTile() {
        tile?.stop()
        tile = null
    }

    /** The user moved a switch on the screen while the tile is up: the core follows at once. */
    fun blockerChanged(on: Boolean) {
        tile?.command("blocker ${if (on) 1 else 0}")
        _live.update { it.copy(blocker = on) }
    }

    fun locationChanged(context: Context) {
        tile?.command("location ${UrnetworkSettings.location(context).ifBlank { "best" }}")
    }

    /** Smart Iran or a per-destination list changed: the running core takes the new rules at once. */
    fun rulesChanged(context: Context) {
        val t = tile ?: return
        t.command("rules ${rulesFile(context)?.absolutePath ?: "off"}")
    }

    // ── the account's usage, cached for the screen ─────────────────────────────────────────────

    private fun usagePrefs(context: Context) = context.applicationContext.getSharedPreferences("urnetwork", Context.MODE_PRIVATE)

    fun cachedUsage(context: Context): UrnetworkUsage? =
        usagePrefs(context).getString("usage", null)?.let { s -> runCatching { UrnetworkUsage.parse(JSONObject(s), usagePrefs(context).getLong("usage_at", 0L)) }.getOrNull() }

    fun saveUsage(context: Context, json: JSONObject) {
        usagePrefs(context).edit().putString("usage", json.toString()).putLong("usage_at", System.currentTimeMillis()).apply()
    }

    private fun resetUsage(context: Context) {
        usagePrefs(context).edit().remove("usage").remove("usage_at").remove("locations").remove("locations_at").apply()
    }

    internal fun log(msg: String) = Log.i(TAG, msg)

    internal fun fa(n: Int): String = tr(n.toString().map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString(""), n.toString())

    const val EXIT_NO_ACCOUNT = 2
    const val EXIT_REFUSED = 3
    const val EXIT_LOGGED_OUT = 4
}

/**
 * One running URnetwork core for a job ([role]): its folder, its local SOCKS port, the TUN socket
 * for the tile, and its `[ur] ` lines read into [onEvent].
 */
class UrnetworkProcess(
    val role: String,
    val dir: File,
    val socksPort: Int,
    val tunSocket: File? = null,
    private val location: String = "",
    private val blocker: Boolean = false,
    private val rules: File? = null,
    private val memoryMb: Int = 48,
    private val onEvent: (String, JSONObject) -> Unit = { _, _ -> },
    private val onExit: (Int) -> Unit = {},
) {
    /** The TUN's addresses the SDK chose for this device. */
    data class TunAddr(val v4: String, val v6: String, val prefix6: Int, val dns4: List<String>, val dns6: List<String>, val mtu: Int)

    @Volatile private var proc: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var stopping = false
    @Volatile var listening = false
        private set
    @Volatile var tunAddr: TunAddr? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var exitCode: Int? = null
        private set
    /** The latest window: added providers, for waiting on the first one. */
    @Volatile var added = 0
        private set
    @Volatile var insufficient = false
        private set
    @Volatile var refused: String? = null
        private set
    @Volatile var loggedOut = false
        private set
    /** Why the sign-in was refused, as the core said: "register: …" is the account's, "client: …" this device's. */
    @Volatile var logoutCause: String? = null
        private set

    /** The account's own sign-in was refused at registration: it needs signing in again (UrnetworkEngine.reauth). */
    val accountRefused: Boolean get() = loggedOut && logoutCause?.startsWith("register") == true

    /** The power-saving state this core was last told (at its start, then by UrnetworkHost). */
    @Volatile internal var degraded = false

    val isAlive: Boolean
        get() = proc?.let { p -> runCatching { p.exitValue(); false }.getOrDefault(true) } ?: false

    fun start(context: Context) {
        dir.mkdirs()
        val args = arrayListOf(
            UrnetworkEngine.core(context).absolutePath,
            "-state", dir.absolutePath,
            "-account", UrnetworkEngine.accountFile(context).absolutePath,
            "-socks", "127.0.0.1:$socksPort",
            "-memory", memoryMb.toString(),
            "-desc", "MLMVPN · ${android.os.Build.MODEL}".take(60),
            "-dns", com.mlmvpn.core.lantern.LanternEngine.systemDns(context).joinToString(","),
            "-country", UrnetworkEngine.networkCountry(context),
            "-control",
        )
        tunSocket?.let { args += listOf("-tun-sock", it.absolutePath) }
        if (location.isNotBlank()) args += listOf("-location", location)
        if (blocker) args += "-blocker"
        rules?.let { args += listOf("-rules", it.absolutePath) }
        degraded = UrnetworkHost.degradedNow(context)
        if (degraded) args += "-degraded"
        val pb = ProcessBuilder(args).directory(dir).redirectErrorStream(true)
        UrnetworkEngine.env(pb, context, dir)
        val p = pb.start()
        stopping = false
        listening = false
        tunAddr = null
        lastError = null
        exitCode = null
        proc = p
        stdin = p.outputStream
        Thread({ read(p) }, "urnetwork-$role").apply { isDaemon = true }.start()
        // From now on the phone's network and power changes reach this core too.
        UrnetworkHost.attach(context, this)
    }

    fun command(line: String) {
        val o = stdin ?: return
        runCatching { synchronized(this) { o.write((line + "\n").toByteArray()); o.flush() } }
    }

    fun stop() {
        UrnetworkHost.detach(this)
        val p = proc ?: return
        stopping = true
        proc = null
        // Closing stdin is the core's signal to close its device and exit.
        runCatching { stdin?.close() }
        stdin = null
        if (!runCatching { p.waitUpTo(2500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            p.destroy()
            if (!runCatching { p.waitUpTo(1000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) runCatching { p.killHard() }
        }
    }

    /** The local SOCKS port is open (and, for the tile, the TUN addresses are known). */
    fun awaitReady(timeoutMs: Long, cancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (listening && (tunSocket == null || tunAddr != null)) return true
            if (cancelled() || !isAlive) return false
            Thread.sleep(150)
        }
        return false
    }

    /** At least one provider joined the window -- or a reason why none will. */
    fun awaitProviders(timeoutMs: Long, cancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (added > 0) return true
            if (cancelled() || !isAlive || insufficient || refused != null || loggedOut) return false
            Thread.sleep(200)
        }
        return added > 0
    }

    /**
     * Hands the VPN's TUN to the core over its unix socket (SCM_RIGHTS) and waits for its byte
     * back, which says the packet loop runs. Retried briefly: the socket is up a moment after the
     * core said its addresses.
     */
    fun handTun(fd: java.io.FileDescriptor): Boolean {
        val path = tunSocket ?: return false
        repeat(20) {
            val ok = runCatching {
                android.net.LocalSocket().use { s ->
                    s.connect(android.net.LocalSocketAddress(path.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                    s.soTimeout = 10_000
                    s.setFileDescriptorsForSend(arrayOf(fd))
                    s.outputStream.write(1)
                    s.outputStream.flush()
                    s.inputStream.read() == 1
                }
            }.getOrDefault(false)
            if (ok) return true
            if (!isAlive) return false
            Thread.sleep(250)
        }
        return false
    }

    private fun read(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine()?.trim() ?: break
                    if (!line.startsWith("[ur] ")) continue
                    val ev = line.removePrefix("[ur] ")
                    when {
                        ev.startsWith("listening") -> listening = true
                        ev.startsWith("error ") -> { lastError = ev.removePrefix("error "); UrnetworkEngine.log("[$role] $ev") }
                        ev.startsWith("ev ") -> {
                            val json = runCatching { JSONObject(ev.removePrefix("ev ")) }.getOrNull() ?: continue
                            val kind = json.optString("t")
                            when (kind) {
                                "window" -> added = json.optInt("added")
                                "contract" -> insufficient = json.optBoolean("insufficient")
                                "refused" -> refused = json.optString("why")
                                "logout" -> { logoutCause = json.optString("cause"); loggedOut = true }
                                "tunaddr" -> tunAddr = TunAddr(
                                    v4 = json.optString("v4"), v6 = json.optString("v6"), prefix6 = json.optInt("p6", 64),
                                    dns4 = json.optString("dns4").split(',').filter { it.isNotBlank() },
                                    dns6 = json.optString("dns6").split(',').filter { it.isNotBlank() },
                                    mtu = json.optInt("mtu", 1280),
                                )
                                "account" -> runCatching { UrnetworkEngine.log("[$role] token renewed") }
                            }
                            if (kind != "window" && kind != "blocks") UrnetworkEngine.log("[$role] $ev")
                            runCatching { onEvent(kind, json) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        exitCode = code
        if (proc === p || stopping) UrnetworkHost.detach(this)
        if (stopping || proc !== p) return
        proc = null
        runCatching { onExit(code) }
    }
}
