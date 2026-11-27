package cn.arch.dmr;

import android.content.Context;

/** D-Star reflector catalog with Cloudflare-hosted online refresh. */
final class DStarServers {
    private static final String PREFS="arch";
    private static final String KEY="dstarServersJsonV2";
    private static final String LIST_URL="https://arch.hljrsa.cn/downloads/arch-hotspot/data/dstar-servers.json";
static final String[][] BUILTIN = {
            {"XLXHLJ", "China-二区全模式 TG46002", "ABCDEF", "hljrsa.cn",
             "A=二区全模互联-TG46002\nB=哈尔滨组-TG460451\nC=日常通联频道\nD=数据测试频道\nE=鹦鹉测试\nF=哈尔滨438.500中继", "62130"},
            {"XLX002", "DCS002 / XLX002 中国链路", "ABCDEFGHIJ", "220.178.187.72"},
            {"XLX004", "DCS004 / XLX004 杭州", "ABCDEFGHIJ", "121.43.36.126"},
            {"XLX055", "DCS055 / XLX055 中国链路", "ABCDEFGHIJ", "52.80.4.154"},
            {"XLX074", "DCS074 / CN46001-XLX074", "ABCDEFGHIJ", "222.128.4.82"},
            {"XLX078", "DCS078 / XLX078 中国链路", "ABCDEFGHIJ", "139.196.158.18"},
            {"XLX086", "DCS086 / XLX086 中国链路", "ABCDEFGHIJ", "140.179.13.243"},
            {"XLX107", "DCS107 / XLX107 江西", "ABCDEFGHIJ", "218.95.37.116"},
            {"XLX355", "DCS355 / XLX355 浙江", "ABCDEFGHIJ", "223.95.197.217"},
            {"XLX516", "DCS516 / XLX516 上海", "ABCDEFGHIJ", "101.132.21.119"},
            {"XLX551", "DCS551 / XLX551 杭州", "ABCDEFGHIJ", "120.27.213.225"},
            {"XLX616", "XLX616 上海", "ABCDEFGHIJ", "222.71.150.92"},
            {"XLX826", "XLX826 广州 TG46020", "ABCDEFGHIJ", "8.163.29.23", "C=广州组-TG46020"},
            {"XLXNPP", "XLXNPP 北京", "ABCDEFGHIJ", "49.232.24.178"},
            {"XLXPRC", "XLXPRC 广东", "A", "120.197.207.2"}
    };
    static volatile String[][] ALL = BUILTIN;

