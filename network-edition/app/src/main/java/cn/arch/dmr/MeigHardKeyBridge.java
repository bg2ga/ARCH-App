package cn.arch.dmr;

import com.meige.autosdk.hardkey.HardKeyManager;

/** VM780/Meig AutoSDK hardware PTT bridge; works without accessibility or key binding. */
public final class MeigHardKeyBridge {
    public interface Listener { void onPtt(boolean down, boolean longPress); }
    private static HardKeyManager manager;
    private static HardKeyManager.HardKeyListener listener;
    private static Listener callback;
    private static boolean installed;

    private MeigHardKeyBridge() {}

    public static synchronized boolean install(Listener cb) {
        if (cb == null) return false;
        callback = cb;
        if (installed) return true;
        try {
            manager = HardKeyManager.getInstance();
            try { manager.setLowPowerEnable(true); } catch (Throwable ignored) {}
            listener = new HardKeyManager.HardKeyListener() {
                @Override public void onPTTInputKeyEvent(boolean longPress, boolean down) {
                    Listener c = callback;
                    android.util.Log.i("ARCH-MEIG-KEY", "native PTT down=" + down + " long=" + longPress);
                    if (c != null) c.onPtt(down, longPress);
                }
                @Override public void onAlarmInputKeyEvent(boolean longPress, boolean down) {}
                @Override public void onRecordVideoInputKeyEvent(boolean longPress, boolean down) {}
                @Override public void onRecordAudioInputKeyEvent(boolean longPress, boolean down) {}
                @Override public void onShutterInputKeyEvent(boolean longPress, boolean down) {}
            };
            manager.registerHardKeyListener(listener);
            installed = true;
            android.util.Log.i("ARCH-MEIG-KEY", "HardKeyManager registered");
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ARCH-MEIG-KEY", "HardKeyManager unavailable", t);
            manager = null;
            listener = null;
            installed = false;
            return false;
        }
    }

    public static synchronized void uninstall() {
        try { if (manager != null && listener != null) manager.unRegisterHardKeyListener(listener); } catch (Throwable ignored) {}
        installed = false;
        listener = null;
        manager = null;
        callback = null;
    }
}
