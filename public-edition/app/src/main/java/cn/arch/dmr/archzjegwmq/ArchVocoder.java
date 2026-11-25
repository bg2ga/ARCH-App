package cn.arch.dmr.archzjegwmq;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Exact MD380 JNI wrapper used by ARCH Digital Audio. */
public final class ArchVocoder {
    private static final String TAG = "ARCH-MD380";
    private static final Object LOCK = new Object();
    private static final Object NATIVE_LOCK = new Object();
    private static volatile ArchVocoder instance;

    private static final String FIRMWARE_FILE = "md380_firmware.img";
    private static final String CORE_FILE = "md380_core.img";
    private static final String FIRMWARE_SHA =
            "c0351f250a834660641bca3b06be931c80cc1ed0ef808356c550b99cd0f4c632";
    private static final String CORE_SHA =
            "d40398183e9aa8db6288a62d9a0e0c233cb143ef5633680abd829cc03c719f66";

    private static final boolean LOADED;
    static {
        boolean ok = false;
        try { System.loadLibrary("md380vocoder"); ok = true; }
        catch (Throwable t) { Log.e(TAG, "native library unavailable", t); }
        LOADED = ok;
    }

    private final Context appContext;
    private volatile boolean ready;
    private volatile boolean failed;

    private ArchVocoder(Context context) { appContext = context.getApplicationContext(); }

    public static ArchVocoder get(Context context) {
        ArchVocoder local = instance;
        if (local == null) {
            synchronized (LOCK) {
                local = instance;
                if (local == null) { local = new ArchVocoder(context); instance = local; }
            }
        }
        return local;
    }

    public boolean init() {
        synchronized (LOCK) {
            if (ready) return true;
            if (failed || !LOADED) return false;
            try {
                File firmware = stage("firmware.img", FIRMWARE_FILE, FIRMWARE_SHA);
                File core = stage("core.img", CORE_FILE, CORE_SHA);
                File emu = findEmulator();
                if (firmware == null || core == null || emu == null) { failed = true; return false; }
                ready = nativeInit(firmware.getAbsolutePath(), core.getAbsolutePath(), emu.getAbsolutePath());
                if (ready) {
                    try { nativePinToFastCores(); } catch (Throwable ignored) {}
                    try { Log.i(TAG, "MD380 ready mode=" + nativeVocoderMode()); } catch (Throwable ignored) {}
                } else failed = true;
            } catch (Throwable t) { failed = true; Log.e(TAG, "init failed", t); }
            return ready;
        }
    }

    public boolean ok() { return init(); }

    /** Exact ARCH Digital Audio 33-byte DMRD payload decode; returns 480 PCM samples. */
    public short[] decodeDmrdPayload(byte[] payload33) {
        if (!init() || payload33 == null || payload33.length < 33) return null;
        try { synchronized (NATIVE_LOCK) { return nativeDecodeDmrdPayload(payload33); } }
        catch (Throwable t) { Log.e(TAG, "DMRD decode failed", t); return null; }
    }

    /** Exact ARCH Digital Audio AMBE encode; returns one 9-byte FEC frame. */
    public byte[] encodeFec(short[] pcm160) {
        if (!init() || pcm160 == null || pcm160.length != 160) return null;
        try { synchronized (NATIVE_LOCK) { return nativeEncodeFec(pcm160); } }
        catch (Throwable t) { Log.e(TAG, "encode failed", t); return null; }
    }

    public short[] decode(byte[] code7) {
        if (!init() || code7 == null || code7.length < 7) return null;
        try { synchronized (NATIVE_LOCK) { return nativeDecode(code7); } }
        catch (Throwable t) { return null; }
    }

    /** Exact ARCH Digital Audio natural 7-byte AMBE decode entry used by YSF/NXDN. */
    public short[] decodeNatural(byte[] code7) {
        if (!init() || code7 == null || code7.length < 7) return null;
        try { synchronized (NATIVE_LOCK) { return nativeDecode(code7); } }
        catch (Throwable t) { Log.e(TAG, "AMBE decode failed", t); return null; }
    }

