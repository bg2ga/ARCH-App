package cn.arch.dmr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Bridges PTT state to vendor LEDs. Idle=off, receive=green, transmit=red. */
final class DeviceIndicatorBridge {
    static final int IDLE = 0;
    static final int RECEIVE = 1;
    static final int TRANSMIT = 2;

    private static final int NOTIFICATION_ID = 19871103;
    private static final int VENDOR_LED_NOTIFICATION_ID = 1;
    private static final int P50_LED_NOTIFICATION_ID = 19871104;
    // P50 factory RGB nodes. These are the same nodes used by the device's
    // own FactoryTest/RGBLightActivity, rather than the notification HAL.
    private static final String P50_RED_LED_PATH =
            "/sys/devices/platform/11016000.i2c5/i2c-5/5-0034/mt6370_pmu_rgbled/leds/red/brightness";
    private static final String P50_GREEN_LED_PATH =
            "/sys/devices/platform/11016000.i2c5/i2c-5/5-0034/mt6370_pmu_rgbled/leds/green/brightness";
    private static final String P50_BLUE_LED_PATH =
            "/sys/devices/platform/11016000.i2c5/i2c-5/5-0034/mt6370_pmu_rgbled/leds/blue/brightness";
    private static final long ACTIVE_DEBOUNCE_MS = 25L;
    private static final long IDLE_DEBOUNCE_MS = 180L;
    private static int appliedMode = -1;
    private static int desiredMode = IDLE;
    private static Context pendingContext;
    private static final Handler LED_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable APPLY_PENDING = DeviceIndicatorBridge::applyPending;
    private static boolean meigUnavailable;
    private static boolean hyteraUnavailable;
    private static boolean vendorLedServiceUnavailable;
    private static boolean p50LightsUnavailable;
    private static Object p50LightsSession;
    private static java.util.List<Object> p50RedLights = new java.util.ArrayList<>();
    private static java.util.List<Object> p50GreenLights = new java.util.ArrayList<>();

    private DeviceIndicatorBridge() {}

    static synchronized void setMode(Context context, int mode) {
        if (context == null) return;
        if (mode != IDLE && mode != RECEIVE && mode != TRANSMIT) mode = IDLE;
        Context app = context.getApplicationContext();
        pendingContext = app == null ? context : app;
        desiredMode = mode;
        LED_HANDLER.removeCallbacks(APPLY_PENDING);
        if (mode == IDLE && appliedMode == IDLE) return;
        if (mode != IDLE && appliedMode == mode) return;
        long delay = mode == IDLE ? IDLE_DEBOUNCE_MS : ACTIVE_DEBOUNCE_MS;
        LED_HANDLER.postDelayed(APPLY_PENDING, delay);
        Log.d("ARCH-LED", "queued " + modeName(mode) + " applied=" + modeName(appliedMode)
                + " delay=" + delay);
    }

    /** PTT down must be visible immediately and must not depend on UI/main-loop latency. */
    static synchronized void transmitNow(Context context) {
        applyNow(context, TRANSMIT);
    }

    /** PTT release must clear the latched vendor LED immediately. */
    static synchronized void idleNow(Context context) {
        applyNow(context, IDLE);
    }

