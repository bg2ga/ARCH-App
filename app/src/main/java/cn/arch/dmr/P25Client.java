package cn.arch.dmr;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/** Direct P25 Phase 1 reflector client. The UDP framing follows the DroidStar P25/QNet path. */
public final class P25Client {
    public interface Listener {
        void onState(int state, String message);
        void onVoice(int srcId, int dstId, short[] pcm160);
        void onEnd(int srcId, int dstId, int seconds);
        void onLog(String message);
    }

    private static final int STATE_CONNECTING=0, STATE_CONNECTED=1, STATE_DISCONNECTED=2, STATE_ERROR=3;
    private static final int RX_START_FRAMES=4;
    private static final long RX_IDLE_MS=1200L;
    private static final int[] OFFSETS={10,1,5,5,5,5,5,5,4,10,1,5,5,5,5,5,5,4};
    private static final byte[] REC62 = hex("6202020c0b1264000080000000000000000000000000");
    private static final byte[] REC63 = hex("6300000000000000000000000002");
    private static final byte[] REC64 = hex("6400000000000000000000000000000002");
    private static final byte[] REC65 = hex("6500000000000000000000000000000002");
    private static final byte[] REC66 = hex("6600000000000000000000000000000002");
    private static final byte[] REC67 = hex("67f09d6a00000000000000000000000002");
    private static final byte[] REC68 = hex("6819d42600000000000000000000000002");
    private static final byte[] REC69 = hex("69e0eb7b00000000000000000000000002");
    private static final byte[] REC6A = hex("6a000002000000000000000000000000");
    private static final byte[] REC6B = hex("6b02020c0b1264000080000000000000000000000000");
    private static final byte[] REC6C = hex("6c00000000000000000000000002");
    private static final byte[] REC6D = hex("6d00000000000000000000000000000002");
    private static final byte[] REC6E = hex("6e00000000000000000000000000000002");
    private static final byte[] REC6F = hex("6f00000000000000000000000000000002");
    private static final byte[] REC70 = hex("7000000000000000000000000000000002");
    private static final byte[] REC71 = hex("71acb8a400000000000000000000000002");
    private static final byte[] REC72 = hex("729bdc7500000000000000000000000002");
    private static final byte[] REC73 = hex("73000002000000000000000000000000");
    private static final byte[] REC80 = hex("8000000000000000000000000000000000");
    private static final byte[][] PACKETS={REC62, REC63, REC64, REC65, REC66, REC67, REC68, REC69, REC6A, REC6B, REC6C, REC6D, REC6E, REC6F, REC70, REC71, REC72, REC73};
    private static final byte[] END_PACKET=REC80;

    private final Object txLock=new Object();
    private final Object rxLock=new Object();
    private final ArrayDeque<short[]> rxPcmQueue=new ArrayDeque<>();
    private final P25Vocoder codec=new P25Vocoder();
    private volatile Listener listener;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile int port=41000;
    private volatile String callsign="";
    private volatile int srcId=0, dstId=0;
    private volatile boolean connected;
    private volatile boolean running, tx, rxEnded;
    private volatile int txStep=0;
    private volatile Thread rxThread, pingThread, playbackThread;
    private volatile long rxStart=0, lastRx=0;
    private volatile int rxSrc=0, rxDst=0;

    public void setListener(Listener l) { listener=l; }
    public boolean isRunning() { return running && socket!=null; }
    public boolean isConnected() { return connected && isRunning(); }
    public boolean isTx() { return tx; }
    public boolean codecOk() { return codec.ok(); }

