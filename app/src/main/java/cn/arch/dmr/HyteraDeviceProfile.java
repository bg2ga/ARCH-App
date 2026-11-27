package cn.arch.dmr;

import android.os.Build;
import java.util.Locale;

/** Per-model hardware contract used by Hytera / Meig terminals. */
final class HyteraDeviceProfile {
    static final String FAMILY_PNC380 = "pnc380";
    static final String FAMILY_PNC370 = "pnc370";
    static final String FAMILY_PNC = "pnc";
    static final String FAMILY_VM780 = "vm780";
    static final String FAMILY_PDC = "pdc";
    static final String FAMILY_GENERIC = "generic";

    static final HyteraDeviceProfile PNC380 = new HyteraDeviceProfile(
            FAMILY_PNC380,
            "PNC380",
            true,
            true,
            true,
            false,
            new int[]{1001, 138, 139, 140, 141, 142, 243},
            new String[]{"Red", "red"},
            new String[]{"Green", "green"},
            new String[]{
                    "com.zello.ptt.down",
                    "android.intent.action.ACTION_PTTKEY_DOWN",
                    "android.intent.action.ptt.down",
                    "android.intent.action.PTT.down",
                    "android.intent.action.PTT_DOWN",
                    "com.android.intent.ptt.down",
                    "com.yl.ptt.keydown",
                    "alk.hotkey.ptt.down",
                    "android.intent.action.side_key.keydown.PTT",
                    "com.dream.sk1_down"
            },
            new String[]{
                    "com.zello.ptt.up",
                    "android.intent.action.ACTION_PTTKEY_UP",
                    "android.intent.action.ptt.up",
                    "android.intent.action.PTT.up",
                    "android.intent.action.PTT_UP",
                    "com.android.intent.ptt.up",
                    "com.yl.ptt.keyup",
                    "alk.hotkey.ptt.up",
                    "android.intent.action.side_key.keyup.PTT",
                    "com.dream.sk1_up"
            });

