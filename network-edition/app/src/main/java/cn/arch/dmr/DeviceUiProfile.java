package cn.arch.dmr;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import java.util.Locale;

/** One-APK runtime UI profile selected from model, resolution, density and input capabilities. */
final class DeviceUiProfile {
    static final String P50PRO = "p50pro";
    static final String PNC370 = "pnc370";
    static final String VM780 = "vm780";
    static final String TABLET = "tablet";
    static final String WIDE_POC = "wide-poc";
    static final String SMALL = "small";
    static final String PHONE = "phone";

    final String kind;
    final boolean p50Pro;
    final boolean pnc370;
    final boolean pnc380;
    final boolean vm780;
    final boolean tablet;
    final boolean wideForm;
    final boolean tinyScreen;
    final boolean shortScreen;
    final boolean keyNavigation;
    final float fontScale;
    final int authFieldHeightDp;
    final int authActionHeightDp;
    final int authLabelSp;
    final float authInputSp;
    final float authButtonSp;
    final int authLogoDp;
    final int authBrandSp;

    private DeviceUiProfile(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / Math.max(1f, dm.density);
        float heightDp = dm.heightPixels / Math.max(1f, dm.density);
        float smallestDp = Math.min(widthDp, heightDp);
        String id = (safe(Build.MANUFACTURER) + " " + safe(Build.MODEL) + " "
                + safe(Build.DEVICE) + " " + safe(Build.PRODUCT)).toUpperCase(Locale.US);
        HyteraDeviceProfile hardware = HyteraDeviceProfile.detect();
        // PNC370 has a stricter keyboard/input contract than PNC380. Keep this
        // profile family-exact; using "small PNC" here made PNC380 inherit the
        // PNC370 layout and focus behavior.
        p50Pro = hardware.isP50Pro();
        pnc370 = HyteraDeviceProfile.FAMILY_PNC370.equals(hardware.family);
        pnc380 = hardware.isPnc380();
        vm780 = HyteraDeviceProfile.FAMILY_VM780.equals(hardware.family)
                || id.contains("VM780") || id.contains("DSJ-HYT");
        tablet = !pnc370 && smallestDp >= 600f;
        wideForm = !pnc370 && widthDp >= 600f;
        tinyScreen = !tablet && (widthDp <= 360f || heightDp <= 480f);
        shortScreen = heightDp <= 280f;
        PackageManager pm = context.getPackageManager();
        boolean touch = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        // PNC370/PNC380 are key-first devices even when the vendor image
        // incorrectly reports a touchscreen feature. PNC370 still gets the
        // stricter input contract through pnc370/isPnc370DedicatedInput().
        keyNavigation = p50Pro || pnc370 || hardware.isPncSmall() || !touch;
        if (p50Pro) kind = P50PRO;
        else if (pnc370) kind = PNC370;
        else if (vm780) kind = VM780;
        else if (tablet) kind = TABLET;
        else if (wideForm) kind = WIDE_POC;
        else if (tinyScreen) kind = SMALL;
        else kind = PHONE;

        if (p50Pro) fontScale = 1.00f;
        else if (pnc370) fontScale = 1.32f;
        else if (vm780) fontScale = 0.90f;
        else if (tablet) fontScale = 1.07f;
        else if (wideForm) fontScale = 1.04f;
        else if (pnc380) fontScale = 1.18f; // Only the PNC small-screen UI, not phones/tablets.
        else if (widthDp <= 260f) fontScale = 0.90f;
        else if (widthDp <= 320f) fontScale = 0.95f;
        else fontScale = 1.00f;

        authFieldHeightDp = pnc370 ? 46 : (vm780 ? 34 : (tablet ? 52 : (shortScreen ? 38 : 44)));
        authActionHeightDp = pnc370 ? 46 : (vm780 ? 38 : (tablet ? 52 : (tinyScreen ? 42 : 46)));
        authLabelSp = (pnc370 || tablet) ? 13 : 11;
        authInputSp = (pnc370 || tablet) ? 17f : 15f;
        authButtonSp = (pnc370 || tablet) ? 16f : 14f;
        authLogoDp = pnc370 ? 36 : (tablet ? 58 : (vm780 ? 30 : 46));
        authBrandSp = pnc370 ? 20 : (tablet ? 28 : (vm780 ? 16 : 24));
        android.util.Log.i("ARCH-UI", "profile=" + kind + " hardware=" + hardware.family
                + " wdp=" + widthDp + " hdp=" + heightDp + " key=" + keyNavigation);
    }

    boolean isPncKeyDevice() { return pnc370 && keyNavigation; }

    static DeviceUiProfile detect(Context context) {
        return new DeviceUiProfile(context.getApplicationContext());
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
