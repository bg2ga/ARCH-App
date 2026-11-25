package cn.arch.dmr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.List;

import cn.arch.dmr.NetworkRoomClient.Listener;

/**
 * Process-level owner for the network room connection.
 *
 * The foreground keep-alive service creates and retains this runtime, while
 * MainActivity only attaches as a temporary UI/audio listener. This prevents an
 * Activity recreation from tearing down the room or opening a second connection
 * with the same stable deviceId.
 */
final class NetworkRuntime implements NetworkRoomClient.Listener {
    static final String VOICE_URL = "wss://hljrsa.cn:50113/voice";
    private static final int HANDOFF_MAX_FRAMES = 20;
    private static final long HANDOFF_END_DELAY_MS = 220L;

    private static volatile NetworkRuntime instance;

    private static final class VoiceFrame {
        final int dmrId;
        final String callsign;
        final short[] pcm;
        VoiceFrame(int dmrId, String callsign, short[] pcm) {
            this.dmrId = dmrId;
            this.callsign = callsign;
            this.pcm = pcm;
        }
    }

    private final Context app;
    private final NetworkRoomClient client;
    private final android.os.PowerManager powerManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Object handoffLock = new Object();
    private final ArrayDeque<VoiceFrame> handoffFrames = new ArrayDeque<>();

    private volatile Listener uiListener;
    private volatile Listener backgroundListener;
    private volatile Listener activeVoiceSink;
    private volatile boolean screenInteractive = true;
    private volatile boolean wanted;
    private volatile String targetRoom = "";
    private String connectedRoom = "";
    private int reconnectAttempt;

    private boolean backgroundOutputBlocked;
    private int handoffSerial;
    private int activeHandoffToken;
    private boolean handoffVoiceEnded;
    private int voiceEventGeneration;

    private final NetworkRecovery.Listener networkRecoveryListener = new NetworkRecovery.Listener() {
        @Override public void onNetworkLost() {
            main.removeCallbacks(reconnectTask);
        }

        @Override public void onNetworkRestored() {
            synchronized (lock) {
                reconnectAttempt = 0;
                main.removeCallbacks(reconnectTask);
                if (wanted && !client.isRunning() && !client.isConnecting() && !client.hasPermanentError()) {
                    connectLocked(true);
                }
            }
        }
    };

