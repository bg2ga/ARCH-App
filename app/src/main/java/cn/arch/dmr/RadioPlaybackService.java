package cn.arch.dmr;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.MediaSession.ConnectionResult;

/** [3.2.39] 网络收音机后台播放服务；[3.2.42] 在音频 Sink 上截取真实 PCM 做频谱。 */
@UnstableApi
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
        mediaSession = new MediaSession.Builder(this, player)
                .setCallback(new MediaSession.Callback() {
                    @Override public ConnectionResult onConnect(MediaSession session,
                                                                 MediaSession.ControllerInfo controller) {
                        if (isAllowedController(controller)) {
                            return ConnectionResult.accept(
                                    ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                                    ConnectionResult.DEFAULT_PLAYER_COMMANDS);
                        }
                        android.util.Log.w("ARCH", "拒绝未受信任的媒体控制器: "
                                + (controller == null ? "null" : controller.getPackageName()));
                        return ConnectionResult.reject();
                    }
                })
                .build();
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return isAllowedController(controllerInfo) ? mediaSession : null;
    }

    private boolean isAllowedController(MediaSession.ControllerInfo controller) {
        if (controller == null) return false;
        if (controller.isTrusted()) return true;
        String pkg = controller.getPackageName();
        if (pkg == null) return false;
        return getPackageName().equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.android.bluetooth".equals(pkg)
                || "com.google.android.projection.gearhead".equals(pkg)
                || "com.android.car".equals(pkg);
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
