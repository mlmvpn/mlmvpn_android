package com.mlmchat.netprobe;

/**
 * libnetprobe.so (tools/netprobe/native, built from tools/probe-core). Every network byte of the
 * internet tests goes through here, encrypted: Noise to the observer, TLS 1.3 / QUIC between phones.
 */
final class Native {
    static final boolean LOADED;
    static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("netprobe");
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private Native() {}

    /** One JSON command in, one JSON result out. Blocking: never call it on the UI thread. */
    static native String run(String json);

    static native void cancel();

    /** Log lines written since the last call (no addresses, no keys). */
    static native String pollLog();

    // Bluetooth test: Noise_NKpsk0 sessions by handle (probe-core blenoise). 0 / null = failed.
    static native long bleResponder();

    static native long bleInitiator(String peerCard);

    static native byte[] bleWriteHs(long id, byte[] payload);

    static native byte[] bleReadHs(long id, byte[] msg);

    /** 8-byte nonce + ciphertext. */
    static native byte[] bleSeal(long id, long nonce, byte[] plaintext);

    /** null for a forged, damaged, replayed or old packet. */
    static native byte[] bleOpen(long id, byte[] packet);

    static native void bleFree(long id);
}
