package cn.arch.dmr;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.content.pm.ServiceInfo;
import android.widget.Toast;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Draggable floating PTT. It broadcasts local down/up events to the running activity. */
public class ArchOverlayPttService extends Service {
    public static final String ACTION_DOWN = "cn.arch.dmr.OVERLAY_PTT_DOWN";
    public static final String ACTION_UP = "cn.arch.dmr.OVERLAY_PTT_UP";
    public static final String ACTION_START = "cn.arch.dmr.OVERLAY_PTT_START";
    public static final String ACTION_STOP = "cn.arch.dmr.OVERLAY_PTT_STOP";
    public static final String ACTION_SHOW = "cn.arch.dmr.OVERLAY_PTT_SHOW";
    public static final String ACTION_HIDE = "cn.arch.dmr.OVERLAY_PTT_HIDE";
    private static final String CHANNEL = "arch-overlay-ptt";
    private static final int NOTIFY_ID = 1002;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private TextView btn;
    private boolean dragging;
    private int downRawX, downRawY, startX, startY;
    private long downMs;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "悬浮 PTT",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("在其他应用上使用 ARCH 悬浮 PTT");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private Notification notification() {
        Intent stop = new Intent(this, ArchOverlayPttService.class).setAction(ACTION_STOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("ARCH 悬浮 PTT")
                .setContentText("点击悬浮按钮即可发射所选模式")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPi)
                .build();
    }

    private void addButton() {
        if (btn != null) return;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(dp(96), dp(96), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(20); lp.y = dp(240);

        btn = new TextView(this);
        btn.setText("PTT");
        btn.setTextSize(22);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setButtonColor(false);

        btn.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                dragging = false; downMs = System.currentTimeMillis();
                downRawX = (int)e.getRawX(); downRawY = (int)e.getRawY();
                startX = lp.x; startY = lp.y;
                send(ACTION_DOWN); setButtonColor(true);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                int dx = (int)e.getRawX() - downRawX;
                int dy = (int)e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                    dragging = true; send(ACTION_UP); setButtonColor(false);
                }
                if (dragging) {
                    lp.x = startX + dx; lp.y = startY + dy;
                    try { wm.updateViewLayout(btn, lp); } catch (Throwable ignored) {}
                }
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (!dragging) { send(ACTION_UP); v.performClick(); }
                setButtonColor(false);
                return true;
            }
            return false;
        });
        boolean added = false;
        try { wm.addView(btn, lp); added = true; }
        catch (Throwable first) {
            android.util.Log.w("ARCH", "APPLICATION_OVERLAY failed: " + first);
            try {
                lp.type = WindowManager.LayoutParams.TYPE_PHONE;
                wm.addView(btn, lp); added = true;
            } catch (Throwable second) {
                android.util.Log.w("ARCH", "TYPE_PHONE overlay failed: " + second);
            }
        }
        if (!added) {
            Toast.makeText(this, "悬浮窗显示失败，请检查 HyperOS 的悬浮窗、后台弹出界面和后台运行权限", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void removeButton() {
        try { if (wm != null && btn != null) wm.removeView(btn); } catch (Throwable ignored) {}
        btn = null;
    }

    private void setButtonColor(boolean down) {
        if (btn == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(down ? 0xFFDC2626 : 0xFF2563EB);
        d.setStroke(dp(2), down ? 0xFFFFD0D0 : 0x99FFFFFF);
        btn.setBackground(d);
        btn.setElevation(dp(8));
        btn.setText(down ? "发射" : ("PTT\n" + shortMode()));
    }

    private String shortMode() {
                String m = new Prefs(this).pttTargetMode();
        if ("auto".equals(m)) return "自动";
        if ("bm".equals(m)) return "BM";
        if ("analog".equals(m)) return "模拟互联";
        if ("network".equals(m)) return "网络对讲";
        return "数字中继";
    }

    private void send(String action) {
        Intent i = new Intent(action).setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (new Prefs(this).userExited()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (ACTION_STOP.equals(action)) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else
                startForeground(NOTIFY_ID, notification());
        } catch (Throwable t) {
            android.util.Log.w("ARCH", "overlay foreground failed: " + t);
        }
        if (ACTION_HIDE.equals(action)) {
            removeButton();
        } else {
            removeButton();
            addButton();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        removeButton();
        wm = null;
        try { stopForeground(true); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
