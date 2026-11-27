package cn.arch.dmr;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PDC760 / 海能达 NB 双模终端兼容桥。
 *
 * 该实现只编译到 HYTERA_EDITION 并在运行时启用。它按 HyTalk 的 NB 路径绑定：
 * com.dream.service.3RD_PARTY_CALL_MANAGER_SERVICE
 * -> IDrmCallManagerService
 * -> IDrmCallSrvApi / IPocCallSrvApiEx
 * -> IPocCallSrvListener
 *
 * 已确认的 AIDL 事务号：
 * Manager.K() = 3, Manager.J() = 6
 * PocEx.register callback = 4, set client version = 6, get version = 7
 * CallSrv.notifyPocConnected = 49, setDefaultContactName = 51,
 * isPocAllowedInitiateCall = 54, start POC activity = 56,
 * clear POC activity = 44
 * Listener.onPTT = 4, channel button = 6
 */
@SuppressLint({"WrongConstant", "UnspecifiedRegisterReceiverFlag"})
public final class HyteraPdc760Bridge {
    private static final String TAG = "ARCH-HYTERA";
    private static final String ACTION_ENGINE_HYTERA = "com.hytera.service.HYTERA_CALL_SERVICE";
    private static final String ACTION_ENGINE_DREAM = "com.dream.service.DREAM_CALL_SERVICE";
    private static final String ACTION_MANAGER = "com.dream.service.3RD_PARTY_CALL_MANAGER_SERVICE";
    private static final String ACTION_THIRD_PARTY_PING = "android.intent.action.THIRD_PARTY_PING";
    private static final String ACTION_THIRD_PARTY_PONG = "android.intent.action.THIRD_PARTY_PONG";
    private static final String DESC_MANAGER = "com.dream.call.service.IDrmCallManagerService";
    private static final String DESC_CALL = "com.dream.callapi.IDrmCallSrvApi";
    private static final String DESC_POC_EX = "com.dream.callapi.IPocCallSrvApiEx";
    private static final String DESC_CALLBACK = "com.dream.callapi.IPocCallSrvListener";
    private static final int INTERFACE_TRANSACTION = 1598968902;

