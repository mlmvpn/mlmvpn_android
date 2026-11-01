package com.mlmchat.spike;

/**
 * libspike_libp2p.so (tools/spike-libp2p): the rust-libp2p node of decision T11. Only arm64-v8a
 * and armeabi-v7a are packed; elsewhere LOADED is false and the buttons say so.
 */
public final class Spike {
    public static final boolean LOADED;
    public static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("spike_libp2p");
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private Spike() {}

    /** `{"cmd":"chat","obs":{...},"room":"...","duration_s":120}` in, one JSON result out. Blocking. */
    public static native String run(String cmd);

    public static native void cancel();

    /** Log lines since the last call (no addresses). */
    public static native String pollLog();
}
