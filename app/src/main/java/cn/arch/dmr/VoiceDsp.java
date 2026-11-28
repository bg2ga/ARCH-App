package cn.arch.dmr;

/**
 * Common voice DSP used by every mode. Protocol and vocoder code only supplies
 * 16-bit PCM; receive and transmit level shaping happens here.
 */
final class VoiceDsp {
    private static final float MIN_AGC_LEVEL = 0.0025f;
    private static final float MAX_AGC_GAIN = 8.0f;
    private static final float MIN_AGC_GAIN = 0.18f;
    private static final float LIMIT_THRESHOLD = 0.78f * 32768f;
    private static final float AGC_BASE_TARGET = 0.55f;

    private volatile AudioProfile profile = new AudioProfile(
            false, false, false, false, false,
            0f, 0f, -9f, -12f, 0, 0);
    private final PathState txState = new PathState();
    private final PathState rxState = new PathState();

    synchronized void setProfile(AudioProfile next) {
        if (next != null) profile = next;
    }

    synchronized void resetAll() {
        txState.reset();
        rxState.reset();
    }

    synchronized void resetTx() {
        txState.reset();
    }

    synchronized void resetRx() {
        rxState.reset();
    }

    synchronized short[] processTx(short[] pcm, int sampleRate) {
        process(pcm, sampleRate, true);
        return pcm;
    }

    synchronized short[] processRx(short[] pcm, int sampleRate) {
        process(pcm, sampleRate, false);
        return pcm;
    }

