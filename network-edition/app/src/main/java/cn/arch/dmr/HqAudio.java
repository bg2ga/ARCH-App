package cn.arch.dmr;

import android.media.*;
import android.os.Build;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 48 kHz mono network-room audio. Hardware lifecycle is serialized and fully closed before reopen. */
final class HqAudio {
    private final int playbackSampleRate, playbackFrameSamples;
    private final int recordSampleRate, recordFrameSamples;
    private volatile int streamType = AudioManager.STREAM_MUSIC;
    private volatile float outputVolume = 1f;
    private volatile boolean txMuted;
    public interface MicCb { void onFrame(short[] pcmFrame); }

    HqAudio() { this(48000, 24000); }
    void setStreamType(int type) {
        if (streamType == type) return;
        streamType = type;
        // A PCM AudioTrack is bound to its stream type at construction. If
        // the route changes, discard the old track so the next receive frame
        // is opened on the correct PNC370/PNC380 path.
        if (playRun || trk != null) stopPlayback();
    }
    int getStreamType() { return streamType; }
    void setWarmMic(boolean on) { warmMic = on; }
    void setPreferredInputDevice(AudioDeviceInfo device) { preferredInputDevice = device; }
    HqAudio(int sampleRate) { this(sampleRate, sampleRate); }
    HqAudio(int playbackRate, int recordRate) {
        this.playbackSampleRate = playbackRate > 0 ? playbackRate : 48000;
        this.playbackFrameSamples = this.playbackSampleRate / 50;
        this.recordSampleRate = recordRate > 0 ? recordRate : 24000;
        this.recordFrameSamples = this.recordSampleRate / 50;
    }

    private volatile AudioRecord rec;
    private volatile AudioTrack trk;
    private volatile Thread micThread;
    private volatile Thread playThread;
    private volatile boolean warmMic;
    private volatile boolean playRun;
    private volatile boolean micRun;
    private volatile long lastMicFrameAt;
    private volatile AudioDeviceInfo preferredInputDevice;
    private volatile MicCb micCb;
    private volatile float rxGain = 1f;
    private volatile float txGain = 1f;
    private volatile boolean agcEnabled = false;
    private volatile boolean externalPacing = false;
    private final DigitalAgc rxAgc = new DigitalAgc();
    private final DigitalAgc txAgc = new DigitalAgc();
    private final LinkedBlockingQueue<short[]> queue = new LinkedBlockingQueue<>(20);
    private final LinkedBlockingQueue<short[]> prestartQueue = new LinkedBlockingQueue<>(12);
    private final LinkedBlockingQueue<short[]> realtimeQueue = new LinkedBlockingQueue<>(5);
    private final Object realtimeLock = new Object();
    private volatile Thread realtimeThread;
    private volatile boolean realtimeRun;
    private volatile int realtimeGeneration;
    private final ExecutorService shutdownIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arch-hq-audio-stop");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastPlayWriteAt;
    private volatile int directFadeRemaining;
    private long droppedRxFrames;

