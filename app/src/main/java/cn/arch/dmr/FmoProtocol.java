package cn.arch.dmr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** FMO 4.0 SAS 注册、证书、登录和语音包协议。纯 Java，可在手机和服务端测试。 */
public final class FmoProtocol {
    public static final String PROTOCOL = "FMO";
    public static final int VERSION = 4;
    public static final int CM_PCM = 0, CM_OPUS = 1, CM_RADPCM = 2;
    /** FMO 官方值必须是 0x0000；0x1000~0x2FFF 为软件/试验区，
     *  真实 FMO 设备对软件区厂商的流『提示接收但不放音』（实测）。 */
    public static final int DEFAULT_VENDOR = 0x0000;
    public static final byte[] CODEC_TAG = {0x3d, 0x14, 0x00, (byte)0xe0, 0x3d};
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final String OVERSEAS_FMO_BASE = "https://arch-overseas.pages.dev/fmo";

    private static String fmoBase(String host, int port) {
        return BuildConfig.OVERSEAS_EDITION ? OVERSEAS_FMO_BASE : ("http://" + host + ":" + port + "/fmo");
    }

    private FmoProtocol() {}

    public static final class ServerInfo {
        public int uid; public String callsign, fingerprint, mqttHost; public int mqttPort;
        public String authHost; public int authPort;
        public long timeOffsetMs;
    }
    public static final class CertBundle {
        public JsonObject userCert, intermediateCert; public byte[] seed; public ServerInfo server;
    }
    public static final class Credential { public String username, password; }
    public static final class Frame { public int mode; public byte[] data; public Frame(int m, byte[] d){mode=m;data=d;} }
    public static final class Voice {
        public int uid, srvUid; public String callsign; public long streamBegin, timestamp;
        public List<Frame> frames = new ArrayList<>(); public boolean crcOk;
    }
    public static final class Telemetry {
        public int uid; public String callsign; public long time; public float txMhz, rxMhz;
    }

