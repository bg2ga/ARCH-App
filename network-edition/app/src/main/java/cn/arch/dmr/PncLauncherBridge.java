package cn.arch.dmr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Makes the PNC370 launcher POC shortcut point at this installed app. */
final class PncLauncherBridge {
    private static final String EXPECTED_SIGNER_SHA256 =
            "f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97";
    private PncLauncherBridge() {}

    static void install(Context context) {
        if (context == null || !HyteraDeviceProfile.detect().isPncSmall()) return;
        installFromFile(context, new File(context.getApplicationInfo().publicSourceDir));
    }

    /** Also used by Updater before opening the system installer. */
    static void installFromFile(Context context, File source) {
        if (context == null || source == null || !source.isFile()
                || !HyteraDeviceProfile.detect().isPncSmall()) return;
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return;
        FileInputStream in = null;
        FileOutputStream out = null;
        File temp = null;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), ".poc");
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, "poc.apk");
            int flags = PackageManager.GET_SIGNATURES;
            PackageInfo srcInfo = context.getPackageManager()
                    .getPackageArchiveInfo(source.getAbsolutePath(), flags);
            PackageInfo dstInfo = target.exists()
                    ? context.getPackageManager().getPackageArchiveInfo(target.getAbsolutePath(), flags) : null;
            if (dstInfo != null && srcInfo != null
                    && context.getPackageName().equals(dstInfo.packageName)
                    && dstInfo.versionCode >= srcInfo.versionCode
                    && target.length() == source.length()
                    && hasExpectedSigner(srcInfo) && hasExpectedSigner(dstInfo)) return;

            temp = new File(dir, "poc.apk.tmp");
            in = new FileInputStream(source);
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            out.getFD().sync();
            out.close(); out = null;
            in.close(); in = null;

            PackageInfo copied = context.getPackageManager()
                    .getPackageArchiveInfo(temp.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (copied == null || srcInfo == null
                    || !context.getPackageName().equals(copied.packageName)
                    || copied.versionCode != srcInfo.versionCode
                    || !hasExpectedSigner(copied)) return;
            if (target.exists() && !target.delete()) return;
            if (!temp.renameTo(target)) return;
            temp = null;
            try { target.setReadable(true, false); } catch (Throwable ignored) {}
            android.util.Log.i("ARCH-POC", "POC shortcut updated to " + srcInfo.versionCode);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-POC", "POC launcher update failed", t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            if (temp != null) try { temp.delete(); } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean hasExpectedSigner(PackageInfo pi) {
        if (pi == null) return false;
        Signature[] signatures = pi.signatures;
        if (signatures == null || signatures.length == 0) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature sig : signatures) {
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest(sig.toByteArray()))
                    hex.append(String.format(Locale.US, "%02x", b));
                if (EXPECTED_SIGNER_SHA256.equalsIgnoreCase(hex.toString())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
