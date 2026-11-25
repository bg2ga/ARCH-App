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

/** Direct NXDN reflector client, ported from the current G4KLX/DVReflectors NXDN framing. */
public final class NxdnClient {
    public interface Listener {
        void onState(int state, String message);
        void onSignal(int srcId, int dstId);
        void onVoice(int srcId, int dstId, short[] pcm160);
        void onEnd(int srcId, int dstId, int seconds);
        void onLog(String message);
    }

    private static final int STATE_CONNECTING=0, STATE_CONNECTED=1, STATE_DISCONNECTED=2, STATE_ERROR=3;
    private static final int RX_PREBUFFER_FRAMES=4;
    private static final long RX_IDLE_MS=1200L;
    private static final int RFCT_RDCH=2;
    private static final int USC_SACCH_NS=0, USC_SACCH_SS=2;
    private static final int STEAL_FACCH=0, STEAL_NONE=3;
    private static final int DIR_INBOUND=0;
    private static final int MT_VCALL=1, MT_TX_REL=8;

    private final Object txLock=new Object();
    private final Object rxLock=new Object();
    private final Object codecLock=new Object();
    private final ArrayDeque<short[]> rxPcmQueue=new ArrayDeque<>();
    private final ArrayDeque<Byte> rxCodeQueue=new ArrayDeque<>();
    private final NxdnCodec codec=new NxdnCodec();
    private volatile Listener listener;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;
    private volatile int port=41400;
    private volatile String callsign="";
    private volatile int srcId=0,groupId=0;
    private volatile boolean running,connected,tx,rxEnded;
    private volatile Thread rxThread,pingThread,playbackThread;
    private volatile long rxStart=0,lastRx=0;
    private volatile int rxSrc=0,rxDst=0;
    private final byte[] txCode=new byte[28];
    private int txCodeLen=0,txCnt=0;
    private int lich=0;
    private final byte[] sacch=new byte[5];
    private final byte[] layer3=new byte[22];

    public void setListener(Listener l){listener=l;}
    public boolean isRunning(){return running&&socket!=null;}
    public boolean isConnected(){return connected&&isRunning();}
    public boolean isTx(){return tx;}
    public boolean codecOk(){return codec.ok();}

    public boolean connect(String host,int portNo,String call,int sourceId,int group){
        stop();
        port=portNo;callsign=normalize(call);srcId=sourceId&0xFFFF;groupId=group&0xFFFF;
        if(callsign.isEmpty()){state(STATE_ERROR,"NXDN 呼号为空");return false;}
        if(!codec.ok()){state(STATE_ERROR,"NXDN 声码器加载失败");return false;}
        try{
            address=InetAddress.getByName(host);
            DatagramSocket s=new DatagramSocket();s.setSoTimeout(500);socket=s;
            state(STATE_CONNECTING,"正在连接 NXDN "+host+":"+portNo);
            running=true;
            send(pingPacket(false));
            rxThread=new Thread(this::rxLoop,"nxdn-rx");
            pingThread=new Thread(this::pingLoop,"nxdn-ping");
            playbackThread=new Thread(this::playbackLoop,"nxdn-playback");
            rxThread.start();pingThread.start();playbackThread.start();
            return true;
        }catch(Throwable t){closeSocket();state(STATE_ERROR,String.valueOf(t.getMessage()==null?t:t.getMessage()));return false;}
    }

    public void startTx(){
        if(!isConnected()||tx)return;
        synchronized(txLock){txCodeLen=0;txCnt=0;}
        synchronized(rxLock){rxCodeQueue.clear();rxPcmQueue.clear();}
        synchronized(codecLock){codec.reset();}
        tx=true;rxEnded=false;
        log("NXDN 开始发射");
    }

    public void stopTx(){
        boolean was=tx;tx=false;
        if(was&&isRunning())send(buildFrame(true));
        synchronized(txLock){txCodeLen=0;txCnt=0;}
        log("NXDN 停止发射");
    }

