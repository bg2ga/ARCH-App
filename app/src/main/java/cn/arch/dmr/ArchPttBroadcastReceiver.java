package cn.arch.dmr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * [3.3.0] 厂商硬件 PTT 广播 → ARCH PTT。
 *
 * 支持三种模式：
 *  D: 单独的按下广播
 *  U: 单独的松开广播
 *  T: 单广播/切换；若 Intent 带 KeyEvent/action 额外字段，按额外字段的 down/up 处理
 */
public class ArchPttBroadcastReceiver extends BroadcastReceiver {
    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = new Runnable() {
        @Override public void run() { stopTx("TOT"); }
    };

    public ArchPttBroadcastReceiver(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!isTrustedVendorSender()) {
            android.util.Log.w("ARCH", "拒绝来源不可信的厂商 PTT 广播: uid=" + safeSentUid());
            return;
        }
        String action = intent.getAction();
        if (action == null || action.length() == 0) return;

        Prefs p = new Prefs(appContext);
        ArchAppState.lastBroadcastAction = action;
        ArchAppState.lastBroadcastAt = System.currentTimeMillis();
        ArchAppState.lastKeySource = "bc";
        p.extBroadcastLogAdd(action + extraSummary(intent), "bc");

        if (!p.externalPttEnabled()) return;
        if (!p.externalPttBackground() && !ArchAppState.visible) {
            p.extBroadcastLogAdd(action, "bc-bgskip");
            return;
        }

        String type = ArchPttBroadcast.customTypeOf(action, p.extBroadcastMap());
        if (type == null) type = ArchPttBroadcast.typeOf(action);
        if (type == null) type = ArchPttBroadcast.inferType(action);
        if (type == null) return;

        String event = intentEvent(intent);
        if (ArchPttBroadcast.TYPE_TOGGLE.equals(type)) {
            if ("D".equals(event)) startTx(p, action);
            else if ("U".equals(event)) stopTx("bc-up");
            else toggleTx(p, action);
        } else if (ArchPttBroadcast.TYPE_DOWN.equals(type)) {
            startTx(p, action);
        } else if (ArchPttBroadcast.TYPE_UP.equals(type)) {
            stopTx("bc-up");
        }
    }

    private int safeSentUid() {
        if (Build.VERSION.SDK_INT < 34) return -1;
        try { return getSentFromUid(); } catch (Throwable ignored) { return -1; }
    }

    /** Android 14+ 才能可靠取得广播发送 UID；旧系统继续兼容厂商私有 ROM。 */
    private boolean isTrustedVendorSender() {
        if (Build.VERSION.SDK_INT < 34) return true;
        int uid;
        try { uid = getSentFromUid(); } catch (Throwable ignored) { return false; }
        if (uid == android.os.Process.myUid()) return true;
        if (uid == android.os.Process.SYSTEM_UID) return true;
        String[] packages = appContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            try {
                android.content.pm.ApplicationInfo ai = appContext.getPackageManager()
                        .getApplicationInfo(pkg, 0);
                if ((ai.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                        | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                    return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private String extraSummary(Intent intent) {
        try {
            Bundle b = intent.getExtras();
            if (b == null || b.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(" {");
            int n = 0;
            for (String k : b.keySet()) {
                if (n++ >= 4) { sb.append(" …"); break; }
                Object v = b.get(k);
                String s = String.valueOf(v);
                if (s.length() > 80) s = s.substring(0, 80) + "…";
                sb.append(k).append('=').append(s).append(';');
            }
            return sb.append('}').toString();
        } catch (Throwable ignored) { return ""; }
    }

    private String intentEvent(Intent intent) {
        try {
            Parcelable extra = null;
            if (Build.VERSION.SDK_INT >= 33) extra = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
            else extra = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (extra instanceof KeyEvent) {
                int a = ((KeyEvent) extra).getAction();
                if (a == KeyEvent.ACTION_DOWN) return "D";
                if (a == KeyEvent.ACTION_UP) return "U";
            }
        } catch (Throwable ignored) {}

        try {
            Bundle b = intent.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    if (v instanceof KeyEvent) {
                        int a = ((KeyEvent) v).getAction();
                        if (a == KeyEvent.ACTION_DOWN) return "D";
                        if (a == KeyEvent.ACTION_UP) return "U";
                    }
                }
            }
        } catch (Throwable ignored) {}

        String[] sk = {"action", "keyAction", "key_action", "event", "keyEvent", "state", "android.intent.extra.KEY_ACTION"};
        for (String k : sk) {
            String v = null;
            try { v = intent.getStringExtra(k); } catch (Throwable ignored) {}
            if (v == null) continue;
            v = v.trim().toLowerCase(java.util.Locale.US);
            if (v.equals("down") || v.equals("keydown") || v.equals("key_down") || v.equals("pressed") || v.equals("0")) return "D";
            if (v.equals("up") || v.equals("keyup") || v.equals("key_up") || v.equals("released") || v.equals("1")) return "U";
        }

        String[] ik = {"action", "keyAction", "key_action", "keyCode", "keycode", "key_code", "android.intent.extra.KEY_ACTION"};
        for (String k : ik) {
            try {
                if (!intent.hasExtra(k)) continue;
                int v = intent.getIntExtra(k, Integer.MIN_VALUE);
                if (k.equals("action") || k.equals("keyAction") || k.equals("key_action") || k.equals("android.intent.extra.KEY_ACTION")) {
                    if (v == KeyEvent.ACTION_DOWN) return "D";
                    if (v == KeyEvent.ACTION_UP) return "U";
                }
            } catch (Throwable ignored) {}
        }

        String[] bk = {"down", "pressed", "isDown", "keyDown"};
        for (String k : bk) {
            try {
                Bundle b = intent.getExtras();
                if (b == null || !b.containsKey(k)) continue;
                Object v = b.get(k);
                if (v instanceof Boolean) return ((Boolean) v) ? "D" : "U";
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void toggleTx(Prefs p, String source) {
        if (ArchAppState.hwTx) stopTx("bc-toggle");
        else startTx(p, source);
    }

    private void startTx(Prefs p, String source) {
        if (ArchAppState.hwTx) return;
        try { HyteraPdc760Bridge.noteActivityPttDown(appContext); } catch (Throwable ignored) {}
        ArchAppState.hwTx = true;
        try { MainActivity.dispatchHardwarePtt(true); } catch (Throwable ignored) {}
        try {
            handler.removeCallbacks(autoStop);
            handler.postDelayed(autoStop, Math.max(10, p.pttTimeout()) * 1000L);
        } catch (Throwable ignored) {}
    }

    private void stopTx(String why) {
        try { HyteraPdc760Bridge.noteActivityPttUp(appContext); } catch (Throwable ignored) {}
        if (!ArchAppState.hwTx) return;
        ArchAppState.hwTx = false;
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
        try { MainActivity.dispatchHardwarePtt(false); } catch (Throwable ignored) {}
    }

    public void release() {
        try { handler.removeCallbacks(autoStop); } catch (Throwable ignored) {}
    }
}
