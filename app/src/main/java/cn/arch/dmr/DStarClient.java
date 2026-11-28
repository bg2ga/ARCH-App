package cn.arch.dmr;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/** Direct DCS/XLX D-Star client. Audio is 8 kHz/20 ms PCM. */
public final class DStarClient {
    private static final float DSTAR_ENCODER_TARGET = 0.10f;
    public interface Listener {
        void onState(int state, String message);
        void onVoice(String callsign, short[] pcm160);
        void onLog(String message);
    }

    private static final int STATE_CONNECTING = 0;
    private static final int STATE_CONNECTED = 1;
    private static final int STATE_DISCONNECTED = 2;
    private static final int STATE_ERROR = 3;

    private final Object sendLock = new Object();
    private final Object queueLock = new Object();
    private final java.util.concurrent.ExecutorService txExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ArrayDeque<short[]> rxQueue = new ArrayDeque<>();
    private final ArrayDeque<String> rxCalls = new ArrayDeque<>();
    private final DStarVocoder vocoder = new DStarVocoder();
    private final Context appContext;
    private volatile DStarFusionClient fusion;

    private volatile Listener listener;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile long lastServerRx = 0L;
    private volatile int port = 30051;
    private volatile String callsign = "";
    private volatile String reflector = "";
    private volatile String module = "A";
    private volatile boolean running;
    private volatile boolean tx;
    private volatile int txFrameNo;
    private volatile int txStreamId;
    private volatile String txRptr1 = "";
    private volatile String txRptr2 = "";
    private volatile String userText20 = "ARCH";
    private volatile String txUserText = "ARCH APP";
    private volatile String txSuffix4 = "ARCH";
    private Thread rxThread;
    private Thread pingThread;
    private Thread playThread;
    private static final int MAX_QUEUE_FRAMES = 50;          // 1 s
    private volatile int lastStreamId = -1;
    private volatile boolean rxStreamEnded = true;

    public DStarClient() { this(null); }
    public DStarClient(Context context) { appContext = context == null ? null : context.getApplicationContext(); }

    public void setListener(Listener l) {
        listener = l;
        DStarFusionClient f = fusion;
        if (f != null) f.setListener(l);
    }

    public void setUserText(String text) {
        userText20 = normalizeUserText(text);
        txSuffix4 = normalizeSuffix(userText20);
    }
    public boolean isRunning() {
        DStarFusionClient f = fusion;
        return f != null ? f.isRunning() : running && socket != null;
    }
    public boolean isLinkHealthy() {
        DStarFusionClient f = fusion;
        if (f != null) return f.isLinkHealthy();
        // Idle D-Star has no downlink traffic; an open registered UDP session is healthy.
        return isRunning();
    }
    public boolean isTx() {
        DStarFusionClient f = fusion;
        return f != null ? f.isTx() : tx;
    }
    public boolean vocoderOk() {
        DStarFusionClient f = fusion;
        return f != null ? f.vocoderOk() : vocoder.ok();
    }

    /** XLXHLJ fusion route: use the DMR MMDVM/Homebrew transport and let XLX transcode. */
    public boolean connectFusion(String host, int port, String reflector, String callsign, String module, int dmrId) {
        stop();
        DStarFusionClient f = new DStarFusionClient(appContext);
        f.setListener(listener);
        fusion = f;
        return f.connect(host, port, reflector, callsign, module, dmrId);
    }