    public boolean connect(String host,int port,String call,int sourceId,int destId) {
        stop();
        this.port=port; this.callsign=normalize(call); this.srcId=sourceId; this.dstId=destId; this.connected=false;
        if(this.callsign.isEmpty()) { state(STATE_ERROR,"P25 呼号为空"); return false; }
        if(!codec.ok()) { state(STATE_ERROR,"P25 声码器加载失败"); return false; }
        try {
            address=InetAddress.getByName(host);
            DatagramSocket s=new DatagramSocket(); s.setSoTimeout(500); socket=s;
            state(STATE_CONNECTING,"正在连接 P25 "+host+":"+port);
            send(controlPacket((byte)0xF0));
            running=true;
            rxThread=new Thread(this::rxLoop,"p25-rx");
            pingThread=new Thread(this::pingLoop,"p25-ping");
            playbackThread=new Thread(this::playbackLoop,"p25-playback");
            rxThread.start(); pingThread.start(); playbackThread.start();
            log("P25 已发送登录请求，等待反射器 Poll 应答 "+host+":"+port);
            return true;
        } catch(Throwable t) {
            closeSocket(); state(STATE_ERROR,String.valueOf(t.getMessage()==null?t:t.getMessage()));
            return false;
        }
    }

    public void startTx() {
        if(!isRunning()||tx) return;
        synchronized(txLock) { txStep=0; }
        tx=true;
        log("P25 开始发射");
    }

    public void stopTx() {
        boolean was=tx; tx=false;
        if(was&&isRunning()) send(END_PACKET.clone());
        synchronized(txLock) { txStep=0; }
        log("P25 停止发射");
    }

    public void sendPcm(short[] pcm160) {
        if(!tx||!isRunning()||pcm160==null||pcm160.length!=160) return;
        byte[] imbe=new byte[11];
        if(!codec.encode(pcm160,imbe)) return;
        byte[] out;
        synchronized(txLock) {
            out=packetForStep(txStep,imbe);
            txStep=(txStep+1)%PACKETS.length;
        }
        send(out);
    }

    private byte[] packetForStep(int step,byte[] imbe) {
        byte[] out=PACKETS[step].clone();
        int off=OFFSETS[step];
        System.arraycopy(imbe,0,out,off,11);
        if(step==3) putU24(out,1,dstId);
        else if(step==4) putU24(out,1,srcId);
        return out;
    }

    private void pingLoop() {
        while(running) {
            try { Thread.sleep(5000L); } catch(InterruptedException e) { return; }
            if(running) send(controlPacket((byte)0xF0));
        }
    }

    private void rxLoop() {
        byte[] buf=new byte[512];
        while(running) {
            DatagramSocket s=socket;
            if(s==null) return;
            try {
                DatagramPacket p=new DatagramPacket(buf,buf.length);
                s.receive(p);
                if(p.getLength()>0) handleRx(buf,p.getLength());
            } catch(SocketTimeoutException ignored) {
            } catch(Throwable t) {
                if(!running||s!=socket) return;
                log("P25 接收重试: "+t);
                try { Thread.sleep(20L); } catch(InterruptedException e) { return; }
            }
        }
    }

    private void handleRx(byte[] b,int len) {
        int cmd=b[0]&0xFF;
        if(cmd==0xF0 && len==11) {
            if(!connected) {
                connected=true;
                state(STATE_CONNECTED,"已连接 P25");
                log("P25 Poll 应答，反射器登录成功");
            }
            return;
        }
        if(cmd==0x80) {
            synchronized(rxLock) { rxEnded=true; }
            return;
        }
        int off=offsetFor(cmd);
        if(off<0||len<off+11) return;
        if(cmd==0x65) rxDst=u24(b,1);
        if(cmd==0x66) rxSrc=u24(b,1);
        if(rxStart==0) {
            codec.reset();
            rxStart=System.currentTimeMillis();
            rxEnded=false;
        }
        byte[] imbe=Arrays.copyOfRange(b,off,off+11);
        short[] pcm=new short[160];
        if(!codec.decode(imbe,pcm)) return;
        lastRx=System.currentTimeMillis();
        synchronized(rxLock) {
            rxPcmQueue.addLast(pcm);
            while(rxPcmQueue.size()>30) rxPcmQueue.removeFirst();
        }
    }

