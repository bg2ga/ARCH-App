package cn.arch.dmr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Minimal preferences for the network-intercom-only edition. */
public class Prefs {
    private final SharedPreferences sp;
    private final SharedPreferences selectionSp;
    private final Context context;

    public Prefs(Context c) {
        context = c.getApplicationContext();
        sp = c.getSharedPreferences("arch", Context.MODE_PRIVATE);
        selectionSp = c.getSharedPreferences("arch_selection", Context.MODE_PRIVATE);
        // APRS default changed from 300s to 60s. Migrate devices that still
        // carry the previous default, while preserving explicit custom values.
        if (!"1".equals(get("aprsInterval60Migration", "0"))) {
            String current = get("aprsInterval", "");
            if (current.isEmpty() || "300".equals(current)) {
                sp.edit().putString("aprsInterval", "60")
                        .putString("aprsInterval60Migration", "1").apply();
            } else {
                sp.edit().putString("aprsInterval60Migration", "1").apply();
            }
        }
        // APRS position comment is user-owned. Migrate the old temporary/default
        // values to the official first-line organization name exactly once.
        if (!"1".equals(get("aprsCommentOrgDefaultV1", "0"))) {
            String currentComment = get("aprsComment", "");
            String trimmedComment = currentComment == null ? "" : currentComment.trim();
            if (trimmedComment.isEmpty()
                    || "ARCH".equalsIgnoreCase(trimmedComment)
                    || "ARCH Network Voice".equalsIgnoreCase(trimmedComment)) {
                sp.edit().putString("aprsComment", "黑龙江省无线电运动协会ARCH")
                        .putString("aprsCommentOrgDefaultV1", "1").apply();
            } else {
                sp.edit().putString("aprsCommentOrgDefaultV1", "1").apply();
            }
        }
        // Do not override the system screen timeout by default.
        if (!"1".equals(get("screenSleepMigrationV1", "0"))) {
            sp.edit().putString("keepScreen", "0")
                    .putString("screenSleepMigrationV1", "1").apply();
        }
        // V2: never let an older "always on" preference override the system
        // 30-second display timeout after upgrades.
        if (!"1".equals(get("screenSleepEnforceV2", "0"))) {
            sp.edit().putString("keepScreen", "0")
                    .putString("screenSleepEnforceV2", "1").apply();
        }
        // One account identity is authoritative. Remove legacy per-BM identity
        // overrides so DMR direct always uses the account callsign/ID/ESSID.
        if (!"1".equals(get("unifiedBmIdentityV1", "0"))) {
            sp.edit().remove("bmCallsign").remove("bmId").remove("bmEssid")
                    .putString("unifiedBmIdentityV1", "1").apply();
        }
        // Page modes are independent switches. A user may enable all, none,
        // or any subset; only the currently visible page owns the audio path.
        if (!"1".equals(get("independentModeSwitchesV2", "0"))) {
            android.content.SharedPreferences.Editor editor = sp.edit();
            if (!sp.contains("showDigital")) editor.putString("showDigital", "1");
            if (!sp.contains("showNetwork")) editor.putString("showNetwork", "0");
            if (!sp.contains("showBm")) editor.putString("showBm", "0");
            if (!sp.contains("showNetRadio")) editor.putString("showNetRadio", "0");
            editor.putString("independentModeSwitchesV2", "1").apply();
        }
        migrateSecret("password");
        migrateSecret("bmPassword");
        migrateSecret("sipPassword");
        migrateSecret("dmrPassword");
        android.util.Log.i("ARCH-AUTH-STORE", "logged=" + authLoggedIn()
                + " pwdLen=" + password().length() + " callsign=" + callsign());
    }

    private static final String SECRET_PREFIX = "enc1:";
    private static final String LEGACY_PREFIX = SECRET_PREFIX + "aes:";
    private static final String STABLE_LEGACY_PREFIX = SECRET_PREFIX + "aes2:";
    private static final String CBC_PREFIX = SECRET_PREFIX + "cbc:";
    private static final String KEY_ALIAS = "arch_prefs_aes_v1";
    private static final String LEGACY_KEY_FILE = ".arch_prefs_key_v1";
    private static final Object LEGACY_KEY_LOCK = new Object();

