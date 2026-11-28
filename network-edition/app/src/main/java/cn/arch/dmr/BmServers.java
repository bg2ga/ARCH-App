package cn.arch.dmr;

import android.content.Context;

/** BrandMeister HBP master servers. 4602 and 4501 stay first by product requirement. */
public final class BmServers {
    private static final String[][] BUILTIN = {
        {"4602", "4602 中国", "4602.master.brandmeister.network", "62031"},
        {"4501", "4501 韩国", "4501.master.brandmeister.network", "62031"},
        {"2001", "2001 Europe HAMNET", "44.148.230.201", "62031"},
        {"2022", "2022 Greece", "2022.master.brandmeister.network", "62031"},
        {"2041", "2041 Netherlands", "2041.master.brandmeister.network", "62031"},
        {"2061", "2061 Belgium", "2061.master.brandmeister.network", "62031"},
        {"2081", "2081 France", "2081.master.brandmeister.network", "62031"},
        {"2082", "2082 France", "2082.master.brandmeister.network", "62031"},
        {"2141", "2141 Spain", "2141.master.brandmeister.network", "62031"},
        {"2162", "2162 Hungary", "2162.master.brandmeister.network", "62031"},
        {"2222", "2222 Italy", "2222.master.brandmeister.network", "62031"},
        {"2262", "2262 Romania", "2262.master.brandmeister.network", "62031"},
        {"2282", "2282 Switzerland", "2282.master.brandmeister.network", "62031"},
        {"2302", "2302 Czech", "2302.master.brandmeister.network", "62031"},
        {"2322", "2322 Austria", "2322.master.brandmeister.network", "62031"},
        {"2341", "2341 United Kingdom", "2341.master.brandmeister.network", "62031"},
        {"2382", "2382 Denmark", "2382.master.brandmeister.network", "62031"},
        {"2402", "2402 Sweden", "2402.master.brandmeister.network", "62031"},
        {"2421", "2421 Norway", "2421.master.brandmeister.network", "62031"},
        {"2441", "2441 Finland", "2441.master.brandmeister.network", "62031"},
        {"2502", "2502 Russia", "2502.master.brandmeister.network", "62031"},
        {"2503", "2503 Russia", "2503.master.brandmeister.network", "62031"},
        {"2602", "2602 Poland", "2602.master.brandmeister.network", "62031"},
        {"2621", "2621 Germany", "2621.master.brandmeister.network", "62031"},
        {"2622", "2622 Germany", "2622.master.brandmeister.network", "62031"},
        {"2682", "2682 Portugal", "2682.master.brandmeister.network", "62031"},
        {"2721", "2721 Ireland", "2721.master.brandmeister.network", "62031"},
        {"2841", "2841 Bulgaria", "2841.master.brandmeister.network", "62031"},
        {"2931", "2931 Slovenia", "2931.master.brandmeister.network", "62031"},
        {"3021", "3021 Canada", "3021.master.brandmeister.network", "62031"},
        {"3102", "3102 United States", "3102.master.brandmeister.network", "62031"},
        {"3103", "3103 United States", "3103.master.brandmeister.network", "62031"},
        {"3104", "3104 United States", "3104.master.brandmeister.network", "62031"},
        {"3341", "3341 Mexico", "3341.master.brandmeister.network", "62031"},
        {"4251", "4251 Israel", "4251.master.brandmeister.network", "62031"},
        {"5021", "5021 Malaysia", "5021.master.brandmeister.network", "62031"},
        {"5051", "5051 Australia", "5051.master.brandmeister.network", "62031"},
        {"5151", "5151 Philippines", "5151.master.brandmeister.network", "62031"},
        {"6551", "6551 South Africa", "6551.master.brandmeister.network", "62031"},
        {"7242", "7242 Brazil", "7242.master.brandmeister.network", "62031"},
        {"7301", "7301 Chile", "7301.master.brandmeister.network", "62031"},
        {"2551", "2551 Ukraine", "23.111.17.39", "62031"}
    };
    private static final String PREFS = "arch";
    private static final String KEY = "dmrServersJson";
    private static final String LIST_URL = "https://arch.hljrsa.cn/downloads/arch-hotspot/data/dmr-servers.json";
    public static volatile String[][] ALL = BUILTIN;

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
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(LIST_URL + "?t=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("User-Agent", "ARCH-Android");
                if (conn.getResponseCode() == 200) {
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    in.close();
                    body = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
                conn.disconnect();
            } catch (Throwable ignored) {}
            if (body != null) {
                String[][] rows = parse(body);
                if (rows != null && rows.length > 0) {
                    ALL = rows;
                    try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, body).apply(); } catch (Throwable ignored) {}
                }
            }
            if (done != null) done.run();
        }, "dmr-list-refresh").start();
    }

    private static String[][] parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(s);
            if (!root.isJsonArray()) return null;
            java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject o = e.getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString().trim() : "";
                String name = o.has("name") ? o.get("name").getAsString().trim() : "";
                String host = o.has("host") ? o.get("host").getAsString().trim() : "";
                int port = o.has("port") ? o.get("port").getAsInt() : 62031;
                if (id.isEmpty() || name.isEmpty() || host.isEmpty() || port <= 0 || port > 65535) continue;
                rows.add(new String[]{id, name, host, String.valueOf(port)});
            }
            if (rows.isEmpty()) return null;
            java.util.Collections.sort(rows, (a,b) -> {
                int ia = "ARCH".equals(a[0]) ? 0 : ("4602".equals(a[0]) ? 1 : ("4501".equals(a[0]) ? 2 : 3));
                int ib = "ARCH".equals(b[0]) ? 0 : ("4602".equals(b[0]) ? 1 : ("4501".equals(b[0]) ? 2 : 3));
                return ia != ib ? Integer.compare(ia, ib) : a[0].compareTo(b[0]);
            });
            return rows.toArray(new String[0][]);
        } catch (Throwable ignored) { return null; }
    }

    public static String[] find(String key) {
        if (key != null) for (String[] s : ALL) if (s[0].equals(key)) return s;
        return ALL[0];
    }
    public static String name(String key) { return find(key)[1]; }
    public static String host(String key) { return find(key)[2]; }
    public static int port(String key) { try { return Integer.parseInt(find(key)[3]); } catch (Exception e) { return 62031; } }
}
