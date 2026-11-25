package cn.arch.dmr;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Hardware PTT transport for Hytera terminals. Different product families expose
 * different broadcast actions, key values or vendor SDK callbacks; the active
 * contract is selected by HyteraDeviceProfile.
 */
@SuppressLint({"WrongConstant", "UnspecifiedRegisterReceiverFlag"})
final class HyteraHardwareKeyBridge {
    interface Listener { void onPtt(boolean down); }

    private static final String TAG = "ARCH-HYTERA-KEY";
    private static Context appContext;
    private static Listener listener;
    private static Object dreamCommon;
    private static Object dreamCallback;
    private static BroadcastReceiver receiver;
    private static boolean installed;
    private static volatile boolean pttPhysicalDown;

    private HyteraHardwareKeyBridge() {}

    static synchronized boolean install(Context context, Listener callback) {
        if (context == null || callback == null) return false;
        if (installed) {
            listener = callback;
            return true;
        }
        appContext = context.getApplicationContext();
        listener = callback;
        pttPhysicalDown = false;
        HyteraDeviceProfile profile = HyteraDeviceProfile.detect();
        boolean direct = profile.dreamKeyDirect && installDreamDirect(profile);
        boolean broadcasts = profile.dreamKeyBroadcast && installReceiver(profile);
        installed = direct || broadcasts;
        Log.i(TAG, "profile=" + profile.label + " family=" + profile.family
                + " direct=" + direct + " broadcasts=" + broadcasts);
        return installed;
    }

