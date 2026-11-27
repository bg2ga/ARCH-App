package cn.arch.dmr;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Receives the system PackageInstaller confirmation/result PendingIntent. */
public final class InstallResultReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_RESULT = "cn.arch.dmr.INSTALL_RESULT";
    public static final String EXTRA_APK_PATH = "apk_path";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); } catch (Throwable ignored) {}
            }
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "安装成功", Toast.LENGTH_SHORT).show();
            return;
        }
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        boolean permissionFailure = lower.contains("permission denied")
                || lower.contains("permission_denied")
                || lower.contains("permission");
        String path = intent.getStringExtra(EXTRA_APK_PATH);
        if (permissionFailure && path != null) {
            File apk = new File(path);
            if (apk.isFile() && Updater.openSystemInstaller(context, apk)) {
                Toast.makeText(context, "正在改用系统安装器", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(context, "安装失败" + (message == null ? "" : "：" + message), Toast.LENGTH_LONG).show();
    }
}
