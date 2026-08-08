package com.therealaleph.mhrv

import android.util.Log

/**
 * JNI bindings for the MasterHttpRelayVPN-RUST core.
 * The package name and class name MUST match `Java_com_therealaleph_mhrv_Native_*`.
 */
object Native {
    init {
        try {
            System.loadLibrary("mhrv_rs")
            Log.i("MhrvNative", "Successfully loaded libmhrv_rs.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MhrvNative", "Failed to load libmhrv_rs.so: ${e.message}")
        }
    }

    /**
     * Call once before startProxy.
     */
    external fun setDataDir(path: String)

    /**
     * Start the proxy with a JSON or TOML config payload.
     * Returns a handle (long). Returns 0 on failure.
     */
    external fun startProxy(configJson: String): Long

    /**
     * Stop the proxy with the given handle.
     */
    external fun stopProxy(handle: Long): Boolean

    /**
     * Return live JSON stats.
     */
    external fun statsJson(handle: Long): String
    
    /**
     * Gets logs from the core.
     */
    external fun drainLogs(): String
    
    /**
     * Gets version of the core.
     */
    external fun version(): String

    /**
     * Runs tun2proxy's CLI entry point (`tun2proxy_run_with_cli_args`), which
     * libmhrv_rs.so dlopen's from libtun2proxy.so. BLOCKS until the TUN is torn
     * down (i.e. the proxy's SOCKS5 listener closes or Tun2proxy.stop() fires) —
     * MUST be called from a background thread.
     *
     * @param cliArgs full tun2proxy CLI, e.g.
     *   "tun2proxy --proxy socks5://127.0.0.1:10809 --tun-fd <fd> --dns virtual
     *    --verbosity info --close-fd-on-drop true"
     * @param tunMtu MTU to match the VpnService setMtu() call.
     * @return 0 on clean shutdown; negative on dlopen/dlsym/other failure.
     */
    external fun runTun2proxy(cliArgs: String, tunMtu: Int): Int
}
