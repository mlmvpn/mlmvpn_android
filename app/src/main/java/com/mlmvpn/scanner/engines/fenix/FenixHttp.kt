package com.mlmvpn.scanner.engines.fenix

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * FENIX's control plane: Firefox Accounts, Mozilla's Guardian and the server list.
 *
 * These calls go out directly, never through a tunnel: they are reachable from Iran (measured
 * 2026-10-03 on an Iranian line: api.accounts.firefox.com, vpn.mozilla.org and
 * firefox.settings.services.mozilla.com all answered), and the app's own traffic is kept off the
 * TUN, so they work the same with FENIX up or down.
 */
internal object FenixHttp {

    /** What Mozilla's own VPN client sends; Guardian and the server list expect it. */
    const val USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"

    val cookies = FenixCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    fun Request.Builder.mozilla(): Request.Builder =
        header("User-Agent", USER_AGENT).header("Accept", "application/json")

    /**
     * The User-Agent of the WebView in which the user passed a site's browser check, by site
     * (`firefox.com`, `mozilla.org`): Fastly may hold its pass to the browser that earned it (31 Oct
     * 2026, on an Iranian line: after the check, vpn.mozilla.org still answered 406). And the sites
     * that took a call only in that browser's name, which then goes so from the start.
     */
    private val checkedAgent = ConcurrentHashMap<String, String>()
    private val inBrowserName = ConcurrentHashMap.newKeySet<String>()

    fun checkPassed(host: String, userAgent: String?) {
        if (!userAgent.isNullOrBlank()) checkedAgent[siteOf(host)] = userAgent
    }

    /** `firefox.com` or `mozilla.org` for their hosts: the browser check and its cookies are per site. */
    fun siteOf(host: String): String = when {
        host == "firefox.com" || host.endsWith(".firefox.com") -> "firefox.com"
        host == "mozilla.org" || host.endsWith(".mozilla.org") -> "mozilla.org"
        else -> host
    }

    /**
     * Runs a control-plane call. A 406 (Fastly's browser check) from a site whose check the user
     * passed is tried once more in that WebView's own name; a 406 still is the caller's to raise.
     */
    fun execute(req: Request): Response {
        val site = siteOf(req.url.host)
        val agent = checkedAgent[site]
        val first = if (agent != null && site in inBrowserName) req.newBuilder().header("User-Agent", agent).build() else req
        val r = client.newCall(first).execute()
        if (r.code != 406) return r
        Log.w(TAG, "406 from ${req.url.host} (cookies ${cookies.loadForRequest(req.url).size}, checked ${agent != null})")
        if (agent == null || first.header("User-Agent") == agent) return r
        r.close()
        val again = client.newCall(req.newBuilder().header("User-Agent", agent).build()).execute()
        Log.w(TAG, "${req.url.host} again in the browser's name: ${again.code}")
        if (again.code != 406) inBrowserName.add(site)
        return again
    }

    private const val TAG = "FENIX"
}

/**
 * Cookies for the control plane. Only the cookies that Fastly sets after its browser check are
 * ever interesting here; they are copied in from the WebView where the user passed that check
 * (see [FenixAuth.ChallengeNeeded]) and kept for the session.
 */
internal class FenixCookieJar : CookieJar {
    private val byDomain = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) put(c)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return byDomain.entries.filter { (d, _) -> url.host == d || url.host.endsWith(".$d") }
            .flatMap { (_, list) -> synchronized(list) { list.filter { it.expiresAt > now } } }
    }

    private fun put(c: Cookie) {
        val list = byDomain.getOrPut(c.domain) { mutableListOf() }
        synchronized(list) { list.removeAll { it.name == c.name }; list.add(c) }
    }

    /**
     * Takes the cookies a WebView holds for one host (`a=b; c=d`, as CookieManager gives them) and
     * makes them valid for every host of its site the API calls use: *.firefox.com for Firefox
     * Accounts, *.mozilla.org for Guardian (vpn.mozilla.org) and the server list.
     */
    fun importFromWebView(host: String, header: String?) {
        header ?: return
        val site = FenixHttp.siteOf(host)
        header.split(';').map { it.trim() }.filter { '=' in it }.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=').trim()
            if (name.isEmpty()) return@forEach
            put(Cookie.Builder().name(name).value(value).domain(site).path("/")
                .expiresAt(System.currentTimeMillis() + 12 * 3600_000L).build())
        }
    }
}