    static final HyteraDeviceProfile PNC370 = new HyteraDeviceProfile(
            FAMILY_PNC370,
            "PNC370",
            true,
            true,
            true,
            true,
            new int[]{1001, 138, 139, 140, 141, 142, 243},
            new String[]{"Red", "red"},
            new String[]{"Green", "green"},
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_DOWN",
                    "android.intent.action.ptt.down",
                    "android.intent.action.PTT.down",
                    "android.intent.action.PTT_DOWN",
                    "com.android.intent.ptt.down",
                    "com.yl.ptt.keydown",
                    "alk.hotkey.ptt.down",
                    "android.intent.action.side_key.keydown.PTT",
                    "com.dream.sk1_down"
            },
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_UP",
                    "android.intent.action.ptt.up",
                    "android.intent.action.PTT.up",
                    "android.intent.action.PTT_UP",
                    "com.android.intent.ptt.up",
                    "com.yl.ptt.keyup",
                    "alk.hotkey.ptt.up",
                    "android.intent.action.side_key.keyup.PTT",
                    "com.dream.sk1_up"
            });

    static final HyteraDeviceProfile PNC = new HyteraDeviceProfile(
            FAMILY_PNC,
            "PNC",
            true,
            true,
            true,
            false,
            new int[]{1001, 138, 139, 140, 141, 142, 243},
            new String[]{"Red", "red"},
            new String[]{"Green", "green"},
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_DOWN",
                    "android.intent.action.ptt.down",
                    "com.android.intent.ptt.down",
                    "com.yl.ptt.keydown",
                    "alk.hotkey.ptt.down",
                    "android.intent.action.side_key.keydown.PTT",
                    "com.dream.sk1_down"
            },
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_UP",
                    "android.intent.action.ptt.up",
                    "com.android.intent.ptt.up",
                    "com.yl.ptt.keyup",
                    "alk.hotkey.ptt.up",
                    "android.intent.action.side_key.keyup.PTT",
                    "com.dream.sk1_up"
            });

    static final HyteraDeviceProfile VM780 = new HyteraDeviceProfile(
            FAMILY_VM780,
            "VM780",
            true,
            false,
            false,
            false,
            new int[]{},
            new String[]{"red"},
            new String[]{"green"},
            new String[]{"android.intent.action.ACTION_PTTKEY_DOWN", "alk.hotkey.ptt.down"},
            new String[]{"android.intent.action.ACTION_PTTKEY_UP", "alk.hotkey.ptt.up"});

    static final HyteraDeviceProfile PDC = new HyteraDeviceProfile(
            FAMILY_PDC,
            "PDC/PTC",
            false,
            true,
            true,
            true,
            new int[]{1001, 138, 139, 140, 141, 142, 243},
            new String[]{"red", "Red"},
            new String[]{"green", "Green"},
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_DOWN",
                    "android.intent.action.ptt.down",
                    "alk.hotkey.ptt.down",
                    "android.intent.action.side_key.keydown.PTT"
            },
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_UP",
                    "android.intent.action.ptt.up",
                    "alk.hotkey.ptt.up",
                    "android.intent.action.side_key.keyup.PTT"
            });

    static final HyteraDeviceProfile GENERIC = new HyteraDeviceProfile(
            FAMILY_GENERIC,
            "Hytera",
            false,
            true,
            true,
            false,
            new int[]{1001, 138, 139, 140, 141, 142, 243},
            new String[]{"red", "Red"},
            new String[]{"green", "Green"},
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_DOWN",
                    "android.intent.action.ptt.down",
                    "android.intent.action.PTT.down",
                    "android.intent.action.PTT_DOWN",
                    "android.intent.action.side_key.keydown.PTT",
                    "alk.hotkey.ptt.down",
                    "com.android.intent.ptt.down",
                    "com.yl.ptt.keydown",
                    "com.dream.sk1_down"
            },
            new String[]{
                    "android.intent.action.ACTION_PTTKEY_UP",
                    "android.intent.action.ptt.up",
                    "android.intent.action.PTT.up",
                    "android.intent.action.PTT_UP",
                    "android.intent.action.side_key.keyup.PTT",
                    "alk.hotkey.ptt.up",
                    "com.android.intent.ptt.up",
                    "com.yl.ptt.keyup",
                    "com.dream.sk1_up"
            });

    final String family;
    final String label;
    final boolean meigHardKey;
    final boolean dreamKeyDirect;
    final boolean dreamKeyBroadcast;
    final boolean pdcBridge;
    final int[] pttKeyCodes;
    final String[] redLedNodes;
    final String[] greenLedNodes;
    final String[] pttDownActions;
    final String[] pttUpActions;

    private HyteraDeviceProfile(String family, String label, boolean meigHardKey,
            boolean dreamKeyDirect, boolean dreamKeyBroadcast, boolean pdcBridge,
            int[] pttKeyCodes, String[] redLedNodes, String[] greenLedNodes,
            String[] pttDownActions, String[] pttUpActions) {
        this.family = family;
        this.label = label;
        this.meigHardKey = meigHardKey;
        this.dreamKeyDirect = dreamKeyDirect;
        this.dreamKeyBroadcast = dreamKeyBroadcast;
        this.pdcBridge = pdcBridge;
        this.pttKeyCodes = pttKeyCodes;
        this.redLedNodes = redLedNodes;
        this.greenLedNodes = greenLedNodes;
        this.pttDownActions = pttDownActions;
        this.pttUpActions = pttUpActions;
    }

    static HyteraDeviceProfile detect() {
        String id = compact(safe(Build.MANUFACTURER) + " " + safe(Build.MODEL) + " "
                + safe(Build.DEVICE) + " " + safe(Build.PRODUCT));
        if (id.contains("DSJHYT") || id.contains("VM780")) return VM780;
        if (id.contains("PDC") || id.contains("PTC") || id.contains("PDM")) return PDC;
        if (id.contains("PNC380")) return PNC380;
        if (id.contains("PNC370")) return PNC370;
        if (id.contains("PNC") || id.contains("MNC360") || id.contains("RPC360S")
                || id.contains("TR5POC") || id.contains("POC5") || id.contains("POC868")
                || id.contains("RT580")) return PNC;
        return GENERIC;
    }

    boolean isHyteraLike() {
        return this != GENERIC || isVendorName();
    }

    boolean isPncSmall() {
        return FAMILY_PNC380.equals(family) || FAMILY_PNC370.equals(family) || FAMILY_PNC.equals(family);
    }

    boolean isPnc380() { return FAMILY_PNC380.equals(family); }

    boolean isPnc370() { return FAMILY_PNC370.equals(family); }

    boolean isPttAction(String action, boolean down) {
        String[] actions = down ? pttDownActions : pttUpActions;
        for (String candidate : actions) {
            if (candidate.equals(action)) return true;
        }
        return false;
    }

    boolean isPttKeyCode(int code) {
        if (code == 1001) return true;
        for (int candidate : pttKeyCodes) if (candidate == code) return true;
        return false;
    }

    boolean acceptsDreamSideKeyBroadcast() {
        return dreamKeyBroadcast || isPncSmall();
    }

    private static boolean isVendorName() {
        return safe(Build.MANUFACTURER).equalsIgnoreCase("Hytera")
                || safe(Build.MANUFACTURER).toLowerCase(Locale.US).contains("meig");
    }

    private static String compact(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