    /** Start only the receive player. It is kept alive for the whole room session. */
    synchronized boolean startPlayback() {
        if (playRun && trk != null && playThread != null && playThread.isAlive()) return true;
        discardStalePlayback();
        droppedRxFrames = 0;

        AudioTrack created = null;
        try {
            int bo = Math.max(AudioTrack.getMinBufferSize(
                    playbackSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), playbackFrameSamples * 2 * 4);
            created = new AudioTrack(streamType, playbackSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bo, AudioTrack.MODE_STREAM);
            if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                created.release();
                return false;
            }
            final AudioTrack track = created;
            trk = track;
            playRun = true;
            track.setVolume(effectiveOutputVolume());
            track.play();
            drainPrestartQueue();
            playThread = new Thread(() -> {
                short[] lastFrame = null;
                int plcRemaining = 0;
                long lastPacketAt = 0L;
                long startupAt = 0L;
                long nextTick = System.currentTimeMillis();
                int fadeInSamples = 0;
                try {
                    while (playRun && trk == track) {
                        long now = System.currentTimeMillis();
                        short[] f = externalPacing ? queue.take() : queue.poll();
                        if (f == null && lastPacketAt == 0L) {
                            if (startupAt == 0L) startupAt = now;
                            if (queue.size() < 6 && now - startupAt < 200L) {
                                Thread.sleep(20L);
                                continue;
                            }
                        }
                        if (f == null) {
                            long idle = lastPacketAt == 0L ? Long.MAX_VALUE : now - lastPacketAt;
                            if (lastPacketAt > 0L && idle >= 220L) {
                                // Do not leave a streaming AudioTrack playing with no data:
                                // several phone HALs turn that underrun into a continuous buzz.
                                playRun = false;
                                lastPlayWriteAt = 0L;
                                try { track.pause(); track.flush(); track.stop(); track.release(); } catch (Throwable ignored) {}
                                if (trk == track) trk = null;
                                break;
                            }
                            if (idle >= 100L) lastPlayWriteAt = 0L;
                            if (idle < 100L && plcRemaining > 0 && lastFrame != null) {
                                int index = 1 - plcRemaining;
                                float gain = Math.max(0.12f, 0.72f - index * 0.14f);
                                f = new short[playbackFrameSamples];
                                int copy = Math.min(Math.min(lastFrame.length, f.length), f.length);
                                for (int i = 0; i < copy; i++) f[i] = (short)Math.round(lastFrame[i] * gain);
                                plcRemaining--;
                            } else {
                                long wait = nextTick - System.currentTimeMillis();
                                if (wait > 0L) Thread.sleep(Math.min(wait, 20L));
                                nextTick = Math.max(System.currentTimeMillis(), nextTick + 20L);
                                continue;
                            }
                        } else {
                            if (lastFrame == null) fadeInSamples = Math.max(1, playbackFrameSamples / 2);
                            lastFrame = f.clone();
                            lastPacketAt = now;
                            plcRemaining = 1;
                            startupAt = 0L;
                        }
                        if (!playRun || trk != track) break;
                        if (fadeInSamples > 0) {
                            int apply = Math.min(fadeInSamples, f.length);
                            for (int i = 0; i < apply; i++) {
                                float ramp = (float) (i + 1) / (float) Math.max(1, playbackFrameSamples / 2);
                                f[i] = (short)Math.round(f[i] * Math.min(1f, ramp));
                            }
                            fadeInSamples -= apply;
                        }
                        float g = rxGain;
                        if (g != 1f) applyGain(f, g);
                        if (agcEnabled) rxAgc.process(f);
                        AudioLevelBus.pushRx(f);
                        if (!playRun || trk != track) break;
                        if (!writePlaybackFrame(track, f)) break;
                        lastPlayWriteAt = System.currentTimeMillis();
                        if (!externalPacing) {
                            nextTick = Math.max(System.currentTimeMillis(), nextTick + 20L);
                            long wait = nextTick - System.currentTimeMillis();
                            if (wait > 0L) Thread.sleep(Math.min(wait, 20L));
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    if (!playRun || trk != track) return;
                    android.util.Log.w("ARCH-AUDIO", "playback loop error: " + t);
                } finally {
                    // A failed/partial AudioTrack write must not leave playRun,
                    // playThread and trk pointing at a dead streaming session.
                    releaseOwnedPlaybackTrack(track);
                }
            }, "hq-play");
            playThread.start();
            return true;
        } catch (Throwable t) {
            stopPlayback();
            return false;
        }
    }

    /** Start recording; null only closes the microphone and keeps receive playback alive. */
    @android.annotation.SuppressLint("MissingPermission")
    synchronized boolean startMic(MicCb cb) {
        if (cb == null) {
            stopMic();
            return true;
        }
        if (micRun && rec != null && micThread != null && micThread.isAlive()) {
            long last = lastMicFrameAt;
            // 黑屏/切路由后 AudioRecord 有时会活着但不再出帧；新一次 PTT 必须重建。
            if (last == 0L || System.currentTimeMillis() - last < 1500L) {
                micCb = cb;
                return true;
            }
            android.util.Log.w("ARCH", "AudioRecord stalled, recreate microphone");
        }
        stopMicInternal(true);

        AudioRecord created = null;
        try {
            int bi = Math.max(AudioRecord.getMinBufferSize(
                    recordSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), recordFrameSamples * 2 * 6);
            // PNC380's audio HAL can fail start_input_stream for 24 kHz
            // VOICE_COMMUNICATION (pcm_prepare -1). Raw MIC is the stable
            // high-rate capture source; keep the other sources as fallbacks.
            int[] sources = recordSampleRate >= 24000
                    ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC}
                    : new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION};
            // [3.2.41] 有些机型在别的录音已占用时 AudioRecord 能初始化但 startRecording 不生效，
            // 结果是“授予了发射权却一帧都发不出去”。这里逐个音源真正启动并校验状态。
            for (int source : sources) {
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(source,
                            recordSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bi);
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                        candidate.release();
                        continue;
                    }
                    AudioDeviceInfo preferred = preferredInputDevice;
                    if (preferred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { candidate.setPreferredDevice(preferred); } catch (Throwable ignored) {}
                    }
                    candidate.startRecording();
                    if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        candidate.release();
                        continue;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            AudioDeviceInfo routed = candidate.getRoutedDevice();
                            android.util.Log.i("ARCH-AUDIO", "network mic routed id=" + (routed == null ? -1 : routed.getId())
                                    + " type=" + (routed == null ? -1 : routed.getType())
                                    + " product=" + (routed == null ? "null" : routed.getProductName()));
                        } catch (Throwable ignored) {}
                    }
                    created = candidate;
                    android.util.Log.i("ARCH", "hq-mic source=" + source
                            + " rate=" + recordSampleRate + " frame=" + recordFrameSamples);
                    break;
                } catch (Throwable t) {
                    if (candidate != null) try { candidate.release(); } catch (Throwable ignored) {}
                }
            }
            if (created == null) return false;
            final AudioRecord record = created;
            rec = record;
            micCb = cb;
            micRun = true;
            lastMicFrameAt = System.currentTimeMillis();
            micThread = new Thread(() -> {
                short[] chunk = new short[recordFrameSamples];
                short[] frame = new short[recordFrameSamples];
                int fill = 0;
                int reads = 0;
                int emitted = 0;
                try {
                    while (micRun && rec == record) {
                        try {
                            int n = record.read(chunk, 0, chunk.length);
                            if (n <= 0) continue;
                            lastMicFrameAt = System.currentTimeMillis();
                            if (micCb == null) { fill = 0; continue; }
                            if (reads++ < 4) {
                                android.util.Log.i("ARCH-AUDIO", "mic read n=" + n + " target=" + recordFrameSamples);
                            }
                            int offset = 0;
                            while (offset < n && micRun && rec == record) {
                                int copy = Math.min(frame.length - fill, n - offset);
                                System.arraycopy(chunk, offset, frame, fill, copy);
                                fill += copy;
                                offset += copy;
                                if (fill != frame.length) continue;
                                short[] txFrame = resampleFrame(frame, recordSampleRate, playbackSampleRate);
                                float g = txGain;
                                if (g != 1f) applyGain(txFrame, g);
                                if (agcEnabled) txAgc.process(txFrame);
                                AudioLevelBus.pushTx(txFrame);
                                MicCb callback = micCb;
                                if (callback != null) callback.onFrame(txFrame.clone());
                                if (emitted++ == 0) {
                                    android.util.Log.i("ARCH-AUDIO", "mic first complete frame");
                                }
                                fill = 0;
                            }
                        } catch (Throwable t) {
                            android.util.Log.w("ARCH-AUDIO", "mic read error, retrying: " + t);
                            try { Thread.sleep(50L); } catch (InterruptedException ie) { return; }
                        }
                    }
                } finally {
                    if (rec == record) micRun = false;
                }
            }, "hq-mic");
            micThread.start();
            return true;
        } catch (Throwable t) {
            stopMic();
            return false;
        }
    }

    void play(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (!playRun || trk == null || playThread == null || !playThread.isAlive()) {
            if (playRun || trk != null) stopPlayback();
            if (!prestartQueue.offer(pcm.clone())) {
                prestartQueue.poll(); prestartQueue.offer(pcm.clone());
            }
            startPlayback();
            return;
        }
        if (!queue.offer(pcm)) {
            queue.poll(); queue.offer(pcm); droppedRxFrames++;
            android.util.Log.w("ARCH-AUDIO", "rx queue dropped one frame total=" + droppedRxFrames);
        }
    }

    /**
     * Network receive path: enqueue one already-paced 20 ms PCM frame.
     * AudioTrack is owned by a dedicated worker so a HAL stall can never block
     * NetworkRoomClient's receive clock or the Runtime handoff lock.
     */
    void playRealtime(short[] pcm) {
        if (txMuted) return;
        if (pcm == null || pcm.length == 0) return;
        short[] frame = pcm.clone();
        synchronized (realtimeLock) {
            ensureRealtimeWorkerLocked();
            if (!realtimeQueue.offer(frame)) {
                realtimeQueue.poll();
                realtimeQueue.offer(frame);
                droppedRxFrames++;
                android.util.Log.w("ARCH-AUDIO", "realtime queue dropped one frame total=" + droppedRxFrames);
            }
        }
    }

    private void ensureRealtimeWorkerLocked() {
        Thread t = realtimeThread;
        if (t != null && t.isAlive()) {
            realtimeRun = true;
            return;
        }
        realtimeRun = true;
        final int generation = ++realtimeGeneration;
        t = new Thread(() -> realtimePlaybackLoop(generation), "arch-network-audio-out");
        t.setDaemon(true);
        realtimeThread = t;
        t.start();
    }

    private void realtimePlaybackLoop(int generation) {
        AudioTrack owned = null;
        try {
            while (realtimeRun && generation == realtimeGeneration) {
                short[] frame = realtimeQueue.poll(250L, TimeUnit.MILLISECONDS);
                if (frame == null || !realtimeRun || txMuted) continue;
                AudioTrack track;
                synchronized (HqAudio.this) {
                    track = ensureRealtimeTrackLocked();
                }
                if (track == null) break;
                owned = track;
                if (directFadeRemaining > 0) {
                    int apply = Math.min(directFadeRemaining, frame.length);
                    for (int i = 0; i < apply; i++) {
                        float ramp = (float)(i + 1) / (float)Math.max(1, playbackFrameSamples / 2);
                        frame[i] = (short)Math.round(frame[i] * Math.min(1f, ramp));
                    }
                    directFadeRemaining -= apply;
                }
                float gain = rxGain;
                if (gain != 1f) applyGain(frame, gain);
                if (agcEnabled) rxAgc.process(frame);
                AudioLevelBus.pushRx(frame);
                if (!writePlaybackFrame(track, frame)) {
                    android.util.Log.w("ARCH-AUDIO", "realtime playback writer stopped");
                    break;
                }
                lastPlayWriteAt = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "realtime playback worker error: " + t);
        } finally {
            // Do not take realtimeLock here: primeRealtime holds the HqAudio
            // monitor while entering realtimeLock, and this worker may need the
            // HqAudio monitor below. Keeping ownership flags volatile avoids a
            // lock-order inversion during route changes.
            if (generation == realtimeGeneration) {
                if (realtimeThread == Thread.currentThread()) realtimeThread = null;
                realtimeRun = false;
                realtimeQueue.clear();
            }
            releaseOwnedPlaybackTrack(owned);
        }
    }

    /** Caller must hold this monitor. */
    private AudioTrack ensureRealtimeTrackLocked() {
        // Re-check under the audio lock: a receive frame may have passed
        // the worker check just before PTT set txMuted.
        if (txMuted) return null;
        AudioTrack existing = trk;
        if (playRun && existing != null) return existing;
        discardStalePlayback();
        AudioTrack track = null;
        try {
            int buffer = Math.max(AudioTrack.getMinBufferSize(
                    playbackSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    playbackFrameSamples * 2 * 6);
            track = new AudioTrack(streamType, playbackSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buffer, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return null;
            }
            trk = track;
            playRun = true;
            directFadeRemaining = Math.max(1, playbackFrameSamples / 2);
            track.setVolume(effectiveOutputVolume());
            track.play();
            return track;
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "realtime track open failed: " + t);
            try { if (track != null) track.release(); } catch (Throwable ignored) {}
            if (trk == track) trk = null;
            playRun = false;
            return null;
        }
    }

    private void releaseOwnedPlaybackTrack(AudioTrack track) {
        if (track == null) return;
        boolean owner;
        synchronized (HqAudio.this) {
            owner = trk == track;
            if (owner) {
                trk = null;
                playRun = false;
                lastPlayWriteAt = 0L;
                if (playThread == Thread.currentThread()) playThread = null;
            }
        }
        if (!owner) return;
        try { track.pause(); } catch (Throwable ignored) {}
        try { track.flush(); } catch (Throwable ignored) {}
        try { track.stop(); } catch (Throwable ignored) {}
        try { track.release(); } catch (Throwable ignored) {}
    }

    void clearPlaybackQueue() { queue.clear(); prestartQueue.clear(); realtimeQueue.clear(); lastPlayWriteAt = 0L; directFadeRemaining = 0; }

    /** Discard stale hardware before creating a fresh talkspurt player. */
    private void discardStalePlayback() {
        playRun = false;
        queue.clear();
        prestartQueue.clear();
        AudioTrack oldTrack = trk;
        trk = null;
        Thread oldThread = playThread;
        playThread = null;
        if (oldTrack != null) {
            try { oldTrack.stop(); } catch (Throwable ignored) {}
            try { oldTrack.release(); } catch (Throwable ignored) {}
        }
        if (oldThread != null && oldThread.isAlive() && oldThread != Thread.currentThread()) {
            oldThread.interrupt();
            try { oldThread.join(120L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drainPrestartQueue() {
        short[] pending;
        while ((pending = prestartQueue.poll()) != null) {
            if (!queue.offer(pending)) break;
        }
    }

    /** Set an explicit AudioTrack gain for vendor HALs that ignore stream changes. */
    void setOutputVolume(float volume) {
        float value = Math.max(0f, Math.min(1f, volume));
        outputVolume = value;
        AudioTrack track = trk;
        if (track != null) {
            try { track.setVolume(effectiveOutputVolume()); } catch (Throwable ignored) {}
        }
        android.util.Log.i("ARCH-VOL", "track volume=" + value + " muted=" + txMuted
                + " active=" + (track != null));
    }

    /** Mute the network speaker during PNC380 PTT without pausing the AudioTrack. */
    void setTxMuted(boolean muted) {
        txMuted = muted;
        AudioTrack track = trk;
        if (track != null) {
            try { track.setVolume(effectiveOutputVolume()); } catch (Throwable ignored) {}
        }
        android.util.Log.i("ARCH-AUDIO", "PNC380 TX mute=" + muted
                + " trackActive=" + (track != null));
    }

    private float effectiveOutputVolume() {
        return txMuted ? 0f : outputVolume;
    }
    /** Open the direct network AudioTrack before a call arrives. */
    synchronized boolean primeRealtime() {
        if (playRun && trk != null && playThread == null) {
            synchronized (realtimeLock) { ensureRealtimeWorkerLocked(); }
            return true;
        }
        discardStalePlayback();
        AudioTrack track = null;
        try {
            int bo = Math.max(AudioTrack.getMinBufferSize(
                    playbackSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    playbackFrameSamples * 2 * 6);
            track = new AudioTrack(streamType, playbackSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bo, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return false;
            }
            trk = track;
            playRun = true;
            directFadeRemaining = Math.max(1, playbackFrameSamples / 2);
            track.setVolume(effectiveOutputVolume());
            track.play();
            lastPlayWriteAt = 0L;
            synchronized (realtimeLock) { ensureRealtimeWorkerLocked(); }
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "realtime playback open failed: " + t);
            try { if (track != null) track.release(); } catch (Throwable ignored) {}
            trk = null;
            playRun = false;
            return false;
        }
    }
    void setRxGain(float gain) { rxGain = Math.max(0.03f, Math.min(8f, gain)); }
    void setTxGain(float gain) { txGain = Math.max(0.03f, Math.min(8f, gain)); }
    void setExternalPacing(boolean on) { externalPacing = on; }
    void setAgc(boolean on, int targetDb) {
        agcEnabled = on;
        rxAgc.setTargetDb(targetDb);
        txAgc.setTargetDb(targetDb);
        rxAgc.reset();
        txAgc.reset();
    }

    private boolean writePlaybackFrame(AudioTrack track, short[] pcm) {
        int offset = 0;
        long noProgressSince = android.os.SystemClock.elapsedRealtime();
        try {
            while (offset < pcm.length && playRun && trk == track) {
                int written;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    written = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                } else {
                    written = track.write(pcm, offset, pcm.length - offset);
                }
                if (written > 0) {
                    offset += written;
                    noProgressSince = android.os.SystemClock.elapsedRealtime();
                    continue;
                }
                if (written == 0) {
                    if (android.os.SystemClock.elapsedRealtime() - noProgressSince >= 120L) {
                        android.util.Log.w("ARCH-AUDIO", "playback write stalled for 120ms");
                        return false;
                    }
                    Thread.sleep(2L);
                    continue;
                }
                android.util.Log.w("ARCH-AUDIO", "playback write failed code=" + written);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "playback write error: " + t);
            return false;
        }
        return offset > 0;
    }

    private static short[] resampleFrame(short[] input, int inRate, int outRate) {
        if (input == null || input.length == 0 || inRate <= 0 || outRate <= 0 || inRate == outRate) return input;
        int outLen = Math.max(1, (int)Math.round(input.length * (double)outRate / (double)inRate));
        short[] out = new short[outLen];
        if (outLen == 1) { out[0] = input[0]; return out; }
        double step = (input.length - 1) / (double)(outLen - 1);
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int)pos;
            int i1 = Math.min(input.length - 1, i0 + 1);
            double frac = pos - i0;
            out[i] = (short)Math.round(input[i0] * (1.0 - frac) + input[i1] * frac);
        }
        return out;
    }

    private static void applyGain(short[] pcm, float gain) {
        for (int i = 0; i < pcm.length; i++) {
            int v = Math.round(pcm[i] * gain);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            pcm[i] = (short) v;
        }
    }

    boolean isMicOn() { return micRun; }
    boolean isPlaybackRunning() { Thread t = playThread; return playRun && trk != null && t != null && t.isAlive(); }

    boolean isMicHealthy() {
        try {
            AudioRecord record = rec;
            Thread thread = micThread;
            return micRun && record != null && thread != null && thread.isAlive()
                    && lastMicFrameAt > 0L
                    && System.currentTimeMillis() - lastMicFrameAt < 2500L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    synchronized boolean restartMic() {
        MicCb cb = micCb;
        if (cb == null) return false;
        stopMicInternal(true);
        return startMic(cb);
    }

    synchronized void stopMic() {
        micCb = null;
        // PNC380 vendor AudioRecord.stop() can deadlock in the audio HAL. Keep the
        // recorder warm between PTT presses; only the callback is detached.
        if (warmMic && micRun && rec != null && micThread != null && micThread.isAlive()) {
            return;
        }
        stopMicInternal(false);
    }

    /** Release the warm PTT microphone before another mode opens AudioRecord. */
    synchronized boolean releaseMicForModeSwitch() {
        AudioRecord record = rec;
        Thread thread = micThread;
        if (record == null && thread == null && !micRun) return true;
        long t0 = System.currentTimeMillis();
        micCb = null;
        micRun = false;
        rec = null;
        micThread = null;
        if (thread != null) thread.interrupt();
        // Do not call AudioRecord.stop() here: PNC380's vendor HAL can block in stop().
        // Releasing the recorder closes the input session and lets DMR restart capture.
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(700L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (thread.isAlive() && record != null) try { record.stop(); } catch (Throwable ignored) {}
            try { thread.join(1000L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        releaseRecordWhenThreadDead(record, thread);
        android.util.Log.i("ARCH-AUDIO", "releaseMicForModeSwitch took="
                + (System.currentTimeMillis() - t0) + "ms");
        return true;
    }

    /** Caller must hold the HqAudio monitor. */
    private void stopMicInternal(boolean forceStop) {
        long t0 = System.currentTimeMillis();
        micCb = null;
        micRun = false;
        AudioRecord record = rec;
        rec = null;
        Thread thread = micThread;
        micThread = null;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(300L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (record != null) try { record.stop(); } catch (Throwable ignored) {}
        if (record != null) try { record.release(); } catch (Throwable ignored) {}
        long dt = System.currentTimeMillis() - t0;
        if (dt > 200) android.util.Log.w("ARCH", "HqAudio.stopMic took " + dt + "ms");
    }

    private void releaseTrackWhenThreadDead(final AudioTrack track, Thread thread) {
        if (track == null) return;
        if (thread == null || !thread.isAlive() || thread == Thread.currentThread()) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
            return;
        }
        new Thread(() -> {
            try { thread.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }, "audio-track-release").start();
    }

    private void releaseRecordWhenThreadDead(final AudioRecord record, Thread thread) {
        if (record == null) return;
        if (thread == null || !thread.isAlive() || thread == Thread.currentThread()) {
            try { record.release(); } catch (Throwable ignored) {}
            return;
        }
        new Thread(() -> {
            try { thread.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            try { record.release(); } catch (Throwable ignored) {}
        }, "audio-record-release").start();
    }

    synchronized void stopPlayback() {
        long t0 = System.currentTimeMillis();
        playRun = false;
        lastPlayWriteAt = 0L;
        directFadeRemaining = 0;
        queue.clear();
        prestartQueue.clear();
        realtimeQueue.clear();
        AudioTrack track = trk;
        trk = null;
        Thread thread = playThread;
        playThread = null;
        Thread real = realtimeThread;
        realtimeThread = null;
        realtimeRun = false;
        realtimeGeneration++;
        if (real != null) real.interrupt();
        // Do not touch AudioTrack here: an active write() thread may own a
        // proxy buffer. releaseTrackWhenThreadDead() releases only after the
        // writer exits, preventing Android 7.1 ClientProxy assertions.
        if (thread != null) {
            thread.interrupt();
            try { thread.join(300L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        Thread releaseOwner = thread != null && thread.isAlive() ? thread : real;
        releaseTrackWhenThreadDead(track, releaseOwner);
        long dt = System.currentTimeMillis() - t0;
        if (dt > 200) android.util.Log.w("ARCH", "HqAudio.stopPlayback took " + dt + "ms");
    }

    /**
     * PNC380 release: only signal the realtime worker. AudioTrack.pause(),
     * flush(), stop() and release() are forbidden here because Android 7.1
     * aborts when another thread manipulates AudioTrack during write().
     * The worker releases its own track in realtimePlaybackLoop.finally.
     */
    void stopPlaybackForPnc380() {
        long started = System.currentTimeMillis();
        AudioTrack track;
        Thread legacy;
        Thread realtime;
        synchronized (this) {
            playRun = false;
            lastPlayWriteAt = 0L;
            directFadeRemaining = 0;
            queue.clear();
            prestartQueue.clear();
            realtimeQueue.clear();
            track = trk;
            legacy = playThread;
            playThread = null;
            realtime = realtimeThread;
            realtimeThread = null;
            realtimeRun = false;
            realtimeGeneration++;
        }
        if (legacy != null) legacy.interrupt();
        if (realtime != null) realtime.interrupt();
        if (legacy != null && legacy != Thread.currentThread()) {
            try { legacy.join(500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (realtime != null && realtime != Thread.currentThread()) {
            try { realtime.join(1500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        boolean releaseHere = false;
        synchronized (this) {
            boolean writerAlive = realtime != null && realtime.isAlive();
            if (track != null && trk == track && !writerAlive) {
                trk = null;
                playRun = false;
                releaseHere = true;
            }
        }
        if (releaseHere && track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
        if (realtime != null && realtime.isAlive()) {
            android.util.Log.w("ARCH-AUDIO", "PNC380 audio writer still exiting; track ownership left to worker");
        }
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed > 200L) android.util.Log.w("ARCH-AUDIO", "PNC380 playback release took " + elapsed + "ms");
    }

    synchronized void stop() {
        warmMic = false;
        stopMicInternal(true);
        stopPlayback();
    }

    /** Lifecycle-safe variant: never block Activity/Service teardown on a vendor HAL. */
    void stopAsync() {
        try {
            shutdownIo.execute(() -> {
                try { stop(); } catch (Throwable ignored) {}
                shutdownIo.shutdown();
            });
        } catch (Throwable submitFailure) {
            Thread fallback = new Thread(() -> {
                try { stop(); } catch (Throwable ignored) {}
            }, "arch-hq-audio-stop-fallback");
            fallback.setDaemon(true);
            fallback.start();
        }
    }
}
