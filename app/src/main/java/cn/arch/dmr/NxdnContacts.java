package cn.arch.dmr;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Separate NXDN directory downloaded from the ARCH Cloudflare mirror. */
final class NxdnContacts {
    private static final String URL_TEXT="https://arch.hljrsa.cn/downloads/arch-hotspot/data/NXDN.csv";
    private static final Object LOCK=new Object();
    private static final Map<Integer,String> MAP=new HashMap<>();
    private static volatile long loadedAt=0L;
    private static volatile boolean refreshRunning=false;

    static void load(Context c){
        File f=new File(c.getFilesDir(),"NXDN.csv");
        if(!f.exists())return;
        try{parse(f);loadedAt=f.lastModified();}catch(Throwable ignored){}
    }

    static String get(int id){synchronized(LOCK){return MAP.get(id);}}
    static int size(){synchronized(LOCK){return MAP.size();}}

    static void refreshIfNeeded(Context c){
        if(System.currentTimeMillis()-loadedAt<6L*60L*60L*1000L)return;
        refresh(c,null);
    }

    static void refresh(final Context c,final Runnable done){
        if(refreshRunning)return;
        refreshRunning=true;
        new Thread(()->{
            File tmp=new File(c.getFilesDir(),"NXDN.csv.tmp");
            try{
                HttpURLConnection conn=(HttpURLConnection)new URL(URL_TEXT+"?t="+System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(15000);conn.setReadTimeout(30000);conn.setUseCaches(false);
                conn.setRequestProperty("Accept-Encoding","identity");
                conn.setRequestProperty("Cache-Control","no-cache");
                conn.setRequestProperty("User-Agent","ARCH-Android");
                if(conn.getResponseCode()==200){
                    try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                        byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
                    }
                    File dst=new File(c.getFilesDir(),"NXDN.csv");
                    if(dst.exists()&&!dst.delete())throw new IOException("cannot replace NXDN.csv");
                    if(!tmp.renameTo(dst))throw new IOException("cannot rename NXDN.csv");
                    parse(dst);loadedAt=dst.lastModified();
                }
                conn.disconnect();
            }catch(Throwable ignored){
                try{if(tmp.exists())tmp.delete();}catch(Throwable ignored2){}
            }finally{
                refreshRunning=false;if(done!=null)done.run();
            }
        },"nxdn-contacts-refresh").start();
    }

    private static void parse(File f)throws IOException{
        Map<Integer,String> next=new HashMap<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
            String line;boolean first=true;
            while((line=r.readLine())!=null){
                if(first){first=false;if(line.startsWith("RADIO_ID"))continue;}
                String[] p=line.split(",",7);
                if(p.length<2)continue;
                try{int id=Integer.parseInt(p[0].trim());String cs=p[1].trim();if(id>0&&id<=65535&&!cs.isEmpty())next.put(id,cs.toUpperCase(java.util.Locale.US));}catch(Throwable ignored){}
            }
        }
        synchronized(LOCK){MAP.clear();MAP.putAll(next);}
    }

    private NxdnContacts(){}
}
