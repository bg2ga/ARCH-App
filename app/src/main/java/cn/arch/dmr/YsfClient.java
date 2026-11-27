package cn.arch.dmr;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/** Direct YSF DN-mode client. TX is driven by the 20 ms audio clock; RX uses a 100 ms jitter buffer. */
public final class YsfClient {
    public interface Listener {
        void onState(int state, String message);
        void onVoice(String callsign, short[] pcm160);
        void onEnd(String callsign, int seconds);
        void onLog(String message);
    }
    private static final int STATE_CONNECTING=0, STATE_CONNECTED=1, STATE_DISCONNECTED=2, STATE_ERROR=3;
    private static final int RX_START_FRAMES=5;
    private static final long RX_IDLE_END_NS=1_200_000_000L;
    private static final long RX_CONCEAL_NS=200_000_000L;
    private final Object txLock=new Object();
    private final Object rxLock=new Object();
    private final ArrayDeque<short[]> rxPcmQueue=new ArrayDeque<>();
    private final short[] txPcm800=new short[800];
    private final YsfCodec codec=new YsfCodec();
    private volatile Listener listener;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile int port=42000;
    private volatile String callsign="";
    private volatile boolean running, tx;
    private volatile int txFrameNo, txPcmLen;
    private volatile Thread rxThread, pingThread, playbackThread;
    private volatile long rxStart=0, lastRx=0;
    private volatile String rxCall="";

    public YsfClient() { }
    public void setListener(Listener l){ listener=l; }
    public boolean isRunning(){ return running && socket!=null; }
    public boolean isTx(){ return tx; }
    public boolean codecOk(){ return codec.ok(); }

    public boolean connect(String host,int port,String call){
        stop();
        this.port=port; this.callsign=normalize(call);
        if(this.callsign.isEmpty()){ state(STATE_ERROR,"YSF 呼号为空"); return false; }
        if(!codec.ok()){ state(STATE_ERROR,"YSF 声码器加载失败"); return false; }
        try{
            address=InetAddress.getByName(host);
            DatagramSocket s=new DatagramSocket(); s.setSoTimeout(3000); socket=s;
            state(STATE_CONNECTING,"正在连接 YSF "+host+":"+port);
            byte[] hello=packet(callsign);
            s.send(new DatagramPacket(hello,hello.length,address,port));
            byte[] reply=new byte[64]; DatagramPacket p=new DatagramPacket(reply,reply.length); s.receive(p);
            String r=new String(reply,0,p.getLength(),StandardCharsets.US_ASCII);
            if(!r.startsWith("YSFPREFLECTOR")) throw new IllegalStateException("YSF 反射器无响应: "+r.trim());
            s.setSoTimeout(1000); running=true;
            rxThread=new Thread(this::rxLoop,"ysf-rx");
            pingThread=new Thread(this::pingLoop,"ysf-ping");
            playbackThread=new Thread(this::playbackLoop,"ysf-playback");
            rxThread.start(); pingThread.start(); playbackThread.start();
            state(STATE_CONNECTED,"已连接 YSF "+host+":"+port); log("YSF 直连 "+host+":"+port);
            return true;
        }catch(Throwable t){ closeSocket(); state(STATE_ERROR,String.valueOf(t.getMessage()==null?t:t.getMessage())); return false; }
    }

    public void startTx(){
        if(!isRunning()||tx) return;
        synchronized(txLock){ txPcmLen=0; txFrameNo=0; }
        synchronized(rxLock){ rxPcmQueue.clear(); }
        tx=true;
        byte[] h=new byte[155];
        if(codec.encodeHeader(h,pad10(callsign),0,false)) send(h);
        log("YSF 开始发射");
    }

    public void stopTx(){
        boolean was=tx; tx=false;
        if(was&&isRunning()){byte[] h=new byte[155];codec.encodeHeader(h,pad10(callsign),txFrameNo,true);send(h);}
        synchronized(txLock){txPcmLen=0;}
        log("YSF 停止发射");
    }

    public void sendPcm(short[] pcm160){
        if(!tx||!isRunning()||pcm160==null||pcm160.length!=160)return;
        byte[] out=null;
        synchronized(txLock){
            System.arraycopy(pcm160,0,txPcm800,txPcmLen,160);
            txPcmLen+=160;
            if(txPcmLen==800){
                out=new byte[155];
                if(codec.encodeVoice(out,pad10(callsign),txPcm800,txFrameNo)){ txFrameNo++; }
                else out=null;
                txPcmLen=0;
            }
        }
        if(out!=null) send(out);
    }

    private void pingLoop(){
        while(running){try{Thread.sleep(5000L);}catch(InterruptedException e){return;} if(running)send(packet(callsign));}
    }

