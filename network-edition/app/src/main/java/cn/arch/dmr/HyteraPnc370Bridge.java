package cn.arch.dmr;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** PNC370 / NB 系列第三方 POC 显示与身份广播兼容层。 */
public final class HyteraPnc370Bridge {
    private static volatile String lastDisplay = "";

    private HyteraPnc370Bridge() {}

    public static boolean isSupported() {
        return HyteraDeviceProfile.FAMILY_PNC370.equals(HyteraDeviceProfile.detect().family);
    }

    @android.annotation.SuppressLint("WrongConstant")
    public static void setDisplay(Context context, String name, String contactId) {
        if (context == null || !isSupported()) return;
        String display = name == null ? "" : name.trim();
        String id = contactId == null ? "" : contactId.trim();
        if ((display + "|" + id).equals(lastDisplay)) return;
        lastDisplay = display + "|" + id;
        try {
            Intent intent = new Intent("com.poc.id");
            intent.putExtra("pocPackageName", context.getPackageName());
            intent.putExtra("pocId", id);
            intent.putExtra("pocIdName", display);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {}
        try {
            Object dsp = context.getSystemService("dsp_service");
            if (dsp == null) return;
            Object wb = dsp.getClass().getMethod("getWBManager").invoke(dsp);
            if (wb == null) return;
            byte[] nameBytes = display.getBytes(StandardCharsets.UTF_16BE);
            byte[] idBytes = id.getBytes(StandardCharsets.UTF_16BE);
            byte[] data = new byte[idBytes.length + 1];
            data[0] = (byte) 2;
            System.arraycopy(idBytes, 0, data, 1, idBytes.length);
            Method broadcast = wb.getClass().getMethod(
                    "HRCPP_WB_Broadcast_POCContactInfo", int.class, byte[].class, byte[].class);
            broadcast.invoke(wb, 2, nameBytes, data);
        } catch (Throwable ignored) {}
    }

    public static void clear(Context context) {
        if (context == null || !isSupported()) return;
        lastDisplay = "";
        setDisplay(context, "", "");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