    private boolean isSecret(String key) {
        return "password".equals(key) || "bmPassword".equals(key)
                || "sipPassword".equals(key) || "dmrPassword".equals(key);
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
    private SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private SecretKey legacySecretKey() throws Exception {
        File file = new File(context.getFilesDir(), LEGACY_KEY_FILE);
        byte[] key = new byte[32];
        synchronized (LEGACY_KEY_LOCK) {
            boolean loaded = false;
            if (file.isFile() && file.length() == key.length) {
                try (FileInputStream in = new FileInputStream(file)) {
                    int read = in.read(key);
                    loaded = read == key.length;
                } catch (Throwable ignored) {}
            }
            if (!loaded) {
                new SecureRandom().nextBytes(key);
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(key);
                    out.getFD().sync();
                }
                try { file.setReadable(false, false); file.setReadable(true, true); } catch (Throwable ignored) {}
                try { file.setWritable(false, false); file.setWritable(true, true); } catch (Throwable ignored) {}
            }
        }
        return new SecretKeySpec(key, "AES");
    }

    private SecretKey stableLegacySecretKey() throws Exception {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        String material = String.valueOf(Build.FINGERPRINT) + "|" + String.valueOf(Build.SERIAL)
                + "|" + String.valueOf(androidId) + "|" + context.getPackageName()
                + "|ARCH-PREFS-AES2";
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }
    private String encryptSecret(String value) {
        if (value == null || value.isEmpty()) return value;
        // Android 5.x devices (PNC370) do not reliably retain the custom key
        // material across vendor updates. The private app data area is already
        // protected by the OS sandbox, so store the credential directly there.
        if (Build.VERSION.SDK_INT < 23) return value;
        try {
            SecretKey key = secretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return SECRET_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Throwable t) {
            android.util.Log.e("ARCH-CRYPTO", "encrypt failed", t);
            return SECRET_PREFIX + "invalid";
        }
    }
    private String decryptSecret(String value) {
        if (value == null || !value.startsWith(SECRET_PREFIX)) return value;
        if (value.startsWith(SECRET_PREFIX + "b64:")) {
            try {
                byte[] plain = Base64.decode(value.substring((SECRET_PREFIX + "b64:").length()), Base64.NO_WRAP);
                return new String(plain, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                android.util.Log.e("ARCH-CRYPTO", "base64 decode failed", t);
                return "";
            }
        }
        if (value.startsWith(CBC_PREFIX)) {
            try {
                byte[] packed = Base64.decode(value.substring(CBC_PREFIX.length()), Base64.NO_WRAP);
                if (packed.length <= 16) return "";
                byte[] iv = new byte[16];
                System.arraycopy(packed, 0, iv, 0, iv.length);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, stableLegacySecretKey(), new IvParameterSpec(iv));
                byte[] plain = cipher.doFinal(packed, iv.length, packed.length - iv.length);
                return new String(plain, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                android.util.Log.e("ARCH-CRYPTO", "cbc decrypt failed", t);
                return "";
            }
        }
        boolean stableLegacy = value.startsWith(STABLE_LEGACY_PREFIX);
        boolean legacy = value.startsWith(LEGACY_PREFIX);
        if ((!stableLegacy && !legacy && Build.VERSION.SDK_INT < 23)
                || SECRET_PREFIX.concat("invalid").equals(value)) return "";
        try {
            String encoded = value.substring(stableLegacy ? STABLE_LEGACY_PREFIX.length()
                    : legacy ? LEGACY_PREFIX.length() : SECRET_PREFIX.length());
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length < 13) return "";
            byte[] iv = new byte[12];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKey key = stableLegacy ? stableLegacySecretKey()
                    : legacy ? legacySecretKey() : secretKey();
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(packed, iv.length, packed.length - iv.length);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            android.util.Log.e("ARCH-CRYPTO", "gcm decrypt failed", t);
            return "";
        }
    }
    private void migrateSecret(String key) {
        try {
            String raw = sp.getString(key, null);
            if (raw == null || raw.isEmpty()) return;
            boolean migratableLegacy = raw.startsWith(SECRET_PREFIX + "b64:")
                    || raw.startsWith(CBC_PREFIX)
                    || raw.startsWith(LEGACY_PREFIX)
                    || raw.startsWith(STABLE_LEGACY_PREFIX);
            if (migratableLegacy) {
                String plain = decryptSecret(raw);
                if (!plain.isEmpty()) {
                    String encrypted = encryptSecret(plain);
                    if (!encrypted.endsWith(":invalid")) {
                        sp.edit().putString(key, encrypted).apply();
                        android.util.Log.i("ARCH-CRYPTO", "legacy secret migrated key=" + key);
                    }
                } else {
                    android.util.Log.w("ARCH-CRYPTO", "legacy secret could not be decrypted key=" + key);
                }
                return;
            }
            if (raw.startsWith(SECRET_PREFIX)) return;
            String encrypted = encryptSecret(raw);
            if (!encrypted.endsWith(":invalid")) sp.edit().putString(key, encrypted).apply();
        } catch (Throwable ignored) {}
    }
    public String get(String key, String def) {
        try {
            String value = sp.getString(key, def);
            return isSecret(key) ? decryptSecret(value) : value;
        } catch (Throwable t) { return def; }
    }
    public void set(String key, String value) {
        String stored = isSecret(key) ? encryptSecret(value) : value;
        sp.edit().putString(key, stored == null ? "" : stored).apply();
    }
    public void setBatch(java.util.Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        android.content.SharedPreferences.Editor e = sp.edit();
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                String raw = entry.getValue() == null ? "" : entry.getValue();
                e.putString(entry.getKey(), isSecret(entry.getKey()) ? encryptSecret(raw) : raw);
            }
        }
        e.apply();
    }
    private int i(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); } catch (Throwable t) { return def; }
    }
    private int clamp(int db) { return Math.max(-20, Math.min(20, db)); }

    public String callsign() { return get("callsign", "").trim().toUpperCase(); }
    public String password() { return get("password", "").trim(); }
    public int dmrId() { return Math.max(0, i("dmrid", 0)); }
    public int essid() { return Math.max(0, Math.min(99, i("essid", 0))); }

    public boolean authLoggedIn() { return "1".equals(get("authLoggedIn", "0")); }
    public void setAuthLoggedIn(boolean on) { set("authLoggedIn", on ? "1" : "0"); }
    public boolean dmrAllow10() { return "1".equals(get("dmrAllow10", "0")); }
    public boolean dmrAllow175() { return "1".equals(get("dmrAllow175", "0")); }
    public void setDmrAllowedGroups(boolean a10, boolean a175) { set("dmrAllow10", a10 ? "1" : "0"); set("dmrAllow175", a175 ? "1" : "0"); }
    public void clearIdentity() {
        sp.edit().remove("callsign").remove("dmrid").remove("essid").remove("password")
                .remove("bmPassword").remove("nxdnId").remove("dmrAllow10").remove("dmrAllow175")
                .putString("authLoggedIn", "0").apply();
    }

    public void saveUnifiedIdentity(String callsign, int dmrId, int essid, String password,
                                    String bmPassword, int nxdnId) {
        sp.edit()
                .putString("callsign", callsign == null ? "" : callsign.trim().toUpperCase())
                .putString("dmrid", String.valueOf(Math.max(0, dmrId)))
                .putString("essid", String.valueOf(Math.max(0, Math.min(99, essid))))
                .putString("password", encryptSecret(password == null ? "" : password))
                .putString("bmPassword", encryptSecret(bmPassword == null ? "" : bmPassword))
                .putString("nxdnId", String.valueOf(Math.max(0, nxdnId)))
                .apply();
    }

    public int networkBitrateKbps() {
        int v = i("networkBitrateKbps", 24);
        return (v == 16 || v == 24 || v == 32 || v == 48) ? v : 24;
    }
    public void setNetworkBitrateKbps(int kbps) {
        int v = (kbps == 16 || kbps == 24 || kbps == 32 || kbps == 48) ? kbps : 24;
        set("networkBitrateKbps", String.valueOf(v));
    }

    public String netRoom() {
        String v = selectionSp.getString("netRoom", get("netRoom", "room5"));
        return v == null ? "" : v.trim();
    }
    public String lastPageMode() {
        String v = selectionSp.getString("lastPageMode", get("lastPageMode", "digital"))
                .trim().toLowerCase(Locale.US);
        if ("network".equals(v) || "dmr".equals(v) || "digital".equals(v) || "radio".equals(v)) return v;
        return "digital";
    }
    public void setLastPageMode(String mode) {
        if (mode == null) return;
        String v = mode.trim().toLowerCase(Locale.US);
        if ("network".equals(v) || "dmr".equals(v) || "digital".equals(v) || "radio".equals(v)) {
            boolean ok = selectionSp.edit().putString("lastPageMode", v).commit();
            android.util.Log.i("ARCH-STATE", "save lastPageMode=" + v + " ok=" + ok);
        }
    }
    public void rememberDmrTg(int tg, int slot) {
        boolean ok = selectionSp.edit().putInt("tg", tg)
                .putInt("tgSlot", slot == 2 ? 2 : 1).commit();
        android.util.Log.i("ARCH-STATE", "save dmr tg=" + tg + " slot=" + slot + " ok=" + ok);
    }
    public void rememberNetRoom(String room) {
        String value = room == null ? "" : room.trim();
        boolean ok = selectionSp.edit().putString("netRoom", value).commit();
        android.util.Log.i("ARCH-STATE", "save netRoom=" + value + " ok=" + ok);
    }
    public void rememberBmTg(int tg) {
        boolean ok = selectionSp.edit().putInt("bmTg", tg).commit();
        android.util.Log.i("ARCH-STATE", "save bmTg=" + tg + " ok=" + ok);
    }
    public String lockedNetRoom() {
        return selectionSp.getString("lockedNetRoom", get("lockedNetRoom", "")).trim();
    }
    public void rememberLockedNetRoom(String roomId) {
        String value = roomId == null ? "" : roomId.trim();
        boolean ok = selectionSp.edit().putString("lockedNetRoom", value).commit();
        android.util.Log.i("ARCH-STATE", "save lockedNetRoom=" + value + " ok=" + ok);
    }
    public int lockedDigitalTg() { return selectionSp.getInt("lockedDigitalTg", 0); }
    public void rememberLockedDigitalTg(int tg) {
        boolean ok = selectionSp.edit().putInt("lockedDigitalTg", Math.max(0, tg)).commit();
        android.util.Log.i("ARCH-STATE", "save lockedDigitalTg=" + tg + " ok=" + ok);
    }
    public int lockedBmTg() { return selectionSp.getInt("lockedBmTg", 0); }
    public void rememberLockedBmTg(int tg) {
        boolean ok = selectionSp.edit().putInt("lockedBmTg", Math.max(0, tg)).commit();
        android.util.Log.i("ARCH-STATE", "save lockedBmTg=" + tg + " ok=" + ok);
    }

    // ── 数字中继（HBP DMR）──
    public boolean showDigital() { return "1".equals(get("showDigital", "1")); }
    public boolean dmrAuto() { return "1".equals(get("dmrAuto", "1")); }
    public boolean autoDigital() { return showDigital() && "1".equals(get("autoDigital", "1")); }
    public String dmrHost() { String v = get("dmrHost", "hljrsa.cn").trim(); return v.isEmpty() ? "hljrsa.cn" : v; }
    public String dmrPassword() { return get("dmrPassword", ""); }
    public int dmrPort() { int v = i("dmrPort", 62030); return v > 0 && v <= 65535 ? v : 62030; }
    public int tg() {
        int v = selectionSp.getInt("tg", i("tg", 460451));
        return v > 0 ? v : 460451;
    }
    public int tgSlot() {
        int v = selectionSp.getInt("tgSlot", i("tgSlot", 1));
        return v == 2 ? 2 : 1;
    }
    public int cc() { return 7; }
    private static final String DEFAULT_TALKGROUPS_JSON = "[{\"id\":460451,\"slot\":1,\"name\":\"哈市组\"},{\"id\":46002,\"slot\":2,\"name\":\"二区组\"},{\"id\":46020,\"slot\":1,\"name\":\"广州组\"},{\"id\":46001,\"slot\":2,\"name\":\"全国组\"},{\"id\":4602,\"slot\":2,\"name\":\"协会组\"},{\"id\":11,\"slot\":2,\"name\":\"私聊组\"}]";
    public String talkgroupsJson() {
        String value = get("talkgroups", DEFAULT_TALKGROUPS_JSON);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_TALKGROUPS_JSON;
        String migrated = value;
        if (!containsJsonId(migrated, 4602)) migrated = appendTalkgroupJson(migrated, 4602, "协会组");
        if (!containsJsonId(migrated, 11)) migrated = appendTalkgroupJson(migrated, 11, "私聊组");
        if (!migrated.equals(value)) set("talkgroups", migrated);
        return migrated;
    }

    // 网络对讲默认关闭，用户必须到设置里手动启用。
    public boolean showNetwork() { return "1".equals(get("showNetwork", "0")); }
    public boolean sipEnabled() { return "1".equals(get("sipEnabled", (!get("sipServer", "").trim().isEmpty() && !get("sipNumber", "").trim().isEmpty()) ? "1" : "0")); }
    public void setSipEnabled(boolean on) { set("sipEnabled", on ? "1" : "0"); }
    public void setNetRoom(String id) { rememberNetRoom(id); }

    public String theme() { return get("theme", "light"); }
    public boolean dark() { return "dark".equals(theme()) || "1".equals(get("dark", "0")); }
    public void setDark(boolean on) { set("theme", on ? "dark" : "light"); set("dark", on ? "1" : "0"); }
    public String themeAccent() { return get("themeAccent", "blue"); }
    public String uiFontMode() { return get("uiFontMode", "auto"); }
    public float uiFontFactor() {
        String m = uiFontMode();
        if ("small".equals(m)) return 0.90f;
        if ("normal".equals(m)) return 1.00f;
        if ("large".equals(m)) return 1.08f;
        if ("xlarge".equals(m)) return 1.16f;
        if ("xxlarge".equals(m)) return 1.24f;
        if ("xxxlarge".equals(m)) return 1.32f;
        return 0f;
    }


    public int rxGainDb() { return clamp(i("rxDb", 0)); }
    public int txGainDb() { return clamp(i("txDb", 0)); }
    public boolean multiListen() { return "1".equals(get("multiListen", "0")); }
    public boolean agc() { return "1".equals(get("agc", "0")); }
    public int agcDb() { return clamp(i("agcDb", 0)); }
    public void setAudio(int rxDb, int txDb, boolean agc, int agcDb) {
        sp.edit().putString("rxDb", String.valueOf(clamp(rxDb)))
                .putString("txDb", String.valueOf(clamp(txDb)))
                .putString("agc", agc ? "1" : "0")
                .putString("agcDb", String.valueOf(clamp(agcDb))).apply();
    }

    public boolean aprsEnabled() { return "1".equals(get("aprsEnabled", "0")); }
    public String aprsCallsign() {
        String v = get("aprsCallsign", callsign()).trim().toUpperCase();
        return v.isEmpty() ? callsign() : v;
    }
    public String aprsSsid() { return get("aprsSsid", "9").trim(); }
    public String aprsSymbol() { return get("aprsSymbol", ">").trim(); }
    public String aprsServer() {
        String v = get("aprsServer", "asia.aprs2.net").trim();
        return v.isEmpty() || "aprs.cn".equalsIgnoreCase(v) ? "asia.aprs2.net" : v;
    }
    public int aprsPort() { return i("aprsPort", 14580); }
    public int aprsInterval() {
        int v = i("aprsInterval", 60);
        return v < 30 ? 30 : Math.min(v, 3600);
    }
    public String aprsComment() {
        String v = get("aprsComment", "黑龙江省无线电运动协会ARCH");
        if (v == null || v.trim().isEmpty()) return "黑龙江省无线电运动协会ARCH";
        return v.trim();
    }

    private static final String DEFAULT_BM_TGS = "460451:哈市组,46001:全国组,46002:二区组,46020:广州组";

    public boolean dmrDirectEnabled() { return "1".equals(get("showBm", "0")); }
    public void setDmrDirectEnabled(boolean on) { set("showBm", on ? "1" : "0"); }
    public String bmCallsign() { return callsign(); }
    public int bmId() { return dmrId(); }
    public int bmEssid() { return essid(); }
    public String bmPassword() { return get("bmPassword", ""); }
    public int nxdnId() { int v = i("nxdnId", 0); return Math.max(0, v); }
    public String bmServerKey() { return get("bmServerKey", "4602"); }
    public String bmServerName() { return get("bmServerName", BmServers.name(bmServerKey())); }
    public String bmServerHost() { return get("bmServerHost", BmServers.host(bmServerKey())); }
    public int bmServerPort() { return i("bmServerPort", BmServers.port(bmServerKey())); }
    public void setBmServer(String key) {
        String[] row = BmServers.find(key);
        set("bmServerKey", row[0]);
        set("bmServerName", row[1]);
        set("bmServerHost", row[2]);
        set("bmServerPort", row[3]);
    }
    public int bmTg() {
        int v = selectionSp.getInt("bmTg", i("bmTg", 460451));
        if (v <= 0) v = 460451;
        String groups = bmTalkgroups();
        if (talkgroupIdPresent(groups, v)) return v;
        int fallback = firstTalkgroupId(groups);
        selectionSp.edit().putInt("bmTg", fallback).apply();
        sp.edit().putString("bmTg", String.valueOf(fallback)).apply();
        android.util.Log.w("ARCH-DMR", "invalid persisted bmTg=" + v + " migrated to TG" + fallback);
        return fallback;
    }
    // MMDVMHost simplex hotspot behavior: Slot1=0, Slot2=1.
    public int bmSlot() { return 2; }
    public String bmTalkgroups() {
        String value = get("bmTalkgroups", DEFAULT_BM_TGS);
        if (value == null || value.trim().isEmpty()) value = DEFAULT_BM_TGS;
        // TG4602 and TG11 belong to the digital-relay list only. Remove any
        // accidental copies from the DMR-direct list while preserving the rest.
        String migrated = removeTalkgroupId(value, 4602);
        migrated = removeTalkgroupId(migrated, 11);
        if (migrated.trim().isEmpty()) migrated = DEFAULT_BM_TGS;
        if (!migrated.equals(value)) set("bmTalkgroups", migrated);
        return migrated;
    }
    private static String removeTalkgroupId(String value, int id) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (String part : value.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            int colon = token.indexOf(':');
            String number = colon >= 0 ? token.substring(0, colon).trim() : token;
            int parsed = -1;
            try { parsed = Integer.parseInt(number); } catch (Throwable ignored) {}
            if (parsed == id) continue;
            if (out.length() > 0) out.append(',');
            out.append(token);
        }
        return out.toString();
    }
    private static boolean talkgroupIdPresent(String value, int id) {
        if (value == null) return false;
        for (String part : value.split(",")) {
            String token = part == null ? "" : part.trim();
            if (token.isEmpty()) continue;
            int colon = token.indexOf(':');
            String number = colon >= 0 ? token.substring(0, colon).trim() : token;
            try {
                if (Integer.parseInt(number) == id) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
    private static int firstTalkgroupId(String value) {
        if (value != null) for (String part : value.split(",")) {
            String token = part == null ? "" : part.trim();
            if (token.isEmpty()) continue;
            int colon = token.indexOf(':');
            String number = colon >= 0 ? token.substring(0, colon).trim() : token;
            try {
                int id = Integer.parseInt(number);
                if (id > 0) return id;
            } catch (Throwable ignored) {}
        }
        return 460451;
    }
    private static boolean containsJsonId(String value, int id) {
        return value != null && value.matches("(?s).*?\"id\"\\s*:\\s*" + id + "\\b.*");
    }
    private static String appendTalkgroupJson(String value, int id, String name) {
        int end = value.lastIndexOf(']');
        if (end < 0) return value;
        String prefix = value.substring(0, end);
        if (!prefix.trim().endsWith("[")) prefix += ",";
        return prefix + "{\"id\":" + id + ",\"slot\":2,\"name\":\"" + name + "\"}]";
    }

    public boolean showNetRadio() { return "1".equals(get("showNetRadio", "0")); }
    public String radioFavorites() { return get("radioFavorites", ""); }
    public boolean radioIsFavorite(String id) {
        if (id == null || id.isEmpty()) return false;
        for (String x : radioFavorites().split("\n")) if (id.equals(x.trim())) return true;
        return false;
    }
    public void radioToggleFavorite(String id) {
        if (id == null || id.isEmpty()) return;
        ArrayList<String> list = new ArrayList<>();
        for (String x : radioFavorites().split("\n")) if (x != null && !x.trim().isEmpty() && !id.equals(x.trim())) list.add(x.trim());
        if (!radioIsFavorite(id)) list.add(0, id);
        set("radioFavorites", String.join("\n", list));
    }
    public String radioRecent() { return get("radioRecent", ""); }
    public void radioRemoveRecent(String id) {
        if (id == null || id.isEmpty()) return;
        ArrayList<String> list = new ArrayList<>();
        for (String x : radioRecent().split("\n")) if (x != null && !x.trim().isEmpty() && !id.equals(x.trim())) list.add(x.trim());
        set("radioRecent", String.join("\n", list));
    }
    public void radioMarkRecent(String id) {
        if (id == null || id.isEmpty()) return;
        ArrayList<String> list = new ArrayList<>(); list.add(id);
        for (String x : radioRecent().split("\n")) {
            String v = x == null ? "" : x.trim();
            if (!v.isEmpty() && !id.equals(v)) list.add(v);
            if (list.size() >= 12) break;
        }
        set("radioRecent", String.join("\n", list));
    }
    public boolean keepScreenOn() { return false; }
    public String audioRoute() { return get("audioRoute", "speaker"); }
    public boolean isEarpiece() { return "earpiece".equals(audioRoute()); }
    public boolean isBluetoothAudio() { return "bluetooth".equals(audioRoute()); }
    public boolean pttTone() { return "1".equals(get("pttTone", "1")); }
    public String pttMode() { return get("pttMode", "hold"); }
    public int pttTimeout() { int v = i("pttTimeout", 300); return v < 30 ? 30 : Math.min(v, 600); }
    public boolean autoReconnect() { return "1".equals(get("autoReconnect", "1")); }
    public boolean bootAutoStart() { return "1".equals(get("bootAutoStart", "1")); }
    public void setBootAutoStart(boolean on) { set("bootAutoStart", on ? "1" : "0"); }
    public boolean userExited() { return "1".equals(get("userExited", "0")); }
    public void setUserExited(boolean exited) { set("userExited", exited ? "1" : "0"); }
    public boolean externalPttEnabled() { return "1".equals(get("extPttEnabled", "1")); }
    public boolean externalPttBackground() { return "1".equals(get("extPttBackground", "1")); }
    public String externalPttKeyMode() { return get("extPttKeyMode", "hold"); }
    public int externalPttKeyCode() { return i("extPttKeyCode", 0); }
    public String externalPttKeyName() { return get("extPttKeyName", "未设置"); }
    public int externalPttReleaseKeyCode() { return i("extPttReleaseKeyCode", 0); }
    public String externalPttReleaseKeyName() { return get("extPttReleaseKeyName", "未设置"); }
    public boolean externalPttA11yFallback() { return "1".equals(get("extPttA11yFallback", "1")); }
    public String extBroadcastMap() { return get("extBroadcastMap", ""); }
    public void extKeyLogAdd(int code, int action, String src) {
        try {
            String line = String.format(Locale.US, "%d|%s|%s|%s", code,
                    action == 0 ? "DOWN" : "UP", src == null ? "" : src,
                    new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            String cur = get("extKeyLog", "");
            String[] old = cur.split("\n");
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, old.length - 11);
            for (int i = start; i < old.length; i++) if (!old[i].trim().isEmpty()) sb.append(old[i].trim()).append('\n');
            sb.append(line);
            set("extKeyLog", sb.toString());
        } catch (Throwable ignored) {}
    }
    public void extBroadcastLogAdd(String value, String src) {
        extKeyLogAdd(0, 0, (src == null ? "" : src) + ":" + (value == null ? "" : value));
    }
    public boolean hyteraKnobEnabled() { return "1".equals(get("hyteraKnobEnabled", "0")); }
    public boolean hyteraIncomingVibrate() { return "1".equals(get("hyteraIncomingVibrate", "1")); }
}