    private void playbackLoop() {
        long next=System.nanoTime();
        while(running) {
            next+=20_000_000L;
            long d=next-System.nanoTime();
            if(d>0) LockSupport.parkNanos(d); else next=System.nanoTime();
            if(tx) continue;
            short[] out=null; boolean end=false; long now=System.currentTimeMillis();
            synchronized(rxLock) {
                if(!rxPcmQueue.isEmpty() && (rxPcmQueue.size()>=RX_START_FRAMES || lastRx==0 || now-lastRx>300L)) {
                    out=rxPcmQueue.removeFirst();
                } else if(rxStart>0 && rxPcmQueue.isEmpty() && (rxEnded || lastRx==0 || now-lastRx>RX_IDLE_MS)) {
                    end=true;
                }
            }
            if(out!=null) {
                Listener l=listener; if(l!=null) l.onVoice(rxSrc,rxDst,out);
            }
            if(end) endRx();
        }
    }

    private synchronized void endRx() {
        if(rxStart==0) return;
        int sec=(int)((System.currentTimeMillis()-rxStart)/1000L); if(sec==0) sec=1;
        Listener l=listener; if(l!=null) l.onEnd(rxSrc,rxDst,sec);
        synchronized(rxLock) { rxPcmQueue.clear(); }
        rxStart=0; lastRx=0; rxEnded=false; rxSrc=0; rxDst=0;
    }

    public void stop() {
        if(running && socket!=null) send(controlPacket((byte)0xF1));
        running=false; tx=false; connected=false;
        Thread a=rxThread,b=pingThread,c=playbackThread; rxThread=pingThread=playbackThread=null;
        if(a!=null) a.interrupt(); if(b!=null) b.interrupt(); if(c!=null) c.interrupt();
        closeSocket();
        synchronized(rxLock) { rxPcmQueue.clear(); }
        state(STATE_DISCONNECTED,"已断开");
    }

    private void closeSocket() { DatagramSocket s=socket; socket=null; if(s!=null) try { s.close(); } catch(Throwable ignored) {} }

    private void send(byte[] b) {
        try { DatagramSocket s=socket; if(s!=null&&address!=null) s.send(new DatagramPacket(b,b.length,address,port)); }
        catch(Throwable t) { log("P25 发送失败: "+t); }
    }

    private byte[] controlPacket(byte cmd) {
        byte[] o=new byte[11]; o[0]=cmd;
        byte[] c=callsign.getBytes(StandardCharsets.US_ASCII);
        int n=Math.min(10,c.length); System.arraycopy(c,0,o,1,n);
        for(int i=1+n;i<o.length;i++) o[i]=' ';
        return o;
    }

    private static int offsetFor(int cmd) {
        if(cmd==0x62) return 10;
        if(cmd==0x63) return 1;
        if(cmd==0x64||cmd==0x65||cmd==0x66||cmd==0x67||cmd==0x68||cmd==0x69) return 5;
        if(cmd==0x6A) return 4;
        if(cmd==0x6B) return 10;
        if(cmd==0x6C) return 1;
        if(cmd>=0x6D&&cmd<=0x72) return 5;
        if(cmd==0x73) return 4;
        return -1;
    }

    private static int u24(byte[] b,int off) { return ((b[off]&0xFF)<<16)|((b[off+1]&0xFF)<<8)|(b[off+2]&0xFF); }
    private static void putU24(byte[] b,int off,int v) { b[off]=(byte)(v>>16); b[off+1]=(byte)(v>>8); b[off+2]=(byte)v; }
    private static byte[] hex(String s) {
        byte[] o=new byte[s.length()/2];
        for(int i=0;i<o.length;i++) o[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return o;
    }
    private static String normalize(String s) { return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]",""); }
    private void state(int s,String m) { Listener l=listener; if(l!=null) l.onState(s,m); }
    private void log(String s) { Listener l=listener; if(l!=null) l.onLog(s); }
}
