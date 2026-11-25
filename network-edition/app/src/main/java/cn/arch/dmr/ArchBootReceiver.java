package cn.arch.dmr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.content.Intent;

/** Starts ARCH after vendor POC apps have processed the ordered boot broadcast. */
public final class ArchBootReceiver extends BroadcastReceiver {
    public static final String EXTRA_BOOT_START = "arch_boot_start";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.bootAutoStart() || prefs.userExited()) return;
        try {
            Intent service = new Intent(context, NetworkKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            Intent launch = new Intent(context, MainActivity.class);
            launch.putExtra(EXTRA_BOOT_START, true);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(launch);
        } catch (Throwable ignored) {}
    }
}
