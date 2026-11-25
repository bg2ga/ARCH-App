package cn.arch.dmr;

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

    private volatile DatagramSocket socket;
    private volatile InetAddress server;
    private volatile boolean ready;
    private volatile long lastPongAt;
    private volatile long rttMs;
    private volatile int txSeq;
    private volatile int txTimestamp;
    private volatile int rxHighest = -1;
    private volatile long rxBitmap;
    private volatile int lostPackets;
    private volatile int reorderedPackets;
    private volatile int targetDelayFrames = 2;
    private int lastAcceptedMissedFrames;
    private int rxAudioTimestamp;
    private boolean rxAudioTimestampValid;
    private final ScheduledExecutorService nackScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "arch-udp-nack"); t.setDaemon(true); return t;
    });
    private final LinkedHashMap<Integer, Boolean> recentlyReceived = new LinkedHashMap<Integer, Boolean>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) { return size() > 256; }
    };
    private volatile double jitterMs;
    private volatile long lastTransitMs = Long.MIN_VALUE;
    private volatile boolean markerPending = true;
    private volatile long lastAudioSentAt;
    private Thread rxThread;
    private Thread heartbeatThread;

    UdpVoiceTransport(String host, JSONObject cfg, int sampleRate, int frameSamples, int selfDmrId, Callback callback) {
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
        if (this.host.isEmpty() || this.port <= 0 || this.sampleRate <= 0 ||
                this.frameSamples <= 0 || sessionId.length != 8 || upKey == null ||
                downKey == null || upNoncePrefix.length != 4 || downNoncePrefix.length != 4) {
            throw new IllegalArgumentException("invalid UDP voice configuration");
        }
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        rxThread = new Thread(this::receiveLoop, "arch-udp-rx");
        heartbeatThread = new Thread(this::heartbeatLoop, "arch-udp-heartbeat");
        rxThread.start();
        heartbeatThread.start();
    }

    boolean isReady() { return running.get() && ready; }

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
        synchronized (sendLock) {
        DatagramSocket s = socket;
        InetAddress dst = server;
        if (s == null || dst == null || sessionId.length != 8) return false;
        int seq = txSeq;
        txSeq = (txSeq + 1) & 0xffffffff;
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
            setReady(false, "UDP send failed");
            return false;
        }
        if (cacheAudio) {
            synchronized (cacheLock) { txCache.put(seq, wire); }
        }
        return true;
        }
    }

    private void receiveLoop() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(500);
            try { s.setReceiveBufferSize(262144); } catch (Throwable ignored) {}
            try { s.setSendBufferSize(65536); } catch (Throwable ignored) {}
            try { s.setTrafficClass(0xB8); } catch (Throwable ignored) {}
            socket = s;
            server = InetAddress.getByName(host);
            byte[] buf = new byte[MAX_PACKET];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(packet);
                } catch (java.net.SocketTimeoutException te) {
                    continue;
                }
                if (!running.get()) break;
                if (packet.getLength() > HEADER_SIZE + 16) handlePacket(packet.getData(), packet.getLength());
            }
        } catch (Throwable t) {
            if (running.get()) setReady(false, "UDP receive failed");
        } finally {
            closeSocket();
            ready = false;
        }
    }

    private void handlePacket(byte[] data, int len) {
        try {
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
            if (!acceptSeq(seq)) return;

            if (kind == KIND_PONG) {
                long now = SystemClock.elapsedRealtime();
                if (plain.length >= 8) {
                    long sent = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).getLong();
                    rttMs = Math.max(0L, now - sent);
                }
                lastPongAt = now;
                setReady(true, "UDP connected");
            } else if (kind == KIND_NACK) {
                handleNack(plain);
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
                Callback cb = callback;
                if (cb != null) cb.onUdpAudio(ssrc, plain, timestamp, marker, missed);
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
        if (highest < 0) {
            lastAcceptedMissedFrames = 0;
            synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
            rxHighest = seq;
            rxBitmap = 1L;
            return true;
        }
        if (seq == highest) return false;
        if (seq < highest) {
            lastAcceptedMissedFrames = 0;
            int distance = highest - seq;
            if (distance >= 64) return false;
            long mask = 1L << distance;
            if ((rxBitmap & mask) != 0) return false;
            synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
            rxBitmap |= mask;
            reorderedPackets++;
            return true;
        }
        synchronized (recentlyReceived) { recentlyReceived.put(seq, Boolean.TRUE); }
        int gap = seq - highest;
        lastAcceptedMissedFrames = gap > 1 ? Math.min(gap - 1, 64) : 0;
        if (gap > 1 && gap <= MAX_NACK) {
            lostPackets += gap - 1;
            final ArrayList<Integer> missing = new ArrayList<>();
            for (int s = highest + 1; s < seq; s++) missing.add(s);
            nackScheduler.schedule(() -> {
                ArrayList<Integer> stillMissing = new ArrayList<>();
                synchronized (recentlyReceived) {
                    for (Integer n : missing) if (!recentlyReceived.containsKey(n)) stillMissing.add(n);
                }
                sendNack(stillMissing);
            }, NACK_DELAY_MS, TimeUnit.MILLISECONDS);
        } else if (gap > 1) {
            lostPackets += Math.min(gap - 1, 64);
        }
        rxBitmap = gap >= 64 ? 1L : (rxBitmap << gap) | 1L;
        rxHighest = seq;
        return true;
    }

    private void handleNack(byte[] plain) {
        try {
            JSONObject o = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            JSONArray arr = o.optJSONArray("n");
            if (arr == null || socket == null || server == null) return;
            for (int i = 0; i < arr.length() && i < MAX_NACK; i++) {
                int seq = arr.optInt(i);
                byte[] wire;
                synchronized (cacheLock) { wire = txCache.get(seq); }
                if (wire != null) socket.send(new DatagramPacket(wire, wire.length, server, port));
            }
        } catch (Throwable ignored) {}
    }

    private void sendNack(ArrayList<Integer> missing) {
        if (missing.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, missing.size() - MAX_NACK);
            for (int i = from; i < missing.size(); i++) arr.put(missing.get(i));
            JSONObject o = new JSONObject();
            o.put("n", arr);
            sendDatagram(KIND_NACK, 0, 0, o.toString().getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (Throwable ignored) {}
    }

    private void heartbeatLoop() {
        long lastStatsAt = 0L;
        while (running.get()) {
            try {
                InetAddress dst = server;
                if (dst == null) {
                    server = InetAddress.getByName(host);
                }
                byte[] ping = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                        .putLong(SystemClock.elapsedRealtime()).array();
                sendDatagram(KIND_PING, 0, 0, ping, 0, false);
                long now = SystemClock.elapsedRealtime();
                if (ready && now - lastPongAt > 7000L) setReady(false, "UDP timeout, WebSocket fallback");
                if (ready && now - lastStatsAt >= 5000L) {
                    JSONObject st = new JSONObject();
                    st.put("rtt", rttMs);
                    st.put("jitter", Math.round(jitterMs));
                    st.put("targetMs", targetDelayFrames * 20);
                    st.put("lost", lostPackets);
                    st.put("reordered", reorderedPackets);
                    st.put("queued", 0);
                    sendDatagram(KIND_STATS, 0, 0, st.toString().getBytes(StandardCharsets.UTF_8), 0, false);
                    lastStatsAt = now;
                    Callback cb = callback;
                    if (cb != null) cb.onUdpMetrics(rttMs, 0, lostPackets, reorderedPackets);
                }
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void setReady(boolean value, String message) {
        if (ready == value && value) return;
        ready = value;
        if (value) lastPongAt = SystemClock.elapsedRealtime();
        Callback cb = callback;
        if (cb != null) cb.onUdpReady(value, message == null ? "" : message);
    }

    void stop() {
        if (!running.getAndSet(false)) return;
        ready = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
        if (rxThread != null) rxThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        nackScheduler.shutdownNow();
        synchronized (recentlyReceived) { recentlyReceived.clear(); }
        synchronized (cacheLock) { txCache.clear(); }
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
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
