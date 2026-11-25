package cn.arch.dmr;

/** D-Star AMBE 2400x1200 vocoder wrapper. */
public final class DStarVocoder {
    private static final boolean LOADED;
    static {
        boolean ok = false;
        try { System.loadLibrary("archdstar"); ok = true; } catch (Throwable ignored) {}
        LOADED = ok;
    }
    private long h = LOADED ? nativeCreate() : 0;
    public boolean ok() { return h != 0; }
    public boolean encode(short[] pcm160, byte[] out9) {
        if (h == 0 || pcm160 == null || out9 == null || pcm160.length != 160 || out9.length != 9) return false;
        try { return nativeEncode(h, pcm160, out9) == 0; } catch (Throwable ignored) { return false; }
    }
    public boolean decode(byte[] in9, short[] pcm160) {
        if (h == 0 || in9 == null || pcm160 == null || in9.length != 9 || pcm160.length != 160) return false;
        try { return nativeDecode(h, in9, pcm160) == 0; } catch (Throwable ignored) { return false; }
    }
    public void reset() {
        if (h == 0) return;
        try { nativeDestroy(h); } catch (Throwable ignored) {}
        h = 0;
        try { h = nativeCreate(); } catch (Throwable ignored) {}
    }
    public boolean selfTest() {
        if (h == 0) return false;
        short[] pcm = new short[160];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(Math.sin(2.0 * Math.PI * 1000.0 * i / 8000.0) * 12000.0);
        byte[] bits = new byte[9]; short[] out = new short[160];
        boolean ok = encode(pcm, bits) && decode(bits, out);
        reset();
        return ok;
    }
    public void close() { if (h != 0) { try { nativeDestroy(h); } catch (Throwable ignored) {} h = 0; } }
    private static native long nativeCreate();
    private static native void nativeDestroy(long h);
    private static native int nativeReset(long h);
    private static native int nativeEncode(long h, short[] pcm, byte[] out9);
    private static native int nativeDecode(long h, byte[] in9, short[] pcm);
}