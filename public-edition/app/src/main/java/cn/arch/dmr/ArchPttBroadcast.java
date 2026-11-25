package cn.arch.dmr;

import android.content.Context;
import android.content.IntentFilter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * [3.3.0] 设备硬件 PTT 广播表。
 *
 * 很多安卓对讲机不会把实体 PTT 键送进无障碍 KeyEvent，而是直接发厂商广播。
 * 这里收录 HT/滔滔 文档里的常见 down/up 动作，并支持用户在 APP 里手动追加。
 */
public final class ArchPttBroadcast {
    public static final String TYPE_DOWN = "D";
    public static final String TYPE_UP = "U";
    public static final String TYPE_TOGGLE = "T";

    /** 每行：按下动作, 松开动作 */
    private static final String[][] PAIRS = {
            {"com.dream.sk1_down", "com.dream.sk1_up"},
            {"com.zello.ptt.down", "com.zello.ptt.up"},
            {"com.runbo.ptt.key.down", "com.runbo.ptt.key.up"},
            {"com.runbo.poc.key.down", "com.runbo.poc.key.up"},
            {"com.android.action.tdcptt.down", "com.android.action.tdcptt.up"},
            {"com.agold.hy.ptt.down", "com.agold.hy.ptt.up"},
            {"com.sdream.ls.PTT.down", "com.sdream.ls.PTT.up"},
            {"unipro.hotkey.ptt.down", "unipro.hotkey.ptt.up"},
            {"unipro.hotkey.p2.down", "unipro.hotkey.p2.up"},
            {"unipro.hotkey.p3.down", "unipro.hotkey.p3.up"},
            {"com.sonim.intent.action.PTT_KEY_DOWN", "com.sonim.intent.action.PTT_KEY_UP"},
            {"android.intent.action.side_key.keydown.PTT", "android.intent.action.side_key.keyup.PTT"},
            {"android.intent.action.PTT_KEYDOWN", "android.intent.action.PTT_KEYUP"},
            {"android.intent.action.PTT.down", "android.intent.action.PTT.up"},
            {"com.android.intent.ptt.down", "com.android.intent.ptt.up"},
            {"com.ptt1.action.PTT.down", "com.ptt1.action.PTT.up"},
            {"com.ptt2.action.PTT.down", "com.ptt2.action.PTT.up"},
            {"com.dfl.a9.camdown", "com.dfl.a9.camup"},
            {"android.intent.action.DOWN_PTT_KEY", "android.intent.action.UP_PTT_KEY"},
            {"tangwei.intent.action.ACTION_ONEKEY_HANDPHONE_DOWN", "tangwei.intent.action.ACTION_ONEKEY_HANDPHONE_UP"},
            {"ACTION_ONEKEY_HANDPHONE_DOWN", "ACTION_ONEKEY_HANDPHONE_UP"},
            {"android.intent.action.PPT.down", "android.intent.action.PPT.up"},
            {"com.yl.ptt.keydown", "com.yl.ptt.keyup"},
            {"com.android.cusKey.f3.down", "com.android.cusKey.f3.up"},
            {"com.corget.ptt.down", "com.corget.ptt.up"},
            {"com.ntdj.ptt_down", "com.ntdj.ptt_up"},
            {"lte.trunk.action.KEYEVENT_PTT_DOWN", "lte.trunk.action.KEYEVENT_PTT_UP"},
            {"android.intent.action.side_key.keydown.CAMERA", "android.intent.action.side_key.keyup.CAMERA"},
            {"android.intent.action.PTT_DOWN", "android.intent.action.PTT_UP"},
            {"com.chivin.action.MEDIA_PTT_DOWN", "com.chivin.action.MEDIA_PTT_UP"},
            {"com.android.ctyon.PTT.KEY_DOWN", "com.android.ctyon.PTT.KEY_UP"}
    };

