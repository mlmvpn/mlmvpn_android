package com.github.shadowsocks.bg

/**
 * JNI bridge to the tun2proxy crate's Android entry points.
 *
 * The tun2proxy Rust crate (pulled in as an Android-only dep of libmhrv_rs)
 * defines its C entry points under this exact package path:
 *   Java_com_github_shadowsocks_bg_Tun2proxy_run
 *   Java_com_github_shadowsocks_bg_Tun2proxy_stop
 *
 * That's why this file lives at `com.github.shadowsocks.bg` — the package name
 * is NOT our choice: it's the JNI name-mangling target. Renaming it would break
 * symbol resolution and the native functions would fail to link at runtime.
 * The crate reuses Shadowsocks-Android's original JNI convention.
 *
 * NOTE: the tun2proxy JNI symbols live in libtun2proxy.so (not libmhrv_rs.so).
 * tun2proxy is a Rust dep of mhrv-rs, but because nothing in mhrv-rs calls
 * these symbols directly, Rust's rlib-level dead-code elimination drops them
 * from libmhrv_rs.so. The cdylib variant of tun2proxy (built alongside the
 * rlib) retains them, so we ship that .so separately and load it here.
 *
 * We drive tun2proxy through the CLI-args wrapper exposed by libmhrv_rs
 * (`Native.runTun2proxy`) — it dlopen's libtun2proxy.so and calls
 * `tun2proxy_run_with_cli_args`. This object exists for the `stop()` symbol
 * (which the mhrv Native class does not expose) and to trigger loading of
 * libtun2proxy.so early so the dlopen inside runTun2proxy succeeds.
 */
object Tun2proxy {

    init {
        System.loadLibrary("tun2proxy")
    }

    /**
     * Start the TUN <-> proxy bridge directly (unused in our flow — we go
     * through Native.runTun2proxy's CLI wrapper instead — but kept for symbol
     * parity with the reference project and as a fallback entry point).
     *
     * @param proxyUrl e.g. "socks5://127.0.0.1:10809"
     * @param tunFd raw fd from VpnService.Builder#establish().detachFd()
     * @param closeFdOnDrop whether tun2proxy should close the fd on shutdown.
     * @param tunMtu MTU to match the VpnService setMtu() call.
     * @param verbosity 0=off, 1=error, 2=warn, 3=info, 4=debug, 5=trace.
     * @param dnsStrategy 0=Virtual, 1=OverTcp, 2=Direct. Virtual is the default.
     *
     * Returns 0 on normal shutdown, non-zero on error. BLOCKS until the TUN is
     * torn down or `stop()` is called — call from a background thread.
     */
    @JvmStatic
    external fun run(
        proxyUrl: String,
        tunFd: Int,
        closeFdOnDrop: Boolean,
        tunMtu: Char,
        verbosity: Int,
        dnsStrategy: Int,
    ): Int

    /** Signals the running tun2proxy to shut down. Idempotent. */
    @JvmStatic
    external fun stop(): Int
}