    public void sendPcm(short[] pcm160){
        if(!tx||!isRunning()||pcm160==null||pcm160.length!=160)return;
        byte[] ambe=new byte[7];
        synchronized(codecLock){if(!codec.encode(pcm160,ambe))return;}
        ambe[6]=(byte)(ambe[6]&0x80);
        byte[] out=null;
        synchronized(txLock){
            System.arraycopy(ambe,0,txCode,txCodeLen,7);txCodeLen+=7;
            if(txCodeLen>=28){out=buildFrame(false);txCodeLen=0;}
        }
        if(out!=null)send(out);
    }

    private void pingLoop(){
        while(running){try{Thread.sleep(1000L);}catch(InterruptedException e){return;}if(running)send(pingPacket(false));}
    }

    private void rxLoop(){
        byte[] buf=new byte[512];
        while(running){
            DatagramSocket s=socket;if(s==null)return;
            try{
                DatagramPacket p=new DatagramPacket(buf,buf.length);s.receive(p);
                if(p.getLength()>0)handleRx(buf,p.getLength());
            }catch(SocketTimeoutException ignored){
            }catch(Throwable t){if(!running||s!=socket)return;log("NXDN 接收重试: "+t);try{Thread.sleep(20L);}catch(InterruptedException e){return;}}
        }
    }

    private void handleRx(byte[] b,int len){
        if(len==17){
            if(!connected){connected=true;state(STATE_CONNECTED,"已连接 NXDN");log("NXDN 直连成功");}
            return;
        }
        if(len!=43)return;
        if(b[0]!='N'||b[1]!='X'||b[2]!='D'||b[3]!='N'||b[4]!='D')return;
        rxSrc=u16(b,5);rxDst=u16(b,7);
        if((b[9]&0x08)==0){Listener sl=listener;if(sl!=null)try{sl.onSignal(rxSrc,rxDst);}catch(Throwable t){log("NXDN 信号处理异常: "+t);}}
        int fct=getLichFct(b[10]&0xFF);
        if(fct==USC_SACCH_NS){
            if((b[9]&0x08)!=0){rxEnded=true;lastRx=System.currentTimeMillis();return;}
            if(rxStart==0){synchronized(codecLock){codec.reset();}rxStart=System.currentTimeMillis();rxEnded=false;}
        }else{
            if(rxStart==0){synchronized(codecLock){codec.reset();}rxStart=System.currentTimeMillis();rxEnded=false;}
        }
        lastRx=System.currentTimeMillis();
        synchronized(rxLock){
            addFrame(Arrays.copyOfRange(b,15,22));
            byte[] t=new byte[7];
            for(int i=0;i<6;i++)t[i]=(byte)((b[21+i]<<1)|((b[22+i]>>7)&1));
            t[6]=(byte)(b[27]<<1);
            addFrame(t);
            addFrame(Arrays.copyOfRange(b,29,36));
            byte[] u=new byte[7];
            for(int i=0;i<6;i++)u[i]=(byte)((b[35+i]<<1)|((b[36+i]>>7)&1));
            u[6]=(byte)(b[41]<<1);
            addFrame(u);
        }
    }

    private void addFrame(byte[] f){for(byte x:f)rxCodeQueue.addLast(x);while(rxCodeQueue.size()>280)rxCodeQueue.removeFirst();}

    private void playbackLoop(){
        long next=System.nanoTime();
        while(running){
            next+=20_000_000L;long d=next-System.nanoTime();if(d>0)LockSupport.parkNanos(d);else next=System.nanoTime();
            if(tx)continue;
            short[] pcm=null;boolean end=false;long now=System.currentTimeMillis();
            synchronized(rxLock){
                if(rxCodeQueue.size()>=7&&(rxCodeQueue.size()>=RX_PREBUFFER_FRAMES*7||rxEnded||lastRx==0||now-lastRx>300L))pcm=pullFrame();
                else if(rxStart>0&&rxCodeQueue.size()<7&&(rxEnded||lastRx==0||now-lastRx>RX_IDLE_MS))end=true;
            }
            if(pcm!=null){Listener l=listener;if(l!=null)try{l.onVoice(rxSrc,rxDst,pcm);}catch(Throwable t){log("NXDN 接收处理异常: "+t);}}
            if(end)endRx();
        }
    }

