package cn.arch.dmr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 极简 ContentProvider：把缓存里的升级用 APK 以 content:// 暴露给系统安装器。
 * Android 7 起不能再把 file:// 交给安装器，必须用 content:// + 临时授权。
 */
public class ApkProvider extends ContentProvider {
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".files";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), "upgrade");
        String name = uri.getLastPathSegment();
        File f = new File(dir, name == null ? "" : name);
        try {
            String base = dir.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(base)) throw new FileNotFoundException("非法路径");
        } catch (IOException e) {
            throw new FileNotFoundException("非法路径");
        }
        if (!f.exists()) throw new FileNotFoundException(f.getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}