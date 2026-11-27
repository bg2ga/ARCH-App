package cn.arch.dmr;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** ARCH voice room client: single-port WebSocket/Opus, no WebRTC. */
public final class NetworkRoomClient {
    public interface Listener {
        void onState(int state, String message);
        void onLog(String message);
        void onVoice(int dmrId, String callsign, short[] pcm);
        void onMembers(java.util.List<Member> members);
        void onBitrate(int bps);
        /** state: 0 idle/released, 1 granted, 2 denied, 3 remote active, 4 remote busy */
        void onPtt(int state, String callsign, String message);
        /** state: 0 ended, 1 active, 2 incoming ring, 3 outgoing ringing */
        void onDuplex(int state, int peerDmrId, String peerCallsign, String message);
        default void onUdpReady(boolean ready, String message) {}
        default void onVoiceEnded() {}
    }

    private static final class RxPacket {
        final int source;
        final String callsign;
        final byte[] opus;
        final int timestamp;
        final boolean marker;
        final int missedFrames;
        final int epoch;
        RxPacket(int source, String callsign, byte[] opus, int timestamp, boolean marker, int missedFrames, int epoch) {
            this.source = source;
            this.callsign = callsign;
            this.opus = opus;
            this.timestamp = timestamp;
            this.marker = marker;
            this.missedFrames = missedFrames;
            this.epoch = epoch;
        }
    }

    public static final class Member {
        public final int dmrId;
        public final String callsign;
        public final boolean tx;
        public final int duplexWith;
        public final String duplexCallsign;
        public final int pendingWith;
        public final String pendingCallsign;
        public Member(int dmrId, String callsign, boolean tx, int duplexWith, String duplexCallsign,
                      int pendingWith, String pendingCallsign) {
            this.dmrId = dmrId; this.callsign = callsign; this.tx = tx;
            this.duplexWith = duplexWith; this.duplexCallsign = duplexCallsign;
            this.pendingWith = pendingWith; this.pendingCallsign = pendingCallsign;
        }
    }

    private final Context context;
    private final OkHttpClient http;
    private final int networkSampleRate;
    private final int networkFrameSamples;
    private final int maxSendBitrate;
    private final Map<Integer,String> members = new ConcurrentHashMap<>();

    // Receive audio has exactly one jitter buffer and one playback clock.
    private static final int RX_PREBUFFER_FRAMES = 2;
    private static final int RX_MAX_FRAMES = 4;
    private static final int RX_UNDERRUN_PLC_FRAMES = 3;
    private static final long RX_FRAME_NS = 20_000_000L;
    private static final long RX_IDLE_END_NS = 240_000_000L;
    private final Object rxQueueLock = new Object();
    private final Object rxPlayLock = new Object();
    private final ArrayDeque<RxPacket> rxQueue = new ArrayDeque<>();
    private volatile Thread rxPlayThread;
    private volatile boolean rxPlayRun;
    private volatile int rxQueueEpoch;
    private volatile int rxPrimeSource = Integer.MIN_VALUE;
    private volatile long rxLastPacketAtNs;
    private volatile int rxDroppedFrames;
    private volatile int rxPlcFrames;
    private volatile int rxSilenceFrames;
    private volatile WebSocket ws;
    private volatile boolean running;
    private volatile boolean micEnabled;
    private volatile boolean txGranted;
    private volatile boolean txRequested;
    private volatile boolean txTransportChosen;
    private volatile boolean txUseUdp;
    private volatile long txRequestStartedAt;
    private volatile int pttRetryCount;
    private volatile boolean joined;
    private volatile boolean remoteVoiceActive;
    private volatile boolean receiveSegmentDirty;
    private static final long VOICE_TAIL_GUARD_MS = 180L;
    private volatile long remoteReleaseAtMs;
    private volatile boolean duplexActive;
    private volatile boolean duplexPending;
    private volatile String selfCallsign = "";
    private volatile String deviceId = "";
    private volatile Listener listener;
    private volatile FmoOpus opus;
    private volatile UdpVoiceTransport udp;
    private volatile short[] lastUdpPcm;
    private volatile int udpUnderrunFrames;
    private int decoderSource = Integer.MIN_VALUE;
    private volatile int selfDmrId;
    private volatile int duplexPeerId;
    private volatile String duplexPeerName = "";
    private volatile String room = "hrb48k";
    private volatile int rxFrames;
    private volatile long pingSentAt;
    private volatile long lastPongAt;
    private volatile boolean pingRun;
    private volatile int currentBitrate;
    private volatile int connectionGeneration;
    private volatile int roomStateSeq;
    private final Handler pttHandler = new Handler(Looper.getMainLooper());
    private final Handler joinHandler = new Handler(Looper.getMainLooper());
    private final Runnable pttRetry = new Runnable() {
        @Override public void run() {
            if (txRequested && !txGranted && isRunning() && pttRetryCount < 3) {
                pttRetryCount++;
                log("PTT acknowledgement timeout, retry " + pttRetryCount);
                sendJson("ptt", "active", true);
                pttHandler.postDelayed(this, pttRetryCount == 1 ? 280L : 700L);
            }
        }
    };
    private final Runnable joinTimeout = new Runnable() {
        @Override public void run() {
            if (!running || joined) return;
            log("joined timeout, reconnect");
            cleanupDeadConnection();
            state(3, "join timeout");
        }
    };
    // Adaptive Opus controller: RTT + jitter + send backlog + receive gap.
    private static final int[] BITRATES = {12000, 16000, 20000, 24000, 32000, 48000};
    private final Object bitrateLock = new Object();
    private double rttEwma;
    private double jitterEwma;
    private double queueEwma;
    private double rxGapEwma;
    private long lastRttSampleAt;
    private long lastRxFrameAt;
    private long lastBitrateChangeAt;

