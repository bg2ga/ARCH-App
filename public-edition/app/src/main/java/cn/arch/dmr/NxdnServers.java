package cn.arch.dmr;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** NXDN reflectors with online list refresh from arch.hljrsa.cn. */
final class NxdnServers {
    private static final String PREFS = "arch";
    private static final String KEY = "nxdnServersJson";
    private static final String LIST_URL = "https://arch.hljrsa.cn/downloads/arch-hotspot/data/nxdn-servers.json";
    private static final String[][] BUILTIN = {
            {"NXDN-46002", "NXDN-46002 / 46002", "39.96.117.224", "41400"},
            {"NXDN-46001", "NXDN-46001 / 中国 46001", "46001.bh1nyr.net", "41400"},
            {"NXDN-46010", "NXDN-46010 / 中国 46010", "46010.bh1nyr.net", "41402"},
            {"NXDN-46018", "NXDN-46018 / 中国 46018", "47.103.95.163", "41400"},
            {"NXDN-46020", "NXDN-46020 / 中国 46020", "8.163.29.23", "41400"},
            {"NXDN-46085", "NXDN-46085 / 中国 46085", "120.76.96.210", "41400"},
            {"NXDN-46666", "NXDN-46666 / 中国 46666", "tg460677.cn", "41400"},
            {"NXDN-461", "NXDN-461 / 中国 URF", "urf.fmrs.cn", "41400"}
    };
    static volatile String[][] ALL = BUILTIN;

    static synchronized void load(Context c) {
        try { String s=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,""); String[][] r=parse(s); if(r!=null&&r.length>0)ALL=r; }
        catch(Throwable ignored){}
    }

    static void refresh(final Context c,final Runnable done) {
        new Thread(()->{
            String body=null;
            try{
                HttpURLConnection conn=(HttpURLConnection)new URL(LIST_URL+"?t="+System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setUseCaches(false);
                conn.setRequestProperty("Accept-Encoding","identity");conn.setRequestProperty("Cache-Control","no-cache");conn.setRequestProperty("Pragma","no-cache");conn.setRequestProperty("User-Agent","ARCH-Android");
                if(conn.getResponseCode()==200){InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);in.close();body=new String(out.toByteArray(),StandardCharsets.UTF_8);}
                conn.disconnect();
            }catch(Throwable ignored){}
            if(body!=null){String[][] r=parse(body);if(r!=null&&r.length>0){ALL=r;try{c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,body).apply();}catch(Throwable ignored){}}}
            if(done!=null)done.run();
        },"nxdn-list-refresh").start();
    }

    private static String[][] parse(String s){
        if(s==null||s.trim().isEmpty())return null;
        try{
            JsonElement root=JsonParser.parseString(s);if(!root.isJsonArray())return null;JsonArray arr=root.getAsJsonArray();ArrayList<String[]> rows=new ArrayList<>();
            for(JsonElement e:arr){if(!e.isJsonObject())continue;JsonObject o=e.getAsJsonObject();String id=str(o,"id"),name=str(o,"name"),host=str(o,"host");int port=0;try{port=o.get("port").getAsInt();}catch(Throwable ignored){}if(id.isEmpty())id=name;if(name.isEmpty())name=id;if(id.isEmpty()||host.isEmpty()||port<=0||port>65535)continue;rows.add(new String[]{id,name,host,String.valueOf(port)});}
            return rows.isEmpty()?null:rows.toArray(new String[0][]);
        }catch(Throwable ignored){return null;}
    }
    private static String str(JsonObject o,String k){try{JsonElement e=o.get(k);return e==null||e.isJsonNull()?"":e.getAsString().trim();}catch(Throwable ignored){return "";}}
    static int count(){return ALL.length;}
    static String id(int i){return ALL[clamp(i)][0];}
    static String name(int i){return ALL[clamp(i)][1];}
    static String host(int i){return ALL[clamp(i)][2];}
    static int port(int i){try{return Integer.parseInt(ALL[clamp(i)][3]);}catch(Throwable ignored){return 41400;}}
    static int designator(int i){try{return Integer.parseInt(id(i).replaceFirst("(?i)^NXDN-",""));}catch(Throwable ignored){return 46001;}}
    static int indexOf(String id){if(id!=null)for(int i=0;i<ALL.length;i++)if(ALL[i][0].equalsIgnoreCase(id))return i;return 0;}
    private static int clamp(int i){return Math.max(0,Math.min(ALL.length-1,i));}
    private NxdnServers(){}
}
