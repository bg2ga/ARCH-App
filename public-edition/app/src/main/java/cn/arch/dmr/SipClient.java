package cn.arch.dmr;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.pjsip.pjsua2.*;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Minimal single-call SIP client backed by the trimmed PJSIP build. */
final class SipClient {
    interface Listener {
        void onSipState(String state, boolean registered);
        void onIncoming(String remoteUri);
        void onCallState(String state, String remoteUri);
        void onError(String message);
        void onCallResourcesReady();
    }

    private static final ThreadLocal<Endpoint> REGISTERED_ENDPOINT = new ThreadLocal<>();

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Call> endedCalls = new ConcurrentLinkedQueue<>();
    // Do not delete a SWIG Call until the PJSIP callback which queued it has
    // fully returned. On older Android/PJSUA builds the director teardown
    // still touches its JavaVM after the Java callback returns.
    private static final long ENDED_CALL_CALLBACK_GRACE_MS = 1600L;
    private volatile long lastCallStateCallbackAt;

    private volatile Endpoint endpoint;
    private volatile SipAccount account;
    private volatile SipCall call;
    private volatile boolean started;
    private String domain;
    private String dialDomain;
    private MediaPlayer ringtone;
    private Vibrator vibrator;
    private volatile int ringGeneration;
    private AudioMedia connectedCallMedia;
    private boolean mediaConnected;

    SipClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isStarted() { return started; }

