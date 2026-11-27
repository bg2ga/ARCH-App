package cn.arch.dmr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 全局异常记录：崩溃堆栈写入 files/last_crash.txt，便于用户反馈与定位。 */
public final class ArchCrash {
    private static final String FILE = "last_crash.txt";
    private static final String HISTORY = "crash_history.txt";
    private static volatile boolean installed = false;

    private ArchCrash() {}

    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { write(app, t, e); } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void write(Context ctx, Thread t, Throwable e) {
        String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  thread=" + (t == null ? "?" : t.getName())
                + "  ver=" + BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")\n";
        String body = head + Log.getStackTraceString(e) + "\n";
        try { FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), FILE)); fo.write(body.getBytes("UTF-8")); fo.close(); } catch (Throwable ignored) {}
        try {
            File h = new File(ctx.getFilesDir(), HISTORY);
            if (h.length() > 200 * 1024) h.delete();
            FileOutputStream fo = new FileOutputStream(h, true);
            fo.write(body.getBytes("UTF-8")); fo.close();
        } catch (Throwable ignored) {}
        Log.e("ARCH-CRASH", body);
    }

    public static String read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(b);
            in.close();
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    public static void clear(Context ctx) {
        try { new File(ctx.getFilesDir(), FILE).delete(); } catch (Throwable ignored) {}
    }
}