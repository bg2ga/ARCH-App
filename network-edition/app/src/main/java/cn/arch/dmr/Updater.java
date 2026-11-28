package cn.arch.dmr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 在线升级：检查版本清单 -> 验证明文/大小/哈希/签名 -> 下载 -> 安装。 */
public class Updater {
    public static final String VERSION_URL = BuildConfig.UPDATE_VERSION_URL;
    public static final String FALLBACK_VERSION_URL = BuildConfig.UPDATE_FALLBACK_URL;
    private static final String UA = "ARCH-Android";
    private static final String EXPECTED_SIGNER_SHA256 =
            "f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97";
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private static final long MIN_APK_BYTES = 1024L * 1024L;
    private static final String PREFS = "arch";
    private static final String KEY_PENDING_INSTALL = "pendingInstallPath";

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean autoChecked;
    private static File pendingInstall;
    private volatile HttpURLConnection activeConnection;

    public Updater(Activity a) { this.a = a; }

    public static void resumePendingInstall(Activity a) {
        if (a == null) return;
        File apk = pendingInstall;
        if (apk == null) {
            String path = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .getString(KEY_PENDING_INSTALL, "");
            if (path != null && !path.trim().isEmpty()) apk = new File(path);
        }
        if (apk == null || !apk.isFile()) return;
        if (!canInstallPackages(a)) return;
        pendingInstall = null;
        a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_INSTALL).apply();
        new Updater(a).install(apk);
    }

    public interface Cb { void onDone(String msg); }

    private static final class UpdateInfo {
        final int versionCode; final String versionName, apkUrl, sha256, notes; final long size;
        UpdateInfo(int code, String name, String url, String sha, long bytes, String text) {
            versionCode=code; versionName=name; apkUrl=url; sha256=sha; size=bytes; notes=text;
        }
    }

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
            } catch (Throwable ignored) { progress = null; }
        }
        final ProgressDialog shownProgress = progress;
        new Thread(() -> {
            UpdateInfo info = null;
            String err = null;
            try { info = parseManifest(fetchJson()); }
            catch (Throwable e) { err = safeMessage(e); }
            final UpdateInfo fi = info;
            final String ferr = err;
            ui.post(() -> {
                try {
                    if (shownProgress != null) try { if (shownProgress.isShowing()) shownProgress.dismiss(); } catch (Throwable ignored) {}
                    if (cb != null) cb.onDone(fi == null ? "失败: " + ferr : "ok");
                    if (fi == null) {
                        if (!silent) showInfo("检查更新失败", "无法连接更新服务器，请确认设备能够访问互联网。\n\n" + ferr);
                        return;
                    }
                    if (fi.versionCode <= BuildConfig.VERSION_CODE) {
                        if (!silent) showInfo("检查更新", "当前已是最新版本\n\n" + BuildConfig.VERSION_NAME
                                + "（" + BuildConfig.VERSION_CODE + "）");
                        return;
                    }
                    if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
                    StringBuilder msg = new StringBuilder()
                            .append("当前版本：").append(BuildConfig.VERSION_NAME)
                            .append("（").append(BuildConfig.VERSION_CODE).append("）\n")
                            .append("最新版本：").append(fi.versionName)
                            .append("（").append(fi.versionCode).append("）\n");
                    if (!fi.notes.isEmpty()) msg.append("\n更新内容：\n").append(fi.notes).append('\n');
                    msg.append("\n是否现在下载并安装？");
                    new AlertDialog.Builder(a).setTitle("发现新版本 " + fi.versionName)
                            .setMessage(msg.toString()).setNegativeButton("以后再说", null)
                            .setPositiveButton("下载并安装", (d, w) -> download(fi)).show();
                } catch (Throwable t) {
                    android.util.Log.e("ARCH-UPDATE", "check UI failed", t);
                }
            });
        }, "arch-update-check").start();
    }

    private void showInfo(String title, String message) {
        if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
        try {
            new AlertDialog.Builder(a).setTitle(title).setMessage(message)
                    .setPositiveButton("确定", null).show();
        } catch (Throwable ignored) { toast(message); }
    }

    private JsonObject fetchJson() throws Exception {
        JsonObject primary = null, fallback = null;
        Exception primaryError = null, fallbackError = null;
        try { primary = fetchJson(VERSION_URL); } catch (Exception e) { primaryError = e; }
        try { fallback = fetchJson(FALLBACK_VERSION_URL); } catch (Exception e) { fallbackError = e; }
        if (primary == null && fallback == null) {
            if (fallbackError != null && primaryError != null) fallbackError.addSuppressed(primaryError);
            throw fallbackError != null ? fallbackError : (primaryError != null ? primaryError : new Exception("无更新结果"));
        }
        if (primary == null) return fallback;
        if (fallback == null) return primary;
        try {
            return parseManifest(fallback).versionCode > parseManifest(primary).versionCode ? fallback : primary;
        } catch (Throwable ignored) { return primary; }
    }

    private JsonObject fetchJson(String url) throws Exception {
        if (!isAllowedHttpsUrl(url, true)) throw new SecurityException("更新清单地址不安全");
        HttpURLConnection c = openValidated(url, true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096]; int n; long total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > MAX_MANIFEST_BYTES) throw new Exception("更新清单过大");
                    out.write(b, 0, n);
                }
                JsonElement root = JsonParser.parseString(out.toString(StandardCharsets.UTF_8.name()));
                if (root == null || !root.isJsonObject()) throw new Exception("更新清单格式错误");
                return root.getAsJsonObject();
            }
        } finally { c.disconnect(); }
    }

    private UpdateInfo parseManifest(JsonObject j) throws Exception {
        if (j == null) throw new Exception("更新清单为空");
        long code = requiredNumber(j, "versionCode");
        if (code < 1 || code > Integer.MAX_VALUE) throw new Exception("versionCode 非法");
        String name = requiredString(j, "versionName");
        String url = requiredString(j, "apkUrl");
        String sha = requiredString(j, "sha256");
        long size = requiredNumber(j, "size");
        String notes = optionalString(j, "notes", "");
        if (name.length() > 80) throw new Exception("versionName 过长");
        if (notes.length() > 4000) notes = notes.substring(0, 4000);
        if (!isAllowedHttpsUrl(url, false)) throw new Exception("apkUrl 必须是受信任的 HTTPS 地址");
        if (!sha.matches("[0-9a-fA-F]{64}")) throw new Exception("sha256 非法");
        if (size < MIN_APK_BYTES || size > MAX_APK_BYTES) throw new Exception("安装包大小非法");
        return new UpdateInfo((int)code, name, url, sha.toLowerCase(Locale.US), size, notes);
    }

    private static String requiredString(JsonObject j, String key) throws Exception {
        JsonElement e = j.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new Exception("更新清单缺少字符串字段: " + key);
        return e.getAsString();
    }
    private static String optionalString(JsonObject j, String key, String def) throws Exception {
        JsonElement e = j.get(key);
        if (e == null || e.isJsonNull()) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new Exception("更新清单字段类型错误: " + key);
        return e.getAsString();
    }
    private static long requiredNumber(JsonObject j, String key) throws Exception {
        JsonElement e = j.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new Exception("更新清单缺少数字字段: " + key);
        try { return e.getAsLong(); }
        catch (Throwable t) { throw new Exception("更新清单数字字段非法: " + key); }
    }

    private static boolean isAllowedHttpsUrl(String raw, boolean manifest) {
        try {
            Uri u = Uri.parse(raw == null ? "" : raw);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
            String path = u.getPath() == null ? "" : u.getPath();
            if (!"https".equals(scheme) || !"hljrsa.cn".equals(host)
                    || (u.getUserInfo() != null && !u.getUserInfo().isEmpty())) return false;
            if (manifest) return path.startsWith("/releases/ota/") && path.endsWith("-version.json");
            return path.startsWith("/releases/apps/");
        } catch (Throwable ignored) { return false; }
    }

    private HttpURLConnection openValidated(String url, boolean manifest) throws Exception {
        String current = url;
        for (int i = 0; i < 4; i++) {
            if (!isAllowedHttpsUrl(current, manifest)) throw new SecurityException("更新地址跳转到不可信域名");
            HttpURLConnection c = TlsCompat.open(a, new URL(current));
            c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null || location.length() == 0) throw new Exception("更新跳转缺少地址");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return c;
        }
        throw new Exception("更新跳转次数过多");
    }

    private void download(final UpdateInfo info) {
        File dir = new File(a.getCacheDir(), "upgrade");
        if (!dir.exists() && !dir.mkdirs()) { toast("无法创建下载目录"); return; }
        final File part = new File(dir, "ARCH-update-" + info.versionCode + ".apk.part");
        final File out = new File(dir, "ARCH-update-" + info.versionCode + ".apk");
        deleteQuietly(part); deleteQuietly(out);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final TextView tv = new TextView(a);
        tv.setPadding(48, 40, 48, 40); tv.setTextSize(15); tv.setText("正在下载… 0%");
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("正在下载新版本")
                .setView(tv).setCancelable(false).setNegativeButton("取消", (d, w) -> {
                    cancelled.set(true);
                    HttpURLConnection c = activeConnection;
                    if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
                }).create();
        try { dlg.show(); } catch (Throwable ignored) { toast("无法打开下载窗口"); return; }
        new Thread(() -> {
            String err = null;
            try {
                String digest = downloadFile(info.apkUrl, info.size, part, tv, cancelled);
                if (cancelled.get()) throw new InterruptedException("已取消");
                if (!digest.equalsIgnoreCase(info.sha256)) throw new Exception("安装包 SHA-256 不一致");
                verifyApk(part, info);
                if (cancelled.get()) throw new InterruptedException("已取消");
                if (out.exists() && !out.delete()) throw new Exception("无法替换旧安装包");
                if (!part.renameTo(out)) throw new Exception("无法保存安装包");
            } catch (Throwable e) { err = cancelled.get() ? "已取消" : safeMessage(e); }
            finally { activeConnection = null; if (err != null) deleteQuietly(part); }
            final String ferr = err;
            ui.post(() -> {
                try {
                    if (dlg.isShowing()) dlg.dismiss();
                    if (ferr != null) { if (!"已取消".equals(ferr)) toast(ferr); return; }
                    install(out);
                } catch (Throwable t) { android.util.Log.e("ARCH-UPDATE", "download UI failed", t); }
            });
        }, "arch-update-dl").start();
    }

    private String downloadFile(String source, long expectedSize, File out, TextView tv,
                                AtomicBoolean cancelled) throws Exception {
        if (!isAllowedHttpsUrl(source, false)) throw new SecurityException("安装包地址不安全");
        HttpURLConnection c = openValidated(source, false);
        activeConnection = c;
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + c.getResponseCode());
            long contentLength = c.getContentLength();
            if (contentLength > MAX_APK_BYTES) throw new Exception("安装包过大");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[65536]; int n; long got = 0, last = 0;
                while ((n = in.read(b)) > 0) {
                    if (cancelled.get()) throw new InterruptedException("已取消");
                    got += n;
                    if (got > MAX_APK_BYTES || got > expectedSize) throw new Exception("安装包超过清单声明大小");
                    fo.write(b, 0, n); md.update(b, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - last > 250) {
                        last = now;
                        final int pct = (int)Math.min(100L, got * 100L / expectedSize);
                        final long fg = got;
                        ui.post(() -> tv.setText("正在下载… " + pct + "%  (" + (fg / 1024) + " KB)"));
                    }
                }
            }
            if (cancelled.get()) throw new InterruptedException("已取消");
            if (out.length() != expectedSize)
                throw new Exception("下载不完整：收到 " + out.length() + " / " + expectedSize + " 字节");
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format(Locale.US, "%02x", x));
            return hex.toString();
        } finally { activeConnection = null; c.disconnect(); }
    }

    private void verifyApk(File apk, UpdateInfo info) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() != info.size) throw new Exception("安装包文件不完整");
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (pi == null || !a.getPackageName().equals(pi.packageName)
                || pi.versionCode != info.versionCode || pi.versionCode <= BuildConfig.VERSION_CODE)
            throw new Exception("安装包版本或包名不匹配，已拒绝安装");
        if (!hasExpectedSigner(pi)) throw new Exception("安装包签名不匹配，已拒绝安装");
    }

    @SuppressWarnings("deprecation")
    private static boolean hasExpectedSigner(PackageInfo pi) {
        if (pi == null) return false;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            if (containsExpectedSigner(pi.signingInfo.getApkContentsSigners())) return true;
            return containsExpectedSigner(pi.signingInfo.getSigningCertificateHistory());
        }
        return containsExpectedSigner(pi.signatures);
    }

    private static boolean containsExpectedSigner(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                byte[] digest = md.digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte x : digest) hex.append(String.format(Locale.US, "%02x", x));
                if (EXPECTED_SIGNER_SHA256.equalsIgnoreCase(hex.toString())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void install(File apk) {
        if (!canInstallPackages(a)) {
            pendingInstall = apk;
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                    .putString(KEY_PENDING_INSTALL, apk.getAbsolutePath()).apply();
            new AlertDialog.Builder(a).setTitle("需要安装权限")
                    .setMessage("请先允许 ARCH 安装应用，返回后会自动继续安装。")
                    .setPositiveButton("去设置", (d, w) -> openInstallPermissionSettings(a))
                    .setNegativeButton("取消", (d, w) -> {
                        pendingInstall = null;
                        a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                                .remove(KEY_PENDING_INSTALL).apply();
                    }).show();
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
            openSystemInstaller(apk);
        }, 5000L);
    }

    private void openSystemInstaller(File apk) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT < 24) {
                try { apk.setReadable(true, false); } catch (Throwable ignored) {}
                uri = Uri.fromFile(apk);
            } else {
                uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
            }
            Intent primary = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(uri.getScheme()))
                primary.setClipData(android.content.ClipData.newRawUri("", uri));
            try {
                a.startActivity(primary); toast("下载已完成，正在打开安装器");
                ui.postDelayed(() -> { if (apk.isFile()) apk.delete(); }, 120000L);
                return;
            } catch (Exception first) {
                android.util.Log.w("ARCH-UPDATE", "ACTION_VIEW installer failed: " + first);
            }
            Intent fallback = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,
                    "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(uri.getScheme()))
                fallback.setClipData(android.content.ClipData.newRawUri("", uri));
            a.startActivity(fallback); toast("下载已完成，正在打开安装器");
            ui.postDelayed(() -> { if (apk.isFile()) apk.delete(); }, 120000L);
        } catch (Exception e) {
            android.util.Log.e("ARCH-UPDATE", "installer launch failed: " + e);
            toast("无法启动安装器：" + e.getMessage());
        }
    }

    static boolean canInstallPackages(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 26) return context.getPackageManager().canRequestPackageInstalls();
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 1) == 1;
        } catch (Throwable ignored) { return true; }
    }

    private static void openInstallPermissionSettings(Context context) {
        try {
            Intent i = Build.VERSION.SDK_INT >= 26
                    ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))
                    : new Intent(Settings.ACTION_SECURITY_SETTINGS);
            if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {}
    }

    private boolean installViaPackageSession(File apk) {
        android.content.pm.PackageInstaller.Session session = null;
        try {
            android.content.pm.PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (FileInputStream in = new FileInputStream(apk);
                 java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536]; int n;
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
            return true;
        } catch (Throwable t) {
            android.util.Log.e("ARCH-UPDATE", "package session failed: " + t);
            return false;
        } finally { if (session != null) try { session.close(); } catch (Throwable ignored) {} }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) try { f.delete(); } catch (Throwable ignored) {}
    }
    private static String safeMessage(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
    private void toast(String s) { try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {} }
}
