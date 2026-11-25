package cn.arch.dmr;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * [3.2.30] 硬件按键 → ARCH PTT。
 *
 * 相比旧版：
 *  1) 任何实体按键都会写进"按键诊断日志"（app 里可看/可复制），用于识别各种安卓对讲机的键码；
 *  2) 支持"只发按下、不发松开"的机型：可以再绑一个松开键，或改用点按开关模式；
 *  3) 尊重"设备按键后台生效"开关：关闭时只有 APP 在前台才响应，避免口袋里误发射；
 *  4) 发射超时保护：按键卡住时到 TOT 自动停止发射。
 */
public class ArchPttAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = new Runnable() {
        @Override public void run() { stopTx("TOT"); }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent event) {
        try { if (new Prefs(this).userExited()) return false; } catch (Throwable ignored) {}
        // [3.3.0] 默认只走厂商广播；无障碍 KeyEvent 注入仅作为老设备兼容兜底。
        try { if (!new Prefs(this).externalPttA11yFallback()) return false; } catch (Throwable ignored) {}
        try {
            final int code = event.getKeyCode();
            final int action = event.getAction();
            final boolean down = action == KeyEvent.ACTION_DOWN;
            final boolean up = action == KeyEvent.ACTION_UP;
            if (down && event.getRepeatCount() > 0) return false;      // 长按重复：忽略
            if (!down && !up) return false;

            Prefs p = new Prefs(this);
            ArchAppState.lastKeyCode = code;
            ArchAppState.lastKeyAction = action;
            ArchAppState.lastKeySource = "a11y";
            ArchAppState.lastKeyAt = System.currentTimeMillis();
            if (!isNoisyKey(code)) p.extKeyLogAdd(code, action, "a11y");

            if (!p.externalPttEnabled()) return false;
            if (isMediaKey(code)) return false;                        // 媒体键交给 MediaSession 那条路
            if (!p.externalPttBackground() && !ArchAppState.visible) {
                if (down) p.extKeyLogAdd(code, action, "a11y-bgskip");
                return false;
            }
            final int ptt = p.externalPttKeyCode();
            if (ptt <= 0) return false;
            final int rel = p.externalPttReleaseKeyCode();
            final boolean toggle = "toggle".equals(p.externalPttKeyMode());

            if (code == ptt) {
                if (toggle) {
                    if (down) { if (ArchAppState.hwTx) stopTx("toggle"); else startTx(p); }
                    return true;
                }
                if (down) startTx(p);
                else if (up && rel <= 0) stopTx("key-up");             // 自带松开
                return true;
            }
            if (rel > 0 && code == rel && (down || up)) {              // 另绑的松开键
                stopTx("release-key");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void startTx(Prefs p) {
        if (ArchAppState.hwTx) return;
        ArchAppState.hwTx = true;
        send(ArchOverlayPttService.ACTION_DOWN);
        try {
            int tot = p.pttTimeout();
            handler.removeCallbacks(autoStop);
            handler.postDelayed(autoStop, Math.max(10, tot) * 1000L);
        } catch (Throwable ignored) {}
    }

    private void stopTx(String why) {
        if (!ArchAppState.hwTx) return;
        ArchAppState.hwTx = false;
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
        send(ArchOverlayPttService.ACTION_UP);
    }

    private boolean isMediaKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || code == KeyEvent.KEYCODE_MEDIA_STOP;
    }

    /** 音量/电源这类会刷屏的键不进日志（但依然可以绑定使用） */
    private boolean isNoisyKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER
                || code == KeyEvent.KEYCODE_BRIGHTNESS_UP || code == KeyEvent.KEYCODE_BRIGHTNESS_DOWN;
    }

    private void send(String action) {
        try {
            Intent i = new Intent(action).setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