    public NetworkRoomClient(Context context) {
        this.context = context.getApplicationContext();
        this.networkSampleRate = 24000;
        this.networkFrameSamples = 480;
        this.maxSendBitrate = 24000;
        this.http = new OkHttpClient.Builder()
                .dns(ArchDns.get())
                .sslSocketFactory(TlsCompat.socketFactory(this.context), TlsCompat.trustManager(this.context))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    /** ARCH room standard: mono Opus 24 kHz, 24 kbps, 20 ms / 480 samples. */
    public static int preferredSampleRate(Context context) { return 24000; }

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean isRunning() { return running && joined && ws != null; }
    public boolean isPttRequested() { return txRequested; }
    public int connectionGeneration() { return connectionGeneration; }
    public boolean isDuplexActive() { return duplexActive; }
    public boolean isDuplexPending() { return duplexPending; }
    public int getDuplexPeerId() { return duplexPeerId; }
    public String getDuplexPeerName() { return duplexPeerName; }


    public void connectWithTokenServer(final String url, final String room, final String identity,
                                       final String name, final int dmrId, final int essid, final String password) {
        connectionGeneration++;
        stop(false);
        this.room = room == null ? "hrb48k" : room;
        this.selfDmrId = dmrId;
        this.selfCallsign = identity == null ? "" : identity.trim().toUpperCase(java.util.Locale.US);
        this.deviceId = stableDeviceId();
        this.rxFrames = 0;
        this.opus = new FmoOpus(networkSampleRate);
        decoderSource = Integer.MIN_VALUE;
        running = true;
        startReceivePlayback();
        pingSentAt = 0;
        lastPongAt = System.currentTimeMillis();
        joined = false;
        resetAdaptiveBitrate();
        final int generation = connectionGeneration;
        state(0, "connecting");
        Request req = new Request.Builder().url(url).build();
        joinHandler.removeCallbacks(joinTimeout);
        joinHandler.postDelayed(joinTimeout, 15000L);
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (webSocket != ws || generation != connectionGeneration) return;
                lastPongAt = System.currentTimeMillis();
                try {
                    JSONObject j = new JSONObject();
                    j.put("type", "join");
                    j.put("room", NetworkRoomClient.this.room);
                    j.put("dmrId", dmrId);
                    j.put("callsign", identity == null ? "" : identity);
                    j.put("name", name == null ? identity : name);
                    j.put("essid", essid);
                    j.put("password", password == null ? "" : password);
                    j.put("deviceId", deviceId);
                    j.put("udpVersion", 1);
                    webSocket.send(j.toString());
                } catch (Throwable t) { log("join send failed: " + t); }
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (webSocket != ws || generation != connectionGeneration) return;
                lastPongAt = System.currentTimeMillis();
                try {
                    JSONObject j = new JSONObject(text);
                    String type = j.optString("type", "");
                    if ("joined".equals(type) || "members".equals(type)) {
                        members.clear();
                        org.json.JSONArray arr = j.optJSONArray("members");
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject m = arr.getJSONObject(i);
                            members.put(m.optInt("dmrId"), m.optString("callsign", ""));
                        }
                        JSONObject self = j.optJSONObject("self");
                        if (self != null) members.put(self.optInt("dmrId"), self.optString("callsign", identity));
                        java.util.ArrayList<Member> list = new java.util.ArrayList<>();
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject m = arr.getJSONObject(i);
                            list.add(new Member(m.optInt("dmrId"), m.optString("callsign", ""),
                                    m.optBoolean("tx", false), m.optInt("duplexWith", 0),
                                    m.optString("duplexCallsign", ""), m.optInt("pendingWith", 0),
                                    m.optString("pendingCallsign", "")));
                        } else if (self != null) {
                            list.add(new Member(self.optInt("dmrId"), self.optString("callsign", identity), false, 0, "", 0, ""));
                        }
                        if (listener != null) listener.onMembers(list);
                        JSONObject holder = j.optJSONObject("txHolder");
                        if (holder != null) {
                            int hid = holder.optInt("dmrId", 0);
                            if (hid != selfDmrId) {
                                if (!remoteVoiceActive) {
                                    resetReceiveSegmentIfDirty();
                                    remoteVoiceActive = true;
                                }
                                if (listener != null) listener.onPtt(3, holder.optString("callsign", ""), "对方正在发射");
                            }
                        } else {
                            // A presence snapshot can race just ahead of our granted message.
                            // Do not cancel a fresh local PTT; after 2.5s the server view wins
                            // and a genuinely stale local floor is self-healed.
                            boolean freshLocalRequest = (txRequested || txGranted)
                                    && txRequestStartedAt > 0L
                                    && System.currentTimeMillis() - txRequestStartedAt < 2500L;
                            if (!freshLocalRequest) {
                                roomStateSeq++;
                                resetReceiveSegmentIfDirty();
                                if (listener != null) listener.onPtt(0, "", "频道空闲");
                            }
                        }
                        if ("joined".equals(type)) {
                            joinHandler.removeCallbacks(joinTimeout);
                            joined = true;
                            JSONObject udpCfg = j.optJSONObject("udp");
                            if (udpCfg != null) startUdpVoice(url, udpCfg, generation);
                            startPingLoop(generation);
                            requestPresence();
                        }
                        state(1, "connected");
                    } else if ("error".equals(type)) {
                        state(3, j.optString("error", "room error"));
                    } else if ("ptt".equals(type)) {
                        handlePtt(j);
                    } else if ("duplex".equals(type)) {
                        handleDuplex(j);
                    } else if ("pong".equals(type)) {
                        long nowMs = System.currentTimeMillis();
                        lastPongAt = nowMs;
                        long sent = pingSentAt;
                        if (sent > 0 && nowMs > sent) observeRtt(nowMs - sent);
                    }
                } catch (Throwable t) { log("message parse failed: " + t); }
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (webSocket != ws || generation != connectionGeneration) return;
                try {
                    byte[] b = bytes.toByteArray();
                    if (b.length >= 13 && b[0] == 3) {
                        String callsign = new String(b, 5, 8, StandardCharsets.US_ASCII).trim();
                        byte[] frame = new byte[b.length - 13];
                        System.arraycopy(b, 13, frame, 0, frame.length);
                        enqueueVoice(callsign.hashCode(), callsign.isEmpty() ? "D-Star" : callsign,
                                frame, 0, false, 0);
                        return;
                    }
                    if (b.length < 5 || (b[0] != 1 && b[0] != 2)) return;
                    // Callback threads only enqueue encrypted/Opus payloads. Decoding and
                    // AudioTrack writes are serialized by the receive playback thread.
                    int src = ((b[1] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 8) | (b[4] & 0xFF);
                    byte[] frame = new byte[b.length - 5];
                    System.arraycopy(b, 5, frame, 0, frame.length);
                    int n = ++rxFrames;
                    if (n <= 3) log("voice rx src=" + src + " bytes=" + frame.length + " mode=" + b[0]);
                    enqueueVoice(src, null, frame, 0, false, 0);
                } catch (Throwable t) { log("voice enqueue failed: " + t); }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (webSocket != ws || generation != connectionGeneration) return;
                boolean wasDuplex = duplexActive || duplexPending;
                int pid = duplexPeerId;
                String peer = duplexPeerName;
                cleanupDeadConnection();
                if (wasDuplex && listener != null) listener.onDuplex(0, pid, peer, "disconnected");
                state(3, "connection failed: " + (t == null ? "unknown" : t.getMessage()));
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (webSocket != ws || generation != connectionGeneration) return;
                boolean wasDuplex = duplexActive || duplexPending;
                int pid = duplexPeerId;
                String peer = duplexPeerName;
                cleanupDeadConnection();
                if (wasDuplex && listener != null) listener.onDuplex(0, pid, peer, "disconnected");
                // Unexpected remote close must also trigger the UI reconnect path.
                state(3, "connection closed");
            }
        });
    }

    private void handlePtt(JSONObject j) {
        String st = j.optString("state", "");
        JSONObject holder = j.optJSONObject("holder");
        String call = holder == null ? "" : holder.optString("callsign", "");
        int hid = holder == null ? 0 : holder.optInt("dmrId", 0);
        if ("granted".equals(st)) {
            roomStateSeq++;
            pttHandler.removeCallbacks(pttRetry);
            resetReceiveSegmentIfDirty();
            txGranted = true;
            if (!txRequested) {
                sendJson("ptt", "active", false);
                txGranted = false;
                micEnabled = false;
                return;
            }
            micEnabled = true;
            listener.onPtt(1, call, "已获得发射权");
        } else if ("denied".equals(st)) {
            pttHandler.removeCallbacks(pttRetry);
            txGranted = false;
            txRequested = false;
            txTransportChosen = false;
            txRequestStartedAt = 0L;
            micEnabled = false;
            listener.onPtt(2, call, "对方正在发射，请稍后");
        } else if ("active".equals(st)) {
            pttHandler.removeCallbacks(pttRetry);
            if (hid == selfDmrId) {
                if (txRequested) {
                    resetReceiveSegmentIfDirty();
                    txGranted = true;
                    micEnabled = true;
                    listener.onPtt(1, selfCallsign, "已获得发射权");
                }
            } else {
                txRequested = false;
                // Keep txRequestStartedAt so a local release still sends active=false
                // if a late remote active event races our grant.
                txGranted = false;
                micEnabled = false;
                if (!remoteVoiceActive) {
                    resetReceiveSegmentIfDirty();
                    remoteReleaseAtMs = 0L;
                    remoteVoiceActive = true;
                }
                if (listener != null) listener.onPtt(3, call, "对方正在发射");
            }
        } else if ("released".equals(st)) {
            roomStateSeq++;
            boolean selfReleased = hid == selfDmrId || (hid == 0 && (txGranted || txRequestStartedAt > 0L));
            pttHandler.removeCallbacks(pttRetry);
            txRequested = false;
            txRequestStartedAt = 0L;
            txGranted = false;
            micEnabled = false;
            long releaseNow = System.currentTimeMillis();
            if (selfReleased) {
                resetReceiveSegmentIfDirty();
                remoteReleaseAtMs = 0L;
            } else {
                // Keep already decoded/queued tail audio. onPtt(0) ends the
                // callsign UI immediately; the next PTT active event resets the
                // decoder before any new talk spurt.
                remoteVoiceActive = false;
                remoteReleaseAtMs = releaseNow;
            }
            if (selfReleased) {
                if (listener != null) listener.onPtt(0, selfCallsign, "本机发射已释放");
            } else if (hid != selfDmrId && listener != null) {
                listener.onPtt(0, call, "频道空闲");
            }
        }
    }

    private void handleDuplex(JSONObject j) {
        String st = j.optString("state", "");
        JSONObject peer = j.optJSONObject("peer");
        int pid = peer == null ? 0 : peer.optInt("dmrId", 0);
        String pname = peer == null ? "" : peer.optString("callsign", "");
        if ("active".equals(st)) {
            resetReceiveSegmentIfDirty();
            remoteVoiceActive = true;
            duplexActive = true;
            duplexPending = false;
            txGranted = false;
            txRequested = false;
            duplexPeerId = pid;
            duplexPeerName = pname;
            micEnabled = true;
            listener.onDuplex(1, pid, pname, "已接通");
        } else if ("ring".equals(st)) {
            listener.onDuplex(2, pid, pname, "双工呼叫");
        } else if ("ringing".equals(st)) {
            duplexPending = true;
            listener.onDuplex(3, pid, pname, "正在呼叫");
        } else if ("declined".equals(st) || "ended".equals(st)) {
            String reason = j.optString("reason", "");
            resetReceiveSegmentIfDirty();
            resetDuplex();
            listener.onDuplex(0, pid, pname, reason);
        }
    }

    private void startPingLoop(final int generation) {
        if (pingRun) return;
        pingRun = true;
        new Thread(() -> {
            long lastPingAt = 0L;
            while (pingRun && running && generation == connectionGeneration) {
                try {
                    long now = System.currentTimeMillis();
                    if (lastPongAt > 0 && now - lastPongAt > 35000L) {
                        if (generation != connectionGeneration) break;
                        log("心跳超时（35s 无服务端消息），强制重连");
                        WebSocket w = ws;
                        if (w != null) w.cancel();
                        break;
                    }
                    WebSocket w = ws;
                    if (w != null) observeSendQueue(w.queueSize());

                    if (now - lastPingAt >= 3000L) {
                        JSONObject p = new JSONObject();
                        p.put("type", "ping");
                        pingSentAt = now;
                        sendText(p.toString());
                        lastPingAt = now;
                    }
                    evaluateAdaptiveBitrate(now);
                } catch (Throwable ignored) {}
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "voice-ping-" + generation).start();
    }

    private void resetAdaptiveBitrate() {
        synchronized (bitrateLock) {
            rttEwma = 0; jitterEwma = 0; queueEwma = 0; rxGapEwma = 0;
            lastRttSampleAt = 0; lastRxFrameAt = 0; lastBitrateChangeAt = 0;
            currentBitrate = 0;
        }
        setBitrateNow(24000, "fixed-standard");
    }

    private void observeRtt(long rtt) {
        if (rtt <= 0 || rtt > 5000) return;
        synchronized (bitrateLock) {
            long now = System.currentTimeMillis();
            if (lastRttSampleAt > 0) {
                double jitter = Math.abs(rtt - (rttEwma > 0 ? rttEwma : rtt));
                jitterEwma = jitterEwma == 0 ? jitter : jitterEwma * 0.75 + jitter * 0.25;
            }
            rttEwma = rttEwma == 0 ? rtt : rttEwma * 0.75 + rtt * 0.25;
            lastRttSampleAt = now;
        }
    }

    private void observeSendQueue(long bytes) {
        if (bytes < 0) return;
        synchronized (bitrateLock) {
            queueEwma = queueEwma == 0 ? bytes : queueEwma * 0.70 + bytes * 0.30;
        }
    }

    private void observeReceiveFrame() {
        long now = System.currentTimeMillis();
        synchronized (bitrateLock) {
            if (lastRxFrameAt > 0) {
                long gap = now - lastRxFrameAt;
                if (gap >= 35 && gap <= 1000) {
                    double jitter = Math.abs(gap - 20);
                    rxGapEwma = rxGapEwma == 0 ? jitter : rxGapEwma * 0.80 + jitter * 0.20;
                }
            }
            lastRxFrameAt = now;
        }
    }

    private int clampBitrate(int bps) { return 24000; }

    private void setBitrateNow(int bps, String reason) {
        bps = clampBitrate(bps);
        synchronized (bitrateLock) {
            if (bps == currentBitrate) return;
            currentBitrate = bps;
            lastBitrateChangeAt = System.currentTimeMillis();
        }
        Listener l = listener;
        if (l != null) l.onBitrate(bps);
        android.util.Log.i("ARCH-BITRATE", "encoder=" + (bps / 1000) + "kbps reason=" + reason);
        log("网络码率: " + (bps / 1000) + " kbps · " + reason);
    }

    private void evaluateAdaptiveBitrate(long now) {
        // ARCH fixed codec standard: 24 kHz / 24 kbps / 20 ms / 480 samples.
    }

    private void enqueueVoice(int source, String callsign, byte[] frame, int timestamp,
                              boolean marker, int missedFrames) {
        if (frame == null || frame.length == 0 || !running) return;
        if (!marker && !remoteVoiceActive) {
            if (remoteReleaseAtMs > 0L && System.currentTimeMillis() - remoteReleaseAtMs < VOICE_TAIL_GUARD_MS) return;
        }
        boolean newSegment;
        int epoch;
        synchronized (rxQueueLock) {
            newSegment = marker || rxPrimeSource == Integer.MIN_VALUE || rxPrimeSource != source;
            if (newSegment) {
                rxQueue.clear();
                rxQueueEpoch++;
                rxPrimeSource = source;
            }
            epoch = rxQueueEpoch;
            RxPacket packet = new RxPacket(source, callsign, frame, timestamp, marker, missedFrames, epoch);
            if (timestamp != 0) {
                Iterator<RxPacket> it = rxQueue.iterator();
                int at = 0;
                while (it.hasNext()) {
                    RxPacket existing = it.next();
                    if (existing.timestamp != 0 && Integer.compare(timestamp, existing.timestamp) < 0) break;
                    at++;
                }
                if (at >= rxQueue.size()) rxQueue.addLast(packet);
                else {
                    RxPacket[] tail = rxQueue.toArray(new RxPacket[0]);
                    rxQueue.clear();
                    for (int i = 0; i < at; i++) rxQueue.addLast(tail[i]);
                    rxQueue.addLast(packet);
                    for (int i = at; i < tail.length; i++) rxQueue.addLast(tail[i]);
                }
            } else {
                rxQueue.addLast(packet);
            }
            while (rxQueue.size() > RX_MAX_FRAMES) {
                rxQueue.removeFirst();
                rxDroppedFrames++;
            }
            rxLastPacketAtNs = System.nanoTime();
            rxQueueLock.notifyAll();
        }
        if (newSegment) resetReceiveDecoderOnly();
        remoteVoiceActive = true;
        observeReceiveFrame();
    }

    private void startReceivePlayback() {
        synchronized (rxPlayLock) {
            if (rxPlayThread != null && rxPlayThread.isAlive()) return;
            rxPlayRun = true;
            Thread t = new Thread(this::receivePlaybackLoop, "arch-network-rx");
            t.setDaemon(true);
            rxPlayThread = t;
            t.start();
        }
    }

    private void stopReceivePlayback() {
        Thread t;
        synchronized (rxPlayLock) {
            rxPlayRun = false;
            t = rxPlayThread;
            rxPlayThread = null;
        }
        synchronized (rxQueueLock) {
            rxQueue.clear();
            rxQueueEpoch++;
            rxQueueLock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(300L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void receivePlaybackLoop() {
        long nextNs = 0L;
        long primeStartedNs = 0L;
        int underruns = 0;
        int activeSource = Integer.MIN_VALUE;
        boolean primed = false;
        while (running && rxPlayRun) {
            try {
                long now = System.nanoTime();
                int queued;
                synchronized (rxQueueLock) { queued = rxQueue.size(); }
                if (!primed) {
                    if (queued >= RX_PREBUFFER_FRAMES) {
                        primed = true;
                        primeStartedNs = 0L;
                        nextNs = System.nanoTime();
                    } else {
                        if (queued > 0 && primeStartedNs == 0L) primeStartedNs = now;
                        if (queued > 0 && now - primeStartedNs >= RX_FRAME_NS) {
                            primed = true;
                            primeStartedNs = 0L;
                            nextNs = now;
                        } else {
                            if (queued == 0 && remoteVoiceActive && rxLastPacketAtNs > 0L
                                    && now - rxLastPacketAtNs >= RX_IDLE_END_NS) {
                                finishReceiveSegment();
                                activeSource = Integer.MIN_VALUE;
                            }
                            synchronized (rxQueueLock) { rxQueueLock.wait(5L); }
                            continue;
                        }
                    }
                }
                if (nextNs <= 0L) nextNs = System.nanoTime();
                sleepUntil(nextNs);
                RxPacket packet = null;
                synchronized (rxQueueLock) {
                    if (!rxQueue.isEmpty()) {
                        RxPacket candidate = rxQueue.removeFirst();
                        if (candidate.epoch == rxQueueEpoch) packet = candidate;
                    }
                }
                // A release/marker can invalidate this packet while we are between
                // polling and decoding. Never let an old talkspurt leak into the new one.
                if (packet != null && packet.epoch != rxQueueEpoch) packet = null;
                now = System.nanoTime();
                if (packet == null) {
                    if (remoteVoiceActive && rxLastPacketAtNs > 0L && now - rxLastPacketAtNs >= RX_IDLE_END_NS) {
                        finishReceiveSegment();
                        activeSource = Integer.MIN_VALUE;
                        primed = false;
                        nextNs = 0L;
                        continue;
                    }
                    if (underruns < RX_UNDERRUN_PLC_FRAMES) {
                        short[] pcm = null;
                        synchronized (NetworkRoomClient.this) {
                            if (opus != null) pcm = opus.decodePlc(networkFrameSamples);
                        }
                        if (pcm != null) {
                            rxPlcFrames++;
                            emitPcm(activeSource, null, pcm);
                        } else {
                            rxSilenceFrames++;
                            emitPcm(activeSource, null, new short[networkFrameSamples]);
                        }
                        underruns++;
                    }
                } else {
                    underruns = 0;
                    if (packet.source != activeSource || packet.marker) {
                        activeSource = packet.source;
                        resetReceiveDecoderOnly();
                    }
                    emitPacket(packet);
                }
                nextNs += RX_FRAME_NS;
                if (nextNs < System.nanoTime() - 80_000_000L) nextNs = System.nanoTime() + RX_FRAME_NS;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                android.util.Log.w("ARCH-AUDIO", "receive playback error: " + t);
                nextNs = 0L;
                primed = false;
            }
        }
    }

    private static void sleepUntil(long deadlineNs) throws InterruptedException {
        while (true) {
            long left = deadlineNs - System.nanoTime();
            if (left <= 0L) return;
            long ms = left / 1_000_000L;
            int ns = (int)(left % 1_000_000L);
            Thread.sleep(ms, ns);
        }
    }

    private void emitPacket(RxPacket packet) {
        FmoOpus dec;
        synchronized (NetworkRoomClient.this) {
            dec = decoderForSource(packet.source);
            if (dec == null) return;
            if (packet.missedFrames > 0) {
                short[] fec = dec.decodeFec(packet.opus, networkFrameSamples);
                if (fec != null) emitPcm(packet.source, packet.callsign, fec);
                int missing = packet.missedFrames - (fec == null ? 0 : 1);
                for (int i = 0; i < missing && i < 3; i++) {
                    short[] plc = dec.decodePlc(networkFrameSamples);
                    if (plc == null) break;
                    emitPcm(packet.source, packet.callsign, plc);
                    rxPlcFrames++;
                }
            }
            short[] pcm = dec.decode(packet.opus, networkFrameSamples);
            if (pcm != null) {
                remoteVoiceActive = true;
                receiveSegmentDirty = true;
                emitPcm(packet.source, packet.callsign, pcm);
            }
        }
    }

    private void emitPcm(int source, String callsign, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        Listener l = listener;
        if (l != null) l.onVoice(source, callsign == null ? memberName(source) : callsign, pcm);
    }

    private void finishReceiveSegment() {
        remoteVoiceActive = false;
        receiveSegmentDirty = false;
        synchronized (rxQueueLock) {
            rxQueue.clear();
            rxQueueEpoch++;
            rxPrimeSource = Integer.MIN_VALUE;
            rxQueueLock.notifyAll();
        }
        resetReceiveDecoderOnly();
        Listener l = listener;
        if (l != null) l.onVoiceEnded();
    }

    private synchronized void resetReceiveDecoderOnly() {
        decoderSource = Integer.MIN_VALUE;
        receiveSegmentDirty = false;
        lastUdpPcm = null;
        udpUnderrunFrames = 0;
        FmoOpus old = opus;
        opus = null;
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Ends one decoded receive talkspurt. Closing the decoder prevents predictive/PLC state
     * from leaking into the next PTT; the next packet recreates a clean decoder.
     */
    private synchronized void resetReceiveSegmentIfDirty() {
        remoteVoiceActive = false;
        lastUdpPcm = null;
        udpUnderrunFrames = 0;
        receiveSegmentDirty = false;
        synchronized (rxQueueLock) {
            rxQueue.clear();
            rxQueueEpoch++;
            rxPrimeSource = Integer.MIN_VALUE;
            rxQueueLock.notifyAll();
        }
        decoderSource = Integer.MIN_VALUE;
        FmoOpus old = opus;
        opus = null;
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
        }
    }

    private synchronized short[] decodeFrame(int source, byte[] frame) {
        FmoOpus dec = decoderForSource(source);
        short[] pcm = dec == null ? null : dec.decode(frame, networkFrameSamples);
        if (pcm != null) {
            remoteVoiceActive = true;
            receiveSegmentDirty = true;
        }
        return pcm;
    }

    private synchronized FmoOpus decoderForSource(int source) {
        if (opus == null) {
            opus = new FmoOpus(networkSampleRate);
            decoderSource = source;
        } else if (decoderSource == Integer.MIN_VALUE) {
            decoderSource = source;
        } else if (decoderSource != source) {
            try { opus.close(); } catch (Throwable ignored) {}
            opus = new FmoOpus(networkSampleRate);
            decoderSource = source;
            android.util.Log.i("ARCH-AUDIO", "decoder rebuilt for source=" + source);
        }
        return opus;
    }

    private String memberName(int ssrc) { String c = members.get(ssrc); return c == null ? String.valueOf(ssrc) : c; }

    private void emitPlcFrames(int source, int count) {
        if (count <= 0) return;
        FmoOpus dec = opus;
        if (dec == null || decoderSource != source) return;
        for (int i = 0; i < count; i++) {
            short[] pcm = dec.decodePlc(networkFrameSamples);
            if (pcm == null || listener == null) return;
            listener.onVoice(source, memberName(source), pcm);
        }
    }

    private void resetDuplex() {
        duplexActive = false;
        duplexPending = false;
        duplexPeerId = 0;
        duplexPeerName = "";
        micEnabled = false;
        txGranted = false;
        txRequested = false;
    }

    /** Compatibility entry point: signaling URL is now the voice WebSocket URL. */
    public void connect(final String url, final String token) {
        connectWithTokenServer(url, room, "guest", "guest", 0, 0, "");
    }

    public void setMic(boolean enabled) { requestPtt(enabled); }

    public void requestPtt(boolean enabled) {
        if (enabled) {
            txTransportChosen = false;
            if (duplexActive) {
                listener.onPtt(2, duplexPeerName, "双工通话中不能按 PTT");
                return;
            }
            txRequested = true;
            txRequestStartedAt = System.currentTimeMillis();
            pttRetryCount = 0;
            if (txGranted) listener.onPtt(1, selfCallsign, "已获得发射权");
            sendJson("ptt", "active", true);
            pttHandler.removeCallbacks(pttRetry);
            pttHandler.postDelayed(pttRetry, 220L);
        } else {
            boolean hadFloor = txRequested || txGranted || txRequestStartedAt > 0L;
            txRequested = false;
            txRequestStartedAt = 0L;
            pttHandler.removeCallbacks(pttRetry);
            // Always release once we asked for the floor. A lost grant callback must
            // never leave the server room_tx holder stuck indefinitely.
            if (hadFloor) {
                txGranted = false;
                micEnabled = false;
                sendJson("ptt", "active", false);
            }
        }
    }

    public boolean requestDuplex(int dmrId) {
        if (duplexActive || duplexPending || dmrId <= 0) return false;
        try {
            JSONObject j = new JSONObject();
            j.put("type", "duplex");
            j.put("state", "request");
            j.put("target", dmrId);
            duplexPending = true;
            sendText(j.toString());
            return true;
        } catch (Throwable t) { duplexPending = false; log("duplex request failed: " + t); return false; }
    }

    public void answerDuplex(boolean accept) {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "duplex");
            j.put("state", accept ? "accept" : "decline");
            sendText(j.toString());
        } catch (Throwable t) { log("duplex answer failed: " + t); }
    }

    public void endDuplex() {
        int pid = duplexPeerId;
        String peer = duplexPeerName;
        boolean pending = duplexPending;
        String reason = pending ? "cancelled" : "ended";
        try {
            JSONObject j = new JSONObject();
            j.put("type", "duplex");
            j.put("state", "end");
            j.put("reason", reason);
            sendText(j.toString());
        } catch (Throwable t) { log("duplex end failed: " + t); }
        resetDuplex();
        listener.onDuplex(0, pid, peer, reason);
    }

    public void sendVoice(byte[] opusFrame) {
        if (!running || opusFrame == null) return;
        if (!txTransportChosen) {
            UdpVoiceTransport media = udp;
            txUseUdp = media != null && media.isReady();
            if (txUseUdp) media.startTalkspurt();
            txTransportChosen = true;
        }
        if (txUseUdp) {
            UdpVoiceTransport media = udp;
            if (media != null) media.sendAudio(opusFrame, false);
            return;
        }
        WebSocket w = ws;
        if (w == null) return;
        byte[] out = new byte[5 + opusFrame.length];
        out[0] = (byte)(duplexActive ? 2 : 1);
        out[1] = (byte)(selfDmrId >> 24);
        out[2] = (byte)(selfDmrId >> 16);
        out[3] = (byte)(selfDmrId >> 8);
        out[4] = (byte)selfDmrId;
        System.arraycopy(opusFrame, 0, out, 5, opusFrame.length);
        try { if (!w.send(ByteString.of(out))) failSend(w, new IllegalStateException("websocket audio queue rejected")); }
        catch (Throwable t) { failSend(w, t); }
    }

    private void startUdpVoice(String wsUrl, JSONObject cfg, final int generation) {
        stopUdpVoice();
        String host = Uri.parse(wsUrl).getHost();
        if (host == null || host.isEmpty()) return;
        try {
            UdpVoiceTransport transport = new UdpVoiceTransport(host, cfg,
                    networkSampleRate, networkFrameSamples, selfDmrId, new UdpVoiceTransport.Callback() {
                @Override public void onUdpReady(boolean ready, String message) {
                    if (generation != connectionGeneration) return;
                    if (listener != null) listener.onUdpReady(ready, message);
                    log(ready ? "UDP voice ready" : ("UDP fallback: " + message));
                }
                @Override public void onUdpAudio(int ssrc, byte[] frame, int timestamp, boolean marker, int missedFrames) {
                    if (generation != connectionGeneration || frame == null) return;
                    enqueueVoice(ssrc, null, frame, timestamp, marker, missedFrames);
                }
                @Override public void onUdpUnderrun(int ssrc) { /* central receive player owns underrun timing */ }
                @Override public void onUdpLoss(int ssrc, int missedFrames) { }
                @Override public void onUdpMetrics(long rttMs, int targetDelayMs, int lost, int reordered) {
                    if (generation == connectionGeneration) {
                        log("UDP rtt=" + rttMs + "ms buffer=" + targetDelayMs + "ms lost=" + lost + " reorder=" + reordered);
                    }
                }
            });
            udp = transport;
            transport.start();
            log("UDP voice starting");
        } catch (Throwable t) {
            udp = null;
            log("UDP unavailable, WebSocket fallback: " + t.getMessage());
        }
    }

    private void stopUdpVoice() {
        UdpVoiceTransport u = udp;
        udp = null;
        lastUdpPcm = null;
        udpUnderrunFrames = 0;
        if (u != null) try { u.stop(); } catch (Throwable ignored) {}
    }

    /** Drop queued receive audio immediately while keeping the signaling connection alive. */
    public void clearReceiveAudio() {
        resetReceiveSegmentIfDirty();
    }

    /** 主动请求服务器下发当前房间成员列表（用于定期刷新在线用户）。 */
    public void requestPresence() {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "presence");
            sendText(j.toString());
        } catch (Throwable ignored) {}
    }

    private void sendJson(String type, String state, boolean active) {
        try {
            JSONObject j = new JSONObject();
            j.put("type", type);
            j.put("state", state);
            j.put("active", active);
            sendText(j.toString());
        } catch (Throwable t) { log("json send failed: " + t); }
    }

    private void sendText(String text) {
        WebSocket w = ws;
        if (!running || w == null) return;
        try { w.send(text); }
        catch (Throwable t) { failSend(w, t); }
    }

    private void failSend(WebSocket failed, Throwable t) {
        if (!running || failed == null || failed != ws) return;
        boolean wasDuplex = duplexActive || duplexPending;
        int pid = duplexPeerId;
        String peer = duplexPeerName;
        cleanupDeadConnection();
        if (wasDuplex && listener != null) listener.onDuplex(0, pid, peer, "disconnected");
        String m = t == null || t.getMessage() == null ? "socket write failed" : t.getMessage();
        state(3, "connection failed: " + m);
    }

    /** Release stale transport state before starting or accepting a reconnect. */
    private String stableDeviceId() {
        try {
            String id = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (id != null && !id.trim().isEmpty()) return "arch-" + id.trim();
        } catch (Throwable ignored) {}
        return "arch-device-" + Integer.toHexString(System.identityHashCode(context));
    }

    private void cleanupDeadConnection() {
        joinHandler.removeCallbacks(joinTimeout);
        pttHandler.removeCallbacks(pttRetry);
        running = false;
        joined = false;
        pingRun = false;
        resetDuplex();
        WebSocket w = ws;
        ws = null;
        if (w != null) { try { w.cancel(); } catch (Throwable ignored) {} }
        stopUdpVoice();
        stopReceivePlayback();
        resetReceiveSegmentIfDirty();
    }

    public void stop() { stop(true); }

    private void stop(boolean notify) {
        joinHandler.removeCallbacks(joinTimeout);
        pttHandler.removeCallbacks(pttRetry);
        running = false;
        joined = false;
        pingRun = false;
        resetDuplex();
        WebSocket w = ws; ws = null;
        if (w != null) {
            try {
                JSONObject leave = new JSONObject();
                leave.put("type", "leave");
                leave.put("room", room);
                w.send(leave.toString());
            } catch (Throwable ignored) {}
            try { w.close(1000, "bye"); } catch (Throwable ignored) {}
        }
        stopUdpVoice();
        stopReceivePlayback();
        FmoOpus o = opus; opus = null;
        if (o != null) { try { o.close(); } catch (Throwable ignored) {} }
        if (notify) state(0, "not connected");
    }

    private void state(int st, String message) {
        android.util.Log.i("ARCH-NET", "state=" + st + " " + (message == null ? "" : message));
        Listener l = listener; if (l != null) l.onState(st, message);
    }
    private void log(String message) {
        android.util.Log.i("ARCH-NET", message == null ? "" : message);
        Listener l = listener; if (l != null) l.onLog(message);
    }
}
