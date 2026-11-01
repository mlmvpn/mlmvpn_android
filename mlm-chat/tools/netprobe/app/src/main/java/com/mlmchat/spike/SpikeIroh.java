package com.mlmchat.spike;

/** libspike_iroh.so (tools/spike-iroh): the iroh endpoint of decision T11. Same contract as {@link Spike}. */
public final class SpikeIroh {
    public static final boolean LOADED;
    public static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("spike_iroh");
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        LOADED = ok;
        LOAD_ERROR = err;
    }

    private SpikeIroh() {}

    public static native String run(String cmd);

    public static native void cancel();

    public static native String pollLog();
}