    /** 单广播/切换动作。若 Intent 里还带 KeyEvent/action 额外字段，接收器会优先按 down/up 处理。 */
    private static final String[] SINGLES = {
            "android.intent.action.FUN_KEY",
            "com.android.action.OPEN_THIRDPARTY_PTT_APPLICATION",
            "com.android.action.OPEN_THIRDPARTY_PTT_APPLICATION_P2",
            "com.android.action.OPEN_PTT_APPLICATION",
            "com.zello.ptt.toggle",
            "com.android.action.PTT_LONGPRESS",
            "android.intent.action.ppt",
            "key.intent.action.onkeyevent",
            "com.meigsmart.meigkeyaccessibility.onkeyevent",
            "android.intent.action.GLOBAL_BUTTON",
            "com.android.action.ptt",
            "android.intent.action.ptt",
            "android.intent.action.PTT",
            "com.kodiak.intent.action.PTT_BUTTON",
            "com.pockey.keyeventt"
    };

    private ArchPttBroadcast() {}

    public static IntentFilter buildFilter(Context c) {
        IntentFilter f = new IntentFilter();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] pair : PAIRS) {
            if (pair.length >= 2) {
                addAction(f, seen, pair[0]);
                addAction(f, seen, pair[1]);
            }
        }
        for (String action : SINGLES) addAction(f, seen, action);

        try {
            String map = new Prefs(c).extBroadcastMap();
            if (map != null) {
                for (String raw : map.split("\n")) {
                    String line = raw == null ? "" : raw.trim();
                    int p = line.indexOf('|');
                    if (p <= 0 || p >= line.length() - 1) continue;
                    addAction(f, seen, line.substring(p + 1));
                }
            }
        } catch (Throwable ignored) {}
        f.setPriority(1000);
        return f;
    }

    private static void addAction(IntentFilter f, Set<String> seen, String raw) {
        String action = raw == null ? "" : raw.trim();
        if (action.isEmpty() || action.indexOf(' ') >= 0 || action.indexOf('\n') >= 0) return;
        if (!seen.add(action)) return;
        try { f.addAction(action); } catch (Throwable ignored) {}
    }

    /** 返回内置类型：D=按下，U=松开，T=单广播/切换；未知返回 null。 */
    public static String typeOf(String action) {
        if (action == null) return null;
        for (String[] pair : PAIRS) {
            if (action.equals(pair[0])) return TYPE_DOWN;
            if (action.equals(pair[1])) return TYPE_UP;
        }
        for (String single : SINGLES) if (action.equals(single)) return TYPE_TOGGLE;
        return null;
    }

    /** 用户自定义映射优先于内置映射。 */
    public static String customTypeOf(String action, String map) {
        if (action == null || map == null) return null;
        for (String raw : map.split("\n")) {
            String line = raw == null ? "" : raw.trim();
            int p = line.indexOf('|');
            if (p <= 0 || p >= line.length() - 1) continue;
            String type = line.substring(0, p).trim().toUpperCase();
            String value = line.substring(p + 1).trim();
            if (!action.equals(value)) continue;
            if (TYPE_DOWN.equals(type) || TYPE_UP.equals(type) || TYPE_TOGGLE.equals(type)) return type;
        }
        return null;
    }

    /** 从 action 名称本身猜类型，用于命名规范的厂商。 */
    public static String inferType(String action) {
        if (action == null) return null;
        String a = action.toLowerCase(java.util.Locale.US);
        if (a.endsWith(".down") || a.endsWith("_down") || a.endsWith(".keydown")
                || a.endsWith(".key_down") || a.endsWith("ptt.key.down")) return TYPE_DOWN;
        if (a.endsWith(".up") || a.endsWith("_up") || a.endsWith(".keyup")
                || a.endsWith(".key_up") || a.endsWith("ptt.key.up")) return TYPE_UP;
        return null;
    }

    public static int builtInCount() { return PAIRS.length * 2 + SINGLES.length; }

    public static String typeLabel(String type) {
        if (TYPE_DOWN.equals(type)) return "按下 (down)";
        if (TYPE_UP.equals(type)) return "松开 (up)";
        return "单次/切换 (toggle)";
    }
}