    static synchronized void load(Context c) {
        try { String s=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,""); String[][] r=parse(s); if(r!=null&&r.length>0) ALL=r; } catch(Throwable ignored) {}
    }

    static void refresh(final Context c,final Runnable done) {
        new Thread(()->{
            String body=null;
            try {
                java.net.HttpURLConnection conn=(java.net.HttpURLConnection)new java.net.URL(LIST_URL+"?t="+System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setUseCaches(false);
                conn.setRequestProperty("Accept-Encoding","identity"); conn.setRequestProperty("Cache-Control","no-cache"); conn.setRequestProperty("Pragma","no-cache"); conn.setRequestProperty("User-Agent","ARCH-Android");
                if(conn.getResponseCode()==200){
                    java.io.InputStream in=conn.getInputStream(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
                    while((n=in.read(b))>0) out.write(b,0,n); in.close(); body=new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
                }
                conn.disconnect();
            } catch(Throwable ignored) {}
            if(body!=null){ String[][] r=parse(body); if(r!=null&&r.length>0){ ALL=r; try{ c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,body).apply(); }catch(Throwable ignored){} } }
            if(done!=null) done.run();
        },"dstar-list-refresh").start();
    }

    private static String encodeModuleLabels(com.google.gson.JsonObject o){
        StringBuilder out=new StringBuilder();
        for(java.util.Map.Entry<String,com.google.gson.JsonElement> e:o.entrySet()){
            String key=e.getKey()==null?"":e.getKey().trim().toUpperCase(java.util.Locale.US);
            String value=(e.getValue()!=null&&e.getValue().isJsonPrimitive())?e.getValue().getAsString().trim():"";
            if(key.isEmpty()||value.isEmpty()) continue;
            if(out.length()>0) out.append('\n');
            out.append(key).append('=').append(value);
        }
        return out.toString();
    }

    /** Server-list label only: remove inline module-definition suffixes. */
    static String displayName(int index){
        if(ALL==null || ALL.length==0) return "";
        int i=Math.max(0,Math.min(ALL.length-1,index));
        String id=ALL[i][0]==null?"":ALL[i][0].trim();
        String name=ALL[i][1]==null?"":ALL[i][1].trim();
        name=name.replaceFirst("(?i)^DCS\\d+\\s*/\\s*","");
        name=name.replaceAll("(?i)\\s*[,，;；|/]?\\s*模式\\s*[A-Z]\\s*=.*$","");
        name=name.replaceAll("(?i)\\s*[,，;；|/]?\\s*模块\\s*[A-Z]\\s*=.*$","");
        name=name.replaceAll("(?i)\\s*[,，;；|/]?\\s*[A-Z]\\s*=.*$","");
        name=name.replaceAll("\\s+"," ").trim();
        name=name.replaceAll("[\\s·,，;；|/-]+$","");
        if(id.isEmpty()) return name;
        if(name.toUpperCase(java.util.Locale.US).startsWith(id.toUpperCase(java.util.Locale.US))) return name;
        return id + (name.isEmpty()?"":" · "+name);
    }

    static String displayName(String id){
        if(id!=null) for(int i=0;i<ALL.length;i++) if(ALL[i][0]!=null&&ALL[i][0].equalsIgnoreCase(id)) return displayName(i);
        return id==null?"":id;
    }

    static String dmrPort(String id){
        if(id!=null) for(String[] row:ALL){
            if(row!=null&&row.length>=6&&row[0]!=null&&row[0].equalsIgnoreCase(id)&&row[5]!=null&&!row[5].trim().isEmpty()) return row[5].trim();
        }
        return "62030";
    }

    static String moduleLabel(String id,String module){
        if(id==null||module==null) return "";
        for(String[] row:ALL){
            if(row==null||row.length<5||row[0]==null||!row[0].equalsIgnoreCase(id)||row[4]==null) continue;
            for(String line:row[4].split("\\n")){
                int p=line.indexOf('='); if(p<=0) continue;
                if(line.substring(0,p).trim().equalsIgnoreCase(module.trim())) return line.substring(p+1).trim();
            }
        }
        return "";
    }

    private static String[][] parse(String s){
        if(s==null||s.trim().isEmpty()) return null;
        try{
            com.google.gson.JsonElement root=com.google.gson.JsonParser.parseString(s); if(!root.isJsonArray()) return null;
            java.util.ArrayList<String[]> rows=new java.util.ArrayList<>();
            for(com.google.gson.JsonElement e:root.getAsJsonArray()){
                if(!e.isJsonObject()) continue; com.google.gson.JsonObject o=e.getAsJsonObject();
                String id=o.has("id")?o.get("id").getAsString().trim():""; String name=o.has("name")?o.get("name").getAsString().trim():"";
                String mods=o.has("modules")?o.get("modules").getAsString().trim():""; String host=o.has("host")?o.get("host").getAsString().trim():"";
                String dmrPort=o.has("dmrPort")?String.valueOf(o.get("dmrPort").getAsInt()).trim():"62130";
                String labels=""; if(o.has("moduleLabels")&&o.get("moduleLabels").isJsonObject()) labels=encodeModuleLabels(o.getAsJsonObject("moduleLabels"));
                if(id.isEmpty()||name.isEmpty()||host.isEmpty()) continue;
                rows.add(new String[]{id,name,mods,host,labels,dmrPort});
            }
            return rows.isEmpty()?null:rows.toArray(new String[0][]);
        }catch(Throwable ignored){return null;}
    }
}
