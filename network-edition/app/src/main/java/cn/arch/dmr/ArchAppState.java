package cn.arch.dmr;

/** [3.2.30] APP 运行状态（进程级），供无障碍服务与界面共享，避免互相依赖。 */
public class ArchAppState {
    /** 界面是否在前台（onResume/onPause 维护） */
    public static volatile boolean visible = false;
    /** 最近一次实体按键（诊断用） */
    public static volatile int lastKeyCode = 0;
    public static volatile int lastKeyAction = -1;
    public static volatile String lastKeySource = "";
    public static volatile long lastKeyAt = 0L;
    /** 由设备按键发起、正在发射中（用于点按开关模式与超时保护） */
    public static volatile boolean hwTx = false;
    /** 原生硬件 PTT 已按下，但 Activity 尚未恢复 */
    public static volatile boolean pendingPttDown = false;
    public static volatile long pendingPttAt = 0L;
    /** [3.3.0] 最近收到的设备广播（供捕获/诊断界面使用） */
    public static volatile String lastBroadcastAction = "";
    public static volatile long lastBroadcastAt = 0L;

    /** Current visible/background audio mode used by screen-off hardware volume keys. */
    public static volatile String activePageMode = "";
    /** Android AudioManager stream id; 3 is STREAM_MUSIC. */
    public static volatile int activeVolumeStream = 3;

    public static void setActiveVolumeContext(String mode, int stream) {
        String nextMode = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.US);
        int nextStream = (stream == 0 || stream == 2 || stream == 3) ? stream : 3;
        if (nextMode.equals(activePageMode) && nextStream == activeVolumeStream) return;
        activePageMode = nextMode;
        activeVolumeStream = nextStream;
        android.util.Log.i("ARCH-VOL", "active context mode=" + nextMode + " stream=" + nextStream);
    }
}