    public boolean connect(String host, int port, String reflector, String callsign, String module) {
        stop();
        this.port = port;
        this.reflector = (reflector == null ? "" : reflector.trim().toUpperCase(Locale.US));
        this.callsign = normalizeCall(callsign);
        this.module = module == null || module.isEmpty() ? "A" : module.substring(0, 1).toUpperCase(Locale.US);
        if (this.callsign.isEmpty() || this.reflector.isEmpty()) {
            state(STATE_ERROR, "呼号或反射器为空");
            return false;
        }
        try {
            address = InetAddress.getByName(host);
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(4000);
            socket = s;
            state(STATE_CONNECTING, "正在连接 " + this.reflector + " " + this.module);
            byte[] hello = new byte[519];
            putPadded(hello, 0, 8, this.callsign);
            hello[8] = (byte)this.module.charAt(0);
            hello[9] = (byte)this.module.charAt(0);
            hello[10] = 11;
            synchronized (sendLock) { s.send(new DatagramPacket(hello, hello.length, address, port)); }
            byte[] reply = new byte[128];
            DatagramPacket p = new DatagramPacket(reply, reply.length);
            s.receive(p);
            if (p.getLength() < 13 || reply[10] != 'A' || reply[11] != 'C' || reply[12] != 'K') {
                String r = new String(reply, 10, Math.min(4, Math.max(0, p.getLength() - 10)), StandardCharsets.US_ASCII);
                throw new IllegalStateException("DCS 连接失败: " + r);
            }
            s.setSoTimeout(1000);
            lastServerRx = System.currentTimeMillis();
            running = true;
            if (!vocoder.ok()) throw new IllegalStateException("D-Star 声码器加载失败");
            if (!vocoder.selfTest()) throw new IllegalStateException("D-Star 声码器自检失败");
            lastStreamId = -1;
            rxStreamEnded = true;
            synchronized (queueLock) { rxQueue.clear(); rxCalls.clear(); }
            startThreads();
            state(STATE_CONNECTED, "已连接 " + this.reflector + " " + this.module);
            log("D-Star 直连 " + this.reflector + " " + this.module + " @" + host + ":" + port);
            return true;
        } catch (Throwable t) {
            closeSocket();
            state(STATE_ERROR, String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            return false;
        }
    }

    public void startTx() {
        dstarTxLeveler.reset();
        DStarFusionClient f = fusion;
        if (f != null) { f.startTx(); return; }
        if (!isRunning() || tx) return;
        // Match DroidStar/DCS: keep the AMBE encoder stream state and reset only the PTT frame counter.
        txFrameNo = 0;
        txStreamId = 1 + (int)(System.nanoTime() & 0x7FFE);
        txRptr2 = formatRoute(reflector, module);
        txRptr1 = formatRoute(callsign, module);
        txUserText = normalizeUserText(userText20);
        txSuffix4 = normalizeSuffix(txSuffix4);
        tx = true;
        log("D-Star 开始发射");
    }

    public void stopTx() {
        dstarTxLeveler.reset();
        DStarFusionClient f = fusion;
        if (f != null) { f.stopTx(); return; }
        if (!isRunning()) { tx = false; return; }
        boolean wasTx = tx;
        tx = false;
        if (wasTx) sendEndFrame();
        txFrameNo = 0;
        txStreamId = 0;
        log("D-Star 停止发射");
    }

    public void sendPcm(short[] pcm160) {
        if (pcm160 == null || pcm160.length != 160) return;
        short[] calibrated = dstarTxLeveler.process(pcm160);
        DStarFusionClient f = fusion;
        if (f != null) { f.sendPcm(calibrated); return; }
        if (!tx || !isRunning()) return;
        byte[] ambe = new byte[9];
        if (vocoder.encode(calibrated, ambe)) sendVoiceFrame(ambe, false);
    }

    private static final class DStarTxLeveler {
        private float gain = 0.18f;

        synchronized void reset() {
            gain = 0.18f;
        }

        synchronized short[] process(short[] pcm) {
            int peak = 0;
            for (short sample : pcm) {
                int value = sample < 0 ? -sample : sample;
                if (value > peak) peak = value;
            }
            float level = peak / 32768.0f;
            if (level > 0.004f) {
                float wanted = DSTAR_ENCODER_TARGET / level;
                wanted = Math.max(0.08f, Math.min(2.5f, wanted));
                gain += (wanted - gain) * (wanted < gain ? 0.42f : 0.08f);
            }
            short[] out = new short[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                int value = Math.round(pcm[i] * gain);
                out[i] = (short)Math.max(-32768, Math.min(32767, value));
            }
            return out;
        }
    }

    private final DStarTxLeveler dstarTxLeveler = new DStarTxLeveler();

    public void stop() {
        DStarFusionClient f = fusion;
        fusion = null;
        if (f != null) { try { f.stop(); } catch (Throwable ignored) {} }
        running = false;
        lastServerRx = 0L;
        boolean wasTx = tx;
        tx = false;
        final DatagramSocket oldSocket = socket;
        final InetAddress oldAddress = address;
        final int oldPort = port;
        socket = null;
        try { if (wasTx) sendEndFrameTo(oldSocket, oldAddress, oldPort); } catch (Throwable ignored) {}
        if (oldSocket != null && oldAddress != null) {
            final byte[] d = new byte[11];
            putPadded(d, 0, 8, callsign);
            d[8] = (byte)module.charAt(0);
            d[9] = ' ';
            d[10] = 0;
            txExec.execute(() -> {
                try { synchronized (sendLock) { oldSocket.send(new DatagramPacket(d, d.length, oldAddress, oldPort)); } }
                catch (Throwable ignored) {}
                try { oldSocket.close(); } catch (Throwable ignored) {}
            });
        } else if (oldSocket != null) {
            try { oldSocket.close(); } catch (Throwable ignored) {}
        }
        synchronized (queueLock) { rxQueue.clear(); rxCalls.clear(); queueLock.notifyAll(); }
        lastStreamId = -1;
        rxStreamEnded = true;
        try { vocoder.reset(); } catch (Throwable ignored) {}
        state(STATE_DISCONNECTED, "已断开");
    }

    private void startThreads() {
        rxThread = new Thread(this::receiveLoop, "dstar-rx");
        pingThread = new Thread(this::pingLoop, "dstar-ping");
        playThread = new Thread(this::playLoop, "dstar-play");
        rxThread.start(); pingThread.start(); playThread.start();
    }

    private void receiveLoop() {
        byte[] buf = new byte[512];
        DatagramSocket s = socket;
        while (running && s == socket) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                lastServerRx = System.currentTimeMillis();
                if (p.getLength() == 100 && buf[0]=='0' && buf[1]=='0' && buf[2]=='0' && buf[3]=='1') {
                    int frameNo = buf[45] & 0xff;
                    int sid = ((buf[43] & 0xff) << 8) | (buf[44] & 0xff);
                    synchronized (queueLock) {
                        if (sid != lastStreamId) {
                            lastStreamId = sid;
                            rxStreamEnded = false;
                            rxQueue.clear();
                            rxCalls.clear();
                            vocoder.reset();
                        }
                    }
                    if ((frameNo & 0x40) != 0) {
                        synchronized (queueLock) {
                            rxStreamEnded = true;
                            queueLock.notifyAll();
                        }
                        log("D-Star 语音流结束");
                        continue;
                    }
                    String src = new String(buf, 31, 8, StandardCharsets.US_ASCII).trim();
                    byte[] ambe = new byte[9];
                    System.arraycopy(buf, 46, ambe, 0, 9);
                    short[] pcm = new short[160];
                    if (vocoder.decode(ambe, pcm)) {
                        synchronized (queueLock) {
                            rxStreamEnded = false;
                            while (rxQueue.size() >= MAX_QUEUE_FRAMES) {
                                rxQueue.pollFirst();
                                rxCalls.pollFirst();
                            }
                            rxQueue.addLast(pcm);
                            rxCalls.addLast(src.isEmpty() ? "D-Star" : src);
                            queueLock.notifyAll();
                        }
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Throwable t) {
                if (!running || s != socket) break;
                log("D-Star 接收重试: " + t);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void pingLoop() {
        while (running) {
            try {
                Thread.sleep(2000);
                DatagramSocket s = socket;
                if (!running || s == null) break;
                sendKeepalive(s);
            } catch (InterruptedException e) { return; }
            catch (Throwable t) { if (running) log("D-Star 心跳异常: " + t); }
        }
    }
    private void sendKeepalive(DatagramSocket s) throws Exception {
        byte[] p = new byte[7 + 1 + 1 + reflector.length() + 1 + 1];
        int o = 0;
        byte[] c = padded7(callsign);
        System.arraycopy(c, 0, p, o, 7); o += 7;
        p[o++] = (byte)module.charAt(0); p[o++] = 0;
        System.arraycopy(reflector.getBytes(StandardCharsets.US_ASCII), 0, p, o, reflector.length()); o += reflector.length();
        p[o++] = 0; p[o] = (byte)module.charAt(0);
        synchronized (sendLock) { s.send(new DatagramPacket(p, p.length, address, port)); }
    }

    private void failLink(String message) {
        if (!running) return;
        running = false;
        tx = false;
        DatagramSocket old = socket;
        socket = null;
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) {}
        }
        synchronized (queueLock) {
            rxQueue.clear();
            rxCalls.clear();
            rxStreamEnded = true;
            queueLock.notifyAll();
        }
        lastStreamId = -1;
        state(STATE_ERROR, message);
    }

    private void playLoop() {
        while (running) {
            short[] pcm = null;
            String call = "";
            synchronized (queueLock) {
                while (running && rxQueue.isEmpty()) {
                    try { queueLock.wait(100L); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                pcm = rxQueue.pollFirst();
                call = rxCalls.isEmpty() ? "" : rxCalls.pollFirst();
            }
            if (pcm != null) {
                Listener l = listener;
                if (l != null) l.onVoice(call, pcm);
            }
        }
    }

    private void sendVoiceFrame(byte[] ambe, boolean end) {
        sendVoiceFrameTo(socket, address, port, txRptr1, txRptr2, callsign, txStreamId, txFrameNo, ambe, end);
        if (!end) txFrameNo++;
    }

    private void sendEndFrame() {
        byte[] last = new byte[]{(byte)0xdc,(byte)0x8e,(byte)0x0a,(byte)0x40,(byte)0xad,(byte)0xed,(byte)0xad,(byte)0x39,(byte)0x6e};
        sendVoiceFrame(last, true);
    }

    private void sendEndFrameTo(DatagramSocket s, InetAddress a, int p) {
        byte[] last = new byte[]{(byte)0xdc,(byte)0x8e,(byte)0x0a,(byte)0x40,(byte)0xad,(byte)0xed,(byte)0xad,(byte)0x39,(byte)0x6e};
        sendVoiceFrameTo(s, a, p, txRptr1, txRptr2, callsign, txStreamId, txFrameNo, last, true);
    }

    private void sendVoiceFrameTo(final DatagramSocket s, final InetAddress a, final int p,
                                  final String r1, final String r2, final String call,
                                  final int sid, final int fn, final byte[] ambe, final boolean end) {
        if (s == null || a == null || r1 == null || r1.isEmpty()) return;
        txExec.execute(() -> {
            byte[] f = new byte[100];
            putAscii(f, 0, "0001");
            putPadded(f, 7, 8, r2);
            putPadded(f, 15, 8, r1);
            putPadded(f, 23, 8, "CQCQCQ");
            putPadded(f, 31, 8, call);
            putAscii(f, 39, txSuffix4);
            f[43] = (byte)(sid >> 8); f[44] = (byte)sid;
            f[45] = (byte)(fn % 21);
            if (end) f[45] |= 0x40;
            System.arraycopy(ambe, 0, f, 46, 9);
            fillUserText(f);
            fillDcsText(f);
            f[58] = (byte)(fn & 0xff);
            f[59] = (byte)((fn >> 8) & 0xff);
            f[60] = (byte)((fn >> 16) & 0xff);
            f[61] = 1;
            try { synchronized (sendLock) { s.send(new DatagramPacket(f, f.length, a, p)); } }
            catch (Throwable t) { log("D-Star 发送失败: " + t); }
        });
    }

    private void fillDcsText(byte[] f) {
        // DCS text field: byte 63 is the separator and bytes 64-83 hold
        // the 20-character D-Star user text.
        f[63] = 0x21;
        for (int i = 0; i < 20; i++) f[64 + i] = (byte) txUserText.charAt(i);
        for (int i = 84; i < f.length; i++) f[i] = 0;
    }

    private static String normalizeSuffix(String v) {
        String s = v == null ? "" : v.trim().replaceAll("[^ -~]", "");
        if (s.length() > 4) s = s.substring(0, 4);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 4) b.append(' ');
        return b.toString();
    }

    private void fillUserText(byte[] f) {
        int n = f[45] & 0x3f;
        if (n == 0) {
            f[55] = 0x55; f[56] = 0x2d; f[57] = 0x16;
            return;
        }
        if (n >= 1 && n <= 8) {
            String t = txUserText;
            switch (n) {
                case 1: f[55] = (byte)(0x40 ^ 0x70); f[56] = (byte)(t.charAt(0) ^ 0x4f); f[57] = (byte)(t.charAt(1) ^ 0x93); break;
                case 2: f[55] = (byte)(t.charAt(2) ^ 0x70); f[56] = (byte)(t.charAt(3) ^ 0x4f); f[57] = (byte)(t.charAt(4) ^ 0x93); break;
                case 3: f[55] = (byte)(0x41 ^ 0x70); f[56] = (byte)(t.charAt(5) ^ 0x4f); f[57] = (byte)(t.charAt(6) ^ 0x93); break;
                case 4: f[55] = (byte)(t.charAt(7) ^ 0x70); f[56] = (byte)(t.charAt(8) ^ 0x4f); f[57] = (byte)(t.charAt(9) ^ 0x93); break;
                case 5: f[55] = (byte)(0x42 ^ 0x70); f[56] = (byte)(t.charAt(10) ^ 0x4f); f[57] = (byte)(t.charAt(11) ^ 0x93); break;
                case 6: f[55] = (byte)(t.charAt(12) ^ 0x70); f[56] = (byte)(t.charAt(13) ^ 0x4f); f[57] = (byte)(t.charAt(14) ^ 0x93); break;
                case 7: f[55] = (byte)(0x43 ^ 0x70); f[56] = (byte)(t.charAt(15) ^ 0x4f); f[57] = (byte)(t.charAt(16) ^ 0x93); break;
                case 8: f[55] = (byte)(t.charAt(17) ^ 0x70); f[56] = (byte)(t.charAt(18) ^ 0x4f); f[57] = (byte)(t.charAt(19) ^ 0x93); break;
                default: break;
            }
            return;
        }
        f[55] = 0x16; f[56] = 0x29; f[57] = (byte)0xf5;
    }

    private static String normalizeUserText(String v) {
        String s = v == null ? "" : v.trim().replaceAll("[^ -~]", "");
        if (s.length() > 20) s = s.substring(0, 20);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 20) b.append(' ');
        return b.toString();
    }

    private String normalizeCall(String v) {
        if (v == null) return "";
        return v.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]", "");
    }

    private static String formatRoute(String call, String module) {
        String s = call == null ? "" : call.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]", "");
        if (s.length() > 7) s = s.substring(0, 7);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 7) b.append(' ');
        b.append(module == null || module.isEmpty() ? 'A' : module.charAt(0));
        return b.toString();
    }
    private static byte[] padded7(String v) {
        byte[] out = new byte[7]; java.util.Arrays.fill(out, (byte)' ');
        byte[] b = v.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, out, 0, Math.min(7, b.length)); return out;
    }
    private static String pad8(String v) {
        String s = v == null ? "" : v.toUpperCase(Locale.US);
        if (s.length() > 8) s = s.substring(0, 8);
        StringBuilder b = new StringBuilder(s); while (b.length() < 8) b.append(' '); return b.toString();
    }
    private static void putPadded(byte[] out, int off, int len, String v) {
        String s = pad8(v);
        for (int i=0;i<len;i++) out[off+i]=(byte)s.charAt(Math.min(i,s.length()-1));
    }
    private static void putAscii(byte[] out, int off, String v) {
        byte[] b=v.getBytes(StandardCharsets.US_ASCII); System.arraycopy(b,0,out,off,Math.min(b.length,out.length-off));
    }
    private void state(int st, String msg) { Listener l=listener; if(l!=null) l.onState(st,msg); }
    private void log(String msg) { Listener l=listener; if(l!=null) l.onLog(msg); }
    private void closeSocket() { running=false; DatagramSocket s=socket; socket=null; if(s!=null) try{s.close();}catch(Throwable ignored){} }
}
