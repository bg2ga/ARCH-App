package cn.arch.dmr;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Homebrew(HBP) 协议客户端：登录、心跳、DMRD 收发。 */
public class HbpClient {
    private static final Object ACTIVE_LOCK = new Object();
    private static final java.util.HashMap<String, HbpClient> activeClients = new java.util.HashMap<>();
    public interface Listener {
        void onState(int state, String text);   // 0断开 1登录中 2已登录 3错误
        void onRxStart(int src, int dst);
        void onRxVoice(byte[] ambe27);
        void onRxEnd(int src, int dst, int seconds);
        void onLog(String s);
    }

    private static final byte[] RPTL = {'R','P','T','L'};
    private static final byte[] RPTK = {'R','P','T','K'};
    private static final byte[] RPTC = {'R','P','T','C'};
    private static final byte[] RPTO = {'R','P','T','O'};
    private static final byte[] PING = {'R','P','T','P','I','N','G'};
    private static final byte[] RPTCL = {'R','P','T','C','L'};

    private final String host; private final int port;
    private final int peerId, srcId, monitorTg, cc;
    private volatile int slot;
    private volatile int dstId;
    private final String callsign;
    private final String password;
    private final String options;
    private final boolean rawPayload;
    private final Listener cb;

    private DatagramSocket sock;
    private volatile InetAddress addr;
    private Thread rx, hb, tx;
    private final BlockingQueue<byte[]> txq = new LinkedBlockingQueue<>();
    private volatile boolean run = false;
    private volatile int state = 0;
    private int seq = 0;
    private byte[] streamId = new byte[4];
    private int txBurst = 0;
    private long txStart = 0;
    private int rxSrc = 0, rxDst = 0;
    private int rxStream = 0;
    private int lastDmrStream = 0, lastDmrSeq = -1;
    private long lastDmrSeqAt = 0L;
    private volatile int ownTxStream = 0;
    private volatile long ownTxUntil = 0;
    private long rxStart = 0, lastRxPacket = 0;
    private int rawStream = 0, rawSeq = -1;
    private long rawStreamAt = 0L;
    private boolean rawHaveStream = false;
    private long lastPing = 0, loginStart = 0;
    private int lastHeaderSrc = 0, lastHeaderDst = 0;
    private int lastHeaderStream = 0;
    private long lastHeaderAt = 0L;
    private long dmrPacketCount = 0, dmrFilteredCount = 0, lastStatsAt = 0;
    private long dmrSequenceGaps = 0L, dmrSequenceResets = 0L, rxIoErrors = 0L, dmrGapFillCount = 0L;
    private int seqTrackStream = 0, seqTrackLast = -1;
    private byte[] lastVoicePayload;
    private int lastDmrDst = 0;
    private volatile long lastRx = 0;
    private volatile boolean manualClose = false;
    private volatile boolean localTxActive = false;
    private static final int TX_QUEUE_MAX = 512;
    private long txDropped = 0L;
    private long lastTxDropLogAt = 0L;
    private boolean sentK = false;
    private boolean sentCfg = false;

    public HbpClient(String host, int port, int peerId, int srcId, int dstId,
                     int slot, int cc, String callsign, Listener cb) {
        this(host, port, peerId, srcId, dstId, 0, slot, cc, callsign, cb);
    }

    public HbpClient(String host, int port, int peerId, int srcId, int dstId, int monitorTg,
                     int slot, int cc, String callsign, Listener cb) {
        this(host, port, peerId, srcId, dstId, monitorTg, slot, cc, callsign, "", "", cb);
    }

    public HbpClient(String host, int port, int peerId, int srcId, int dstId, int monitorTg,
                     int slot, int cc, String callsign, String password, String options, Listener cb) {
        this(host, port, peerId, srcId, dstId, monitorTg, slot, cc, callsign, password, options, false, cb);
    }

    public HbpClient(String host, int port, int peerId, int srcId, int dstId, int monitorTg,
                     int slot, int cc, String callsign, String password, String options,
                     boolean rawPayload, Listener cb) {
        this.host = host; this.port = port; this.peerId = peerId;
        this.srcId = srcId; this.dstId = dstId; this.monitorTg = monitorTg; this.slot = slot;
        this.cc = cc; this.callsign = callsign;
        this.password = password == null ? "" : password;
        this.options = options == null ? "" : options;
        this.rawPayload = rawPayload;
        this.cb = cb;
        new Random().nextBytes(streamId);
        setSeq(0);
    }

