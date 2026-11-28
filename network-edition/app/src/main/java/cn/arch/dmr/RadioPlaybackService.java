package cn.arch.dmr;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** [3.2.39] 网络收音机后台播放服务；[3.2.42] 在音频 Sink 上截取真实 PCM 做频谱。 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class RadioPlaybackService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override public void onCreate() {
        super.onCreate();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                               boolean enableAudioTrackPlaybackParams) {
                // 固定 16bit 输出，SpectrumAudioSink 按 16bit 小端解析
                return new SpectrumAudioSink(
                        super.buildAudioSink(context, false, enableAudioTrackPlaybackParams));
            }
        };
        player = new ExoPlayer.Builder(this, renderers).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        mediaSession = new MediaSession.Builder(this, player).build();
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override public void onDestroy() {
        RadioSpectrum.clear();
        if (mediaSession != null) {
            try { mediaSession.release(); } catch (Throwable ignored) {}
            mediaSession = null;
        }
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        super.onDestroy();
    }
}