    private static volatile HyteraPdc760Bridge instance;
    private static volatile WeakReference<Listener> listenerRef = new WeakReference<>(null);

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final PocCallback callback = new PocCallback();
    private final StringBuilder lastStatus = new StringBuilder("未连接");
    private final BroadcastReceiver pingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_THIRD_PARTY_PING.equals(intent.getAction())) sendPong();
        }
    };

    private volatile boolean engineBound;
    private volatile boolean engineBinding;
    private volatile boolean managerBound;
    private volatile boolean binding;
    private volatile boolean connected;
    private volatile boolean pendingPttDown;
    private volatile String callPackageName = "com.dream.call";
    private volatile IBinder callSrv;
    private volatile IBinder pocEx;
    private volatile int sdkVersion = -1;
    private volatile String channelName = "ARCH 海能达";
    private volatile String callsign = "";
    private volatile String terminalId = "";
    private long lastEngineBindAt;
    private long lastBindAt;
    private long lastDownAt;
    private volatile String lastPocDisplayName = "";
    private volatile int lastPocDisplayStatus = -1;
    private volatile int lastPocDisplayType = -1;
    private volatile boolean nativeDisplayWarned;
    private volatile boolean pocActive;

    private final ServiceConnection engineConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            engineBound = true;
            engineBinding = false;
            status("海能达通话引擎已启动");
            log("DREAM_CALL_SERVICE 已连接: " + name);
            ui.postDelayed(HyteraPdc760Bridge.this::ensureBound, 300);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            engineBound = false;
            engineBinding = false;
            connected = false;
            callSrv = null;
            pocEx = null;
            status("海能达通话引擎已断开");
            log("DREAM_CALL_SERVICE 断开: " + name);
            scheduleRetry(1000);
        }

        @Override public void onBindingDied(ComponentName name) {
            engineBound = false;
            engineBinding = false;
            connected = false;
            callSrv = null;
            pocEx = null;
            scheduleRetry(1000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            managerBound = true;
            boolean registered = false;
            try {
                callSrv = binderCall(service, DESC_MANAGER, 3);
                pocEx = binderCall(service, DESC_MANAGER, 6);
                if (pocEx != null) {
                    setClientVersion();
                    registered = registerCallback();
                    sdkVersion = querySdkVersion();
                    log("POC 回调注册" + (registered ? "成功" : "返回失败") + "，终端 SDK=" + sdkVersion);
                } else {
                    log("系统未返回 IPocCallSrvApiEx");
                }
                if (callSrv != null && pocEx != null && registered) {
                    notifyConnected(true);
                    applyIdleDisplay(true);
                    sendPocIdentity();
                    connected = true;
                    sendPong();
                    status("已连接海能达终端调度服务");
                } else if (callSrv == null || pocEx == null) {
                    connected = false;
                    status("海能达引擎尚未就绪，正在重试");
                    rebindManagerLater(1200);
                } else {
                    connected = false;
                    status("海能达 POC 回调注册失败，正在重试");
                    rebindManagerLater(1800);
                }
            } catch (Throwable t) {
                connected = false;
                log("初始化海能达服务失败: " + t);
                status("初始化失败: " + shortError(t));
                scheduleRetry(3000);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            connected = false;
            managerBound = false;
            callSrv = null;
            pocEx = null;
            status("海能达终端服务已断开");
            log("onServiceDisconnected: " + name);
            scheduleRetry(1500);
        }

        @Override public void onBindingDied(ComponentName name) {
            connected = false;
            managerBound = false;
            callSrv = null;
            pocEx = null;
            binding = false;
            status("海能达终端服务绑定失效");
            scheduleRetry(1500);
        }
    };

    private HyteraPdc760Bridge(Context context) {
        app = context.getApplicationContext();
        try {
            IntentFilter pingFilter = new IntentFilter(ACTION_THIRD_PARTY_PING);
            if (Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(pingReceiver, pingFilter, Context.RECEIVER_EXPORTED);
            else
                app.registerReceiver(pingReceiver, pingFilter);
        } catch (Throwable t) {
            log("注册 THIRD_PARTY_PING 接收器失败: " + t);
        }
    }

    private void sendPong() {
        try {
            Intent pong = new Intent(ACTION_THIRD_PARTY_PONG);
            pong.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            if (callsign != null && !callsign.isEmpty()) pong.putExtra("PONG_USER_ID", callsign);
            app.sendBroadcast(pong);
            log("发送 THIRD_PARTY_PONG");
        } catch (Throwable t) {
            log("发送 THIRD_PARTY_PONG 失败: " + t);
        }
    }

    /** 只有 PDC/PTC 这类 Dream NB 双模机型才存在第三方 POC 调度服务。 */
    private static boolean supportsPdcBridge() {
        return BuildConfig.HYTERA_EDITION && HyteraDeviceProfile.detect().pdcBridge;
    }

    public static HyteraPdc760Bridge get(Context context) {
        HyteraPdc760Bridge local = instance;
        if (local == null) {
            synchronized (HyteraPdc760Bridge.class) {
                local = instance;
                if (local == null) {
                    local = new HyteraPdc760Bridge(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void install(Context context) {
        if (!supportsPdcBridge()) return;
        get(context).ensureBound();
    }

    public static void setDisplay(Context context, String name, String cs, String id) {
        if (!supportsPdcBridge()) return;
        HyteraPdc760Bridge b = get(context);
        String nextName = safe(name, "ARCH 海能达");
        boolean changed = !nextName.equals(b.channelName);
        b.channelName = nextName;
        b.callsign = safe(cs, "");
        b.terminalId = safe(id, "");
        b.ensureBound();
        // updateModeLabel() 会被心跳和页面刷新重复调用；只有频道真的变化时才
        // 重写系统 POC 状态，避免每次心跳都清一次通话状态。
        if (b.connected && !b.pocActive && !ArchAppState.hwTx
                && (changed || !nextName.equals(b.lastPocDisplayName) || b.lastPocDisplayStatus != 0)) {
            b.applyIdleDisplay(true);
            b.sendPocIdentity();
        }
    }

    public static String statusText(Context context) {
        if (!BuildConfig.HYTERA_EDITION) return "";
        return get(context).lastStatus.toString();
    }

    public static boolean consumePendingPttDown(Context context) {
        if (!supportsPdcBridge()) return false;
        HyteraPdc760Bridge b = get(context);
        boolean take = b.pendingPttDown && ArchAppState.hwTx;
        b.pendingPttDown = false;
        return take;
    }

    public static void noteActivityPttDown(Context context) {
        if (!supportsPdcBridge()) return;
        get(context).updatePocOnly(true);
    }

    public static void noteActivityPttUp(Context context) {
        if (!supportsPdcBridge()) return;
        get(context).updatePocOnly(false);
    }

    public static void setListener(Listener listener) {
        listenerRef = new WeakReference<>(listener);
    }

    public interface Listener {
        void onHyteraStatus(String message);
    }

    public void ensureBound() {
        if (!supportsPdcBridge()) return;
        if (!engineBound) ensureEngineBound();
        // 不等待引擎绑定成功，管理器和引擎并行连接；管理器绑定本身也会拉起系统 App。
        if (connected || binding) return;
        long now = System.currentTimeMillis();
        if (now - lastBindAt < 1200L) return;
        lastBindAt = now;
        binding = true;
        try {
            Intent intent = new Intent(ACTION_MANAGER);
            String pkg = resolveCallPackage(intent);
            if (pkg != null && !pkg.isEmpty()) callPackageName = pkg;
            intent.setPackage(callPackageName);
            boolean ok = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                binding = false;
                status("未找到海能达终端调度服务");
                scheduleRetry(4000);
            } else {
                log("正在绑定 " + intent.getPackage() + " / " + ACTION_MANAGER);
            }
        } catch (Throwable t) {
            binding = false;
            log("bindService 失败: " + t);
            status("绑定失败: " + shortError(t));
            scheduleRetry(4000);
        }
    }

    private void ensureEngineBound() {
        if (!supportsPdcBridge() || engineBound || engineBinding) return;
        long now = System.currentTimeMillis();
        if (now - lastEngineBindAt < 1200L) return;
        lastEngineBindAt = now;
        engineBinding = true;
        String[] actions = {ACTION_ENGINE_HYTERA, ACTION_ENGINE_DREAM};
        for (String action : actions) {
            try {
                Intent intent = new Intent(action);
                String pkg = resolveCallPackage(intent);
                if (pkg == null || pkg.isEmpty()) {
                    if ("com.hytera.call".equals(callPackageName) && ACTION_ENGINE_HYTERA.equals(action)) pkg = "com.hytera.call";
                    else if ("com.dream.call".equals(callPackageName) && ACTION_ENGINE_DREAM.equals(action)) pkg = "com.dream.call";
                }
                if (pkg == null || pkg.isEmpty()) continue;
                intent.setPackage(pkg);
                boolean ok = app.bindService(intent, engineConnection, Context.BIND_AUTO_CREATE);
                if (ok) {
                    callPackageName = pkg;
                    log("正在绑定海能达通话引擎: " + pkg + " / " + action);
                    return;
                }
            } catch (Throwable t) {
                log("绑定海能达通话引擎异常: " + action + " / " + t);
            }
        }
        engineBinding = false;
        status("未找到海能达通话引擎，继续绑定 POC 管理器");
        scheduleRetry(3000);
    }

    private String resolveCallPackage(Intent intent) {
        try {
            List<ResolveInfo> list = app.getPackageManager().queryIntentServices(intent, 0);
            if (list == null) return null;
            String fallback = null;
            for (ResolveInfo r : list) {
                if (r == null || r.serviceInfo == null) continue;
                String p = r.serviceInfo.packageName;
                if ("com.dream.call".equals(p)) return p;
                if (fallback == null && p != null && p.matches("com\\.[a-zA-Z0-9]+\\.call")) fallback = p;
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void rebindManagerLater(long delayMs) {
        ui.postDelayed(() -> {
            try { if (managerBound) app.unbindService(connection); } catch (Throwable ignored) {}
            managerBound = false;
            binding = false;
            connected = false;
            callSrv = null;
            pocEx = null;
            ensureBound();
        }, delayMs);
    }

    private void scheduleRetry(long delayMs) {
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, delayMs);
    }

    private final Runnable retry = () -> {
        if (!connected) ensureBound();
    };

    private void handleIncomingCall() {
        try {
            Prefs p = new Prefs(app);
            if (!p.hyteraIncomingVibrate()) return;
            android.os.Vibrator v = (android.os.Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(350L,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(350L);
            }
            log("海能达原生来电：已振动提醒");
        } catch (Throwable t) {
            log("海能达原生来电振动失败: " + t);
        }
    }

    private void handleChannelKnob(boolean next) {
        try {
            Prefs p = new Prefs(app);
            if (p.userExited() || !p.hyteraKnobEnabled()) return;
            boolean delivered = MainActivity.dispatchHyteraKnob(next);
            log("信道旋钮: " + (next ? "下一项" : "上一项") + (delivered ? "（已投递）" : "（无运行实例）"));
        } catch (Throwable t) {
            log("信道旋钮处理失败: " + t);
        }
    }

    private void handlePtt(boolean down) {
        if (!BuildConfig.HYTERA_EDITION) return;
        Prefs p = new Prefs(app);
        if (p.userExited() || !p.externalPttEnabled()) return;
        if (!p.externalPttBackground() && !ArchAppState.visible) return;

        if (down) {
            long now = System.currentTimeMillis();
            if (ArchAppState.hwTx) {
                // 广播通道可能先到；仍要把 POC 优先级补发给终端。
                updatePocOnly(true);
                return;
            }
            if (now - lastDownAt < 350L) return;
            lastDownAt = now;
            ArchAppState.hwTx = true;
            updatePocOnly(true);
            boolean interactive = true;
            try {
                android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
                interactive = pm == null || pm.isInteractive();
            } catch (Throwable ignored) {}
            // 关键：优先把按键直接投递给已经存在的 Activity。Activity 退到桌面后
            // 动态广播接收器并非总能在 Android 7 上可靠唤醒，直接调用可避免
            // “系统灯亮、ARCH 不发射”。
            boolean delivered = false;
            try { delivered = MainActivity.dispatchHardwarePtt(true); } catch (Throwable ignored) {}
            pendingPttDown = false;
            if (!delivered && !ArchAppState.visible && p.externalPttBackground() && interactive) {
                pendingPttDown = true;
                status("后台 PTT，正在恢复发射界面");
                bringMainToFront();
                ui.postDelayed(() -> {
                    if (pendingPttDown && !ArchAppState.visible && ArchAppState.hwTx) {
                        pendingPttDown = false;
                        sendLocal(ArchOverlayPttService.ACTION_DOWN);
                    }
                }, 750L);
            } else if (!delivered) {
                sendLocal(ArchOverlayPttService.ACTION_DOWN);
            }
            log("PTT DOWN channel=" + channelName + (delivered ? "（直接投递）" : ""));
            status("PTT 按下 · " + channelName);
        } else {
            pendingPttDown = false;
            updatePocOnly(false);
            if (!ArchAppState.hwTx) return;
            boolean delivered = false;
            try { delivered = MainActivity.dispatchHardwarePtt(false); } catch (Throwable ignored) {}
            ArchAppState.hwTx = false;
            if (!delivered) sendLocal(ArchOverlayPttService.ACTION_UP);
            log("PTT UP channel=" + channelName + (delivered ? "（直接投递）" : ""));
            status("PTT 松开 · " + channelName);
        }
    }

    private void bringMainToFront() {
        try {
            Intent i = new Intent(app, MainActivity.class)
                    .setAction("cn.arch.dmr.action.HYTERA_PTT_DOWN")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
            log("后台 PTT：已请求 ARCH 界面前置");
        } catch (Throwable t) {
            log("后台 PTT 拉起界面失败: " + t);
            pendingPttDown = false;
            sendLocal(ArchOverlayPttService.ACTION_DOWN);
        }
    }

    private void sendLocal(String action) {
        try {
            Intent i = new Intent(action).setPackage(app.getPackageName());
            app.sendBroadcast(i);
        } catch (Throwable t) {
            log("发送本地 PTT 事件失败: " + t);
        }
    }

    private synchronized void updatePocOnly(boolean active) {
        if (active) {
            notifyPocActive(true);
            setCallingContact(channelName, 5);
            setDefaultContactName(channelName);
            publishNativeDisplay(true, 5, true);
            pocActive = true;
        } else {
            notifyPocActive(false);
            // 空闲态只保留待机频道，不把当前说话人写成频道名。
            setCallingContact("", 7);
            setDefaultContactName(channelName);
            publishNativeDisplay(false, 7, true);
            pocActive = false;
        }
    }

    /** 更新待机/切组显示。先结束上一次 POC 通话状态，再写当前频道，避免旧组残留。 */
    private synchronized void applyIdleDisplay(boolean force) {
        if (callSrv == null || channelName == null || channelName.trim().isEmpty()) return;
        if (!pocActive && !ArchAppState.hwTx) {
            try { boolBoolCall(callSrv, DESC_CALL, 44, false, false); } catch (Throwable ignored) {}
            setCallingContact("", 7);
            setDefaultContactName(channelName);
        }
        publishNativeDisplay(false, 7, force);
    }

    private void notifyPocActive(boolean active) {
        IBinder call = callSrv;
        IBinder ex = pocEx;
        if (call == null) return;
        try {
            if (active) {
                boolean allowed = boolIntCall(call, DESC_CALL, 54, 0);
                log("isPocAllowedInitiateCall=" + allowed);
                if (!allowed) {
                    // 仍尝试提升 POC 状态；部分 PDC760 固件在 POC 刚绑定时会短暂返回 false。
                    log("系统短暂拒绝 POC 优先级，继续尝试激活");
                }
                if (sdkVersion > 1 && ex != null) {
                    boolIntIntCall(ex, DESC_POC_EX, 8, true, 0, 5);
                } else {
                    // PDC760 的 SDK 返回 0，海能达官方 NB 路径使用事务 56。
                    boolIntPairCall(call, DESC_CALL, 56, true, 0);
                }
            } else {
                // 官方 NB 结束通话路径是事务 44；不要再额外写 POCEx false，
                // 否则部分固件会把状态留在一个未结束的 POC 通话里。
                boolBoolCall(call, DESC_CALL, 44, false, false);
            }
        } catch (Throwable t) {
            log("设置 POC 通话状态失败: " + t);
        }
    }

    private void notifyConnected(boolean connectedState) {
        IBinder call = callSrv;
        if (call == null) return;
        try {
            boolCall(call, DESC_CALL, 49, connectedState);
        } catch (Throwable t) {
            log("notifyPocConnected 失败: " + t);
        }
    }

    private void setDefaultContactName(String name) {
        IBinder call = callSrv;
        if (call == null || name == null || name.isEmpty()) return;
        try {
            stringCall(call, DESC_CALL, 51, name);
            log("频道显示更新: " + name);
        } catch (Throwable t) {
            log("setDefaultContactName 失败: " + t);
        }
    }

    private synchronized void publishNativeDisplay(boolean callStatus, int callType) {
        publishNativeDisplay(callStatus, callType, false);
    }

    private synchronized void publishNativeDisplay(boolean callStatus, int callType, boolean force) {
        String name = channelName == null ? "" : channelName.trim();
        if (name.isEmpty()) return;
        int status = callStatus ? 1 : 0;
        if (!force && name.equals(lastPocDisplayName) && status == lastPocDisplayStatus && callType == lastPocDisplayType) return;
        lastPocDisplayName = name;
        lastPocDisplayStatus = status;
        lastPocDisplayType = callType;
        try {
            Intent intent = new Intent("com.poc.key");
            intent.putExtra("isPocContact", 1);
            intent.putExtra("pocContactName", name);
            intent.putExtra("pocSpeakerName", callStatus ? name : "");
            intent.putExtra("pocCallStatus", status);
            intent.putExtra("pocCallType", callType);
            intent.putExtra("isEmergencyCall", 0);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            app.sendBroadcast(intent);
            log("POC 顶屏广播: " + name + " status=" + status + " type=" + callType);
            // HytCallManager 自己也会发 com.poc.key；延迟再发一次，确保系统
            // AppWidget 最后收到的是 ARCH 当前频道，而不是旧频道。
            if (force) ui.postDelayed(() -> sendPocBroadcast(name, status, callType), 120L);
        } catch (Throwable t) {
            log("POC 顶屏广播失败: " + t);
        }
        try {
            Object dsp = app.getSystemService("dsp_service");
            if (dsp == null) return;
            Object wb = dsp.getClass().getMethod("getWBManager").invoke(dsp);
            if (wb == null) return;
            java.lang.reflect.Method m = wb.getClass().getMethod(
                    "HRCPP_WB_Broadcast_POCContactInfo", int.class, byte[].class, byte[].class);
            byte[] bytes = name.getBytes(StandardCharsets.UTF_16BE);
            m.invoke(wb, 1, bytes, null);
            log("窄带顶屏频道已写入: " + name);
        } catch (Throwable t) {
            if (!nativeDisplayWarned) {
                nativeDisplayWarned = true;
                log("窄带顶屏写入接口不可用: " + shortError(t));
            }
        }
    }

    private void sendPocBroadcast(String name, int status, int callType) {
        try {
            Intent intent = new Intent("com.poc.key");
            intent.putExtra("isPocContact", 1);
            intent.putExtra("pocContactName", name);
            intent.putExtra("pocSpeakerName", status == 1 ? name : "");
            intent.putExtra("pocCallStatus", status);
            intent.putExtra("pocCallType", callType);
            intent.putExtra("isEmergencyCall", 0);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            app.sendBroadcast(intent);
            log("POC 顶屏广播补发: " + name + " status=" + status + " type=" + callType);
        } catch (Throwable t) {
            log("POC 顶屏广播补发失败: " + t);
        }
    }

    private void setCallingContact(String talking, int type) {
        IBinder ex = pocEx;
        if (ex == null) return;
        try {
            stringStringIntCall(ex, DESC_POC_EX, 1, talking == null ? "" : talking,
                    channelName == null ? "" : channelName, type);
        } catch (Throwable t) {
            log("setCallingContactName 失败: " + t);
        }
    }

    private void sendPocIdentity() {
        try {
            Intent i = new Intent("com.poc.id");
            i.putExtra("pocPackageName", app.getPackageName());
            i.putExtra("pocId", terminalId == null ? "" : terminalId);
            i.putExtra("pocIdName", callsign == null ? "" : callsign);
            app.sendBroadcast(i);
        } catch (Throwable t) {
            log("发送 POC 身份失败: " + t);
        }
    }

    private void setClientVersion() throws RemoteException {
        IBinder ex = pocEx;
        if (ex == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_POC_EX);
            data.writeInt(13);
            data.writeString("20230608");
            ex.transact(6, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int querySdkVersion() {
        IBinder ex = pocEx;
        if (ex == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_POC_EX);
            ex.transact(7, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean registerCallback() throws RemoteException {
        IBinder ex = pocEx;
        if (ex == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_POC_EX);
            data.writeStrongBinder(callback);
            data.writeInt(1);
            data.writeString(app.getPackageName());
            ex.transact(4, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static IBinder binderCall(IBinder remote, String desc, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            remote.transact(code, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean boolIntCall(IBinder remote, String desc, int code, int value) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeInt(value);
            remote.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void boolCall(IBinder remote, String desc, int code, boolean value) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeInt(value ? 1 : 0);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void boolBoolCall(IBinder remote, String desc, int code, boolean a, boolean b) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeInt(a ? 1 : 0);
            data.writeInt(b ? 1 : 0);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void boolIntPairCall(IBinder remote, String desc, int code, boolean a, int b) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeInt(a ? 1 : 0);
            data.writeInt(b);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void boolIntIntCall(IBinder remote, String desc, int code, boolean a, int b, int c) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeInt(a ? 1 : 0);
            data.writeInt(b);
            data.writeInt(c);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void stringCall(IBinder remote, String desc, int code, String value) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeString(value);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void stringStringIntCall(IBinder remote, String desc, int code, String a, String b, int c) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            data.writeString(a);
            data.writeString(b);
            data.writeInt(c);
            remote.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void status(String message) {
        lastStatus.setLength(0);
        lastStatus.append(message);
        Listener l = listenerRef.get();
        if (l != null) {
            try { l.onHyteraStatus(message); } catch (Throwable ignored) {}
        }
    }

    private void log(String message) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + message;
        Log.i(TAG, message);
        try {
            File f = new File(app.getFilesDir(), "arch-hytera.log");
            if (f.exists() && f.length() > 512 * 1024) {
                File old = new File(app.getFilesDir(), "arch-hytera.log.old");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            File extDir = app.getExternalFilesDir(null);
            if (extDir != null) {
                if (!extDir.exists()) extDir.mkdirs();
                File ext = new File(extDir, "arch-hytera.log");
                try (FileOutputStream out = new FileOutputStream(ext, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String shortError(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private final class PocCallback extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESC_CALLBACK);
                return true;
            }
            switch (code) {
                case 2: // incoming platform call
                    data.enforceInterface(DESC_CALLBACK);
                    reply.writeNoException();
                    handleIncomingCall();
                    return true;
                case 3: // call end
                    data.enforceInterface(DESC_CALLBACK);
                    if (ArchAppState.hwTx) handlePtt(false);
                    reply.writeNoException();
                    return true;
                case 4: { // PTT down/up
                    data.enforceInterface(DESC_CALLBACK);
                    boolean down = data.readInt() != 0;
                    reply.writeNoException();
                    handlePtt(down);
                    return true;
                }
                case 5: // top button status
                    data.enforceInterface(DESC_CALLBACK);
                    data.readInt();
                    reply.writeNoException();
                    return true;
                case 6: // channel knob: true=next, false=previous
                    data.enforceInterface(DESC_CALLBACK);
                    boolean next = data.readInt() != 0;
                    reply.writeNoException();
                    handleChannelKnob(next);
                    return true;
                case 7: // extra top button
                    data.enforceInterface(DESC_CALLBACK);
                    data.readInt();
                    data.readInt();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}