    private short[] pullFrame(){
        if(rxCodeQueue.size()<7)return null;
        byte[] f=new byte[7];for(int i=0;i<7;i++)f[i]=rxCodeQueue.removeFirst();
        short[] pcm=new short[160];synchronized(codecLock){return codec.decode(f,pcm)?pcm:null;}
    }

    private synchronized void endRx(){
        if(rxStart==0)return;
        int sec=(int)((System.currentTimeMillis()-rxStart)/1000L);if(sec==0)sec=1;
        Listener l=listener;if(l!=null)try{l.onEnd(rxSrc,rxDst,sec);}catch(Throwable t){log("NXDN 结束处理异常: "+t);}
        synchronized(rxLock){rxCodeQueue.clear();rxPcmQueue.clear();}
        rxStart=0;lastRx=0;rxEnded=false;rxSrc=0;rxDst=0;
    }

    public void stop(){
        boolean was=connected;running=false;connected=false;tx=false;
        if(was&&socket!=null)send(pingPacket(true));
        Thread a=rxThread,b=pingThread,c=playbackThread;rxThread=pingThread=playbackThread=null;
        if(a!=null)a.interrupt();if(b!=null)b.interrupt();if(c!=null)c.interrupt();closeSocket();
        synchronized(rxLock){rxCodeQueue.clear();rxPcmQueue.clear();}
        state(STATE_DISCONNECTED,"已断开");
    }

    private byte[] pingPacket(boolean disconnect){
        byte[] o=new byte[17];byte[] h=(disconnect?"NXDNU":"NXDNP").getBytes(StandardCharsets.US_ASCII);System.arraycopy(h,0,o,0,5);
        byte[] c=callsign.getBytes(StandardCharsets.US_ASCII);int n=Math.min(10,c.length);System.arraycopy(c,0,o,5,n);for(int i=5+n;i<15;i++)o[i]=' ';
        o[15]=(byte)(groupId>>8);o[16]=(byte)groupId;return o;
    }

    private byte[] buildFrame(boolean eot){
        byte[] d=new byte[43];byte[] h="NXDND".getBytes(StandardCharsets.US_ASCII);System.arraycopy(h,0,d,0,5);
        d[5]=(byte)(srcId>>8);d[6]=(byte)srcId;d[7]=(byte)(groupId>>8);d[8]=(byte)groupId;d[9]=0x01;
        if(txCnt==0||eot)encodeHeader(d,eot);else encodeData(d);
        int l=d[10]&0xFF;
        if(l==0x81||l==0x83){if(d[15]==0x01)d[9]|=0x04;if(d[15]==0x08)d[9]|=0x08;}
        else if((l&0xF0)==0x90){d[9]|=0x02;if(l==0x90||l==0x92||l==0x9C||l==0x9E){if(d[12]==0x09)d[9]|=0x04;if(d[12]==0x08)d[9]|=0x08;}}
        if(eot)txCnt=0;else txCnt++;
        return d;
    }

    private void encodeHeader(byte[] d,boolean eot){
        Arrays.fill(sacch,(byte)0);Arrays.fill(layer3,(byte)0);lich=0;
        setLich(RFCT_RDCH,USC_SACCH_NS,STEAL_FACCH,DIR_INBOUND);d[10]=(byte)getLich();
        setSacchRan(1);setSacchStruct(0);setSacchData(new byte[]{0x10,0x00,0x00});getSacch(d,11);
        layer3[0]=(byte)((layer3[0]&0xC0)|((eot?MT_TX_REL:MT_VCALL)&0x3F));
        setSrcDst();layer3[2]|=0x20;
        System.arraycopy(layer3,0,d,15,14);System.arraycopy(layer3,0,d,29,14);
    }

