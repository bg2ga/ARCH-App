package cn.arch.dmr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** FMO 4.0 直连客户端：自动证书注册、MQTT SAS 登录、在线宣告、PCM/RADPCM/OPUS 语音。 */
public final class FmoClient {
    public interface Listener {
        void onFmoState(int state, String text);
        void onFmoVoice(int uid, String callsign, short[] pcm);
        void onFmoOnline(List<User> users);
        void onFmoAppOnline(List<AppUser> users);
        void onHqVoice(int dmrId, String callsign, short[] pcm);
        void onFmoLog(String s);
        void onRooms(String json);
    }
    public static final class AppUser {
        public final int dmrId, fmoUid; public final String callsign; public final long lastSeen;
        AppUser(int d,int f,String c,long n){dmrId=d;fmoUid=f;callsign=c;lastSeen=n;}
    }
    public static final class User {
        public final int uid; public final String callsign; public final float txMhz, rxMhz; public final long lastSeen;
        User(int u,String c,float t,float r,long n){uid=u;callsign=c;txMhz=t;rxMhz=r;lastSeen=n;}
    }
    /** FMO broker 只允许 FMO/* 主题（实测：自建主题会被静默丢弃），所以统一走 FMO/QSO 房间主题 */
    public static final String TOPIC_PRESENCE = "FMO/QSO/ARCH-PRESENCE";
    public static final String TOPIC_CHANNEL_PREFIX = "FMO/QSO/ARCH-";
    public static final String TOPIC_ROOMS = "FMO/QSO/ARCH-ROOMS";
    private static final String[] TOPICS = {"FMO/RAW","FMO/TELE","FMO/USER_RIG","FMO/PROFILE","FMO/LATE/UID_V1/+","FMO/QSO/+"};
    private final File dir; private final String registerHost; private final int registerPort; private final String callsign; private final int dmrId;
    private final Listener cb; private final FmoRadPcm rad = new FmoRadPcm();
    /** FMO 上行 Opus（8kHz/40ms/320样本）。 */
    private final FmoOpus rawEnc = new FmoOpus(8000);
    private static final int OPUS_FRAME_SAMPLES = 320;
    private static final int OPUS_FRAMES_PER_PACKET = 6;
    private final FmoOpus hqEnc = new FmoOpus(48000), hqDec = new FmoOpus(48000);
    private FmoOpus rawOpus; private int rawOpusUid; private long rawOpusStream;
    private FmoOpus relayOpus; private int relayUid; private volatile long lastRelayRx;
    private final Map<Integer,User> online = new LinkedHashMap<>();
    private final Map<Integer,AppUser> appOnline = new LinkedHashMap<>();
    private volatile boolean run; private Socket sock; private InputStream in; private OutputStream out;
    private Thread worker, reader, heartbeat; private FmoProtocol.CertBundle cert; private int uid;
    private long streamBegin, lastTx; private volatile boolean tx, hqTx;
    private long txFrameCount = 0; private long txLastPublish = 0; private ByteArrayOutputStream txPcm = new ByteArrayOutputStream(); private int hqSeq;
    private int txSeq; private long session; private boolean opusWarned;
    /** 由界面塞进来、随在线宣告一起发给服务器的额外字段（工作模式 / 房间 等） */
    public volatile String presenceExtra = null;
    /** 自己发射用的 streamBegin，用来屏蔽服务器回环回来的自己的语音 */
    private volatile long ownStreamBegin = 0;
    private volatile long ownTxUntil = 0;
    /** 服务器平滑流的 streamBeginUTC / 代次，用于快速 PTT 时清掉上一段尾音 */
    private volatile long relayStreamId = 0;
    private volatile long relayGeneration = 0;