    /** Exact ARCH Digital Audio natural 7-byte AMBE encode: encodeFec then the same 9->7 bit packing used by ARCH. */
    public byte[] encodeNatural(short[] pcm160) {
        byte[] fec = encodeFec(pcm160);
        return fec == null ? null : fec9ToAmbe7(fec);
    }

    /** Exact port of ARCH Digital Audio kq2.m3994X(byte[9]) -> byte[7]. */
    public static byte[] fec9ToAmbe7(byte[] in) {
        if (in == null || in.length < 9) return null;
        int a = readBits(in, IDX1) >> 12;
        int b = readBits(in, IDX2);
        int state = (a << 4) & 0xFFFF;
        int lfsr = 0;
        for (int i = 0; i < 24; i++) {
            state = ((state * 173) + 13849) & 0xFFFF;
            lfsr = (lfsr << 1) | ((state >> 15) & 1);
        }
        int c = readBits(in, IDX3);
        byte[] out = new byte[7];
        putBits(0, a, 12, out);
        putBits(12, (b ^ (lfsr >> 1)) >> 11, 12, out);
        putBits(24, c, 25, out);
        return out;
    }

    private static int readBits(byte[] in, int[] positions) {
        int out = 0;
        for (int pos : positions) {
            out <<= 1;
            if (pos >= 0 && (pos >> 3) < in.length && (in[pos >> 3] & (0x80 >>> (pos & 7))) != 0) out |= 1;
        }
        return out;
    }

    private static void putBits(int start, int value, int count, byte[] out) {
        for (int i = 0; i < count; i++) {
            int pos = start + i;
            int mask = 0x80 >>> (pos & 7);
            if (((value >>> ((count - 1) - i)) & 1) != 0) out[pos >> 3] |= mask;
            else out[pos >> 3] &= (byte)~mask;
        }
    }

    private static final int[] IDX1 = {
        0,4,8,12,16,20,24,28,32,36,40,44,48,52,56,60,64,68,1,5,9,13,17,21
    };
    private static final int[] IDX2 = {
        25,29,33,37,41,45,49,53,57,61,65,69,2,6,10,14,18,22,26,30,34,38,42
    };
    private static final int[] IDX3 = {
        46,50,54,58,62,66,70,3,7,11,15,19,23,27,31,35,39,43,47,51,55,59,63,67,71
    };

    private File stage(String assetName, String targetName, String expectedSha) throws IOException {
        File target = new File(appContext.getFilesDir(), targetName);
        if (valid(target, expectedSha)) return target;
        File part = new File(appContext.getFilesDir(), targetName + ".part");
        if (part.exists() && !part.delete()) throw new IOException("cannot remove stale part");
        try (InputStream in = appContext.getAssets().open("md380/" + assetName);
             FileOutputStream out = new FileOutputStream(part)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
            out.getFD().sync();
        }
        if (!valid(part, expectedSha)) { if (!part.delete()) Log.w(TAG, "cannot delete invalid part"); throw new IOException("hash mismatch"); }
        if (target.exists() && !target.delete()) throw new IOException("cannot replace target");
        if (!part.renameTo(target)) throw new IOException("cannot rename part");
        return target;
    }

    private static boolean valid(File f, String sha) { return f != null && f.isFile() && f.length() > 0 && sha.equalsIgnoreCase(sha256(f)); }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) >= 0) if (n > 0) d.update(b, 0, n);
            byte[] out = d.digest(); StringBuilder s = new StringBuilder(out.length * 2);
            for (byte x : out) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Throwable t) { return ""; }
    }

    private File findEmulator() {
        String dir = appContext.getApplicationInfo().nativeLibraryDir;
        if (dir == null) return null;
        File f = new File(dir, "libmd380emu32.so");
        if (!f.isFile()) return null;
        if (!f.canExecute()) f.setExecutable(true, false);
        return f.canExecute() ? f : null;
    }

    private static native short[] nativeDecode(byte[] code7);
    private static native short[] nativeDecodeDmrdPayload(byte[] payload33);
    private static native byte[] nativeEncodeFec(short[] pcm160);
    private static native boolean nativeInit(String firmware, String core, String emulator);
    private static native int nativePinToFastCores();
    private static native int nativeVocoderMode();
}