    void start(String server, int port, String number, String password) throws Exception {
        stop();
        HostPort target = parseHostPort(server, port);
        String user = number == null ? "" : number.trim();
        if (target.host.isEmpty() || user.isEmpty()) {
            throw new IllegalArgumentException("服务器或电话号为空");
        }

        domain = target.host;
        dialDomain = uriHost(target.host) + ":" + target.port;
        Endpoint created = new Endpoint();
        SipAccount createdAccount = null;
        try {
            created.libCreate();
            EpConfig ep = new EpConfig();
            ep.getUaConfig().setMaxCalls(1);
            LogConfig log = ep.getLogConfig();
            log.setLevel(2);
            log.setConsoleLevel(0);
            log.setMsgLogging(0);
            created.libInit(ep);
            TransportConfig transport = new TransportConfig();
            transport.setPort(0);
            created.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transport);
            created.libStart();

            endpoint = created;
            started = true;

            AccountConfig cfg = new AccountConfig();
            cfg.setIdUri("sip:" + user + "@" + uriHost(target.host));
            cfg.getRegConfig().setRegistrarUri("sip:" + uriHost(target.host) + ":" + target.port + ";transport=udp");
            cfg.getRegConfig().setTimeoutSec(300);
            cfg.getRegConfig().setRetryIntervalSec(30);
            AuthCredInfo auth = new AuthCredInfo("digest", "*", user, 0, password == null ? "" : password);
            AuthCredInfoVector creds = new AuthCredInfoVector();
            creds.add(auth);
            cfg.getSipConfig().setAuthCreds(creds);

            createdAccount = new SipAccount();
            account = createdAccount;
            createdAccount.create(cfg, true);
            notifyState("SIP 注册中…", false);
        } catch (Throwable t) {
            synchronized (lock) {
                if (endpoint == created) endpoint = null;
                account = null;
                started = false;
            }
            if (createdAccount != null) {
                try { createdAccount.shutdown(); } catch (Throwable ignored) {}
                try { createdAccount.delete(); } catch (Throwable ignored) {}
            }
            try { created.libDestroy(); } catch (Throwable ignored) {}
            try { created.delete(); } catch (Throwable ignored) {}
            resetAudio();
            throw new Exception(safeMessage(t), t);
        }
    }

    void dial(String target) throws Exception {
        registerCurrentThread("arch-sip-dial");
        Account currentAccount = account;
        if (!started || currentAccount == null) throw new IllegalStateException("请先注册 SIP");
        String dst = target == null ? "" : target.trim();
        if (dst.isEmpty()) throw new IllegalArgumentException("请输入电话号码");
        if (!dst.toLowerCase(Locale.US).startsWith("sip:")) dst = "sip:" + dst + "@" + dialDomain;

        SipCall created;
        synchronized (lock) {
            if (call != null) throw new IllegalStateException("已有通话");
            created = new SipCall(currentAccount);
            call = created;
        }
        try {
            created.makeCall(dst, new CallOpParam(true));
        } catch (Throwable t) {
            synchronized (lock) { if (call == created) call = null; }
            lastCallStateCallbackAt = android.os.SystemClock.elapsedRealtime();
            endedCalls.add(created);
            throw new Exception(safeMessage(t), t);
        }
    }

    void answer() throws Exception {
        registerCurrentThread("arch-sip-answer");
        SipCall current = call;
        if (current == null) return;
        CallInfo info = current.getInfo();
        if (info.getState() != pjsip_inv_state.PJSIP_INV_STATE_INCOMING) {
            // A fast hardware key can race the UI state change. Answering an
            // already-connecting/confirmed call is an invalid PJSIP operation.
            stopRingtone();
            return;
        }
        CallOpParam prm = new CallOpParam(true);
        prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
        current.answer(prm);
        stopRingtone();
    }

    void hangup() {
        registerCurrentThread("arch-sip-hangup");
        SipCall current = call;
        if (current == null) {
            stopRingtone();
            return;
        }
        try { current.hangup(new CallOpParam(true)); }
        catch (Throwable t) { notifyError("挂断失败: " + safeMessage(t)); }
        stopRingtone();
    }

    void setSpeaker(boolean on) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
        } catch (Throwable ignored) {}
    }

    void setEarpiece() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(false);
        } catch (Throwable ignored) {}
    }

    void resetAudio() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            // SIP/PJSUA can leave MODE_IN_COMMUNICATION and the earpiece selected.
            // Restore a safe speaker default; MainActivity re-applies the configured route.
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(true);
        } catch (Throwable ignored) {}
    }

    void stop() {
        registerCurrentThread("arch-sip-stop");
        stopRingtone();
        disconnectMedia();
        SipCall oldCall;
        SipAccount oldAccount;
        Endpoint oldEndpoint;
        synchronized (lock) {
            oldCall = call;
            oldAccount = account;
            oldEndpoint = endpoint;
            call = null;
            account = null;
            endpoint = null;
            started = false;
        }
        // Native Call objects must be released while their Endpoint still exists.
        // The generated Call wrapper's finalizer would otherwise call into a
        // destroyed PJSUA endpoint and can crash the process.
        if (oldCall != null) {
            try { oldCall.hangup(new CallOpParam(true)); } catch (Throwable ignored) {}
            lastCallStateCallbackAt = android.os.SystemClock.elapsedRealtime();
            endedCalls.add(oldCall);
        }
        reapEndedCalls();
        if (oldAccount != null) try { oldAccount.shutdown(); } catch (Throwable ignored) {}
        if (oldAccount != null) try { oldAccount.delete(); } catch (Throwable ignored) {}
        if (oldEndpoint != null) {
            try { oldEndpoint.libDestroy(); } catch (Throwable ignored) {}
            try { oldEndpoint.delete(); } catch (Throwable ignored) {}
        }
        REGISTERED_ENDPOINT.remove();
        resetAudio();
        notifyState("未注册", false);
    }

    void reapEndedCalls() {
        if (endedCalls.isEmpty()) return;
        registerCurrentThread("arch-sip-cleanup");
        long quietFor = android.os.SystemClock.elapsedRealtime() - lastCallStateCallbackAt;
        long remaining = ENDED_CALL_CALLBACK_GRACE_MS - quietFor;
        if (remaining > 0L) {
            android.util.Log.i("ARCH-SIP", "defer ended-call delete ms=" + remaining);
            try { Thread.sleep(remaining); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        Call old;
        while ((old = endedCalls.poll()) != null) {
            try { old.delete(); } catch (Throwable ignored) {}
        }
        android.util.Log.i("ARCH-SIP", "ended-call cleanup complete");
    }

    private void requestEndedCallCleanup() {
        try { listener.onCallResourcesReady(); } catch (Throwable ignored) {}
    }

    private void registerCurrentThread(String name) {
        Endpoint current = endpoint;
        if (current == null || REGISTERED_ENDPOINT.get() == current) return;
        try {
            current.libRegisterThread(name + "-" + Thread.currentThread().getId());
            REGISTERED_ENDPOINT.set(current);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-SIP", "线程注册失败: " + t.getMessage());
        }
    }

    private void playRingtone() {
        final int generation = ++ringGeneration;
        new Thread(() -> {
            if (ringtone != null || generation != ringGeneration) return;
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            Uri notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            boolean played = false;
            for (Uri uri : new Uri[]{ringtoneUri, notificationUri, alarmUri}) {
                if (uri == null || generation != ringGeneration) continue;
                MediaPlayer player = null;
                try {
                    player = new MediaPlayer();
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                    player.setAudioAttributes(attributes);
                    player.setDataSource(context, uri);
                    player.setLooping(true);
                    player.prepare();
                    player.start();
                    final MediaPlayer ready = player;
                    main.post(() -> {
                        if (generation != ringGeneration || ringtone != null) {
                            try { if (ready.isPlaying()) ready.stop(); } catch (Throwable ignored) {}
                            try { ready.release(); } catch (Throwable ignored) {}
                        } else {
                            ringtone = ready;
                        }
                    });
                    played = true;
                    break;
                } catch (Throwable ignored) {
                    if (player != null) try { player.release(); } catch (Throwable releaseError) {}
                }
            }
            if (!played && generation == ringGeneration) main.post(this::startVibration);
        }, "arch-sip-ringtone").start();
    }

    private void startVibration() {
        try {
            if (vibrator == null) vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = new long[]{0, 700, 500, 700, 1200};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void stopRingtone() {
        ringGeneration++;
        main.post(() -> {
            MediaPlayer old = ringtone;
            ringtone = null;
            if (old != null) {
                try { if (old.isPlaying()) old.stop(); } catch (Throwable ignored) {}
                try { old.release(); } catch (Throwable ignored) {}
            }
            try { if (vibrator != null) vibrator.cancel(); } catch (Throwable ignored) {}
        });
    }

    private void notifyState(String state, boolean registered) {
        try { listener.onSipState(state, registered); } catch (Throwable ignored) {}
    }

    private void notifyIncoming(String remote) {
        if (!HyteraDeviceProfile.FAMILY_PNC370.equals(HyteraDeviceProfile.detect().family)) playRingtone();
        try { listener.onIncoming(remote); } catch (Throwable ignored) {}
    }

    private void notifyCall(String state, String remote) {
        try { listener.onCallState(state, remote); } catch (Throwable ignored) {}
    }

    private void notifyError(String message) {
        try { listener.onError(message); } catch (Throwable ignored) {}
    }

    private static String terminalCallState(int code, String reason) {
        if (code == 603 || code == 487) return "已挂断";
        if (code == 486) return "对方忙线";
        if (code == 408 || code == 480) return "无人接听";
        if (code == 404) return "号码不存在";
        if (code >= 300) return "通话失败 " + code + (reason == null || reason.isEmpty() ? "" : " " + reason);
        return "通话结束";
    }

    private static String safeMessage(Throwable t) {
        String s = t == null ? "" : t.getMessage();
        return s == null || s.trim().isEmpty() ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : s;
    }

    private static String uriHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.contains(":") && !value.startsWith("[")) return "[" + value + "]";
        return value;
    }

    private static HostPort parseHostPort(String value, int fallbackPort) {
        String s = value == null ? "" : value.trim();
        if (s.toLowerCase(Locale.US).startsWith("sip:")) s = s.substring(4);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        s = s.replace("<", "").replace(">", "");
        int selectedPort = fallbackPort > 0 && fallbackPort <= 65535 ? fallbackPort : 5060;
        String host = s;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                host = s.substring(1, end);
                if (end + 1 < s.length() && s.charAt(end + 1) == ':') {
                    int embedded = parsePort(s.substring(end + 2));
                    if (embedded > 0) selectedPort = embedded;
                }
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon > 0 && colon < s.length() - 1 && s.indexOf(':') == colon) {
                int embedded = parsePort(s.substring(colon + 1));
                if (embedded > 0) {
                    host = s.substring(0, colon);
                    selectedPort = embedded;
                }
            }
        }
        return new HostPort(host, selectedPort);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535 ? port : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String host, int port) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
        }
    }

    private void disconnectMedia() {
        if (!mediaConnected || connectedCallMedia == null) return;
        try {
            Endpoint ep = endpoint;
            if (ep != null) {
                ep.audDevManager().getCaptureDevMedia().stopTransmit(connectedCallMedia);
                connectedCallMedia.stopTransmit(ep.audDevManager().getPlaybackDevMedia());
            }
        } catch (Throwable ignored) {}
        connectedCallMedia = null;
        mediaConnected = false;
    }

    private final class SipAccount extends Account {
        @Override public void onRegState(OnRegStateParam prm) {
            int code = prm.getCode();
            boolean ok = code >= 200 && code < 300 && prm.getExpiration() != 0;
            String reason = prm.getReason() == null ? "" : prm.getReason().trim();
            String msg = ok ? "SIP 已注册"
                    : "SIP 注册失败 code=" + code + (reason.isEmpty() ? "" : " " + reason);
            android.util.Log.i("ARCH-SIP", msg + " expiration=" + prm.getExpiration());
            notifyState(msg, ok);
        }

        @Override public void onIncomingCall(OnIncomingCallParam prm) {
            boolean busy;
            synchronized (lock) { busy = call != null; }
            if (busy) {
                rejectBusy(new SipCall(SipAccount.this, prm.getCallId(), false));
                return;
            }
            SipCall incoming = new SipCall(SipAccount.this, prm.getCallId(), true);
            boolean raceBusy;
            synchronized (lock) {
                raceBusy = call != null;
                if (!raceBusy) call = incoming;
            }
            if (raceBusy) {
                // PJSIP normally serializes callbacks, but close the race
                // anyway so a simultaneous second call is rejected with 486
                // and cannot publish call-state events for this account.
                rejectBusy(incoming);
                return;
            }
            String remote = "";
            try { remote = incoming.getInfo().getRemoteUri(); } catch (Throwable ignored) {}
            notifyIncoming(remote);
        }

        private void rejectBusy(SipCall rejected) {
            rejected.markUntracked();
            try {
                CallOpParam reject = new CallOpParam(true);
                reject.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
                rejected.answer(reject);
            } catch (Throwable ignored) {
                lastCallStateCallbackAt = android.os.SystemClock.elapsedRealtime();
                endedCalls.add(rejected);
                requestEndedCallCleanup();
            }
        }
    }

    private final class SipCall extends Call {
        private boolean tracked;
        SipCall(Account acc) { super(acc); tracked = true; }
        SipCall(Account acc, int id) { super(acc, id); tracked = true; }
        SipCall(Account acc, int id, boolean isTracked) { super(acc, id); tracked = isTracked; }
        void markUntracked() { tracked = false; }

        @Override public void onCallState(OnCallStateParam prm) {
            lastCallStateCallbackAt = android.os.SystemClock.elapsedRealtime();
            if (!tracked) {
                try {
                    CallInfo ignored = getInfo();
                    if (ignored.getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                        endedCalls.add(this);
                        requestEndedCallCleanup();
                    }
                } catch (Throwable ignored) {}
                return;
            }
            String remote = "";
            String state = "呼叫中";

            try {
                CallInfo ci = getInfo();
                remote = ci.getRemoteUri();
                int invState = ci.getState();
                if (invState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                    int code = ci.getLastStatusCode();
                    String reason = ci.getLastReason() == null ? "" : ci.getLastReason().trim();
                    state = terminalCallState(code, reason);
                    android.util.Log.i("ARCH-SIP", "call state=DISCONNECTED code=" + code);
                    synchronized (lock) { if (call == this) call = null; }
                    endedCalls.add(this);
                    requestEndedCallCleanup();
                    disconnectMedia();
                    stopRingtone();

                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                    state = "通话中";
                    stopRingtone();
                    android.util.Log.i("ARCH-SIP", "call state=CONFIRMED");
                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_CALLING) {
                    state = "拨号中";
                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
                    state = "振铃中";
                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_CONNECTING) {
                    state = "接听中";
                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_INCOMING) {
                    state = "来电";
                } else if (invState == pjsip_inv_state.PJSIP_INV_STATE_NULL) {
                    return;
                }
            } catch (Throwable t) {
                state = "通话状态异常";
            }
            notifyCall(state, remote);

        }

        @Override public void onCallMediaState(OnCallMediaStateParam prm) {
            if (!tracked) return;
            try {
                Endpoint ep = endpoint;
                if (ep == null) return;
                CallInfo ci = getInfo();
                CallMediaInfoVector media = ci.getMedia();
                if (media == null) return;
                boolean active = false;
                AudioMedia activeMedia = null;
                for (int i = 0; i < media.size(); i++) {
                    CallMediaInfo mi = media.get(i);
                    if (mi.getType() != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue;
                    if (mi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                        active = true;
                        activeMedia = getAudioMedia(i);
                    }
                }
                if (!active) {
                    disconnectMedia();
                    return;
                }
                if (mediaConnected && connectedCallMedia != null
                        && connectedCallMedia.getPortId() == activeMedia.getPortId()) return;
                if (mediaConnected) disconnectMedia();
                AudioMedia capture = ep.audDevManager().getCaptureDevMedia();
                AudioMedia playback = ep.audDevManager().getPlaybackDevMedia();
                capture.startTransmit(activeMedia);
                activeMedia.startTransmit(playback);
                connectedCallMedia = activeMedia;
                mediaConnected = true;
            } catch (Throwable t) {
                disconnectMedia();
                notifyError("SIP 音频通道连接失败");
            }
        }
    }
}
