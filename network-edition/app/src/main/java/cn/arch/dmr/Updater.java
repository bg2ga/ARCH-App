package cn.arch.dmr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在线升级：检查 arch-version.json → 下载 APK → 校验 SHA-256 → 交给系统安装器。 */
public class Updater {

    /** 版本清单地址：协会版和公开版各自独立更新。 */
    public static final String VERSION_URL = BuildConfig.UPDATE_VERSION_URL;
    public static final String FALLBACK_VERSION_URL = BuildConfig.UPDATE_FALLBACK_URL;
    private static final String UA = "ARCH-Android";

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 同一次运行里只提示一次自动检查结果，避免每次回前台都弹 */
    private static boolean autoChecked;

    public Updater(Activity a) { this.a = a; }

    private static File pendingInstall;
    private static final String PREFS = "arch";
    private static final String KEY_PENDING_INSTALL = "pendingInstallPath";

    public static void resumePendingInstall(Activity a) {
        if (a == null) return;
        File apk = pendingInstall;
        if (apk == null) {
            String path = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .getString(KEY_PENDING_INSTALL, "");
            if (path != null && !path.trim().isEmpty()) apk = new File(path);
        }
        if (apk == null || !apk.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) return;
        pendingInstall = null;
        a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_INSTALL).apply();
        new Updater(a).install(apk);
    }

    public interface Cb { void onDone(String msg); }

    /** @param silent true=启动时自动检查（无更新不提示）；false=用户手动点“检查更新” */
    public void check(final boolean silent, final Cb cb) {
        if (silent && autoChecked) return;
        if (silent) autoChecked = true;
        ProgressDialog progress = null;
        if (!silent) {
            try {
                progress = new ProgressDialog(a);
                progress.setMessage("正在检查更新…");
                progress.setCancelable(false);
                progress.show();
            } catch (Throwable t) { progress = null; }
        }
        final ProgressDialog shownProgress = progress;
        new Thread(() -> {
            String err = null;
            JsonObject j = null;
            try { j = fetchJson(); }
            catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final JsonObject fj = j;
            final String ferr = err;
            if (ferr != null) android.util.Log.e("ARCH-UPDATE", "download failed: " + ferr);
            ui.post(() -> {
                if (shownProgress != null) {
                    try { if (shownProgress.isShowing()) shownProgress.dismiss(); } catch (Throwable ignored) {}
                }
                if (cb != null) cb.onDone(fj != null ? "ok" : ("失败: " + ferr));
                if (fj == null) {
                    if (!silent) showInfoDialog("检查更新失败",
                            "无法连接更新服务器，请确认设备能够访问互联网。\n\n" + ferr);
                    return;
                }
                int code = fj.has("versionCode") ? fj.get("versionCode").getAsInt() : 0;
                String name = fj.has("versionName") ? fj.get("versionName").getAsString() : String.valueOf(code);
                String url = fj.has("apkUrl") ? fj.get("apkUrl").getAsString() : "";
                String sha = fj.has("sha256") ? fj.get("sha256").getAsString() : "";
                long expectedSize = fj.has("size") ? fj.get("size").getAsLong() : -1L;
                String notes = fj.has("notes") ? fj.get("notes").getAsString() : "";
                if (code <= BuildConfig.VERSION_CODE) {
                    if (!silent) showInfoDialog("检查更新",
                            "当前已是最新版本\n\n" + BuildConfig.VERSION_NAME
                                    + "（" + BuildConfig.VERSION_CODE + "）");
                    return;
                }
                if (url.isEmpty()) { if (!silent) toast("最新版本缺少下载地址"); return; }
                if (sha == null || !sha.matches("[0-9a-fA-F]{64}") || expectedSize <= 0) {
                    if (!silent) toast("更新清单校验信息无效，已拒绝下载");
                    return;
                }
                final String fu = url, fs = sha, fn = notes; final long fsize = expectedSize; final int fcode = code;
                String msg = "当前版本：" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）\n"
                        + "最新版本：" + name + "（" + code + "）\n";
                if (fn != null && fn.length() > 0) msg += "\n更新内容：\n" + fn + "\n";
                msg += "\n是否现在下载并安装？";
                if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
                try {
                    new AlertDialog.Builder(a)
                            .setTitle("发现新版本 " + name)
                            .setMessage(msg)
                            .setNegativeButton("以后再说", null)
                            .setPositiveButton("下载并安装", (d, w) -> download(fu, fs, fsize, fcode))
                            .show();
                } catch (Throwable ignored) {}
            });
        }, "arch-update-check").start();
    }

    private void showInfoDialog(String title, String message) {
        if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
        try {
            new AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            toast(message);
        }
    }

    private JsonObject fetchJson() throws Exception {
        JsonObject primary = null;
        JsonObject fallback = null;
        Exception primaryError = null;
        Exception fallbackError = null;
        try { primary = fetchJson(VERSION_URL); } catch (Exception e) { primaryError = e; }
        try { fallback = fetchJson(FALLBACK_VERSION_URL); } catch (Exception e) { fallbackError = e; }
        if (primary == null && fallback == null) {
            if (fallbackError != null && primaryError != null) fallbackError.addSuppressed(primaryError);
            throw fallbackError != null ? fallbackError : primaryError;
        }
        if (primary == null) return fallback;
        if (fallback == null) return primary;
        int pc = primary.has("versionCode") ? primary.get("versionCode").getAsInt() : 0;
        int fc = fallback.has("versionCode") ? fallback.get("versionCode").getAsInt() : 0;
        return fc > pc ? fallback : primary;
    }

    private JsonObject fetchJson(String baseUrl) throws Exception {
        if (!trustedUpdateUrl(baseUrl, true)) throw new Exception("更新清单地址不在白名单");
        HttpURLConnection c = TlsCompat.open(a, new URL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()));
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            if (!trustedUpdateUrl(c.getURL().toString(), true)) throw new Exception("更新清单重定向地址不在白名单");
            try (InputStream in = c.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                JsonObject json = JsonParser.parseString(out.toString("UTF-8")).getAsJsonObject();
                validateManifest(json);
                return json;
            }
        } finally { c.disconnect(); }
    }

    private void validateManifest(JsonObject json) throws Exception {
        if (json == null || !json.has("versionCode") || !json.has("versionName") || !json.has("apkUrl")
                || !json.has("sha256") || !json.has("size")) {
            throw new Exception("更新清单字段不完整");
        }
        int code = json.get("versionCode").getAsInt();
        String sha = json.get("sha256").getAsString();
        long size = json.get("size").getAsLong();
        String url = json.get("apkUrl").getAsString();
        if (code <= 0 || !sha.matches("[0-9a-fA-F]{64}") || size <= 0 || url.isEmpty()) {
            throw new Exception("更新清单字段无效");
        }
        if (!trustedUpdateUrl(url, false)) throw new Exception("APK 下载地址不在白名单");
    }

    private static boolean trustedUpdateUrl(String raw, boolean manifest) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.US);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.US);
            String path = uri.getPath() == null ? "" : uri.getPath();
            boolean manifestPath = path.startsWith("/releases/ota/")
                    && path.endsWith("-version.json");
            if (manifest && !manifestPath) return false;
            if (!manifest && !path.startsWith("/releases/apps/")) return false;
            return "hljrsa.cn".equals(host) && "https".equals(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void download(final String url, final String expectSha, final long expectedSize, final int expectedVersionCode) {
        File dir = new File(a.getCacheDir(), "upgrade");
        if (!dir.exists() && !dir.mkdirs()) { toast("无法创建下载目录"); return; }
        String candidateName = url.substring(url.lastIndexOf('/') + 1);
        if (!candidateName.toLowerCase(java.util.Locale.US).endsWith(".apk")) candidateName = "ARCH-update.apk";
        final String name = candidateName;
        final File out = new File(dir, name);
        File[] cached = dir.listFiles();
        if (cached != null) for (File f : cached) {
            if (f != null && f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")
                    && !f.getName().equals(name)) f.delete();
        }

        final TextView tv = new TextView(a);
        tv.setPadding(48, 40, 48, 40);
        tv.setTextSize(15);
        tv.setText("正在下载… 0%");
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("正在下载新版本")
                .setView(tv)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    cancelled.set(true);
                    out.delete();
                })
                .create();
        dlg.show();

        new Thread(() -> {
            String err = null;
            try {
                String digest;
                digest = downloadFile(url, out, expectedSize, tv, cancelled);

                if (expectedSize > 0) ui.post(() -> tv.setText("正在下载… 100%"));
                if (!digest.equalsIgnoreCase(expectSha)) {
                    out.delete();
                    throw new Exception("安装包校验失败（SHA-256 不一致）");
                }
                PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(
                        out.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (pi == null || !a.getPackageName().equals(pi.packageName)
                        || pi.versionCode != expectedVersionCode
                        || pi.versionCode <= BuildConfig.VERSION_CODE) {
                    out.delete();
                    throw new Exception("安装包版本不匹配，已拒绝安装");
                }
                if (!sameSigner(pi)) {
                    out.delete();
                    throw new Exception("安装包签名不匹配，已拒绝安装");
                }
                if (cancelled.get()) {
                    out.delete();
                    throw new Exception("已取消");
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String ferr = err;
            ui.post(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (ferr != null) {
                    if (!"已取消".equals(ferr)) toast("下载失败：" + ferr);
                    out.delete();
                    return;
                }
                if (cancelled.get()) {
                    out.delete();
                    return;
                }
                install(out);
            });
        }, "arch-update-dl").start();
    }

    private String downloadFile(String source, File out, long expectedSize, TextView tv, AtomicBoolean cancelled) throws Exception {
        if (!trustedUpdateUrl(source, false)) throw new Exception("APK 下载地址不在白名单");
        HttpURLConnection c = TlsCompat.open(a, new URL(source));
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            if (!trustedUpdateUrl(c.getURL().toString(), false)) throw new Exception("APK 下载重定向地址不在白名单");
            long total = expectedSize > 0 ? expectedSize : c.getContentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[65536];
                int n; long got = 0; long last = 0;
                while ((n = in.read(b)) > 0) {
                    if (cancelled.get()) throw new Exception("已取消");
                    fo.write(b, 0, n);
                    md.update(b, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 250) {
                        last = now;
                        final int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        final long fg = got;
                        ui.post(() -> tv.setText(pct >= 0
                                ? "正在下载… " + pct + "%"
                                : "正在下载… " + (fg / 1024) + " KB"));
                    }
                }
            }
            if (expectedSize > 0 && out.length() != expectedSize)
                throw new Exception("下载不完整：收到 " + out.length() + " / " + expectedSize + " 字节");
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format(java.util.Locale.US, "%02x", x));
            return hex.toString();
        } finally { c.disconnect(); }
    }

    private boolean sameSigner(PackageInfo archive) {
        try {
            PackageInfo installed = a.getPackageManager().getPackageInfo(
                    a.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] aSigs = installed.signatures;
            Signature[] bSigs = archive.signatures;
            if (aSigs == null || bSigs == null || aSigs.length == 0 || bSigs.length == 0) return false;
            if (aSigs.length != bSigs.length) return false;
            for (Signature left : aSigs) {
                boolean found = false;
                for (Signature right : bSigs) {
                    if (java.util.Arrays.equals(left.toByteArray(), right.toByteArray())) { found = true; break; }
                }
                if (!found) return false;
            }
            for (Signature right : bSigs) {
                boolean found = false;
                for (Signature left : aSigs) {
                    if (java.util.Arrays.equals(left.toByteArray(), right.toByteArray())) { found = true; break; }
                }
                if (!found) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = apk;
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                    .putString(KEY_PENDING_INSTALL, apk.getAbsolutePath()).apply();
            new AlertDialog.Builder(a)
                    .setTitle("需要安装权限")
                    .setMessage("请先允许 ARCH 安装应用，返回后会自动继续安装。")
                    .setPositiveButton("去设置", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            toast("请到系统设置里允许安装未知应用");
                        }
                    })
                    .setNegativeButton("取消", (d, w) -> {
                        pendingInstall = null;
                        a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                                .remove(KEY_PENDING_INSTALL).apply();
                    })
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 21 && installViaPackageSession(apk)) {
            toast("下载已完成，正在安装");
            scheduleSessionInstallerFallback(apk);
            return;
        }
        openSystemInstaller(apk);
    }

    private void scheduleSessionInstallerFallback(final File apk) {
        final android.content.SharedPreferences pref = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        ui.postDelayed(() -> {
            if (apk == null || !apk.isFile()) return;
            if (pref.getBoolean("installPrompted", false)) return;
            android.util.Log.w("ARCH-UPDATE", "package session did not show confirmation, falling back");
            openSystemInstaller(apk);
        }, 5000L);
    }

    private void openSystemInstaller(File apk) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT < 24) {
                // PNC370 / Android 5-6 package installer needs a public file URI.
                try { apk.setReadable(true, false); } catch (Throwable ignored) {}
                uri = Uri.fromFile(apk);
            } else {
                // Android 7+ must use the provider URI; never expose a private file path.
                uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
            }
            Intent primary = new Intent(Intent.ACTION_VIEW);
            primary.setDataAndType(uri, "application/vnd.android.package-archive");
            primary.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(uri.getScheme())) {
                primary.setClipData(android.content.ClipData.newRawUri("", uri));
            }
            try {
                a.startActivity(primary);
                toast("下载已完成，正在打开安装器");
                ui.postDelayed(() -> { if (apk.isFile()) apk.delete(); }, 120000L);
                return;
            } catch (Exception first) {
                android.util.Log.w("ARCH-UPDATE", "ACTION_VIEW installer failed: " + first);
            }
            Intent fallback = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            fallback.setDataAndType(uri, "application/vnd.android.package-archive");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(uri.getScheme())) {
                fallback.setClipData(android.content.ClipData.newRawUri("", uri));
            }
            a.startActivity(fallback);
            toast("下载已完成，正在打开安装器");
            ui.postDelayed(() -> { if (apk.isFile()) apk.delete(); }, 120000L);
        } catch (Exception e) {
            android.util.Log.e("ARCH-UPDATE", "installer launch failed: " + e);
            toast("无法启动安装器：" + e.getMessage());
        }
    }

    private boolean installViaPackageSession(File apk) {
        android.content.pm.PackageInstaller installer = null;
        android.content.pm.PackageInstaller.Session session = null;
        try {
            installer = a.getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (java.io.FileInputStream in = new java.io.FileInputStream(apk);
                 java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                    .putBoolean("installPrompted", false).apply();
            Intent result = new Intent(a, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(InstallResultReceiver.EXTRA_APK_PATH, apk.getAbsolutePath());
            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(a, sessionId, result, piFlags);
            session.commit(pending.getIntentSender());
            android.util.Log.i("ARCH-UPDATE", "package session committed id=" + sessionId);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("ARCH-UPDATE", "package session failed: " + t);
            return false;
        } finally {
            if (session != null) try { session.close(); } catch (Throwable ignored) {}
        }
    }

    private void toast(String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }
}