    public FmoClient(File dir,String registerHost,int registerPort,String callsign,int dmrId,Listener cb){
        this.dir=dir; this.registerHost=registerHost; this.registerPort=registerPort; this.callsign=callsign.toUpperCase(java.util.Locale.ROOT); this.dmrId=dmrId; this.cb=cb;
    }
    public int uid(){return uid;} public boolean isRunning(){return run;}
    public boolean isRelayActive(){return lastRelayRx>0 && System.currentTimeMillis()-lastRelayRx<1500;}
    public long relayGeneration(){return relayGeneration;}

    public void start(){ if(run)return; run=true; worker=new Thread(this::loop,"fmo-worker"); worker.start(); }
    public void stop(){ run=false; close(); if(worker!=null)worker.interrupt(); if(reader!=null)reader.interrupt(); if(heartbeat!=null)heartbeat.interrupt(); setState(0,"FMO 已断开"); }
    public void startHqTx(){ hqSeq=0; hqTx=true; }
    public void stopHqTx(){ hqTx=false; }
    public void sendHq(short[] pcm960){
        if(!hqTx||pcm960==null||pcm960.length!=960||!run)return;
        try {
            byte[] op=hqEnc.encode(pcm960); if(op==null)return;
            byte[] c=new byte[12]; byte[] cb=callsign.getBytes(StandardCharsets.US_ASCII); System.arraycopy(cb,0,c,0,Math.min(12,cb.length));
            ByteArrayOutputStream b=new ByteArrayOutputStream(); b.write(new byte[]{'A','4','8','K'}); b.write(1); write16(b,hqSeq++); b.write(dmrIdToBytes(dmrId)); b.write(c); b.write(op); publish(TOPIC_CHANNEL_PREFIX+"HRB48K",b.toByteArray());
        } catch(Exception e){ log("48k 编码失败 "+e.getMessage()); }
    }

    public void startTx(){ streamBegin=System.currentTimeMillis(); ownStreamBegin=streamBegin; ownTxUntil=System.currentTimeMillis()+1800L; txSeq=1; txPcm.reset(); rad.reset(); txFrameCount=0; txLastPublish=System.currentTimeMillis(); tx=true; }
    public void stopTx(){ try{ flushTx(); }catch(Throwable ignored){} tx=false; txPcm.reset(); ownTxUntil=System.currentTimeMillis()+1200L; }
    public void sendPcm(short[] pcm160){
        if(!tx||pcm160==null||pcm160.length<160)return;
        try {
            for(int i=0;i<160;i++){ int v=pcm160[i]; txPcm.write(v&255); txPcm.write((v>>>8)&255); }
            boolean full = txPcm.size()>=OPUS_FRAME_SAMPLES*2*OPUS_FRAMES_PER_PACKET;
            boolean timeout = txPcm.size()>=OPUS_FRAME_SAMPLES*2
                    && System.currentTimeMillis()-txLastPublish>=250L;
            if(full||timeout) flushPcm();
        } catch(Exception e){ log("FMO PCM 缓存错误 "+e); }
    }
    /** PTT 松开时把不足一包的余量也发出去。 */
    public void flushTx(){
        if(!tx)return;
        try{ if(txPcm.size()>=OPUS_FRAME_SAMPLES*2) flushPcm(); }catch(Exception e){ log("FMO 尾包发送失败: "+e); }
    }
    /** 照参考 APP：每 320 样本(40ms) 编一帧 Opus，最多 6 帧/包，compressMode=1。 */
    private void flushPcm() throws Exception {
        byte[] p=txPcm.toByteArray();
        final int frameBytes=OPUS_FRAME_SAMPLES*2;
        if(p.length<frameBytes)return;
        List<FmoProtocol.Frame> fs=new ArrayList<>();
        int off=0;
        while(p.length-off>=frameBytes && fs.size()<OPUS_FRAMES_PER_PACKET){
            short[] chunk=new short[OPUS_FRAME_SAMPLES];
            for(int i=0;i<OPUS_FRAME_SAMPLES;i++) chunk[i]=(short)((p[off+i*2]&255)|(p[off+i*2+1]<<8));
            off+=frameBytes;
            byte[] encoded=null;
            try { encoded=rawEnc.encode(chunk); } catch(Throwable ignored){}
            if(encoded!=null && encoded.length>0){
                fs.add(new FmoProtocol.Frame(FmoProtocol.CM_OPUS,encoded));
            } else {
                byte[] rp=rad.encode(chunk);
                fs.add((rp!=null && rp.length>=8)
                    ? new FmoProtocol.Frame(FmoProtocol.CM_RADPCM,rp)
                    : new FmoProtocol.Frame(FmoProtocol.CM_PCM,Arrays.copyOfRange(p,off-frameBytes,off)));
            }
        }
        if(fs.isEmpty())return;
        long packetTs = txFrameCount * 40L + streamBegin;   // Opus 帧长 40ms
        txFrameCount += fs.size();
        long nowMs=System.currentTimeMillis(); ownTxUntil=nowMs+1800L; txLastPublish=nowMs;
        publish("FMO/RAW",FmoProtocol.voicePacket(uid,callsign,streamBegin,packetTs,cert.server.uid,fs)); lastTx=nowMs;
        int rest=p.length-off;
        txPcm.reset(); if(rest>0)txPcm.write(p,off,rest);
    }

