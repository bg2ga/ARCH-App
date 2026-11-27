package cn.arch.dmr;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

/**
 * 8kHz 单声道音频：麦克风 20ms 一帧回调，喇叭直接播放。
 * 内含：RX/TX 增益（dB）、AGC、降噪（自适应噪声门 + 高通去低频）、PTT 提示音。
 */
public class AudioEngine {
    public interface MicCb { void onFrame(short[] pcm160); }
    public interface MicRateCb { void onFrame(short[] pcm, int sampleRate); }
    private static final int SR = 8000;
    private static final int CAPTURE_SR = 48000;
    private static final int CAPTURE_FRAME = 480;   // 10ms @ 48k
    private static final int FRONT_NONE = 0;
    private static final int FRONT_PCM8K = 2;
    private static final int FRONT_DSTAR = 3;
    /** FMO / 模拟互联：8kHz VOICE_COMMUNICATION + 系统 NS/AGC/AEC（对齐参考 APP）。 */
    private static final int FRONT_FMO = 4;
    private static final int FRONT_DSTAR_PDC = 5;
    private AudioRecord rec; private AudioTrack trk;
    private AudioTrack dstarTrk;
    private HighQualityResampler dstarResampler;
    private final DigitalAgc dstarAgc = new DigitalAgc();
    private final DigitalAgc rawAgc = new DigitalAgc();
    private final DigitalAgc micAgc = new DigitalAgc();
    private AutomaticGainControl agcFx; private NoiseSuppressor nsFx; private AcousticEchoCanceler aecFx;
    private Thread th; private volatile boolean run; private volatile boolean micRun;
    private volatile boolean dstarRawMic;
    private volatile MicCb micCb;
    private volatile MicRateCb micRateCb;

 /** 线性增益（由 dB 换算） */
    private volatile float micGain = 1.0f, spkGain = 1.0f;
    private volatile boolean agc = false, denoise = false;
    /** FMO 语音增强：系统降噪+AGC+AEC（默认开，对齐参考 APP）。 */
    private volatile boolean voiceEffects = true;
    private static final float TX_LIMIT_PEAK = 24000f;
    private static final float RX_LIMIT_PEAK = 30000f;
    private static final float LIMIT_RELEASE = 0.02f;
    /** DMR/BM 接收专用补偿：额外 2.5 倍，约 +8 dB。 */
    private static final float DMR_RX_EXTRA_GAIN = 2.5f;
    private volatile float txLimitGain = 1f;
    private volatile float rxLimitGain = 1f;

    // ── DSP 状态（只被播放线程和录音线程各自访问）──
    private float dcY = 0f;              // 一阶高通
    private float env = 0f;              // 降噪用包络
    private float noiseFloor = 250f;     // 自适应底噪
    private float gate = 1f, gateTarget = 1f;
    private float agcEnv = 0f, agcGain = 1f;
    private static final float AGC_TARGET = 7000f;

