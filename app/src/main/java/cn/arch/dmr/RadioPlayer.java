package cn.arch.dmr;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

/** [3.2.39] Media3 播放器桥接。 */
@UnstableApi
public final class RadioPlayer {
    public interface Listener {
        void onState(int state, String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private boolean connecting;
    private RadioStation pending;
    private Listener listener;
    private float volume = 1f;
    private int retries;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean playing) {
            if (playing) state(1, "播放中");
            else if (controller != null && controller.getPlaybackState() == Player.STATE_READY) state(1, "已暂停");
        }

        @Override public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) {
                state(2, "缓冲中");
            } else if (state == Player.STATE_READY) {
                retries = 0;
                state(1, isPlaying() ? "播放中" : "已就绪");
            } else if (state == Player.STATE_ENDED) {
                state(0, "播放结束");
            }
        }

        @Override public void onPlayerError(PlaybackException error) {
            if (retries < 2) {
                retries++;
                main.postDelayed(() -> {
                    if (controller != null) {
                        try { controller.prepare(); controller.play(); } catch (Throwable ignored) {}
                    }
                }, 1500L);
                state(2, "线路不稳定，正在重试");
            } else {
                state(3, "线路不可用");
            }
        }
    };

    public RadioPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void play(RadioStation station) {
        pending = station;
        retries = 0;
        ensureConnected();
        applyPending();
    }

    public void pause() {
        if (controller != null) {
            try { controller.pause(); } catch (Throwable ignored) {}
        }
    }

    public void resume() {
        ensureConnected();
        if (controller != null) {
            try { controller.play(); } catch (Throwable ignored) {}
        } else if (pending != null) {
            applyPending();
        }
    }

    public void stop() {
        pending = null;
        if (controller != null) {
            try { controller.stop(); controller.clearMediaItems(); } catch (Throwable ignored) {}
        }
        state(0, "未播放");
    }

    public boolean isPlaying() {
        return controller != null && controller.isPlaying();
    }

    public RadioStation current() { return pending; }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        if (controller != null) {
            try { controller.setVolume(volume); } catch (Throwable ignored) {}
        }
    }

    public float volume() { return volume; }

    private void ensureConnected() {
        if (controller != null || connecting) return;
        connecting = true;
        try {
            SessionToken token = new SessionToken(context,
                    new ComponentName(context, RadioPlaybackService.class));
            var future = new MediaController.Builder(context, token).buildAsync();
            future.addListener(() -> {
                try {
                    MediaController c = future.get();
                    main.post(() -> {
                        connecting = false;
                        controller = c;
                        controller.addListener(playerListener);
                        controller.setVolume(volume);
                        applyPending();
                    });
                } catch (Throwable t) {
                    connecting = false;
                    state(3, "播放器启动失败");
                }
            }, Runnable::run);
        } catch (Throwable t) {
            connecting = false;
            state(3, "播放器启动失败");
        }
    }

    private void applyPending() {
        final RadioStation station = pending;
        if (station == null || controller == null) return;
        try {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.subtitle())
                    .build();
            MediaItem item = new MediaItem.Builder()
                    .setUri(station.stream)
                    .setMediaMetadata(metadata)
                    .build();
            controller.setMediaItem(item);
            controller.prepare();
            controller.play();
            state(2, "正在连接");
        } catch (Throwable t) {
            state(3, "无法播放");
        }
    }

    private void state(int st, String msg) {
        Listener l = listener;
        if (l != null) main.post(() -> l.onState(st, msg));
    }

    public void release() {
        if (controller != null) {
            try { controller.removeListener(playerListener); controller.release(); } catch (Throwable ignored) {}
            controller = null;
        }
        connecting = false;
    }
}