    static synchronized void uninstall() {
        if (dreamCommon != null && dreamCallback != null) {
            try {
                Method unregister = dreamCommon.getClass().getMethod(
                        "unregisterCommonManagerListener", dreamCallback.getClass().getInterfaces()[0]);
                unregister.invoke(dreamCommon, dreamCallback);
            } catch (Throwable ignored) {
                try {
                    dreamCommon.getClass().getMethod("unregisterCommonManagerListener",
                            Class.forName("android.dsp.proxy.CommonManagerListener"))
                            .invoke(dreamCommon, dreamCallback);
                } catch (Throwable ignored2) {}
            }
        }
        if (receiver != null && appContext != null) {
            try { appContext.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        }
        dreamCommon = null;
        dreamCallback = null;
        receiver = null;
        listener = null;
        appContext = null;
        installed = false;
        pttPhysicalDown = false;
    }

    private static boolean installDreamDirect(HyteraDeviceProfile profile) {
        try {
            ClassLoader loader = HyteraHardwareKeyBridge.class.getClassLoader();
            Class<?> listenerClass = Class.forName("android.dsp.proxy.CommonManagerListener", false, loader);
            Object dsp = appContext.getSystemService("dsp_service");
            if (dsp == null) return false;
            Object common = dsp.getClass().getMethod("getCommonManager").invoke(dsp);
            if (common == null) return false;
            Object callback = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name != null && (name.contains("KeyStatus") || name.contains("keyStatus"))) {
                            handleVendorKey(profile, args);
                            return null;
                        }
                        if ("toString".equals(name)) return "ARCH-DreamKey";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                        return null;
                    });
            boolean registered = false;
            try {
                common.getClass().getMethod("registerCommonManagerListener", listenerClass)
                        .invoke(common, callback);
                registered = true;
            } catch (Throwable ignored) {
                try {
                    common.getClass().getMethod("registerCommonManagerListener",
                            listenerClass, Looper.class).invoke(common, callback, Looper.getMainLooper());
                    registered = true;
                } catch (Throwable ignored2) {}
            }
            if (registered) {
                dreamCommon = common;
                dreamCallback = callback;
            }
            return registered;
        } catch (Throwable t) {
            Log.d(TAG, "direct unavailable: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void handleVendorKey(HyteraDeviceProfile profile, Object[] args) {
        if (args == null || args.length < 2) return;
        int key;
        int operation;
        try { key = ((Number) args[0]).intValue(); }
        catch (Throwable t) { return; }
        try { operation = ((Number) args[1]).intValue(); }
        catch (Throwable t) {
            if (args[1] instanceof Boolean) operation = ((Boolean) args[1]) ? 0 : 1;
            else return;
        }
        if (!profile.isPttKeyCode(key)) return;
        if (operation == 0) emit(true, "dream-key:" + key);
        else if (operation == 1) emit(false, "dream-key:" + key);
    }

    private static boolean installReceiver(HyteraDeviceProfile profile) {
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (!ArchPttBroadcast.trustedVendorSender(context, this, intent,
                            action, new Prefs(context).extBroadcastMap())) {
                        Log.w(TAG, "blocked untrusted vendor PTT sender action=" + action);
                        return;
                    }
                    handleBroadcast(profile, intent);
                }
            };
            IntentFilter filter = new IntentFilter();
            for (String action : profile.pttDownActions) filter.addAction(action);
            for (String action : profile.pttUpActions) filter.addAction(action);
            filter.addAction("com.dream.thirdpartyDefine");
            filter.addAction("com.dream.api.keyEvent");
            filter.addAction("key.intent.action.onkeyevent");
            filter.addAction("com.meigsmart.meigkeyaccessibility.onkeyevent");
            filter.addAction("com.zello.ptt.down");
            filter.addAction("com.zello.ptt.up");
            filter.addAction("android.intent.ACTION_PTT_KEY");
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "receiver unavailable: " + t.getClass().getSimpleName());
            receiver = null;
            return false;
        }
    }

    private static void handleBroadcast(HyteraDeviceProfile profile, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Bundle diagnostic = intent.getExtras();
        int rawKey = diagnostic == null ? -1 : (diagnostic.containsKey("keycode")
                ? diagnostic.getInt("keycode", -1) : diagnostic.containsKey("keyCode")
                ? diagnostic.getInt("keyCode", -1) : diagnostic.getInt("key", -1));
        int rawOp = diagnostic == null ? -1 : (diagnostic.containsKey("action")
                ? diagnostic.getInt("action", -1) : diagnostic.containsKey("operator")
                ? diagnostic.getInt("operator", -1) : diagnostic.getInt("operation", -1));
        Log.i(TAG, "action=" + action + " key=" + rawKey + " op=" + rawOp);

        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null) {
            if (profile.isPttKeyCode(event.getKeyCode())) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) emit(true, action);
                else if (event.getAction() == KeyEvent.ACTION_UP) emit(false, action);
            }
            return;
        }

        if ("com.zello.ptt.down".equals(action)) {
            emit(true, action);
            return;
        }
        if ("com.zello.ptt.up".equals(action)) {
            emit(false, action);
            return;
        }

        if (profile.isPttAction(action, true)) {
            emit(true, action);
            return;
        }
        if (profile.isPttAction(action, false)) {
            emit(false, action);
            return;
        }

        if ("key.intent.action.onkeyevent".equals(action)
                || "com.meigsmart.meigkeyaccessibility.onkeyevent".equals(action)
                || "com.dream.thirdpartyDefine".equals(action)
                || "com.dream.api.keyEvent".equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            int key = extras.containsKey("keycode") ? extras.getInt("keycode", -1)
                    : extras.containsKey("keyCode") ? extras.getInt("keyCode", -1)
                    : extras.getInt("key", -1);
            int operation = extras.containsKey("action") ? extras.getInt("action", -1)
                    : extras.containsKey("operator") ? extras.getInt("operator", -1)
                    : extras.getInt("operation", -1);
            if (!profile.isPttKeyCode(key)) return;
            if (operation == 0) emit(true, action + ":key=" + key);
            else if (operation == 1) emit(false, action + ":key=" + key);
            return;
        }

        if ("android.intent.ACTION_PTT_KEY".equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            boolean down;
            if (extras.containsKey("down")) down = extras.getBoolean("down");
            else if (extras.containsKey("pressed")) down = extras.getBoolean("pressed");
            else if (extras.containsKey("state")) down = extras.getInt("state", 0) == 1;
            else if (extras.containsKey("value")) down = extras.getInt("value", 0) == 1;
            else return;
            emit(down, action);
        }
    }

    private static void emit(boolean down, String source) {
        if (pttPhysicalDown == down) {
            Log.i(TAG, "PTT " + (down ? "down" : "up") + " duplicate ignored source=" + source);
            return;
        }
        pttPhysicalDown = down;
        Listener callback = listener;
        Log.i(TAG, "PTT " + (down ? "down" : "up") + " source=" + source);
        if (callback != null) callback.onPtt(down);
    }
}
