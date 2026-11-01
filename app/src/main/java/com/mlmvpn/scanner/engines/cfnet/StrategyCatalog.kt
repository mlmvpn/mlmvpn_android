package com.mlmvpn.scanner.engines.cfnet

/**
 * The catalog of real, validatable strategies, grounded in capabilities the app actually has:
 *  - CONTROL (api.cloudflare.com / Workers over the app's own HTTPS): an address source × a wire
 *    shape, run by [CfControlClient] over the Doctor's WayClient for BOTH diagnosis and the app's
 *    own requests (one wire path, so what was proven is what runs), plus the IDENTITY carriers
 *    (direct / WarpApiFront / the user's relay Worker);
 *  - DATA (an Xray tunnel to a Cloudflare-fronted server): a TLS/tunnel shape × family × address
 *    policy × port, applied by [CfShape] to the core's JSON. Full MASQUE/WARP/chains keep their
 *    own lifecycle and data checks and are deliberately NOT listed: they do not carry the user's
 *    config, so they cannot be a fallback for it.
 *
 * Every entry is a distinct runtime behaviour with its own validation path: a different address,
 * resolver path, ClientHello, TLS version, record layout, port or family on the wire. Pure value
 * tweaks that change nothing observable are deliberately NOT listed. Adding one later is a single
 * [StrategyRegistry.register] call here (plus its applier, if the shape is new).
 */
object StrategyCatalog {

    private val CONTROL_REACH = setOf(CfStage.ACCOUNT, CfStage.DEPLOY, CfStage.CONFIG)
    private val SCAN_CONNECT = setOf(CfStage.SCAN, CfStage.CONNECT)

    fun populate(reg: StrategyRegistry) {
        control(reg); identity(reg); data(reg)
    }

