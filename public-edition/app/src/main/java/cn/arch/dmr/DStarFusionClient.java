package cn.arch.dmr;

import android.content.Context;
import cn.arch.dmr.archzjegwmq.ArchVocoder;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/**
 * D-Star UI backend for the XLXHLJ fusion route.
 *
 * The UI and callback contract stay identical to DStarClient.  This class
 * speaks XLX's DMR MMDVM/Homebrew link instead of D-Star AMBE, so the XLX
 * server performs the D-Star <-> DMR conversion.  Other D-Star servers keep
 * using the original native DStarClient path.
 */
final class DStarFusionClient {
    private static final int STATE_CONNECTING = 0;
    private static final int STATE_CONNECTED = 1;
    private static final int STATE_DISCONNECTED = 2;
    private static final int STATE_ERROR = 3;

    private static final int XLX_VOICE_TG = 9;
    private static final int XLX_SLOT = 2;
    private static final int XLX_COLOR_CODE = 1;
    private static final int RX_FRAME = 160;
    private static final int RX_MAX_FRAMES = 30;

    private final Object rxLock = new Object();
    private final ArrayDeque<short[]> rxPcmQueue = new ArrayDeque<>();
    private final ArchVocoder md380;

    private volatile DStarClient.Listener listener;
    private volatile HbpClient hbp;
    private volatile String module = "A";
    private volatile int state = STATE_DISCONNECTED;
    private volatile boolean tx;
    private volatile boolean running;
    private byte[] txAcc = new byte[0];
    private volatile String rxCall = "";
    private volatile boolean rxStreamEnded = true;
    private Thread playbackThread;

    DStarFusionClient(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        md380 = ArchVocoder.get(context);
    }

    void setListener(DStarClient.Listener l) { listener = l; }

    boolean isRunning() {
        HbpClient c = hbp;
        return running && c != null && c.isRunning();
    }

    boolean isLinkHealthy() {
        HbpClient c = hbp;
        return c != null && c.isRunning() && c.isConnected();
    }

    boolean isTx() { return tx; }
    boolean vocoderOk() { return md380.ok(); }

    boolean connect(String host, int port, String reflector, String callsign, String module, int dmrId) {
        stop();
        String call = normalizeCall(callsign);
        String mod = module == null || module.isEmpty() ? "A" : module.substring(0, 1).toUpperCase(Locale.US);
        this.module = mod;
        if (call.isEmpty()) { state(STATE_ERROR, "呼号为空"); return false; }
        if (dmrId <= 0) { state(STATE_ERROR, "请先保存有效的 DMR ID"); return false; }
        if (!md380.ok() || !md380SelfTest()) {
            state(STATE_ERROR, "MD380 声码器加载失败");
            return false;
        }

        state = STATE_CONNECTING;
        int activePort = probeDmrPort(host, port, dmrId);
        state(STATE_CONNECTING, "正在连接融合语音通道 " + reflector + " " + mod);
        final int chosenPort = activePort;
        final HbpClient c = new HbpClient(host, chosenPort, dmrId, dmrId, XLX_VOICE_TG, 0,
                XLX_SLOT, XLX_COLOR_CODE, call, "passw0rd", "", true, new HbpClient.Listener() {
            @Override public void onState(int st, String text) {
                if (st == 2) {
                    running = true;
                    startPlaybackThread();
                    HbpClient active = hbp;
                    if (active != null) active.switchXlxModule(DStarFusionClient.this.module.charAt(0));
                    state = STATE_CONNECTED;
                    DStarFusionClient.this.state(STATE_CONNECTED,
                            "已连接 " + reflector + " " + DStarFusionClient.this.module);
                    log("融合语音直连 " + reflector + " 模块 " + DStarFusionClient.this.module
                            + " @" + host + ":" + chosenPort);
                } else if (st == 1) {
                    state = STATE_CONNECTING;
                    DStarFusionClient.this.state(STATE_CONNECTING, text);
                } else if (st == 3) {
                    running = false;
                    state = STATE_ERROR;
                    DStarFusionClient.this.state(STATE_ERROR, text);
                } else {
                    running = false;
                    state = STATE_DISCONNECTED;
                    DStarFusionClient.this.state(STATE_DISCONNECTED, text);
                }
            }

            @Override public void onRxStart(int src, int dst) {
                if (!running) return;
                rxCall = String.valueOf(src);
                synchronized (rxLock) {
                    rxPcmQueue.clear();
                    rxStreamEnded = false;
                    rxLock.notifyAll();
                }
                log("融合语音收到呼叫 ID=" + src + " 目标=" + dst);
            }

            @Override public void onRxVoice(byte[] payload) {
                if (payload == null || payload.length < 33 || !running) return;
                short[] out;
                synchronized (md380) { out = md380.decodeDmrdPayload(payload); }
                if (out == null || out.length < RX_FRAME) return;
                int frames = out.length / RX_FRAME;
                synchronized (rxLock) {
                    if (!running) return;
                    for (int i = 0; i < frames; i++) {
                        rxPcmQueue.addLast(Arrays.copyOfRange(out, i * RX_FRAME, (i + 1) * RX_FRAME));
                    }
                    while (rxPcmQueue.size() > RX_MAX_FRAMES) rxPcmQueue.pollFirst();
                    rxStreamEnded = false;
                    rxLock.notifyAll();
                }
            }

            @Override public void onRxEnd(int src, int dst, int seconds) {
                synchronized (rxLock) {
                    rxStreamEnded = true;
                    rxLock.notifyAll();
                }
            }

            @Override public void onLog(String msg) { log(msg); }
        });
        hbp = c;
        c.start();
        return true;
    }

