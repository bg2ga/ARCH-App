package cn.arch.dmr;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** NRL server directory: ARCH first, then the public NRL directory, then user custom entries. */
public final class NrlServers {
    public static final String ARCH_KEY = "arch-46002";
    public static final Server ARCH = new Server(ARCH_KEY,
            "ARCH-46002业余无线电融合中枢", "1.58.71.162", 60050, "");

    public static final class Server {
        public final String key;
        public final String name;
        public final String host;
        public final int port;
        public final String password;

        public Server(String key, String name, String host, int port, String password) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "NRL 服务器" : name;
            this.host = host == null ? "" : host.trim();
            this.port = port <= 0 || port > 65535 ? 60050 : port;
            this.password = password == null ? "" : password;
        }

        public String endpoint() {
            return host + ":" + port;
        }
    }

    private static final ArrayList<Server> PUBLIC = new ArrayList<>();
    private static volatile long lastRefreshAt;

    private NrlServers() {}

    public static List<Server> all(Context context) {
        ArrayList<Server> out = new ArrayList<>();
        addUnique(out, ARCH);
        if (context != null) {
            try {
                Prefs p = new Prefs(context);
                addCustom(out, p.nrlCustomServers());
            } catch (Throwable ignored) {}
        }
        synchronized (PUBLIC) {
            for (Server s : PUBLIC) addUnique(out, s);
        }
        return out;
    }

    public static Server find(Context context, String key) {
        if (key == null || key.trim().isEmpty()) return ARCH;
        for (Server s : all(context)) if (s.key.equals(key)) return s;
        return ARCH;
    }

    public static void refresh(Context context, Runnable done) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null) { if (done != null) done.run(); return; }
        final long now = System.currentTimeMillis();
        if (now - lastRefreshAt < 10L * 60L * 1000L) { if (done != null) done.run(); return; }
        lastRefreshAt = now;
        new Thread(() -> {
            try { fetchPublic(); } catch (Throwable ignored) {}
            if (done != null) {
                try { done.run(); } catch (Throwable ignored) {}
            }
        }, "nrl-directory").start();
    }

    private static void fetchPublic() throws Exception {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://m.nrlptt.com/platform/list");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "ARCH-Android-NRL/1.0");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                JSONObject dataObj = root.optJSONObject("data");
                data = dataObj == null ? null : dataObj.optJSONArray("items");
            }
            if (data == null) return;
            ArrayList<Server> parsed = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null || o.optBoolean("hidden", false)) continue;
                String name = o.optString("name", "").trim();
                String host = o.optString("host", "").trim();
                String portText = o.optString("port", "").trim();
                boolean declaredPort = !portText.isEmpty();
                int port = parseInt(portText.isEmpty() ? "60050" : portText, 60050);
                if (name.isEmpty() || host.isEmpty()) continue;
                HostPort hp = splitHostPort(host, port);
                // The official directory sometimes puts a web port in host (for example
                // nrlptt.549990.xyz:2083) while the NRL UDP port is the separate port field.
                if (declaredPort) hp = new HostPort(hp.host, port);
                String key = "public:" + hp.host.toLowerCase(Locale.US) + ":" + hp.port;
                parsed.add(new Server(key, name, hp.host, hp.port, ""));
            }
            synchronized (PUBLIC) {
                PUBLIC.clear();
                PUBLIC.addAll(parsed);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void addCustom(List<Server> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        for (String line : raw.split("\\n")) {
            String[] a = line == null ? new String[0] : line.trim().split("\\|", -1);
            if (a.length < 3) continue;
            String name = a[0].trim();
            HostPort hp = splitHostPort(a[1].trim(), parseInt(a[2], 60050));
            if (hp.host.isEmpty()) continue;
            String pw = a.length > 3 ? a[3] : "";
            addUnique(out, new Server("custom:" + hp.host.toLowerCase(Locale.US) + ":" + hp.port,
                    name.isEmpty() ? hp.host : name, hp.host, hp.port, pw));
        }
    }

    private static void addUnique(List<Server> out, Server s) {
        if (s == null || s.host.isEmpty()) return;
        for (Server x : out) {
            if (x.key.equals(s.key) || (x.host.equalsIgnoreCase(s.host) && x.port == s.port)) return;
        }
        out.add(s);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); }
        catch (Throwable t) { return fallback; }
    }

    private static HostPort splitHostPort(String host, int fallbackPort) {
        String h = host == null ? "" : host.trim();
        int port = fallbackPort;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 0) {
                String tail = h.substring(end + 1);
                if (tail.startsWith(":")) port = parseInt(tail.substring(1), port);
                h = h.substring(1, end);
            }
        } else {
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {
                port = parseInt(h.substring(colon + 1), port);
                h = h.substring(0, colon);
            }
        }
        return new HostPort(h, port);
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String h, int p) { host = h; port = p; }
    }
}
