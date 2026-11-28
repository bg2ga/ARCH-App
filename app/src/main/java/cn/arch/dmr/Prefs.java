package cn.arch.dmr;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private final SharedPreferences sp;
    private static final String OLD_BM_TGS = "91:Worldwide,460:China,46001:National,46045:Heilongjiang";
    private static final String DEFAULT_BM_TGS = "460451:哈市组,46001:中国组,46002:二区组,46020:广州组";
    private static final String OLD_APRS_COMMENT = "ARCH Amateur Radio Convergence Hub";
    private static final String DEFAULT_APRS_COMMENT = "ARCH业余无线电融合中枢";
    public Prefs(Context c) {
        sp = c.getSharedPreferences("arch", Context.MODE_PRIVATE);
        migrateBmDefaults();
        migrateAprsComment();
        migrateDstarModuleA();
        migrateUnifiedIdentity();
        migrateIndependentIdentity();
        migrateShowModes250();
        migrateNxdnDefault46002();
        migrateAutoConnectModes();
        migratePublicEditionDefaults();
        migrateYsfAutoLoginV2();
        migrateAssociationAllModesV1();
        migrateNetworkDefaultsV2();
        migratePowerDefaultsV1();
        migrateSipDefaultsV1();
    }

    private void migrateShowModes250() {
        if (sp.getBoolean("showModes250_v1", false)) return;
        sp.edit().putString("showDstar", "0")
                .putString("showYsf", "0")
                .putString("modeYsf", "0")
                .putBoolean("showModes250_v1", true).apply();
    }

    private void migrateIndependentIdentity() {
        if (sp.getBoolean("independentIdentity_v2", false)) return;
        String cs = get("callsign", "").trim();
        String id = get("dmrid", "").trim();
        String es = get("essid", "").trim();
        android.content.SharedPreferences.Editor e = sp.edit();
        if (sp.getString("dstarCallsign", "").trim().isEmpty()) e.putString("dstarCallsign", cs);
        if (sp.getString("ysfCallsign", "").trim().isEmpty()) e.putString("ysfCallsign", cs);
        if (sp.getString("bmCallsign", "").trim().isEmpty()) e.putString("bmCallsign", cs);
        if (sp.getString("bmId", "").trim().isEmpty() || "0".equals(sp.getString("bmId", "").trim())) e.putString("bmId", id);
        if (sp.getString("bmEssid", "").trim().isEmpty()) e.putString("bmEssid", es.isEmpty() ? "1" : es);
        e.putBoolean("independentIdentity_v2", true).apply();
    }

    /** Network modes are enabled and auto-connected by default after installation/update. */
    private void migrateAutoConnectModes() {
        if (sp.getBoolean("autoConnectModes_v1", false)) return;
        sp.edit()
                .putString("showDigital", "1")
                .putString("dmrAuto", "1")
                .putString("autoDigital", "1")
                .putString("showYsf", "1")
                .putString("modeYsf", "1")
                .putString("autoYsf", "1")
                .putBoolean("autoConnectModes_v1", true)
                .apply();
    }

    /** 公开版只开放 DMR、P25、YSF、D-Star、NXDN、Net；数字中继和模拟互联不启动。 */
    private void migratePublicEditionDefaults() {
        if (!BuildConfig.PUBLIC_EDITION || sp.getBoolean("publicEditionDefaults_v1", false)) return;
        sp.edit()
                .putString("showDigital", "0").putString("modeDigital", "0").putString("autoDigital", "0")
                .putString("showAnalog", "0").putString("modeAnalog", "0").putString("autoAnalog", "0")
                .putString("showBm", "1").putString("autoBm", "1")
                .putString("showP25", "1").putString("modeP25", "1").putString("autoP25", "1")
                .putString("showYsf", "1").putString("modeYsf", "1").putString("autoYsf", "1")
                .putString("showDstar", "1").putString("autoDstar", "1")
                .putString("showNxdn", "1").putString("modeNxdn", "1").putString("autoNxdn", "1")
                .putString("showNetwork", "1").putString("modeNetwork", "1").putString("autoNetwork", "1")
                .putBoolean("publicEditionDefaults_v1", true).apply();
    }

    /** Ensure upgraded installs get one-time YSF auto-login default when YSF is visible. */
    private void migrateYsfAutoLoginV2() {
        if (sp.getBoolean("ysfAutoLogin_v2", false)) return;
        boolean visible = "1".equals(sp.getString("showYsf", "1"));
        android.content.SharedPreferences.Editor e = sp.edit();
        if (visible) e.putString("autoYsf", "1").putString("modeYsf", "1");
        e.putBoolean("ysfAutoLogin_v2", true).apply();
    }

    /** [3.2.57] 网络对讲不再默认开启：两版统一一次性关闭，用户可在工作模式里自行勾选。 */
    private void migrateNetworkDefaultOffV1() {
        if (sp.getBoolean("netDefaultOff_v1", false)) return;
        sp.edit()
                .putString("showNetwork", "0")
                .putString("modeNetwork", BuildConfig.HYTERA_EDITION ? "1" : "0")
                .putString("autoNetwork", "0")
                .putBoolean("netDefaultOff_v1", true).apply();
    }

    /** [3.2.74] Do not start every configured digital mode at boot; users opt in per mode. */
    /** SIP is a separately enabled service and must stay off until the user configures it. */
    private void migrateSipDefaultsV1() {
        if (sp.getBoolean("sipDefaultsV1", false)) return;
        if (!sp.contains("sipEnabled")) sp.edit().putString("sipEnabled", "0").apply();
        sp.edit().putBoolean("sipDefaultsV1", true).apply();
    }

    private void migratePowerDefaultsV1() {
        if (sp.getBoolean("powerDefaultsV1", false)) return;
        sp.edit()
                .putString("autoDigital", "0")
                .putString("autoAnalog", "0")
                .putString("autoBm", "0")
                .putString("autoDstar", "0")
                .putString("autoYsf", "0")
                .putString("autoP25", "0")
                .putString("autoNxdn", "0")
                .putString("autoNrl", "0")
                .putString("autoNetwork", "1")
                .putBoolean("powerDefaultsV1", true).apply();
    }

    /** [3.2.68] Correct the install defaults: network room visible and auto-login on, NRL remains opt-in. */
    private void migrateNetworkDefaultsV2() {
        if (sp.getBoolean("netDefaultsV2", false)) return;
        sp.edit()
                .putString("showNetwork", "1")
                .putString("modeNetwork", BuildConfig.HYTERA_EDITION ? "1" : "0")
                .putString("autoNetwork", "1")
                .putString("showNrl", "0")
                .putString("modeNrl", "0")
                .putString("autoNrl", "0")
                .putBoolean("netDefaultsV2", true).apply();
    }

    /** Association edition: enable all eight work modes once; users may turn them off afterwards. */
    private void migrateAssociationAllModesV1() {
        if (BuildConfig.PUBLIC_EDITION || sp.getBoolean("associationAllModes_v1", false)) return;
        sp.edit()
                .putString("showDigital", "1").putString("modeDigital", "1").putString("autoDigital", "1")
                .putString("dmrAuto", "1")
                .putString("showAnalog", "1").putString("modeAnalog", "1").putString("autoAnalog", "1")
                .putString("showBm", "1").putString("autoBm", "1")
                .putString("showDstar", "1").putString("autoDstar", "1")
                .putString("showYsf", "1").putString("modeYsf", "1").putString("autoYsf", "1")
                .putString("showP25", "1").putString("modeP25", "1").putString("autoP25", "1")
                .putString("showNxdn", "1").putString("modeNxdn", "1").putString("autoNxdn", "1")
                .putString("showNetwork", "1").putString("modeNetwork", "1").putString("autoNetwork", "1")
                .putBoolean("associationAllModes_v1", true).apply();
    }

    private void migrateUnifiedIdentity() {
        if (sp.getBoolean("unifiedIdentity_v1", false)) return;
        String cs = sp.getString("callsign", "");
        if (cs == null || cs.trim().isEmpty()) {
            cs = firstNonEmpty(sp.getString("dstarCallsign", ""), sp.getString("ysfCallsign", ""),
                    sp.getString("bmCallsign", ""));
            if (!cs.isEmpty()) sp.edit().putString("callsign", cs).apply();
        }
        String id = sp.getString("dmrid", "");
        if (id == null || id.trim().isEmpty() || "0".equals(id.trim())) {
            id = firstNonEmpty(sp.getString("bmId", ""), sp.getString("dstarId", ""));
            if (!id.isEmpty()) sp.edit().putString("dmrid", id).apply();
        }
        String es = sp.getString("essid", "");
        if (es == null || es.trim().isEmpty()) {
            es = firstNonEmpty(sp.getString("bmEssid", ""));
            if (!es.isEmpty()) sp.edit().putString("essid", es).apply();
        }
        sp.edit().putBoolean("unifiedIdentity_v1", true).apply();
    }

    /** Change historical NXDN defaults to the renamed ARCH NXDN-46002 reflector, but keep an explicit custom selection. */
    private void migrateNxdnDefault46002() {
        if (sp.getBoolean("nxdnDefault46002_v1", false)) return;
        String cur = sp.getString("nxdnServer", "").trim();
        android.content.SharedPreferences.Editor e = sp.edit();
        if (cur.isEmpty() || "NXDN-46001".equalsIgnoreCase(cur) || "NXDN-46045".equalsIgnoreCase(cur)) e.putString("nxdnServer", "NXDN-46002");
        e.putBoolean("nxdnDefault46002_v1", true).apply();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }
    private void migrateBmDefaults() {
        if (OLD_BM_TGS.equals(sp.getString("bmTalkgroups", ""))) {
            sp.edit().putString("bmTalkgroups", DEFAULT_BM_TGS)
                    .putString("bmStaticTgs", "460451,46001,46002,46020")
                    .putString("bmTg", "460451").apply();
        }
    }
    private void migrateAprsComment() {
        String v = sp.getString("aprsComment", "");
        if (v == null || v.isEmpty() || OLD_APRS_COMMENT.equals(v)) {
            sp.edit().putString("aprsComment", DEFAULT_APRS_COMMENT).apply();
        }
    }
    private void migrateDstarModuleA() {
        if (sp.contains("dstarModule")) return;
        sp.edit().putString("dstarModule", "A").apply();
    }

    private void migrateDstarModule() {
        if (sp.getBoolean("dstarModuleC_v1", false)) return;
        String server = sp.getString("dstarServer", "XLXHLJ");
        String module = sp.getString("dstarModule", "");
        if ("XLXHLJ".equalsIgnoreCase(server) && ("A".equalsIgnoreCase(module) || "H".equalsIgnoreCase(module) || module == null || module.isEmpty())) {
            sp.edit().putString("dstarModule", "C").putBoolean("dstarModuleC_v1", true).apply();
        } else {
            sp.edit().putBoolean("dstarModuleC_v1", true).apply();
        }
    }

    private void migrateDstarModuleF() {
        if (sp.getBoolean("dstarModuleF_v1", false)) return;
        String server = sp.getString("dstarServer", "XLXHLJ");
        String module = sp.getString("dstarModule", "");
        if ("XLXHLJ".equalsIgnoreCase(server) && (module == null || module.isEmpty() || "C".equalsIgnoreCase(module))) {
            sp.edit().putString("dstarModule", "F").putBoolean("dstarModuleF_v1", true).apply();
        } else {
            sp.edit().putBoolean("dstarModuleF_v1", true).apply();
        }
    }

    public String get(String k, String def) { return sp.getString(k, def); }
    public void set(String k, String v) { sp.edit().putString(k, v).apply(); }
    public boolean setSync(String k, String v) { return sp.edit().putString(k, v).commit(); }

    private int i(String k, int def) {
        try { return Integer.parseInt(get(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }
    private float f(String k, float def) {
        try { return Float.parseFloat(get(k, String.valueOf(def)).trim()); }
        catch (Exception e) { return def; }
    }

    public String host()  { return get("host", "hljrsa.cn").trim(); }
    public int    port()  { return i("port", 62030); }
    public String callsign() { return get("callsign", "").trim().toUpperCase(java.util.Locale.US); }
    public int    dmrId()    { return i("dmrid", 0); }
    public int    essid()    { int v = i("essid", 1); return v < 0 ? 0 : (v > 99 ? 99 : v); }
    public String password() { return get("password", ""); }

    /** Save the unified identity used by D-Star, YSF, P25, NXDN, DMR and the association account. */
    public void saveUnifiedIdentity(String callsign, int dmrId, int essid, String assocPassword,
                                    String bmPassword, int nxdnId) {
        String cs = callsign == null ? "" : callsign.trim().toUpperCase(java.util.Locale.US);
        int id = Math.max(0, dmrId);
        int es = Math.max(0, Math.min(99, essid));
        String esText = String.format(java.util.Locale.US, "%02d", es);
        android.content.SharedPreferences.Editor e = sp.edit();
        e.putString("callsign", cs);
        e.putString("dmrid", String.valueOf(id));
        e.putString("essid", esText);
        e.putString("password", assocPassword == null ? "" : assocPassword);
        e.putString("bmCallsign", cs);
        e.putString("bmId", String.valueOf(id));
        e.putString("bmEssid", esText);
        e.putString("bmPassword", bmPassword == null ? "" : bmPassword);
        e.putString("dstarCallsign", cs);
        e.putString("ysfCallsign", cs);
        e.putString("p25Callsign", cs);
        e.putString("nxdnCallsign", cs);
        if (nxdnId > 0 && nxdnId <= 65535) e.putString("nxdnId", String.valueOf(nxdnId));
        else e.remove("nxdnId");
        e.apply();
    }

    public boolean modeDigital() { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("modeDigital", "1")); }
    public boolean modeAnalog()  { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("modeAnalog", "1")); }
    public boolean showNetwork() { return "1".equals(get("showNetwork", "1")); }
    public boolean modeNetwork() { return "1".equals(get("modeNetwork", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    /** NRL is an optional second network stack; it is hidden until the user enables it. */
    public boolean showNrl() { return "1".equals(get("showNrl", "0")); }
    public boolean modeNrl() { return "1".equals(get("modeNrl", "0")); }
    public String nrlServerKey() { return get("nrlServerKey", NrlServers.ARCH_KEY).trim(); }
    public String nrlServerName() { return get("nrlServerName", NrlServers.ARCH.name).trim(); }
    public String nrlServerHost() { return get("nrlServerHost", NrlServers.ARCH.host).trim(); }
    public int nrlServerPort() { int v = i("nrlServerPort", NrlServers.ARCH.port); return v <= 0 || v > 65535 ? NrlServers.ARCH.port : v; }
    public int nrlGroup() { return nrlGroupFor(nrlServerKey(), true); }
    public void setNrlGroup(int group) {
        int safe = group <= 0 ? 1004 : group;
        String key = nrlServerKey();
        sp.edit()
                .putString("nrlGroup:" + key, String.valueOf(safe))
                .putString("nrlGroup", String.valueOf(safe))
                .apply();
    }
    public String nrlCustomServers() { return get("nrlCustomServers", ""); }
    /** NRL defaults to the same callsign/ID as ARCH; the software SSID is fixed by protocol. */
    public boolean nrlUseGlobal(){ return "1".equals(get("nrlUseGlobal", "1")); }
    public String nrlCallsign(){ String v = nrlUseGlobal() ? callsign() : get("nrlCallsign", callsign()).trim().toUpperCase(java.util.Locale.US); return v; }
    public int nrlDmrId(){ if (nrlUseGlobal()) return dmrId(); try { return Integer.parseInt(get("nrlDmrId", String.valueOf(dmrId())).trim()); } catch (Throwable t) { return dmrId(); } }
    /** The official NRL Android client uses software SSID 107 and DevModel 107. */
    public int nrlSsid(){ return NrlClient.SOFTWARE_DEVICE; }
    /** Password used for the selected NRL server, not the ARCH account password. */
    public String nrlPassword(){ return nrlPasswordFor(nrlServerKey()); }

    public String nrlPasswordFor(String serverKey) {
        String key = serverKey == null ? "" : serverKey.trim();
        String scoped = "nrlPassword:" + key;
        if (sp.contains(scoped)) return get(scoped, "");
        return key.equals(nrlServerKey()) ? get("nrlServerPassword", "") : "";
    }

    public void setNrlPassword(String password) {
        String value = password == null ? "" : password;
        String key = nrlServerKey();
        sp.edit()
                .putString("nrlPassword:" + key, value)
                .putString("nrlServerPassword", value)
                .apply();
    }

    public void saveNrlSelectionState() {
        String key = nrlServerKey();
        sp.edit()
                .putString("nrlPassword:" + key, nrlPassword())
                .putString("nrlGroup:" + key, String.valueOf(nrlGroup()))
                .apply();
    }

    public void selectNrlServer(String key, String name, String host, int port, String fallbackPassword) {
        String selected = key == null ? "" : key.trim();
        saveNrlSelectionState();
        String scopedPassword = "nrlPassword:" + selected;
        String scopedGroup = "nrlGroup:" + selected;
        String password = sp.contains(scopedPassword) ? get(scopedPassword, "") : (fallbackPassword == null ? "" : fallbackPassword);
        int group = nrlGroupFor(selected, false);
        sp.edit()
                .putString("nrlServerKey", selected)
                .putString("nrlServerName", name == null ? "" : name)
                .putString("nrlServerHost", host == null ? "" : host)
                .putString("nrlServerPort", String.valueOf(port))
                .putString(scopedPassword, password)
                .putString(scopedGroup, String.valueOf(group))
                .putString("nrlServerPassword", password)
                .putString("nrlGroup", String.valueOf(group))
                .apply();
    }

    private int nrlGroupFor(String serverKey, boolean allowLegacyFallback) {
        String key = serverKey == null ? "" : serverKey.trim();
        int scoped = i("nrlGroup:" + key, -1);
        if (scoped > 0) return scoped;
        if (allowLegacyFallback && key.equals(nrlServerKey())) {
            int legacy = i("nrlGroup", 1004);
            return legacy > 0 ? legacy : 1004;
        }
        return 1004;
    }
    public boolean modeYsf() { return "1".equals(get("modeYsf", "1")); }
    public boolean showDigital() { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("showDigital", "1")); }
    public boolean showAnalog()  { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("showAnalog", "1")); }
    public boolean showBm()      { return "1".equals(get("showBm", "1")); }
    public boolean showDstar()   { return "1".equals(get("showDstar", BuildConfig.PUBLIC_EDITION ? "0" : "1")); }
    public boolean showYsf()     { return "1".equals(get("showYsf", BuildConfig.PUBLIC_EDITION ? "0" : "1")); }
    public boolean modeP25()      { return "1".equals(get("modeP25", "0")); }
    public boolean showP25()      { return "1".equals(get("showP25", BuildConfig.PUBLIC_EDITION ? "0" : "1")); }
    public boolean modeNxdn()     { return "1".equals(get("modeNxdn", "0")); }
    public boolean showNxdn()     { return "1".equals(get("showNxdn", BuildConfig.PUBLIC_EDITION ? "0" : "1")); }
    public String nxdnServer()    { return get("nxdnServer", "NXDN-46002").trim(); }
    public void setNxdnServer(String id) { set("nxdnServer", id == null || id.trim().isEmpty() ? "NXDN-46002" : id.trim()); }
    public String p25Server()     { return get("p25Server", "P25-46002").trim(); }
    public void setP25Server(String id) { set("p25Server", id == null || id.trim().isEmpty() ? "P25-46002" : id.trim()); }
    public String dstarServer()  { return get("dstarServer", "XLXHLJ"); }
    public String dstarModule()  { String v = get("dstarModule", "A").trim().toUpperCase(java.util.Locale.US); return v.isEmpty() ? "A" : v.substring(0, 1); }
    public String dstarCallsign() { String v=get("dstarCallsign", callsign()); return v == null || v.trim().isEmpty() ? callsign() : v.trim().toUpperCase(java.util.Locale.US); }
    public String dstarUserText() {
        String v = get("dstarUserText", "").trim();
        if (v.isEmpty()) v = "ARCH APP";
        if (v.length() > 20) v = v.substring(0, 20);
        return v;
    }
    public String ysfCallsign() { String v=get("ysfCallsign", callsign()); return v == null || v.trim().isEmpty() ? callsign() : v.trim().toUpperCase(java.util.Locale.US); }
    public int ysfDgId() { int v=i("ysfDgId", 0); return v >= 0 && v <= 127 ? v : 0; }
    public void setYsfDgId(int dgId) { set("ysfDgId", String.valueOf(Math.max(0, Math.min(127, dgId)))); }
    public String ysfOptions() { String v=get("ysfOptions", ""); return v.length() > 36 ? v.substring(0, 36) : v; }
    public void setYsfOptions(String options) { String v=options == null ? "" : options.trim(); if(v.length() > 36) v=v.substring(0,36); set("ysfOptions", v); }
    private String optionalModeCallsign(String key) {
        String v=get(key, "").trim();
        if (!v.isEmpty()) return v.toUpperCase(java.util.Locale.US);
        v=callsign();
        if (!v.isEmpty()) return v;
        v=bmCallsign();
        return v == null ? "" : v.trim().toUpperCase(java.util.Locale.US);
    }
    public String p25Callsign() { return optionalModeCallsign("p25Callsign"); }
    public String nxdnCallsign() { return optionalModeCallsign("nxdnCallsign"); }
    /** NXDN uses its own 16-bit radio ID, separate from the 7-digit DMR ID. */
    public int nxdnId() { int v=i("nxdnId",0); return v>0 && v<=65535 ? v : 0; }

    public int    tg()       { return i("tg", 10); }
    public int    slot()     { return i("tgSlot", 1); }
    public int    cc()       { return 7; }
    public boolean dualWatch() { return "1".equals(get("dualWatch", "0")); }
    public boolean fmoAuto() { return "1".equals(get("fmoAuto", "1")); }
    public boolean dmrAuto() { return "1".equals(get("dmrAuto", "1")); }
    public boolean bothListen() { return "1".equals(get("bothListen", "1")); }
 /** 多路监听开关。 */
    public boolean multiListen() { return "1".equals(get("multiListen", "0")); }
    public int     monitorTg() { return dualWatch() ? i("monitorTg", 0) : 0; }
    public String  talkgroupsJson() { return get("talkgroups", "[{\"id\":460451,\"slot\":1,\"name\":\"哈市组\"},{\"id\":46002,\"slot\":2,\"name\":\"二区组\"},{\"id\":46020,\"slot\":1,\"name\":\"广州组\"},{\"id\":46001,\"slot\":2,\"name\":\"全国组\"}]"); }
    public String netRoom() { return get("netRoom", "room1"); }

    // ── 开机自动连接（三个模式各自可勾）──
    public boolean autoDigital() { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("autoDigital", "1")); }
    public boolean autoAnalog()  { return !BuildConfig.PUBLIC_EDITION && "1".equals(get("autoAnalog", "1")); }
    public boolean autoNetwork() { return "1".equals(get("autoNetwork", "1")); }
    public boolean autoNrl() { return "1".equals(get("autoNrl", "0")); }
    public boolean autoBm()      { return "1".equals(get("autoBm", "1")); }
    public boolean autoDstar()   { return "1".equals(get("autoDstar", "1")); }
    public boolean autoYsf()     { return "1".equals(get("autoYsf", "1")); }
    public boolean autoP25()     { return "1".equals(get("autoP25", "1")); }
    public boolean autoNxdn()    { return "1".equals(get("autoNxdn", "1")); }
    public boolean autoConnectAny() { return autoDigital() || autoAnalog() || autoNetwork() || autoNrl() || autoBm() || autoDstar() || autoYsf() || autoP25() || autoNxdn(); }

    // ── 音频 ──
    public int rxGainDb() { return clamp(i("rxDb", 0)); }
    public int txGainDb() { return clamp(i("txDb", 0)); }
    public boolean rxAgc() { return "1".equals(get("rxAgc", "1")); }
    public boolean txAgc() { return "1".equals(get("txAgc", "1")); }
    public int rxAgcTargetDb() { return clampTarget(i("rxAgcTargetDb", 0)); }
    public int txAgcTargetDb() { return clampTarget(i("txAgcTargetDb", 0)); }
    public boolean rxVolumeBoost() { return "1".equals(get("rxVolumeBoost", "0")); }
    public boolean rxNoiseReduction() { return "1".equals(get("rxNoiseReduction", "0")); }
    public boolean txNoiseReduction() { return "1".equals(get("txNoiseReduction", "0")); }
    public String voiceTuning() { return get("voiceTuning", "clarity"); }
    public String listeningTuning() { return get("listeningTuning", "flat"); }

    /** Legacy master switch retained for old callers. */
    public boolean agc()      { return rxAgc() && txAgc(); }
    /** FMO/模拟互联语音增强（系统降噪+AGC+回声消除），默认开启。 */
    public boolean fmoEnhance(){ return "1".equals(get("fmoEnhance", "1")); }
    public boolean denoise()  { return rxNoiseReduction(); }
    public boolean callMute() { return "1".equals(get("callMute", "0")); }
    public boolean pttTone() { return "1".equals(get("pttTone", "1")); }
    private static int clamp(int v) { return v < -30 ? -30 : (v > 30 ? 30 : v); }
    private static int clampTarget(int v) { return v < -12 ? -12 : (v > 12 ? 12 : v); }

    public void resetAudioDefaults() {
        set("rxDb", "0");
        set("txDb", "0");
        set("rxAgc", "1");
        set("txAgc", "1");
        set("rxAgcTargetDb", "0");
        set("txAgcTargetDb", "0");
        set("rxVolumeBoost", "0");
        set("rxNoiseReduction", "0");
        set("txNoiseReduction", "0");
        set("voiceTuning", "clarity");
        set("listeningTuning", "flat");
        set("fmoEnhance", "1");
        set("callMute", "0");
        set("pttTone", "1");
        // Keep the legacy switch synchronized for older callers.
        set("agc", "1");
        set("denoise", "0");
    }

    public String audioRoute() { return get("audioRoute", "speaker"); }
    public boolean isEarpiece() { return "earpiece".equals(audioRoute()); }
    public boolean autoReconnect() { return "1".equals(get("autoReconnect", "1")); }
    public boolean bootAutoStart() { return "1".equals(get("bootAutoStart", "1")); }
    public void setBootAutoStart(boolean on) { set("bootAutoStart", on ? "1" : "0"); }
    public boolean callsignEnhanced() { return "1".equals(get("callsignEnhanced", "0")); }
    public void setCallsignEnhanced(boolean on) { set("callsignEnhanced", on ? "1" : "0"); }
    public boolean userExited() { return "1".equals(get("userExited", "0")); }
    public String stableDeviceId() {
        String value = get("stableDeviceId", "");
        if (value != null && !value.trim().isEmpty()) return value.trim();
        value = "arch-" + java.util.UUID.randomUUID().toString().replace("-", "");
        setSync("stableDeviceId", value);
        return value;
    }
    public void setUserExited(boolean exited) { setSync("userExited", exited ? "1" : "0"); }
    public boolean showNotification() { return "1".equals(get("showNotify", "1")); }
    public boolean overlayPtt() { return "1".equals(get("overlayPtt", "0")); }

    public String theme()     { return get("theme", "light"); }
    public boolean dark()     { return "dark".equals(theme()); }
    public boolean keepScreenOn() { return "1".equals(get("keepScreen", "0")); }

    public float  gain()     { return 1.0f; }

    // ── BrandMeister（BM）独立身份 ──
    // DMR direct uses the single account identity. Legacy bm* fields are migrated, not used.
    public String bmCallsign() { return callsign(); }
    public int bmId() { return dmrId(); }
    public int bmEssid() { return essid(); }
    public String bmPassword() { return get("bmPassword", ""); }
    public String bmServerKey(){ return get("bmServerKey", "4602"); }
    public String bmServerName(){ return get("bmServerName", BmServers.name(bmServerKey())); }
    public String bmServerHost(){ return get("bmServerHost", BmServers.host(bmServerKey())); }
    public int    bmServerPort(){ return i("bmServerPort", BmServers.port(bmServerKey())); }
    public void setBmServer(String key) {
        String[] s = BmServers.find(key);
        set("bmServerKey", s[0]);
        set("bmServerName", s[1]);
        set("bmServerHost", s[2]);
        set("bmServerPort", s[3]);
    }

    // Independent DMR networks use their own ID/ESSID/password. BM keeps the legacy unified account.
    public int dmrServerId() {
        String key = bmServerKey();
        if ("860".equals(key)) return i("dmr860Id", 0);
        if ("46020".equals(key)) return i("dmr46020Id", 0);
        return bmId();
    }
    public int dmrServerEssid() {
        String key = bmServerKey();
        int v;
        if ("860".equals(key)) v = i("dmr860Essid", 0);
        else if ("46020".equals(key)) v = i("dmr46020Essid", 0);
        else return bmEssid();
        return v < 0 ? 0 : (v > 99 ? 99 : v);
    }
    public String dmrServerPassword() {
        String key = bmServerKey();
        if ("860".equals(key)) return get("dmr860Password", "");
        if ("46020".equals(key)) return get("dmr46020Password", "");
        return bmPassword();
    }
    public int dmr860Id() { return i("dmr860Id", 0); }
    public int dmr860Essid() { int v=i("dmr860Essid",0); return v < 0 ? 0 : (v > 99 ? 99 : v); }
    public String dmr860Password() { return get("dmr860Password", ""); }
    public int dmr46020Id() { return i("dmr46020Id", 0); }
    public int dmr46020Essid() { int v=i("dmr46020Essid",0); return v < 0 ? 0 : (v > 99 ? 99 : v); }
    public String dmr46020Password() { return get("dmr46020Password", ""); }
    public void saveDmrServerIdentities(int id860, int essid860, String pass860,
                                        int id46020, int essid46020, String pass46020) {
        android.content.SharedPreferences.Editor e = sp.edit();
        if (id860 > 0) e.putString("dmr860Id", String.valueOf(id860)); else e.remove("dmr860Id");
        e.putString("dmr860Essid", String.format(java.util.Locale.US, "%02d", Math.max(0, Math.min(99, essid860))));
        e.putString("dmr860Password", pass860 == null ? "" : pass860);
        if (id46020 > 0) e.putString("dmr46020Id", String.valueOf(id46020)); else e.remove("dmr46020Id");
        e.putString("dmr46020Essid", String.format(java.util.Locale.US, "%02d", Math.max(0, Math.min(99, essid46020))));
        e.putString("dmr46020Password", pass46020 == null ? "" : pass46020);
        e.apply();
    }
    public String pttMode() { return get("pttMode", "hold"); }
    public int pttTimeout() { int v = i("pttTimeout", 300); return v < 30 ? 30 : (v > 600 ? 600 : v); }
    public boolean pttBusyLock() { return "1".equals(get("pttBusyLock", "1")); }
    public boolean pttWakeFocus() { return "1".equals(get("pttWakeFocus", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    public boolean pttOnScreen() { return "1".equals(get("pttOnScreen", "1")); }
    public boolean pttBackground() { return "1".equals(get("pttBackground", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    public boolean alertCalls() { return "1".equals(get("alertCalls", "0")); }
    public boolean alertConnection() { return "1".equals(get("alertConnection", "0")); }
    public boolean alertVibrate() { return "1".equals(get("alertVibrate", "0")); }
    /** 海能达原生来电/通话震动，可在设置中关闭。 */
    public boolean hyteraIncomingVibrate() { return "1".equals(get("hyteraIncomingVibrate", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    /** 海能达信道旋钮按当前模式切换房间/通话组。 */
    public boolean hyteraKnobEnabled() { return "1".equals(get("hyteraKnobEnabled", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    public boolean aprsEnabled() { return "1".equals(get("aprsEnabled", "0")); }
    public String aprsCallsign() { return get("aprsCallsign", callsign()); }
    public String aprsSsid() { return get("aprsSsid", "9"); }
    public String aprsSymbol() { return get("aprsSymbol", ">"); }
    public String aprsServer() { String v = get("aprsServer", "asia.aprs2.net").trim(); return (v.isEmpty() || "aprs.cn".equalsIgnoreCase(v)) ? "asia.aprs2.net" : v; }
    public int aprsPort() { return i("aprsPort", 14580); }
    public String aprsPasscode() { return get("aprsPasscode", "-1"); }
    public int aprsInterval() { int v = i("aprsInterval", 60); return v < 30 ? 30 : (v > 3600 ? 3600 : v); }
    public String aprsComment() { return get("aprsComment", DEFAULT_APRS_COMMENT); }

    public boolean externalPttEnabled(){ return "1".equals(get("extPttEnabled", BuildConfig.HYTERA_EDITION ? "1" : "0")); }
    // ===== [3.2.39] 网络收音机 =====
    public boolean showNetRadio(){
        if (BuildConfig.PUBLIC_EDITION) return false;
        return "1".equals(get("showNetRadio", "0"));
    }
    /** pause=来信号暂停 / duck=压低混音 / off=不处理 */
    public String radioInterruptMode(){ return "off"; }
    public String radioFavorites(){ return get("radioFavorites", ""); }
    public boolean radioIsFavorite(String id){
        if (id == null || id.isEmpty()) return false;
        for (String x : radioFavorites().split("\n")) if (id.equals(x.trim())) return true;
        return false;
    }
    public void radioToggleFavorite(String id){
        if (id == null || id.isEmpty()) return;
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String x : radioFavorites().split("\n")) if (x != null && !x.trim().isEmpty() && !id.equals(x.trim())) list.add(x.trim());
        if (!radioIsFavorite(id)) list.add(0, id);
        StringBuilder sb = new StringBuilder();
        for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
        set("radioFavorites", sb.toString());
    }
    public String radioRecent(){ return get("radioRecent", ""); }
    /** [3.2.47] 从最近播放里移除一条（长按/滑动删除用）。 */
    public void radioRemoveRecent(String id){
        if (id == null || id.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String x : radioRecent().split("\n")) {
            x = x == null ? "" : x.trim();
            if (x.isEmpty() || id.equals(x)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        set("radioRecent", sb.toString());
    }
    public void radioMarkRecent(String id){
        if (id == null || id.isEmpty()) return;
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        list.add(id);
        for (String x : radioRecent().split("\n")) {
            x = x == null ? "" : x.trim();
            if (!x.isEmpty() && !id.equals(x)) list.add(x);
            if (list.size() >= 12) break;
        }
        StringBuilder sb = new StringBuilder();
        for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
        set("radioRecent", sb.toString());
    }

    /** [3.2.38] 设置页外置 PTT 使用最后一次语音页面的模式，而不是默认数字中继。 */
    public int lastVoiceMode(){ int v = i("lastVoiceMode", 3); return (v < 0 || v > 8) ? 3 : v; }
    public int externalPttKeyCode(){ return i("extPttKeyCode", 0); }
    public String externalPttKeyName(){ return get("extPttKeyName", "未设置"); }

    // ===== [3.2.30] 设备按键（硬件 PTT）增强 =====
    public int externalPttReleaseKeyCode(){ return i("extPttReleaseKeyCode", 0); }
    public String externalPttReleaseKeyName(){ return get("extPttReleaseKeyName", ""); }
    /** 绑定的键是否自带"松开"事件；false = 只发按下（要么另绑松开键，要么用点按开关） */
    public boolean externalPttKeyHasRelease(){ return "1".equals(get("extPttKeyHasRelease", "1")); }
    public String externalPttKeySource(){ return get("extPttKeySource", ""); }
    /** APP 不在前台/锁屏时，设备按键是否也能发射 */
    public boolean externalPttBackground(){ return "1".equals(get("extPttBackground", "1")); }
    /** hold=按住发射（默认） / toggle=点按开关 */
    public String externalPttKeyMode(){ return get("extPttKeyMode", "hold"); }
    public String extKeyLog(){ return get("extKeyLog", ""); }
    public String extKeyLogSource(){ return get("extKeyLogSource", ""); }
    public int extKeyLogCode(){ return i("extKeyLogCode", 0); }
    public long extKeyLogAt(){ try { return Long.parseLong(get("extKeyLogAt", "0")); } catch (Throwable t) { return 0L; } }
    /** 最近按键诊断日志：键码|动作|来源|时间（最多 12 条） */
    public void extKeyLogAdd(int code, int action, String src){
        try{
            String line = String.format(java.util.Locale.US, "%d|%s|%s|%s", code,
                    action == android.view.KeyEvent.ACTION_DOWN ? "DOWN" : "UP",
                    src, new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
            String cur = extKeyLog();
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            if (cur != null) for (String x : cur.split("\n")) if (x != null && x.trim().length() > 0) list.add(x.trim());
            list.add(line);
            while (list.size() > 12) list.remove(0);
            StringBuilder sb = new StringBuilder();
            for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
            set("extKeyLog", sb.toString());
            set("extKeyLogSource", String.valueOf(src == null ? "" : src));
            set("extKeyLogCode", String.valueOf(code));
            set("extKeyLogAt", String.valueOf(System.currentTimeMillis()));
        }catch(Throwable ignored){}
    }

    /** [3.3.0] 无障碍 KeyEvent 仅作为老设备兼容兜底，默认关闭。 */
    public boolean externalPttA11yFallback(){ return "1".equals(get("extPttA11yFallback", "0")); }

    /**
     * [3.3.0] 用户手动追加的广播映射。每行：D|action、U|action 或 T|action。
     * 内置广播表在 ArchPttBroadcast 中，不写进这里；这里只存用户追加项。
     */
    public String extBroadcastMap(){ return get("extBroadcastMap", ""); }

    public int extBroadcastCount(){
        int n = 0;
        String s = extBroadcastMap();
        if (s == null || s.trim().isEmpty()) return 0;
        for (String x : s.split("\n")) {
            String line = x == null ? "" : x.trim();
            int p = line.indexOf('|');
            if (p > 0 && p < line.length() - 1) n++;
        }
        return n;
    }

    public void extBroadcastAdd(String type, String action){
        String a = action == null ? "" : action.trim();
        if (a.length() == 0) return;
        String ty = type == null ? "T" : type.trim().toUpperCase(java.util.Locale.US);
        if (!"D".equals(ty) && !"U".equals(ty) && !"T".equals(ty)) ty = "T";
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        String cur = extBroadcastMap();
        if (cur != null) {
            for (String x : cur.split("\n")) {
                String line = x == null ? "" : x.trim();
                int p = line.indexOf('|');
                if (p <= 0 || p >= line.length() - 1) continue;
                String oldAction = line.substring(p + 1).trim();
                if (!a.equals(oldAction)) list.add(line);
            }
        }
        list.add(ty + "|" + a);
        StringBuilder sb = new StringBuilder();
        for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
        set("extBroadcastMap", sb.toString());
    }

    public void extBroadcastRemove(String action){
        String a = action == null ? "" : action.trim();
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        String cur = extBroadcastMap();
        if (cur != null) {
            for (String x : cur.split("\n")) {
                String line = x == null ? "" : x.trim();
                int p = line.indexOf('|');
                if (p <= 0 || p >= line.length() - 1) continue;
                String oldAction = line.substring(p + 1).trim();
                if (!a.equals(oldAction)) list.add(line);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
        set("extBroadcastMap", sb.toString());
    }

    public void extBroadcastClear(){ set("extBroadcastMap", ""); }

    /** 广播诊断日志：action|来源|时间（最多 20 条）。 */
    public String extBroadcastLog(){ return get("extBroadcastLog", ""); }

    public void extBroadcastLogAdd(String action, String src){
        try{
            String a = action == null ? "" : action.trim();
            if (a.length() == 0) return;
            String line = a + "|" + (src == null ? "" : src) + "|"
                    + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            String cur = extBroadcastLog();
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            if (cur != null) for (String x : cur.split("\n")) if (x != null && x.trim().length() > 0) list.add(x.trim());
            list.add(line);
            while (list.size() > 20) list.remove(0);
            StringBuilder sb = new StringBuilder();
            for (String x : list) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
            set("extBroadcastLog", sb.toString());
        }catch(Throwable ignored){}
    }

    public String extBroadcastLogLast(){
        String s = extBroadcastLog();
        if (s == null || s.trim().isEmpty()) return "";
        String[] a = s.trim().split("\n");
        return a.length == 0 ? "" : a[a.length - 1];
    }

    // ===== [3.2.30] 界面字号（自动缩放之外再乘一层，适配各种安卓对讲机屏幕）=====
    /** auto / small / normal / large / xlarge */
    public String uiFontMode(){ return get("uiFontMode", "auto"); }
    /** 返回额外倍率；auto 返回 0（表示"不额外乘"，交给自动缩放） */
    public float uiFontFactor(){
        String m = uiFontMode();
        if ("small".equals(m)) return 0.90f;
        if ("normal".equals(m)) return 1.00f;
        if ("large".equals(m)) return 1.12f;
        if ("xlarge".equals(m)) return 1.25f;
        return 0f;
    }
    public String pttTargetMode(){ return get("pttTargetMode", "auto"); }
    public boolean contactAutoDownloaded(int dmrId, int essid) { return "1".equals(get("contacts.auto." + dmrId + "." + essid, "0")); }
    public void markContactAutoDownloaded(int dmrId, int essid) { set("contacts.auto." + dmrId + "." + essid, "1"); }
    public boolean sipEnabled() { return "1".equals(get("sipEnabled", "0")); }
    public void setSipEnabled(boolean on) { set("sipEnabled", on ? "1" : "0"); }
    public boolean sipConfigured() {
        return sipEnabled() && !get("sipServer", "").trim().isEmpty()
                && !get("sipNumber", "").trim().isEmpty();
    }
    public int    bmTg()       { return i("bmTg", 460451); }
    public int    bmSlot()     { return 2; }
    public String bmStaticTgs(){ return get("bmStaticTgs", "460451,46001,46002,46020"); }
    public String bmTalkgroups(){ return get("bmTalkgroups", DEFAULT_BM_TGS); }
    public boolean bmAutoLogin(){ return "1".equals(get("bmAutoLogin", "1")); }
}
