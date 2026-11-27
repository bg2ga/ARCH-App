package cn.arch.dmr;

/**
 * Frame AGC with the attack/release behaviour used for digital speech playback.
 *
 * The peak of each 160-sample (20 ms) frame controls the gain.  Fast attack
 * prevents clipping; slow release avoids pumping.  Gain is limited to about
 * +/-15.85 dB and silence slowly returns the gain to unity.
 */
final class DigitalAgc {
    static final float DEFAULT_TARGET = 0.55f; // 0 dB target used by digital speech
    private static final float MIN_LEVEL = 0.015f;
    private static final float MIN_GAIN = (float)Math.pow(10.0, -1.2);
    private static final float MAX_GAIN = (float)Math.pow(10.0, 1.2);

    private final float target;
    private float gain = 1.0f;

    DigitalAgc() {
        this(DEFAULT_TARGET);
    }

    DigitalAgc(float target) {
        this.target = target;
    }

    synchronized void reset() {
        gain = 1.0f;
    }

    synchronized void process(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;

        int peak = 0;
        for (short sample : pcm) {
            int value = sample < 0 ? -sample : sample;
            if (value > peak) peak = value;
        }

        float level = peak / 32768.0f;
        if (level > MIN_LEVEL) {
            float wanted = clamp(target / level, MIN_GAIN, MAX_GAIN);
            gain += (wanted - gain) * (wanted < gain ? 0.5f : 0.05f);
        } else {
            gain += (1.0f - gain) * 0.05f;
        }

        if (gain != 1.0f) {
            for (int i = 0; i < pcm.length; i++) {
                int value = Math.round(pcm[i] * gain);
                pcm[i] = (short)clamp(value, -32768.0f, 32767.0f);
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}

