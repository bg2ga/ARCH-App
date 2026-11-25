package cn.arch.dmr;

import android.content.Context;
import android.util.JsonReader;
import android.util.SparseArray;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** 从 ARCH 服务器下载并缓存 DMR ID -> 呼号通讯录。 */
final class ContactBook {
    private static final String URL = "https://arch.hljrsa.cn/downloads/arch-hotspot/data/contacts.json.gz";
    private static final long REFRESH_MS = 4L * 60 * 60 * 1000;
    private static final String LAST_KEY = "contacts.last.v2";
    private final Context ctx;
    private final SparseArray<String> map = new SparseArray<>(16384);
    private volatile boolean loaded;
    private volatile String lastError;

    ContactBook(Context c) { ctx = c.getApplicationContext(); }

    /** 启动时只读取本地缓存，不自动联网下载。 */
    void loadAsync(final Runnable done) {
        new Thread(() -> {
            try {
                File f = new File(ctx.getFilesDir(), "contacts.json.gz");
                long last = ctx.getSharedPreferences("arch", Context.MODE_PRIVATE).getLong(LAST_KEY, 0);
                boolean cold = !f.exists();
                if (f.exists()) {
                    try {
                        parse(f);
                        loaded = true;
                    } catch (Throwable t) {
                        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
                // 旧版只读缓存，导致新注册/新增呼号长期查不到；过期或数据过少时自动后台刷新。
                if (cold || System.currentTimeMillis() - last > REFRESH_MS || count() < 1000) {
                    try {
                        load(true);
                    } catch (Throwable t) {
                        lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
            } finally {
                if (done != null) done.run();
            }
        }, "arch-contacts-cache").start();
    }

    /** 用户手动点“更新通讯录”：忽略缓存，强制重新下载。 */
    void refreshAsync(final Runnable done) { start(true, done); }

    private void start(final boolean force, final Runnable done) {
        new Thread(() -> {
            try { load(force); }
            catch (Throwable t) { lastError = t.getClass().getSimpleName() + ": " + t.getMessage(); }
            finally { if (done != null) done.run(); }
        }, "arch-contacts").start();
    }

    String lastError() { return lastError; }

    private void load(boolean force) throws Exception {
        File f = new File(ctx.getFilesDir(), "contacts.json.gz");
        long last = ctx.getSharedPreferences("arch", Context.MODE_PRIVATE).getLong(LAST_KEY, 0);
        if (force || !f.exists() || System.currentTimeMillis() - last > REFRESH_MS) {
            if (download(f)) ctx.getSharedPreferences("arch", Context.MODE_PRIVATE)
                    .edit().putLong(LAST_KEY, System.currentTimeMillis()).apply();
        }
        if (!f.exists()) return;
        parse(f);
        loaded = true;
    }

    private boolean download(File f) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "ARCH-Android/" + BuildConfig.VERSION_NAME);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            if (f.exists() && !f.delete()) return false;
            return tmp.renameTo(f);
        } catch (Throwable e) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 解析时用临时表，成功后一次性换入；初始容量调小，避免低内存机型 OOM。 */
    private void parse(File f) throws Exception {
        SparseArray<String> next = new SparseArray<>(16384);
        try (JsonReader r = new JsonReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(f)), "UTF-8"))) {
            r.beginObject();
            while (r.hasNext()) {
                String id = r.nextName();
                String call = r.nextString();
                try { next.put(Integer.parseInt(id), call.trim().toUpperCase(java.util.Locale.ROOT)); }
                catch (NumberFormatException ignored) {}
            }
            r.endObject();
        }
        synchronized (map) {
            map.clear();
            for (int i = 0; i < next.size(); i++) map.put(next.keyAt(i), next.valueAt(i));
        }
    }

    String get(int id) {
        synchronized (map) { return map.get(id); }
    }

    int count() {
        synchronized (map) { return map.size(); }
    }

    boolean isLoaded() { return loaded; }
}