package cn.arch.dmr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NRL server directory.
 *
 * ARCH is always first. The public directory is cached locally and refreshed in
 * the background with two independent official endpoints. A failed refresh never
 * clears a usable cache and never changes the user's selected server.
 */
public final class NrlServers {
    public static final String ARCH_KEY = "arch-46002";
    public static final Server ARCH = new Server(ARCH_KEY,
            "ARCH-46002业余无线电融合中枢", "1.58.71.162", 60050, "");

    private static final String PREFS = "arch";
    private static final String CACHE_JSON = "nrlDirectoryCacheV2";
    private static final String LAST_ATTEMPT = "nrlDirectoryLastAttemptV2";
    private static final String LAST_SUCCESS = "nrlDirectoryLastSuccessV2";
    private static final String LAST_SOURCE = "nrlDirectoryLastSourceV2";
    private static final String LAST_ERROR = "nrlDirectoryLastErrorV2";
    private static final String PRIMARY_URL = "https://nrlptt.com/api/platform-servers";
    private static final String FALLBACK_URL = "https://m.nrlptt.com/platform/list";
    private static final long REFRESH_TTL_MS = 15L * 60L * 1000L;
    private static final long FAILURE_RETRY_MS = 60L * 1000L;

    public static final class Server {
        public final String key;
        public final String name;
        public final String host;
        public final int port;
        public final String password;
        public final int online;
        public final int total;
        public final int sortOrder;
        public final String status;
        public final String source;

        public Server(String key, String name, String host, int port, String password) {
            this(key, name, host, port, password, -1, -1, "", "", 0);
        }

        public Server(String key, String name, String host, int port, String password,
                      int online, int total, String status, String source, int sortOrder) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "NRL 服务器" : name;
            this.host = host == null ? "" : host.trim();
            this.port = port <= 0 || port > 65535 ? 60050 : port;
            this.password = password == null ? "" : password;
            this.online = online;
            this.total = total;
            this.status = status == null ? "" : status.trim();
            this.source = source == null ? "" : source.trim();
            this.sortOrder = sortOrder;
        }

        public String endpoint() {
            return host + ":" + port;
        }

        public String populationText() {
            return online >= 0 && total >= 0 ? online + "/" + total : "";
        }

