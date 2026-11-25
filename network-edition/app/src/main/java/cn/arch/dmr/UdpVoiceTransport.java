package cn.arch.dmr;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ARCH low-footprint encrypted UDP voice transport.
 *
 * Control stays on the existing WebSocket. This class only carries Opus frames,
 * heartbeats, NACK requests and transport statistics. It deliberately has no
 * WebRTC dependency.
 */
final class UdpVoiceTransport {
    interface Callback {
        void onUdpReady(boolean ready, String message);
        void onUdpAudio(int ssrc, byte[] opus, int timestamp, boolean marker, int missedFrames);
        void onUdpLoss(int ssrc, int missedFrames);
        default void onUdpUnderrun(int ssrc) {}
        void onUdpMetrics(long rttMs, int targetDelayMs, int lost, int reordered);
    }

    private static final byte[] MAGIC = {'A', 'V'};
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 25;
    private static final int MAX_PACKET = 2048;
    private static final int KIND_AUDIO = 1;
    private static final int KIND_PING = 2;
    private static final int KIND_PONG = 3;
    private static final int KIND_NACK = 4;
    private static final int KIND_STATS = 5;
    private static final int FLAG_MARKER = 1;
    private static final int TX_CACHE = 96;
    private static final int MAX_NACK = 24;
    private static final int NACK_DELAY_MS = 30;
    private static final int AUDIO_TIMESTAMP_RATE = 24000;
    private static final int FRAME_CLOCK_UNITS = 480;
    private static final int FRAME_PERIOD_MS = 20;

    private static final class AudioPacket {
        final int ssrc;
        final int seq;
        final int timestamp;
        final boolean marker;
        final byte[] opus;
        AudioPacket(int ssrc, int seq, int timestamp, boolean marker, byte[] opus) {
            this.ssrc = ssrc;
            this.seq = seq;
            this.timestamp = timestamp;
            this.marker = marker;
            this.opus = opus;
        }
    }

