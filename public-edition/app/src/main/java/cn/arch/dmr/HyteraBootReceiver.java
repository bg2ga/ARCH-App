package cn.arch.dmr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启入口；三个版本共用，用户关闭开机自启或已完全退出时不启动。 */
public class HyteraBootReceiver extends BroadcastReceiver {
    public static final String EXTRA_BOOT_START = "arch_hytera_boot_start";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.USER_SWITCHED".equals(action)) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.bootAutoStart() || prefs.userExited()) return;
        try {
            Intent keep = new Intent(context, ArchKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(keep);
            else context.startService(keep);
        } catch (Throwable ignored) {}
        try {
            Intent main = new Intent(context, MainActivity.class)
                    .putExtra(EXTRA_BOOT_START, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(main);
        } catch (Throwable ignored) {}
    }
}