    // ---- CONTROL: how the app's own HTTPS reaches api.cloudflare.com and a Worker -----------------
    private fun control(reg: StrategyRegistry) {
        fun route(id: String, name: String, addr: AddressSource, shape: WireShape, prior: Double, cost: Int, prereqs: Set<Prereq> = emptySet()) =
            reg.register(Strategy(
                id = "ctl_$id", name = name, stages = CONTROL_REACH, layer = Layer.CONTROL,
                prior = prior, cost = cost, timeoutMs = 90_000, retries = 1, prereqs = prereqs,
                route = RouteSpec(addr, shape),
            ))

        route("sys_plain", "مسیر پیش‌فرض", AddressSource.SYSTEM, WireShape.NONE, 0.50, 1)
        route("current_tunnel", "تونل فعال برنامه", AddressSource.CURRENT_TUNNEL, WireShape.NONE, 0.72, 2, setOf(Prereq.ACTIVE_PROXY))
        route("sys_split", "مسیر پیش‌فرض + تکه‌کردن", AddressSource.SYSTEM, WireShape.SPLIT, 0.55, 2)
        route("wroute_plain", "آدرس سالم ورکر", AddressSource.WORKER_ROUTE, WireShape.NONE, 0.60, 2)
        route("wroute_split", "آدرس سالم ورکر + تکه‌کردن", AddressSource.WORKER_ROUTE, WireShape.SPLIT, 0.62, 3)
        route("cfedge_plain", "لبه‌ی سالم کلادفلر", AddressSource.CF_EDGE, WireShape.NONE, 0.60, 3)
        route("cfedge_split", "لبه‌ی سالم کلادفلر + تکه‌کردن", AddressSource.CF_EDGE, WireShape.SPLIT, 0.65, 3)
        route("clean_split", "آی‌پی تمیز + تکه‌کردن", AddressSource.SCANNER_CLEAN, WireShape.SPLIT, 0.55, 4)
        route("doh8_plain", "DNS گوگل", AddressSource.DOH_GOOGLE, WireShape.NONE, 0.50, 3)
        route("doh8_split", "DNS گوگل + تکه‌کردن", AddressSource.DOH_GOOGLE, WireShape.SPLIT, 0.55, 4)
        route("udp8_split", "DNS ساده‌ی گوگل + تکه‌کردن", AddressSource.UDP_GOOGLE, WireShape.SPLIT, 0.50, 4)
        route("cfedge_f3", "لبه‌ی سالم + فرگمنت F3", AddressSource.CF_EDGE, WireShape.F3, 0.52, 4)
        route("v6edge_plain", "لبه‌ی IPv6 کلادفلر", AddressSource.IPV6_EDGE, WireShape.NONE, 0.50, 3, setOf(Prereq.IPV6_ROUTE))
        route("v6edge_split", "لبه‌ی IPv6 + تکه‌کردن", AddressSource.IPV6_EDGE, WireShape.SPLIT, 0.55, 3, setOf(Prereq.IPV6_ROUTE))
        route("clean_f12", "آی‌پی تمیز + فرگمنت F12", AddressSource.SCANNER_CLEAN, WireShape.F12, 0.50, 4)
        route("sys_f3", "مسیر پیش‌فرض + فرگمنت F3", AddressSource.SYSTEM, WireShape.F3, 0.50, 3)
        route("cfedge_f12", "لبه‌ی سالم + فرگمنت F12", AddressSource.CF_EDGE, WireShape.F12, 0.55, 4)
        route("sys_tls12", "مسیر پیش‌فرض با TLS 1.2", AddressSource.SYSTEM, WireShape.TLS12, 0.48, 2)
        route("cfedge_tls12", "لبهٔ سالم با TLS 1.2", AddressSource.CF_EDGE, WireShape.TLS12, 0.49, 3)
        route("sys_f14", "فرگمنت یک‌بایتی روی مسیر پیش‌فرض", AddressSource.SYSTEM, WireShape.F14, 0.42, 6)
        route("doh8_tls12", "DNS گوگل با TLS 1.2", AddressSource.DOH_GOOGLE, WireShape.TLS12, 0.46, 3)
        route("v6edge_tls12", "IPv6 با TLS 1.2", AddressSource.IPV6_EDGE, WireShape.TLS12, 0.44, 3, setOf(Prereq.IPV6_ROUTE))
        route("cfedge_f14", "فرگمنت یک‌بایتی روی لبهٔ سالم", AddressSource.CF_EDGE, WireShape.F14, 0.44, 6)

        // A second resolver path that is not Google's: where 8.8.8.8 is the thing filtered.
        route("doh94_plain", "DNS ادگارد", AddressSource.DOH_ADGUARD, WireShape.NONE, 0.47, 3)
        route("doh94_split", "DNS ادگارد + تکه‌کردن", AddressSource.DOH_ADGUARD, WireShape.SPLIT, 0.50, 4)
        // The name filter, attacked where it reads: a cut inside the server name, the hello in two
        // TLS records, and a mIxEd-case name.
        route("sys_f4", "برش وسط نام سرور", AddressSource.SYSTEM, WireShape.F4, 0.50, 3)
        route("cfedge_f4", "لبهٔ سالم + برش وسط نام سرور", AddressSource.CF_EDGE, WireShape.F4, 0.54, 4)
        route("sys_f8", "دو رکورد TLS", AddressSource.SYSTEM, WireShape.F8, 0.48, 3)
        route("cfedge_f8", "لبهٔ سالم + دو رکورد TLS", AddressSource.CF_EDGE, WireShape.F8, 0.52, 4)
        route("sys_snimix", "نام سرور با حروف درهم", AddressSource.SYSTEM, WireShape.SNI_MIXED, 0.46, 2)
        route("cfedge_snimix", "لبهٔ سالم + نام سرور درهم", AddressSource.CF_EDGE, WireShape.SNI_MIXED, 0.50, 3)
        route("cfedge_tls13", "لبهٔ سالم با TLS 1.3", AddressSource.CF_EDGE, WireShape.TLS13, 0.48, 3)
        // Reachability is per address range and per region (138 Doctor reports): a live address in
        // each of the three ranges that answered most often, each its own route.
        route("r172_64_split", "محدودهٔ 172.64 + تکه‌کردن", AddressSource.RANGE_172_64, WireShape.SPLIT, 0.56, 4)
        route("r104_16_split", "محدودهٔ 104.16 + تکه‌کردن", AddressSource.RANGE_104_16, WireShape.SPLIT, 0.54, 4)
        route("r188_114_split", "محدودهٔ 188.114 + تکه‌کردن", AddressSource.RANGE_188_114, WireShape.SPLIT, 0.48, 4)
    }