    private static byte[] pack27to33(byte[] ambe27) {
        byte[] payload33 = new byte[33];
        System.arraycopy(ambe27, 0, payload33, 0, 13);
        payload33[13] = (byte)(ambe27[13] & 0xF0);
        payload33[19] = (byte)(ambe27[13] & 0x0F);
        System.arraycopy(ambe27, 14, payload33, 20, 13);
        return payload33;
    }

    private boolean md380SelfTest() {
        try {
            byte[] a27 = new byte[27];
            for (int frame = 0; frame < 3; frame++) {
                short[] pcm = new short[RX_FRAME];
                for (int i = 0; i < pcm.length; i++) {
                    double t = (frame * RX_FRAME + i) / 8000.0;
                    pcm[i] = (short)(Math.sin(2.0 * Math.PI * 1000.0 * t) * 8000.0);
                }
                byte[] code;
                synchronized (md380) { code = md380.encodeFec(pcm); }
                if (code == null || code.length < 9) return false;
                System.arraycopy(code, 0, a27, frame * 9, 9);
            }
            short[] out;
            synchronized (md380) { out = md380.decodeDmrdPayload(pack27to33(a27)); }
            if (out == null || out.length < RX_FRAME) return false;
            long sumSq = 0L; int peak = 0;
            for (short v : out) { int a = Math.abs((int)v); if (a > peak) peak = a; sumSq += (long)v * v; }
            int rms = (int)Math.sqrt(sumSq / (double)out.length);
            android.util.Log.i("ARCH-MD380", "D-Star fusion self-test rms=" + rms + " peak=" + peak + " samples=" + out.length);
            return peak > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void startPlaybackThread() {
        if (playbackThread != null && playbackThread.isAlive()) return;
        playbackThread = new Thread(this::playbackLoop, "dstar-fusion-playback");
        playbackThread.start();
    }

    private void playbackLoop() {
        while (running) {
            short[] out = null;
            synchronized (rxLock) {
                while (running && rxPcmQueue.isEmpty()) {
                    if (tx) {
                        rxLock.notifyAll();
                        break;
                    }
                    try { rxLock.wait(100L); } catch (InterruptedException e) { return; }
                }
                if (!running) return;
                if (!rxPcmQueue.isEmpty()) out = rxPcmQueue.pollFirst();
            }
            if (out != null) {
                DStarClient.Listener l = listener;
                if (l != null) l.onVoice(rxCall, out);
            }
        }
    }

    private static int probeDmrPort(String host, int preferred, int dmrId) {
        int[] candidates = { preferred, 62130, 62030 };
        for (int i = 0; i < candidates.length; i++) {
            int p = candidates[i];
            boolean seen = false;
            for (int j = 0; j < i; j++) if (candidates[j] == p) seen = true;
            if (seen) continue;
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(1200);
                byte[] q = new byte[8];
                q[0]='R'; q[1]='P'; q[2]='T'; q[3]='L';
                q[4]=(byte)(dmrId>>>24); q[5]=(byte)(dmrId>>>16); q[6]=(byte)(dmrId>>>8); q[7]=(byte)dmrId;
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(host), p));
                byte[] r = new byte[64];
                DatagramPacket a = new DatagramPacket(r, r.length);
                s.receive(a);
                if (a.getLength() >= 6 && r[0]=='R' && r[1]=='P' && r[2]=='T' && r[3]=='A' && r[4]=='C' && r[5]=='K') return p;
            } catch (Throwable ignored) {
            } finally {
                if (s != null) s.close();
            }
        }
        return preferred;
    }

    void startTx() {
        HbpClient c = hbp;
        if (!isRunning() || c == null || !c.isConnected() || tx) return;
        txAcc = new byte[0];
        tx = true;
        c.txStart();
        log("融合语音开始发射");
    }

    void sendPcm(short[] pcm160) {
        HbpClient c = hbp;
        if (!tx || c == null || !c.isConnected() || pcm160 == null || pcm160.length != RX_FRAME) return;
        byte[] a9;
        synchronized (md380) { a9 = md380.encodeFec(pcm160); }
        if (a9 == null || a9.length < 9) return;
        byte[] next = new byte[txAcc.length + 9];
        System.arraycopy(txAcc, 0, next, 0, txAcc.length);
        System.arraycopy(a9, 0, next, txAcc.length, 9);
        txAcc = next;
        if (txAcc.length >= 27) {
            byte[] b27 = Arrays.copyOf(txAcc, 27);
            txAcc = Arrays.copyOfRange(txAcc, 27, txAcc.length);
            c.txVoice(b27);
        }
    }

    void stopTx() {
        HbpClient c = hbp;
        if (!tx) return;
        tx = false;
        if (c != null && c.isConnected()) c.txEnd();
        log("融合语音停止发射");
    }

    void stop() {
        tx = false;
        running = false;
        HbpClient c = hbp;
        hbp = null;
        if (c != null) { try { c.stop(); } catch (Throwable ignored) {} }
        synchronized (rxLock) {
            rxPcmQueue.clear();
            rxStreamEnded = true;
            rxCall = "";
            rxLock.notifyAll();
        }
        Thread t = playbackThread;
        playbackThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(200L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        state = STATE_DISCONNECTED;
    }

    private void state(int s, String message) {
        DStarClient.Listener l = listener;
        if (l != null) l.onState(s, message == null ? "" : message);
    }

    private void log(String message) {
        DStarClient.Listener l = listener;
        if (l != null) l.onLog(message);
    }

    private static String normalizeCall(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]", "");
    }
}