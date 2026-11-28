package cn.arch.dmr;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private PowerManager.WakeLock pttWakeLock;
    private volatile boolean accessibilityPttDown;
    private final Runnable autoStop = new Runnable() {
        @Override public void run() { stopTx("TOT"); }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent event) {
        // [3.3.0] 默认只走厂商广播；无障碍 KeyEvent 注入仅作为老设备兼容兜底。
        boolean a11yFallback = true;
        try { a11yFallback = new Prefs(this).externalPttA11yFallback(); } catch (Throwable ignored) {}
        try {
            final int code = event.getKeyCode();
            final int action = event.getAction();
            final boolean down = action == KeyEvent.ACTION_DOWN;
            final boolean up = action == KeyEvent.ACTION_UP;
            if (down && event.getRepeatCount() > 0) return false;      // 长按重复：忽略
            if (!down && !up) return false;

            Prefs p = new Prefs(this);
            if (p.userExited()) return false;
            ArchAppState.lastKeyCode = code;
            ArchAppState.lastKeyAction = action;
            ArchAppState.lastKeySource = "a11y";
            ArchAppState.lastKeyAt = System.currentTimeMillis();
            if (!isNoisyKey(code)) p.extKeyLogAdd(code, action, "a11y");

            if (HyteraDeviceProfile.detect().isPnc380() && isVolumeKey(code)) {
                boolean interactive = true;
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    interactive = pm == null || pm.isInteractive();
                } catch (Throwable ignored) {}
                if (!interactive && (code == KeyEvent.KEYCODE_VOLUME_UP
                        || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    if (down) {
                        Intent volume = new Intent(this, NetworkKeepAliveService.class);
                        volume.setAction(code == KeyEvent.KEYCODE_VOLUME_UP
                                ? NetworkKeepAliveService.ACTION_VOLUME_UP
                                : NetworkKeepAliveService.ACTION_VOLUME_DOWN);
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(volume);
                            else startService(volume);
                        } catch (Throwable ignored) {}
                    }
                    return true;
                }
                return false;
            }
            // Screen-off PNC380 volume handling is independent of the optional
            // accessibility PTT fallback switch.
            if (!a11yFallback) return false;
            if (!p.externalPttEnabled()) return false;
            if (isMediaKey(code)) return false;                        // 媒体键交给 MediaSession 那条路
            if (!p.externalPttBackground() && !ArchAppState.visible) {
                if (down) p.extKeyLogAdd(code, action, "a11y-bgskip");
                return false;
            }
            int ptt = p.externalPttKeyCode();
            if (ptt <= 0) {
                if (!isAutoBindCandidate(code)) return false;
                ptt = code;
                p.set("extPttKeyCode", String.valueOf(code));
                p.set("extPttKeyName", KeyEvent.keyCodeToString(code));
                p.set("extPttEnabled", "1");
                p.extKeyLogAdd(code, action, "a11y-autobind");
            }
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
        if (accessibilityPttDown || ArchAppState.hwTx) return;
        accessibilityPttDown = true;
        acquirePttWakeLock(p);
        dispatchPtt(true);
        try {
            int tot = p.pttTimeout();
            handler.removeCallbacks(autoStop);
            handler.postDelayed(autoStop, Math.max(10, tot) * 1000L);
        } catch (Throwable ignored) {}
    }

    private void stopTx(String why) {
        if (!accessibilityPttDown) return;
        accessibilityPttDown = false;
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
        dispatchPtt(false);
        releasePttWakeLock();
    }

    private void dispatchPtt(boolean down) {
        try {
            Intent i = new Intent(this, NetworkKeepAliveService.class)
                    .setAction(down ? NetworkKeepAliveService.ACTION_HW_PTT_DOWN
                            : NetworkKeepAliveService.ACTION_HW_PTT_UP);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private boolean isAutoBindCandidate(int code) {
        if (isNoisyKey(code)) return false;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_APP_SWITCH || code == KeyEvent.KEYCODE_TAB
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) return false;
        if (!ArchAppState.visible) return true;
        return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_CAMERA
                || (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12)
                || (code >= KeyEvent.KEYCODE_BUTTON_1 && code <= KeyEvent.KEYCODE_BUTTON_16)
                || code >= 200;
    }

    private void acquirePttWakeLock(Prefs p) {
        try {
            if (pttWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                pttWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARCH:PTTKEY");
                pttWakeLock.setReferenceCounted(false);
            }
            if (!pttWakeLock.isHeld()) pttWakeLock.acquire((Math.max(10, p.pttTimeout()) + 5L) * 1000L);
        } catch (Throwable ignored) {}
    }

    private void releasePttWakeLock() {
        try { if (pttWakeLock != null && pttWakeLock.isHeld()) pttWakeLock.release(); } catch (Throwable ignored) {}
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

    private boolean isVolumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
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
        try { if (accessibilityPttDown || ArchAppState.hwTx) stopTx("service-destroy"); } catch (Throwable ignored) {}
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}