    // ---- IDENTITY: which carrier reaches the WARP API ---------------------------------------------
    private fun identity(reg: StrategyRegistry) {
        fun carrier(id: String, name: String, carrier: Carrier, addr: AddressSource, shape: WireShape, prior: Double, cost: Int, prereqs: Set<Prereq> = emptySet()) =
            reg.register(Strategy(
                id = "id_$id", name = name, stages = setOf(CfStage.IDENTITY), layer = Layer.CONTROL,
                prior = prior, cost = cost, timeoutMs = 60_000, retries = 1, prereqs = prereqs,
                route = RouteSpec(addr, shape, carrier),
            ))

        carrier("direct", "دریافت هویت WARP مستقیم", Carrier.DIRECT, AddressSource.SYSTEM, WireShape.NONE, 0.40, 2)
        carrier("direct_split", "دریافت هویت WARP + تکه‌کردن", Carrier.DIRECT, AddressSource.SYSTEM, WireShape.SPLIT, 0.45, 3)
        carrier("front", "دریافت هویت از مسیر جایگزین", Carrier.WARP_FRONT, AddressSource.CF_EDGE, WireShape.SPLIT, 0.55, 4)
        carrier("relay", "دریافت هویت از ورکر شما", Carrier.WORKER_RELAY, AddressSource.WORKER_ROUTE, WireShape.SPLIT, 0.60, 5, setOf(Prereq.WORKER_DEPLOYED))
        // The same relay with the ClientHello whole. Measured on the phone (Wi-Fi, 2026-10-04): the
        // split handshake was cut on every route while the account API answered on the plain
        // worker route -- a network can filter the split itself.
        carrier("relay_plain", "دریافت هویت از ورکر شما (بدون تکه‌کردن)", Carrier.WORKER_RELAY, AddressSource.WORKER_ROUTE, WireShape.NONE, 0.58, 4, setOf(Prereq.WORKER_DEPLOYED))
        // The WARP API host on an edge proven here, and on Cloudflare's IPv6 edge.
        carrier("cfedge_split", "دریافت هویت از لبهٔ سالم", Carrier.DIRECT, AddressSource.CF_EDGE, WireShape.SPLIT, 0.50, 4)
        carrier("v6_split", "دریافت هویت روی IPv6", Carrier.DIRECT, AddressSource.IPV6_EDGE, WireShape.SPLIT, 0.45, 4, setOf(Prereq.IPV6_ROUTE))
    }