    private void loop(){
        while(run){
            try {
                connectOnce();
                while(run && sock!=null && !sock.isClosed()) Thread.sleep(1000);
            } catch(InterruptedException ie){
                break;                                  // 主动 stop() 中断，不报错
            } catch(Exception e){
                // 只有“本来还应该连着”的时候才算掉线；run=false 是主动停止
                if(run){ log("FMO 连接失败: "+e.getMessage()); setState(3,"FMO 连接失败"); }
            }
            close();
            if(!run) break;
            try { Thread.sleep(5000); } catch(InterruptedException ignored){ break; }
        }
    }
    private void connectOnce() throws Exception {
        setState(1,"FMO 注册/登录中…"); ensureCert();
        Socket s=new Socket(); s.connect(new InetSocketAddress(cert.server.mqttHost,cert.server.mqttPort),15000); s.setSoTimeout(1000);
        sock=s; in=new BufferedInputStream(s.getInputStream()); out=new BufferedOutputStream(s.getOutputStream());
        FmoProtocol.Credential cred=FmoProtocol.buildLogin(cert,cert.server.authHost,cert.server.authPort);
        sendConnect("ARCH-Android-"+uid,cred.username,cred.password); Packet ack=readPacket(); if(ack==null||ack.type!=2||ack.body.length<2||ack.body[1]!=0) throw new IOException("MQTT SAS 登录失败");
        for(String t:TOPICS) subscribe(t); publishTelemetry(); setState(2,"FMO 已连接"); log("FMO MQTT 已登录 "+cert.server.mqttHost+":"+cert.server.mqttPort+" auth="+cert.server.authHost+":"+cert.server.authPort+" uid="+uid);
        reader=new Thread(this::readLoop,"fmo-reader"); reader.start(); heartbeat=new Thread(this::heartbeat,"fmo-heartbeat"); heartbeat.start();
    }
    private void ensureCert() throws Exception {
        File uf=new File(dir,"fmo_user.json"), inf=new File(dir,"fmo_intermediate.json"), sf=new File(dir,"fmo_seed.txt");
        if(uf.exists()&&inf.exists()&&sf.exists()){
            JsonObject u=JsonParser.parseString(readFile(uf)).getAsJsonObject(); JsonObject i=JsonParser.parseString(readFile(inf)).getAsJsonObject();
            if(u.has("subject")&&u.getAsJsonObject("subject").has("callsign")&&u.getAsJsonObject("subject").get("callsign").getAsString().equalsIgnoreCase(callsign)){
                cert=new FmoProtocol.CertBundle(); cert.userCert=u; cert.intermediateCert=i; cert.seed=FmoProtocol.b64uDecode(readFile(sf).trim()); cert.server=FmoProtocol.probe(registerHost,registerPort); logFmoClockOffset(cert.server.timeOffsetMs); uid=cert.userCert.getAsJsonObject("subject").get("uid").getAsInt(); session=System.currentTimeMillis(); return;
            }
        }
        byte[] seed=FmoProtocol.generateSeed(); cert=FmoProtocol.register(registerHost,registerPort,callsign,seed); logFmoClockOffset(cert.server.timeOffsetMs); uid=cert.userCert.getAsJsonObject("subject").get("uid").getAsInt(); session=System.currentTimeMillis();
        writeFile(uf,cert.userCert.toString()); writeFile(inf,cert.intermediateCert.toString()); writeFile(sf,FmoProtocol.b64u(seed));
        log("FMO 自动注册成功 uid="+uid+" callsign="+callsign);
    }
    private void logFmoClockOffset(long offsetMs) {
        if (Math.abs(offsetMs) >= 5000L) {
            log("FMO 时钟偏差校正 " + (offsetMs / 1000L) + " 秒");
        }
    }