    private final Runnable reconnectTask = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                if (!wanted || client.isRunning() || client.isConnecting() || client.hasPermanentError()) return;
                connectLocked(false);
            }
        }
    };

    static NetworkRuntime get(Context context) {
        NetworkRuntime local = instance;
        if (local == null) {
            synchronized (NetworkRuntime.class) {
                local = instance;
                if (local == null) {
                    local = new NetworkRuntime(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private NetworkRuntime(Context app) {
        this.app = app;
        this.client = new NetworkRoomClient(app);
        this.client.setListener(this);
        this.powerManager = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
        this.screenInteractive = powerManager == null || powerManager.isInteractive();
    }

    NetworkRoomClient client() { return client; }

    /** Attach the visible Activity as the sole UI/audio consumer. */
    void attachUi(Listener listener) {
        if (listener == null) return;
        Listener oldBackground = backgroundListener;
        // Block and stop the service sink before publishing the Activity as owner.
        synchronized (handoffLock) {
            uiListener = null;
            backgroundOutputBlocked = true;
        }
        if (oldBackground != null) safeVoiceEnded(oldBackground);
        boolean ended;
        int endedGeneration;
        synchronized (handoffLock) {
            backgroundOutputBlocked = false;
            activeHandoffToken = 0;
            voiceEventGeneration++;
            uiListener = listener;
            // Publish connection state first so buffered voice can set the final
            // "receiving" state after it, not the other way around.
            safeState(listener, client.isRunning() ? 1 : 0,
                    client.isRunning() ? "connected" : (client.isConnecting() ? "connecting" : "not connected"));
            // Deliver buffered frames before releasing the ownership lock so a new
            // live frame cannot overtake older handoff audio.
            while (!handoffFrames.isEmpty()) {
                VoiceFrame frame = handoffFrames.removeFirst();
                safeVoice(listener, frame.dmrId, frame.callsign, frame.pcm);
            }
            ended = handoffVoiceEnded;
            handoffVoiceEnded = false;
            endedGeneration = voiceEventGeneration;
        }
        if (ended) {
            final int expected = endedGeneration;
            main.postDelayed(() -> {
                synchronized (handoffLock) {
                    if (uiListener != listener || expected != voiceEventGeneration) return;
                }
                safeVoiceEnded(listener);
            }, HANDOFF_END_DELAY_MS);
        }
    }

    /**
     * Detach the Activity and return a one-time handoff token. The service sink remains
     * blocked until the Activity confirms its AudioTrack has fully closed.
     */
    int detachUi(Listener listener) {
        if (listener == null) return 0;
        synchronized (handoffLock) {
            if (uiListener != listener) return 0;
            uiListener = null;
            if (activeVoiceSink == listener) activeVoiceSink = null;
            backgroundOutputBlocked = true;
            handoffVoiceEnded = false;
            activeHandoffToken = ++handoffSerial;
            return activeHandoffToken;
        }
    }

    /** Release the matching handoff token after the Activity AudioTrack is fully closed. */
    void completeUiDetach(Listener listener, int token) {
        if (listener == null || token <= 0) return;
        boolean ended = false;
        int endedGeneration = 0;
        synchronized (handoffLock) {
            if (uiListener != null || token != activeHandoffToken) return;
            backgroundOutputBlocked = false;
            activeHandoffToken = 0;
            Listener background = backgroundListener;
            if (background != null) activeVoiceSink = background;
            while (!handoffFrames.isEmpty() && background != null) {
                VoiceFrame frame = handoffFrames.removeFirst();
                safeVoice(background, frame.dmrId, frame.callsign, frame.pcm);
            }
            handoffFrames.clear();
            ended = handoffVoiceEnded;
            handoffVoiceEnded = false;
            endedGeneration = voiceEventGeneration;
        }
        if (ended) {
            final int expected = endedGeneration;
            main.postDelayed(() -> {
                synchronized (handoffLock) {
                    if (uiListener != null || expected != voiceEventGeneration) return;
                }
                safeVoiceEnded(backgroundListener);
            }, HANDOFF_END_DELAY_MS);
        }
    }

    /** Called by the foreground service when the physical display turns on/off. */
    void onScreenInteractiveChanged(boolean interactive) {
        synchronized (handoffLock) {
            if (screenInteractive == interactive) return;
            screenInteractive = interactive;
            Listener old = activeVoiceSink;
            activeVoiceSink = null;
            if (old != null) safeVoiceEnded(old);
            android.util.Log.i("ARCH-NET-RX", "screen interactive=" + interactive
                    + " voiceSink=" + (interactive ? "UI_OR_SERVICE" : "SERVICE"));
        }
    }

    /** Select the owner of receive audio. Screen-off always prefers the foreground service. */
    private Listener selectVoiceListenerLocked() {
        if (!screenInteractive && backgroundListener != null) {
            return backgroundOutputBlocked ? null : backgroundListener;
        }
        if (uiListener != null) return uiListener;
        return backgroundOutputBlocked ? null : backgroundListener;
    }

    /** Service-owned fallback listener. It is used only when no Activity is attached. */
    void setBackgroundListener(Listener listener) {
        backgroundListener = listener;
    }

    void clearBackgroundListener(Listener listener) {
        if (listener != null && backgroundListener == listener) backgroundListener = null;
    }

    /** Connect or keep the requested room alive. Repeated calls for the same live room are no-ops. */
    void connect(String room) {
        Prefs p = new Prefs(app);
        if (!p.authLoggedIn()) return;
        String requested = room == null || room.trim().isEmpty() ? p.netRoom() : room.trim();
        boolean alreadyLive = false;
        synchronized (lock) {
            wanted = true;
            targetRoom = requested;
            main.removeCallbacks(reconnectTask);
            alreadyLive = requested.equals(connectedRoom) && (client.isRunning() || client.isConnecting());
            if (!alreadyLive) {
                reconnectAttempt = 0;
                connectLocked(true);
            }
        }
        if (alreadyLive) emitState(client.isRunning() ? 1 : 0, client.isRunning() ? "connected" : "connecting");
    }

    /** Restore the connection after service/process restart without changing the selected room. */
    void ensureConfiguredConnection() {
        Prefs p = new Prefs(app);
        if (!p.authLoggedIn() || !p.showNetwork()) {
            disconnect();
            return;
        }
        synchronized (lock) {
            wanted = true;
            targetRoom = p.netRoom();
            main.removeCallbacks(reconnectTask);
            if (client.isRunning() || client.isConnecting()) return;
            connectLocked(false);
        }
    }

    /** Explicit disable/logout/configuration stop. Automatic reconnect is cancelled. */
    void disconnect() {
        synchronized (lock) {
            wanted = false;
            targetRoom = "";
            connectedRoom = "";
            reconnectAttempt = 0;
            main.removeCallbacks(reconnectTask);
            synchronized (handoffLock) {
                handoffFrames.clear();
                handoffVoiceEnded = false;
                backgroundOutputBlocked = false;
                activeHandoffToken = 0;
                voiceEventGeneration++;
            }
            client.stopFast();
        }
    }

    private void connectLocked(boolean force) {
        Prefs p = new Prefs(app);
        if (!wanted || !p.authLoggedIn() || targetRoom == null || targetRoom.isEmpty()) return;
        if (!NetworkRecovery.get(app).isOnline()) return;
        if (!force && (client.isRunning() || client.isConnecting())) return;
        connectedRoom = targetRoom;
        client.connectWithTokenServer(VOICE_URL, targetRoom, p.callsign(), p.callsign(),
                p.dmrId(), p.essid(), p.password());
    }

    private void scheduleReconnect() {
        final int attempt;
        synchronized (lock) {
            if (!wanted || client.hasPermanentError()) return;
            if (!NetworkRecovery.get(app).isOnline()) return;
            attempt = Math.min(6, reconnectAttempt++);
            main.removeCallbacks(reconnectTask);
            long delayMs = Math.min(15000L, 2000L << attempt);
            main.postDelayed(reconnectTask, delayMs);
        }
    }

    private Listener controlListener() {
        Listener ui = uiListener;
        return ui != null ? ui : backgroundListener;
    }

    private static void safeState(Listener listener, int state, String message) {
        if (listener == null) return;
        try { listener.onState(state, message); } catch (Throwable ignored) {}
    }

    private static void safeVoice(Listener listener, int dmrId, String callsign, short[] pcm) {
        if (listener == null) return;
        try { listener.onVoice(dmrId, callsign, pcm); } catch (Throwable ignored) {}
    }

    private static void safeVoiceEnded(Listener listener) {
        if (listener == null) return;
        try { listener.onVoiceEnded(); } catch (Throwable ignored) {}
    }

    private void emitState(int state, String message) {
        safeState(controlListener(), state, message);
    }

    @Override public void onState(int state, String message) {
        if (state == 1) {
            synchronized (lock) { reconnectAttempt = 0; }
        } else if (state == 3) {
            synchronized (lock) { connectedRoom = ""; }
            if (new Prefs(app).autoReconnect()) scheduleReconnect();
        }
        emitState(state, message);
    }

    @Override public void onLog(String message) {
        Listener l = controlListener();
        if (l != null) try { l.onLog(message); } catch (Throwable ignored) {}
    }

    @Override public void onVoice(int dmrId, String callsign, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (handoffLock) {
            voiceEventGeneration++;
            Listener target = selectVoiceListenerLocked();
            if (target == null && backgroundListener != null) {
                if (handoffFrames.size() >= HANDOFF_MAX_FRAMES) handoffFrames.removeFirst();
                handoffFrames.addLast(new VoiceFrame(dmrId, callsign, pcm));
                return;
            }
            if (target == null) return;
            Listener old = activeVoiceSink;
            if (old != null && old != target) safeVoiceEnded(old);
            activeVoiceSink = target;
            // Deliver while holding the ownership lock. attach/detach cannot publish
            // a new sink until the current sink call has returned.
            safeVoice(target, dmrId, callsign, pcm);
        }
    }

    @Override public void onMembers(List<NetworkRoomClient.Member> members) {
        Listener l = controlListener();
        if (l != null) try { l.onMembers(members); } catch (Throwable ignored) {}
    }

    @Override public void onBitrate(int bps) {
        Listener l = controlListener();
        if (l != null) try { l.onBitrate(bps); } catch (Throwable ignored) {}
    }

    @Override public void onPtt(int state, String callsign, String message) {
        Listener l = controlListener();
        if (l != null) try { l.onPtt(state, callsign, message); } catch (Throwable ignored) {}
    }

    @Override public void onDuplex(int state, int peerDmrId, String peerCallsign, String message) {
        Listener l = controlListener();
        if (l != null) try { l.onDuplex(state, peerDmrId, peerCallsign, message); } catch (Throwable ignored) {}
    }

    @Override public void onUdpReady(boolean ready, String message) {
        Listener l = controlListener();
        if (l != null) try { l.onUdpReady(ready, message); } catch (Throwable ignored) {}
    }

    @Override public void onVoiceEnded() {
        synchronized (handoffLock) {
            voiceEventGeneration++;
            Listener target = activeVoiceSink;
            if (target != null) {
                activeVoiceSink = null;
                safeVoiceEnded(target);
                return;
            }
            if (backgroundOutputBlocked) {
                handoffVoiceEnded = true;
                return;
            }
            safeVoiceEnded(selectVoiceListenerLocked());
        }
    }
}