    // ---- DATA: how the Xray/Aether tunnel is dialled ----------------------------------------------
    private fun data(reg: StrategyRegistry) {
        fun shape(id: String, name: String, stages: Set<CfStage>, spec: ShapeSpec, prior: Double, cost: Int, prereqs: Set<Prereq> = emptySet(), timeout: Long = 120_000) =
            reg.register(Strategy(
                id = "dat_$id", name = name, stages = stages, layer = Layer.DATA,
                prior = prior, cost = cost, timeoutMs = timeout, retries = 1, prereqs = prereqs,
                data = spec,
            ))

        // The config exactly as written: tried, measured and remembered like any other strategy, so
        // a network where nothing needs changing is learned as such.
        shape("asis_v4", "تنظیمات خود کانفیگ", SCAN_CONNECT, ShapeSpec(TunnelShape.AS_IS, Family.V4), 0.52, 1)

        // Baseline and the two headline shapes, per family.
        shape("chrome_v4", "کروم (بدون تغییر)", SCAN_CONNECT, ShapeSpec(TunnelShape.CHROME, Family.V4), 0.50, 1)
        shape("chrome_v6", "کروم روی IPv6", SCAN_CONNECT, ShapeSpec(TunnelShape.CHROME, Family.V6), 0.40, 2, setOf(Prereq.IPV6_ROUTE))
        shape("fragfp_v4", "تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4), 0.60, 3)
        shape("fragfp_v6", "تکه‌کردن + فینگرپرینت روی IPv6", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V6), 0.50, 3, setOf(Prereq.IPV6_ROUTE))
        shape("ech_v4", "ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V4), 0.55, 3)
        shape("ech_v6", "ECH روی IPv6", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V6), 0.50, 3, setOf(Prereq.IPV6_ROUTE))

        // Fragment with uTLS Chrome kept (the verified tlshello mask, without switching uTLS off):
        // a genuinely different ClientHello from FRAG_FP, which dials with Go's TLS.
        shape("fragchrome_v4", "تکه‌کردن (کروم)", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_CHROME, Family.V4), 0.52, 3)
        shape("fragchrome_v6", "تکه‌کردن (کروم) روی IPv6", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_CHROME, Family.V6), 0.47, 3, setOf(Prereq.IPV6_ROUTE))

        // Fingerprint-only (uTLS profiles -- each a distinct ClientHello).
        shape("firefox_v4", "فینگرپرینت فایرفاکس", SCAN_CONNECT, ShapeSpec(TunnelShape.FIREFOX, Family.V4), 0.45, 2)
        shape("safari_v4", "فینگرپرینت سافاری", SCAN_CONNECT, ShapeSpec(TunnelShape.SAFARI, Family.V4), 0.45, 2)
        shape("random_v4", "فینگرپرینت تصادفی", SCAN_CONNECT, ShapeSpec(TunnelShape.RANDOMIZED, Family.V4), 0.40, 2)
        shape("ios_v4", "فینگرپرینت iOS", SCAN_CONNECT, ShapeSpec(TunnelShape.IOS, Family.V4), 0.44, 2)
        shape("edge_v4", "فینگرپرینت Edge", SCAN_CONNECT, ShapeSpec(TunnelShape.EDGE, Family.V4), 0.43, 2)
        shape("android_v4", "فینگرپرینت اندروید", SCAN_CONNECT, ShapeSpec(TunnelShape.ANDROID, Family.V4), 0.42, 2)

        // Address-policy variants: where the dialled address comes from, with a proven shape.
        shape("clean_fragfp", "آی‌پی تمیز + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, AddressPolicy.CLEAN_IP), 0.60, 4)
        shape("clean_chrome", "آی‌پی تمیز (کروم)", SCAN_CONNECT, ShapeSpec(TunnelShape.CHROME, Family.V4, AddressPolicy.CLEAN_IP), 0.50, 3)
        shape("clean_ech", "آی‌پی تمیز + ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V4, AddressPolicy.CLEAN_IP), 0.52, 4)
        shape("clean_fragchrome", "آی‌پی تمیز + تکه‌کردن (کروم)", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_CHROME, Family.V4, AddressPolicy.CLEAN_IP), 0.50, 4)
        shape("cfedge_fragfp", "لبه‌ی سالم + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, AddressPolicy.CF_EDGE), 0.55, 4)
        shape("cfedge_ech", "لبهٔ سالم + ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V4, AddressPolicy.CF_EDGE), 0.52, 4)
        shape("v6twin_fragfp", "دوقلوی IPv6 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V6, AddressPolicy.IPV6_TWIN), 0.50, 4, setOf(Prereq.IPV6_ROUTE))
        shape("v6twin_ech", "دوقلوی IPv6 + ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V6, AddressPolicy.IPV6_TWIN), 0.48, 4, setOf(Prereq.IPV6_ROUTE))

        // Alternate HTTPS ports Cloudflare serves (filters treat ports differently).
        shape("port2053_fragfp", "پورت 2053 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, port = 2053), 0.45, 3)
        shape("port2083_fragfp", "پورت 2083 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, port = 2083), 0.45, 3)
        shape("port2087_fragfp", "پورت 2087 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, port = 2087), 0.43, 3)
        shape("port2096_fragfp", "پورت 2096 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, port = 2096), 0.43, 3)
        shape("port8443_fragfp", "پورت 8443 + تکه‌کردن + فینگرپرینت", SCAN_CONNECT, ShapeSpec(TunnelShape.FRAG_FP, Family.V4, port = 8443), 0.45, 3)
        shape("port2053_ech", "پورت 2053 + ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V4, port = 2053), 0.44, 3)
        shape("port8443_ech", "پورت 8443 + ECH", SCAN_CONNECT, ShapeSpec(TunnelShape.ECH, Family.V4, port = 8443), 0.44, 3)

        // XHTTP, for a config whose server speaks XHTTP: which HTTP version and mode carry it here.
        // Never tried on a WebSocket config (the transport is the server's); the stream mode is
        // never tried on a Worker (Cloudflare buffers a Worker's request body, measured 2026-09).
        val xh = setOf(Prereq.XHTTP_CONFIG)
        shape("xhttp_h2", "XHTTP روی HTTP/2", SCAN_CONNECT, ShapeSpec(TunnelShape.XHTTP_H2, Family.V4), 0.56, 2, xh)
        shape("xhttp_h2_stream", "XHTTP روی HTTP/2 (جریانی)", SCAN_CONNECT, ShapeSpec(TunnelShape.XHTTP_H2_STREAM, Family.V4), 0.46, 2, xh)
        shape("xhttp_h11", "XHTTP روی HTTP/1.1", SCAN_CONNECT, ShapeSpec(TunnelShape.XHTTP_H11, Family.V4), 0.47, 2, xh)
        shape("xhttp_h2_frag", "XHTTP روی HTTP/2 + تکه‌کردن", SCAN_CONNECT, ShapeSpec(TunnelShape.XHTTP_H2_FRAG, Family.V4), 0.52, 3, xh)

        // Full engines have their own lifecycle and data validation. They are not Xray shape
        // adapters and must not masquerade as fallback candidates for a user's server config.
    }
}
