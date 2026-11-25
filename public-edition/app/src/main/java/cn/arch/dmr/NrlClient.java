package cn.arch.dmr;

import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal NRL2 UDP client.
 *
 * Protocol source: hicaoc/nrllink (MIT), NRL2: 48-byte header, big endian.
 * This client uses G.711 A-law (Type 1), the codec understood by every
 * current NRL server, and intentionally does not depend on a vendor token.
 */
public final class NrlClient {
    public static final int TYPE_VOICE = 1;
    public static final int TYPE_HEARTBEAT = 2;
    public static final int TYPE_TEXT = 5;
    public static final int TYPE_OPUS = 8;
    public static final int TYPE_GROUP = 7;

    public static final class Group {
        public final int id;
        public final String name;
        public Group(int id, String name) { this.id = id; this.name = name == null ? "" : name; }
    }

    public interface Listener {
        void onState(int state, String message);
        void onVoice(String callsign, int ssid, short[] pcm);
        void onGroupList(List<Group> groups);
        void onGroup(int id, String name);
        void onLog(String message);
    }

    private static final int HEADER = 48;
    private static final int HEARTBEAT_MS = 2000;

    private volatile Listener listener;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile int remotePort;
    private volatile String host = "";
    private volatile int dmrId;
    private volatile int ssid;
    private volatile String callsign = "";
    private volatile String password = "";
    private volatile int packetCount;
    private volatile int currentGroup;
    private volatile long lastRxAt;
    private volatile long lastGroupRequestAt;
    private volatile int groupRequestAttempts;
    private volatile boolean groupListReceived;
    private volatile FmoOpus opus16;
    private volatile HighQualityResampler g711To16;
    private volatile int opusRxLogCount;
    private volatile int duplicateVoiceDrops;
    private volatile String lastVoiceSource = "";
    private volatile int lastVoiceSeq = -1;
    private volatile long lastVoiceSeqAt = 0L;
    private volatile int generation;
    private volatile Thread worker;

    public void setListener(Listener l) { listener = l; }
    public boolean isRunning() { return running && connected; }
    public int currentGroup() { return currentGroup; }