    private void process(short[] pcm, int sampleRate, boolean tx) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) return;
        AudioProfile p = profile;
        final int tuning = tx ? p.txTuning : p.rxTuning;
        final boolean noiseReduction = tx ? p.txNoiseReduction : p.rxNoiseReduction;
        final boolean agc = tx ? p.txAgc : p.rxAgc;
        final float targetDb = tx ? p.txAgcTargetDb : p.rxAgcTargetDb;
        final float manualDb = tx ? p.txGainDb : p.rxGainDb;
        final float boost = (!tx && p.rxVolumeBoost) ? 1.9952623f : 1.0f;

        PathState st = tx ? txState : rxState;
        st.prepare(sampleRate, tuning);

        for (int i = 0; i < pcm.length; i++) {
            float x = st.highPass.process(pcm[i]);
            x = st.lowPass.process(x);
            pcm[i] = clamp16(Math.round(x));
        }

        if (noiseReduction) st.applyNoiseReducer(pcm);

        for (int i = 0; i < pcm.length; i++) {
            float x = pcm[i];
            for (Biquad band : st.bands) x = band.process(x);
            pcm[i] = clamp16(Math.round(x));
        }

        if (agc) st.applyAgc(pcm, AGC_BASE_TARGET * (float)Math.pow(10.0, targetDb / 20.0));

        float gain = (float)Math.pow(10.0, manualDb / 20.0) * boost;
        if (gain != 1.0f) {
            for (int i = 0; i < pcm.length; i++) pcm[i] = clamp16(Math.round(pcm[i] * gain));
        }

        for (int i = 0; i < pcm.length; i++) pcm[i] = softLimit(pcm[i]);
    }

    private static final class PathState {
        private int sampleRate;
        private int tuning = -1;
        private final OnePole highPass = new OnePole();
        private final OnePole lowPass = new OnePole();
        private final Biquad[] bands = new Biquad[6];
        private float agcGain = 1.0f;
        private float noiseFloor = 0.0005f;
        private float gateGain = 1.0f;

        PathState() {
            for (int i = 0; i < bands.length; i++) bands[i] = new Biquad();
        }
        void prepare(int rate, int nextTuning) {
            if (rate == sampleRate && nextTuning == tuning) return;
            sampleRate = rate;
            tuning = nextTuning;
            float lowCut = 0f, highCut = 0f;
            float[] gains = new float[6];
            switch (nextTuning) {
                case 1: // clarity
                    lowCut = 180f; highCut = 3600f;
                    gains = new float[]{-1f, 0f, 0f, 2f, 2f, 1f};
                    break;
                case 2: // bandpass
                    lowCut = 300f; highCut = 3400f;
                    break;
                case 3: // deep
                    lowCut = 100f;
                    gains = new float[]{3f, 1f, -1f, 0f, -2f, -3f};
                    break;
                case 4: // high
                    lowCut = 300f;
                    gains = new float[]{-4f, -2f, 0f, 3f, 3f, 2f};
                    break;
                case 5: // car
                    lowCut = 300f; highCut = 3400f;
                    gains = new float[]{-3f, -1f, 1f, 4f, 1f, 0f};
                    break;
                case 6: // headset
                    lowCut = 180f; highCut = 3600f;
                    gains = new float[]{-2f, -1f, 0f, 3f, 3f, 2f};
                    break;
                default: // flat
                    break;
            }
            highPass.setHighPass(lowCut, rate);
            lowPass.setLowPass(highCut, rate);
            float nyquist = rate * 0.48f;
            float[] centers = rate <= 8000
                    ? new float[]{300f, 650f, 1200f, 1900f, 2600f, 3300f}
                    : new float[]{400f, 800f, 1200f, 2000f, 3000f, 4000f};
            for (int i = 0; i < bands.length; i++) {
                bands[i].setPeaking(Math.min(centers[i], nyquist), gains[i], 0.85f, rate);
            }
            reset();
        }

        void applyNoiseReducer(short[] pcm) {
            double sum = 0.0;
            for (short sample : pcm) {
                double x = sample / 32768.0;
                sum += x * x;
            }
            float rms = (float)Math.sqrt(sum / Math.max(1, pcm.length));
            if (rms < 0.045f) noiseFloor += (rms - noiseFloor) * 0.012f;
            float floor = Math.max(0.00018f, Math.min(0.025f, noiseFloor));
            float open = floor * 3.4f;
            float close = floor * 1.65f;
            float wanted;
            if (rms >= open) wanted = 1.0f;
            else if (rms <= close) wanted = 0.16f;
            else wanted = 0.16f + 0.84f * (rms - close) / Math.max(1.0e-6f, open - close);
            gateGain += (wanted - gateGain) * (wanted > gateGain ? 0.35f : 0.035f);
            if (gateGain < 0.999f) {
                for (int i = 0; i < pcm.length; i++) pcm[i] = clamp16(Math.round(pcm[i] * gateGain));
            }
        }

        void applyAgc(short[] pcm, float target) {
            int peak = 0;
            for (short sample : pcm) {
                int value = sample < 0 ? -sample : sample;
                if (value > peak) peak = value;
            }
            float level = peak / 32768.0f;
            if (level > MIN_AGC_LEVEL) {
                float wanted = Math.max(MIN_AGC_GAIN, Math.min(MAX_AGC_GAIN,
                        Math.max(0.03f, Math.min(0.95f, target)) / level));
                float rate = wanted < agcGain ? 0.42f : 0.075f;
                agcGain += (wanted - agcGain) * rate;
            } else {
                agcGain += (1.0f - agcGain) * 0.025f;
            }
            agcGain = Math.max(MIN_AGC_GAIN, Math.min(MAX_AGC_GAIN, agcGain));
            if (agcGain != 1.0f) {
                for (int i = 0; i < pcm.length; i++) pcm[i] = clamp16(Math.round(pcm[i] * agcGain));
            }
        }

        void reset() {
            highPass.reset();
            lowPass.reset();
            for (Biquad band : bands) band.reset();
            agcGain = 1.0f;
            noiseFloor = 0.0005f;
            gateGain = 1.0f;
        }
    }
    private static final class OnePole {
        private boolean highPassMode;
        private float a;
        private float lastIn;
        private float lastOut;

        void setHighPass(float hz, int sampleRate) {
            highPassMode = true;
            a = hz <= 0f ? 0f : (float)(1.0 / (1.0 + 2.0 * Math.PI * hz / sampleRate));
        }

        void setLowPass(float hz, int sampleRate) {
            highPassMode = false;
            a = hz <= 0f ? 0f : (float)((2.0 * Math.PI * hz / sampleRate) /
                    (1.0 + 2.0 * Math.PI * hz / sampleRate));
        }

        float process(float x) {
            if (highPassMode) {
                if (a <= 0f) return x;
                float y = a * (lastOut + x - lastIn);
                lastIn = x;
                lastOut = y;
                return y;
            }
            if (a <= 0f) return x;
            lastOut += a * (x - lastOut);
            return lastOut;
        }

        void reset() {
            lastIn = 0f;
            lastOut = 0f;
        }
    }

    private static final class Biquad {
        private double b0 = 1.0, b1, b2, a1, a2;
        private double z1, z2;

        void setPeaking(float hz, float gainDb, float q, int sampleRate) {
            if (gainDb == 0f || hz <= 0f || sampleRate <= 0) {
                b0 = 1.0; b1 = b2 = a1 = a2 = 0.0;
                reset();
                return;
            }
            double a = Math.pow(10.0, gainDb / 40.0);
            double w = 2.0 * Math.PI * Math.min(hz, sampleRate * 0.45f) / sampleRate;
            double alpha = Math.sin(w) / (2.0 * Math.max(0.2, q));
            double c = Math.cos(w);
            double a0 = 1.0 + alpha / a;
            b0 = (1.0 + alpha * a) / a0;
            b1 = (-2.0 * c) / a0;
            b2 = (1.0 - alpha * a) / a0;
            a1 = (-2.0 * c) / a0;
            a2 = (1.0 - alpha / a) / a0;
            reset();
        }

        float process(float x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return (float)y;
        }

        void reset() {
            z1 = 0.0;
            z2 = 0.0;
        }
    }

    private static short softLimit(short sample) {
        float x = sample;
        float abs = Math.abs(x);
        if (abs <= LIMIT_THRESHOLD) return sample;
        float span = 32767f - LIMIT_THRESHOLD;
        float y = LIMIT_THRESHOLD + span * (float)Math.tanh((abs - LIMIT_THRESHOLD) / span);
        return clamp16(Math.round(x < 0f ? -y : y));
    }

    private static short clamp16(int value) {
        return (short)Math.max(-32768, Math.min(32767, value));
    }
}