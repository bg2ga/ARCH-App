package cn.arch.dmr;

/** Official libopus wrapper with sample-rate, frame-size, and native-call guards. */
final class FmoOpus {
    private static final boolean LOADED = ArchOpus.loaded();
    private long enc, dec;
    private final int rate;

    FmoOpus() { this(8000); }

    FmoOpus(int sampleRate) {
        rate = sampleRate;
        if (LOADED && validRate(rate)) {
            try {
                enc = ArchOpus.createEncoder(rate, 1);
                dec = ArchOpus.createDecoder(rate, 1);
            } catch (Throwable ignored) {
                enc = 0;
                dec = 0;
            }
        }
    }

    synchronized void setBitrate(int bps) {
        if (enc == 0) return;
        try { ArchOpus.setBitrate(enc, Math.max(6000, Math.min(510000, bps))); }
        catch (Throwable ignored) {}
    }

    synchronized byte[] encode(short[] pcm) {
        if (enc == 0 || pcm == null || !validSamples(rate, pcm.length)) return null;
        try { return ArchOpus.encode(enc, pcm, pcm.length); }
        catch (Throwable ignored) { return null; }
    }

    synchronized short[] decode(byte[] frame) { return decode(frame, rate / 50); }

    synchronized short[] decode(byte[] frame, int samples) {
        if (dec == 0 || frame == null || frame.length == 0 || !validSamples(rate, samples)) return null;
        try { return ArchOpus.decode(dec, frame, samples); }
        catch (Throwable ignored) { return null; }
    }

    /** libopus packet-loss concealment; preserves decoder state across short network gaps. */
    synchronized short[] decodePlc(int samples) {
        if (dec == 0 || !validSamples(rate, samples)) return null;
        try { return ArchOpus.decodePlc(dec, samples); }
        catch (Throwable ignored) { return null; }
    }

    /** libopus FEC recovery using the following packet. */
    synchronized short[] decodeFec(byte[] frame, int samples) {
        if (dec == 0 || frame == null || frame.length == 0 || !validSamples(rate, samples)) return null;
        try { return ArchOpus.decodeFec(dec, frame, samples); }
        catch (Throwable ignored) { return null; }
    }

    synchronized boolean selfTest() {
        try {
            short[] out = decode(hex("10c272172274648341ceebc92b509a387d63e1e75a5ba0f859dd883bc03cf4bea47e8ef114a2d4f080a57a1e6aeb227e8c605fef27d2bd"));
            return out != null && out.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    synchronized void close() {
        if (enc != 0) {
            try { ArchOpus.destroyEncoder(enc); } catch (Throwable ignored) {}
            enc = 0;
        }
        if (dec != 0) {
            try { ArchOpus.destroyDecoder(dec); } catch (Throwable ignored) {}
            dec = 0;
        }
    }

    private static boolean validRate(int r) {
        return r == 8000 || r == 12000 || r == 16000 || r == 24000 || r == 48000;
    }

    private static boolean validSamples(int r, int n) {
        int[] us = {2500, 5000, 10000, 20000, 40000, 60000};
        for (int u : us) if ((long)r * u / 1000000L == n) return true;
        return false;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte)Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
