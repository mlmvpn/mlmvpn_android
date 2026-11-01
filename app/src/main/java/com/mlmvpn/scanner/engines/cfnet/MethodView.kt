package com.mlmvpn.scanner.engines.cfnet

/**
 * How a proven strategy is NAMED for the person who pressed «سنجش روش‌ها روی این شبکه» on the Cloud
 * tab — pure, so it runs as a plain JVM test. The Cloud tab's card shows the method the engine proved
 * on this network (the same one the scanner, the connect and every combined config then use); the
 * technical ids and strategy names stay in the developer view.
 */
object MethodView {

    /** (Persian, English). A null strategy has no name: the caller says «not measured yet». */
    fun label(s: Strategy?): Pair<String, String> {
        val d = s?.data ?: return "—" to "—"
        val v6 = d.family == Family.V6
        var (fa, en) = when (d.shape) {
            TunnelShape.AS_IS -> "بدون تغییر (همان کانفیگ)" to "As the config is"
            TunnelShape.CHROME -> if (v6) "IPv6 با کروم" to "IPv6 with Chrome" else "کروم (بدون تغییر)" to "Chrome (unchanged)"
            TunnelShape.FRAG_FP -> "F&F (تکه‌کردن + فینگرپرینت)" to "F&F (fragment + fingerprint)"
            TunnelShape.FRAG_CHROME -> "تکه‌کردن با کروم" to "Fragment with Chrome"
            TunnelShape.ECH -> "ECH" to "ECH"
            TunnelShape.FIREFOX -> "فینگرپرینت فایرفاکس" to "Firefox fingerprint"
            TunnelShape.SAFARI -> "فینگرپرینت سافاری" to "Safari fingerprint"
            TunnelShape.RANDOMIZED -> "فینگرپرینت تصادفی" to "Randomised fingerprint"
            TunnelShape.IOS -> "فینگرپرینت iOS" to "iOS fingerprint"
            TunnelShape.EDGE -> "فینگرپرینت Edge" to "Edge fingerprint"
            TunnelShape.ANDROID -> "فینگرپرینت اندروید" to "Android fingerprint"
            TunnelShape.XHTTP_H2 -> "XHTTP روی HTTP/2" to "XHTTP over HTTP/2"
            TunnelShape.XHTTP_H2_STREAM -> "XHTTP روی HTTP/2 (جریانی)" to "XHTTP over HTTP/2 (streaming)"
            TunnelShape.XHTTP_H11 -> "XHTTP روی HTTP/1.1" to "XHTTP over HTTP/1.1"
            TunnelShape.XHTTP_H2_FRAG -> "XHTTP روی HTTP/2 + تکه‌کردن" to "XHTTP over HTTP/2 + fragment"
            else -> s.name to s.name
        }
        if (v6 && d.shape != TunnelShape.CHROME) { fa += " · IPv6"; en += " · IPv6" }
        when (d.addressPolicy) {
            AddressPolicy.CLEAN_IP -> { fa += " · آی‌پی تمیز"; en += " · clean IP" }
            AddressPolicy.CF_EDGE -> { fa += " · لبهٔ سالم"; en += " · working edge" }
            AddressPolicy.IPV6_TWIN -> { fa += " · دوقلوی IPv6"; en += " · IPv6 twin" }
            AddressPolicy.RESOLVED -> Unit
        }
        if (d.port > 0) { fa += " · پورت ${d.port}"; en += " · port ${d.port}" }
        return fa to en
    }

    enum class Span { NOW, MINUTES, HOURS, DAYS }

    data class Age(val span: Span, val n: Long)

    /** How long ago [at] was: «just now», minutes, hours or days. 0 / future → just now. */
    fun age(now: Long, at: Long): Age {
        val ms = (now - at).coerceAtLeast(0)
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> Age(Span.NOW, 0)
            minutes < 60 -> Age(Span.MINUTES, minutes)
            minutes < 24 * 60 -> Age(Span.HOURS, minutes / 60)
            else -> Age(Span.DAYS, minutes / (24 * 60))
        }
    }

    /**
     * What this network has proven: the strategy that carries the scan, the one that carries the
     * connect, and when each last succeeded. [best] is the connect winner (what a tunnel uses), else
     * the scan winner.
     */
    data class Verdict(val scan: Strategy?, val scanAt: Long, val connect: Strategy?, val connectAt: Long) {
        val best: Strategy? get() = connect ?: scan
        val bestAt: Long get() = if (connect != null) connectAt else scanAt
        val proven: Boolean get() = scan != null || connect != null
    }

    /** The verdict the store holds for [profile], read whether or not the «رفع فیلتر» switch is on. */
    fun verdict(registry: StrategyRegistry, store: StrategyStore, profile: NetworkProfile): Verdict {
        fun pick(stage: CfStage): Pair<Strategy?, Long> {
            val candidates = registry.forStage(stage).filter { it.data?.needsXhttp != true }
            val s = store.fastPath(profile, stage, candidates)
            return s to (s?.let { store.stat(profile, stage, it.id).lastSuccess } ?: 0L)
        }
        val (scan, scanAt) = pick(CfStage.SCAN)
        val (connect, connectAt) = pick(CfStage.CONNECT)
        return Verdict(scan, scanAt, connect, connectAt)
    }
}