    private final String host;
    private final int port;
    private final int sampleRate;
    private final int frameSamples;
    private final int selfDmrId;
    private final byte[] sessionId;
    private final byte[] upKey;
    private final byte[] downKey;
    private final byte[] upNoncePrefix;
    private final byte[] downNoncePrefix;
    private final Callback callback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean startedOnce = new AtomicBoolean(false);
    private final Object sendLock = new Object();
    private final LinkedHashMap<Integer, byte[]> txCache = new LinkedHashMap<Integer, byte[]>(128, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > TX_CACHE;
        }
    };
    private final Object cacheLock = new Object();
    private final ThreadLocal<Cipher> encCipher = new ThreadLocal<Cipher>() {
        @Override protected Cipher initialValue() { try { return Cipher.getInstance("AES/GCM/NoPadding"); } catch (Exception e) { return null; } }
    };
    private final ThreadLocal<Cipher> decCipher = new ThreadLocal<Cipher>() {
        @Override protected Cipher initialValue() { try { return Cipher.getInstance("AES/GCM/NoPadding"); } catch (Exception e) { return null; } }
    };

    private final Object socketLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Object readyLock = new Object();
    private volatile DatagramSocket socket;
    private volatile InetAddress server;
    private volatile long socketGeneration;
    private volatile boolean ready;
    private volatile boolean sequenceUnavailable;
    private volatile long socketOpenedAt;
    private volatile long lastPongAt;
    private volatile long rttMs;
    private volatile long txSeqValue;
    private volatile long txSeqBlockEnd;
    private volatile int txTimestamp;
    private volatile int rxHighest;
    private volatile boolean rxSeqValid;
    private volatile long rxBitmap;
    private volatile int lostPackets;
    private volatile int reorderedPackets;
    private volatile int targetDelayFrames = 2;
    private int lastAcceptedMissedFrames;
    private int rxAudioTimestamp;
    private boolean rxAudioTimestampValid;
    private volatile ScheduledExecutorService nackScheduler;
    private volatile ExecutorService callbackExecutor;
    private long readyEventVersion;
    private final LinkedHashMap<Integer, Boolean> recentlyReceived = new LinkedHashMap<Integer, Boolean>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) { return size() > 256; }
    };
    private volatile double jitterMs;
    private volatile long lastTransitMs = Long.MIN_VALUE;
    private volatile boolean markerPending = true;
    private volatile long lastAudioSentAt;
    private volatile int reconnectDelayMs = 1000;
    private Thread rxThread;
    private Thread heartbeatThread;
    private final Context context;
    private final String sequenceStateKey;
    private static final long TX_SEQUENCE_BLOCK = 65536L;
    private static final long TX_SEQUENCE_LIMIT = 0x100000000L;
    private static final Object TX_SEQUENCE_RESERVATION_LOCK = new Object();

    UdpVoiceTransport(Context context, String host, JSONObject cfg, int sampleRate, int frameSamples, int selfDmrId, Callback callback) {
        this.context = context == null ? null : context.getApplicationContext();
        if (this.context == null) throw new IllegalArgumentException("UDP transport requires application context");
        this.host = host == null ? "" : host.trim();
        this.sampleRate = sampleRate;
        this.frameSamples = frameSamples;
        this.selfDmrId = selfDmrId;
        this.callback = callback;
        this.port = cfg.optInt("port", 50115);
        this.sessionId = decodeB64(cfg.optString("sessionId", ""));
        byte[] master = decodeB64(cfg.optString("masterKey", ""));
        this.upNoncePrefix = decodeB64(cfg.optString("uplinkNoncePrefix", ""));
        this.downNoncePrefix = decodeB64(cfg.optString("downlinkNoncePrefix", ""));
        this.upKey = derive(master, "ARCH-UDP-V1-UP");
        this.downKey = derive(master, "ARCH-UDP-V1-DOWN");
        this.sequenceStateKey = sequenceStateKey();
        if (this.host.isEmpty() || this.port <= 0 || this.sampleRate <= 0 ||
                this.frameSamples <= 0 || sessionId.length != 8 || upKey == null ||
                downKey == null || upNoncePrefix.length != 4 || downNoncePrefix.length != 4) {
            throw new IllegalArgumentException("invalid UDP voice configuration");
        }
        reserveSequenceBlock();
    }

    void start() {
        synchronized (lifecycleLock) {
            if (!startedOnce.compareAndSet(false, true)) return;
            running.set(true);
            reconnectDelayMs = 1000;
            sequenceUnavailable = false;
            rxSeqValid = false;
            rxHighest = 0;
            rxBitmap = 0L;
            nackScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "arch-udp-nack");
                t.setDaemon(true);
                return t;
            });
            callbackExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "arch-udp-callback");
                t.setDaemon(true);
                return t;
            });
            rxThread = new Thread(this::receiveLoop, "arch-udp-rx");
            heartbeatThread = new Thread(this::heartbeatLoop, "arch-udp-heartbeat");
            rxThread.start();
            heartbeatThread.start();
        }
    }

    boolean isReady() { return running.get() && ready && !sequenceUnavailable; }

    void startTalkspurt() {
        markerPending = true;
        txTimestamp = 0;
        lastAudioSentAt = 0L;
    }

    boolean sendAudio(byte[] opus, boolean marker) {
        if (!running.get() || !ready || opus == null || opus.length == 0) return false;
        long now = SystemClock.elapsedRealtime();
        if (markerPending) {
            marker = true;
            markerPending = false;
            txTimestamp = 0;
        } else if (now - lastAudioSentAt > 500L) {
            marker = true;
            txTimestamp = 0;
        }
        lastAudioSentAt = now;
        txTimestamp = (txTimestamp + FRAME_CLOCK_UNITS) & 0xffffffff;
        return sendDatagram(KIND_AUDIO, txTimestamp, selfDmrId, opus,
                marker ? FLAG_MARKER : 0, true);
    }

    private boolean sendDatagram(int kind, int timestamp, int ssrc, byte[] payload,
                                 int flags, boolean cacheAudio) {
        return sendDatagram(kind, timestamp, ssrc, payload, flags, cacheAudio, -1L);
    }

    private boolean sendDatagram(int kind, int timestamp, int ssrc, byte[] payload,
                                 int flags, boolean cacheAudio, long requiredSocketGeneration) {
        synchronized (sendLock) {
        DatagramSocket s;
        InetAddress dst;
        long socketGen;
        synchronized (socketLock) {
            s = socket;
            dst = server;
            socketGen = socketGeneration;
        }
        if (s == null || dst == null || sessionId.length != 8) return false;
        if (requiredSocketGeneration >= 0L && socketGen != requiredSocketGeneration) return false;
        int seq;
        try {
            seq = nextSequenceNumber();
        } catch (Throwable t) {
            sequenceUnavailable = true;
            setReady(false, "UDP sequence reservation failed");
            forceSocketRebuild("UDP sequence unavailable");
            return false;
        }
        byte[] header = new byte[HEADER_SIZE];
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        h.put(MAGIC).put((byte) VERSION).put((byte) kind).put((byte) flags).put(sessionId)
                .putInt(seq).putInt(timestamp).putInt(ssrc);
        byte[] wire;
        try {
            Cipher c = encCipher.get();
            if (c == null) return false;
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(upKey, "AES"),
                    new GCMParameterSpec(128, nonce(upNoncePrefix, seq)));
            c.updateAAD(header);
            byte[] encrypted = c.doFinal(payload == null ? new byte[0] : payload);
            wire = new byte[header.length + encrypted.length];
            System.arraycopy(header, 0, wire, 0, header.length);
            System.arraycopy(encrypted, 0, wire, header.length, encrypted.length);
            s.send(new DatagramPacket(wire, wire.length, dst, port));
        } catch (Throwable t) {
            forceSocketRebuild(s, socketGen, "UDP send failed");
            return false;
        }
        if (cacheAudio) {
            synchronized (cacheLock) { txCache.put(seq, wire); }
        }
        return true;
        }
    }

    private void receiveLoop() {
        // One receive owner rebuilds the socket for the whole UDP session. Heartbeat,
        // send failures and network changes all request a rebuild by closing this socket,
        // but never start a second receive loop (which would duplicate media callbacks).
        while (running.get() && !sequenceUnavailable) {
            DatagramSocket active = null;
            try {
                active = new DatagramSocket();
                active.setSoTimeout(500);
                try { active.setReceiveBufferSize(262144); } catch (Throwable ignored) {}
                try { active.setSendBufferSize(65536); } catch (Throwable ignored) {}
                try { active.setTrafficClass(0xB8); } catch (Throwable ignored) {}
                InetAddress resolved = InetAddress.getByName(host);
                long generation;
                synchronized (socketLock) {
                    if (!running.get()) {
                        try { active.close(); } catch (Throwable ignored) {}
                        return;
                    }
                    server = resolved;
                    socket = active;
                    generation = ++socketGeneration;
                    socketOpenedAt = SystemClock.elapsedRealtime();
                }
                lastPongAt = 0L;
                rxSeqValid = false;
                rxHighest = 0;
                rxBitmap = 0L;
                lastAcceptedMissedFrames = 0;
                rxAudioTimestampValid = false;
                lastTransitMs = Long.MIN_VALUE;
                jitterMs = 0.0;
                synchronized (recentlyReceived) { recentlyReceived.clear(); }
                byte[] buf = new byte[MAX_PACKET];
                while (running.get() && socket == active) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        active.receive(packet);
                    } catch (java.net.SocketTimeoutException te) {
                        continue;
                    }
                    if (!running.get() || socket != active) break;
                    InetAddress expected = server;
                    if (expected == null || !expected.equals(packet.getAddress())
                            || packet.getPort() != port) continue;
                    if (packet.getLength() > HEADER_SIZE + 16) {
                        handlePacket(packet.getData(), packet.getLength(), active, generation);
                    }
                }
            } catch (Throwable t) {
                if (running.get()) {
                    android.util.Log.w("ARCH-UDP", "socket rebuild requested: " + t);
                    setReady(false, "UDP receive failed, rebuilding");
                }
            } finally {
                synchronized (socketLock) {
                    if (socket == active) socket = null;
                }
                if (active != null) try { active.close(); } catch (Throwable ignored) {}
                if (running.get()) setReady(false, "UDP reconnecting");
            }

            if (!running.get()) break;
            int delay = Math.max(1000, Math.min(15000, reconnectDelayMs));
            reconnectDelayMs = Math.min(15000, delay * 2);
            try { Thread.sleep(delay); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void handlePacket(byte[] data, int len, DatagramSocket sourceSocket, long generation) {
        try {
            if (!isCurrentSocket(sourceSocket, generation)) return;
            ByteBuffer h = ByteBuffer.wrap(data, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            byte m0 = h.get(), m1 = h.get();
            if (m0 != MAGIC[0] || m1 != MAGIC[1] || (h.get() & 0xff) != VERSION) return;
            int kind = h.get() & 0xff;
            int flags = h.get() & 0xff;
            byte[] sid = new byte[8];
            h.get(sid);
            if (!Arrays.equals(sid, sessionId)) return;
            int seq = h.getInt();
            int timestamp = h.getInt();
            int ssrc = h.getInt();
            byte[] aad = Arrays.copyOfRange(data, 0, HEADER_SIZE);
            Cipher c = decCipher.get();
            if (c == null) return;
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(downKey, "AES"),
                    new GCMParameterSpec(128, nonce(downNoncePrefix, seq)));
            c.updateAAD(aad);
            byte[] plain = c.doFinal(data, HEADER_SIZE, len - HEADER_SIZE);
            synchronized (socketLock) {
                if (!isCurrentSocket(sourceSocket, generation)) return;
                if (!acceptSeq(seq)) return;
            }

            if (kind == KIND_PONG) {
                long now = SystemClock.elapsedRealtime();
                if (plain.length >= 8) {
                    long sent = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).getLong();
                    rttMs = Math.max(0L, now - sent);
                }
                lastPongAt = now;
                reconnectDelayMs = 1000;
                setReadyForSocket(true, "UDP connected", sourceSocket, generation);
            } else if (kind == KIND_NACK) {
                handleNack(plain, sourceSocket, generation);
            } else if (kind == KIND_AUDIO) {
                boolean marker = (flags & FLAG_MARKER) != 0;
                int missed = 0;
                if (marker || !rxAudioTimestampValid) {
                    rxAudioTimestamp = timestamp;
                    rxAudioTimestampValid = true;
                } else {
                    int diff = timestamp - rxAudioTimestamp;
                    if (diff > 0 && diff < FRAME_CLOCK_UNITS * 8) {
                        missed = Math.max(0, diff / FRAME_CLOCK_UNITS - 1);
                        rxAudioTimestamp = timestamp;
                    } else if (diff > 0) {
                        rxAudioTimestamp = timestamp;
                    }
                }
                // The receive thread only hands raw Opus to NetworkRoomClient. The app's
                // single unjitter/playback clock is owned there, never here.
                if (!isCurrentSocket(sourceSocket, generation)) return;
                final int callbackMissedFrames = missed;
                Callback cb = callback;
                if (cb != null) postCallback(() -> {
                    if (isCurrentSocket(sourceSocket, generation)) {
                        cb.onUdpAudio(ssrc, plain, timestamp, marker, callbackMissedFrames);
                    }
                });
                updateJitter(timestamp, SystemClock.elapsedRealtime());
            }
        } catch (Throwable ignored) {
            // Authentication failure, malformed packet or replay: drop silently.
        }
    }


    private int timestampGapFrames(int timestamp) {
        // Coarse timestamp continuity is handled in playback; sequence NACK is
        // the primary loss signal. Returning zero here avoids treating UDP
        // reordering as a talkspurt marker.
        return 0;
    }

    private void updateJitter(int timestamp, long arrivalMs) {
        long transit = arrivalMs - ((long) Integer.toUnsignedLong(timestamp) * 1000L / sampleRate);
        if (lastTransitMs != Long.MIN_VALUE) {
            double d = Math.abs((double) (transit - lastTransitMs));
            jitterMs = jitterMs == 0.0 ? d : jitterMs * 0.875 + d * 0.125;
            int frames = Math.max(6, Math.min(10, 6 + (int) Math.ceil(jitterMs / 20.0)));
            targetDelayFrames = frames;
        }
        lastTransitMs = transit;
    }

    private boolean acceptSeq(int seq) {
        int highest = rxHighest;
        if (!rxSeqValid) {
            lastAcceptedMissedFrames = 0;
            synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
            rxHighest = seq;
            rxSeqValid = true;
            rxBitmap = 1L;
            return true;
        }
        // Modular 32-bit difference handles 0xFFFFFFFF -> 0 naturally.
        int delta = seq - highest;
        if (delta == 0) return false;
        if (delta < 0) {
            if (delta < -63) return false;
            lastAcceptedMissedFrames = 0;
            int distance = -delta;
            long mask = 1L << distance;
            if ((rxBitmap & mask) != 0) return false;
            synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
            rxBitmap |= mask;
            reorderedPackets++;
            return true;
        }
        synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
        int gap = delta;
        lastAcceptedMissedFrames = gap > 1 ? Math.min(gap - 1, 64) : 0;
        if (gap > 1 && gap <= MAX_NACK) {
            lostPackets += gap - 1;
            final ArrayList<Integer> missing = new ArrayList<>();
            int s = highest + 1;
            while (s != seq) { missing.add(s); s++; }
            final long nackGeneration = socketGeneration;
            ScheduledExecutorService scheduler = nackScheduler;
            if (scheduler != null) scheduler.schedule(() -> {
                if (!isCurrentGeneration(nackGeneration)) return;
                ArrayList<Integer> stillMissing = new ArrayList<>();
                synchronized (recentlyReceived) {
                    for (Integer n : missing) if (!recentlyReceived.containsKey(n)) stillMissing.add(n);
                }
                sendNack(stillMissing, nackGeneration);
            }, NACK_DELAY_MS, TimeUnit.MILLISECONDS);
        } else if (gap > 1) {
            lostPackets += Math.min(gap - 1, 64);
        }
        rxBitmap = gap >= 64 ? 1L : (rxBitmap << gap) | 1L;
        rxHighest = seq;
        return true;
    }

    private void handleNack(byte[] plain, DatagramSocket sourceSocket, long generation) {
        try {
            JSONObject o = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            JSONArray arr = o.optJSONArray("n");
            if (arr == null || !isCurrentSocket(sourceSocket, generation)) return;
            DatagramSocket active;
            InetAddress dst;
            synchronized (socketLock) {
                active = sourceSocket;
                dst = server;
            }
            if (active == null || dst == null) return;
            for (int i = 0; i < arr.length() && i < MAX_NACK; i++) {
                if (!isCurrentSocket(sourceSocket, generation)) return;
                int seq = arr.optInt(i);
                byte[] wire;
                synchronized (cacheLock) { wire = txCache.get(seq); }
                if (wire != null) active.send(new DatagramPacket(wire, wire.length, dst, port));
            }
        } catch (Throwable ignored) {}
    }

    private void sendNack(ArrayList<Integer> missing, long requiredGeneration) {
        if (missing.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, missing.size() - MAX_NACK);
            for (int i = from; i < missing.size(); i++) arr.put(missing.get(i));
            JSONObject o = new JSONObject();
            o.put("n", arr);
            sendDatagram(KIND_NACK, 0, 0, o.toString().getBytes(StandardCharsets.UTF_8), 0, false, requiredGeneration);
        } catch (Throwable ignored) {}
    }

    private void heartbeatLoop() {
        long lastStatsAt = 0L;
        while (running.get() && !sequenceUnavailable) {
            try {
                InetAddress dst;
                synchronized (socketLock) { dst = server; }
                if (dst == null) {
                    InetAddress resolved = InetAddress.getByName(host);
                    synchronized (socketLock) {
                        if (server == null) server = resolved;
                    }
                }
                byte[] ping = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                        .putLong(SystemClock.elapsedRealtime()).array();
                sendDatagram(KIND_PING, 0, 0, ping, 0, false);
                long now = SystemClock.elapsedRealtime();
                long openedAt = socketOpenedAt;
                long lastContact = lastPongAt > openedAt ? lastPongAt : openedAt;
                DatagramSocket current;
                synchronized (socketLock) { current = socket; }
                if (current != null && openedAt > 0L && lastContact > 0L
                        && now - lastContact > 7000L) {
                    forceSocketRebuild("UDP timeout, WebSocket fallback");
                }
                if (ready && now - lastStatsAt >= 5000L) {
                    JSONObject st = new JSONObject();
                    st.put("rtt", rttMs);
                    st.put("jitter", Math.round(jitterMs));
                    st.put("targetMs", targetDelayFrames * 20);
                    st.put("lost", lostPackets);
                    st.put("reordered", reorderedPackets);
                    st.put("queued", 0);
                    long statsGeneration;
                    synchronized (socketLock) { statsGeneration = socketGeneration; }
                    sendDatagram(KIND_STATS, 0, 0, st.toString().getBytes(StandardCharsets.UTF_8), 0,
                            false, statsGeneration);
                    lastStatsAt = now;
                    final long metricsGeneration = statsGeneration;
                    Callback cb = callback;
                    if (cb != null) postCallback(() -> {
                        if (ready && isCurrentGeneration(metricsGeneration)) {
                            cb.onUdpMetrics(rttMs, targetDelayFrames * 20, lostPackets, reorderedPackets);
                        }
                    });
                }
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
            }
        }
    }

    private boolean isCurrentSocket(DatagramSocket expected, long generation) {
        return expected != null && running.get() && socket == expected
                && socketGeneration == generation;
    }

    private boolean isCurrentGeneration(long generation) {
        return running.get() && socket != null && socketGeneration == generation;
    }
    private void setReady(boolean value, String message) {
        setReadyInternal(value, message, null, -1L);
    }

    private void setReadyForSocket(boolean value, String message,
                                   DatagramSocket expected, long generation) {
        setReadyInternal(value, message, expected, generation);
    }

    private void setReadyInternal(boolean value, String message,
                                  DatagramSocket expected, long generation) {
        final Callback cb;
        final long eventVersion;
        final String eventMessage = message == null ? "" : message;
        synchronized (readyLock) {
            if (value && (!running.get() || sequenceUnavailable)) return;
            if (expected != null && socketGeneration != generation) return;
            if (value && expected != null && socket != expected) return;
            if (ready == value) return;
            ready = value;
            if (value) lastPongAt = SystemClock.elapsedRealtime();
            eventVersion = ++readyEventVersion;
            cb = callback;
        }
        if (cb != null) postCallback(() -> {
            synchronized (readyLock) {
                if (eventVersion != readyEventVersion) return;
            }
            cb.onUdpReady(value, eventMessage);
        });
    }

    private void postCallback(Runnable task) {
        ExecutorService executor = callbackExecutor;
        if (executor == null || task == null) return;
        try { executor.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /** Ask the existing receive loop to recreate its socket without creating another loop. */
    private void forceSocketRebuild(String reason) {
        DatagramSocket current;
        long generation;
        synchronized (socketLock) {
            current = socket;
            generation = socketGeneration;
        }
        forceSocketRebuild(current, generation, reason);
    }

    /** Close only the exact socket whose operation failed, never a newer replacement. */
    private void forceSocketRebuild(DatagramSocket expected, long generation, String reason) {
        if (expected == null) return;
        synchronized (socketLock) {
            if (socket != expected || socketGeneration != generation) return;
            socket = null;
        }
        setReadyForSocket(false, reason == null ? "UDP reconnecting" : reason, expected, generation);
        try { expected.close(); } catch (Throwable ignored) {}
    }

    void stop() { stop(true); }

    void stopFast() { stop(false); }

    private void stop(boolean waitForThreads) {
        DatagramSocket active;
        Thread rx;
        Thread heartbeat;
        ScheduledExecutorService nack;
        ExecutorService callbacks;
        synchronized (lifecycleLock) {
            if (!running.getAndSet(false)) return;
            setReady(false, "UDP stopped");
            synchronized (socketLock) {
                active = socket;
                socket = null;
            }
            if (active != null) try { active.close(); } catch (Throwable ignored) {}
            rx = rxThread;
            heartbeat = heartbeatThread;
            rxThread = null;
            heartbeatThread = null;
            nack = nackScheduler;
            nackScheduler = null;
            callbacks = callbackExecutor;
            callbackExecutor = null;
            if (rx != null) rx.interrupt();
            if (heartbeat != null) heartbeat.interrupt();
            if (nack != null) nack.shutdownNow();
        }
        if (waitForThreads) {
            joinQuietly(rx);
            joinQuietly(heartbeat);
        }
        if (callbacks != null) {
            if (waitForThreads) callbacks.shutdown();
            else callbacks.shutdownNow();
        }
        synchronized (recentlyReceived) { recentlyReceived.clear(); }
        synchronized (cacheLock) { txCache.clear(); }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(600L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String sequenceStateKey() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // GCM nonce uniqueness depends on key + prefix + sequence. sessionId is not
            // part of the nonce and therefore must not create a fresh sequence namespace.
            md.update(upKey == null ? new byte[0] : upKey);
            md.update(upNoncePrefix == null ? new byte[0] : upNoncePrefix);
            byte[] digest = md.digest();
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return "udpTxSeq." + out;
        } catch (Throwable t) {
            return "udpTxSeq." + Integer.toHexString(java.util.Arrays.hashCode(upKey))
                    + "." + Integer.toHexString(java.util.Arrays.hashCode(upNoncePrefix));
        }
    }

    /**
     * Reserve a durable block of sequence numbers before using them. A process crash or
     * transport recreation can therefore never restart the same AES-GCM key at seq 0.
     */
    private void reserveSequenceBlock() {
        if (context == null || sequenceStateKey == null) {
            throw new IllegalStateException("UDP sequence persistence unavailable");
        }
        synchronized (TX_SEQUENCE_RESERVATION_LOCK) {
            android.content.SharedPreferences sp = context.getSharedPreferences(
                    "arch_udp_sequence", Context.MODE_PRIVATE);
            long start = sp.getLong(sequenceStateKey, 0L);
            if (start < 0L || start + TX_SEQUENCE_BLOCK > TX_SEQUENCE_LIMIT) {
                throw new IllegalStateException("UDP key exhausted, server must issue a new session");
            }
            long end = start + TX_SEQUENCE_BLOCK;
            if (!sp.edit().putLong(sequenceStateKey, end).commit()) {
                throw new IllegalStateException("UDP sequence reservation failed");
            }
            txSeqValue = start;
            txSeqBlockEnd = end;
        }
    }

    /** Caller holds sendLock, so blocks are consumed by exactly one sender. */
    private int nextSequenceNumber() {
        if (txSeqValue >= txSeqBlockEnd) reserveSequenceBlock();
        long value = txSeqValue++;
        if (value >= TX_SEQUENCE_LIMIT) {
            throw new IllegalStateException("UDP sequence space exhausted");
        }
        return (int) value;
    }
    private static byte[] derive(byte[] master, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] nonce(byte[] prefix, int seq) {
        return ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .put(prefix).putLong(Integer.toUnsignedLong(seq)).array();
    }

    private static byte[] decodeB64(String value) {
        try {
            return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }
}