    private void setState(int s, String t) { state = s; if (cb != null) cb.onState(s, t); }
    private void fail(String t) {
        if (manualClose || !run) return;
        localTxActive = false;
        run = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        if (tx != null) tx.interrupt();
        if (rx != null) rx.interrupt();
        if (hb != null) hb.interrupt();
        clearActive();
        setState(3, t);
    }
    private void log(String s) { if (cb != null) cb.onLog(s); }

    public void start() {
        synchronized (this) {
            if (run && sock != null && !sock.isClosed()) return;
        }
        synchronized (ACTIVE_LOCK) {
            String key = endpointKey();
            HbpClient old = activeClients.get(key);
            if (old != null && old != this && old.run) {
                // Only one HBP session per actual master endpoint. Digital mode
                // (FreeDMR/ARCH) and DMR-direct (BM) are different servers and
                // must be allowed to stay connected at the same time.
                old.manualClose = true;
                old.stop();
            }
            activeClients.put(key, this);
        }
        manualClose = false;
        sentK = false;
        sentCfg = false;
        run = true;
        loginStart = System.currentTimeMillis();
        lastRx = System.currentTimeMillis();
        try {
            sock = new DatagramSocket();
            try { sock.setReceiveBufferSize(512 * 1024); sock.setSendBufferSize(256 * 1024); } catch (Throwable ignored) {}
            sock.setSoTimeout(500);
        } catch (Exception e) {
            clearActive();
            run = false;
            setState(3, "网络错误: " + e.getMessage());
            return;
        }
        tx = new Thread(this::txLoop, "hbp-tx"); tx.start();
        rx = new Thread(this::rxLoop, "hbp-rx"); rx.start();
        hb = new Thread(this::hbLoop, "hbp-hb"); hb.start();
        setState(1, "登录中…");
    }

    public boolean isRunning() { return run; }
    /** 最近一次收到服务器报文的时刻（毫秒），用于判断链路是否还活着。 */
    public long lastRxMs() { return lastRx; }
    public boolean isConnected() { return state == 2; }
    public boolean isConnecting() { return run && state == 1; }

    public void stop() {
        manualClose = true;
        localTxActive = false;
        // 按标准 RPTCL 流程发送，释放 BM 旧会话，避免重复登录被 MSTNAK/MSTCL。
        try {
            if (state == 2 && run && sock != null && !sock.isClosed()) {
                if (addr == null) addr = InetAddress.getByName(host);
                byte[] bye = join(RPTCL, u32(peerId));
                sock.send(new DatagramPacket(bye, bye.length, addr, port));
            }
        } catch (Exception ignored) {}
        run = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        txq.clear();
        if (tx != null) tx.interrupt();
        if (rx != null) rx.interrupt();
        if (hb != null) hb.interrupt();
        // Do not let a replacement HBP session start before the old socket's
        // threads have really exited, otherwise the master sees a duplicate ID.
        joinQuietly(tx, 350L);
        joinQuietly(rx, 350L);
        joinQuietly(hb, 350L);
        tx = null; rx = null; hb = null;
        clearActive();
        setState(0, "已断开");
    }

    private String endpointKey() { return host + ":" + port; }

