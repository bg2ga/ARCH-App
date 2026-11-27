package cn.arch.dmr;

/**
 * Lightweight RX/TX audio level bus.
 * Values are computed from the actual PCM frames that are played or captured.
 * No timers are started here; the UI view decides when to redraw.
 */
final class AudioLevelBus {
    private static volatile float rxDb = -120f;
    private static volatile float txDb = -120f;
    private static volatile long rxAt = 0L;
    private static volatile long txAt = 0L;
    private static volatile Runnable onChange;

    private AudioLevelBus() {}

    static void setOnChange(Runnable r) { onChange = r; }

    static float rxDb() { return rxDb; }
    static float txDb() { return txDb; }

    static boolean rxActive() {
        return System.currentTimeMillis() - rxAt < 140L;
    }

    static boolean txActive() {
        return System.currentTimeMillis() - txAt < 140L;
    }

    static void pushRx(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        long now = System.currentTimeMillis();
        float next = level(pcm);
        if (now - rxAt > 320L) rxDb = next;
        else if (next > rxDb) rxDb += (next - rxDb) * 0.82f;
        else rxDb += (next - rxDb) * 0.32f;
        if (rxDb < -120f) rxDb = -120f;
        rxAt = now;
        notifyChange();
    }

    static void pushTx(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        long now = System.currentTimeMillis();
        float next = level(pcm);
        if (now - txAt > 320L) txDb = next;
        else if (next > txDb) txDb += (next - txDb) * 0.86f;
        else txDb += (next - txDb) * 0.34f;
        if (txDb < -120f) txDb = -120f;
        txAt = now;
        notifyChange();
    }

    private static void notifyChange() {
        Runnable r = onChange;
        if (r != null) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /** RMS dBFS, measured on every second sample to keep the cost negligible. */
    private static float level(short[] pcm) {
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            double v = pcm[i];
            sum += v * v;
            n++;
        }
        if (n == 0) return -120f;
        double rms = Math.sqrt(sum / n);
        if (rms < 1.0) return -120f;
        double db = 20.0 * Math.log10(rms / 32768.0);
        if (db < -120.0) db = -120.0;
        if (db > 0.0) db = 0.0;
        return (float) db;
    }
}