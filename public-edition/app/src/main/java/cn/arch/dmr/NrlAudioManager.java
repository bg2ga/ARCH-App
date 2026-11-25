package cn.arch.dmr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import java.util.concurrent.LinkedBlockingQueue;

/** Native NRL audio path: 16 kHz Opus PCM in and out, matching the official NRL client. */
final class NrlAudioManager {
    interface MicCb { void onFrame(short[] pcm320); }

    private static final int SR = 16000;
    private static final int FRAME = 320;

    private final Context context;
    private final AudioManager audioManager;
    private final LinkedBlockingQueue<short[]> playQueue = new LinkedBlockingQueue<>();
    private AudioTrack track;
    private AudioRecord record;
    private Thread playThread;
    private Thread micThread;
    private volatile boolean playRun;
    private volatile boolean micRun;
    private volatile MicCb micCb;
    private volatile float rxGain = 2.5f; // official NRL playback gain
    private volatile float txGain = 1.0f;
    private volatile boolean agcEnabled = false;
    private final DigitalAgc rxAgc = new DigitalAgc();
    private final DigitalAgc txAgc = new DigitalAgc();
    private AutomaticGainControl agc;
    private NoiseSuppressor ns;
    private AcousticEchoCanceler aec;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean communicationRoute;

    NrlAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    synchronized boolean startPlayback() {
        if (playRun && track != null && playThread != null && playThread.isAlive()) return true;
        stopPlayback();
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            final AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, FRAME * 2 * 6), AudioTrack.MODE_STREAM);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) { t.release(); return false; }
            track = t;
            playRun = true;
            t.play();
            playThread = new Thread(() -> {
                while (playRun && track == t) {
                    try {
                        short[] pcm = playQueue.take();
                        if (!playRun || track != t) break;
                        applyGain(pcm, rxGain);
                        if (agcEnabled) rxAgc.process(pcm);
                        AudioLevelBus.pushRx(pcm);
                        t.write(pcm, 0, pcm.length);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                        if (!playRun || track != t) return;
                    }
                }
            }, "nrl-play");
            playThread.start();
            return true;
        } catch (Throwable t) {
            stopPlayback();
            return false;
        }
    }

    void play(short[] pcm) {
        if (pcm == null || pcm.length == 0 || !playRun) return;
        if (playQueue.size() > 16) playQueue.poll();
        playQueue.offer(pcm.clone());
    }

    synchronized boolean startMic(MicCb cb) {
        if (cb == null) { stopMic(); return true; }
        if (micRun && record != null && micThread != null && micThread.isAlive()) {
            micCb = cb;
            return true;
        }
        stopMic();
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            startCommunicationRoute();
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) { stopCommunicationRoute(); return false; }
            final AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, FRAME * 2 * 6));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); stopCommunicationRoute(); return false; }
            record = r;
            micCb = cb;
            enableEffects(r.getAudioSessionId());
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                stopMic();
                return false;
            }
            micRun = true;
            micThread = new Thread(() -> {
                short[] pcm = new short[FRAME];
                while (micRun && record == r) {
                    try {
                        int n = r.read(pcm, 0, FRAME);
                        MicCb callback = micCb;
                        if (n == FRAME && callback != null && micRun && record == r) {
                            short[] frame = pcm.clone();
                            applyGain(frame, txGain);
                            if (agcEnabled) txAgc.process(frame);
                            AudioLevelBus.pushTx(frame);
                            callback.onFrame(frame);
                        }
                    } catch (Throwable t) {
                        return;
                    }
                }
            }, "nrl-mic");
            micThread.start();
            return true;
        } catch (Throwable t) {
            stopMic();
            return false;
        }
    }

    synchronized void stopMic() {
        micCb = null;
        micRun = false;
        AudioRecord r = record;
        record = null;
        Thread t = micThread;
        micThread = null;
        if (r != null) try { r.stop(); } catch (Throwable ignored) {}
        if (t != null) {
            t.interrupt();
            try { t.join(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (r != null) try { r.release(); } catch (Throwable ignored) {}
        releaseEffects();
        stopCommunicationRoute();
    }

    synchronized void stopPlayback() {
        playRun = false;
        playQueue.clear();
        AudioTrack t = track;
        track = null;
        Thread p = playThread;
        playThread = null;
        if (t != null) try { t.stop(); } catch (Throwable ignored) {}
        if (p != null) {
            p.interrupt();
            try { p.join(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (t != null) try { t.release(); } catch (Throwable ignored) {}
    }

    synchronized void stop() {
        stopMic();
        stopPlayback();
    }

    void mutePlayback(boolean mute) {
        AudioTrack t = track;
        if (t != null) try { t.setVolume(mute ? 0f : 1f); } catch (Throwable ignored) {}
    }

    void setGains(float rx, float tx) {
        rxGain = Math.max(0.05f, Math.min(8f, rx));
        txGain = Math.max(0.05f, Math.min(8f, tx));
    }

    void setAgc(boolean on) {
        agcEnabled = on;
        rxAgc.reset();
        txAgc.reset();
    }

    private void enableEffects(int session) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(session);
                if (agc != null) agc.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Throwable ignored) { releaseEffects(); }
    }

    private void releaseEffects() {
        if (agc != null) { try { agc.setEnabled(false); agc.release(); } catch (Throwable ignored) {} agc = null; }
        if (ns != null) { try { ns.setEnabled(false); ns.release(); } catch (Throwable ignored) {} ns = null; }
        if (aec != null) { try { aec.setEnabled(false); aec.release(); } catch (Throwable ignored) {} aec = null; }
    }

    private void startCommunicationRoute() {
        if (audioManager == null || communicationRoute) return;
        try {
            previousAudioMode = audioManager.getMode();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            communicationRoute = true;
        } catch (Throwable ignored) {}
    }

    private void stopCommunicationRoute() {
        if (audioManager == null || !communicationRoute) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
            audioManager.setMode(previousAudioMode);
        } catch (Throwable ignored) {}
        communicationRoute = false;
    }

    private static void applyGain(short[] pcm, float gain) {
        if (pcm == null || pcm.length == 0 || gain == 1.0f) return;
        for (int i = 0; i < pcm.length; i++) {
            int v = Math.round(pcm[i] * gain);
            pcm[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
        }
    }
}