    private void clearActive() {
        synchronized (ACTIVE_LOCK) {
            String key = endpointKey();
            if (activeClients.get(key) == this) activeClients.remove(key);
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(timeoutMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private boolean send(byte[] b) {
        if (!run || b == null) return false;
        if (txq.size() >= TX_QUEUE_MAX) {
            txDropped++;
            long now = System.currentTimeMillis();
            if (now - lastTxDropLogAt >= 2000L) {
                lastTxDropLogAt = now;
                log("发送队列已满，丢弃=" + txDropped + "（限频日志）");
            }
            return false;
        }
        boolean queued = txq.offer(b);
        if (!queued) {
            txDropped++;
            log("发送队列拒绝报文，累计丢弃=" + txDropped);
        }
        return queued;
    }

    private void txLoop() {
        while (run) {
            byte[] b;
            try { b = txq.take(); }
            catch (InterruptedException e) { return; }
            try {
                if (sock == null || sock.isClosed()) continue;
                if (addr == null) addr = InetAddress.getByName(host);
                sock.send(new DatagramPacket(b, b.length, addr, port));
            } catch (Exception e) {
                if (!run) return;
                String m = e.getMessage();
                fail("发送失败: " + e.getClass().getSimpleName() + (m == null ? "" : ": " + m));
                return;
            }
        }
    }

    private static byte[] u32(int v) {
        return new byte[]{(byte)(v>>24),(byte)(v>>16),(byte)(v>>8),(byte)v};
    }
    private static byte[] padLeft(String s, int n) {
        byte[] o = new byte[n];
        byte[] b = s.getBytes();
        int copy = Math.min(b.length, n);
        System.arraycopy(b, 0, o, n - copy, copy);
        for (int i = 0; i < n - copy; i++) o[i] = ' ';
        return o;
    }
    private static byte[] pad(String s, int n) {
        byte[] o = new byte[n];
        byte[] b = s.getBytes();
        System.arraycopy(b, 0, o, 0, Math.min(b.length, n));
        for (int i = b.length; i < n; i++) o[i] = ' ';
        return o;
    }
    private void setSeq(int v) { seq = v & 0xFF; }

    private byte[] config() {
        // Homebrew/BM-compatible config. The master validates these fields,
        // especially the software/version/URL block, before accepting RPTC.
        byte[] c = new byte[294];
        int p = 0;
        long freq = 432000000L + (481L * 12500L);
        System.arraycopy(pad(callsign, 8), 0, c, p, 8); p += 8;
        System.arraycopy(pad(String.format(java.util.Locale.US, "%09d", freq), 9), 0, c, p, 9); p += 9;
        System.arraycopy(pad(String.format(java.util.Locale.US, "%09d", freq), 9), 0, c, p, 9); p += 9;
        System.arraycopy(pad("01", 2), 0, c, p, 2); p += 2; // hotspot slot
        System.arraycopy(pad("01", 2), 0, c, p, 2); p += 2; // hotspot color code
        System.arraycopy(pad("0.000000", 8), 0, c, p, 8); p += 8;
        System.arraycopy(padLeft("0.000000", 9), 0, c, p, 9); p += 9;
        System.arraycopy(pad("000", 3), 0, c, p, 3); p += 3;
        System.arraycopy(pad("", 20), 0, c, p, 20); p += 20;
        System.arraycopy(pad("", 19), 0, c, p, 19); p += 19;
        // Match MMDVMHost/Z3 for a non-duplex hotspot. In this protocol the
        // value is an MMDVMHost capability byte, not a general "declare
        // simplex" command; BrandMeister also derives DMO from equal RX/TX.
        c[p++] = (byte)'4';
        System.arraycopy(pad("https://arch.hljrsa.cn", 124), 0, c, p, 124); p += 124;
        System.arraycopy(pad("20260911", 40), 0, c, p, 40); p += 40;
        System.arraycopy(pad("MMDVM", 40), 0, c, p, 40);
        return c;
    }
    private void hbLoop() {
        long t0 = System.currentTimeMillis();
        send(join(RPTL, u32(peerId)));
        while (run) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            if (state == 1 && now - loginStart > 12000) {
                clearActive();
                setState(3, "连接超时");
                localTxActive = false;
                run = false;
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                if (tx != null) tx.interrupt();
                if (rx != null) rx.interrupt();
                return;
            }
            if (state == 1 && !sentK && now - t0 > 3000) { // 仅等待 salt 时重发 RPTL
                t0 = now;
                send(join(RPTL, u32(peerId)));
                log("重发登录请求");
            }
            if (rxStart > 0 && lastRxPacket > 0 && now - lastRxPacket > 700) {
                int sec = (int)((now - rxStart) / 1000);
                int src = rxSrc, dst = rxDst;
                rxStart = 0; lastRxPacket = 0;
                if (cb != null) cb.onRxEnd(src, dst, sec);
            }
            if (state == 2 && now - lastPing > 5000) {
                lastPing = now;
                send(join(PING, u32(peerId)));
            }
            // UDP 心跳只用于保活，不因单次 PONG 延迟主动拆掉已登录会话。
            if (state == 2 && now - lastStatsAt >= 15000L) {
                lastStatsAt = now;
                android.util.Log.i("ARCH-DMR", "HBP接收统计: DMRD=" + dmrPacketCount
                        + " 组过滤=" + dmrFilteredCount + " 序列缺口=" + dmrSequenceGaps
                        + " 序列重置=" + dmrSequenceResets + " IO错误=" + rxIoErrors
                        + " 最后目标=" + lastDmrDst
                        + " 当前=" + (slot == 2 ? "TS2=" : "TS1=") + dstId + ";");
            }

        }
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private void rxLoop() {
        byte[] buf = new byte[2048];
        while (run) {
            DatagramPacket pk = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(pk);
            } catch (java.net.SocketTimeoutException timeout) {
                continue;
            } catch (Exception e) {
                rxIoErrors++;
                continue;
            }
            lastRx = System.currentTimeMillis();
            // Homebrew 控制包和语音包可能由服务器集群的不同出口返回。
            // 不能仅凭单一 IP/端口过滤，否则会话显示已连接但 DMRD 会被静默丢弃。
            byte[] d = new byte[pk.getLength()];
            System.arraycopy(pk.getData(), 0, d, 0, d.length);
            try { handle(d); }
            catch (Exception e) { log("接收处理异常: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
        }
    }

    private void handle(byte[] d) {
        if (d.length >= 6 && d[0]=='R'&&d[1]=='P'&&d[2]=='T'&&d[3]=='A'&&d[4]=='C'&&d[5]=='K') {
            if (state == 1 && !sentK && d.length < 10) { log("登录应答缺少盐值"); return; }
            if (state == 1 && !sentK) {
                // 第一阶段：收到 salt -> RPTK(sha256(salt + hotspot password))
                byte[] salt = new byte[]{d[6],d[7],d[8],d[9]};
                byte[] h;
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(salt);
                    md.update(password.getBytes("UTF-8"));
                    h = md.digest();
                } catch (Exception e) { h = new byte[32]; }
                send(join(join(RPTK, u32(peerId)), h));
                sentK = true;
                log("发送登录应答");
            } else if (state == 1 && !sentCfg) {
                // 第二阶段：主站确认认证 -> RPTC 配置
                send(join(join(RPTC, u32(peerId)), config()));
                sentCfg = true;
                log("发送配置");
            } else if (state == 1) {
                // 第三阶段：主站确认配置 -> RPTO 静态组，然后进入已连接
                if (options.length() > 0) send(join(join(RPTO, u32(peerId)), options.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                setState(2, "已连接");
                log("登录成功");
            }
            return;
        }
        if (d.length >= 6 && d[0]=='M'&&d[1]=='S'&&d[2]=='T'&&d[3]=='N'&&d[4]=='A'&&d[5]=='K') {
            fail("BM/Master 拒绝登录");
            return;
        }
        if (d.length >= 5 && d[0]=='M'&&d[1]=='S'&&d[2]=='T'&&d[3]=='C'&&d[4]=='L') {
            fail("主站关闭连接");
            return;
        }
        if (d.length >= 7 && d[0]=='M'&&d[1]=='S'&&d[2]=='T'&&d[3]=='P'&&d[4]=='O'&&d[5]=='N'&&d[6]=='G') return;
        if (d.length >= 53 && d[0]=='D'&&d[1]=='M'&&d[2]=='R'&&d[3]=='D') onDmrd(d);
    }

    private void onDmrd(byte[] d) {
        int src = ((d[5]&0xFF)<<16)|((d[6]&0xFF)<<8)|(d[7]&0xFF);
        int dst = ((d[8]&0xFF)<<16)|((d[9]&0xFF)<<8)|(d[10]&0xFF);
        int sid = ((d[16]&0xFF)<<24)|((d[17]&0xFF)<<16)|((d[18]&0xFF)<<8)|(d[19]&0xFF);
        int fl  = d[15] & 0xFF;
        int dtype = fl & 0x0F, ftype = (fl >> 4) & 0x03;
        dmrPacketCount++;
        lastDmrDst = dst;
        // 只屏蔽本机本次发射的极短回环；不能把所有同 DMR ID 的电台都丢掉。
        // The master can mirror our own stream back with a different stream id or
        // after the original 5 s window. While local TX is active, never turn our
        // own transmission into an "incoming call" in the UI.
        if (src == srcId && (localTxActive || System.currentTimeMillis() <= ownTxUntil)) return;
        if (dst != dstId && dst != monitorTg) { dmrFilteredCount++; return; }   // 主组或双守听组

        if (ftype == 2 && dtype == 2) {                 // 标准结束帧 0x22
            if (rxStream != 0 && sid != rxStream) return;
            if (rxStart != 0 && (src != rxSrc || dst != rxDst)) return;
            if (rawPayload && (!rawHaveStream || sid == rawStream)) { rawHaveStream = false; rawSeq = -1; rawStreamAt = 0L; }
            int sec = rxStart == 0 ? 0 : (int)((System.currentTimeMillis() - rxStart) / 1000);
            rxStart = 0; lastRxPacket = 0; lastHeaderAt = 0L;
            rxStream = 0; lastDmrStream = 0; lastDmrSeq = -1; lastDmrSeqAt = 0L;
            lastVoicePayload = null;
            if (cb != null) cb.onRxEnd(src, dst, sec);
            return;
        }

        if (ftype == 2 && dtype == 1) {                 // 标准语音头
            long headerNow = System.currentTimeMillis();
            boolean newTalkspurt = rxStart == 0 || src != lastHeaderSrc || dst != lastHeaderDst
                    || sid != lastHeaderStream || headerNow - lastHeaderAt > 1000L;
            rxSrc = src; rxDst = dst; rxStart = headerNow;
            lastRxPacket = headerNow;
            lastHeaderSrc = src; lastHeaderDst = dst; lastHeaderStream = sid; lastHeaderAt = headerNow;
            if (newTalkspurt) rxStream = sid;
            if (newTalkspurt && cb != null) cb.onRxStart(src, dst);
        } else if (ftype == 0 || ftype == 1) {           // 语音突发；dtype=2 也是合法突发 C
            if (d.length < 53) return;
            if (rxStart == 0) {                          // 头部丢失时仍能建立通话记录
                rxSrc = src; rxDst = dst; rxStream = sid; rxStart = System.currentTimeMillis();
                if (cb != null) cb.onRxStart(src, dst);
            }
            int seq = d[4] & 0xFF;
            long now = System.currentTimeMillis();
            if (sid == lastDmrStream && seq == lastDmrSeq && now - lastDmrSeqAt < 1500L) return;
            if (sid == seqTrackStream && seqTrackLast >= 0) {
                int delta = (seq - seqTrackLast) & 0xFF;
                if (delta > 1 && delta < 128) {
                    dmrSequenceGaps += delta - 1L;
                    int fill = Math.min(2, delta - 1);
                    if (lastVoicePayload != null && cb != null) {
                        for (int i = 0; i < fill; i++) {
                            cb.onRxVoice(lastVoicePayload.clone());
                            dmrGapFillCount++;
                        }
                    }
                } else if (delta > 128) {
                    dmrSequenceResets++;
                    lastVoicePayload = null;
                }
            } else {
                lastVoicePayload = null;
            }
            seqTrackStream = sid;
            seqTrackLast = seq;
            lastDmrStream = sid; lastDmrSeq = seq; lastDmrSeqAt = now;
            if (rawPayload) {
                if (!rawHaveStream || now - rawStreamAt > 2000L) {
                    rawHaveStream = true;
                    rawStream = sid;
                    rawSeq = -1;
                }
                if (sid != rawStream) return;
                rawStreamAt = now;
                if (seq == rawSeq) return;
                rawSeq = seq;
            }
            lastRxPacket = now;
            byte[] voicePayload;
            if (rawPayload) {
                voicePayload = new byte[33];
                System.arraycopy(d, 20, voicePayload, 0, 33);
            } else {
                voicePayload = new byte[27];
                System.arraycopy(d, 20, voicePayload, 0, 13);
                voicePayload[13] = (byte)((d[33] & 0xF0) | (d[39] & 0x0F));
                System.arraycopy(d, 40, voicePayload, 14, 13);
            }
            lastVoicePayload = voicePayload.clone();
            if (cb != null) try { cb.onRxVoice(voicePayload); } catch (Exception e) {
                log("解码播放异常: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }
    }

    /** Set the target used by the next DMR burst without changing RPTO. */
    public void setTxTarget(int tg) { if (tg > 0) dstId = tg; }

    /** Send one group-call voice LC header, matching Z3 auto-static key-up. */
    public boolean txHeaderTo(int target) {
        if (state != 2 || target <= 0) return false;
        new Random().nextBytes(streamId);
        ownTxStream = ((streamId[0] & 0xFF) << 24) | ((streamId[1] & 0xFF) << 16)
                | ((streamId[2] & 0xFF) << 8) | (streamId[3] & 0xFF);
        ownTxUntil = System.currentTimeMillis() + 2000L;
        setSeq(0);
        boolean sent = sendDmrdTo(0x21, target, DmrCodec.lcBurst(srcId, target, false, cc));
        log("发送附着头 dst=" + target + " flags=" + (slot == 2 ? "0xA1" : "0x21")
                + " peer=" + peerId + " sent=" + sent);
        return sent;
    }

    /** 切换并附着到指定 BM 通话组；RPTO 使用标准 Homebrew 格式。 */
    public void setTalkgroup(int tg) {
        setTalkgroup(tg, slot);
    }

    /** Attach a talkgroup and update the slot used by subsequent DM R bursts. */
    public void setTalkgroup(int tg, int newSlot) {
        if (tg <= 0) return;
        int requestedSlot = newSlot == 2 ? 2 : 1;
        if (state == 2) {
            String opt = (requestedSlot == 2 ? "TS2=" : "TS1=") + tg + ";";
            boolean sent = send(join(join(RPTO, u32(peerId)), opt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!sent) {
                fail("通话组附着发送失败");
                return;
            }
            log("发送通话组附着: " + opt);
        }
        dstId = tg;
        slot = requestedSlot;
    }

    /** XLX over DMR：私呼 4000 退出旧模块，再私呼 4001+N 进入指定模块。 */
    public void switchXlxModule(char module) {
        if (state != 2) return;
        int idx = Character.toUpperCase(module) - 'A';
        if (idx < 0 || idx >= 26) return;
        sendXlxControl(4000);
        sendXlxControl(4001 + idx);
        log("XLX 模块切换: " + Character.toUpperCase(module));
    }

    private void sendXlxControl(int target) {
        byte[] lc = DmrCodec.lcBurst(srcId, target, false, cc);
        int header = 0x21 | 0x40;
        if (slot == 2) header |= 0x80;
        for (int i = 0; i < 3; i++) sendDmrdTo(header, target, lc);
        byte[] endLc = DmrCodec.lcBurst(srcId, target, true, cc);
        int end = 0x22 | 0x40;
        if (slot == 2) end |= 0x80;
        sendDmrdTo(end, target, endLc);
    }

    /** 开始发射：发语音头。返回值表示报文是否真正进入发送队列。 */
    public boolean txStart() {
        txBurst = 0; txStart = System.currentTimeMillis(); localTxActive = true;
        new Random().nextBytes(streamId);
        ownTxStream = ((streamId[0]&0xFF)<<24)|((streamId[1]&0xFF)<<16)|((streamId[2]&0xFF)<<8)|(streamId[3]&0xFF);
        ownTxUntil = System.currentTimeMillis() + 1000;
        setSeq(0);
        boolean sent = sendDmrd(0x21, DmrCodec.lcBurst(srcId, dstId, false, cc));
        if (!sent) { localTxActive = false; ownTxUntil = 0L; }
        return sent;
    }

    /** 发射一帧语音（27 字节 AMBE，切成 3 个 9 字节）。 */
    public boolean txVoice(byte[] ambe27) {
        if (ambe27 == null || ambe27.length < 27) return false;
        byte[] pay = new byte[33];
        System.arraycopy(ambe27, 0, pay, 0, 13);
        pay[13] = (byte)(ambe27[13] & 0xF0);
        pay[19] = (byte)(ambe27[13] & 0x0F);
        System.arraycopy(ambe27, 14, pay, 20, 13);
        int fl = (txBurst == 0) ? 0x10 : (txBurst & 0x0F);
        boolean sent = sendDmrd(fl, pay);
        if (sent) txBurst = (txBurst + 1) % 6;
        return sent;
    }

    public boolean txEnd() {
        localTxActive = false;
        ownTxUntil = System.currentTimeMillis() + 2000;
        return sendDmrd(0x22, DmrCodec.lcBurst(srcId, dstId, true, cc));
    }

    private boolean sendDmrd(int flags, byte[] payload33) {
        return sendDmrdTo(flags, dstId, payload33);
    }

    private boolean sendDmrdTo(int flags, int target, byte[] payload33) {
        if (slot == 2) flags |= 0x80;
        byte[] p = new byte[55];
        p[0]='D';p[1]='M';p[2]='R';p[3]='D';
        p[4] = (byte)(seq++ & 0xFF);
        p[5]=(byte)(srcId>>16); p[6]=(byte)(srcId>>8); p[7]=(byte)srcId;
        p[8]=(byte)(target>>16); p[9]=(byte)(target>>8); p[10]=(byte)target;
        System.arraycopy(u32(peerId), 0, p, 11, 4);
        p[15] = (byte) flags;
        System.arraycopy(streamId, 0, p, 16, 4);
        System.arraycopy(payload33, 0, p, 20, 33);
        p[53] = 0; p[54] = 0;
        return send(p);
    }

}




