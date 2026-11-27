package cn.arch.dmr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
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
import java.net.URL;
import java.security.MessageDigest;

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

    public static void resumePendingInstall(Activity a) {
        File apk = pendingInstall;
        if (apk == null || a == null || !apk.isFile()) return;
        if (!canInstallPackages(a)) return;
        pendingInstall = null;
        new Updater(a).install(apk);
    }

    public interface Cb { void onDone(String msg); }

    /** @param silent true=启动时自动检查（无更新不提示）；false=用户手动点“检查更新” */
    public void check(final boolean silent, final Cb cb) {
        if (silent && autoChecked) return;
        if (silent) autoChecked = true;
        new Thread(() -> {
            String err = null;
            JsonObject j = null;
            try { j = fetchJson(); }
            catch (Exception e) { err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final JsonObject fj = j;
            final String ferr = err;
            ui.post(() -> {
                if (cb != null) cb.onDone(fj != null ? "ok" : ("失败: " + ferr));
                if (fj == null) {
                    if (!silent) toast("检查更新失败：" + ferr);
                    return;
                }
                int code = fj.has("versionCode") ? fj.get("versionCode").getAsInt() : 0;
                String name = fj.has("versionName") ? fj.get("versionName").getAsString() : String.valueOf(code);
                String url = fj.has("apkUrl") ? fj.get("apkUrl").getAsString() : "";
                String sha = fj.has("sha256") ? fj.get("sha256").getAsString() : "";
                long expectedSize = fj.has("size") ? fj.get("size").getAsLong() : -1L;
                String notes = fj.has("notes") ? fj.get("notes").getAsString() : "";
                if (code <= BuildConfig.VERSION_CODE) {
                    if (!silent) toast("当前已是最新版本 " + BuildConfig.VERSION_NAME);
                    return;
                }
                if (url.isEmpty()) { if (!silent) toast("最新版本缺少下载地址"); return; }
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

    private JsonObject fetchJson() throws Exception {
        try {
            return fetchJson(VERSION_URL);
        } catch (Exception primary) {
            try {
                return fetchJson(FALLBACK_VERSION_URL);
            } catch (Exception fallback) {
                fallback.addSuppressed(primary);
                throw fallback;
            }
        }
    }

    private JsonObject fetchJson(String baseUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
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
            try (InputStream in = c.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return JsonParser.parseString(out.toString("UTF-8")).getAsJsonObject();
            }
        } finally { c.disconnect(); }
    }

    private void download(final String url, final String expectSha, final long expectedSize, final int expectedVersionCode) {
        File dir;
        if (Build.VERSION.SDK_INT < 24) {
            File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            dir = new File(downloads, "ARCHUpdate");
        } else {
            dir = new File(a.getCacheDir(), "upgrade");
        }
        if (!dir.exists() && !dir.mkdirs()) { toast("无法创建下载目录"); return; }
        String candidateName = url.substring(url.lastIndexOf('/') + 1);
        if (!candidateName.toLowerCase(java.util.Locale.US).endsWith(".apk")) candidateName = "ARCH-update.apk";
        final String name = candidateName;
        final File out = new File(dir, name);

        final TextView tv = new TextView(a);
        tv.setPadding(48, 40, 48, 40);
        tv.setTextSize(15);
        tv.setText("正在下载… 0%");
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("正在下载新版本")
                .setView(tv)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {})
                .create();
        dlg.show();

        new Thread(() -> {
            String err = null;
            try {
                String digest;
                digest = downloadFile(url, out, expectedSize, tv);
                if (expectedSize > 0) ui.post(() -> tv.setText("正在下载… 100%"));
                if (expectSha != null && expectSha.length() == 64
                        && !digest.equalsIgnoreCase(expectSha)) {
                    out.delete();
                    throw new Exception("安装包校验失败（SHA-256 不一致）");
                }
                PackageInfo pi = a.getPackageManager().getPackageArchiveInfo(out.getAbsolutePath(), 0);
                if (pi == null || !a.getPackageName().equals(pi.packageName)
                        || pi.versionCode != expectedVersionCode
                        || pi.versionCode <= BuildConfig.VERSION_CODE) {
                    out.delete();
                    throw new Exception("安装包版本不匹配，已拒绝安装");
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String ferr = err;
            ui.post(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (ferr != null) { toast("下载失败：" + ferr); return; }
                install(out);
            });
        }, "arch-update-dl").start();
    }

    private String downloadFile(String source, File out, long expectedSize, TextView tv) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            long total = expectedSize > 0 ? expectedSize : c.getContentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] b = new byte[65536];
                int n; long got = 0; long last = 0;
                while ((n = in.read(b)) > 0) {
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

    private void install(File apk) {
        if (!canInstallPackages(a)) {
            pendingInstall = apk;
            new AlertDialog.Builder(a)
                    .setTitle("需要安装权限")
                    .setMessage(Build.VERSION.SDK_INT >= 26
                            ? "请先允许 ARCH 安装应用，返回后会自动继续安装。"
                            : "请先允许安装未知来源应用，返回后会自动继续安装。")
                    .setPositiveButton("去设置", (d, w) -> openInstallPermissionSettings(a))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        // Android 7 vendor firmware is inconsistent when third-party apps use
        // PackageInstaller sessions; several return Permission Denied asynchronously.
        // The system package installer is the compatible primary path on all devices.
        if (openSystemInstaller(a, apk)) {
            toast("下载已完成，正在打开安装器");
            return;
        }
        if (Build.VERSION.SDK_INT >= 21 && installViaPackageSession(apk)) {
            toast("兼容安装器不可用，正在尝试系统安装会话");
            return;
        }
        toast("无法启动安装器，请检查安装权限");
    }

    static boolean canInstallPackages(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 1) == 1;
        } catch (Throwable ignored) {
            return true;
        }
    }

    static void openInstallPermissionSettings(Context context) {
        try {
            Intent i = Build.VERSION.SDK_INT >= 26
                    ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName()))
                    : new Intent(Settings.ACTION_SECURITY_SETTINGS);
            if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                if (!(context instanceof Activity)) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Throwable ignored) {}
        }
    }

    static boolean openSystemInstaller(Context context, File apk) {
        if (context == null || apk == null || !apk.isFile()) return false;
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT < 24) {
                try { apk.setReadable(true, false); } catch (Throwable ignored) {}
                uri = Uri.fromFile(apk);
            } else {
                uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
            }
            Intent primary = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof Activity)) primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if ("content".equals(uri.getScheme())) {
                primary.setClipData(android.content.ClipData.newRawUri("", uri));
            }
            try {
                context.startActivity(primary);
                return true;
            } catch (Exception first) {
                android.util.Log.w("ARCH-UPDATE", "ACTION_VIEW installer failed: " + first);
            }
            Intent fallback = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof Activity)) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if ("content".equals(uri.getScheme())) {
                fallback.setClipData(android.content.ClipData.newRawUri("", uri));
            }
            context.startActivity(fallback);
            return true;
        } catch (Exception e) {
            android.util.Log.e("ARCH-UPDATE", "installer launch failed: " + e);
            return false;
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
            params.setSize(apk.length());
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (java.io.FileInputStream in = new java.io.FileInputStream(apk);
                 java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(a, InstallResultReceiver.class)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .putExtra(InstallResultReceiver.EXTRA_APK_PATH, apk.getAbsolutePath());
            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
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