    private void encodeData(byte[] d){
        Arrays.fill(sacch,(byte)0);Arrays.fill(layer3,(byte)0);lich=0;
        setLich(RFCT_RDCH,USC_SACCH_SS,STEAL_NONE,DIR_INBOUND);d[10]=(byte)getLich();
        setSacchRan(1);
        layer3[0]=(byte)((layer3[0]&0xC0)|(MT_VCALL&0x3F));
        setSrcDst();layer3[2]|=0x20;
        byte[] msg=new byte[3];
        switch(txCnt%4){
            case 0:setSacchStruct(3);layer3Encode(msg,18,0);break;
            case 1:setSacchStruct(2);layer3Encode(msg,18,18);break;
            case 2:setSacchStruct(1);layer3Encode(msg,18,36);break;
            default:setSacchStruct(0);layer3Encode(msg,18,54);break;
        }
        setSacchData(msg);getSacch(d,11);
        System.arraycopy(txCode,0,d,15,7);
        for(int i=0;i<7;i++){d[21+i]|=(byte)(txCode[7+i]>>1);d[22+i]=(byte)((txCode[7+i]&1)<<7);}
        d[28]|=(byte)(txCode[13]>>2);
        System.arraycopy(txCode,14,d,29,7);
        for(int i=0;i<7;i++){d[35+i]|=(byte)(txCode[21+i]>>1);d[36+i]=(byte)((txCode[21+i]&1)<<7);}
        d[41]|=(byte)(txCode[27]>>2);
    }

    private void setSrcDst(){layer3[3]=(byte)(srcId>>8);layer3[4]=(byte)srcId;layer3[5]=(byte)(groupId>>8);layer3[6]=(byte)groupId;}

    private void setLich(int rfct,int fct,int option,int dir){
        lich=(lich&0x3F)|((rfct<<6)&0xC0);lich=(lich&0xCF)|((fct<<4)&0x30);
        lich=(lich&0xF3)|((option<<2)&0x0C);lich=(lich&0xFD)|((dir<<1)&0x02);
    }

    private int getLich(){
        boolean parity=(lich&0xF0)==0x80||(lich&0xF0)==0xB0;
        if(parity)lich|=0x01;else lich&=0xFE;return lich&0xFF;
    }

    private static int getLichFct(int v){return (v>>4)&0x03;}
    private void setSacchRan(int ran){sacch[0]=(byte)((sacch[0]&0xC0)|(ran&0x3F));}
    private void setSacchStruct(int s){sacch[0]=(byte)((sacch[0]&0x3F)|((s&0x03)<<6));}
    private void setSacchData(byte[] d){for(int i=0;i<18;i++)writeBit(sacch,8+i,readBit(d,i));}

    private void getSacch(byte[] d,int off){System.arraycopy(sacch,0,d,off,4);encodeCrc6(d,off,26);}

    private void encodeCrc6(byte[] d,int off,int len){
        int crc=0x3F;
        for(int i=0;i<len;i++){boolean bit1=readBit(d,off+i);boolean bit2=(crc&0x20)!=0;crc=(crc<<1)&0xFF;if(bit1^bit2)crc^=0x27;}
        crc&=0x3F;
        for(int i=2;i<8;i++)writeBit(d,off*8+len+(i-2),(crc&(0x80>>i))!=0);
    }

    private void layer3Encode(byte[] d,int len,int offset){for(int i=0;i<len;i++)writeBit(d,i,readBit(layer3,offset+i));}
    private static boolean readBit(byte[] a,int i){return (a[i>>3]&(0x80>>(i&7)))!=0;}
    private static void writeBit(byte[] a,int i,boolean b){int m=0x80>>(i&7);if(b)a[i>>3]|=(byte)m;else a[i>>3]&=(byte)~m;}

    private void closeSocket(){DatagramSocket s=socket;socket=null;if(s!=null)try{s.close();}catch(Throwable ignored){}}
    private void send(byte[] b){try{DatagramSocket s=socket;if(s!=null&&address!=null)s.send(new DatagramPacket(b,b.length,address,port));}catch(Throwable t){log("NXDN 发送失败: "+t);}}
    private static int u16(byte[] b,int off){return ((b[off]&0xFF)<<8)|(b[off+1]&0xFF);}
    private static String normalize(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9/]","");}
    private void state(int s,String m){try{Listener l=listener;if(l!=null)l.onState(s,m);}catch(Throwable ignored){}}
    private void log(String s){try{Listener l=listener;if(l!=null)l.onLog(s);}catch(Throwable ignored){}}
}