 /** 只启动扬声器播放；麦克风必须等到按下 PTT 时才启动。 */
    public synchronized boolean start() {
        if (run && trk != null) return true;
        try {
            if (trk != null) { trk.release(); trk = null; }
            int minOut = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            trk = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minOut, 160 * 2 * 6), AudioTrack.MODE_STREAM);
            if (trk.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("扬声器未初始化");
            trk.play();
            run = true;
            return true;
        } catch (Exception e) {
            run = false;
            try { if (trk != null) { trk.release(); trk = null; } } catch (Exception ignored) {}
            android.util.Log.e("ARCH", "AudioEngine speaker start failed", e);
            return false;
        }
    }

 /** 非 null 时按下 PTT 才开始录音；传 null 会立即停止并释放麦克风。 */
    /** D-Star-only high quality playback: frame AGC then band-limited resampling. */
    private synchronized boolean startDstarTrack() {
        if (dstarTrk != null) return true;
        int[] rates = {48000, 44100, SR};
        for (int rate : rates) {
            int minOut = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minOut <= 0) continue;
            AudioTrack t = null;
            try {
                t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minOut, rate / 25 * 2), AudioTrack.MODE_STREAM);
                if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                    try { t.release(); } catch (Throwable ignored) {}
                    continue;
                }
                dstarTrk = t;
                dstarResampler = rate == SR ? null : new HighQualityResampler(SR, rate);
                dstarAgc.reset();
                t.play();
                return true;
            } catch (Throwable ignored) {
                if (t != null) try { t.release(); } catch (Throwable ignored2) {}
            }
        }
        return false;
    }

    public synchronized void playDstar(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (dstarTrk == null && !startDstarTrack()) return;
        try {
            short[] speech = java.util.Arrays.copyOf(pcm, pcm.length);
            // D-Star AGC disabled: the server and codec already provide standard level.
            float g = spkGain;
            if (g != 1.0f) {
                for (int i = 0; i < speech.length; i++) {
                    int v = Math.round(speech[i] * g);
                    speech[i] = (short)(v < -32768 ? -32768 : (v > 32767 ? 32767 : v));
                }
            }
            short[] out = dstarResampler == null ? speech : dstarResampler.process(speech);
            if (out == null || out.length == 0 || dstarTrk == null) return;
            AudioLevelBus.pushRx(out);
            dstarTrk.write(out, 0, out.length);
        } catch (Throwable t) {
            android.util.Log.e("ARCH", "D-Star audio playback failed", t);
        }
    }

    public synchronized void resetDstar() {
        dstarAgc.reset();
        if (dstarResampler != null) dstarResampler.reset();
    }

    public synchronized void stopDstar() {
        AudioTrack d = dstarTrk;
        dstarTrk = null;
        dstarResampler = null;
        dstarAgc.reset();
        if (d != null) {
            new Thread(() -> {
                try { d.stop(); } catch (Throwable ignored) {}
                try { d.release(); } catch (Throwable ignored) {}
            }, "dstar-audio-release").start();
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void startMic(final MicCb cb) {
        startMicInternal(cb, null, MediaRecorder.AudioSource.VOICE_COMMUNICATION, FRONT_NONE, false);
    }

 /** FMO/模拟互联：8kHz VOICE_COMMUNICATION，可选系统 NS+AGC+AEC。 */
    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void startFmoMic(final MicCb cb, final boolean enhance) {
        startMicInternal(cb, null, MediaRecorder.AudioSource.VOICE_COMMUNICATION, FRONT_FMO, enhance);
    }

    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void startYsfMic(final MicCb cb) {
        startMicInternal(cb, MediaRecorder.AudioSource.MIC, FRONT_PCM8K);
    }

    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void startDstarMic(final MicCb cb) {
        startMicInternal(cb, MediaRecorder.AudioSource.MIC, FRONT_DSTAR);
    }

    /** PDC760/PDC hardware: use the POC main-microphone route and native 8 kHz capture for D-Star. */
    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void startDstarPdcMic(final MicCb cb) {
        startMicInternal(cb, MediaRecorder.AudioSource.MIC, FRONT_DSTAR_PDC);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startMicInternal(final MicCb cb, final int source, final int frontEnd) {
        startMicInternal(cb, null, source, frontEnd, false);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void startMicInternal(final MicCb cb, final MicRateCb rateCb, final int source, final int frontEnd) {
        startMicInternal(cb, rateCb, source, frontEnd, false);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startMicInternal(final MicCb cb, final MicRateCb rateCb, final int source, final int frontEnd, final boolean wantEffects) {
        micCb = cb;
        dstarRawMic = frontEnd == FRONT_DSTAR || frontEnd == FRONT_DSTAR_PDC;
        micRateCb = rateCb;
        android.util.Log.i("ARCH", "AudioEngine startMic cb=" + (cb != null) + " rateCb=" + (rateCb != null) + " frontEnd=" + frontEnd);
        if (cb == null && rateCb == null) { stopMic(); return; }
        if (!start()) { android.util.Log.e("ARCH", "AudioEngine startMic: speaker start failed"); return; }
        if (micRun && rec != null) return;
        try {
            final int captureRate;
            AudioRecord candidate = null;
            if (frontEnd == FRONT_DSTAR || frontEnd == FRONT_DSTAR_PDC || frontEnd == FRONT_FMO) {
                // DroidStar/DCS uses native 8 kHz capture. PDC uses its POC HAL route, but the codec path stays 8 kHz; only use 48 kHz if 8 kHz is unsupported.
                // DSTAR/FMO are 8 kHz speech paths: prefer 8 kHz capture to avoid feeding 48 kHz PCM into the 8 kHz Opus encoder.
                int min8 = AudioRecord.getMinBufferSize(SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min8 != AudioRecord.ERROR && min8 != AudioRecord.ERROR_BAD_VALUE) {
                    candidate = new AudioRecord(source, SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min8 * 4, 160 * 2 * 8));
                }
                if (candidate != null && candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate;
                    captureRate = SR;
                } else {
                    if (candidate != null) candidate.release();
                    int min48 = AudioRecord.getMinBufferSize(CAPTURE_SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(source, CAPTURE_SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min48 * 4, CAPTURE_FRAME * 2 * 8));
                    captureRate = CAPTURE_SR;
                }
            } else if (frontEnd != FRONT_NONE) {
                int min48 = AudioRecord.getMinBufferSize(CAPTURE_SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min48 != AudioRecord.ERROR && min48 != AudioRecord.ERROR_BAD_VALUE) {
                    candidate = new AudioRecord(source, CAPTURE_SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min48 * 4, CAPTURE_FRAME * 2 * 8));
                }
                if (candidate != null && candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate;
                    captureRate = CAPTURE_SR;
                } else {
                    if (candidate != null) candidate.release();
                    int min8 = AudioRecord.getMinBufferSize(SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(source, SR,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min8, 160 * 2 * 4));
                    captureRate = SR;
                }
            } else {
                int min8 = AudioRecord.getMinBufferSize(SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(source, SR,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min8, 160 * 2 * 4));
                captureRate = SR;
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风未初始化");
            final boolean dstarCapture = frontEnd == FRONT_DSTAR || frontEnd == FRONT_DSTAR_PDC;
            final boolean fx = (frontEnd == FRONT_FMO) ? (wantEffects && voiceEffects) : (frontEnd != FRONT_NONE && !dstarCapture);
            // D-Star uses the system noise suppressor only. Keep AGC/AEC off so the
            // DroidStar-compatible 8 kHz/160-sample transmit path is not modified.
            final boolean dstarNs = dstarCapture;
            if (frontEnd != FRONT_NONE) {
                if (Build.VERSION.SDK_INT >= 29) {
                    try { rec.setPreferredMicrophoneDirection(android.media.MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED); } catch (Throwable ignored) {}
                    try { rec.setPreferredMicrophoneFieldDimension(0.5f); } catch (Throwable ignored) {}
                }
                try {
                    int sid = rec.getAudioSessionId();
                    if (NoiseSuppressor.isAvailable()) {
                        nsFx = NoiseSuppressor.create(sid);
                        if (nsFx != null) nsFx.setEnabled(dstarNs || fx);
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        agcFx = AutomaticGainControl.create(sid);
                        if (agcFx != null) agcFx.setEnabled(fx && !dstarNs);
                    }
                    if (AcousticEchoCanceler.isAvailable()) {
                        aecFx = AcousticEchoCanceler.create(sid);
                        if (aecFx != null) aecFx.setEnabled(fx && !dstarNs);
                    }
                } catch (Throwable ignored) {
                    releaseEffectsAsync();
                }
            }
            rec.startRecording();
            micRun = true;
            android.util.Log.i("ARCH", "AudioEngine AudioRecord started rate=" + captureRate + " state=" + rec.getState() + " recording=" + rec.getRecordingState());
            final int inputFrame = captureRate == CAPTURE_SR ? CAPTURE_FRAME : (captureRate == 24000 ? 480 : 160);
            th = new Thread(() -> {
                try { android.os.Process.setThreadPriority(-19); } catch (Throwable ignored) {}
                short[] in = new short[inputFrame];
                short[] out8 = new short[160];
                boolean usePcmFrontEnd = (frontEnd == FRONT_PCM8K) || ((frontEnd == FRONT_DSTAR || frontEnd == FRONT_DSTAR_PDC || frontEnd == FRONT_FMO) && captureRate == CAPTURE_SR);
                PcmFrontEnd pcm8k = usePcmFrontEnd ? new PcmFrontEnd() : null;
                final HighQualityResampler captureResampler = (frontEnd == FRONT_DSTAR_PDC && captureRate != SR) ? new HighQualityResampler(captureRate, SR) : null;
                int outAcc = 0;
                boolean first = true;
                try {
                    while (micRun) {
                        try {
                            AudioRecord r = rec;
                            int n = r == null ? -1 : r.read(in, 0, in.length);
                            MicCb call = micCb;
                            MicRateCb rateCall = micRateCb;
                            if (n > 0 && (call != null || rateCall != null)) {
                                if (captureRate == CAPTURE_SR) {
                                    if (first) { first = false; android.util.Log.i("ARCH", "AudioEngine mic first 48k frame"); }
                                    if (usePcmFrontEnd && pcm8k != null) {
                                        short[] frame = (n == in.length) ? pcm8k.process(in, 48000) : pcm8k.process(java.util.Arrays.copyOf(in, n), 48000);
                                        if (frame != null) emitMicFrame(call, rateCall, frame, captureRate);
                                    } else {
                                        if (n == in.length) emitMicFrame(call, rateCall, in, captureRate);
                                        else emitMicFrame(call, rateCall, java.util.Arrays.copyOf(in, n), captureRate);
                                    }
                                } else if (captureResampler != null) {
                                    short[] frame = captureResampler.process(n == in.length ? in : java.util.Arrays.copyOf(in, n));
                                    if (frame != null) {
                                        for (int k = 0; k < frame.length; k++) {
                                            out8[outAcc++] = frame[k];
                                            if (outAcc == 160) {
                                                if (first) { first = false; android.util.Log.i("ARCH", "AudioEngine mic first 24k->8k frame"); }
                                                emitMicFrame(call, rateCall, out8, captureRate);
                                                outAcc = 0;
                                            }
                                        }
                                    }
                                } else {
                                    for (int k = 0; k < n; k++) {
                                        out8[outAcc++] = in[k];
                                        if (outAcc == 160) {
                                            if (first) { first = false; android.util.Log.i("ARCH", "AudioEngine mic first 8k frame"); }
                                            emitMicFrame(call, rateCall, out8, captureRate);
                                            outAcc = 0;
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            android.util.Log.e("ARCH", "AudioEngine mic read failed", e);
                            if (micRun) try { Thread.sleep(20); } catch (InterruptedException ie) { break; }
                        }
                    }
                } finally {
                    if (pcm8k != null) pcm8k.close();
                }
            }, "mic");
            th.start();
        } catch (Throwable t) {
            android.util.Log.e("ARCH", "AudioEngine microphone create failed", t);
            stopMic();
        }
    }

    private void emitMicFrame(MicCb call, MicRateCb rateCall, short[] pcm, int sampleRate) {
        short[] frame = pcm;
        if (!dstarRawMic) {
            float gain = micGain;
            if (frame != null && (gain != 1.0f || agc)) {
                frame = frame.clone();
                for (int i = 0; i < frame.length; i++) {
                    int v = Math.round(frame[i] * gain);
                    frame[i] = (short)(v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                }
            }
            if (frame != null && agc) micAgc.process(frame);
        }
        AudioLevelBus.pushTx(frame);
        if (rateCall != null) rateCall.onFrame(frame, sampleRate);
        else if (call != null) call.onFrame(frame);
    }

    private void releaseRecordAsync(final AudioRecord r) {
        if (r == null) return;
        new Thread(() -> {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
        }, "audio-record-release").start();
    }

    private void releaseTrackAsync(final AudioTrack t) {
        if (t == null) return;
        new Thread(() -> {
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }, "audio-track-release").start();
    }

    public synchronized void stopMic() {
        micCb = null;
        micRateCb = null;
        micRun = false;
        dstarRawMic = false;
        txLimitGain = 1f;
        AudioRecord r = rec;
        rec = null;
        Thread t = th;
        th = null;
        if (t != null) t.interrupt();
        releaseEffectsAsync();
        releaseRecordAsync(r);
    }

    private void releaseEffectsAsync() {
        final AutomaticGainControl agc = agcFx;
        final NoiseSuppressor ns = nsFx;
        final AcousticEchoCanceler aec = aecFx;
        agcFx = null;
        nsFx = null;
        aecFx = null;
        if (agc == null && ns == null && aec == null) return;
        new Thread(() -> {
            try { if (ns != null) { ns.setEnabled(false); ns.release(); } } catch (Throwable ignored) {}
            try { if (agc != null) { agc.setEnabled(false); agc.release(); } } catch (Throwable ignored) {}
            try { if (aec != null) { aec.setEnabled(false); aec.release(); } } catch (Throwable ignored) {}
        }, "audio-effect-release").start();
    }

    /** 软限幅：~-2.7dBFS 起软削，保留语音动态、避免硬削产生数码沙哑声。 */
    public static void softLimit(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        final float threshold = 22000f, ceiling = 30700f, scale = ceiling - threshold;
        int peak = 0;
        for (short smp : pcm) { int a = smp < 0 ? -smp : smp; if (a > peak) peak = a; }
        if (peak <= threshold) return;
        for (int i = 0; i < pcm.length; i++) {
            float x = pcm[i];
            float a = x < 0 ? -x : x;
            if (a > threshold) {
                float over = (a - threshold) / (32768f - threshold);
                a = threshold + scale * (over / (1f + over));
                x = x < 0 ? -a : a;
            }
            if (x > 32767f) x = 32767f; else if (x < -32768f) x = -32768f;
            pcm[i] = (short) x;
        }
    }

    public void setVoiceEffects(boolean on) { voiceEffects = on; }

 /** 播放一路 8k PCM：按需做降噪 / AGC / 固定增益。 */
    public void play(short[] pcm) { play(pcm, 1.0f); }

    /** DMR/BM 接收专用：保留用户接收音量、AGC 和降噪，再额外补偿 2.5 倍。 */
    public void playDmr(short[] pcm) { play(pcm, DMR_RX_EXTRA_GAIN); }

    public synchronized void play(short[] pcm, float extraGain) {
        AudioTrack t = trk;
        if (!run || t == null || pcm == null || pcm.length == 0) return;
        if (!denoise && !agc && spkGain == 1.0f && extraGain == 1.0f) {
            AudioLevelBus.pushRx(pcm);
            try { t.write(pcm, 0, pcm.length); } catch (Throwable ignored) {}
            return;
        }
        short[] o = new short[pcm.length];
        float g = spkGain;
        for (int i = 0; i < pcm.length; i++) {
            float x = pcm[i];
            if (denoise) x = denoiseSample(x, i);
            if (agc) x = agcSample(x);
            else if (g != 1.0f) x = x * g;
            if (extraGain != 1.0f) x = x * extraGain;
            if (x > 32767f) x = 32767f; else if (x < -32768f) x = -32768f;
            o[i] = (short) x;
        }
        softLimit(o);
        AudioLevelBus.pushRx(o);
        try { t.write(o, 0, o.length); } catch (Throwable ignored) {}
    }

 /** P25/NXDN/YSF 原始 PCM：应用固定增益，并在启用时应用 AGC。 */
    public synchronized void playRaw(short[] pcm) {
        AudioTrack t = trk;
        if (!run || t == null || pcm == null || pcm.length == 0) return;
        short[] out = applySpkGain(pcm);
        if (agc) { if (out == pcm) out = pcm.clone(); rawAgc.process(out); }
        AudioLevelBus.pushRx(out);
        try { t.write(out, 0, out.length); } catch (Throwable ignored) {}
    }

    /** FMO 回放：应用固定增益，并在启用时应用 AGC。 */
    /** FMO 回放直通：播放器未就绪时自动启动，避免"收得到却不出声"。 */
    public synchronized void playFmoRaw(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        AudioTrack t = trk;
        if (!run || t == null) { if (!start()) return; t = trk; }
        if (t == null) return;
        short[] out = applySpkGain(pcm);
        if (agc) { if (out == pcm) out = pcm.clone(); rawAgc.process(out); }
        AudioLevelBus.pushRx(out);
        try { t.write(out, 0, out.length); } catch (Throwable ignored) {}
    }

    private short[] applySpkGain(short[] pcm) {
        float g = spkGain;
        if (g == 1.0f) return pcm;
        short[] out = pcm.clone();
        for (int i = 0; i < out.length; i++) {
            int v = Math.round(out[i] * g);
            out[i] = (short)(v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
        return out;
    }

    /** 供界面显示：播放器是否真的可以出声。 */
    public boolean isReady() { return run && trk != null; }

    private void limitTxFrame(short[] pcm) { applyLimiter(pcm, TX_LIMIT_PEAK, true); }
    private void limitRxFrame(short[] pcm) { applyLimiter(pcm, RX_LIMIT_PEAK, false); }

    private void applyLimiter(short[] pcm, float peakLimit, boolean tx) {
        if (pcm == null || pcm.length == 0) return;
        int peak = 0;
        for (short sample : pcm) {
            int a = sample < 0 ? -sample : sample;
            if (a > peak) peak = a;
        }
        float gain = tx ? txLimitGain : rxLimitGain;
        float target = peak > peakLimit ? peakLimit / peak : 1f;
        if (target < gain) gain = target;
        else gain += LIMIT_RELEASE * (target - gain);
        if (gain < 0.05f) gain = 0.05f;
        if (tx) txLimitGain = gain; else rxLimitGain = gain;
        if (gain < 0.999f) {
            for (int i = 0; i < pcm.length; i++) {
                int v = Math.round(pcm[i] * gain);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                pcm[i] = (short) v;
            }
        }
    }

 /** 降噪：一阶高通去低频 + 自适应底噪噪声门。 */
    private float denoiseSample(float x, int idx) {
        dcY += 0.02f * (x - dcY);
        float hp = x - dcY;
        float a = Math.abs(hp);
        env += (a > env ? 0.30f : 0.004f) * (a - env);
        if (env < noiseFloor * 1.6f) noiseFloor += 0.0015f * (env - noiseFloor);
        if (noiseFloor < 80f) noiseFloor = 80f;
        float open = noiseFloor * 2.2f, close = noiseFloor * 1.25f;
        if (env > open) gateTarget = 1f;
        else if (env < close) gateTarget = 0f;
        gate += (gateTarget - gate) * (gateTarget > gate ? 0.55f : 0.02f);
        return hp * gate;
    }

 /** AGC：把语音包络拉向目标电平，慢起慢落避免抽气。 */
    private float agcSample(float x) {
        float a = Math.abs(x);
        agcEnv += (a > agcEnv ? 0.05f : 0.0008f) * (a - agcEnv);
        float want = agcEnv < 1f ? 1f : AGC_TARGET / agcEnv;
        if (want > 10f) want = 10f; else if (want < 0.1f) want = 0.1f;
        agcGain += (want - agcGain) * 0.05f;
        return x * agcGain;
    }


    public static float dbToLinear(int db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    public void setRxGainDb(int db) { spkGain = dbToLinear(db); }
    public void setTxGainDb(int db) { micGain = dbToLinear(db); }
    public void setAgc(boolean on) { agc = on; agcEnv = 0f; agcGain = 1f; rawAgc.reset(); micAgc.reset(); dstarAgc.reset(); }
    public void setDenoise(boolean on) { denoise = on; env = 0f; noiseFloor = 250f; gate = 1f; gateTarget = 1f; }

    public void setMicGain(float g) { micGain = g; }
    public void setSpkGain(float g) { spkGain = g; }
    public void muteSpk(boolean m) {
        AudioTrack t = trk;
        if (t != null) { try { t.setVolume(m ? 0f : 1f); } catch (Exception ignored) {} }
        AudioTrack d = dstarTrk;
        if (d != null) { try { d.setVolume(m ? 0f : 1f); } catch (Exception ignored) {} }
    }

    public synchronized void stop() {
        stopMic();
        run = false;
        AudioTrack t = trk;
        trk = null;
        releaseTrackAsync(t);
        stopDstar();
    }
}
