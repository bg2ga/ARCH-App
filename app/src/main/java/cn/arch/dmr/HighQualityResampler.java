package cn.arch.dmr;

import java.util.Arrays;

/** High-quality streaming resampler for 8 kHz digital speech. */
public final class HighQualityResampler {
    private final int inRate;
    private final int outRate;
    private final boolean sameRate;
    private final int up;
    private final int down;
    private final int taps;
    private final float[] coeff;
    private final short[] history;
    private short[] work;
    private int phase;

    public HighQualityResampler(int inRate, int outRate) {
        this.inRate = inRate;
        this.outRate = outRate;
        this.sameRate = inRate == outRate;
        this.work = new short[0];
        if (inRate <= 0 || outRate <= 0) throw new IllegalArgumentException("rates must be positive");
        if (sameRate) {
            up = 1; down = 1; taps = 1; coeff = new float[0]; history = new short[0];
            return;
        }
        int a = inRate, b = outRate;
        while (b != 0) { int t = b; b = a % b; a = t; }
        up = outRate / a;
        down = inRate / a;
        int filterTaps = (int)Math.ceil(64.0 * Math.max(up, down) / (double)up);
        taps = Math.max(2, filterTaps);
        int filterLen = up * taps;
        double min = (Math.min(inRate, outRate) * 0.45) / (inRate * (double)up);
        double last = filterLen - 1;
        double center = last / 2.0;
        double scale = 1.0;
        double sum = 1.0;
        int n = 1;
        while (n < 64) {
            double q = 8.95926 / (n * 2.0);
            scale *= q * q;
            sum += scale;
            if (scale <= sum * 1.0e-17) break;
            n++;
        }
        coeff = new float[filterLen];
        for (int i = 0; i < filterLen; i++) {
            double x = (i - center) * (min * 2.0);
            double sinc = x == 0.0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
            double y = (i * 2.0) / last - 1.0;
            double bes = Math.sqrt(Math.max(0.0, 1.0 - y * y)) * 8.95926;
            double s0 = 1.0, s1 = 1.0;
            int k = 1;
            while (k < 64) {
                double q = bes / (k * 2.0);
                s0 *= q * q;
                s1 += s0;
                if (s0 <= s1 * 1.0e-17) break;
                k++;
            }
            coeff[i] = (float)((s1 / sum) * (min * 2.0 * sinc) * up);
        }
        history = new short[taps - 1];
    }

    public synchronized void reset() {
        Arrays.fill(history, (short)0);
        phase = 0;
    }

    public synchronized short[] process(short[] input) {
        if (input == null || input.length == 0) return input;
        if (sameRate) return Arrays.copyOf(input, input.length);
        int hlen = history.length;
        int total = hlen + input.length;
        if (work.length < total) work = new short[total];
        System.arraycopy(history, 0, work, 0, hlen);
        System.arraycopy(input, 0, work, hlen, input.length);
        long totalPhases = (long)input.length * up;
        int outLen;
        if (phase >= totalPhases) outLen = 0;
        else outLen = (int)((totalPhases - phase + down - 1L) / down);
        short[] out = new short[outLen];
        int pos = phase;
        for (int oi = 0; oi < outLen; oi++) {
            int wi = pos / up + hlen;
            int ci = pos % up;
            float v = 0.0f;
            for (int j = 0; j < taps; j++) {
                v += work[wi] * coeff[ci];
                ci += up;
                wi--;
            }
            int iv = Math.round(v);
            if (iv > 32767) iv = 32767; else if (iv < -32768) iv = -32768;
            out[oi] = (short)iv;
            pos += down;
        }
        int copyFrom = total - hlen;
        System.arraycopy(work, copyFrom, history, 0, hlen);
        phase = pos - input.length * up;
        return out;
    }
}

