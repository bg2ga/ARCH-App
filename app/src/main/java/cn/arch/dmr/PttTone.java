package cn.arch.dmr;

import android.media.AudioManager;
import android.media.ToneGenerator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standard PTT tone: one ACK beep on transmit start, one on release; NACK when busy. */
final class PttTone {
    private static final int TONE = ToneGenerator.TONE_PROP_ACK;
    private static final int BEEP_MS = 60;
    private static final int GAP_MS = 50;
    private static final int VOLUME = 75;
    // 单线程队列：快速连续按 PTT 时，start/stop 提示音不会互相取消。
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arch-ptt-tone");
        t.setDaemon(true);
        return t;
    });

    private PttTone() {}

    static void start() { play(1); }
    static void stop() { play(1); }

    /** [3.2.40] 频道被占用/PTT 被拒绝：与发射 ACK 不同的 NACK 双短音。 */
    static void busy() {
        try {
            EXEC.execute(() -> {
                ToneGenerator tone = null;
                try {
                    tone = new ToneGenerator(AudioManager.STREAM_SYSTEM, VOLUME);
                    for (int i = 0; i < 2; i++) {
                        tone.startTone(ToneGenerator.TONE_PROP_NACK, 110);
                        Thread.sleep(110);
                        tone.stopTone();
                        if (i == 0) Thread.sleep(70);
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable ignored) {
                } finally {
                    if (tone != null) {
                        try { tone.stopTone(); } catch (Throwable ignored) {}
                        try { tone.release(); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void play(final int count) {
        try {
            EXEC.execute(() -> {
                ToneGenerator tone = null;
                try {
                    tone = new ToneGenerator(AudioManager.STREAM_SYSTEM, VOLUME);
                    for (int i = 0; i < count; i++) {
                        tone.startTone(TONE, BEEP_MS);
                        Thread.sleep(BEEP_MS);
                        tone.stopTone();
                        if (i + 1 < count) Thread.sleep(GAP_MS);
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable ignored) {
                } finally {
                    if (tone != null) {
                        try { tone.stopTone(); } catch (Throwable ignored) {}
                        try { tone.release(); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }
}