    private void readLoop(){ while(run){ try{Packet p=readPacket(); if(p==null){if(run)throw new IOException("FMO 连接关闭");continue;}
        if(p.type==3){ try{ onPublish(p.body); } catch(Throwable bad){ log("忽略一条无法解析的报文: " + bad); } } }
        catch(java.net.SocketTimeoutException e){continue;}
        catch(Exception e){ if(run){ log("FMO 接收断开: "+e.getMessage()); setState(3,"FMO 连接已断开，自动重连中…"); close(); } break; } } }
    private void heartbeat(){ while(run){ try{Thread.sleep(20000);}catch(InterruptedException e){return;} if(!run)return; try{ping(); publishTelemetry(); prune();}catch(Exception e){ if(run){ log("FMO 心跳失败: "+e.getMessage()); setState(3,"FMO 连接已断开，自动重连中…"); close(); } break; } } }
    private void onPublish(byte[] body)throws Exception { if(body.length<2)return; int tn=((body[0]&255)<<8)|(body[1]&255); if(tn+2>body.length)return; String topic=new String(body,2,tn,StandardCharsets.UTF_8); int off=2+tn; if(topic.equals("FMO/TELE")&&off<body.length){FmoProtocol.Telemetry t=FmoProtocol.parseTelemetry(Arrays.copyOfRange(body,off,body.length)); if(t!=null&&t.callsign.length()>0){ synchronized(online){ online.put(t.uid,new User(t.uid,t.callsign,t.txMhz,t.rxMhz,System.currentTimeMillis())); } emitOnline(); }}
        else if(topic.equals(TOPIC_PRESENCE)&&off<body.length){try{JsonObject j=JsonParser.parseString(new String(body,off,body.length-off,StandardCharsets.UTF_8)).getAsJsonObject(); int d=j.has("dmrId")?j.get("dmrId").getAsInt():0; String c=j.has("callsign")?j.get("callsign").getAsString():""; int f=j.has("fmoUid")?j.get("fmoUid").getAsInt():0; if(d>0&&c.length()>0){ synchronized(appOnline){ appOnline.put(d,new AppUser(d,f,c,System.currentTimeMillis())); } emitAppOnline(); }}catch(Exception ignored){}}
        else if(topic.equals(TOPIC_ROOMS)&&off<body.length){ try{ if(cb!=null) cb.onRooms(new String(body,off,body.length-off,StandardCharsets.UTF_8)); }catch(Throwable ignored){} }
        else if(topic.equals("FMO/QSO/ARCH-FMO8P")&&off<body.length){
            byte[] x=Arrays.copyOfRange(body,off,body.length);
            if(x.length>27&&x[0]=='A'&&x[1]=='8'&&x[2]=='P'&&x[3]=='2'){
                long now=System.currentTimeMillis();
                int seq=((x[5]&255)<<8)|(x[6]&255);
                int u=((x[7]&255)<<24)|((x[8]&255)<<16)|((x[9]&255)<<8)|(x[10]&255);
                long sid=(((long)x[11]&255)<<24)|(((long)x[12]&255)<<16)|(((long)x[13]&255)<<8)|((long)x[14]&255);
                lastRelayRx=now;
                // 服务器 A8P 流会把本机上行原样回传；既按 UID 过滤，也按本次 PTT 的 streamBegin 过滤。
                long ownSid=ownStreamBegin & 0xffffffffL;
                if(u==uid || (ownStreamBegin!=0 && sid==ownSid && now<ownTxUntil)) return;
                int z=15; while(z<27&&x[z]!=0)z++;
                String c=new String(x,15,z-15,StandardCharsets.US_ASCII).trim();
                synchronized(this){
                    if(seq==0||sid!=relayStreamId){ relayStreamId=sid; relayGeneration++; }
                }
                int n=(x.length-27)/2; short[] pcm=new short[n];
                for(int i=0;i<n;i++){int p=27+i*2;pcm[i]=(short)((x[p]&255)|(x[p+1]<<8));}
                if(cb!=null){ try{cb.onFmoVoice(u,c,pcm);}catch(Throwable t){log("FMO PCM relay callback error "+t);} }
            }
            else if(x.length>23&&x[0]=='A'&&x[1]=='8'&&x[2]=='P'&&x[3]=='1'){
                long now=System.currentTimeMillis();
                int seq=((x[5]&255)<<8)|(x[6]&255);
                int u=((x[7]&255)<<24)|((x[8]&255)<<16)|((x[9]&255)<<8)|(x[10]&255);
                lastRelayRx=now;
                if(u==uid) return;
                int z=11; while(z<23&&x[z]!=0)z++;
                String c=new String(x,11,z-11,StandardCharsets.US_ASCII).trim();
                synchronized(this){ if(seq==0) relayGeneration++; }
                int n=(x.length-23)/2; short[] pcm=new short[n];
                for(int i=0;i<n;i++){int p=23+i*2;pcm[i]=(short)((x[p]&255)|(x[p+1]<<8));}
                if(cb!=null){ try{cb.onFmoVoice(u,c,pcm);}catch(Throwable t){log("FMO PCM relay callback error "+t);} }
            }
        }
        else if(topic.equals("FMO/QSO/ARCH-FMO8K")&&off<body.length){
            byte[] x=Arrays.copyOfRange(body,off,body.length);
            if(x.length>23&&x[0]=='A'&&x[1]=='8'&&x[2]=='K'&&x[3]=='1'){
                int seq=((x[5]&255)<<8)|(x[6]&255);
                int u=((x[7]&255)<<24)|((x[8]&255)<<16)|((x[9]&255)<<8)|(x[10]&255);
                int z=11; while(z<23&&x[z]!=0)z++;
                String c=new String(x,11,z-11,StandardCharsets.US_ASCII).trim();
                if(relayOpus==null||relayUid!=u||seq==0){ if(relayOpus!=null)relayOpus.close(); relayOpus=new FmoOpus(8000); relayUid=u; }
                short[] pcm=relayOpus.decode(Arrays.copyOfRange(x,23,x.length),320);
                if(pcm!=null&&pcm.length>0){ lastRelayRx=System.currentTimeMillis(); if(cb!=null){ try{cb.onFmoVoice(u,c,pcm);}catch(Throwable t){log("FMO relay callback error "+t);} } }
            }
        }
        else if(topic.startsWith(TOPIC_CHANNEL_PREFIX)&&off<body.length){
            byte[] x=Arrays.copyOfRange(body,off,body.length);
            if(x.length>23&&x[0]=='A'&&x[1]=='4'&&x[2]=='8'&&x[3]=='K'){
                int d=((x[7]&255)<<24)|((x[8]&255)<<16)|((x[9]&255)<<8)|(x[10]&255);
                if(d==dmrId) return;        // 自己发的 48K 不播给自己
                int z=11; while(z<23&&x[z]!=0)z++;
                String c=new String(x,11,z-11,StandardCharsets.US_ASCII);
                short[] pcm=hqDec.decode(Arrays.copyOfRange(x,23,x.length),960);
                if(pcm!=null&&cb!=null){ try{ cb.onHqVoice(d,c,pcm); }catch(Throwable t){ log("48k 语音回调异常 "+t); } }
            }
        }
        else if(topic.equals("FMO/RAW")&&off<body.length){FmoProtocol.Voice v=FmoProtocol.parseVoice(Arrays.copyOfRange(body,off,body.length)); if(!v.crcOk)return;
        // 屏蔽自己的回环：服务器会把我们发上去的语音再广播回来，不能播给自己听
        if(v.uid==uid || (ownStreamBegin!=0 && v.streamBegin==ownStreamBegin)) { if(System.currentTimeMillis()<ownTxUntil) return; }
        if(System.currentTimeMillis()-lastRelayRx<2000)return;
        for(FmoProtocol.Frame f:v.frames){short[] pcm=null; if(f.mode==FmoProtocol.CM_PCM)pcm=bytesToPcm(f.data); else if(f.mode==FmoProtocol.CM_RADPCM)pcm=rad.decode(f.data); else if(f.mode==FmoProtocol.CM_OPUS){ if(rawOpus==null || rawOpusUid!=v.uid || rawOpusStream!=v.streamBegin){ if(rawOpus!=null)rawOpus.close(); rawOpus=new FmoOpus(8000); rawOpusUid=v.uid; rawOpusStream=v.streamBegin; } pcm=rawOpus.decode(f.data,320); if(pcm==null&&!opusWarned){opusWarned=true;log("FMO OPUS 解码失败");} } if(pcm!=null&&cb!=null){ try{ cb.onFmoVoice(v.uid,v.callsign,pcm); }catch(Throwable t){ log("FMO 语音回调异常 "+t); } }} }
    }
    private void publishTelemetry()throws Exception { if(!run)return; byte[] t=FmoProtocol.telemetry(uid,callsign,438.500f,438.500f,session); publish("FMO/TELE",t); synchronized(online){ online.put(uid,new User(uid,callsign,438.5f,438.5f,System.currentTimeMillis())); } emitOnline(); publishPresence(); }
    private void publishPresence()throws Exception {
        JsonObject j=new JsonObject();
        j.addProperty("callsign",callsign); j.addProperty("dmrId",dmrId);
        j.addProperty("fmoUid",uid); j.addProperty("time",System.currentTimeMillis()/1000L);
        String extra=presenceExtra;
        if(extra!=null){ try{ JsonObject e=JsonParser.parseString(extra).getAsJsonObject();
            for(java.util.Map.Entry<String,com.google.gson.JsonElement> en: e.entrySet()) j.add(en.getKey(),en.getValue());
        }catch(Throwable ignored){} }
        publish(TOPIC_PRESENCE,j.toString().getBytes(StandardCharsets.UTF_8));
        synchronized(appOnline){ appOnline.put(dmrId,new AppUser(dmrId,uid,callsign,System.currentTimeMillis())); }
        emitAppOnline();
    }
    private void prune(){ long now=System.currentTimeMillis();
        synchronized(online){ Iterator<Map.Entry<Integer,User>> it=online.entrySet().iterator(); while(it.hasNext()){if(now-it.next().getValue().lastSeen>90000)it.remove();} }
        emitOnline();
        synchronized(appOnline){ Iterator<Map.Entry<Integer,AppUser>> ia=appOnline.entrySet().iterator(); while(ia.hasNext()){if(now-ia.next().getValue().lastSeen>90000)ia.remove();} }
        emitAppOnline(); }
    private void emitOnline(){ if(cb==null)return; List<User> x; synchronized(online){ x=new ArrayList<>(online.values()); } Collections.sort(x,new Comparator<User>(){public int compare(User a,User b){return a.callsign.compareToIgnoreCase(b.callsign);}}); cb.onFmoOnline(x); }
    private void emitAppOnline(){ if(cb==null)return; List<AppUser> x; synchronized(appOnline){ x=new ArrayList<>(appOnline.values()); } Collections.sort(x,new Comparator<AppUser>(){public int compare(AppUser a,AppUser b){return a.callsign.compareToIgnoreCase(b.callsign);}}); cb.onFmoAppOnline(x); }
    private void setState(int s,String t){if(cb!=null)cb.onFmoState(s,t);} private void log(String s){if(cb!=null)cb.onFmoLog(s);}
    private void close(){if(rawOpus!=null){try{rawOpus.close();}catch(Throwable ignored){} rawOpus=null;} if(relayOpus!=null){try{relayOpus.close();}catch(Throwable ignored){} relayOpus=null;} try{if(sock!=null)sock.close();}catch(Exception ignored){} sock=null;in=null;out=null;}
    private void sendConnect(String cid,String user,String pass)throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); utf(b,"MQTT");b.write(4);b.write(0xC2);b.write(0);b.write(30);utf(b,cid);utf(b,user);utf(b,pass);writePacket(1,b.toByteArray()); }
    private void subscribe(String topic)throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(0);b.write(1);utf(b,topic);b.write(0);writePacket(8,b.toByteArray()); }
    private void publish(String topic,byte[] payload)throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream();utf(b,topic);b.write(payload);writePacket(3,b.toByteArray()); }
    private void ping()throws IOException {writePacket(12,new byte[0]);}
    private void writePacket(int type,byte[] body)throws IOException {synchronized(FmoClient.class){out.write(type<<4);remLen(out,body.length);out.write(body);out.flush();}}
    private Packet readPacket()throws IOException {int h=in.read();if(h<0)return null;int len=readLen();byte[] b=readN(len);return new Packet((h>>4)&15,b);}
    private static void remLen(OutputStream o,int n)throws IOException {do{int d=n%128;n/=128;if(n>0)d|=128;o.write(d);}while(n>0);}
    private int readLen()throws IOException {int m=1,v=0,b;do{b=in.read();if(b<0)throw new EOFException();v+=(b&127)*m;m*=128;}while((b&128)!=0);return v;}
    private byte[] readN(int n)throws IOException {byte[] b=new byte[n];int o=0;while(o<n){int k=in.read(b,o,n-o);if(k<0)throw new EOFException();o+=k;}return b;}
    private static void utf(OutputStream o,String s)throws IOException {byte[] b=s.getBytes(StandardCharsets.UTF_8);o.write(b.length>>8);o.write(b.length&255);o.write(b);}
    private static byte[] dmrIdToBytes(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
    private static void write16(OutputStream o,int v)throws IOException{o.write((v>>>8)&255);o.write(v&255);}
    private static short[] bytesToPcm(byte[] b){short[] s=new short[b.length/2];for(int i=0;i<s.length;i++)s[i]=(short)((b[i*2]&255)|(b[i*2+1]<<8));return s;}
    private static String readFile(File f)throws IOException {ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream i=new FileInputStream(f)){byte[] b=new byte[4096];int n;while((n=i.read(b))>0)o.write(b,0,n);}return new String(o.toByteArray(),StandardCharsets.UTF_8);} private static void writeFile(File f,String s)throws IOException {try(OutputStream o=new FileOutputStream(f)){o.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static final class Packet{final int type;final byte[] body;Packet(int t,byte[] b){type=t;body=b;}}
}