        public String pickerLabel() {
            StringBuilder b = new StringBuilder(name).append(" · ").append(endpoint());
            if (online >= 0 && total >= 0) b.append(" · ").append(online).append('/').append(total);
            if (status.equalsIgnoreCase("offline")) b.append(" · 离线");
            if ("saved".equals(source)) b.append(" · 目录中未找到");
            return b.toString();
        }
    }

    public interface RefreshCallback {
        void onFinished(boolean success, String message);
    }

    private static final ArrayList<Server> PUBLIC = new ArrayList<>();
    private static volatile boolean cacheLoaded;
    private static volatile boolean refreshing;
    private static volatile long lastAttemptAt;
    private static volatile long lastSuccessAt;
    private static volatile String lastSource = "";
    private static volatile String lastError = "";

    private NrlServers() {}

    public static List<Server> all(Context context) {
        ensureCacheLoaded(context);
        ArrayList<Server> out = new ArrayList<>();
        addUnique(out, ARCH);

        Prefs prefs = null;
        if (context != null) {
            try {
                prefs = new Prefs(context);
                // Custom entries keep priority if they intentionally override a public endpoint.
                addCustom(out, prefs.nrlCustomServers());
            } catch (Throwable ignored) {}
        }

        synchronized (PUBLIC) {
            for (Server s : PUBLIC) addUnique(out, s);
        }

        if (prefs != null) {
            try {
                Server saved = savedServer(prefs);
                if (saved != null && !containsKey(out, saved.key) && !containsEndpoint(out, saved.host, saved.port)) {
                    addUnique(out, saved);
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    public static Server find(Context context, String key) {
        if (key == null || key.trim().isEmpty()) return ARCH;
        List<Server> list = all(context);
        for (Server s : list) if (s.key.equals(key)) return s;
        if (context != null) {
            try {
                Server saved = savedServer(new Prefs(context));
                if (saved != null && saved.key.equals(key)) return saved;
            } catch (Throwable ignored) {}
        }
        return ARCH;
    }

    /** Compatibility entry point used by existing callers. */
    public static void refresh(Context context, Runnable done) {
        refresh(context, false, (success, message) -> {
            if (done != null) {
                try { done.run(); } catch (Throwable ignored) {}
            }
        });
    }

    public static void refresh(Context context, boolean force, RefreshCallback callback) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            if (callback != null) callback.onFinished(false, "无网络上下文");
            return;
        }
        ensureCacheLoaded(app);

        final long now = System.currentTimeMillis();
        boolean cacheUsable = !PUBLIC.isEmpty() && lastSuccessAt > 0L;
        if (!force && cacheUsable && now - lastSuccessAt < REFRESH_TTL_MS) {
            if (callback != null) callback.onFinished(true, statusText(app));
            return;
        }
        if (!force && lastAttemptAt > 0L && now - lastAttemptAt < FAILURE_RETRY_MS && !lastError.isEmpty()) {
            if (callback != null) callback.onFinished(false, statusText(app));
            return;
        }

        synchronized (NrlServers.class) {
            if (refreshing) {
                if (callback != null) callback.onFinished(true, "服务器目录正在更新");
                return;
            }
            refreshing = true;
            lastAttemptAt = now;
        }

        new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                FetchResult fetched = fetchPublic(app);
                if (fetched == null || fetched.servers.isEmpty()) {
                    throw new IllegalStateException("服务器目录返回空列表");
                }
                long successAt = System.currentTimeMillis();
                synchronized (PUBLIC) {
                    PUBLIC.clear();
                    PUBLIC.addAll(fetched.servers);
                }
                lastSuccessAt = successAt;
                lastSource = fetched.source;
                lastError = "";
                saveSuccess(app, fetched, successAt);
                ok = true;
                message = "服务器目录已更新";
            } catch (Throwable t) {
                message = safeMessage(t);
                lastError = message;
                saveFailure(app, lastAttemptAt, message);
                // Keep both the in-memory and persisted previous cache untouched.
            } finally {
                refreshing = false;
            }
            if (callback != null) {
                try { callback.onFinished(ok, message); } catch (Throwable ignored) {}
            }
        }, "nrl-directory").start();
    }

    public static boolean isRefreshing() {
        return refreshing;
    }

    public static long lastSuccessAt() {
        return lastSuccessAt;
    }

    public static int publicCount() {
        synchronized (PUBLIC) { return PUBLIC.size(); }
    }

    public static String statusText(Context context) {
        ensureCacheLoaded(context);
        int count = publicCount();
        if (refreshing) {
            return count > 0 ? "服务器目录：正在更新 · 现有 " + count + " 台"
                    : "服务器目录：正在更新…";
        }
        if (lastSuccessAt > 0L) {
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(lastSuccessAt));
            String suffix = lastError.isEmpty() ? "更新" : "更新 · 本次失败，使用缓存";
            return "服务器目录：" + count + " 台 · " + time + " " + suffix;
        }
        if (!lastError.isEmpty()) {
            return count > 0 ? "服务器目录更新失败 · 继续使用本地缓存"
                    : "服务器目录更新失败 · 点“更新”重试";
        }
        return count > 0 ? "服务器目录：使用本地缓存 · " + count + " 台"
                : "服务器目录：尚未取得 · 点“更新”重试";
    }

    private static void ensureCacheLoaded(Context context) {
        if (cacheLoaded) return;
        synchronized (NrlServers.class) {
            if (cacheLoaded) return;
            if (context != null) {
                try {
                    SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    lastSuccessAt = sp.getLong(LAST_SUCCESS, 0L);
                    lastAttemptAt = sp.getLong(LAST_ATTEMPT, 0L);
                    lastSource = sp.getString(LAST_SOURCE, "");
                    lastError = sp.getString(LAST_ERROR, "");
                    String raw = sp.getString(CACHE_JSON, "");
                    ArrayList<Server> cached = parseCache(raw);
                    synchronized (PUBLIC) {
                        PUBLIC.clear();
                        PUBLIC.addAll(cached);
                    }
                } catch (Throwable ignored) {}
            }
            cacheLoaded = true;
        }
    }

    private static Server savedServer(Prefs p) {
        if (p == null) return null;
        String key = p.nrlServerKey();
        String host = p.nrlServerHost();
        if (key.isEmpty() || host.isEmpty() || ARCH_KEY.equals(key)) return null;
        return new Server(key, p.nrlServerName(), host, p.nrlServerPort(), p.nrlPassword(),
                -1, -1, "saved", "saved", Integer.MAX_VALUE);
    }

    private static FetchResult fetchPublic(Context context) throws Exception {
        String primaryError;
        try {
            FetchResult primary = fetch(PRIMARY_URL, context, "primary", "官方主接口");
            if (primary != null && (!primary.servers.isEmpty() || primary.notModified)) return primary;
            primaryError = "主接口为空";
        } catch (Throwable t) {
            primaryError = safeMessage(t);
        }

        try {
            FetchResult fallback = fetch(FALLBACK_URL, context, "fallback", "官方兼容接口");
            if (fallback != null && (!fallback.servers.isEmpty() || fallback.notModified)) return fallback;
            throw new IllegalStateException("备用接口为空");
        } catch (Throwable t) {
            throw new IllegalStateException("主接口: " + primaryError + "；备用接口: " + safeMessage(t));
        }
    }

    private static FetchResult fetch(String url, Context context, String sourceKey, String sourceLabel) throws Exception {
        HttpURLConnection c = null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(9000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "ARCH-Android-NRL/" + BuildConfig.VERSION_NAME);
            String etag = sp.getString("nrlDirectoryEtag:" + sourceKey, "");
            String modified = sp.getString("nrlDirectoryModified:" + sourceKey, "");
            if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
            if (modified != null && !modified.isEmpty()) c.setRequestProperty("If-Modified-Since", modified);

            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                ArrayList<Server> current = new ArrayList<>();
                synchronized (PUBLIC) { current.addAll(PUBLIC); }
                if (current.isEmpty()) throw new IllegalStateException("304 但本地缓存为空");
                return new FetchResult(current, sourceLabel, etag, modified, true);
            }
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            InputStream in = c.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP 响应没有内容");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            ArrayList<Server> parsed = parseDirectory(sb.toString(), sourceLabel);
            if (parsed.isEmpty()) throw new IllegalStateException("没有可用服务器");
            return new FetchResult(parsed, sourceLabel, c.getHeaderField("ETag"),
                    c.getHeaderField("Last-Modified"), false);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ArrayList<Server> parseDirectory(String raw, String sourceLabel) {
        ArrayList<Server> out = new ArrayList<>();
        try {
            JSONArray data = extractArray(raw);
            if (data == null) return out;
            Map<String, Server> unique = new LinkedHashMap<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null || o.optBoolean("hidden", false)) continue;
                String name = o.optString("name", "").trim();
                String rawHost = o.optString("host", "").trim();
                if (name.isEmpty() || rawHost.isEmpty()) continue;

                String portText = o.optString("port", "").trim();
                boolean declaredPort = !portText.isEmpty();
                int port = parseInt(portText.isEmpty() ? "60050" : portText, 60050);
                HostPort hp = splitHostPort(rawHost, port);
                // Some directory rows put a web port in host and the real UDP port in port.
                if (declaredPort) hp = new HostPort(hp.host, port);
                if (hp.host.isEmpty()) continue;
                String key = "public:" + hp.host.toLowerCase(Locale.US) + ":" + hp.port;
                Server s = new Server(key, name, hp.host, hp.port, "",
                        o.optInt("online", -1), o.optInt("total", -1),
                        o.optString("status", ""), sourceLabel, o.optInt("sort_order", i));
                unique.put(key, s);
            }
            out.addAll(unique.values());
        } catch (Throwable ignored) {}
        return out;
    }

    private static JSONArray extractArray(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("[")) return new JSONArray(text);
        JSONObject root = new JSONObject(text);
        JSONArray data = root.optJSONArray("data");
        if (data != null) return data;
        JSONObject dataObj = root.optJSONObject("data");
        if (dataObj != null) {
            JSONArray items = dataObj.optJSONArray("items");
            if (items != null) return items;
        }
        return root.optJSONArray("items");
    }

    private static ArrayList<Server> parseCache(String raw) {
        ArrayList<Server> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("servers");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Server s = new Server(o.optString("key"), o.optString("name"),
                        o.optString("host"), o.optInt("port", 60050), "",
                        o.optInt("online", -1), o.optInt("total", -1),
                        o.optString("status", ""), o.optString("source", ""),
                        o.optInt("sortOrder", i));
                if (!s.key.isEmpty() && !s.host.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void saveSuccess(Context context, FetchResult result, long successAt) {
        try {
            JSONObject root = new JSONObject();
            root.put("savedAt", successAt);
            root.put("source", result.source);
            JSONArray arr = new JSONArray();
            for (Server s : result.servers) {
                JSONObject o = new JSONObject();
                o.put("key", s.key);
                o.put("name", s.name);
                o.put("host", s.host);
                o.put("port", s.port);
                o.put("online", s.online);
                o.put("total", s.total);
                o.put("status", s.status);
                o.put("source", s.source);
                o.put("sortOrder", s.sortOrder);
                arr.put(o);
            }
            root.put("servers", arr);
            SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CACHE_JSON, root.toString())
                    .putLong(LAST_ATTEMPT, successAt)
                    .putLong(LAST_SUCCESS, successAt)
                    .putString(LAST_SOURCE, result.source)
                    .putString(LAST_ERROR, "");
            if (!result.notModified) {
                if (result.etag != null && !result.etag.isEmpty())
                    e.putString("nrlDirectoryEtag:" + sourceKey(result.source), result.etag);
                if (result.lastModified != null && !result.lastModified.isEmpty())
                    e.putString("nrlDirectoryModified:" + sourceKey(result.source), result.lastModified);
            }
            e.apply();
        } catch (Throwable ignored) {}
    }

    private static void saveAttempt(Context context, long at, String error) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(LAST_ATTEMPT, at)
                    .putString(LAST_ERROR, error == null ? "" : error)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static void saveFailure(Context context, long at, String error) {
        saveAttempt(context, at, error);
    }

    private static String sourceKey(String label) {
        return label != null && label.contains("兼容") ? "fallback" : "primary";
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
                    name.isEmpty() ? hp.host : name, hp.host, hp.port, pw,
                    -1, -1, "", "custom", Integer.MAX_VALUE));
        }
    }

    private static void addUnique(List<Server> out, Server s) {
        if (s == null || s.host.isEmpty()) return;
        if (containsKey(out, s.key) || containsEndpoint(out, s.host, s.port)) return;
        out.add(s);
    }

    private static boolean containsKey(List<Server> out, String key) {
        for (Server x : out) if (x.key.equals(key)) return true;
        return false;
    }

    private static boolean containsEndpoint(List<Server> out, String host, int port) {
        for (Server x : out) {
            if (x.port == port && x.host.equalsIgnoreCase(host == null ? "" : host)) return true;
        }
        return false;
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

    private static String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String h, int p) { host = h; port = p; }
    }

    private static final class FetchResult {
        final ArrayList<Server> servers;
        final String source;
        final String etag;
        final String lastModified;
        final boolean notModified;

        FetchResult(List<Server> servers, String source, String etag,
                    String lastModified, boolean notModified) {
            this.servers = new ArrayList<>(servers);
            this.source = source == null ? "" : source;
            this.etag = etag;
            this.lastModified = lastModified;
            this.notModified = notModified;
        }
    }
}