    public static byte[] generateSeed() throws Exception {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    public static byte[] publicKey(byte[] seed) throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, spec));
        return key.getA().toByteArray();
    }

    public static byte[] sign(byte[] seed, byte[] msg) throws Exception {
        EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, spec));
        EdDSAEngine e = new EdDSAEngine();
        e.initSign(key);
        return e.signOneShot(msg);
    }

    public static ServerInfo probe(String host, int port) throws IOException {
        long requestStarted = System.currentTimeMillis();
        JsonResponse response = jsonGetResponse(fmoBase(host, port) + "/ping");
        long requestFinished = System.currentTimeMillis();
        JsonObject root = response.json;
        if (!root.has("ok") || !root.get("ok").getAsBoolean() || !root.has("server"))
            throw new IOException("FMO 注册服务无响应");
        JsonObject s = root.getAsJsonObject("server");
        ServerInfo out = new ServerInfo();
        out.uid = s.get("uid").getAsInt();
        out.callsign = s.get("callsign").getAsString();
        out.fingerprint = s.get("certFingerprint").getAsString();
        out.mqttHost = s.has("mqttHost") ? s.get("mqttHost").getAsString() : host;
        out.mqttPort = s.has("mqttPort") ? s.get("mqttPort").getAsInt() : 1883;
        out.authHost = s.has("authHost") ? s.get("authHost").getAsString() : out.mqttHost;
        out.authPort = s.has("authPort") ? s.get("authPort").getAsInt() : out.mqttPort;
        if (response.dateMs > 0L) {
            long localMid = requestStarted + (requestFinished - requestStarted) / 2L;
            long offset = response.dateMs - localMid;
            // Ignore clearly bogus proxy/cache dates; real device drift is normally seconds/minutes.
            if (Math.abs(offset) <= 7L * 24L * 60L * 60L * 1000L) out.timeOffsetMs = offset;
        }
        return out;
    }

    public static CertBundle register(String host, int port, String callsign, byte[] seed) throws Exception {
        ServerInfo server = probe(host, port);
        byte[] pub = publicKey(seed);
        JsonObject body = new JsonObject();
        body.addProperty("callsign", callsign.toUpperCase(java.util.Locale.ROOT));
        body.addProperty("uid", 0);
        body.addProperty("pubkey", b64u(pub));
        JsonObject res = jsonPost(fmoBase(host, port) + "/register", body);
        if (!res.has("ok") || !res.get("ok").getAsBoolean())
            throw new IOException("FMO 注册被拒绝: " + (res.has("error") ? res.get("error").getAsString() : "unknown"));
        CertBundle out = new CertBundle();
        out.userCert = res.getAsJsonObject("userCert");
        out.intermediateCert = res.getAsJsonObject("intermediateCert");
        out.seed = seed; out.server = server;
        return out;
    }

    public static Credential buildLogin(CertBundle c, String host, int port) throws Exception {
        ServerInfo s = c.server;
        byte[] userFp = userFingerprint(c.userCert);
        byte[] serverFp = b64uDecode(s.fingerprint);
        long ts = (System.currentTimeMillis() + s.timeOffsetMs) / 1000L;
        byte[] tbs = cborArray(12,
                "FMO", VERSION, "serverAuthorizerReqHttp",
                s.uid, s.callsign.toUpperCase(java.util.Locale.ROOT), s.uid, "user",
                host, port, serverFp, ts, userFp);
        String sig = b64u(sign(c.seed, tbs));
        JsonObject payload = new JsonObject();
        JsonObject pkg = new JsonObject();
        pkg.add("intermediateCert", c.intermediateCert);
        pkg.add("userCert", c.userCert);
        payload.add("certPackage", pkg);
        payload.addProperty("targetCallsign", s.callsign);
        payload.addProperty("targetUID", s.uid);
        payload.addProperty("role", "user");
        payload.addProperty("targetUrl", host);
        payload.addProperty("targetPort", port);
        payload.addProperty("serverFingerprint", s.fingerprint);
        payload.addProperty("timestamp", ts);
        JsonObject proof = new JsonObject(); proof.addProperty("signature", sig); payload.add("proof", proof);
        Credential out = new Credential();
        out.username = c.userCert.getAsJsonObject("subject").get("callsign").getAsString();
        out.password = b64u(payload.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    public static byte[] userFingerprint(JsonObject cert) throws Exception {
        JsonObject sub = cert.getAsJsonObject("subject");
        int issuerSn = cert.get("issuerSn").getAsInt();
        String callsign = sub.get("callsign").getAsString().toUpperCase(java.util.Locale.ROOT);
        int uid = sub.get("uid").getAsInt();
        byte[] pub = b64uDecode(sub.get("publicKey").getAsString());
        long iat = cert.get("iat").getAsLong(), exp = cert.get("exp").getAsLong();
        return sha256(cborArray(9, "FMO", VERSION, "userCert", issuerSn, callsign, uid, pub, iat, exp));
    }

    public static byte[] voicePacket(int uid, String callsign, long streamBegin, long timestamp,
                                     int srvUid, List<Frame> frames) throws Exception {
        ByteArrayOutputStream fa = new ByteArrayOutputStream();
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            int cvfLen = 8 + f.data.length;
            int tfLen = 8 + cvfLen;
            writeLE16(fa, 1 + i); writeLE16(fa, tfLen); writeLE32(fa, 0);
            fa.write(f.mode); writeLE16(fa, cvfLen); fa.write(CODEC_TAG); fa.write(f.data);
        }
        byte[] area = fa.toByteArray();
        CRC32 crc = new CRC32(); crc.update(area);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLE16(out, 1); writeLE32(out, DEFAULT_VENDOR); writeLE32(out, uid);
        byte[] cs = callsign.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] c12 = new byte[12]; System.arraycopy(cs, 0, c12, 0, Math.min(12, cs.length)); out.write(c12);
        writeLE32(out, streamBegin); writeLE32(out, timestamp); writeLE32(out, 64 + area.length);
        writeLE16(out, frames.size()); writeLE32(out, crc.getValue()); out.write(0); writeLE32(out, srvUid);
        out.write(new byte[19]); out.write(area);
        return out.toByteArray();
    }

    public static Voice parseVoice(byte[] d) {
        Voice v = new Voice();
        if (d.length < 64) return v;
        v.uid = le32(d, 6); v.srvUid = le32(d, 41); v.streamBegin = le32(d, 22); v.timestamp = le32(d, 26);
        int z = 10; while (z < 22 && d[z] != 0) z++;
        v.callsign = new String(d, 10, z - 10, StandardCharsets.US_ASCII);
        int total = Math.min(d.length, le32(d, 30)); int off = 64;
        while (off + 8 <= total) {
            int len = (d[off + 2] & 255) | ((d[off + 3] & 255) << 8);
            if (len < 16 || off + len > total) break;
            int mode = d[off + 8] & 255;
            int clen = (d[off + 9] & 255) | ((d[off + 10] & 255) << 8);
            if (clen > 8 && clen <= len - 8 + 8) v.frames.add(new Frame(mode, Arrays.copyOfRange(d, off + 16, off + 8 + clen)));
            off += len;
        }
        CRC32 crc = new CRC32(); crc.update(d, 64, Math.max(0, total - 64));
        v.crcOk = crc.getValue() == le32(d, 36);
        return v;
    }

    public static byte[] telemetry(int uid, String callsign, float tx, float rx, long session) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(2); writeLE32(o, session); writeLE32(o, uid);
        byte[] c = callsign.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII); byte[] c12 = new byte[12];
        System.arraycopy(c, 0, c12, 0, Math.min(12, c.length)); o.write(c12);
        writeLE32(o, System.currentTimeMillis() / 1000L); writeLE32(o, Float.floatToIntBits(tx)); writeLE32(o, Float.floatToIntBits(rx));
        return o.toByteArray();
    }

    public static Telemetry parseTelemetry(byte[] d) {
        if (d.length < 33 || (d[0] & 255) != 2) return null;
        Telemetry t = new Telemetry(); t.uid = le32(d, 5); int z = 9; while (z < 21 && d[z] != 0) z++;
        t.callsign = new String(d, 9, z - 9, StandardCharsets.US_ASCII); t.time = le32(d, 21);
        t.txMhz = Float.intBitsToFloat(le32(d, 25)); t.rxMhz = Float.intBitsToFloat(le32(d, 29)); return t;
    }

    private static final class JsonResponse {
        final JsonObject json; final long dateMs;
        JsonResponse(JsonObject json, long dateMs) { this.json = json; this.dateMs = dateMs; }
    }

    private static JsonResponse jsonGetResponse(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setUseCaches(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        long dateMs = c.getHeaderFieldDate("Date", 0L);
        try (InputStream in = c.getInputStream()) {
            return new JsonResponse(JsonParser.parseString(read(in)).getAsJsonObject(), dateMs);
        }
    }
    private static JsonObject jsonPost(String u, JsonObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setDoOutput(true); c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = c.getInputStream()) { return JsonParser.parseString(read(in)).getAsJsonObject(); }
    }
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }
    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static void writeLE16(OutputStream o, int v) throws IOException { o.write(v & 255); o.write((v >>> 8) & 255); }
    private static void writeLE32(OutputStream o, long v) throws IOException { o.write((int)(v & 255)); o.write((int)((v>>>8)&255)); o.write((int)((v>>>16)&255)); o.write((int)((v>>>24)&255)); }
    private static int le32(byte[] b, int p) { return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24); }

    private static byte[] cborArray(int n, Object... vals) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); cborHead(o, 4, n);
        for (Object v : vals) {
            if (v instanceof String) { byte[] b=((String)v).getBytes(StandardCharsets.UTF_8); cborHead(o,3,b.length); o.write(b); }
            else if (v instanceof byte[]) { byte[] b=(byte[])v; cborHead(o,2,b.length); o.write(b); }
            else if (v instanceof Integer) cborHead(o,0,(Integer)v);
            else if (v instanceof Long) cborHead(o,0,(Long)v);
            else throw new IllegalArgumentException("cbor type");
        }
        return o.toByteArray();
    }
    private static void cborHead(ByteArrayOutputStream o, int major, long n) {
        int m=major<<5;
        if(n<24){o.write(m|(int)n);return;} if(n<=255){o.write(m|24);o.write((int)n);return;}
        if(n<=65535){o.write(m|25);o.write((int)(n>>8));o.write((int)n);return;}
        o.write(m|26); o.write((int)(n>>24));o.write((int)(n>>16));o.write((int)(n>>8));o.write((int)n);
    }
    public static String b64u(byte[] b) {
        StringBuilder s=new StringBuilder(); for(int i=0;i<b.length;i+=3){int a=b[i]&255; int c=i+1<b.length?b[i+1]&255:0; int d=i+2<b.length?b[i+2]&255:0;
            s.append(B64[a>>2]).append(B64[((a&3)<<4)|(c>>4)]); if(i+1<b.length)s.append(B64[((c&15)<<2)|(d>>6)]); if(i+2<b.length)s.append(B64[d&63]);}
        return s.toString();
    }
    public static byte[] b64uDecode(String s) {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); int val=0,bits=0;
        for(char ch:s.toCharArray()){int x; if(ch>='A'&&ch<='Z')x=ch-'A'; else if(ch>='a'&&ch<='z')x=ch-'a'+26; else if(ch>='0'&&ch<='9')x=ch-'0'+52; else if(ch=='-')x=62; else if(ch=='_')x=63; else continue;
            val=(val<<6)|x; bits+=6; if(bits>=8){bits-=8;o.write((val>>bits)&255);}}
        return o.toByteArray();
    }
}
