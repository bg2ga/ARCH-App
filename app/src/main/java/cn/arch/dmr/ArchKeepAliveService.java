package cn.arch.dmr;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

/** ARCH foreground keepalive and global media-button PTT bridge. */
public class ArchKeepAliveService extends Service {
    private static final String CHANNEL = "arch-connection";
    private static final int ID = 1001;
    public static final String ACTION_RELOAD_PTT = "cn.arch.dmr.action.RELOAD_PTT";
    private MediaSession mediaSession;
    private ArchPttBroadcastReceiver pttBroadcastReceiver;

    @Override public void onCreate() {
        super.onCreate();
        if (new Prefs(this).userExited()) {
            stopSelf();
            return;
        }
        try { HyteraPdc760Bridge.install(this); } catch (Throwable ignored) {}
        if (!BuildConfig.PUBLIC_EDITION) {
            try {
                Prefs p = new Prefs(this);
                if (p.sipEnabled()) SipRuntime.get(this).ensureStarted();
            } catch (Throwable ignored) {}
        }
        registerPttBroadcastReceiver();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ARCH 通信服务",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持 DMR、BM、模拟和网络连接");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        syncMediaSession();
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("ARCH 正在后台运行")
                .setContentText("连接与 PTT 保持中")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true);
        return b.build();
    }

    private void syncMediaSession() {
        Prefs p = new Prefs(this);
        if (!p.externalPttEnabled()) {
            if (mediaSession != null) {
                try { mediaSession.setActive(false); mediaSession.release(); } catch (Throwable ignored) {}
                mediaSession = null;
            }
            return;
        }
        if (mediaSession != null) return;
        mediaSession = new MediaSession(this, "ARCH-PTT");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent e = mediaButtonIntent == null ? null
                        : (KeyEvent) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (e == null) return super.onMediaButtonEvent(mediaButtonIntent);
                int c = e.getKeyCode();
                boolean media = c == KeyEvent.KEYCODE_HEADSETHOOK
                        || c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || c == KeyEvent.KEYCODE_MEDIA_PLAY
                        || c == KeyEvent.KEYCODE_MEDIA_PAUSE;
                if (!media) return super.onMediaButtonEvent(mediaButtonIntent);
                // [3.2.30] 一旦绑定了"设备按键"，媒体键通道就只认那个键（和它的松开键），
                // 避免耳机/蓝牙上随便按一下就把电台键控打开。
                try {
                    Prefs pp = new Prefs(ArchKeepAliveService.this);
                    int bound = pp.externalPttKeyCode();
                    int rel = pp.externalPttReleaseKeyCode();
                    if (bound > 0 && c != bound && c != rel) return super.onMediaButtonEvent(mediaButtonIntent);
                } catch (Throwable ignored) {}
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
                    sendPtt(ArchOverlayPttService.ACTION_DOWN);
                else if (e.getAction() == KeyEvent.ACTION_UP)
                    sendPtt(ArchOverlayPttService.ACTION_UP);
                return true;
            }
        });
        try { mediaSession.setActive(true); } catch (Throwable ignored) {}
    }

    private void sendPtt(String action) {
        boolean down = ArchOverlayPttService.ACTION_DOWN.equals(action);
        try { if (MainActivity.dispatchHardwarePtt(down)) return; } catch (Throwable ignored) {}
        Intent i = new Intent(action).setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (new Prefs(this).userExited()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD_PTT.equals(intent.getAction())) registerPttBroadcastReceiver();
        syncMediaSession();
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int fg = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (Build.VERSION.SDK_INT >= 30) fg |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(ID, n, fg);
            } else {
                startForeground(ID, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("ARCH", "带类型 startForeground 失败，尝试仅麦克风类型: " + t);
            try {
                if (Build.VERSION.SDK_INT >= 30)
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                else
                    startForeground(ID, n);
            } catch (Throwable t2) {
                android.util.Log.w("ARCH", "仅麦克风 startForeground 失败，降级: " + t2);
                try { startForeground(ID, n); }
                catch (Throwable t3) { android.util.Log.w("ARCH", "startForeground 失败: " + t3); }
            }
        }
        return START_STICKY;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerPttBroadcastReceiver() {
        try {
            if (pttBroadcastReceiver != null) {
                try { unregisterReceiver(pttBroadcastReceiver); } catch (Throwable ignored) {}
                pttBroadcastReceiver.release();
                pttBroadcastReceiver = null;
            }
        } catch (Throwable ignored) {}
        try {
            pttBroadcastReceiver = new ArchPttBroadcastReceiver(this);
            IntentFilter f = ArchPttBroadcast.buildFilter(this);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(pttBroadcastReceiver, f, Context.RECEIVER_EXPORTED);
            else
                registerReceiver(pttBroadcastReceiver, f);
            android.util.Log.i("ARCH", "PTT broadcast receiver ready: " + f.countActions() + " actions");
        } catch (Throwable t) {
            android.util.Log.w("ARCH", "PTT broadcast receiver failed: " + t);
        }
    }

    @Override public void onDestroy() {
        try { if (pttBroadcastReceiver != null) unregisterReceiver(pttBroadcastReceiver); } catch (Throwable ignored) {}
        try { if (pttBroadcastReceiver != null) pttBroadcastReceiver.release(); } catch (Throwable ignored) {}
        pttBroadcastReceiver = null;
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Throwable ignored) {}
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
