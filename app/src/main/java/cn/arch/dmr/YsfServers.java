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

/** YSF reflectors with online list refresh from arch.hljrsa.cn. */
final class YsfServers {
    private static final String PREFS = "arch";
    private static final String KEY = "ysfServersJson";
    private static final String LIST_URL = "https://arch.hljrsa.cn/downloads/arch-hotspot/data/ysf-servers.json";

    private static final String[][] BUILTIN = {
            {"CN-46002", "CN-46002 / 46002多模融合互联", "39.96.117.224", "42000"},
            {"CN-C4FM-TG46001", "CN-C4FM-TG46001 / BM TG46001", "222.128.4.82", "42000"},
            {"CN-BI4PCS", "CN-BI4PCS / TG46001", "42.194.130.14", "42000"},
            {"CN-ZJ-HZ01", "CN-ZJ-HZ01 / BM-TG46001", "221.12.6.196", "42001"}
    };

    static volatile String[][] ALL = BUILTIN;

    static synchronized void load(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
            String[][] rows = parse(s);
            if (rows != null && rows.length > 0) ALL = rows;
        } catch (Throwable ignored) {}
    }

    static void refresh(final Context c, final Runnable done) {
        new Thread(() -> {
            String body = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LIST_URL + "?t=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("User-Agent", "ARCH-Android");
                if (conn.getResponseCode() == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    in.close();
                    body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
                conn.disconnect();
            } catch (Throwable ignored) {}

            if (body != null) {
                String[][] rows = parse(body);
                if (rows != null && rows.length > 0) {
                    ALL = rows;
                    try {
                        String data = body;
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, data).apply();
                    } catch (Throwable ignored) {}
                }
            }
            if (done != null) done.run();
        }, "ysf-list-refresh").start();
    }

    private static String[][] parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            JsonElement root = JsonParser.parseString(s);
            if (!root.isJsonArray()) return null;
            JsonArray arr = root.getAsJsonArray();
            ArrayList<String[]> rows = new ArrayList<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = str(o, "id");
                String name = str(o, "name");
                String host = str(o, "host");
                int port = 0;
                try { port = o.get("port").getAsInt(); } catch (Throwable ignored) {}
                if (id.isEmpty()) id = name;
                if (name.isEmpty()) name = id;
                if (id.isEmpty() || host.isEmpty() || port <= 0 || port > 65535) continue;
                rows.add(new String[]{id, name, host, String.valueOf(port)});
            }
            return rows.isEmpty() ? null : rows.toArray(new String[0][]);
        } catch (Throwable ignored) { return null; }
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? "" : e.getAsString().trim();
        } catch (Throwable ignored) { return ""; }
    }

    static int count() { return ALL.length; }
    static String id(int i) { return ALL[clamp(i)][0]; }
    static String name(int i) { return ALL[clamp(i)][1]; }
    static String host(int i) { return ALL[clamp(i)][2]; }
    static int port(int i) { try { return Integer.parseInt(ALL[clamp(i)][3]); } catch (Throwable ignored) { return 42000; } }
    static int indexOf(String id) {
        if (id != null) for (int i = 0; i < ALL.length; i++) if (ALL[i][0].equalsIgnoreCase(id)) return i;
        return 0;
    }
    private static int clamp(int i) { return Math.max(0, Math.min(ALL.length - 1, i)); }
    private YsfServers() {}
}