    private static void applyNow(Context context, int mode) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        pendingContext = app == null ? context : app;
        desiredMode = mode;
        LED_HANDLER.removeCallbacks(APPLY_PENDING);
        applyModeNow(pendingContext, mode);
        appliedMode = mode;
    }

    /** Re-assert the stable state after screen wake / vendor LED reset. */
    static synchronized void refresh(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        pendingContext = app == null ? context : app;
        LED_HANDLER.removeCallbacks(APPLY_PENDING);
        appliedMode = -1;
        LED_HANDLER.postDelayed(APPLY_PENDING,
                desiredMode == IDLE ? 0L : ACTIVE_DEBOUNCE_MS);
    }

    static synchronized void off(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        pendingContext = app == null ? context : app;
        desiredMode = IDLE;
        appliedMode = -1;
        LED_HANDLER.removeCallbacks(APPLY_PENDING);
        LED_HANDLER.post(APPLY_PENDING);
    }

    private static synchronized void applyPending() {
        if (pendingContext == null) return;
        int mode = desiredMode;
        if (mode == appliedMode) return;
        applyModeNow(pendingContext, mode);
        appliedMode = mode;
    }

    private static boolean applyModeNow(Context context, int mode) {
        HyteraDeviceProfile profile = HyteraDeviceProfile.detect();
        if (profile.isPdcBridge()) {
            // PDC760/PTC use the platform LED service through the same
            // AutoSDK contract as the Hytera internal app:
            // LED_ID_SEND=1, LED_ID_RECEIVE=4. This is the status-light
            // channel, not the notification/information LED.
            boolean vendor = applyPdcOfficialLed(context.getApplicationContext(), mode);
            Log.i("ARCH-LED", "profile=" + profile.label + " indicator=" + modeName(mode)
                    + " pdcOfficialLed=" + vendor);
            return vendor;
        }
        if (profile.isP50Pro()) {
            // P50 exposes the status RGB through the MT6370 PMU. Try the
            // factory node first; keep the historical foreground-notification
            // path only as a fallback for firmware builds whose sysfs is
            // protected from a non-system app.
            boolean rgbSysfs = applyP50RgbSysfs(mode);
            boolean vendor = !rgbSysfs && applyHytera(context.getApplicationContext(), mode);
            boolean serviceNotification = !rgbSysfs
                    && NetworkKeepAliveService.setP50StatusLight(context, mode);
            boolean notification = !rgbSysfs
                    && (serviceNotification || applyP50NotificationLed(context, mode));
            boolean lightsManager = !rgbSysfs && !serviceNotification
                    && applyP50LightsManager(context, mode);
            Log.i("ARCH-LED", "profile=P50 Pro indicator=" + modeName(mode)
                    + " rgbSysfs=" + rgbSysfs + " vendorDsp=" + vendor
                    + " serviceNotification=" + serviceNotification
                    + " statusNotification=" + notification + " lightsManager=" + lightsManager);
            return rgbSysfs || notification || lightsManager;
        }
        if (profile.isPnc380()) {
            // PNC380's NotificationManagerService.updateLightsLocked() reads
            // Notification.ledOnMS.  ledOnMS=0 is the system's "no light" path.
            // HyTalk therefore keeps notification id 1 and uses lights(...,1000,0).
            boolean notification = applyPnc380NotificationLed(context, mode);
            boolean meig = !notification && applyMeig(context.getApplicationContext(), mode);
            boolean hytera = !notification && !meig && applyHytera(context.getApplicationContext(), mode);
            Log.i("ARCH-LED", "profile=" + profile.label + " indicator=" + modeName(mode)
                    + " notification=" + notification + " meig=" + meig + " hytera=" + hytera);
            return notification || meig || hytera;
        }
        boolean notification = profile.isPncSmall() && applyNotificationLed(context, mode);
        boolean sysfs = !notification && applySysfs(profile, mode);
        boolean meig = !notification && !sysfs && profile.meigHardKey
                && applyMeig(context.getApplicationContext(), mode);
        boolean hytera = !notification && !sysfs && !meig
                && (profile.dreamKeyDirect || profile.dreamKeyBroadcast)
                && applyHytera(context.getApplicationContext(), mode);
        Log.i("ARCH-LED", "profile=" + profile.label + " indicator=" + modeName(mode)
                + " notification=" + notification + " sysfs=" + sysfs
                + " meig=" + meig + " hytera=" + hytera);
        return notification || sysfs || meig || hytera;
    }

    private static String modeName(int mode) {
        return mode == TRANSMIT ? "TX" : mode == RECEIVE ? "RX" : mode == IDLE ? "IDLE" : "NONE";
    }

    private static void clearPncSystemLeds(Context context) {
        try {
            android.content.Intent clear = new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED);
            clear.setComponent(new android.content.ComponentName("com.meigsmart.setting",
                    "com.meigsmart.setting.activitys.BootLedReceiver"));
            context.sendBroadcast(clear);
        } catch (Throwable ignored) {}
    }

    /**
     * Official HyTalk PDC760/PDC680 LED call, confirmed from
     * LedVibrationManagerImpl_NB + TerminalManagerCommercial:
     *
     *   idle    -> sendControlLed(0, 0, 0, 0, 1000, 0)
     *   transmit-> sendControlLed(0, 1, 1, 0, 1000, 0)
     *   receive -> sendControlLed(0, 1, 2, 0, 1000, 0)
     *
     * deviceIndex must be 0. Using deviceIndex=1 hits the information LED.
     */
    @android.annotation.SuppressLint("WrongConstant")
    private static boolean applyPdcOfficialLed(Context context, int mode) {
        if (vendorLedServiceUnavailable) return false;
        try {
            Object dsp = context.getSystemService("dsp_service");
            if (dsp == null) {
                vendorLedServiceUnavailable = true;
                Log.w("ARCH-LED", "dsp_service is null");
                return false;
            }
            Object common = dsp.getClass().getMethod("getCommonManager").invoke(dsp);
            if (common == null) {
                vendorLedServiceUnavailable = true;
                Log.w("ARCH-LED", "dsp common manager is null");
                return false;
            }
            int operation = mode == IDLE ? 0 : 1;
            int color = mode == TRANSMIT ? 1 : mode == RECEIVE ? 2 : 0;
            boolean sent = invokeLed(common, "sendControlLed",
                    0, operation, color, 0, 1000, 0);
            if (!sent) {
                vendorLedServiceUnavailable = true;
                Log.w("ARCH-LED", "official PDC LED interface unavailable");
            }
            return sent;
        } catch (Throwable t) {
            vendorLedServiceUnavailable = true;
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Log.w("ARCH-LED", "official PDC LED failed: "
                    + cause.getClass().getSimpleName() + " " + cause.getMessage());
            return false;
        }
    }

    private static boolean applyMeig(Context context, int mode) {
        if (meigUnavailable) return false;
        try {
            Class<?> cls = Class.forName("com.meige.autosdk.led.LedServiceManager");
            Object manager = cls.getMethod("getInstance").invoke(null);
            Method set = cls.getMethod("setCustomFlashing", Context.class, int.class, boolean.class);
            set.invoke(manager, context, 1, mode == TRANSMIT);  // LED_ID_SEND
            set.invoke(manager, context, 4, mode == RECEIVE);  // LED_ID_RECEIVE
            return true;
        } catch (Throwable t) {
            meigUnavailable = true;
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Log.w("ARCH-LED", "meig unavailable: " + cause.getClass().getSimpleName()
                    + " " + cause.getMessage());
            return false;
        }
    }

    private static boolean applySysfs(HyteraDeviceProfile profile, int mode) {
        boolean red = writeFirstWritable(profile.redLedNodes, mode == TRANSMIT ? 255 : 0);
        boolean green = writeFirstWritable(profile.greenLedNodes, mode == RECEIVE ? 40 : 0);
        return red && green;
    }

    private static boolean writeFirstWritable(String[] nodes, int value) {
        if (nodes == null) return false;
        for (String node : nodes) {
            if (writeLed(node, value)) return true;
        }
        return false;
    }

    private static boolean writeLed(String node, int value) {
        FileOutputStream out = null;
        try {
            File file = new File("/sys/class/leds/" + node + "/brightness");
            if (!file.exists() || !file.canWrite()) return false;
            out = new FileOutputStream(file);
            out.write(String.valueOf(value).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    private static boolean applyHytera(Context context, int mode) {
        if (hyteraUnavailable) return false;
        try {
            Object dsp = context.getSystemService("dsp_service");
            if (dsp == null) {
                Log.w("ARCH-LED", "dsp_service is null");
                try {
                    Class<?> dspClass = Class.forName("android.dsp.proxy.DspManager");
                    Log.i("ARCH-LED", "P50 DspManager class=" + dspClass
                            + " classloader=" + dspClass.getClassLoader());
                } catch (Throwable classError) {
                    Log.w("ARCH-LED", "P50 DspManager class unavailable: " + classError);
                }
                hyteraUnavailable = true;
                return false;
            }
            Object common = dsp.getClass().getMethod("getCommonManager").invoke(dsp);
            if (common == null) {
                Log.w("ARCH-LED", "dsp common manager is null");
                hyteraUnavailable = true;
                return false;
            }
            HyteraDeviceProfile profile = HyteraDeviceProfile.detect();
            boolean p50 = profile.isP50Pro();
            int ledIndex = p50 ? 0 : 1;
            int operation = mode == IDLE ? 0 : 1;
            int color = mode == IDLE ? 0 : (mode == TRANSMIT ? 1 : 2);
            int cycle = p50 ? 0 : (mode == IDLE ? 1 : 0);
            int onTime = p50 ? 1000 : (mode == IDLE ? 1 : 200);
            int offTime = p50 ? 0 : (mode == IDLE ? 1 : 200);
            boolean sent = invokeLed(common, "sendControlLed", ledIndex, operation, color, cycle, onTime, offTime);
            if (!sent) sent = invokeLed(common, "HRCPP_Control_LED", ledIndex, operation, color, cycle, onTime, offTime, -1);
            Log.i("ARCH-LED", "vendor led model=" + profile.label + " index=" + ledIndex
                    + " mode=" + mode + " operation=" + operation + " color=" + color
                    + " sent=" + sent);
            if (!sent) hyteraUnavailable = true;
            return sent;
        } catch (Throwable t) {
            Log.w("ARCH-LED", "pnc370 led failed: " + t);
            hyteraUnavailable = true;
            return false;
        }
    }

    private static boolean invokeLed(Object target, String name, int... args) {
        if (target == null) return false;
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            try {
                Object[] boxed = new Object[args.length];
                for (int i = 0; i < args.length; i++) boxed[i] = Integer.valueOf(args[i]);
                method.invoke(target, boxed);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
    static void noteP50PocConnected(Context context) {
        if (context == null || !HyteraDeviceProfile.detect().isP50Pro()) return;
        try {
            Intent intent = new Intent("com.dream.poc.connected");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("isConneted", "1");
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    static void noteP50PocDisconnected(Context context) {
        if (context == null || !HyteraDeviceProfile.detect().isP50Pro()) return;
        try {
            Intent intent = new Intent("com.dream.poc.disconneted");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("isConneted", "0");
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    /**
     * The P50 FactoryTest controls the MT6370 RGB PMU directly:
     * red/green/blue brightness = "1" for the current board, "0" for off.
     * Do not use notification onMS/offMS as a steady-state approximation here.
     */
    private static boolean applyP50RgbSysfs(int mode) {
        // Preflight all three nodes before touching any of them so a partial
        // write cannot leave the red or green channel stuck on.
        if (!isWritableAbsoluteLed(P50_RED_LED_PATH)
                || !isWritableAbsoluteLed(P50_GREEN_LED_PATH)
                || !isWritableAbsoluteLed(P50_BLUE_LED_PATH)) {
            Log.w("ARCH-LED", "P50 factory RGB sysfs is not writable from app UID");
            return false;
        }
        String red = mode == TRANSMIT ? "1" : "0";
        String green = mode == RECEIVE ? "1" : "0";
        boolean redOk = writeAbsoluteLed(P50_RED_LED_PATH, red);
        boolean greenOk = writeAbsoluteLed(P50_GREEN_LED_PATH, green);
        boolean blueOk = writeAbsoluteLed(P50_BLUE_LED_PATH, "0");
        boolean ok = redOk && greenOk && blueOk;
        if (!ok) {
            Log.w("ARCH-LED", "P50 factory RGB sysfs write failed red=" + redOk
                    + " green=" + greenOk + " blue=" + blueOk);
        }
        return ok;
    }

    private static boolean isWritableAbsoluteLed(String path) {
        try {
            File file = new File(path);
            return file.exists() && file.canWrite();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean writeAbsoluteLed(String path, String value) {
        FileOutputStream out = null;
        try {
            File file = new File(path);
            if (!file.exists() || !file.canWrite()) return false;
            out = new FileOutputStream(file);
            out.write(value.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w("ARCH-LED", "P50 factory RGB write failed " + path + ": " + t);
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    @SuppressWarnings("unchecked")
    private static boolean applyP50LightsManager(Context context, int mode) {
        if (Build.VERSION.SDK_INT < 31 || p50LightsUnavailable) return false;
        try {
            Object manager = context.getSystemService("lights");
            if (manager == null) {
                p50LightsUnavailable = true;
                Log.w("ARCH-LED", "P50 LightsManager service is null");
                return false;
            }
            if (p50LightsSession == null) {
                java.lang.reflect.Method getLights = manager.getClass().getMethod("getLights");
                Object rawLights = getLights.invoke(manager);
                if (!(rawLights instanceof java.util.List)) return false;
                java.util.List<Object> lights = (java.util.List<Object>) rawLights;
                for (Object light : lights) {
                    int id = (Integer) light.getClass().getMethod("getId").invoke(light);
                    Object nameObj = light.getClass().getMethod("getName").invoke(light);
                    Object typeObj = light.getClass().getMethod("getType").invoke(light);
                    String name = nameObj == null ? "" : String.valueOf(nameObj);
                    Log.i("ARCH-LED", "P50 light id=" + id + " name=" + name + " type=" + typeObj);
                    String lower = name.toLowerCase(java.util.Locale.US);
                    if (lower.contains("red") || lower.contains("tx") || lower.contains("send")) {
                        p50RedLights.add(light);
                    }
                    if (lower.contains("green") || lower.contains("rx") || lower.contains("receive")) {
                        p50GreenLights.add(light);
                    }
                }
                Object session = manager.getClass().getMethod("openSession").invoke(manager);
                if (session == null) return false;
                p50LightsSession = session;
            }
            java.util.List<Object> selected;
            int color;
            if (mode == TRANSMIT) {
                selected = p50RedLights;
                color = 0xFFFF0000;
            } else if (mode == RECEIVE) {
                selected = p50GreenLights;
                color = 0xFF00FF00;
            } else {
                selected = new java.util.ArrayList<>(p50RedLights);
                selected.addAll(p50GreenLights);
                color = 0;
            }
            if (selected.isEmpty()) {
                Log.w("ARCH-LED", "P50 red/green light names not exposed");
                return false;
            }
            java.util.ArrayList<Object> states = new java.util.ArrayList<>();
            for (Object light : selected) {
                int id = (Integer) light.getClass().getMethod("getId").invoke(light);
                Class<?> builderClass = Class.forName("android.hardware.lights.LightState$Builder");
                Object builder = builderClass.getConstructor(int.class).newInstance(id);
                builderClass.getMethod("setColor", int.class).invoke(builder, color);
                Object state = builderClass.getMethod("build").invoke(builder);
                states.add(state);
            }
            p50LightsSession.getClass().getMethod("requestLights", java.util.List.class)
                    .invoke(p50LightsSession, states);
            Log.i("ARCH-LED", "P50 LightsManager applied mode=" + modeName(mode)
                    + " count=" + states.size());
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Log.w("ARCH-LED", "P50 LightsManager unavailable: " + cause);
            p50LightsUnavailable = true;
            return false;
        }
    }

    private static boolean applyP50NotificationLed(Context context, int mode) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (mode == IDLE) {
                nm.cancel(P50_LED_NOTIFICATION_ID);
                return true;
            }
            int color = mode == TRANSMIT ? 0x00000001 : mode == RECEIVE ? 0x00000002 : 0; // P50 raw vendor colors
            int icon = context.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.ic_menu_call;
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                // Keep one stable channel. A per-state channel creates a
                // second channel after the first call and some P50 firmware
                // ignores the light setting on the second channel.
                String channelId = "arch_p50_status_led";
                NotificationChannel channel = new NotificationChannel(
                        channelId, "ARCH P50 收发状态灯",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.enableLights(true);
                channel.setLightColor(color);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
                builder = new Notification.Builder(context, channelId);
            } else {
                builder = new Notification.Builder(context);
            }
            builder.setContentTitle(mode == TRANSMIT ? "ARCH 正在发射" : "ARCH 正在接收")
                    .setSmallIcon(icon)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            Notification n = builder.build();
            // Some vendor NotificationManagerService builds only honor the
            // legacy fields when SHOW_LIGHTS is present explicitly.
            n.ledARGB = color;
            // P50 vendor NMS interprets ledOnMS/ledOffMS as flashing values.
            // Leave them at the platform defaults (zero) and expose only the
            // raw color + SHOW_LIGHTS; do not synthesize a steady state.
            n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR
                    | Notification.FLAG_SHOW_LIGHTS;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                n.contentIntent = PendingIntent.getActivity(context, 0, launch, flags);
            }
            // Use a private id, not PNC380's vendor id=1. On P50 id=1 is
            // used by the stock POC notification path and can be swallowed.
            nm.notify(P50_LED_NOTIFICATION_ID, n);
            Log.i("ARCH-LED", "P50 status notification led state=" + modeName(mode)
                    + " color=" + Integer.toHexString(color) + " onMs=" + n.ledOnMS
                    + " flags=" + Integer.toHexString(n.flags));
            return true;
        } catch (Throwable t) {
            Log.w("ARCH-LED", "P50 notification led failed: " + t);
            return false;
        }
    }

    private static boolean applyPnc380NotificationLed(Context context, int mode) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.w("ARCH-LED", "notification service is null");
                return false;
            }
            int color = mode == TRANSMIT ? 0xFFFF0000 : mode == RECEIVE ? 0xFF00FF00 : 0;
            int onMs = mode == IDLE ? 0 : 1000;
            int icon = context.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.ic_menu_call;

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        "arch_led", "ARCH 状态灯", NotificationManager.IMPORTANCE_LOW);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
                builder = new Notification.Builder(context, "arch_led");
            } else {
                builder = new Notification.Builder(context);
            }
            builder.setContentTitle("ARCH网络对讲")
                    .setContentText(mode == TRANSMIT ? "正在发射" : mode == RECEIVE ? "正在接收" : "待机")
                    .setSmallIcon(icon)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setPriority(Notification.PRIORITY_HIGH);
            if (onMs > 0) builder.setLights(color, onMs, 0);
            Notification n = builder.build();
            n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                n.contentIntent = PendingIntent.getActivity(context, 0, launch, flags);
            }

            // Replace atomically by the same id. Cancelling first creates a
            // visible off/on flicker between receive and transmit transitions.
            nm.notify(VENDOR_LED_NOTIFICATION_ID, n);
            Log.i("ARCH-LED", "notification led posted id=" + VENDOR_LED_NOTIFICATION_ID
                    + " state=" + modeName(mode) + " color=" + Integer.toHexString(color)
                    + " onMs=" + n.ledOnMS + " offMs=" + n.ledOffMS
                    + " flags=" + Integer.toHexString(n.flags));
            return true;
        } catch (Throwable t) {
            Log.w("ARCH-LED", "notification led failed: " + t);
            return false;
        }
    }

    private static boolean applyNotificationLed(Context context, int mode) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.w("ARCH-LED", "notification service is null");
                return false;
            }
            // Existing PNC/PNC370 vendor contract. Keep this path unchanged.
            int color = mode == TRANSMIT ? 0xFFFF0000 : mode == RECEIVE ? 0xFF00FF00 : 16776960;
            nm.cancel(VENDOR_LED_NOTIFICATION_ID);
            NetworkKeepAliveService.reassertNotification(context);
            Notification n = new Notification();
            n.icon = android.R.drawable.ic_menu_call;
            n.ledARGB = color;
            n.flags = 1024;
            nm.notify(VENDOR_LED_NOTIFICATION_ID, n);
            Log.i("ARCH-LED", "notification led posted id=" + VENDOR_LED_NOTIFICATION_ID
                    + " state=" + modeName(mode) + " color=" + Integer.toHexString(color));
            return true;
        } catch (Throwable t) {
            Log.w("ARCH-LED", "notification led failed: " + t);
            return false;
        }
    }
}
