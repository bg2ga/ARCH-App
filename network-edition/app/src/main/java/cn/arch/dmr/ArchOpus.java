package cn.arch.dmr;

/** Dedicated Opus JNI bridge. The unique class name prevents symbol collisions with libarchdmr.so. */
final class ArchOpus {
    private static final boolean LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("archvocoder");
            ok = true;
        } catch (Throwable t) {
            android.util.Log.e("ARCH-OPUS", "loadLibrary(archvocoder) failed", t);
        }
        LOADED = ok;
    }

    private ArchOpus() {}

    static boolean loaded() { return LOADED; }
    static long createEncoder(int rate, int channels) { return nativeCreateEncoder(rate, channels); }
    static void destroyEncoder(long handle) { nativeDestroyEncoder(handle); }
    static byte[] encode(long handle, short[] pcm, int frameSamples) { return nativeEncode(handle, pcm, frameSamples); }
    static void setBitrate(long handle, int bps) { nativeSetBitrate(handle, bps); }
    static long createDecoder(int rate, int channels) { return nativeCreateDecoder(rate, channels); }
    static void destroyDecoder(long handle) { nativeDestroyDecoder(handle); }
    static short[] decode(long handle, byte[] frame, int outSamples) { return nativeDecode(handle, frame, outSamples); }
    static short[] decodePlc(long handle, int outSamples) { return nativeDecodePlc(handle, outSamples); }
    static short[] decodeFec(long handle, byte[] frame, int outSamples) { return nativeDecodeFec(handle, frame, outSamples); }

    private static native long nativeCreateEncoder(int rate, int channels);
    private static native void nativeDestroyEncoder(long handle);
    private static native byte[] nativeEncode(long handle, short[] pcm, int frameSamples);
    private static native void nativeSetBitrate(long handle, int bps);
    private static native long nativeCreateDecoder(int rate, int channels);
    private static native void nativeDestroyDecoder(long handle);
    private static native short[] nativeDecode(long handle, byte[] frame, int outSamples);
    private static native short[] nativeDecodePlc(long handle, int outSamples);
    private static native short[] nativeDecodeFec(long handle, byte[] frame, int outSamples);
}
