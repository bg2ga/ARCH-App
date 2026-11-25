package cn.arch.dmr;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

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
                try {
                    context.startActivity(confirm);
                    context.getSharedPreferences("arch", Context.MODE_PRIVATE)
                            .edit().putBoolean("installPrompted", true).apply();
                } catch (Throwable t) {
                    android.util.Log.e("ARCH-UPDATE", "confirmation activity launch failed", t);
                    context.getSharedPreferences("arch", Context.MODE_PRIVATE)
                            .edit().putBoolean("installPrompted", false).apply();
                }
            }
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            try { PncLauncherBridge.install(context); } catch (Throwable ignored) {}
            Toast.makeText(context, "安装成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "安装失败" + (message == null ? "" : "：" + message), Toast.LENGTH_LONG).show();
        }
        String path = intent.getStringExtra(EXTRA_APK_PATH);
        if (path != null && !path.isEmpty()) {
            try { new java.io.File(path).delete(); } catch (Throwable ignored) {}
        }
    }
}
