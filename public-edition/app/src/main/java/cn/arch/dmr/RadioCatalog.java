package cn.arch.dmr;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** [3.2.40] 内置/在线更新的网络收音机电台目录。 */
public final class RadioCatalog {
    public interface Callback { void done(List<RadioStation> stations); }
    public static final String REMOTE_CURRENT = "https://arch.hljrsa.cn/downloads/radio/current.json";
    private static final String CACHE_FILE = "radio_stations_cache.json";
    private static final String PREF_VERSION = "radioCatalogVersion";

    private RadioCatalog() {}

    public static List<RadioStation> load(Context context) {
        List<RadioStation> cache = parse(readFile(new File(context.getFilesDir(), CACHE_FILE)));
        if (!cache.isEmpty()) return cache;
        return loadAsset(context);
    }

    public static void refreshAsync(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            List<RadioStation> result = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(REMOTE_CURRENT).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "ARCH-Radio/3.2.40");
                InputStream in = c.getInputStream();
                String current = readAll(in);
                in.close();
                JSONObject meta = new JSONObject(current);
                String version = String.valueOf(meta.opt("version"));
                String cached = app.getSharedPreferences("arch", Context.MODE_PRIVATE)
                        .getString(PREF_VERSION, "");
                File cacheFile = new File(app.getFilesDir(), CACHE_FILE);
                if (version.equals(cached)) {
                    result = parse(readFile(cacheFile));
                }
                if (result == null || result.isEmpty()) {
                    String url = meta.optString("stationsUrl", "");
                    String sha = meta.optString("sha256", "");
                    if (url.isEmpty()) return;
                    HttpURLConnection d = (HttpURLConnection) new URL(url).openConnection();
                    d.setConnectTimeout(5000);
                    d.setReadTimeout(15000);
                    d.setRequestProperty("User-Agent", "ARCH-Radio/3.2.40");
                    InputStream di = d.getInputStream();
                    byte[] body = readAllBytes(di);
                    di.close();
                    d.disconnect();
                    if (!sha.isEmpty()) {
                        String got = sha256(body);
                        if (!sha.equalsIgnoreCase(got)) return;
                    }
                    String text = new String(body, StandardCharsets.UTF_8);
                    List<RadioStation> list = parse(text);
                    if (list.isEmpty()) return;
                    File tmp = new File(app.getFilesDir(), CACHE_FILE + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(body); }
                    if (cacheFile.exists()) cacheFile.delete();
                    if (!tmp.renameTo(cacheFile)) return;
                    app.getSharedPreferences("arch", Context.MODE_PRIVATE).edit()
                            .putString(PREF_VERSION, version).apply();
                    result = list;
                }
            } catch (Throwable ignored) {
                result = null;
            } finally {
                if (c != null) c.disconnect();
            }
            if (callback != null) callback.done(result);
        }, "radio-catalog-refresh").start();
    }

    private static List<RadioStation> loadAsset(Context context) {
        try (InputStream in = context.getAssets().open("radio_stations.json")) {
            return parse(readAll(in));
        } catch (Throwable ignored) { return new ArrayList<>(); }
    }

    private static List<RadioStation> parse(String text) {
        List<RadioStation> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String stream = o.optString("stream", "");
                if (stream.isEmpty() || (o.has("available") && !o.optBoolean("available", true))) continue;
                out.add(new RadioStation(
                        o.optString("id", ""),
                        o.optString("name", ""),
                        o.optString("province", ""),
                        o.optString("city", ""),
                        o.optString("category", ""),
                        o.optString("source_type", ""),
                        stream));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) return "";
        try (InputStream in = new java.io.FileInputStream(f)) { return readAll(in); }
        catch (Throwable ignored) { return ""; }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static List<String> provinces(List<RadioStation> stations) {
        Set<String> set = new LinkedHashSet<>();
        set.add("全部");
        boolean network = false, shortwave = false;
        for (RadioStation s : stations) {
            if ("网络台".equals(s.province)) { network = true; continue; }
            if ("短波广播".equals(s.province)) { shortwave = true; continue; }
            if (!s.province.isEmpty()) set.add(s.province);
        }
        if (network) set.add("网络台");
        // [3.2.50] 短波广播固定排在网络台后面
        if (shortwave) set.add("短波广播");
        return new ArrayList<>(set);
    }

    public static List<String> cities(List<RadioStation> stations, String province) {
        Set<String> set = new LinkedHashSet<>();
        set.add("全部城市");
        for (RadioStation s : stations) {
            if (province == null || province.isEmpty() || "全部".equals(province) || province.equals(s.province)) {
                if (!s.city.isEmpty() && !"网络台".equals(s.city)) set.add(s.city);
            }
        }
        return new ArrayList<>(set);
    }

    public static List<String> categories(List<RadioStation> stations) {
        Set<String> set = new LinkedHashSet<>();
        set.add("全部");
        for (RadioStation s : stations) if (!s.category.isEmpty()) set.add(s.category);
        return new ArrayList<>(set);
    }
}