    private void rxLoop(){
        byte[] buf=new byte[512]; DatagramSocket s=socket;
        while(running&&s==socket){
            try{
                DatagramPacket p=new DatagramPacket(buf,buf.length); s.receive(p);
                if(p.getLength()==155&&buf[0]=='Y'&&buf[1]=='S'&&buf[2]=='F'&&buf[3]=='D'){
                    byte[] f=java.util.Arrays.copyOf(buf,155); int type=codec.frameType(f); lastRx=System.nanoTime();
                    if(type==0){
                        byte[] src=new byte[10],dst=new byte[10];
                        if(codec.decodeHeader(f,src,dst)){rxCall=trim(src);rxStart=System.currentTimeMillis();Listener l=listener;if(l!=null)l.onLog("YSF 收到 "+rxCall);}
                    }else if(type==1 && !tx){
                        byte[] src=new byte[10],dst=new byte[10];short[] pcm=new short[800];
                        if(codec.decodeVoice(f,pcm,src,dst)){
                            String voiceCall=trim(src);if(!voiceCall.isEmpty())rxCall=voiceCall;
                            if(rxStart==0)rxStart=System.currentTimeMillis();
                            synchronized(rxLock){
                                for(int i=0;i<5;i++) rxPcmQueue.addLast(java.util.Arrays.copyOfRange(pcm,i*160,i*160+160));
                                while(rxPcmQueue.size()>25) rxPcmQueue.pollFirst();
                            }
                        }
                    }else if(type==2){ lastRx=0; }
                }
            }catch(java.net.SocketTimeoutException ignored){
                if(rxStart>0&&lastRx>0&&System.nanoTime()-lastRx>RX_IDLE_END_NS){ endRx(); }
            }catch(Throwable t){if(!running||s!=socket)break;log("YSF 接收重试: "+t);try{Thread.sleep(50L);}catch(InterruptedException e){return;}}
        }
    }

    private void playbackLoop(){
        long next=System.nanoTime(); short[] last=null; long lastVoiceAt=0;
        while(running){
            next+=20_000_000L; long d=next-System.nanoTime(); if(d>0)LockSupport.parkNanos(d); else next=System.nanoTime();
            if(tx) continue;
            short[] out=null; boolean end=false; long now=System.nanoTime();
            synchronized(rxLock){
                if(!rxPcmQueue.isEmpty() && (rxPcmQueue.size()>=RX_START_FRAMES || lastRx==0 || now-lastRx>300_000_000L)){
                    out=rxPcmQueue.pollFirst();
                }else if(!rxPcmQueue.isEmpty() && last!=null && now-lastVoiceAt<RX_CONCEAL_NS){
                    out=last;
                }else if(rxStart>0 && rxPcmQueue.isEmpty() && (lastRx==0 || now-lastRx>RX_IDLE_END_NS)){
                    end=true;
                }
            }
            if(out!=null){
                last=out; lastVoiceAt=now; Listener l=listener; if(l!=null)l.onVoice(rxCall,out);
            }
            if(end) endRx();
        }
    }

    private synchronized void endRx(){
        if(rxStart==0)return;
        int sec=(int)((System.currentTimeMillis()-rxStart)/1000L);if(sec==0)sec=1;
        Listener l=listener;if(l!=null)l.onEnd(rxCall,sec);
        synchronized(rxLock){rxPcmQueue.clear();}
        rxStart=0;lastRx=0;rxCall="";
    }

    public void stop(){running=false;tx=false;Thread a=rxThread,b=pingThread,c=playbackThread;rxThread=pingThread=playbackThread=null;if(a!=null)a.interrupt();if(b!=null)b.interrupt();if(c!=null)c.interrupt();closeSocket();synchronized(rxLock){rxPcmQueue.clear();}state(STATE_DISCONNECTED,"已断开");}
    private void closeSocket(){DatagramSocket s=socket;socket=null;if(s!=null)try{s.close();}catch(Throwable ignored){}}
    private void send(byte[] b){try{DatagramSocket s=socket;if(s!=null&&address!=null)s.send(new DatagramPacket(b,b.length,address,port));}catch(Throwable t){log("YSF 发送失败: "+t);}}
    private static byte[] packet(String call){String c=call.length()>10?call.substring(0,10):call;byte[] o=new byte[14];byte[] h="YSFP".getBytes(StandardCharsets.US_ASCII);System.arraycopy(h,0,o,0,4);byte[] b=c.getBytes(StandardCharsets.US_ASCII);int n=Math.min(10,b.length);System.arraycopy(b,0,o,4,n);for(int i=4+n;i<14;i++)o[i]=' ';return o;}
    private static byte[] pad10(String call){byte[] o=new byte[10];java.util.Arrays.fill(o,(byte)' ');byte[] b=normalize(call).getBytes(StandardCharsets.US_ASCII);System.arraycopy(b,0,o,0,Math.min(10,b.length));return o;}
    private static String normalize(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]","");}
    private static String trim(byte[] b){return new String(b,StandardCharsets.US_ASCII).trim();}
    private void state(int s,String m){Listener l=listener;if(l!=null)l.onState(s,m);}
    private void log(String s){Listener l=listener;if(l!=null)l.onLog(s);}
}