    public synchronized void connect(String host, int port, String callsign, int dmrId,
                                     int ssid, String password, int group) {
        stop();
        final int gen = ++generation;
        this.host = host == null ? "" : host.trim();
        this.remotePort = port <= 0 || port > 65535 ? 60050 : port;
        this.callsign = (callsign == null ? "" : callsign.trim().toUpperCase(Locale.US));
        this.dmrId = dmrId;
        this.ssid = ssid < 0 ? 1 : (ssid & 0xFF);
        this.password = password == null ? "" : password;
        this.currentGroup = group;
        this.packetCount = 0;
        this.lastRxAt = 0L;
        this.lastGroupRequestAt = 0L;
        this.groupRequestAttempts = 0;
        this.groupListReceived = false;
        this.g711To16 = null;
        this.opusRxLogCount = 0;
        this.duplicateVoiceDrops = 0;
        this.lastVoiceSource = "";
        this.lastVoiceSeq = -1;
        this.lastVoiceSeqAt = 0L;
        running = true;
        connected = false;
        state(0, "正在连接 NRL", gen);
        Thread t = new Thread(() -> runLoop(gen), "nrl-udp-" + gen);
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        generation++;
        running = false;
        connected = false;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
        Thread w = worker;
        worker = null;
        if (w != null && w != Thread.currentThread()) {
            try { w.join(700L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        FmoOpus op = opus16;
        opus16 = null;
        g711To16 = null;
        lastVoiceSource = "";
        lastVoiceSeq = -1;
        lastVoiceSeqAt = 0L;
        if (op != null) try { op.close(); } catch (Throwable ignored) {}
    }

    public void requestGroupList() {
        if (!connected()) return;
        long now = System.currentTimeMillis();
        if (now - lastGroupRequestAt < 250L) return;
        lastGroupRequestAt = now;
        groupRequestAttempts++;
        final int attempt = groupRequestAttempts;
        Runnable send = () -> {
            sendHeartbeat();
            sendPacket(TYPE_GROUP, 1, new byte[]{ 2 });
            log("NRL 请求频道列表 #" + attempt);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) new Thread(send, "nrl-group-request").start();
        else send.run();
    }

    public void joinGroup(int groupId) {
        if (!connected()) return;
        currentGroup = groupId;
        ByteBuffer b = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 1);
        b.putInt(groupId);
        final byte[] data = b.array();
        if (Looper.myLooper() == Looper.getMainLooper()) new Thread(() -> sendPacket(TYPE_GROUP, 1, data), "nrl-join-group").start();
        else sendPacket(TYPE_GROUP, 1, data);
    }

    /** G.711 fallback for old servers. */
    public void sendVoice(short[] pcm) {
        if (!connected() || pcm == null || pcm.length == 0) return;
        byte[] alaw = G711.encode(pcm);
        sendPacket(TYPE_VOICE, 1, alaw);
    }

    /** Native NRL transmit path: Opus 16 kHz, 20 ms / 320 samples, 40 kbps. */
    public void sendOpus(short[] pcm320) {
        if (!connected() || pcm320 == null || pcm320.length == 0) return;
        FmoOpus op = ensureOpus();
        if (op == null) { sendVoice(pcm320); return; }
        short[] frame = pcm320;
        if (frame.length != 320) frame = java.util.Arrays.copyOf(frame, 320);
        byte[] encoded = op.encode(frame);
        if (encoded != null && encoded.length > 0) sendPacket(TYPE_OPUS, 1, encoded);
        else sendVoice(pcm320);
    }

    private synchronized FmoOpus ensureOpus() {
        FmoOpus op = opus16;
        if (op == null) {
            try {
                op = new FmoOpus(16000);
                op.setBitrate(40000);
                opus16 = op;
            } catch (Throwable ignored) { return null; }
        }
        return op;
    }

    private void runLoop(int gen) {
        while (running && gen == generation) {
            DatagramSocket s = null;
            try {
                address = InetAddress.getByName(host);
                s = new DatagramSocket();
                s.setSoTimeout(250);
                s.setReceiveBufferSize(8192);
                socket = s;
                connected = true;
                if (gen != generation) break;
                state(1, "已连接 NRL · " + host + ":" + remotePort, gen);
                sendHeartbeat();
                long nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_MS;
                byte[] buf = new byte[4096];
                while (running && gen == generation && socket == s) {
                    long now = System.currentTimeMillis();
                    if (now >= nextHeartbeat) {
                        sendHeartbeat();
                        nextHeartbeat = now + HEARTBEAT_MS;
                    }
                    if (!groupListReceived && groupRequestAttempts < 8
                            && now - lastGroupRequestAt >= 3000L) {
                        requestGroupList();
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        lastRxAt = System.currentTimeMillis();
                        handlePacket(p.getData(), p.getLength(), p.getAddress(), p.getPort(), gen);
                    } catch (SocketTimeoutException ignored) {
                        // Timeouts are expected; the heartbeat keeps the device online.
                    }
                }
            } catch (Throwable t) {
                if (running && gen == generation) {
                    connected = false;
                    state(3, "NRL 连接失败: " + safeMessage(t), gen);
                    log("NRL 连接失败: " + safeMessage(t));
                }
            } finally {
                if (gen == generation) connected = false;
                if (socket == s) socket = null;
                if (s != null) try { s.close(); } catch (Throwable ignored) {}
            }
            if (!running || gen != generation) break;
            try { Thread.sleep(1500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (running && gen == generation) state(0, "正在重连 NRL", gen);
        }
        if (gen == generation) state(2, "已断开 NRL", gen);
    }

    private void sendHeartbeat() {
        sendPacket(TYPE_HEARTBEAT, 1, new byte[0]);
    }

    private boolean connected() { return running && connected && socket != null; }

    private void sendPacket(int type, int status, byte[] data) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            final byte[] copy = data == null ? null : data.clone();
            new Thread(() -> sendPacketNow(type, status, copy), "nrl-send").start();
            return;
        }
        sendPacketNow(type, status, data);
    }

    private void sendPacketNow(int type, int status, byte[] data) {
        DatagramSocket s = socket;
        InetAddress a = address;
        if (s == null || a == null || !running) return;
        try {
            byte[] packet = encode(type, status, data);
            synchronized (this) {
                s.send(new DatagramPacket(packet, packet.length, a, remotePort));
            }
        } catch (Throwable t) {
            log("NRL 发送失败: " + safeMessage(t));
        }
    }

    private byte[] encode(int type, int status, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put("NRL2".getBytes(StandardCharsets.US_ASCII));
        b.putShort((short) (HEADER + payload.length));
        b.put((byte) ((dmrId >> 16) & 0xFF));
        b.put((byte) ((dmrId >> 8) & 0xFF));
        b.put((byte) (dmrId & 0xFF));
        putFixed(b, password.getBytes(StandardCharsets.UTF_8), 11);
        b.put((byte) type);
        b.put((byte) status);
        b.putShort((short) (packetCount++ & 0xFFFF));
        putFixed(b, callsign.getBytes(StandardCharsets.US_ASCII), 6);
        b.put((byte) ssid);
        b.put((byte) 120); // software NRL client
        b.put(new byte[16]);
        b.put(payload);
        return b.array();
    }

    private void handlePacket(byte[] data, int len, InetAddress from, int port, int gen) {
        if (gen != generation) return;
        if (len < HEADER || data[0] != 'N' || data[1] != 'R' || data[2] != 'L' || data[3] != '2') return;
        // Some nrllink group-list replies append CSV without updating the header
        // length field, so use the actual UDP datagram length for payload slicing.
        int packetLength = len;
        int type = data[20] & 0xFF;
        int srcSsid = data[30] & 0xFF;
        String srcCall = fixedString(data, 24, 6).trim();
        int packetSeq = ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        if (type == TYPE_VOICE || type == 9 || type == 8) {
            // NRL 服务器可能把本机语音回送给同一账号；不能再次播放成回声。
            if (srcCall.equalsIgnoreCase(callsign) && srcSsid == ssid) return;
            // 某些服务器会把同一个 UDP 包重复投递，按源和包序号做短暂去重。
            long now = System.currentTimeMillis();
            if (srcCall.equalsIgnoreCase(lastVoiceSource) && packetSeq == lastVoiceSeq
                    && now - lastVoiceSeqAt < 1500L) {
                if (++duplicateVoiceDrops <= 3) log("NRL 丢弃重复语音包 seq=" + packetSeq);
                return;
            }
            lastVoiceSource = srcCall;
            lastVoiceSeq = packetSeq;
            lastVoiceSeqAt = now;
            byte[] payload = new byte[Math.max(0, packetLength - HEADER)];
            if (payload.length > 0) System.arraycopy(data, HEADER, payload, 0, payload.length);
            if (type == TYPE_VOICE || type == 9) {
                short[] pcm8 = G711.decode(payload);
                if (g711To16 == null) g711To16 = new HighQualityResampler(8000, 16000);
                short[] pcm16 = pcm8.length == 0 ? pcm8 : g711To16.process(pcm8);
                if (pcm16 != null && pcm16.length > 0 && gen == generation && listener != null) listener.onVoice(srcCall, srcSsid, pcm16);
            } else {
                try {
                    FmoOpus op = ensureOpus();
                    short[] pcm16 = op == null ? null : op.decode(payload, 320);
                    if (pcm16 != null && pcm16.length > 0 && gen == generation && listener != null) listener.onVoice(srcCall, srcSsid, pcm16);
                    if (++opusRxLogCount <= 3) log("NRL Opus 接收(16k): " + payload.length + " bytes");
                } catch (Throwable t) {
                    if (++opusRxLogCount <= 3) log("NRL Opus 解码失败: " + safeMessage(t));
                }
            }
            return;
        }
        if (type == TYPE_GROUP && packetLength > HEADER) {
            byte[] payload = new byte[packetLength - HEADER];
            System.arraycopy(data, HEADER, payload, 0, payload.length);
            if (payload[0] == 1 && payload.length >= 5) {
                int id = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                String name = payload.length > 5 ? new String(payload, 5, payload.length - 5, StandardCharsets.UTF_8).trim() : "";
                if (name.toLowerCase(Locale.US).contains("error")) name = "无权限";
                if (gen == generation && listener != null) listener.onGroup(id, name);
            } else {
                // Some nrllink builds return raw CSV (without subtype 2); others use subtype 2.
                ArrayList<Group> groups = parseGroupList(payload);
                if (!groups.isEmpty()) groupListReceived = true;
                StringBuilder first = new StringBuilder();
                for (int i = 0; i < payload.length && i < 24; i++) first.append(String.format(Locale.US, "%02X ", payload[i] & 0xFF));
                log("NRL 收到频道列表: " + groups.size() + " bytes=" + payload.length + " head=" + first);
                if (gen == generation && listener != null) listener.onGroupList(groups);
            }
        }
    }

    private static ArrayList<Group> parseGroupList(byte[] payload) {
        ArrayList<Group> out = new ArrayList<>();
        int start = payload.length > 1 && (payload[0] & 0xFF) == 2 ? 1 : 0;
        // Accept both the official subtype-prefixed response and raw CSV responses.
        for (int i = start; i < payload.length; i++) {
            if (payload[i] >= '0' && payload[i] <= '9') { start = i; break; }
        }
        String text = payload.length <= start ? "" : new String(payload, start, payload.length - start, StandardCharsets.UTF_8);
        for (String line : text.split("\\n")) {
            String s = line == null ? "" : line.trim();
            int comma = s.indexOf(',');
            if (comma <= 0) continue;
            try {
                int id = Integer.parseInt(s.substring(0, comma).trim());
                out.add(new Group(id, s.substring(comma + 1).trim()));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static void putFixed(ByteBuffer b, byte[] src, int size) {
        int n = Math.min(size, src == null ? 0 : src.length);
        if (n > 0) b.put(src, 0, n);
        for (int i = n; i < size; i++) b.put((byte) 0);
    }

    private static String fixedString(byte[] b, int off, int len) {
        int end = off + len;
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < end && i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == 13) break;
            sb.append((char) c);
        }
        return sb.toString().trim().toUpperCase(Locale.US);
    }

    private void state(int s, String msg, int gen) {
        if (gen != generation) return;
        Listener l = listener;
        if (l != null) {
            try { l.onState(s, msg); } catch (Throwable ignored) {}
        }
    }

    private void log(String msg) {
        try { Log.i("ARCH-NRL", msg); } catch (Throwable ignored) {}
        Listener l = listener;
        if (l != null) try { l.onLog(msg); } catch (Throwable ignored) {}
    }

    private static String safeMessage(Throwable t) {
        return t == null ? "unknown" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    /** G.711 A-law codec (same algorithm as nrllink/g711.go). */
    public static final class G711 {
        private G711() {}

        public static byte[] encode(short[] pcm) {
            byte[] out = new byte[160];
            for (int i = 0; i < out.length; i++) {
                short s = i < pcm.length ? pcm[i] : 0;
                out[i] = linear2alaw(s);
            }
            return out;
        }

        public static short[] decode(byte[] alaw) {
            short[] out = new short[alaw == null ? 0 : alaw.length];
            for (int i = 0; i < out.length; i++) out[i] = alaw2linear(alaw[i]);
            return out;
        }

        private static byte linear2alaw(short sample) {
            int sign = 0;
            int ix;
            if (sample < 0) {
                sign = 0x80;
                ix = (~sample) >> 4;
            } else {
                ix = sample >> 4;
            }
            if (ix > 15) {
                int iexp = 1;
                while (ix > 31) {
                    ix >>= 1;
                    iexp++;
                }
                ix -= 16;
                ix += iexp << 4;
            }
            if (sign == 0) ix |= 0x80;
            return (byte) (ix ^ 0x55);
        }

        private static short alaw2linear(byte code) {
            int c = (code & 0xFF) ^ 0x55;
            int iexp = (c & 0x70) >> 4;
            int mant = c & 0x0F;
            if (iexp > 0) mant += 16;
            mant = (mant << 4) + 0x08;
            if (iexp > 1) mant <<= (iexp - 1);
            return (short) ((c & 0x80) != 0 ? mant : -mant);
        }
    }
}
