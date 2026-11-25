package cn.arch.dmr;

/** 48 kHz MIC -> DC blocker -> SpeexDSP Q6 + AGC -> 20 ms 8 kHz PCM. */
public final class PcmFrontEnd {
    private long h;
    static {
        try { System.loadLibrary("archpcm"); } catch (Throwable ignored) {}
    }
    public PcmFrontEnd() { h = nativeCreate(); }
    public boolean ok() { return h != 0; }
    public void reset() { if (h != 0) nativeReset(h); }
    public short[] process(short[] pcm48, int sampleRate) {
        if (h == 0 || pcm48 == null || pcm48.length == 0) return null;
        return nativeProcess(h, pcm48, sampleRate);
    }
    public void close() {
        if (h != 0) { nativeDestroy(h); h = 0; }
    }
    private static native long nativeCreate();
    private static native void nativeReset(long h);
    private static native void nativeDestroy(long h);
    private static native short[] nativeProcess(long h, short[] pcm48, int sampleRate);
}
