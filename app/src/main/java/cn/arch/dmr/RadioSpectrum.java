package cn.arch.dmr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * [3.2.42] 真实音频频谱。
 * 直接从 ExoPlayer 写入 AudioTrack 之前的 PCM 采样做 1024 点 FFT，
 * 不依赖 Visualizer / RECORD_AUDIO，也不会出现“完全不跳”的机型差异。
 */
public final class RadioSpectrum {
    public static final int BARS = 32;
    private static final int FFT_SIZE = 1024;
    private static final float[] LEVELS = new float[BARS];
    private static volatile boolean available;
    private static volatile long lastFeedAt;
    private static volatile long lastFftAt;
    // 自动增益：不同电台电平差很大，用滑动峰值归一化
    private static float agc = 0.02f;
    private static volatile boolean uiVisible = true;

    /** [3.2.44] 收音机页不可见时不做 FFT，后台播放只保留音频本身。 */
    public static void setUiVisible(boolean v) {
        uiVisible = v;
        if (!v) accPos = 0;
    }

    private RadioSpectrum() {}

    public static boolean available() { return available; }

    static void clear() {
        synchronized (LEVELS) { java.util.Arrays.fill(LEVELS, 0f); }
        available = false;
        lastFeedAt = 0;
        lastFftAt = 0;
        agc = 0.02f;
    }

    public static float[] snapshot() {
        float[] out = new float[BARS];
        synchronized (LEVELS) { System.arraycopy(LEVELS, 0, out, 0, BARS); }
        return out;
    }

    private static final float[] ACC = new float[FFT_SIZE];
    private static int accPos;

    /**
     * ExoPlayer 的 PCM 缓冲（16bit 小端）。
     * 解码器单次缓冲可能只有 960/1152 帧，必须跨缓冲累积满 1024 帧再做 FFT，
     * 否则小缓冲会被整段丢掉，表现为频谱一直不动。
     */
    static void feedPcm(ByteBuffer src, int sampleRate, int channels) {
        if (!uiVisible) return;
        if (src == null || !src.hasRemaining() || sampleRate <= 0) return;
        int ch = Math.max(1, Math.min(8, channels));
        int frames = src.remaining() / (2 * ch);
        if (frames <= 0) return;
        final ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position();
        for (int i = 0; i < frames; i++) {
            int off = base + i * ch * 2;
            float sum = 0f;
            for (int c = 0; c < ch; c++) sum += b.getShort(off + c * 2) / 32768f;
            ACC[accPos++] = sum / ch;
            if (accPos >= FFT_SIZE) {
                accPos = 0;
                long now = System.currentTimeMillis();
                if (now - lastFftAt >= 66) {
                    lastFftAt = now;
                    computeAndPublish(sampleRate);
                }
            }
        }
    }

    private static void computeAndPublish(int sampleRate) {
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        boolean any = false;
        for (int i = 0; i < FFT_SIZE; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));
            re[i] = (float) (ACC[i] * w);
            if (ACC[i] != 0f) any = true;
        }
        if (!any) return;
        fft(re, im);
        publish(re, im, sampleRate);
    }

    /** 原地基-2 FFT。 */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int p = i + k, q = p + half;
                    float vr = re[q] * cr - im[q] * ci;
                    float vi = re[q] * ci + im[q] * cr;
                    float ur = re[p], ui = im[p];
                    re[p] = ur + vr; im[p] = ui + vi;
                    re[q] = ur - vr; im[q] = ui - vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    private static void publish(float[] re, float[] im, int sampleRate) {
        int bins = re.length / 2;
        float nyquist = sampleRate / 2f;
        float fMin = 55f;
        float fMax = Math.min(16000f, nyquist * 0.95f);
        if (fMax <= fMin) return;
        float binHz = nyquist / bins;
        float[] next = new float[BARS];
        float framePeak = 0f;
        for (int b = 0; b < BARS; b++) {
            double r0 = Math.pow(fMax / fMin, (double) b / BARS);
            double r1 = Math.pow(fMax / fMin, (double) (b + 1) / BARS);
            int k0 = Math.max(1, (int) Math.round(fMin * r0 / binHz));
            int k1 = Math.min(bins - 1, Math.max(k0, (int) Math.round(fMin * r1 / binHz)));
            float peak = 0f;
            for (int k = k0; k <= k1; k++) {
                float mag = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                if (mag > peak) peak = mag;
            }
            // Hann 窗下单频满幅约 N/4；归一化后再做高频倾斜补偿
            float v = peak / (re.length / 4f);
            next[b] = v * (1f + 0.8f * b / (float) BARS);
            if (next[b] > framePeak) framePeak = next[b];
        }
        // 滑动峰值 AGC：起音立即跟上，静音缓慢回落
        if (framePeak > agc) agc = framePeak;
        else agc = Math.max(0.004f, agc * 0.995f);
        synchronized (LEVELS) {
            for (int b = 0; b < BARS; b++) {
                float v = next[b] / Math.max(0.004f, agc);
                if (v > 1f) v = 1f;
                if (v < 0.02f) v = 0f;
                LEVELS[b] = Math.max(v, LEVELS[b] * 0.80f);
            }
        }
        available = true;
        lastFeedAt = System.currentTimeMillis();
    }

    public static boolean fresh() {
        return lastFeedAt > 0 && System.currentTimeMillis() - lastFeedAt < 1500L;
    }
}
