package cn.arch.dmr;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

/** Reliable local WAV ringtone player for incoming duplex calls. */
final class NokiaRingtone {
    private static final String TAG = "ArchRingtone";
    private final Context context;
    private final AudioManager audioManager;
    private MediaPlayer player;
    private boolean active;
    private int savedMusicVolume = -1;

    NokiaRingtone(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    synchronized void start() {
        if (active) return;
        releasePlayer();
        try {
            raiseVolumeFloor();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            Uri uri = Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.nokia_tune);
            player.setDataSource(context, uri);
            player.setLooping(true);
            player.setVolume(1.0f, 1.0f);
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                stop();
                return true;
            });
            player.prepare();
            player.start();
            active = true;
            Log.i(TAG, "incoming ringtone started");
        } catch (Throwable t) {
            Log.e(TAG, "incoming ringtone start failed", t);
            releasePlayer();
            restoreVolume();
        }
    }

    synchronized void stop() {
        releasePlayer();
        restoreVolume();
    }

    private void releasePlayer() {
        active = false;
        MediaPlayer current = player;
        player = null;
        if (current == null) return;
        try { if (current.isPlaying()) current.stop(); } catch (Throwable ignored) {}
        try { current.reset(); } catch (Throwable ignored) {}
        try { current.release(); } catch (Throwable ignored) {}
        Log.i(TAG, "incoming ringtone stopped");
    }

    private void raiseVolumeFloor() {
        if (audioManager == null || savedMusicVolume >= 0) return;
        try {
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int floor = Math.max(1, max / 2);
            if (savedMusicVolume < floor) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "volume floor failed", t);
        }
    }

    private void restoreVolume() {
        if (audioManager == null || savedMusicVolume < 0) return;
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
        } catch (Throwable ignored) {}
        savedMusicVolume = -1;
    }
}
