package cn.arch.dmr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide SIP owner. Registration survives Phone Activity close and backgrounding. */
final class SipRuntime implements SipClient.Listener {
    interface Listener {
        void onSipState(String state, boolean registered);
        void onIncoming(String remoteUri);
        void onCallState(String state, String remoteUri);
        void onError(String message);
    }

    private static final String CALL_CHANNEL = "arch_sip_calls";
    private static final int CALL_NOTIFICATION_ID = 4701;
    private static SipRuntime instance;

    static synchronized SipRuntime get(Context context) {
        if (instance == null) instance = new SipRuntime(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arch-sip-runtime");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();

    private volatile SipClient client;
    private volatile boolean enabled;
    private volatile boolean registered;
    private volatile String sipState = "未注册";
    private volatile String callState = "";
    private volatile String callRemote = "";
    private volatile boolean localHangupRequested;
    private volatile boolean starting;
    private volatile int retryStep;
    private final java.util.concurrent.atomic.AtomicInteger hangupGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private Runnable retryRunnable;
    private volatile Runnable hangupTimeout;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile String appliedHost = "";
    private volatile String appliedUser = "";
    private volatile String appliedPassword = "";
    private volatile int appliedPort = 5060;
    private volatile boolean appliedEnabled;

    private SipRuntime(Context context) {
        this.context = context;
        registerNetworkCallback();
    }

    void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        ui.post(() -> {
            if (!listeners.contains(listener)) return;
            listener.onSipState(sipState, registered);
            if ("来电".equals(callState)) listener.onIncoming(callRemote);
            else if (callState != null && !callState.isEmpty()) listener.onCallState(callState, callRemote);
        });
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    boolean isRegistered() { return registered; }
    String getSipState() { return sipState; }
    String getCallState() { return callState; }
    String getCallRemote() { return callRemote; }
    boolean isCallLive() {
        String state = callState;
        return "来电".equals(state) || "拨号中".equals(state) || "振铃中".equals(state)
                || "接听中".equals(state) || "通话中".equals(state) || "正在挂断…".equals(state);
    }

    void ensureStarted() {
        Prefs p = new Prefs(context);
        enabled = p.sipEnabled() && !configHost(p).isEmpty() && !configUser(p).isEmpty();
        if (!enabled) {
            terminateForDestructiveChange();
            worker.execute(() -> reconnectLocked(false));
            setStoppedState("SIP 未启用");
            return;
        }
        worker.execute(() -> {
            synchronized (stateLock) {
                if (client != null && client.isStarted()) return;
                startLocked();
            }
        });
    }

    void reloadConfig() {
        Prefs p = new Prefs(context);
        boolean nextEnabled = p.sipEnabled() && !configHost(p).isEmpty() && !configUser(p).isEmpty();
        String host = configHost(p);
        String user = configUser(p);
        String password = p.get("sipPassword", "");
        int port;
        try { port = Integer.parseInt(p.get("sipPort", "5060")); }
        catch (Throwable t) { port = 5060; }
        SipClient current = client;
        boolean unchanged = nextEnabled && appliedEnabled && current != null && current.isStarted()
                && host.equals(appliedHost) && user.equals(appliedUser)
                && password.equals(appliedPassword) && port == appliedPort;
        if (unchanged) {
            enabled = true;
            return;
        }
        enabled = nextEnabled;
        retryStep = 0;
        cancelRetry();
        terminateForDestructiveChange();
        worker.execute(() -> reconnectLocked(false));
    }

    void setEnabled(boolean on) {
        Prefs p = new Prefs(context);
        if (!on) {
            enabled = false;
            appliedEnabled = false;
            cancelRetry();
            terminateForDestructiveChange();
            worker.execute(() -> reconnectLocked(false));
            setStoppedState("SIP 已关闭");
        } else {
            enabled = !configHost(p).isEmpty() && !configUser(p).isEmpty();
            if (enabled) reloadConfig();
            else setStoppedState("SIP 未配置");
        }
    }

    void reconnect() {
        Prefs p = new Prefs(context);
        enabled = p.sipEnabled() && !configHost(p).isEmpty() && !configUser(p).isEmpty();
        if (!enabled || registered || isCallLive()) return;
        cancelRetry();
        worker.execute(() -> reconnectLocked(true));
    }

    void dial(String target) {
        localHangupRequested = false;
        if (!registered) {
            notifyError("SIP 未注册，正在自动重连");
            ensureStarted();
            return;
        }
        worker.execute(() -> {
            try {
                SipClient current = client;
                if (current == null) throw new IllegalStateException("SIP 尚未连接");
                current.dial(target);
            } catch (Throwable t) {
                String message = "拨号失败: " + safeMessage(t);
                callState = "通话失败 " + safeMessage(t);
                callRemote = target;
                notifyError(message);
                notifyCall(callState, callRemote);
                SipClient current = client;
                if (current != null) worker.execute(current::reapEndedCalls);
            }
        });
    }

    void answer() {
        localHangupRequested = false;
        if (!isCallLive() || (!"来电".equals(callState) && !"振铃中".equals(callState))) return;
        if ("接听中".equals(callState) || "通话中".equals(callState)) return;
        callState = "接听中";
        notifyCall(callState, callRemote);
        worker.execute(() -> {
            try {
                SipClient current = client;
                if (current != null) current.answer();
            } catch (Throwable t) {
                notifyError("接听失败: " + safeMessage(t));
            }
        });
    }

    void hangup() {
        if (!isCallLive() && callState.isEmpty()) return;
        localHangupRequested = true;
        callState = "正在挂断…";
        notifyCall(callState, callRemote);
        worker.execute(() -> {
            try {
                SipClient current = client;
                if (current != null) current.hangup();
            } catch (Throwable ignored) {}
        });
        cancelHangupTimeout();
        final int generation = hangupGeneration.incrementAndGet();
        hangupTimeout = () -> {
            if (generation != hangupGeneration.get() || !isCallLive()) return;
            hangupTimeout = null;
            localHangupRequested = false;
            callState = "已挂断";
            notifyCall(callState, callRemote);
            clearIncomingNotification();
            MainActivity.restoreAudioRouteAfterSip();
            worker.execute(() -> reconnectLocked(false));
        };
        ui.postDelayed(hangupTimeout, 2500L);
    }

    void setSpeaker(boolean on) {
        SipClient current = client;
        if (current != null) current.setSpeaker(on);
    }

    void setEarpiece() {
        SipClient current = client;
        if (current != null) current.setEarpiece();
    }

    void resetAudio() {
        SipClient current = client;
        if (current != null) current.resetAudio();
    }

    private void terminateForDestructiveChange() {
        if (isCallLive()) {
            callState = "通话结束";
            notifyCall(callState, callRemote);
        }
        cancelHangupTimeout();
        clearIncomingNotification();
    }

    private void reconnectLocked(boolean force) {
        // A retry task may already be queued when registration succeeds. Recheck
        // on the worker thread immediately before destroying a healthy client.
        if (force && registered) return;
        starting = true;
        try {
            if (isCallLive()) {
                callState = "通话结束";
                notifyCall(callState, callRemote);
            }
            SipClient old = client;
            client = null;
            if (old != null) old.stop();
            appliedEnabled = false;
            registered = false;
            if (enabled) startLocked();
            else setStoppedState("SIP 已关闭");
        } finally {
            starting = false;
        }
    }

    private void startLocked() {
        Prefs p = new Prefs(context);
        if (!enabled) return;
        String host = configHost(p);
        String user = configUser(p);
        String pass = p.get("sipPassword", "");
        int port;
        try { port = Integer.parseInt(p.get("sipPort", "5060")); }
        catch (Throwable t) { port = 5060; }
        if (host.isEmpty() || user.isEmpty()) {
            enabled = false;
            setStoppedState("SIP 未配置");
            return;
        }
        starting = true;
        SipClient created = null;
        try {
            created = new SipClient(context, this);
            client = created;
            created.start(host, port, user, pass);
            appliedHost = host;
            appliedUser = user;
            appliedPassword = pass;
            appliedPort = port;
            appliedEnabled = true;
        } catch (Throwable t) {
            if (created != null) try { created.stop(); } catch (Throwable ignored) {}
            if (client == created) client = null;
            appliedEnabled = false;
            registered = false;
            sipState = "SIP 启动失败";
            notifyState(sipState + ": " + safeMessage(t), false);
            ui.post(this::scheduleRetry);
        } finally {
            starting = false;
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    ui.post(() -> {
                        if (enabled && !registered && !starting && !isCallLive()) reconnect();
                    });
                }
                @Override public void onLost(Network network) {
                    ui.post(() -> {
                        if (!enabled) return;
                        registered = false;
                        sipState = "网络断开，等待重连";
                        notifyState(sipState, false);
                        scheduleRetry();
                    });
                }
            };
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback);
            else cm.registerNetworkCallback(request, networkCallback);
        } catch (Throwable ignored) {}
    }

    private void scheduleRetry() {
        ui.post(() -> {
            if (!enabled || starting || retryRunnable != null || isCallLive()) return;
            if (registered) return;
            int delay = Math.min(30000, 2000 << Math.min(retryStep++, 4));
            retryRunnable = () -> {
                retryRunnable = null;
                if (!enabled || starting || registered || isCallLive()) return;
                reconnect();
            };
            ui.postDelayed(retryRunnable, delay);
        });
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            ui.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    private void cancelHangupTimeout() {
        hangupGeneration.incrementAndGet();
        if (hangupTimeout != null) {
            ui.removeCallbacks(hangupTimeout);
            hangupTimeout = null;
        }
    }

    private void setStoppedState(String state) {
        registered = false;
        sipState = state == null ? "未注册" : state;
        notifyState(sipState, false);
    }

    private static String configHost(Prefs p) {
        return p.get("sipServer", "").trim();
    }

    private static String configUser(Prefs p) {
        return p.get("sipNumber", "").trim();
    }

    private static String safeMessage(Throwable t) {
        String s = t == null ? "" : t.getMessage();
        return s == null || s.trim().isEmpty() ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : s;
    }

    @Override public void onSipState(String state, boolean ok) {
        regState(state, ok);
    }

    @Override public void onIncoming(String remoteUri) {
        onIncomingCall(remoteUri);
    }

    @Override public void onCallState(String state, String remoteUri) {
        String nextState = state == null ? "" : state;
        boolean terminal = nextState.startsWith("通话结束")
                || nextState.startsWith("通话失败")
                || nextState.startsWith("已挂断")
                || nextState.startsWith("对方忙线")
                || nextState.startsWith("无人接听")
                || nextState.startsWith("号码不存在");
        if (terminal && localHangupRequested) nextState = "已挂断";
        callState = nextState;
        if (remoteUri != null && !remoteUri.isEmpty()) callRemote = remoteUri;
        if (!"来电".equals(callState)) clearIncomingNotification();
        if (terminal) {
            localHangupRequested = false;
            cancelHangupTimeout();
            SipClient current = client;
            if (current != null) {
                current.resetAudio();
                worker.execute(current::reapEndedCalls);
            }
            MainActivity.restoreAudioRouteAfterSip();
            if (enabled && !registered) scheduleRetry();
        }
        notifyCall(callState, callRemote);
    }

    @Override public void onError(String message) {
        notifyError(message);
    }

    @Override public void onCallResourcesReady() {
        SipClient current = client;
        if (current != null) worker.execute(current::reapEndedCalls);
    }

    private void regState(String state, boolean ok) {
        sipState = state == null ? "未注册" : state;
        registered = ok;
        if (ok) {
            retryStep = 0;
            cancelRetry();
        } else if (!starting && !sipState.contains("注册中") && !isCallLive()) {
            scheduleRetry();
        }
        notifyState(sipState, ok);
    }

    private void onIncomingCall(String remoteUri) {
        localHangupRequested = false;
        callState = "来电";
        callRemote = remoteUri == null ? "" : remoteUri;
        ui.post(() -> {
            if (!"来电".equals(callState)) return;
            showIncomingNotification(callRemote);
            notifyIncoming(callRemote);
            try {
                if (!SipPhoneActivity.isVisible()) {
                    Intent launch = new Intent(context, SipPhoneActivity.class);
                    launch.putExtra("sip_incoming_call", true);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    context.startActivity(launch);
                }
            } catch (Throwable t) {
                android.util.Log.w("ARCH-SIP", "incoming activity launch blocked", t);
            }
        });
    }

    private void showIncomingNotification(String remote) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CALL_CHANNEL, "SIP 来电",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, SipPhoneActivity.class);
            open.putExtra("sip_incoming_call", true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent fullScreen = PendingIntent.getActivity(context, 4701, open, pendingFlags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CALL_CHANNEL)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle("SIP 来电")
                    .setContentText(remote == null || remote.isEmpty() ? "未知号码" : remote)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(fullScreen);
            boolean canUseFullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
            if (canUseFullScreen) builder.setFullScreenIntent(fullScreen, true);
            if (Build.VERSION.SDK_INT < 26) builder.setPriority(Notification.PRIORITY_MAX);
            nm.notify(CALL_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {}
    }

    void clearIncomingNotification() {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CALL_NOTIFICATION_ID);
        } catch (Throwable ignored) {}
    }

    private void notifyState(String state, boolean ok) {
        ui.post(() -> {
            for (Listener l : listeners) try { l.onSipState(state, ok); } catch (Throwable ignored) {}
        });
    }

    private void notifyIncoming(String remote) {
        ui.post(() -> {
            for (Listener l : listeners) try { l.onIncoming(remote); } catch (Throwable ignored) {}
        });
    }

    private void notifyCall(String state, String remote) {
        ui.post(() -> {
            for (Listener l : listeners) try { l.onCallState(state, remote); } catch (Throwable ignored) {}
        });
    }

    private void notifyError(String message) {
        ui.post(() -> {
            for (Listener l : listeners) try { l.onError(message); } catch (Throwable ignored) {}
        });
    }
}
