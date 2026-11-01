package com.mlmchat.spike;

/**
 * libspike_pqtls.so (tools/spike-pqtls): TLS 1.3 handshakes in memory, X25519 against the
 * hybrid X25519MLKEM768 (PLAN 17.3). No network.
 */
public final class PqTls {
    public static final boolean LOADED;
    public static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("spike_pqtls");
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private PqTls() {}

    /** Blocking; one JSON result. */
    public static native String run(int handshakes);
}
