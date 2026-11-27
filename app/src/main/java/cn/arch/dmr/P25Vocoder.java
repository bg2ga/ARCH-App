package cn.arch.dmr;

/** P25 Phase 1 IMBE 4400 bps vocoder wrapper (20 ms / 160 samples / 11 byte codec frame). */
public final class P25Vocoder {
    private static final boolean LOADED;
    static {
        boolean ok = false;
        try { System.loadLibrary("archdstar"); ok = true; } catch (Throwable ignored) {}
        LOADED = ok;
    }
    private long h = LOADED ? nativeCreate() : 0;

    public boolean ok() { return h != 0; }

    public boolean encode(short[] pcm160, byte[] out11) {
        if (h == 0 || pcm160 == null || out11 == null || pcm160.length != 160 || out11.length != 11) return false;
        try { return nativeEncode(h, pcm160, out11) == 0; } catch (Throwable ignored) { return false; }
    }

    public boolean decode(byte[] in11, short[] pcm160) {
        if (h == 0 || in11 == null || pcm160 == null || in11.length != 11 || pcm160.length != 160) return false;
        try { return nativeDecode(h, in11, pcm160) == 0; } catch (Throwable ignored) { return false; }
    }

    public boolean selfTest() {
        if (h == 0) return false;
        short[] pcm = new short[160];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(Math.sin(2.0 * Math.PI * 1000.0 * i / 8000.0) * 12000.0);
        byte[] bits = new byte[11];
        short[] out = new short[160];
        boolean ok = encode(pcm, bits) && decode(bits, out);
        reset();
        return ok;
    }

    public void reset() {
        if (h == 0) return;
        try { nativeDestroy(h); } catch (Throwable ignored) {}
        h = 0;
        try { h = nativeCreate(); } catch (Throwable ignored) {}
    }

    public void close() {
        if (h != 0) { try { nativeDestroy(h); } catch (Throwable ignored) {} h = 0; }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long h);
    private static native int nativeEncode(long h, short[] pcm, byte[] out11);
    private static native int nativeDecode(long h, byte[] in11, short[] pcm);
}
