package cn.arch.dmr;

/*
 * ARCH - Amateur Radio Convergence Hub
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You are solely responsible for complying with all applicable laws, amateur
 * radio licence conditions, frequency authorisations, privacy rules, export
 * controls and third-party service terms. The authors and contributors accept
 * no liability for unlawful, unauthorised, harmful or emergency use.
 */

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** ARCH —— 业余无线电融合中枢（DMR） */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public class MainActivity extends Activity implements FmoClient.Listener {

    // 主题色（由 applyThemeColors 在 onCreate 里按设置切换）
    private static int BG = 0xFFF3F6FA, CARD = 0xFFFFFFFF, SURFACE2 = 0xFFF7FAFD,
            TXT = 0xFF0B1220, SUB = 0xFF5F6F82, BLUE = 0xFF2563EB, GREEN = 0xFF16A34A,
            ACCENT = 0xFF0284C7, HAIRLINE = 0xFFDCE5EF, RED = 0xFFDC2626;
    private static final int CYAN = 0xFF00BCD4;   // “上次呼叫”呼号颜色（青色）
    private static boolean DARK = false;

    private static final String[] ACCENT_KEYS = { "blue", "cyan", "violet", "amber", "rose", "cyber" };
    private static final String[] ACCENT_LABELS = { "科技蓝", "青蓝", "紫罗兰", "琥珀", "玫红", "赛博" };
    private static final int[] ACCENT_LIGHT = { 0xFF0284C7, 0xFF0891B2, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48, 0xFFFF2BD6 };
    private static final int[] ACCENT_DARK = { 0xFF38BDF8, 0xFF22D3EE, 0xFFA78BFA, 0xFFFBBF24, 0xFFFB7185, 0xFFFF2BD6 };

    /** 海能达桌面会在 Home 后立刻销毁 Activity；PTT 后台模式保留这台无窗口运行时。 */
    private static volatile MainActivity hardwarePttActivity;
    private boolean backgroundRuntimeRetained;
    private BroadcastReceiver overlayPttReceiver;
    private static final String INTERNAL_PTT_PERMISSION =
            BuildConfig.APPLICATION_ID + ".permission.INTERNAL_PTT";
    private boolean runtimeShutdown;
    private boolean completeExitInProgress;

    static void attachHardwarePttActivity(MainActivity activity) {
        MainActivity previous = hardwarePttActivity;
        if (previous != null && previous != activity) {
            try { previous.shutdownRuntime(); } catch (Throwable ignored) {}
        }
        hardwarePttActivity = activity;
    }

    static void detachHardwarePttActivity(MainActivity activity) {
        if (hardwarePttActivity == activity) hardwarePttActivity = null;
    }

    /** Re-apply the intercom route after SIP ends, without rebuilding the main UI. */
    static void restoreAudioRouteAfterSip() {
        MainActivity a = hardwarePttActivity;
        if (a == null) return;
        a.ui.postDelayed(a::applyAudioRoute, 120L);
    }

    static boolean dispatchHardwarePtt(boolean down) {
        final MainActivity activity = hardwarePttActivity;
        if (activity == null || activity.isFinishing()) return false;
        if (activity.isDestroyed() && !activity.backgroundRuntimeRetained) return false;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            if (activity.isDestroyed() && !activity.backgroundRuntimeRetained) return;
            if (down) activity.externalPttDown(); else activity.externalPttUp();
        });
        return true;
    }

    static boolean dispatchHyteraKnob(boolean next) {
        final MainActivity activity = hardwarePttActivity;
        if (activity == null || activity.isFinishing()) return false;
        if (activity.isDestroyed() && !activity.backgroundRuntimeRetained) return false;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            if (activity.isDestroyed() && !activity.backgroundRuntimeRetained) return;
            activity.handleHyteraKnob(next);
        });
        return true;
    }

    private boolean cyberTheme;

    private void applyThemeColors(boolean dark) {
        DARK = dark;
        if (dark) {
            BG = 0xFF070B10; CARD = 0xFF0F151D; SURFACE2 = 0xFF151D27;
            TXT = 0xFFF3F7FB; SUB = 0xFF93A1B2; BLUE = 0xFF7DA2FF; GREEN = 0xFF4ADE80;
            ACCENT = 0xFF22D3EE; HAIRLINE = 0xFF233041; RED = 0xFFFF6B6B;
        } else {
            BG = 0xFFF3F6FA; CARD = 0xFFFFFFFF; SURFACE2 = 0xFFF7FAFD;
            TXT = 0xFF0B1220; SUB = 0xFF5F6F82; BLUE = 0xFF2563EB; GREEN = 0xFF16A34A;
            ACCENT = 0xFF0284C7; HAIRLINE = 0xFFDCE5EF; RED = 0xFFDC2626;
        }
        String accent = accentKey();
        cyberTheme = "cyber".equals(accent);
        if (cyberTheme) {
            DARK = true;
            BG = 0xFF05020A; CARD = 0xFF0C0616; SURFACE2 = 0xFF140A24;
            TXT = 0xFFF6E9FF; SUB = 0xFFB39AD0; BLUE = 0xFF00E5FF; GREEN = 0xFF39FF14;
            ACCENT = 0xFFFF2BD6; HAIRLINE = 0xFF3A1450; RED = 0xFFFF3864;
        } else {
            ACCENT = accentColorFor(accent, dark);
        }
    }

    private void applyWindowTheme() {
        try {
            getWindow().setStatusBarColor(CARD);
            getWindow().setNavigationBarColor(CARD);
            int f = getWindow().getDecorView().getSystemUiVisibility();
            if (!DARK) f |= 0x00002000; else f &= ~0x00002000;
            getWindow().getDecorView().setSystemUiVisibility(f);
        } catch (Throwable ignored) {}
    }

    private String accentKey() {
        String key = "blue";
        try { if (p != null) key = p.get("themeAccent", "blue"); } catch (Throwable ignored) {}
        for (String k : ACCENT_KEYS) if (k.equals(key)) return key;
        return "blue";
    }

    private static int accentColorFor(String key, boolean dark) {
        int idx = 0;
        for (int i = 0; i < ACCENT_KEYS.length; i++) if (ACCENT_KEYS[i].equals(key)) { idx = i; break; }
        return dark ? ACCENT_DARK[idx] : ACCENT_LIGHT[idx];
    }

    private int[] accentGradient() {
        if (cyberTheme) return new int[]{ 0xFF12001F, 0xFF5B00A8, 0xFFFF2BD6, 0xFF00C2D8 };
        int deep = blend(0xFF020812, ACCENT, 0.30f);
        int dark = blend(0xFF071422, ACCENT, 0.55f);
        int mid = blend(0xFF071422, ACCENT, 0.82f);
        int soft = blend(0xFFFFFFFF, ACCENT, 0.72f);
        return new int[]{ deep, dark, mid, soft };
    }

    private int[] accentGradient3() {
        if (cyberTheme) return new int[]{ 0xFFFF2BD6, 0xFF7A00FF, 0xFF00E5FF };
        return new int[]{ blend(0xFFFFFFFF, ACCENT, 0.72f), ACCENT,
                blend(0xFF06111F, ACCENT, 0.65f) };
    }

    /** PTT 专用：顶部受光、底部收暗，形成实体按键的立体弧面。 */
    private int[] pttGradient() {
        int[] g = accentGradient();
        return new int[]{ g[3], g[2], g[1], g[0] };
    }

    // 基础四组对所有账号可见；红十字组和 460175 组仅由服务端账号授权追加。
    private int[][] TGS = { {460451, 1}, {46002, 2}, {46020, 1}, {46001, 2} };
    private String[] TGNAMES = { "哈市组", "二区组", "广州组", "全国组" };
    private LinearLayout tgRow;

    private Prefs p;
    private HbpClient hbp;
    private HbpClient bmHbp;
    private String activePttTarget = null;
    private TextView extPttKeyStatus;
    private AudioEngine audio;
    private HqAudio hqAudio;
    private String lastHqPlaybackSpeaker = "";
    private boolean hqReceivePlaybackDirty = false;
    private AudioLevelMeterView audioLevelMeter;
    private FmoOpus netOpus;
    private Vocoder voc;
    private ContactBook contacts;
    private long contactMissRefreshAt;
    private FmoClient fmo;
    private NetworkRoomClient bmNet;
    private NrlClient nrl;
    private NrlAudioManager nrlAudio;
    private volatile boolean nrlActive, nrlConnecting;
    private volatile String nrlGroupNameCache = "";
    private volatile java.util.List<NrlClient.Group> nrlGroups = new java.util.ArrayList<>();
    private long nrlAutoAttemptAt, nrlRxStart;
    private String nrlLastPeer = "";

    private TextView statusDot, statusText, nowState, nowCall, nowTG, nowTimer;
    private TextView dmrLastLine, bmLastLine, netLastLine, fmoLastLine;
    private TextView fmoCallState, fmoCallLine, fmoNetworkLine;
    private Runnable fmoCallResetTask;
    private String fmoLastPeer = "";
    private int lastCommMode = 0;
    private int netLiveMembers = -1;
    private volatile int netBitrateBps = 0;
    private String hqStatusBase = "状态: 未连接";
    private String lastRoomListSig = "";
    private java.util.List<NetworkRoomClient.Member> lastVoiceMembers = new java.util.ArrayList<>();
    private long lastRoomListAt = 0L;
    private int roomListBurst = 0;
    private long lastNetConnectAt = 0L;
    private TextView accountSettingStatus;
    private Button accountSaveBtn, bmSaveBtn;
    private LinearLayout logBox, netLogBox;
    private TextView tgLabel, connectBtn, setGroupInfo, contactStatus, fmoStatus, modeLabel;
    private LinearLayout onlineBox, appOnlineBox;
    private ScrollView networkScroll;
    private CheckBox fmoEnable;
    private Button fmoButton, hqButton;
    private TextView hqStatus;
    private Button hqHangupBtn;
    private TextView hqCallState, hqCallLine, hqGroupLine, hqNetLine;
    private boolean hqActive;
    private volatile boolean hqMicRestarting;
    private volatile boolean hqTxRequested = false;
    private volatile boolean hqPttGranted = false;
    private static final int HQ_PREROLL_OPUS_FRAMES = 4;
    private final java.util.ArrayDeque<byte[]> hqPttPreroll = new java.util.ArrayDeque<>();
    private boolean duplexActive = false;
    private long duplexLastActivityAt = 0L;
    private Runnable duplexWatchdogTask;
    private boolean duplexIncoming = false;
    private boolean duplexOutgoing = false;
    private int duplexPeerId = 0;
    private String duplexPeerName = "";
    private AlertDialog duplexDialog;
    private NokiaRingtone duplexRingtone;
    private long lastHyteraKnobAt;
    private Runnable hardwarePttRetryTask;
    private int hardwarePttRetryCount;
    private Runnable txTimeoutTask;
    private Runnable resumeRestoreTask;
    private boolean resumeRestorePending;
    private AprsClient aprs;
    private long hqTxStart = 0;
    private long hqRxStart = 0;
    private String hqRxPeer = "";
    private long hqLastRxMs = 0;
    private int hqTxFrames = 0;
    private volatile boolean hqPlaybackReady = false;
    private int hqPlaybackGeneration = 0;
    private volatile boolean hqPlaybackStarting = false;
    private Runnable hqRxEndTask, hqRemoteResetTask;
    private boolean hqRemoteActive;
    private long hqRemoteLastAt;
    private volatile int hqReceiveGeneration;
    private volatile boolean hqUiReleased;
    private String hqReleasedPeer = "";
    private final java.util.ArrayDeque<short[]> fmoQueue = new java.util.ArrayDeque<>();
    private int fmoQueuedSamples = 0;
    private long fmoRxFrames = 0;
    private volatile boolean fmoPrimed = false;
    private long fmoDiagLastAt = 0;
    private volatile int fmoPrebufferMs = 600;
    private volatile long fmoRelayGeneration = -1L;
    private volatile int fmoStreamUnderruns = 0;
    private long fmoUnderruns = 0;
    private long fmoLastUnderrunLog = 0;
    private volatile boolean fmoPlayerRun;
    private EditText callEd, idEd, essidEd, passEd;
    private boolean accountValidated = false;
    private long bmLastConnectAttempt = 0L;
    private volatile boolean bmAutoAttachRunning;
    private int bmAttachGeneration;
    private boolean accountCheckRunning = false;
    private Runnable dmrCallResetTask, bmCallResetTask, hqCallResetTask;
    private long bmCallHoldUntil, hqCallHoldUntil;
    private String hqLastPeer = "";
    private View pageRadio, pageFmo, pageBm, pageNetwork, pageSet, pageDstar, pageYsf, pageP25, pageNxdn, pageNetRadio, pageNrl;
    private LinearLayout radioProvinceGrid, radioCityGrid, radioRecentBox, radioListBox;
    private TextView radioStatus, radioNowTitle, radioNowSub;
    private TextView radioProvinceHeader, radioCityHeader, radioListHeader, radioRecentHeader, radioFavoriteHeader;
    private LinearLayout radioFavoriteBox;
    private boolean radioFavoriteOpen = true;
    private TextView radioLinkHeader;
    private LinearLayout radioLinkBox;
    private boolean radioLinkOpen = false;
    private long radioCatalogLastRefresh = 0L;   // [3.2.51] 收音机页刷新节流
    private boolean radioProvinceOpen = true, radioCityOpen = true, radioListOpen = true, radioRecentOpen = true;
    private EditText radioSearch;
    private Button radioPlayBtn, radioStopBtn;
    private SeekBar radioVolume;
    private RadioWaveView radioWave;
    private java.util.List<RadioStation> radioStations = new java.util.ArrayList<>();
    private RadioPlayer radioPlayer;
    private RadioStation radioCurrent;
    private String radioProvince = "全部", radioCity = "全部城市";
    private boolean radioUserPaused, radioPausedForOther, radioPausedForPtt;
    private boolean radioPausedForPage;   // [3.2.45] 离开收音机页/切到后台时暂停
    private boolean tinyScreen;           // [3.2.45] 320x240 小屏
    private float radioUserVolume = 1f;
    private Runnable radioResumeTask;
    private boolean radioBottomOnly;
    private TextView bmState, bmCallLine, bmNow, bmServerLabel, bmCustomLabel;
    private Button bmLoginBtn;
    private EditText bmCallEd, bmIdEd, bmEssidEd, bmPassEd, bmCustomTgEd, bmTgNameEd;
    private TextView bmServerValue;
    private LinearLayout bmTgRow, bmStaticBox, bmLogBox;
    private final java.util.ArrayList<String> bmHistory = new java.util.ArrayList<>();
    private Button[] bmTgBtns;
    private Button[] navBtns;
    private Button dstarServerBtn, dstarModuleBtn;
    private TextView dstarConnectBtn;
    private TextView dstarCallState, dstarCallLine, dstarLastLine, dstarServerLine, dstarStatus;
    private TextView muteDstarBtn;
    private String activeVoiceKind = "network";
    private DStarClient dstarDirect;
    private YsfClient ysfDirect;
    private P25Client p25Direct;
    private NxdnClient nxdnDirect;
    private volatile boolean dstarDirectActive;
    private volatile boolean ysfActive, ysfConnecting;
    private volatile boolean p25Active, p25Connecting;
    private volatile boolean nxdnActive, nxdnConnecting;
    private Button ysfServerBtn;
    private TextView ysfConnectBtn;
    private TextView ysfCallState, ysfCallLine, ysfLastLine, ysfServerLine, muteYsfBtn;
    private LinearLayout ysfLogBox;
    private Button p25ServerBtn;
    private TextView p25ConnectBtn;
    private TextView p25CallState, p25CallLine, p25LastLine, p25ServerLine, muteP25Btn;
    private LinearLayout p25LogBox;
    private long p25RxStart = 0L;
    private long p25AutoAttemptAt = 0L;
    private String p25LastPeer = "";
    private Button nxdnServerBtn;
    private TextView nxdnConnectBtn;
    private TextView nxdnCallState, nxdnCallLine, nxdnLastLine, nxdnServerLine, muteNxdnBtn;
    private TextView nrlCallState, nrlCallLine, nrlLastLine, nrlServerLine, nrlGroupLine, muteNrlBtn, nrlConnectBtn, nrlDirectoryLine, nrlDirectoryRefreshBtn;
    private Button nrlServerBtn, nrlAddServerBtn;
    private LinearLayout nrlGroupBox, nrlLogBox;
    private LinearLayout nxdnLogBox;
    private long nxdnRxStart = 0L;
    private long nxdnAutoAttemptAt = 0L;
    private String nxdnLastPeer = "";
    private String nxdnUiLastPeer = "";
    private long nxdnUiLastAt = 0L;
    private boolean pttToneOn;
    private long lastPttBusyToneAt = 0L;
    private long ysfRxStart = 0L;
    private long ysfUiLastAt = 0L;
    private String ysfLastPeer = "";
    private String ysfUiLastCall = "";
    private long ysfAutoAttemptAt;
    private volatile boolean dstarConnecting;
    private String dstarLastPeer = "";
    private LinearLayout dstarLogBox;
    private long dstarRxStart;
    private long dstarRxLastAt;
    private String dstarRxPeer = "";
    private Runnable dstarRxEndTask;
    private long dstarTxStart;
    private boolean dstarUserWanted;
    private int dstarReconnectAttempts;
    private Runnable dstarReconnectTask;
    private android.widget.ScrollView settingsScroll;
    private static int pendingSettingsScrollY = -1;
    private static int settingsTabIndex = 0;
    private static long suppressAutoConnectUntil = 0L;
    private Button[] tgBtns;
    private final boolean[] navSignal = new boolean[9];
    private boolean netOnlineAutoScrolled = false;
    private volatile long hqTxLastFrameMs = 0L;
    private Runnable hqTxWatchdogTask;
    private final Runnable[] navSignalOff = new Runnable[9];
    private final long[] navSignalUntil = new long[9];
    private final Object navSignalLock = new Object();
    private TextView muteDmrBtn, muteBmBtn, muteAnalogBtn, muteNetworkBtn;
    private final java.util.HashMap<Integer, TextView> fmoOnlineNameViews = new java.util.HashMap<>();
    private int fmoSpeakerUid = 0;
    private Runnable fmoSpeakerResetTask;
    private Button aprsStateBtn;
    private int page = 3, mode = 3;       // 默认进入Net
    private static final int PAGE_DIGITAL = 0;
    private static final int PAGE_BM = 1;
    private static final int PAGE_ANALOG = 2;
    private static final int PAGE_NETWORK = 3;
    private static final int PAGE_SETTINGS = 4;
    private static final int PAGE_DSTAR = 5;
    private static final int PAGE_YSF = 6;
    private static final int PAGE_P25 = 8;
    private static final int PAGE_NXDN = 9;
    private static final int PAGE_NRL = 11;
    private static final int PAGE_NET_RADIO = 10;
    private static final int PAGE_SIP = 12;
    private static final int[] NAV_ALL_PAGES = {2, 0, 1, 3, 5, 6, 8, 9, 11};
    private static final int[] NAV_ALL_MODES = {1, 0, 2, 3, 4, 5, 6, 7, 8};
    private int[] activeNavPages = NAV_ALL_PAGES.clone();
    private int[] activeNavModes = NAV_ALL_MODES.clone();
    private static volatile String[][] DSTAR_SERVERS = DStarServers.BUILTIN;
    private float uiScale = 1f;
    private static final int FONT_GROUP_ID_BASE = 7300;   // [3.2.32] 界面字号单选按钮 id 基址
    private float userFontFactor = 1f;   // [3.2.30] 用户在"界面字号"里选的倍率（auto 时为 1）
    private int visibleRows = 5;
    private int pendingPage = 3;          // 首次启动默认Net，重建界面时保留当前页
    private boolean fmoWanted = false;    // 运行时“我想要连着 FMO”的意图；stop() 后不再自动重连
    private int joinRetry = 0;
    private boolean networkWanted = false;
    private boolean networkConnecting = false;
    private volatile boolean networkSwitching = false;
    private volatile boolean hqMicStarting = false;
    private volatile int networkMicGeneration = 0;
    private long networkUiCooldownUntil = 0L;
    private Runnable networkReconnectTask;
    private Runnable networkSwitchTask;
    private String pendingNetworkRoom;
    private volatile boolean roomPollRun = false;
    private LinearLayout roomBox;
    private static java.util.ArrayList<String[]> NET_ROOMS = new java.util.ArrayList<>();

    /** [FIX 3.2.29] 公开版屏蔽的房间：438.500中继 / 省红十字（id 与名称双重判断，改号也不会错）。 */
    private static String[] PUBLIC_HIDDEN_IDS = { "room1", "room4" };
    private static String[] PUBLIC_HIDDEN_NAMES = { "438.500", "红十字" };

    private static boolean publicHiddenByIdName(String id, String name) {
        String i = String.valueOf(id == null ? "" : id).trim();
        String n = String.valueOf(name == null ? "" : name);
        for (String x : PUBLIC_HIDDEN_IDS) if (x.equalsIgnoreCase(i)) return true;
        for (String x : PUBLIC_HIDDEN_NAMES) if (n.contains(x)) return true;
        return false;
    }

    /**
     * [FIX 3.2.29] 房间可见性。
     *   协会版：全部可见（行为不变）。
     *   公开版：服务端标了 publicHidden 的不可见；另外 438.500中继 / 省红十字 一律不可见。
     * 历史教训：旧代码把公开版写死成“只允许 room2/room4”，10-12 改号后 room4 变成省红十字，
     *          公开版用户就看到 46002多模融合 + 省红十字（本意是 46002 + 双工测试组）。
     */
    private static boolean isRoomVisible(String id, String name, boolean serverPublicHidden) {
        if (!BuildConfig.PUBLIC_EDITION) return true;
        if (serverPublicHidden) return false;
        return !publicHiddenByIdName(id, name);
    }

    private static void addNetRoomIfVisible(String id, String name) {
        if (isRoomVisible(id, name, false)) NET_ROOMS.add(new String[]{ id, name, "0" });
    }

    static {
        // 服务端房间表下发前的占位表（几秒内会被服务端真实表替换），名称与 93 一致
        addNetRoomIfVisible("room1", "438.500中继");
        addNetRoomIfVisible("room2", "46002多模融合");
        addNetRoomIfVisible("room3", "省无线电运动协会");
        addNetRoomIfVisible("room4", "省红十字");
        addNetRoomIfVisible("room5", "语音测试组");
        addNetRoomIfVisible("room6", "双工测试组");
        addNetRoomIfVisible("room7", "46001中国组");
        addNetRoomIfVisible("room8", "46020广州组");
    }
    private volatile boolean txing = false, audioReady = false, switchingGroup = false;
    private boolean rxActive = false;
    private byte[] txAcc = new byte[0];
    private int txBursts = 0;
    private boolean rebuildingUiInPlace = false;
    private int startupAutoLoginSweepCount = 0;
    private int lastAnimatedNavPage = -1;
    private final java.util.LinkedHashMap<String, Runnable> pendingAccountActions = new java.util.LinkedHashMap<>();
    private int rxCallSrc = 0, rxCallDst = 0;
    private long rxCallStart = 0, txCallStart = 0;
    private final java.util.ArrayList<String> callHistory = new java.util.ArrayList<>();
    private android.os.Handler ui = new android.os.Handler();
    private final SipRuntime.Listener sipStatusListener = new SipRuntime.Listener() {
        @Override public void onSipState(String state, boolean registered) {}
        @Override public void onIncoming(String remoteUri) { ui.post(MainActivity.this::pauseIntercomAudioForSip); }
        @Override public void onCallState(String state, String remoteUri) {
            if (state != null && !state.startsWith("已挂断") && !state.startsWith("通话结束") && !state.startsWith("通话失败"))
                ui.post(MainActivity.this::pauseIntercomAudioForSip);
        }
        @Override public void onError(String message) {}
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ArchCrash.install(getApplicationContext());
        p = new Prefs(this);
        Intent launchIntent = getIntent();
        boolean backgroundLaunch = launchIntent != null
                && launchIntent.getBooleanExtra(HyteraBootReceiver.EXTRA_BOOT_START, false);
        if (!backgroundLaunch) p.setUserExited(false);
        if (!BuildConfig.PUBLIC_EDITION) {
            SipRuntime.get(this).addListener(sipStatusListener);
            SipRuntime.get(this).ensureStarted();
            if (p.sipEnabled()) ensureKeepAliveService();
        }
        if (p.showNrl()) NrlServers.refresh(this, () -> safeUi(() -> { if (page == PAGE_NRL) refreshNrlServerButton(); }));
        YsfServers.load(this);
        P25Servers.load(this);
        NxdnServers.load(this);
        NxdnContacts.load(this);
        // [3.2.44] 未开启 NXDN 的用户不做后台通讯录刷新，减少启动流量/耗电
        if (p.showNxdn()) NxdnContacts.refreshIfNeeded(this);
        BmServers.load(this);
        DStarServers.load(this);
        DSTAR_SERVERS = DStarServers.ALL;
        aprs = new AprsClient(this, p, msg -> log("APRS: " + msg));
        loadTalkgroupsFromPrefs();
        // 主题必须在任何 View 创建之前设置，系统控件（按钮/复选框/输入框）才会跟着换色
        setTheme(p.dark() ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        applyThemeColors(p.dark());
        computeUiScale();
        if (b != null) pendingPage = b.getInt("arch.page", defaultPage());
        else pendingPage = defaultPage();
        if (!pageEnabled(pendingPage)) pendingPage = defaultPage();
        contacts = new ContactBook(this);
        audio = new AudioEngine();
        hqAudio = new HqAudio(24000, 24000);
        duplexRingtone = new NokiaRingtone(this);
        applyAudioPrefs();
        netOpus = new FmoOpus(24000);
        bmNet = new NetworkRoomClient(this);
        bmNet.setListener(new NetworkRoomClient.Listener() {
            @Override public void onState(int st, String msg) {
                final int stateGen = bmNet == null ? -1 : bmNet.connectionGeneration();
                safeUi(() -> {
                    if (bmNet == null || stateGen != bmNet.connectionGeneration()) return;
                    boolean ok = (st == 1);
                    boolean connecting = (st == 0 && "connecting".equals(msg));
                    if (ok) {
                        runAudioIo(() -> { if (hqAudio != null) hqAudio.setExternalPacing(true); });
                        networkConnecting = false;
                        networkUiCooldownUntil = System.currentTimeMillis() + 2500L;
                    } else if (!connecting) {
                        runAudioIo(() -> { if (hqAudio != null) hqAudio.setExternalPacing(false); });
                        networkConnecting = false;
                        netBitrateBps = 0;
                        hqPlaybackReady = false;
                        hqTxRequested = false;
                        // 模式正在发射时不要清 txing，否则频道一掉线就把模式发射带停。
                        if (activePttTarget == null || "network".equals(activePttTarget)) { txing = false; txCallStart = 0; }
                        hqTxFrames = 0;
                        if (duplexActive || duplexIncoming || duplexOutgoing) stopDuplexAudio();
                        hqStopAsync();
                    } else {
                        networkConnecting = true;
        netOnlineAutoScrolled = false;
                    }
                    if (hqStatus != null) {
                        if (ok) setHqStatus(duplexActive
                                ? "状态: 双工通话中 · " + duplexPeerName + " · 点击结束"
                                : "状态: 已连接 · " + hqName() + " · 点击断开");
                        else if (connecting) setHqStatus("状态: 正在连接 · " + hqName());
                        else setHqStatus("状态: " + msg);
                    }
                    hqActive = ok || connecting;
                    if (!ok && !connecting) renderVoiceOnline(java.util.Collections.emptyList());
                    buildRoomList(); updateModeLabel(); refreshNetworkCallBar();
                    if (!ok && !connecting && st == 3 && networkWanted) {
                        if (msg != null && (msg.contains("account/password") || msg.contains("room unavailable"))) {
                            networkWanted = false;
                            pendingNetworkRoom = null;
                            networkSwitching = false;
                            toast(msg.contains("account/password")
                                    ? "账号或密码错误，请到「设置」重新保存"
                                    : "该频道当前不可用，请选择其他频道");
                            return;
                        }
                        if (networkReconnectTask != null) ui.removeCallbacks(networkReconnectTask);
                        networkReconnectTask = () -> {
                            networkReconnectTask = null;
                            if (networkWanted && !hqActive && !networkConnecting) joinRoom();
                        };
                        ui.postDelayed(networkReconnectTask, 2500);
                    }
                });
            }
            @Override public void onLog(String msg) { log(msg); }
            @Override public void onUdpReady(boolean ready, String message) { runAudioIo(() -> { if (hqAudio != null) hqAudio.setExternalPacing(ready); }); }
            @Override public void onVoice(int dmrId, String callsign, short[] pcm) {
                // Drop callbacks queued before a release/cleanup epoch.
                final int voiceGeneration = hqReceiveGeneration;
                safeUi(() -> { if (voiceGeneration == hqReceiveGeneration) onHqVoice(dmrId, callsign, pcm); });
            }
            @Override public void onPtt(int st, String callsign, String message) {
                safeUi(() -> handleNetworkPttState(st, callsign, message));
            }
            @Override public void onDuplex(int st, int peerDmrId, String peerCallsign, String message) {
                safeUi(() -> handleDuplexState(st, peerDmrId, peerCallsign, message));
            }
            @Override public void onMembers(java.util.List<NetworkRoomClient.Member> members) {
                boolean tx = false;
                String txCallsign = "";
                int txId = 0;
                if (members != null) for (NetworkRoomClient.Member m : members) {
                    if (m != null && m.tx && m.dmrId != p.dmrId()) {
                        tx = true;
                        txCallsign = m.callsign == null ? "" : m.callsign;
                        txId = m.dmrId;
                        break;
                    }
                }
                if (tx) markModeSignal(3);
                final boolean anyTx = tx;
                final String activeCallsign = txCallsign;
                final int activeId = txId;
                safeUi(() -> {
                    renderVoiceOnline(members);
                    if (anyTx && !hqUiReleased) {
                        String who = activeCallsign.trim().isEmpty() ? String.valueOf(activeId) : activeCallsign.trim();
                        // The member snapshot is the fastest authoritative source;
                        // update the callsign bar in the same UI turn as the lamp.
                        if (hqCallState != null) { hqCallState.setText("接收中"); hqCallState.setTextColor(BLUE); }
                        if (hqCallLine != null) { hqCallLine.setText(who); hqCallLine.setTextColor(RED); }
                        if (hqStatus != null) setHqStatus("状态: 接收中 · " + who);
                        hqLastPeer = who;
                        hqRemoteActive = true;
                        hqRemoteLastAt = System.currentTimeMillis();
                        if (hqRemoteResetTask == null) scheduleHqRemoteReset();
                        refreshNetworkCallBar();
                    } else if (!anyTx) {
                        if (hqUiReleased || hqReceivePlaybackDirty) endRemoteNetworkReceiveUiNow(hqReleasedPeer);
                        else clearNetworkReceiveIfIdle();
                    }
                });
            }

            @Override public void onBitrate(int bps) {
                netBitrateBps = bps;
                if (netOpus != null) netOpus.setBitrate(bps);
                log("网络 Opus 码率: " + (bps / 1000) + " kbps");
                safeUi(() -> setHqStatus(hqStatusBase));
            }
        });
        nrl = new NrlClient();
        nrlAudio = new NrlAudioManager(this);
        applyAudioPrefs();
        nrl.setListener(new NrlClient.Listener() {
            @Override public void onState(int st, String message) {
                safeUi(() -> {
                    nrlConnecting = (st == 0 || st == 3);
                    nrlActive = (st == 1);
                    p.set("modeNrl", nrlActive ? "1" : "0");
                    if (st == 1) runAudioIo(() -> { if (nrlAudio != null) nrlAudio.startPlayback(); });
                    else runAudioIo(() -> { if (nrlAudio != null) nrlAudio.stopPlayback(); });
                    if (st != 1 && "nrl".equals(activePttTarget)) { txing = false; activePttTarget = null; try { if (nrlAudio != null) nrlAudio.stopMic(); } catch (Throwable ignored) {} }
                    if (nrlCallState != null) {
                        if (st == 1) { nrlCallState.setText("空闲"); nrlCallState.setTextColor(SUB); }
                        else if (st == 0) { nrlCallState.setText("连接中"); nrlCallState.setTextColor(BLUE); }
                        else if (st == 3) { nrlCallState.setText("连接失败"); nrlCallState.setTextColor(RED); }
                        else { nrlCallState.setText("未连接"); nrlCallState.setTextColor(SUB); }
                    }
                    if (nrlCallLine != null) {
                        if (st == 1) { nrlCallLine.setText("等待呼叫"); nrlCallLine.setTextColor(TXT); }
                        else if (st == 0) { nrlCallLine.setText("正在连接 NRL"); nrlCallLine.setTextColor(BLUE); }
                        else if (st == 3) { nrlCallLine.setText(message == null ? "服务器无响应，正在重连" : message); nrlCallLine.setTextColor(RED); }
                        else { nrlCallLine.setText("未登录"); nrlCallLine.setTextColor(TXT); }
                    }
                    if (st == 1) {
                        // Let the server register the first heartbeat before asking for groups.
                        ui.postDelayed(() -> {
                            if (!nrlActive || nrl == null) return;
                            nrl.requestGroupList();
                            nrl.joinGroup(p.nrlGroup());
                        }, 700L);
                        ui.postDelayed(() -> {
                            if (nrlActive && nrl != null) nrl.requestGroupList();
                        }, 2600L);
                    }
                    refreshNrlServerButton();
                    paintNrlConnectIcon();
                    updateModeLabel();
                });
            }
            @Override public void onVoice(String callsign, int ssid, short[] pcm) {
                safeUi(() -> onNrlVoice(callsign, ssid, pcm));
            }
            @Override public void onGroupList(java.util.List<NrlClient.Group> groups) {
                safeUi(() -> {
                    java.util.ArrayList<NrlClient.Group> incoming = groups == null
                            ? new java.util.ArrayList<NrlClient.Group>()
                            : new java.util.ArrayList<>(groups);
                    if (!incoming.isEmpty()) {
                        nrlGroups = incoming;
                        saveNrlGroupCache(nrlGroups);
                    } else {
                        if (nrlGroups == null || nrlGroups.isEmpty()) {
                            nrlGroups = loadNrlGroupCache(p.nrlServerKey());
                        }
                        log("NRL 服务器未返回频道，保留本地频道缓存 " + nrlGroups.size() + " 条");
                    }
                    if (!nrlGroups.isEmpty()) {
                        int currentId = p.nrlGroup();
                        NrlClient.Group selected = null;
                        for (NrlClient.Group g : nrlGroups) if (g.id == currentId) { selected = g; break; }
                        if (selected == null) {
                            selected = nrlGroups.get(0);
                            p.setNrlGroup(selected.id);
                            if (nrl != null) nrl.joinGroup(selected.id);
                        }
                        nrlGroupNameCache = selected.name;
                        if (nrlGroupLine != null) nrlGroupLine.setText("当前房间: " + selected.name + " · " + selected.id);
                    } else if (nrlGroupLine != null) {
                        nrlGroupLine.setText(nrlGroupDisplayText());
                    }
                    buildNrlGroupButtons(nrlGroups);
                    updateModeLabel();
                });
            }
            @Override public void onGroup(int id, String name) {
                safeUi(() -> {
                    p.setNrlGroup(id);
                    nrlGroupNameCache = name == null ? "" : name;
                    if (nrlGroupLine != null) nrlGroupLine.setText(nrlGroupDisplayText());
                    buildNrlGroupButtons(nrlGroups);
                    updateModeLabel();
                    toast("NRL 已切换: " + (name == null || name.isEmpty() ? id : name));
                });
            }
            @Override public void onLog(String message) { log(message); }
        });
        radioPlayer = new RadioPlayer(this);
        radioPlayer.setListener((state, msg) -> safeUi(() -> updateNetRadioPlayerState(state, msg)));
        dstarDirect = new DStarClient(this);
        dstarDirect.setListener(new DStarClient.Listener() {
            @Override public void onState(int st, String msg) {
                dstarDirectActive = (st == 1);
                dstarConnecting = (st == 0);
                if (st == 1) { dstarReconnectAttempts = 0; if (audio != null) audio.resetDstar(); }
                if (st == 3 && dstarUserWanted && dstarReconnectAttempts < 3) {
                    dstarReconnectAttempts++;
                    if (dstarReconnectTask != null) ui.removeCallbacks(dstarReconnectTask);
                    dstarReconnectTask = () -> { dstarReconnectTask = null; if (dstarUserWanted && !dstarDirectActive) joinDstar(); };
                    ui.postDelayed(dstarReconnectTask, 1500L);
                }
                if (st == 2 || st == 3) {
                    if (dstarRxEndTask != null) { ui.removeCallbacks(dstarRxEndTask); dstarRxEndTask = null; }
                    dstarRxStart = 0; dstarRxPeer = "";
                }
                safeUi(() -> {
                    paintDstarConnectIcon();
                    if (st == 1) {
                        dstarCallState.setText("空闲"); dstarCallState.setTextColor(SUB);
                        dstarCallLine.setText("等待呼叫"); dstarCallLine.setTextColor(TXT);
                    } else if (st == 2 || st == 3) {
                        dstarCallState.setText("未连接"); dstarCallState.setTextColor(SUB);
                        dstarCallLine.setText("未登录"); dstarCallLine.setTextColor(TXT);
                    }
                    updateModeLabel();
                });
            }
            @Override public void onVoice(String call, short[] pcm) {
                if (!dstarDirectActive || pcm == null || pcm.length == 0) return;
                markModeSignal(4);
                if (!listenMode(4)) return;
                if (audio != null && !rxPlaybackBlocked()) audio.playDstar(pcm);
                if (call == null || call.trim().isEmpty() || "D-Star".equalsIgnoreCase(call)) return;
                String who = call.trim();
                if (who.matches("[0-9]+")) {
                    try { who = contactLabel(Integer.parseInt(who)); } catch (Throwable ignored) {}
                }
                final String whoFinal = who;
                long now = System.currentTimeMillis();
                dstarRxLastAt = now;
                if (dstarRxStart == 0) { dstarRxStart = now; dstarRxPeer = whoFinal; }
                scheduleDstarRxEnd();
                safeUi(() -> {
                    setLastCall(4, whoFinal);
                    if (dstarLastPeer.equals(whoFinal)) return;
                    dstarLastPeer = whoFinal;
                    dstarCallState.setText("接收中"); dstarCallState.setTextColor(BLUE);
                    dstarCallLine.setText(whoFinal); dstarCallLine.setTextColor(RED);
                    if (page == PAGE_DSTAR) {  }
                });
            }
            @Override public void onLog(String msg) { log(msg); }
        });
        buildUi();
        startRoomPolling();
        registerOverlayReceiver();
        attachHardwarePttActivity(this);
        try {
            // 底层服务状态写入运行日志即可，不再向普通用户反复弹出技术信息。
            HyteraPdc760Bridge.setListener(msg -> log("海能达终端: " + msg));
            HyteraPdc760Bridge.install(getApplicationContext());
            updateModeLabel();
        } catch (Throwable t) { log("海能达兼容桥启动失败: " + t); }
        applyWindowTheme();
        applyKeepScreenOn();
        initCallMute();
        // 保活服务：任何异常都不能影响主界面启动（1.0.6 在 Android 13/14 上就是这里崩的）
        try {
            if (p.showNotification() || p.overlayPtt() || p.externalPttEnabled() || p.modeNetwork()) {
                android.content.Intent keep = new android.content.Intent(this, ArchKeepAliveService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
            } else {
                stopService(new android.content.Intent(this, ArchKeepAliveService.class));
            }
        } catch (Throwable t) { log("保活服务启动失败: " + t); }
        applyAudioRoute();
        loadHistory();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (needsLegacyAudio()) startAudioAsync();
        } else {
            requestPerms();
        }
        voc = new Vocoder();
        log(voc.ok() ? (voc.selfTest() ? "声码器自检通过 (blip25)" : "声码器自检失败") : "声码器加载失败");
        FmoOpus op = new FmoOpus();
        boolean opOk = op.selfTest();
        log(opOk ? "FMO OPUS 自检通过" : "FMO OPUS 自检失败");
        op.close();
        P25Vocoder p25test = new P25Vocoder();
        boolean p25Ok = p25test.selfTest();
        log(p25Ok ? "P25 IMBE 4400 自检通过" : "P25 IMBE 4400 自检失败");
        p25test.close();
        NxdnCodec nxdnTest = new NxdnCodec();
        boolean nxdnOk = nxdnTest.selfTest();
        log(nxdnOk ? "NXDN 2450 自检通过" : "NXDN 2450 自检失败");
        nxdnTest.close();
        contacts.loadAsync(() -> ui.post(this::refreshContactInfo));
        // 每次启动检查更新；有更新才提示，没有不打扰
        try { new Updater(this).check(true, null); } catch (Throwable ignored) {}
    }

    // ────────── 主题 / 显示 / 音频 ──────────
    private void applyKeepScreenOn() {
        try {
            if (p.keepScreenOn())
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
    }

    private void applyAudioPrefs() {
        AudioProfile profile = AudioProfile.fromPrefs(p);
        if (audio != null) audio.setAudioProfile(profile);
        if (hqAudio != null) hqAudio.setAudioProfile(profile);
        if (nrlAudio != null) nrlAudio.setAudioProfile(profile);
    }

    private void showAudioTuningPicker(final boolean transmit, final Button target) {
        final String key = transmit ? "voiceTuning" : "listeningTuning";
        String current = transmit ? p.voiceTuning() : p.listeningTuning();
        int checked = AudioProfile.tuningIndex(current);
        new AlertDialog.Builder(this)
                .setTitle(transmit ? "发射语音调音模式" : "接收收听调音模式")
                .setSingleChoiceItems(AudioProfile.TUNING_LABELS, checked, (dialog, which) -> {
                    p.set(key, AudioProfile.TUNING_KEYS[which]);
                    target.setText(AudioProfile.TUNING_LABELS[which]);
                    applyAudioPrefs();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

 /** 扬声器 / 听筒 输出切换 */
    /** Pause local receive audio while a SIP call owns the audio route. */
    private void pauseIntercomAudioForSip() {
        try { if (txing || hqTxRequested) stopNetworkPttRequest(); } catch (Throwable ignored) {}
        try { hqFlushPlayback(); } catch (Throwable ignored) {}
        try { if (audio != null) audio.stop(); } catch (Throwable ignored) {}
        try { if (radioPlayer != null && radioPlayer.isPlaying()) radioPlayer.pause(); } catch (Throwable ignored) {}
        android.util.Log.i("ARCH-SIP", "intercom audio paused for SIP");
    }

    private void applyAudioRoute() {
        try {
            android.media.AudioManager m = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
            if (m == null) return;
            boolean ear = p.isEarpiece();
            m.setMode(ear ? android.media.AudioManager.MODE_IN_COMMUNICATION : android.media.AudioManager.MODE_NORMAL);
            m.setSpeakerphoneOn(!ear);
        } catch (Throwable ignored) {}
    }

    private android.media.AudioManager am;

 /**
 * 通话静音：来电 / 网络电话，或其它 App 正在出声时，把对讲输出静音。
 * 只用公开 API（getMode + getActivePlaybackConfigurations），隐藏字段用反射读，失败就降级。
 */
    private void initCallMute() {
        try { am = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE); }
        catch (Throwable ignored) {}
        ui.postDelayed(new Runnable() {
            @Override public void run() { updateMute(); ui.postDelayed(this, 2000); }
        }, 1000);
    }

    private boolean otherAppPlaying() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || am == null) return false;
        try {
            java.util.List<android.media.AudioPlaybackConfiguration> list = am.getActivePlaybackConfigurations();
            if (list == null) return false;
            for (android.media.AudioPlaybackConfiguration c : list) {
                Object active = c.getClass().getMethod("isActive").invoke(c);
                if (!(active instanceof Boolean) || !((Boolean) active)) continue;
                Object uid = c.getClass().getMethod("getClientUid").invoke(c);
                int u = (uid instanceof Integer) ? (Integer) uid : -1;
                if (u != android.os.Process.myUid()) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void updateMute() {
        if (audio == null) return;
        boolean busy = false;
        try {
            if (am != null) {
                int m = am.getMode();
                busy = (m == android.media.AudioManager.MODE_IN_CALL
                        || m == android.media.AudioManager.MODE_IN_COMMUNICATION);
                if (!busy && android.os.Build.VERSION.SDK_INT >= 26) busy = otherAppPlaying();
            }
        } catch (Throwable ignored) {}
        try { audio.muteSpk(p.callMute() && busy); } catch (Throwable ignored) {}
    }

    private void requestPerms() {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
        else
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    private void requestLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
    }
    /** [3.2.44] 只有开启非 Net 模式才需要常驻 8k 音频引擎；Net-only 用户不占 AudioTrack。 */
    private boolean needsLegacyAudio() {
        try {
            return p.showDigital() || p.showAnalog() || p.showBm() || p.showDstar()
                    || p.showYsf() || p.showP25() || p.showNxdn();
        } catch (Throwable t) { return true; }
    }

    // [3.2.52] 所有音频硬件生命周期串行到后台线程，UI 线程不再 stop/release 音频设备
    private static final java.util.concurrent.ExecutorService AUDIO_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "arch-audio-io");
                t.setDaemon(true);
                return t;
            });

    private void runAudioIo(Runnable r) {
        try {
            AUDIO_IO.execute(() -> {
                try { r.run(); } catch (Throwable t) { log("音频后台任务异常: " + t); }
            });
        } catch (Throwable ignored) {}
    }

    private void hqMicOffAsync() {
        final HqAudio h = hqAudio;
        if (h == null) return;
        runAudioIo(() -> h.startMic(null));
    }

    private void hqStopAsync() {
        final HqAudio h = hqAudio;
        if (h == null) return;
        hqPlaybackGeneration++;
        hqPlaybackReady = false;
        hqPlaybackStarting = false;
        hqReceivePlaybackDirty = false;
        lastHqPlaybackSpeaker = "";
        runAudioIo(h::stop);
    }

    /** End one received talkspurt and fully release AudioTrack before mic/PTT starts. */
    private void hqFlushPlayback() {
        if (!hqReceivePlaybackDirty && !hqPlaybackReady && !hqPlaybackStarting) return;
        hqPlaybackGeneration++;
        hqPlaybackReady = false;
        hqPlaybackStarting = false;
        hqReceivePlaybackDirty = false;
        lastHqPlaybackSpeaker = "";
        HqAudio h = hqAudio;
        if (h != null) runAudioIo(h::stopPlayback);
    }

    private void legacyMicOffAsync() {
        final AudioEngine a = audio;
        if (a == null) return;
        runAudioIo(() -> a.startMic(null));
    }

    private void startAudioAsync() {
        runAudioIo(this::startAudio);
    }

    private void startAudio() {
        if (audioReady) return;
        audioReady = audio != null && audio.start();
        if (audioReady) applyAudioPrefs();
        log(audioReady ? "音频已就绪" : "音频初始化失败，请检查麦克风权限");
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (needsLegacyAudio()) startAudioAsync();
            ensureKeepAliveService();
        } else if (code == 1) log("未授予麦克风权限，无法发射语音");
        else if (code == 2) {
            boolean granted = false;
            for (int g : grants) if (g == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted && p != null && p.aprsEnabled() && aprs != null) aprs.start();
            else if (!granted) toast("未授予定位权限，APRS 无法上传位置");
        }
    }

    // ────────── UI ──────────
    private void ts(TextView v, float size) {
        // [3.2.30] 自动缩放（按屏幕分辨率） × 用户选的界面字号
        float minSp = tinyScreen ? 7f : 10f;
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(minSp, size * uiScale * userFontFactor));
    }

    private TextView tv(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); ts(t, size); t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.12f);
        return t;
    }
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(cardBg());
        l.setElevation(dp(4));
        l.setTranslationZ(dp(1));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        l.setLayoutParams(lp);
        return l;
    }

 /** Common call-sign/status card used by DMR, BM and network pages. */
    private boolean callsignEnhancedLayout() {
        try { return !BuildConfig.OVERSEAS_EDITION && p != null && p.callsignEnhanced(); }
        catch (Throwable ignored) { return false; }
    }

    private void scaleTextTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * 2f);
            text.setIncludeFontPadding(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scaleTextTree(group.getChildAt(i));
        }
    }

    private void addEnhancedStatusView(LinearLayout col, View view, int topMargin) {
        if (view == null || view.getParent() != null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        col.addView(view, lp);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setGravity(Gravity.CENTER);
            text.setHorizontallyScrolling(false);
        }
        scaleTextTree(view);
    }

    private void adjustTextTreeSize(View view, float deltaSp) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            float density = getResources().getDisplayMetrics().scaledDensity;
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    Math.max(density * 6f, text.getTextSize() + deltaSp * density));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                adjustTextTreeSize(group.getChildAt(i), deltaSp);
        }
    }

    /** 只调整实际文字带指定前缀的状态行，避免误伤服务器名、当前组等其它字段。 */
    private void adjustStatusTextByPrefix(View view, float deltaSp, String... prefixes) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            String value = String.valueOf(text.getText()).trim();
            for (String prefix : prefixes) {
                if (value.startsWith(prefix)) {
                    adjustTextTreeSize(text, deltaSp);
                    return;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                adjustStatusTextByPrefix(group.getChildAt(i), deltaSp, prefixes);
        }
    }

    /** 单行呼号以目标字号显示；只有超出左右边界时才等比缩到可用宽度。 */
    private void fitSingleLineText(TextView view, float targetSp, float minSp) {
        if (view == null) return;
        int available = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight() - dp(2);
        if (available <= 0) return;
        float density = getResources().getDisplayMetrics().scaledDensity;
        float targetPx = targetSp * density;
        float minPx = Math.max(6f, minSp) * density;
        view.setTextScaleX(1f);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, targetPx);
        String value = view.getText() == null ? "" : view.getText().toString();
        float measured = view.getPaint().measureText(value);
        float fittedPx = targetPx;
        if (measured > available && measured > 0f)
            fittedPx = Math.max(minPx, targetPx * available / measured);
        if (Math.abs(view.getTextSize() - fittedPx) > 0.5f)
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fittedPx);
        float fittedWidth = view.getPaint().measureText(value);
        view.setTextScaleX(fittedWidth > available && fittedWidth > 0f
                ? Math.max(0.65f, available / fittedWidth) : 1f);
    }

    private LinearLayout buildEnhancedCallLine(TextView source) {
        if (source == null) return null;
        float density = getResources().getDisplayMetrics().scaledDensity;
        float baseSp = source.getTextSize() / density;
        final TextView main = tv("", 24, TXT);
        final TextView id = tv("", 18, SUB);
        final TextView country = tv("", 18, SUB);
        main.setTypeface(null, Typeface.BOLD);
        final float targetMainSp = Math.min(64f, (baseSp * 2f + 6f) * 1.5f);
        final float targetSubSp = Math.max(12f, Math.min(22f, baseSp * 0.85f));
        main.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, targetMainSp);
        id.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, targetSubSp);
        country.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, targetSubSp);
        TextView[] parts = new TextView[]{main, id, country};
        for (TextView part : parts) {
            part.setGravity(Gravity.CENTER);
            part.setHorizontallyScrolling(false);
            part.setIncludeFontPadding(false);
            part.setSingleLine(false);
            part.setMaxLines(2);
            part.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        // 纯呼号目标放大 50%，但必须单行显示；超宽时只缩小到刚好适配状态卡宽度。
        main.setSingleLine(true);
        main.setMaxLines(1);
        main.setEllipsize(null);
        main.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                fitSingleLineText(main, targetMainSp, Math.max(8f, baseSp)));
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(main, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        idLp.topMargin = dp(2);
        block.addView(id, idLp);
        LinearLayout.LayoutParams countryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countryLp.topMargin = dp(2);
        block.addView(country, countryLp);
        final Runnable sync = () -> {
            String raw = source.getText() == null ? "" : source.getText().toString().replace("\r", "");
            String[] lines = raw.split("\n", 2);
            String head = lines.length > 0 ? lines[0].trim() : "";
            String countryText = lines.length > 1 ? lines[1].trim() : "";
            String name = head;
            String idText = "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(.*?)\\s*\\((\\d{2,})\\)\\s*(.*)$").matcher(head);
            if (m.matches()) {
                name = m.group(1).trim();
                idText = "(" + m.group(2) + ")";
                if (countryText.isEmpty()) countryText = m.group(3).trim();
            }
            if (name.isEmpty()) name = head;
            main.setText(name);
            id.setText(idText);
            country.setText(countryText);
            id.setVisibility(idText.isEmpty() ? View.GONE : View.VISIBLE);
            country.setVisibility(countryText.isEmpty() ? View.GONE : View.VISIBLE);
            main.post(() -> {
                int color = source.getCurrentTextColor();
                main.setTextColor(color);
                id.setTextColor(withAlpha(color, 205));
                country.setTextColor(withAlpha(color, 205));
                fitSingleLineText(main, targetMainSp, Math.max(8f, baseSp));
            });
        };
        source.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { sync.run(); }
        });
        sync.run();
        return block;
    }

    private boolean containsStatusText(View view, String... needles) {
        if (view == null) return false;
        if (view instanceof TextView) {
            String value = String.valueOf(((TextView) view).getText());
            for (String needle : needles) if (value.contains(needle)) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsStatusText(group.getChildAt(i), needles)) return true;
        }
        return false;
    }

    private boolean isReceivingStatus(View stateView, TextView call) {
        if (containsStatusText(stateView, "发射", "发送")) return false;
        if (containsStatusText(stateView, "接收")) return true;
        if (call == null) return false;
        String value = String.valueOf(call.getText()).trim();
        if (value.isEmpty() || value.contains("等待呼叫") || value.contains("未连接")
                || value.contains("未登录") || value.contains("正在连接")) return false;
        return call.getCurrentTextColor() == RED;
    }

    private void applyDynamicCallState(LinearLayout card, TextView normalCall, View enhancedCall,
                                       TextView mute, boolean receiving) {
        if (card == null || normalCall == null || enhancedCall == null) return;
        if (receiving) {
            normalCall.animate().cancel();
            normalCall.setVisibility(View.GONE);
            enhancedCall.animate().cancel();
            enhancedCall.setVisibility(View.VISIBLE);
            enhancedCall.setAlpha(0f);
            enhancedCall.setScaleX(0.90f);
            enhancedCall.setScaleY(0.90f);
            enhancedCall.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start();
            card.setPadding(dp(20), dp(20), dp(20), dp(20));
            card.setMinimumHeight(dp(tinyScreen ? 155 : 195));
            if (mute != null) mute.setTranslationY(dp(6));
        } else {
            enhancedCall.animate().cancel();
            enhancedCall.setVisibility(View.GONE);
            normalCall.animate().cancel();
            normalCall.setVisibility(View.VISIBLE);
            normalCall.setAlpha(0f);
            normalCall.animate().alpha(1f).setDuration(120L).start();
            card.setPadding(dp(15), dp(13), dp(15), dp(13));
            card.setMinimumHeight(0);
            if (mute != null) mute.setTranslationY(dp(2));
        }
    }

    private void bindDynamicEnhancedCall(LinearLayout card, View stateView, TextView normalCall,
                                         View enhancedCall, TextView mute) {
        final boolean[] lastReceiving = {false};
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (!card.isAttachedToWindow()) return;
            boolean receiving = isReceivingStatus(stateView, normalCall);
            if (receiving != lastReceiving[0]) {
                lastReceiving[0] = receiving;
                applyDynamicCallState(card, normalCall, enhancedCall, mute, receiving);
            }
            card.postDelayed(poll[0], 120L);
        };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { card.post(poll[0]); }
            @Override public void onViewDetachedFromWindow(View v) { card.removeCallbacks(poll[0]); }
        });
        applyDynamicCallState(card, normalCall, enhancedCall, mute, false);
    }

    private LinearLayout statusCard(TextView mute, View stateView, TextView call,
                                   TextView group, TextView network, TextView... extras) {
        boolean dynamicEnhanced = callsignEnhancedLayout() && call != null;
        LinearLayout card = card();
        card.setBackground(statusCardBg());
        card.setElevation(dp(6));
        card.setTranslationZ(dp(2));
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout enhancedCallBlock = null;
        if (dynamicEnhanced) {
            if (stateView != null && stateView.getParent() == null) col.addView(stateView);
            col.addView(call);
            enhancedCallBlock = buildEnhancedCallLine(call);
            enhancedCallBlock.setVisibility(View.GONE);
            LinearLayout.LayoutParams enhancedLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            enhancedLp.topMargin = dp(6);
            col.addView(enhancedCallBlock, enhancedLp);
            if (group != null && group.getParent() == null) col.addView(group);
            if (network != null && network.getParent() == null) col.addView(network);
            if (extras != null) for (TextView e : extras)
                if (e != null && e.getParent() == null) col.addView(e);
        } else {
            if (stateView != null) col.addView(stateView);
            if (call != null) col.addView(call);
            if (group != null) col.addView(group);
            if (network != null) col.addView(network);
            if (extras != null) for (TextView e : extras)
                if (e != null && e.getParent() == null) col.addView(e);
        }
        row.addView(col);
        if (mute != null) {
            mute.setTranslationY(dp(2));
            row.addView(mute);
        }
        LinearLayout live = new LinearLayout(this);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setGravity(Gravity.CENTER_VERTICAL);
        View accent = new View(this);
        accent.setBackground(roundBg(ACCENT, 2));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                dp(3), ViewGroup.LayoutParams.MATCH_PARENT);
        alp.setMargins(0, dp(2), dp(12), dp(2));
        live.addView(accent, alp);
        live.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(live);
        if (enhancedCallBlock != null)
            bindDynamicEnhancedCall(card, stateView, call, enhancedCallBlock, mute);
        return card;
    }
    private LinearLayout row(String label, View v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(label, 12, SUB);
        t.setTypeface(null, Typeface.BOLD);
        t.setLetterSpacing(0.02f);
        t.setWidth(dp(98));
        r.addView(t);
        r.setMinimumHeight(dp(52));
        r.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        v.setLayoutParams(lp);
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            ts(e, 14);
            e.setTextColor(TXT);
            e.setHintTextColor(withAlpha(SUB, 170));
            e.setBackground(roundStroke(SURFACE2, HAIRLINE, 10));
            e.setPadding(dp(12), dp(10), dp(12), dp(10));
            e.setMinHeight(dp(46));
        } else if (v instanceof SeekBar) {
            v.setFocusable(true);
            v.setFocusableInTouchMode(false);
            v.setPadding(0, dp(4), 0, dp(4));
        }
        r.addView(v);
        return r;
    }
 /** 通话组按钮的立体渐变背景 */
    private GradientDrawable tgButtonBg(boolean on) {
        GradientDrawable d = on
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        accentGradient3())
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        (DARK ? new int[]{0xFF1A2330, 0xFF141B24} : new int[]{0xFFFFFFFF, 0xFFF2F6FA}));
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), on ? 0x55FFFFFF : HAIRLINE);
        return d;
    }

 /** 圆角纯色背景 */
    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private android.graphics.drawable.StateListDrawable stateBg(int normal, int pressed, int radiusDp) {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, roundBg(pressed, radiusDp));
        d.addState(new int[]{android.R.attr.state_focused}, roundStroke(pressed, withAlpha(ACCENT, 120), radiusDp));
        d.addState(new int[]{}, roundStroke(normal, HAIRLINE, radiusDp));
        return d;
    }

 /** 圆角 + 细描边，做科技感层次 */
    private GradientDrawable roundStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable d = roundBg(color, radiusDp);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private GradientDrawable cardBg() {
        int top = blend(CARD, 0xFFFFFFFF, DARK ? 0.06f : 0.72f);
        int bottom = blend(CARD, SURFACE2, DARK ? 0.52f : 0.42f);
        int edge = blend(SURFACE2, DARK ? 0xFF000000 : 0xFFFFFFFF, DARK ? 0.18f : 0.55f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ top, CARD, bottom, edge });
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), DARK ? 0x28FFFFFF : 0xF2FFFFFF);
        return d;
    }

    private GradientDrawable statusCardBg() {
        int top = blend(CARD, ACCENT, DARK ? 0.18f : 0.08f);
        int bottom = blend(CARD, SURFACE2, DARK ? 0.55f : 0.40f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ top, CARD, bottom });
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1), withAlpha(ACCENT, DARK ? 105 : 70));
        return d;
    }

    /** 柔光玻璃面板：顶部高光、底部微暗边，配合阴影形成悬浮层。 */
    private GradientDrawable glassPanelBg(int radiusDp) {
        int top = blend(SURFACE2, 0xFFFFFFFF, DARK ? 0.05f : 0.72f);
        int bottom = blend(SURFACE2, DARK ? 0xFF000000 : CARD, DARK ? 0.30f : 0.30f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ top, SURFACE2, bottom });
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), DARK ? 0x26FFFFFF : 0xE8FFFFFF);
        return d;
    }

    private GradientDrawable glassNavBg(boolean on) {
        if (!on) return glassPanelBg(12);
        int base = blend(ACCENT, 0xFF031827, DARK ? 0.60f : 0.68f);
        int top = blend(base, ACCENT, 0.22f);
        int bottom = blend(base, 0xFF000000, 0.24f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ top, base, bottom });
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), blend(ACCENT, 0xFFFFFFFF, DARK ? 0.28f : 0.18f));
        return d;
    }

    /** Keyboard focus is amber; selected mode is deep accent. Both remain distinguishable. */
    private GradientDrawable glassNavFocusBg(boolean selected) {
        int base = blend(selected ? ACCENT : 0xFF374151, 0xFF071827, 0.72f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ blend(base, 0xFFFFC107, 0.16f), base, blend(base, 0xFF000000, 0.28f) });
        d.setCornerRadius(dp(12));
        d.setStroke(dp(3), 0xFFFFC107);
        return d;
    }

    private GradientDrawable glassColorBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ blend(color, 0xFFFFFFFF, 0.20f), color, blend(color, 0xFF000000, 0.16f) });
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), withAlpha(0xFFFFFFFF, 90));
        return d;
    }


    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private View circleIcon(int drawableRes, int color, int sizeDp) {
        FrameLayout box = new FrameLayout(this);
        int size = dp(sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(0, 0, dp(12), 0);
        box.setLayoutParams(lp);
        box.setBackground(roundBg(withAlpha(color, 34), 999));
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(color);
        int pad = dp(Math.max(10, sizeDp / 4));
        iv.setPadding(pad, pad, pad, pad);
        box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private LinearLayout settingTile(int drawableRes, int color, String title, String subtitle,
                                     boolean arrow, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(0, dp(4), 0, dp(4));
        r.addView(circleIcon(drawableRes, color, 44));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(title, 14, TXT);
        t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        if (subtitle != null && subtitle.length() > 0) col.addView(tv(subtitle, 11, SUB));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (arrow) {
            TextView a = tv("\u203a", 28, SUB);
            a.setGravity(Gravity.CENTER);
            r.addView(a);
        }
        if (click != null) {
            r.setClickable(true);
            r.setFocusable(true);
            r.setOnClickListener(click);
        }
        return r;
    }

    private LinearLayout toggleTile(String title, String subtitle, boolean checked,
                                    CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setMinimumHeight(dp(74));
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        tile.setFocusable(true);
        tile.setClickable(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView led = tv("●", 8, ACCENT);
        led.setPadding(0, 0, dp(5), 0);
        top.addView(led);
        TextView t = tv(title, 14, TXT);
        t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setText("");
        sw.setShowText(false);
        sw.setMinWidth(0);
        sw.setMinimumWidth(0);
        sw.setPadding(0, 0, 0, 0);
        sw.setScaleX(0.90f);
        sw.setScaleY(0.90f);
        sw.setContentDescription(title);
        int offThumb = withAlpha(SUB, DARK ? 150 : 120);
        int offTrack = withAlpha(SUB, DARK ? 95 : 70);
        sw.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, offThumb}));
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{withAlpha(ACCENT, 205), offTrack}));
        top.addView(sw, new LinearLayout.LayoutParams(dp(48), dp(34)));
        tile.addView(top);

        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = tv(subtitle, 10, SUB);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(sub);
        }

        Runnable paint = () -> {
            boolean on = sw.isChecked();
            t.setTextColor(on ? ACCENT : TXT);
            led.setTextColor(on ? ACCENT : SUB);
            tile.setBackground(on
                    ? roundStroke(withAlpha(ACCENT, DARK ? 42 : 22), withAlpha(ACCENT, 110), 14)
                    : roundStroke(SURFACE2, HAIRLINE, 14));
            tile.setContentDescription(title + (on ? "，已开启" : "，已关闭"));
        };
        sw.setOnCheckedChangeListener((b, c) -> {
            paint.run();
            if (listener != null) listener.onCheckedChanged(b, c);
        });
        tile.setOnClickListener(v -> sw.toggle());
        paint.run();
        return tile;
    }

    private Switch switchRow(LinearLayout parent, String title, String sub, boolean checked,
                             CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(tinyScreen ? 44 : 50));
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv(title, 13, TXT);
        t.setTypeface(null, Typeface.BOLD);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView d = tv(sub, 10, SUB);
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setText("");
        sw.setShowText(false);
        sw.setMinWidth(0);
        sw.setMinimumWidth(0);
        sw.setPadding(0, 0, 0, 0);
        sw.setScaleX(0.90f);
        sw.setScaleY(0.90f);
        sw.setContentDescription(title);
        sw.setFocusable(true);
        sw.setFocusableInTouchMode(false);
        sw.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, withAlpha(SUB, DARK ? 150 : 120)}));
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{withAlpha(ACCENT, 205), withAlpha(SUB, DARK ? 95 : 70)}));
        row.addView(sw, new LinearLayout.LayoutParams(dp(48), dp(34)));
        row.setOnClickListener(v -> sw.toggle());
        if (listener != null) sw.setOnCheckedChangeListener(listener);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View divider = new View(this);
        divider.setBackgroundColor(withAlpha(HAIRLINE, DARK ? 170 : 210));
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return sw;
    }


    private View accentChip(String key, String label, boolean selected) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(3), dp(2), dp(3));
        item.setContentDescription("主题颜色：" + label);
        FrameLayout dot = new FrameLayout(this);
        GradientDrawable d;
        if ("cyber".equals(key)) {
            d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{ 0xFFFF2BD6, 0xFF00E5FF });
            d.setCornerRadius(dp(999));
        } else {
            d = roundBg(accentColorFor(key, DARK), 999);
        }
        d.setStroke(dp(selected ? 3 : 1), selected ? TXT : withAlpha(SUB, 80));
        dot.setBackground(d);
        if (selected) {
            TextView check = tv("✓", 13, 0xFFFFFFFF);
            check.setGravity(Gravity.CENTER);
            check.setTypeface(null, Typeface.BOLD);
            dot.addView(check, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        int dotSize = dp(tinyScreen ? 28 : 32);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.gravity = Gravity.CENTER_HORIZONTAL;
        item.addView(dot, dotLp);
        TextView t = tv(label, tinyScreen ? 8 : 9, selected ? ACCENT : SUB);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        item.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setOnClickListener(v -> {
            if ("cyber".equals(key)) p.set("theme", "dark");
            p.set("themeAccent", key);
            reThemeInPlace();
        });
        return item;
    }

    private void addToggleGrid(LinearLayout parent, java.util.List<View> tiles) {
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp1.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(tiles.get(i), lp1);
            if (i + 1 < tiles.size()) {
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp2.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(tiles.get(i + 1), lp2);
            } else {
                View spacer = new View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            }
            parent.addView(row);
        }
    }

    private TextView tableCell(String text, float weight, int color, boolean bold) {
        TextView t = tv(text, 11, color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(0, 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }


    private interface PickerChoice { void onChoice(int index); }

    private void showSearchablePicker(String title, String[] labels, int current, PickerChoice choice) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(4));
        final EditText search = new EditText(this);
        search.setSingleLine(true); search.setHint("搜索"); search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextColor(TXT); search.setHintTextColor(SUB); box.addView(search);
        final java.util.ArrayList<String> visible = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> mapping = new java.util.ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, visible);
        final ListView list = new ListView(this); list.setAdapter(adapter); list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("取消", null).create();
        final Runnable refilter = () -> {
            String q = search.getText() == null ? "" : search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            visible.clear(); mapping.clear(); int selectedPos = -1;
            for (int i = 0; i < labels.length; i++) {
                String label = labels[i] == null ? "" : labels[i];
                if (q.isEmpty() || label.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    if (i == current) selectedPos = visible.size();
                    visible.add(label); mapping.add(i);
                }
            }
            adapter.notifyDataSetChanged();
            if (selectedPos >= 0) list.setItemChecked(selectedPos, true);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { refilter.run(); }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mapping.size()) return;
            choice.onChoice(mapping.get(position)); dialog.dismiss();
        });
        refilter.run(); dialog.show();
    }

    private void showBmServerPicker() {
        String[] labels = new String[BmServers.ALL.length]; int current = 0;
        for (int i = 0; i < BmServers.ALL.length; i++) {
            String[] row = BmServers.ALL[i]; labels[i] = row[1] + " · " + row[2];
            if (row[0].equals(p.bmServerKey())) current = i;
        }
        showSearchablePicker("选择 DMR 主站", labels, current, which -> {
            String key = BmServers.ALL[which][0]; p.setBmServer(key);
            if (bmServerValue != null) bmServerValue.setText(p.bmServerName());
            if (bmServerLabel != null) bmServerLabel.setText("当前网络: " + p.bmServerName() + " · 已选择 ▾");
            setBmStatus(false, false, null);
            if (bmHbp != null && bmHbp.isRunning()) { disconnectBm(); bmLastConnectAttempt = 0; connectBm(); }
        });
    }


    private String pttTargetLabel(String target) {
        if ("auto".equals(target)) return "自动（跟随当前页面）";
        if ("bm".equals(target)) return "DMR";
        if ("analog".equals(target)) return "模拟互联";
        if ("network".equals(target)) return "Net";
        if ("dstar".equals(target)) return "D-Star";
        if ("ysf".equals(target)) return "YSF";
        if ("p25".equals(target)) return "P25";
        if ("nxdn".equals(target)) return "NXDN";
        if ("nrl".equals(target)) return "NRL";
        return "数字中继";
    }

    private void showPttModePicker() {
        final String[] keys = {"auto", "digital", "bm", "analog", "network", "dstar", "ysf", "p25", "nxdn", "nrl"};
        String[] labels = {"自动（跟随最后语音页面/房间）", "数字中继", "DMR", "模拟互联", "Net", "D-Star", "YSF", "P25", "NXDN", "NRL"};
        int current = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(p.pttTargetMode())) current = i;
        new AlertDialog.Builder(this)
                .setTitle("悬浮/外置 PTT 发射模式")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    p.set("pttTargetMode", keys[which]);
                    d.dismiss();
                    if (p.overlayPtt()) requestOverlayPtt();
                    toast("PTT 发射模式: " + labels[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** [3.2.30] 设备按键绑定：三步走 —— 按下 → 再按一次确认 → 检测"松开"信号。
     *  为什么要三步：很多安卓对讲机的实体 PTT 只发"按下"不发"松开"，
     *  直接绑会导致"按了没反应"或"一直发射"。 */
    private void showKeyCaptureDialog() {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设备 PTT 键 · 第 1/3 步")
                .setMessage("请按下要用作 PTT 的设备按键。\n\n" +
                        "如果这个键是“只发按下、不发松开”的类型（不少安卓对讲机的实体 PTT 就是这样），" +
                        "第 3 步会让你再绑一个“松开键”，或者改用“点按开关”模式。")
                .setNegativeButton("取消", null)
                .setNeutralButton("清除绑定", (x, w) -> clearExtKeyBinding())
                .create();
        final long t0 = System.currentTimeMillis();
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                try { p.extKeyLogAdd(keyCode, event.getAction(), "inapp"); } catch (Throwable ignored) {}
                dialog.dismiss();
                confirmExtKeyStep2(keyCode, "inapp");
            }
            return true;
        });
        d.show();
        // 有些机型的键只送给无障碍服务：同时盯一眼诊断日志
        ui.postDelayed(new Runnable() {
            int last = -1;
            @Override public void run() {
                if (!d.isShowing()) return;
                try {
                    int code = p.extKeyLogCode();
                    long at = p.extKeyLogAt();
                    String src = p.extKeyLogSource();
                    if (code > 0 && at > t0 && code != last && "a11y".equals(src)) {
                        last = code;
                        d.dismiss();
                        toast("已从无障碍服务捕获按键");
                        confirmExtKeyStep2(code, "a11y");
                        return;
                    }
                } catch (Throwable ignored) {}
                ui.postDelayed(this, 400);
            }
        }, 400);
    }

    private void confirmExtKeyStep2(final int code, final String src) {
        final String name = android.view.KeyEvent.keyCodeToString(code);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设备 PTT 键 · 第 2/3 步")
                .setMessage("再按一次同一个按键确认：" + name + "（键码 " + code + "）")
                .setNegativeButton("取消", null)
                .setNeutralButton("重新选键", (x, w) -> showKeyCaptureDialog())
                .create();
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (keyCode == code) { dialog.dismiss(); detectExtKeyRelease(code, src); }
                else toast("不是同一个键，请再按： " + name);
            }
            return true;
        });
        d.show();
    }

    private void detectExtKeyRelease(final int code, final String src) {
        final long t0 = System.currentTimeMillis();
        final boolean[] got = { false };
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设备 PTT 键 · 第 3/3 步")
                .setMessage("按住刚才那个键约 2 秒，然后松开。\n\n正在检测它是否自带“松开”信号…")
                .setNegativeButton("跳过检测", (x, w) -> askNoRelease(code, src))
                .create();
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == code && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                got[0] = true;
                dialog.dismiss();
                saveExtKeyBinding(code, src, true, "hold", 0, "");
                toast("这个键自带松开信号，已按“按住发射”绑定");
            }
            return true;
        });
        d.show();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (got[0] || !d.isShowing()) return;
                if (System.currentTimeMillis() - t0 < 6000) { ui.postDelayed(this, 300); return; }
                try {
                    String log = p.extKeyLog();
                    if (p.extKeyLogCode() == code && p.extKeyLogAt() > t0
                            && log != null && log.contains(code + "|UP")) {
                        d.dismiss();
                        saveExtKeyBinding(code, src, true, "hold", 0, "");
                        toast("检测到松开信号，已按“按住发射”绑定");
                        return;
                    }
                } catch (Throwable ignored) {}
                d.dismiss();
                askNoRelease(code, src);
            }
        }, 300);
    }

    private void askNoRelease(final int code, final String src) {
        new AlertDialog.Builder(this)
                .setTitle("这个按键没有“松开”信号")
                .setMessage("很多安卓对讲机的实体 PTT 只发“按下”、不发“松开”。两种解决办法：\n\n" +
                        "1) 再绑一个“松开键”：松开 PTT 的同时按它；\n" +
                        "2) 改成“点按开关”：按一下开始发射，再按一下停止。")
                .setPositiveButton("绑一个松开键", (x, w) -> captureReleaseKey(code, src))
                .setNeutralButton("用点按开关模式", (x, w) -> saveExtKeyBinding(code, src, false, "toggle", 0, ""))
                .setNegativeButton("取消", null)
                .show();
    }

    private void captureReleaseKey(final int pttCode, final String src) {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("按住 PTT 松开时，按这个键")
                .setMessage("请按下“松开键”。\n\n提示：有的机器“按住/松开”是同一个物理键但键码不同，那就再按一次同一个键试试。")
                .setNegativeButton("取消", null)
                .create();
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (keyCode == pttCode) { toast("这和 PTT 键一样，请换一个键"); return true; }
                dialog.dismiss();
                saveExtKeyBinding(pttCode, src, false, "hold", keyCode, android.view.KeyEvent.keyCodeToString(keyCode));
            }
            return true;
        });
        d.show();
    }

    private void saveExtKeyBinding(int code, String src, boolean hasRelease, String mode, int relCode, String relName) {
        p.set("extPttKeyCode", String.valueOf(code));
        p.set("extPttKeyName", android.view.KeyEvent.keyCodeToString(code));
        p.set("extPttKeySource", src == null ? "" : src);
        p.set("extPttKeyHasRelease", hasRelease ? "1" : "0");
        p.set("extPttKeyMode", mode == null ? "hold" : mode);
        p.set("extPttReleaseKeyCode", String.valueOf(relCode));
        p.set("extPttReleaseKeyName", relName == null ? "" : relName);
        p.set("extPttEnabled", "1");        // 绑定成功顺手打开外接 PTT 总开关
        try { ensureKeepAliveService(); } catch (Throwable ignored) {}
        updateExtKeyStatus();
        toast("设备按键已绑定：" + android.view.KeyEvent.keyCodeToString(code)
                + ("toggle".equals(mode) ? "（点按开关）" : (hasRelease ? "（按住发射）" : "（按住发射，松开键 " + relName + "）")));
    }

    private void clearExtKeyBinding() {
        p.set("extPttKeyCode", "0");
        p.set("extPttKeyName", "未设置");
        p.set("extPttReleaseKeyCode", "0");
        p.set("extPttReleaseKeyName", "");
        p.set("extPttKeyHasRelease", "1");
        p.set("extPttKeyMode", "hold");
        p.set("extPttKeySource", "");
        updateExtKeyStatus();
        toast("已清除设备按键绑定");
    }

    /** [3.3.0] 广播 PTT：内置厂商动作 + 用户手动追加，不再依赖无障碍 KeyEvent。 */
    private void showBroadcastPttDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("内置广播动作：").append(ArchPttBroadcast.builtInCount()).append(" 条\n");
        sb.append("自定义广播值：").append(p.extBroadcastCount()).append(" 条\n");
        String map = p.extBroadcastMap();
        if (map != null && !map.trim().isEmpty()) {
            String[] lines = map.trim().split("\n");
            sb.append("\n");
            for (int i = 0; i < Math.min(lines.length, 8); i++) sb.append("• ").append(lines[i]).append("\n");
            if (lines.length > 8) sb.append("…共 ").append(lines.length).append(" 条\n");
        }
        String last = p.extBroadcastLogLast();
        if (last != null && !last.trim().isEmpty()) sb.append("\n最近收到：").append(last);
        sb.append("\n\n捕获下一个广播：点“捕获下一个”，然后按一下设备 PTT 键。\n")
          .append("手动添加：输入厂商广播 action，再选择按下/松开/切换。");
        new AlertDialog.Builder(this)
                .setTitle("广播 PTT 键")
                .setMessage(sb.toString())
                .setPositiveButton("捕获下一个", (d, w) -> captureNextBroadcast())
                .setNeutralButton("手动添加", (d, w) -> manualBroadcastPtt())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void captureNextBroadcast() {
        ensureKeepAliveService();
        reloadPttReceiver();
        ArchAppState.lastBroadcastAction = "";
        ArchAppState.lastBroadcastAt = 0L;
        final long t0 = System.currentTimeMillis();
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("捕获广播 PTT")
                .setMessage("请按一下设备上的 PTT 键。\n\n只要设备发出标准厂商广播，就会自动抓到；没有反应就点“手动添加”。")
                .setNegativeButton("取消", null)
                .create();
        d.show();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                String a = ArchAppState.lastBroadcastAction;
                long at = ArchAppState.lastBroadcastAt;
                if (a != null && !a.trim().isEmpty() && at >= t0) {
                    d.dismiss();
                    askBroadcastType(a.trim());
                    return;
                }
                if (System.currentTimeMillis() - t0 > 20000) {
                    d.dismiss();
                    toast("没有收到广播，请手动添加 action");
                    return;
                }
                ui.postDelayed(this, 300);
            }
        }, 300);
    }

    private void manualBroadcastPtt() {
        final EditText et = new EditText(this);
        et.setHint("例如 com.meigsmart.meigkeyaccessibility.onkeyevent");
        et.setTextSize(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 6, 18, 0);
        box.addView(tv("输入设备发出的广播 action（可从“广播诊断”复制）：", 11, SUB));
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("手动添加广播 PTT")
                .setView(box)
                .setPositiveButton("下一步", (d, w) -> {
                    String a = et.getText() == null ? "" : et.getText().toString().trim();
                    if (a.isEmpty()) { toast("action 不能为空"); return; }
                    if (a.indexOf(' ') >= 0 || a.indexOf('|') >= 0 || a.indexOf('\n') >= 0) { toast("action 不能包含空格、竖线或换行"); return; }
                    askBroadcastType(a);
                })
                .setNeutralButton("清除自定义", (d, w) -> {
                    p.extBroadcastClear();
                    reloadPttReceiver();
                    updateExtKeyStatus();
                    toast("已清除自定义广播值");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askBroadcastType(final String action) {
        String guess = ArchPttBroadcast.customTypeOf(action, p.extBroadcastMap());
        if (guess == null) guess = ArchPttBroadcast.typeOf(action);
        if (guess == null) guess = ArchPttBroadcast.inferType(action);
        String hint = (guess == null ? "" : "\n\n系统建议：" + ArchPttBroadcast.typeLabel(guess));
        String[] labels = {"按下 (down)", "松开 (up)", "单次/切换 (toggle)"};
        new AlertDialog.Builder(this)
                .setTitle("选择广播类型")
                .setMessage("action：\n" + action + hint)
                .setItems(labels, (d, w) -> saveBroadcastPtt(w == 0 ? "D" : (w == 1 ? "U" : "T"), action))
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveBroadcastPtt(String type, String action) {
        String a = action == null ? "" : action.trim();
        if (a.isEmpty()) return;
        if (a.indexOf(' ') >= 0 || a.indexOf('|') >= 0 || a.indexOf('\n') >= 0) { toast("action 不能包含空格、竖线或换行"); return; }
        p.extBroadcastAdd(type, a);
        p.set("extPttEnabled", "1");
        p.set("extPttKeySource", "broadcast");
        try { ensureKeepAliveService(); } catch (Throwable ignored) {}
        reloadPttReceiver();
        updateExtKeyStatus();
        toast("已添加广播 PTT：" + ArchPttBroadcast.typeLabel(type));
    }

    private void reloadPttReceiver() {
        try {
            Intent keep = new Intent(this, ArchKeepAliveService.class)
                    .setAction(ArchKeepAliveService.ACTION_RELOAD_PTT);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
        } catch (Throwable ignored) {}
    }

    private void updateExtKeyStatus() {
        if (extPttKeyStatus == null) return;
        boolean en = false;
        int custom = 0;
        String last = "";
        try { en = p.externalPttEnabled(); } catch (Throwable ignored) {}
        try { custom = p.extBroadcastCount(); last = p.extBroadcastLogLast(); } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("广播 PTT：").append(en ? "已启用" : "未启用")
          .append("（内置 ").append(ArchPttBroadcast.builtInCount()).append(" 条，自定义 ").append(custom).append(" 条）");
        if (last != null && !last.trim().isEmpty()) sb.append("\n最近广播：").append(last);
        if (!en) sb.append("\n点上面“外接 PTT”开关启用。");
        extPttKeyStatus.setText(sb.toString());
    }

    /** [3.3.0] 广播值 + 老键码兼容诊断。 */
    private void showKeyDiagDialog() {
        try { ensureKeepAliveService(); reloadPttReceiver(); } catch (Throwable ignored) {}
        String bc = p.extBroadcastLog();
        String key = p.extKeyLog();
        String bcBody = (bc == null || bc.trim().isEmpty())
                ? "还没有收到广播值。按一下设备 PTT 键，再回来看这里。"
                : bc;
        String keyBody = (key == null || key.trim().isEmpty()) ? "暂无（默认关闭，仅老设备兼容时使用）" : key;
        final String body = "【广播值】\n" + bcBody + "\n\n【无障碍键码（兼容）】\n" + keyBody;
        final String copy = "ARCH PTT 诊断\n" + body;
        new AlertDialog.Builder(this)
                .setTitle("广播/按键诊断")
                .setMessage(body + "\n\n广播格式：action|来源|时间\n键码格式：键码|按下或松开|来源|时间")
                .setPositiveButton("复制", (d, w) -> {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("arch-keys", copy));
                        toast("已复制，粘贴发给开发者即可");
                    } catch (Throwable t) { toast("复制失败"); }
                })
                .setNeutralButton("清空", (d, w) -> {
                    p.set("extBroadcastLog", "");
                    p.set("extKeyLog", "");
                    toast("已清空");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showCrashLog() {
        String txt = ArchCrash.read(this);
        if (txt == null || txt.trim().isEmpty()) txt = "没有记录到崩溃日志。";
        final String body = txt;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("最近一次崩溃日志")
                    .setMessage(body.length() > 4000 ? body.substring(0, 4000) : body)
                    .setPositiveButton("清空", (d, w) -> { ArchCrash.clear(this); toast("已清空"); })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable t) { toast("打开失败: " + t); }
    }

    private void showLegalDialog() {
        String text;
        try (java.io.InputStream in = getAssets().open("disclaimer.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            text = "ARCH 开源许可与免责声明\n\n详见项目主页：https://arch.hljrsa.cn";
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView body = tv(text, 12, TXT);
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        sv.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("开源许可与免责声明")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void requestBatteryOptimizationExemption() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开后台常驻设置，请手动允许后台运行");
        }
    }

    private void ensureKeepAliveService() {
        try {
            Intent keep = new Intent(this, ArchKeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
        } catch (Throwable ignored) {}
    }

    private void rebuildNavForSettings() {
        if (!pageEnabled(page)) pendingPage = defaultPage(); else pendingPage = page;
        recreatePreservingScroll();
    }
    private void recreatePreservingScroll() {
        pendingSettingsScrollY = settingsScroll == null ? 0 : settingsScroll.getScrollY();
        // 纯 UI 重建不要顺带触发账号校验/自动连接；否则切主题会弹出“正在校验账号”。
        suppressAutoConnectUntil = System.currentTimeMillis() + 3000L;
        recreate();
    }

    /** 主题切换只重建视图，不重建 Activity，因此不会断开连接或触发重新登录。 */
    private void reThemeInPlace() {
        final int keepPage = page;
        final int keepScroll = settingsScroll == null ? 0 : settingsScroll.getScrollY();
        rebuildingUiInPlace = true;
        try {
            setTheme(p.dark() ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
            applyThemeColors(p.dark());
            computeUiScale();
            pendingPage = keepPage;
            pendingSettingsScrollY = keepScroll;
            buildUi();
            applyWindowTheme();
            applyKeepScreenOn();
        } catch (Throwable t) {
            log("主题切换失败: " + t);
        } finally {
            rebuildingUiInPlace = false;
        }
        toast("主题已切换，连接保持在线");
    }

    private boolean autoConnectSuppressed() {
        return System.currentTimeMillis() < suppressAutoConnectUntil;
    }

    private String currentPttTarget() {
        int m = mode;
        // 设置页没有自己的模式；外置 PTT 必须沿用进入设置前的最后一个语音页面。
        if (m < 0 || page == PAGE_SETTINGS) {
            try { m = p.lastVoiceMode(); } catch (Throwable ignored) { m = lastCommMode; }
        }
        if (m < 0 || m > 8) m = lastCommMode;
        if (m == 8) return "nrl";
        if (m == 7) return "nxdn";
        if (m == 6) return "p25";
        return m == 5 ? "ysf" : (m == 4 ? "dstar" : (m == 3 ? "network" : (m == 2 ? "bm" : (m == 1 ? "analog" : "digital"))));
    }

    private void externalPttDown() {
        if (page == PAGE_NET_RADIO) { toast("网络收音机模式仅接收，不发射"); return; }
        try { HyteraPdc760Bridge.noteActivityPttDown(this); } catch (Throwable ignored) {}
        if (p.pttWakeFocus()) wakeForPtt();
        String target = p.pttTargetMode();
        if ("auto".equals(target)) target = currentPttTarget();
        pttDownFor(target);
        // Activity 被海能达桌面销毁后，第一次按键常常发生在重连完成前。
        // 按住不放时继续重试目标链路，连上后立即起发；松开会取消。
        scheduleHardwarePttRetry(target);
    }
    private void externalPttUp() {
        cancelHardwarePttRetry();
        try { HyteraPdc760Bridge.noteActivityPttUp(this); } catch (Throwable ignored) {}
        pttUp();
    }

    private void scheduleHardwarePttRetry(final String target) {
        cancelHardwarePttRetry();
        hardwarePttRetryCount = 0;
        if (txing || hqTxRequested) return;
        hardwarePttRetryTask = new Runnable() {
            @Override public void run() {
                hardwarePttRetryTask = null;
                if (!ArchAppState.hwTx) return;
                if (txing || hqTxRequested) return;
                if (hardwarePttRetryCount++ >= 12) {
                    log("后台 PTT 等待连接超时: " + target);
                    return;
                }
                pttDownFor(target);
                if (!txing && !hqTxRequested && ArchAppState.hwTx)
                    ui.postDelayed(this, 2000L);
            }
        };
        ui.postDelayed(hardwarePttRetryTask, 700L);
    }

    private void cancelHardwarePttRetry() {
        if (hardwarePttRetryTask != null) {
            ui.removeCallbacks(hardwarePttRetryTask);
            hardwarePttRetryTask = null;
        }
        hardwarePttRetryCount = 0;
    }

    /** 海能达信道旋钮：按当前模式切换房间/通话组，不改变系统原生信道。 */
    private void handleHyteraKnob(final boolean next) {
        long now = System.currentTimeMillis();
        if (now - lastHyteraKnobAt < 120L) return;
        if (txing || hqTxRequested || networkConnecting || nrlConnecting
                || switchingGroup || bmAutoAttachRunning) return;
        if (page == PAGE_NET_RADIO) return;
        int target = (page == PAGE_SETTINGS || mode < 0 || mode > 8)
                ? lastCommMode : mode;
        lastHyteraKnobAt = now;
        boolean changed = false;
        if (target == 0) {
            if (TGS == null || TGS.length < 2) return;
            int pos = 0;
            for (int i = 0; i < TGS.length; i++) if (TGS[i][0] == p.tg()) { pos = i; break; }
            int nextPos = next ? (pos + 1) % TGS.length : (pos - 1 + TGS.length) % TGS.length;
            switchGroup(nextPos);
            changed = true;
        } else if (target == 2) {
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            for (String part : p.bmTalkgroups().split(",")) {
                if (part == null || part.trim().isEmpty()) continue;
                try { ids.add(Integer.parseInt(part.trim().split(":", 2)[0].trim())); } catch (Throwable ignored) {}
            }
            if (ids.size() < 2) return;
            int pos = 0;
            for (int i = 0; i < ids.size(); i++) if (ids.get(i) == p.bmTg()) { pos = i; break; }
            int nextPos = next ? (pos + 1) % ids.size() : (pos - 1 + ids.size()) % ids.size();
            switchBmTg(ids.get(nextPos));
            changed = true;
        } else if (target == 3) {
            if (NET_ROOMS == null || NET_ROOMS.size() < 2) return;
            String current = p.netRoom();
            int pos = 0;
            for (int i = 0; i < NET_ROOMS.size(); i++) if (NET_ROOMS.get(i)[0].equals(current)) { pos = i; break; }
            int nextPos = next ? (pos + 1) % NET_ROOMS.size() : (pos - 1 + NET_ROOMS.size()) % NET_ROOMS.size();
            beginRoomSwitch(NET_ROOMS.get(nextPos)[0]);
            changed = true;
        } else if (target == 8) {
            if (nrlGroups == null || nrlGroups.size() < 2 || nrl == null || !nrlActive) return;
            int pos = 0;
            for (int i = 0; i < nrlGroups.size(); i++) if (nrlGroups.get(i).id == p.nrlGroup()) { pos = i; break; }
            int nextPos = next ? (pos + 1) % nrlGroups.size() : (pos - 1 + nrlGroups.size()) % nrlGroups.size();
            NrlClient.Group group = nrlGroups.get(nextPos);
            p.setNrlGroup(group.id);
            nrlGroupNameCache = group.name;
            nrl.joinGroup(group.id);
            if (nrlGroupLine != null) nrlGroupLine.setText(nrlGroupDisplayText());
            buildNrlGroupButtons(nrlGroups);
            updateModeLabel();
            changed = true;
        }
        if (!changed && page != PAGE_SETTINGS) log("信道旋钮: 当前模式没有可切换的房间或通话组");
    }

    private void wakeForPtt() {
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        } catch (Throwable ignored) {}
    }

    /** [3.2.30] 界面自适应：按屏幕分辨率算基准缩放，再乘用户选的界面字号。
     *  改动点：① 下限从 0.68 抬到 0.80（小屏别再缩到看不清）② 参考 smallestScreenWidthDp
     *  ③ 用户可手动选 小/标准/大/特大（各种安卓对讲机屏幕差异太大，光靠自动不够）。 */
    private void computeUiScale() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float wdp = dm.widthPixels / Math.max(1f, dm.density);
        float hdp = dm.heightPixels / Math.max(1f, dm.density);
        // [3.2.45] 320x240 这类公网对讲机小屏：强制竖屏后宽高可能变成 240x320，
        // 不能只看 width/height 的固定方向，否则小屏适配会失效。
        float shortSide = Math.min(wdp, hdp);
        float longSide = Math.max(wdp, hdp);
        tinyScreen = shortSide <= 300f && longSide <= 460f;
        float base;
        if (tinyScreen) {
            base = 0.62f;
        } else {
            base = Math.min(wdp / 393f, hdp / 851f);
            try {
                int sw = getResources().getConfiguration().smallestScreenWidthDp;
                if (sw > 0) base = Math.min(base, sw / 393f);
            } catch (Throwable ignored) {}
            base = Math.max(0.80f, Math.min(1.15f, base));
        }
        float fontScale = getResources().getConfiguration().fontScale;
        if (fontScale > 1.0f) {
            // 小屏不能被 0.80 的下限抬回去，否则设置大字体时必然溢出。
            float minBase = tinyScreen ? 0.55f : 0.80f;
            base = Math.min(base, Math.max(minBase, 1.0f / fontScale));
        }
        uiScale = base;
        float uf = 0f;
        try { uf = (p == null) ? 0f : p.uiFontFactor(); } catch (Throwable ignored) {}
        userFontFactor = uf > 0f ? uf : 1f;
        // 320x240 上强制限制用户字号放大，优先保证布局完整。
        if (tinyScreen) userFontFactor = Math.min(userFontFactor, 1.0f);
        visibleRows = hdp < 620 ? 3 : (hdp < 720 ? 4 : 5);
    }

    private String uiFontLabel() {
        String m = "auto";
        try { m = p.uiFontMode(); } catch (Throwable ignored) {}
        if ("small".equals(m)) return "小（90%）";
        if ("normal".equals(m)) return "标准（100%）";
        if ("large".equals(m)) return "大（112%）";
        if ("xlarge".equals(m)) return "特大（125%）";
        return "自动（按屏幕分辨率自适应）";
    }

    private int pttHeightDp() {
        if (tinyScreen) return 92;   // [3.2.45] 320x240 屏
        return visibleRows <= 3 ? 145 : (visibleRows == 4 ? 165 : 190);
    }

    private int dp(int v) { return (int)(v * uiScale * getResources().getDisplayMetrics().density); }

    private TextView muteButton(final String key) {
        TextView v = tv("", 20, TXT);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(6), 0, dp(2), 0);
        v.setMinWidth(dp(36));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> toggleModeMute(key));
        return v;
    }

    private boolean modeMuted(String key) { return "1".equals(p.get("mute_" + key, "0")); }

    private String modeName(String key) {
        if ("digital".equals(key)) return "数字中继";
        if ("bm".equals(key)) return "DMR";
        if ("analog".equals(key)) return "模拟互联";
        if ("dstar".equals(key)) return "D-Star";
        if ("ysf".equals(key)) return "YSF";
        if ("p25".equals(key)) return "P25";
        if ("nxdn".equals(key)) return "NXDN";
        if ("nrl".equals(key)) return "NRL";
        return "Net";
    }

    private void setMuteView(TextView v, String key) {
        if (v == null) return;
        boolean muted = modeMuted(key);
        v.setText(muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
        v.setTextColor(muted ? RED : GREEN);
    }

    private void refreshMuteButtons() {
        setMuteView(muteDmrBtn, "digital");
        setMuteView(muteBmBtn, "bm");
        setMuteView(muteAnalogBtn, "analog");
        setMuteView(muteNetworkBtn, "network");
        setMuteView(muteDstarBtn, "dstar");
        setMuteView(muteYsfBtn, "ysf");
        setMuteView(muteP25Btn, "p25");
        setMuteView(muteNxdnBtn, "nxdn");
        setMuteView(muteNrlBtn, "nrl");
    }

    private void toggleModeMute(String key) {
        boolean nowMuted = !modeMuted(key);
        p.set("mute_" + key, nowMuted ? "1" : "0");
        if ("nrl".equals(key) && nrlAudio != null) nrlAudio.mutePlayback(nowMuted);
        refreshMuteButtons();
        toast(modeName(key) + (nowMuted ? "已静音" : "已开启声音"));
    }

    private void markModeSignal(int idx) {
        if (idx < 0 || idx > 8) return;
        boolean changed;
        synchronized (navSignalLock) {
            changed = !navSignal[idx];
            navSignal[idx] = true;
            navSignalUntil[idx] = System.currentTimeMillis() + 1200;
            if (navSignalOff[idx] == null) {
                navSignalOff[idx] = new Runnable() {
                    @Override public void run() {
                        long left;
                        synchronized (navSignalLock) {
                            left = navSignalUntil[idx] - System.currentTimeMillis();
                            if (left > 0) {
                                ui.postDelayed(this, left);
                                return;
                            }
                            navSignal[idx] = false;
                            navSignalOff[idx] = null;
                        }
                        paintNav();
                    }
                };
                ui.postDelayed(navSignalOff[idx], 1200);
            }
        }
        if (changed) ui.post(this::paintNav);
        ui.post(this::radioOtherSignal);
    }

    private void clearModeSignal(int idx) {
        if (idx < 0 || idx > 8) return;
        synchronized (navSignalLock) {
            navSignal[idx] = false;
            navSignalUntil[idx] = 0;
            if (navSignalOff[idx] != null) {
                ui.removeCallbacks(navSignalOff[idx]);
                navSignalOff[idx] = null;
            }
        }
        ui.post(this::paintNav);
    }

 /** 当前正在发射的模式在导航栏中的下标（数字=0 模拟=1 BM=2 网络=3）；-1=未发射。 */
    private int txModeIdx() {
        if ((hqTxRequested || txing) && "ysf".equals(activePttTarget)) return 5;
        if ((hqTxRequested || txing) && "p25".equals(activePttTarget)) return 6;
        if ((hqTxRequested || txing) && "nxdn".equals(activePttTarget)) return 7;
        if ((hqTxRequested || txing) && "nrl".equals(activePttTarget)) return 8;
        if ((hqTxRequested || txing) && "dstar".equals(activePttTarget)) return 4;
        if (hqTxRequested || (txing && "network".equals(activePttTarget))) return 3;
        if (!txing) return -1;
        if ("analog".equals(activePttTarget)) return 1;
        if ("bm".equals(activePttTarget)) return 2;
        if ("digital".equals(activePttTarget)) return 0;
        return -1;
    }

 /** 更新“上次呼叫”显示（青色）。m: 0=数字中继 2=DMR 3=Net */
    private void setLastCall(final int m, String call) {
        if (call == null) return;
        final String c = call.trim();
        if (c.isEmpty()) return;
        ui.post(() -> {
            try {
                if (m == 0 && dmrLastLine != null) { dmrLastLine.setText("上次呼叫：" + c); dmrLastLine.setTextColor(CYAN); }
                else if (m == 2 && bmLastLine != null) { bmLastLine.setText("上次呼叫：" + c); bmLastLine.setTextColor(CYAN); }
                else if (m == 3 && netLastLine != null) { netLastLine.setText("上次呼叫：" + c); netLastLine.setTextColor(CYAN); }
                else if (m == 4 && dstarLastLine != null) { dstarLastLine.setText("上次呼叫：" + c); dstarLastLine.setTextColor(CYAN); }
                else if (m == 1 && fmoLastLine != null) { fmoLastLine.setText("上次呼叫：" + c); fmoLastLine.setTextColor(CYAN); }
                else if (m == 5 && ysfLastLine != null) { ysfLastLine.setText("上次呼叫：" + c); ysfLastLine.setTextColor(CYAN); }
                else if (m == 6 && p25LastLine != null) { p25LastLine.setText("上次呼叫：" + c); p25LastLine.setTextColor(CYAN); }
                else if (m == 7 && nxdnLastLine != null) { nxdnLastLine.setText("上次呼叫：" + c); nxdnLastLine.setTextColor(CYAN); }
                else if (m == 8 && nrlLastLine != null) { nrlLastLine.setText("上次呼叫：" + c); nrlLastLine.setTextColor(CYAN); }
            } catch (Throwable t) { log("上次呼叫更新失败: " + t); }
        });
    }

 /** 多路监听开关。 */
    private boolean listenMode(int m) {
        try { return p.multiListen() || lastCommMode == m; } catch (Throwable t) { return true; }
    }

    private void pulseNavButton(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.965f);
        v.setScaleY(0.965f);
        v.setTranslationZ(dp(9));
        v.animate().scaleX(1f).scaleY(1f).translationZ(dp(6)).setDuration(190L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();
    }

    private void paintNav() {
        if (navBtns == null) return;
        boolean animateSelection = lastAnimatedNavPage >= 0 && lastAnimatedNavPage != page;
        for (int k = 0; k < navBtns.length; k++) {
            int pageIdx = activeNavPages[k];
            int signalIdx = activeNavModes[k];
            boolean on = (pageIdx == page);
            boolean signal = signalIdx >= 0 && signalIdx < navSignal.length && navSignal[signalIdx];
            boolean txNow = (signalIdx == txModeIdx());
            if (txNow) {
                navBtns[k].setTextColor(Color.WHITE);
                navBtns[k].setTypeface(null, Typeface.BOLD);
                navBtns[k].setBackground(glassColorBg(RED, 12));
                navBtns[k].setElevation(dp(7));
                navBtns[k].setTranslationZ(dp(6));
            } else if (signal) {
                navBtns[k].setTextColor(Color.WHITE);
                navBtns[k].setTypeface(null, Typeface.BOLD);
                navBtns[k].setBackground(glassColorBg(GREEN, 12));
                navBtns[k].setElevation(dp(7));
                navBtns[k].setTranslationZ(dp(6));
            } else {
                boolean focused = navBtns[k].hasFocus();
                navBtns[k].setTextColor((on || focused) ? Color.WHITE : SUB);
                navBtns[k].setTypeface(null, (on || focused) ? Typeface.BOLD : Typeface.NORMAL);
                navBtns[k].setBackground(focused ? glassNavFocusBg(on)
                        : (on ? glassNavBg(true) : glassNavBg(false)));
                navBtns[k].setAlpha((on || focused) ? 1f : (DARK ? 0.82f : 0.88f));
                navBtns[k].setElevation(dp(focused ? 9 : (on ? 7 : 4)));
                navBtns[k].setTranslationZ(dp(focused ? 8 : (on ? 5 : 2)));
            }
            if (animateSelection && on) pulseNavButton(navBtns[k]);
        }
        lastAnimatedNavPage = page;
    }

    private void buildUi() {
        // [3.2.42] 小屏（4.3/3.2 寸）统一按 380dp 宽度切换紧凑布局
        final boolean compactHeader = getResources().getConfiguration().screenWidthDp < 380;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new TechGridDrawable());

        // 顶部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(CARD);
        hbg.setCornerRadii(new float[]{0,0,0,0,dp(18),dp(18),dp(18),dp(18)});
        head.setBackground(hbg);
        head.setElevation(dp(1));
        final int hpL = dp(compactHeader ? 8 : 16), hpT = dp(9), hpR = dp(8), hpB = dp(9);
        head.setPadding(hpL, hpT, hpR, hpB);
        head.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = 0;
            try { top = ins.getSystemWindowInsetTop(); } catch (Throwable ignored) {}
            v.setPadding(hpL, hpT + top, hpR, hpB);
            return ins;
        });
        head.requestApplyInsets();
        // 左上角：LOGO 图标 + 名称（不再用 ARCH 四个字母）
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int logoSize = dp(tinyScreen ? 20 : (compactHeader ? 26 : 32));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        llp.setMargins(0, 0, dp(compactHeader ? 6 : 8), 0);
        logo.setLayoutParams(llp);
        head.addView(logo);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        // [3.2.42] 标题区必须可压缩：小屏/大字体时不能用固定宽度把右侧图标挤出去
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(0, 0, dp(4), 0);
        titleBox.setLayoutParams(tlp);
        titleBox.setMinimumWidth(0);
        // [3.2.44] 顶部只显示主名称，去掉“（协会版）”，小屏也能完整显示
        String headTitle = BuildConfig.PUBLIC_EDITION
                ? "业余无线电融合中枢"
                : BuildConfig.APP_DISPLAY_TITLE;
        int titleSize = tinyScreen ? 10 : (compactHeader ? 13 : (BuildConfig.PUBLIC_EDITION ? 17 : 15));
        TextView title = tv(headTitle, titleSize, TXT);
        title.setTypeface(null, Typeface.BOLD);
        // 协会内测版标题固定单行；空间不足时自动缩小，不再折到第二行。
        title.setSingleLine(true);
        title.setMaxLines(1);
        title.setHorizontallyScrolling(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            title.setAutoSizeTextTypeUniformWithConfiguration(tinyScreen ? 7 : 9,
                    tinyScreen ? 10 : (compactHeader ? 13 : 15), 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        titleBox.addView(title);
        if (!tinyScreen) {
            String _ver = BuildConfig.VERSION_NAME.replace("-ASSOCIATION", "").replace("-PUBLIC", "");
            TextView subtitle = tv("Amateur Radio Convergence Hub · V" + _ver, compactHeader ? 9 : 10, SUB);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleBox.addView(subtitle);
        }
        head.addView(titleBox);
        // [3.2.45] 删掉中间占位 spacer：它和 titleBox 各占一半权重，正是标题被截断的原因
        // [3.2.41] 网络收音机入口：放在设置齿轮左侧
        if (p.showNetRadio()) {
            FrameLayout radioEntry = new FrameLayout(this);
            int rSize = dp(tinyScreen ? 28 : (compactHeader ? 36 : 42));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(rSize, rSize);
            rLp.setMargins(dp(4), 0, 0, 0);
            radioEntry.setLayoutParams(rLp);
            radioEntry.setContentDescription("网络收音机");
            radioEntry.setBackground(roundBg(withAlpha(ACCENT, DARK ? 42 : 24), 999));
            ImageView radioIcon = new ImageView(this);
            radioIcon.setImageResource(R.drawable.ic_radio);
            radioIcon.setColorFilter(ACCENT);
            radioIcon.setPadding(dp(9), dp(9), dp(9), dp(9));
            radioEntry.addView(radioIcon, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            radioEntry.setOnClickListener(v -> setPage(PAGE_NET_RADIO));
            head.addView(radioEntry);
        }
        FrameLayout gear = new FrameLayout(this);
        int gearSize = dp(tinyScreen ? 28 : (compactHeader ? 36 : 42));
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(gearSize, gearSize);
        gearLp.setMargins(dp(4), 0, 0, 0);
        gear.setLayoutParams(gearLp);
        gear.setContentDescription("设置");
        gear.setBackground(roundBg(withAlpha(ACCENT, DARK ? 42 : 24), 999));
        ImageView gearIcon = new ImageView(this);
        gearIcon.setImageResource(R.drawable.ic_settings_gear);
        gearIcon.setColorFilter(ACCENT);
        gearIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.addView(gearIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gear.setOnClickListener(v -> setPage(4));
        head.addView(gear);
        root.addView(head);

        // 顶部模式导航：放在 logo 下方；模式超过 6 个时自动分成两排
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackground(null);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        nav.setElevation(dp(0));
        nav.setClipChildren(false);
        nav.setClipToPadding(false);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.setMargins(dp(compactHeader ? 6 : 12), dp(4), dp(compactHeader ? 6 : 12), dp(6));
        nav.setLayoutParams(navLp);
        String netLabel = BuildConfig.PUBLIC_EDITION ? "Net" : "网络对讲";
        String[] names = { "模拟互联", "数字中继", netLabel, "DMR", "D-Star", "YSF", "P25", "NXDN", "NRL", "SIP电话" };
        java.util.ArrayList<Integer> visiblePages = new java.util.ArrayList<>();
        if (BuildConfig.PUBLIC_EDITION) {
            if (p.showBm()) visiblePages.add(PAGE_BM);
            if (p.showP25()) visiblePages.add(PAGE_P25);
            if (p.showYsf()) visiblePages.add(PAGE_YSF);
            if (p.showDstar()) visiblePages.add(PAGE_DSTAR);
            if (p.showNxdn()) visiblePages.add(PAGE_NXDN);
            if (p.showNetwork()) visiblePages.add(PAGE_NETWORK);
            if (p.showNrl()) visiblePages.add(PAGE_NRL);
        } else {
            if (p.showNetwork()) visiblePages.add(PAGE_NETWORK);
            if (p.showAnalog()) visiblePages.add(PAGE_ANALOG);
            if (p.showDigital()) visiblePages.add(PAGE_DIGITAL);
            if (p.showBm()) visiblePages.add(PAGE_BM);
            if (p.showDstar()) visiblePages.add(PAGE_DSTAR);
            if (p.showYsf()) visiblePages.add(PAGE_YSF);
            if (p.showP25()) visiblePages.add(PAGE_P25);
            if (p.showNxdn()) visiblePages.add(PAGE_NXDN);
            if (p.showNrl()) visiblePages.add(PAGE_NRL);
            if (p.sipConfigured()) visiblePages.add(PAGE_SIP);
            java.util.Collections.sort(visiblePages, (a, b2) -> Integer.compare(navOrderIndex(a), navOrderIndex(b2)));
        }
        // [3.2.41] 收音机不再占用模式导航，改走右上角图标入口
        radioBottomOnly = false;
        final int navCount = visiblePages.size();
        navBtns = new Button[navCount];
        activeNavPages = new int[navCount];
        activeNavModes = new int[navCount];
        final int navFont = navCount >= 5 ? 11 : 13;
        if (navCount > 6) {
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(Gravity.CENTER);
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setGravity(Gravity.CENTER);
            for (int i = 0; i < 4; i++) {
                int pageIdx = visiblePages.get(i);
                addNavButton(row1, i, pageIdx, names[navOrderIndex(pageIdx)], navFont);
            }
            int secondCount = navCount - 4;
            float sideWeight = (4 - secondCount) / 2.0f;
            if (sideWeight > 0f) addNavSpacer(row2, sideWeight);
            for (int i = 4; i < navCount; i++) {
                int pageIdx = visiblePages.get(i);
                addNavButton(row2, i, pageIdx, names[navOrderIndex(pageIdx)], navFont);
            }
            if (sideWeight > 0f) addNavSpacer(row2, sideWeight);
            nav.addView(row1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            nav.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int i = 0; i < navCount; i++) {
                int pageIdx = visiblePages.get(i);
                addNavButton(row, i, pageIdx, names[navOrderIndex(pageIdx)], navFont);
            }
            nav.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(nav);
        audioLevelMeter = new AudioLevelMeterView(this);
        AudioLevelMeterView audioMeter = audioLevelMeter;
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tinyScreen ? 30 : 34));
        meterLp.setMargins(dp(compactHeader ? 6 : 12), dp(2), dp(compactHeader ? 6 : 12), 0);
        root.addView(audioMeter, meterLp);
        // 内容
        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        content.setLayoutParams(flp);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        pageRadio = buildRadio(); pageBm = buildBmPage(); pageFmo = buildFmoPage(); pageNetwork = buildNetworkPage(); pageDstar = buildDstarPage(); pageYsf = buildYsfPage(); pageP25 = buildP25Page(); pageNxdn = buildNxdnPage(); pageNrl = buildNrlPage(); pageNetRadio = BuildConfig.PUBLIC_EDITION ? new View(this) : buildNetRadioPage(); pageSet = buildSet();
        content.addView(pageRadio); content.addView(pageBm); content.addView(pageFmo); content.addView(pageNetwork); content.addView(pageDstar); content.addView(pageYsf); content.addView(pageP25); content.addView(pageNxdn); content.addView(pageNrl); content.addView(pageNetRadio); content.addView(pageSet);
        root.addView(content);
        setContentView(root);
        setPage(pendingPage);
        if (!rebuildingUiInPlace) {
            ui.postDelayed(this::autoConnectYsfIfNeeded, 1200L);
            ui.postDelayed(this::autoConnectP25IfNeeded, 1450L);
            ui.postDelayed(this::autoConnectNxdnIfNeeded, 1700L);
            ui.postDelayed(this::autoConnectNrlIfNeeded, 1900L);
            ui.postDelayed(this::autoConnectDmrIfNeeded, 1600L);
            ui.postDelayed(this::startupAutoLoginSweep, 2600L);
        }
        if (pendingPage == PAGE_SETTINGS && pendingSettingsScrollY >= 0) {
            final int y = pendingSettingsScrollY; pendingSettingsScrollY = -1;
            ui.postDelayed(() -> { if (settingsScroll != null) settingsScroll.scrollTo(0, y); }, 120L);
        }
        refreshMuteButtons();
        refreshAccountStatus();
    }

    private void addNavButton(LinearLayout row, int navIndex, int pageIdx, String label, int font) {
        boolean navCompact = getResources().getConfiguration().screenWidthDp < 380;
        Button bt = new Button(this);
        bt.setText(label);
        ts(bt, font);
        bt.setLetterSpacing(0.03f);
        bt.setIncludeFontPadding(false);
        bt.setMinHeight(0);
        bt.setMinimumHeight(0);
        bt.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        bt.setAllCaps(false);
        bt.setTextColor(SUB);
        bt.setMaxLines(1); bt.setSingleLine(true);
        bt.setMinWidth(0); bt.setMinimumWidth(0);
        bt.setPadding(dp(navCompact ? 1 : 2), dp(navCompact ? 2 : 3), dp(navCompact ? 1 : 2), dp(navCompact ? 2 : 3));
        bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // [3.2.42] 大字体/小屏时自动缩小导航文字，避免两行字挤在一起或把按钮撑破
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            bt.setAutoSizeTextTypeUniformWithConfiguration(tinyScreen ? 7 : 8, tinyScreen ? 10 : 15, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        bt.setBackground(glassNavBg(false));
        bt.setElevation(dp(4));
        bt.setTranslationZ(dp(2));
        bt.setOnFocusChangeListener((v, hasFocus) -> paintNav());
        bt.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
            }
            return false;
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                dp(tinyScreen ? 26 : (navCompact ? 30 : 34)), 1f);
        nlp.setMargins(dp(2), dp(2), dp(2), dp(2));
        bt.setLayoutParams(nlp);
        bt.setOnClickListener(v -> { if (pageIdx == PAGE_SIP) openSipPhone(); else setPage(pageIdx); });
        navBtns[navIndex] = bt;
        activeNavPages[navIndex] = pageIdx;
        activeNavModes[navIndex] = navModeForPage(pageIdx);
        row.addView(bt);
    }

    private void addNavSpacer(LinearLayout row, float weight) {
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, weight));
    }

    private LinearLayout wrap() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = tinyScreen ? 6 : 12;
        l.setPadding(dp(pad), dp(tinyScreen ? 6 : 10), dp(pad), dp(tinyScreen ? 6 : 10));
        return l;
    }

    private LinearLayout buildRadio() {
        LinearLayout l = wrap();
        statusDot = tv("●", 14, SUB);
        statusText = tv("", 13, SUB);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        nowState = tv("空闲", 12, SUB);
        nowState.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nowTimer = tv("", 16, TXT); nowTimer.setTypeface(null, Typeface.BOLD);
        nowTimer.setGravity(Gravity.END);
        statusRow.addView(nowState); statusRow.addView(nowTimer);
        nowCall = tv("未连接", 24, TXT); nowCall.setTypeface(null, Typeface.BOLD);
        nowTG = tv("当前组: " + tgDescription(), 15, TXT); tgLabel = nowTG;
        modeLabel = tv("当前网络: DMR", 12, BLUE);
        modeLabel.setSingleLine(true);
        muteDmrBtn = muteButton("digital");
        dmrLastLine = tv("上次呼叫：--", 15, SUB);
        LinearLayout c1 = statusCard(muteDmrBtn, statusRow, nowCall, dmrLastLine, modeLabel);
        l.addView(c1);

        LinearLayout cg = card();
        cg.addView(sectionTitle("通话组"));
        tgRow = new LinearLayout(this);
        tgRow.setOrientation(LinearLayout.VERTICAL);
        cg.addView(tgRow);
        l.addView(cg);
        rebuildTalkgroupButtons();

        LinearLayout c2 = card();
        c2.addView(tv("通话记录", 12, SUB));
        LinearLayout hist = new LinearLayout(this);
        hist.setOrientation(LinearLayout.VERTICAL);
        hist.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        hist.addView(logBox);
        c2.addView(hist);
        l.addView(c2);

        // 通话组（从设置迁过来：通话组是数字中继专用的）


        return pageWithPttScroll(l);
    }

    // ==================== [3.2.44] 网络收音机 ====================
    private TextView radioSectionHeader(String text, boolean open, Runnable click) {
        TextView h = tv((open ? "▾  " : "▸  ") + text, 13, BLUE);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(dp(4), dp(9), dp(4), dp(5));
        h.setOnClickListener(v -> click.run());
        return h;
    }

    /** [3.2.46] 顶部状态胶囊：播放/暂停切换。 */
    private void toggleRadioPlayback() {
        if (radioCurrent == null) { toast("请先选择电台"); return; }
        if (radioPlayer != null && radioPlayer.isPlaying()) {
            radioUserPaused = true;
            radioPlayer.pause();
            if (radioStatus != null) radioStatus.setText("▶ 已暂停");
        } else {
            radioUserPaused = false;
            radioPausedForPage = false;
            if (radioPlayer != null) radioPlayer.resume();
            if (radioStatus != null) radioStatus.setText("⏸ 播放中");
        }
    }

    /** [3.2.48] 在线收听清单（SDR / 航空 / 短波），点击用浏览器打开。 */
    private void buildRadioLinks() {
        if (radioLinkBox == null) return;
        radioLinkBox.removeAllViews();
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
        for (RadioLinks.Link l : RadioLinks.ALL) groups.add(l.group);
        boolean showGroup = groups.size() > 1;
        String group = "";
        for (RadioLinks.Link l : RadioLinks.ALL) {
            if (showGroup && !l.group.equals(group)) {
                group = l.group;
                TextView g = tv(group, 11, SUB);
                g.setPadding(dp(4), dp(8), dp(4), dp(3));
                radioLinkBox.addView(g);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundBg(DARK ? 0xFF232C38 : 0xFFF8FAFD, 12));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(5));
            row.setLayoutParams(rlp);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView n = tv(l.name, 13, TXT);
            n.setTypeface(null, Typeface.BOLD);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = tv(l.sub, 10, SUB);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(n);
            info.addView(sub);
            info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = tv("↗", 16, BLUE);
            go.setPadding(dp(8), 0, dp(4), 0);
            row.addView(info);
            row.addView(go);
            row.setOnClickListener(v -> openRadioLink(l.url));
            radioLinkBox.addView(row);
        }
    }

    private void openRadioLink(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Throwable t) {
            toast("没有可用的浏览器");
        }
    }

    private View buildNetRadioPage() {
        radioStations = RadioCatalog.load(this);
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = wrap();
        body.setPadding(dp(9), dp(6), dp(9), dp(92));

        // 顶部小频谱框
        LinearLayout spec = card();
        radioWave = new RadioWaveView(this);
        spec.addView(radioWave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tinyScreen ? 60 : 78)));
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        radioNowTitle = tv("未选择电台", 13, TXT);
        radioNowTitle.setTypeface(null, Typeface.BOLD);
        radioNowTitle.setSingleLine(true);
        radioNowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        radioNowTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // [3.2.46] 状态胶囊本身就是播放/暂停按钮，去掉右下角浮动圆钮
        radioStatus = tv("▶ 播放", 11, GREEN);
        radioStatus.setTypeface(null, Typeface.BOLD);
        radioStatus.setGravity(Gravity.CENTER);
        radioStatus.setPadding(dp(9), dp(3), dp(9), dp(3));
        radioStatus.setBackground(roundBg(withAlpha(GREEN, DARK ? 56 : 30), 999));
        radioStatus.setOnClickListener(v -> toggleRadioPlayback());
        radioStatus.setOnLongClickListener(v -> {
            stopRadioPlayback();
            toast("已停止播放");
            return true;
        });
        infoRow.addView(radioNowTitle);
        infoRow.addView(radioStatus);
        spec.addView(infoRow);
        body.addView(spec);

        // 省份（可折叠）
        radioProvinceHeader = radioSectionHeader("选择省份", radioProvinceOpen, () -> {
            radioProvinceOpen = !radioProvinceOpen;
            radioProvinceHeader.setText((radioProvinceOpen ? "▾  " : "▸  ") + "选择省份");
            radioProvinceGrid.setVisibility(radioProvinceOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioProvinceHeader);
        radioProvinceGrid = new LinearLayout(this);
        radioProvinceGrid.setOrientation(LinearLayout.VERTICAL);
        radioProvinceGrid.setVisibility(radioProvinceOpen ? View.VISIBLE : View.GONE);
        body.addView(radioProvinceGrid);

        // 城市（可折叠，网络台时整段隐藏）
        radioCityHeader = radioSectionHeader("选择城市", radioCityOpen, () -> {
            radioCityOpen = !radioCityOpen;
            radioCityHeader.setText((radioCityOpen ? "▾  " : "▸  ") + "选择城市");
            radioCityGrid.setVisibility(radioCityOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioCityHeader);
        radioCityGrid = new LinearLayout(this);
        radioCityGrid.setOrientation(LinearLayout.VERTICAL);
        body.addView(radioCityGrid);

        // 电台列表（可折叠）
        radioListHeader = radioSectionHeader("电台", radioListOpen, () -> {
            radioListOpen = !radioListOpen;
            radioListHeader.setText((radioListOpen ? "▾  " : "▸  ") + "电台");
            radioListBox.setVisibility(radioListOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioListHeader);
        radioListBox = new LinearLayout(this);
        radioListBox.setOrientation(LinearLayout.VERTICAL);
        radioListBox.setVisibility(radioListOpen ? View.VISIBLE : View.GONE);
        body.addView(radioListBox);

        // 收藏（可折叠）
        radioFavoriteHeader = radioSectionHeader("收藏", radioFavoriteOpen, () -> {
            radioFavoriteOpen = !radioFavoriteOpen;
            radioFavoriteHeader.setText((radioFavoriteOpen ? "▾  " : "▸  ") + "收藏");
            refreshRadioFavorites();
        });
        radioFavoriteHeader.setVisibility(View.GONE);
        body.addView(radioFavoriteHeader);
        radioFavoriteBox = new LinearLayout(this);
        radioFavoriteBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(radioFavoriteBox);

        // 最近播放（可折叠）
        radioRecentHeader = radioSectionHeader("最近播放", radioRecentOpen, () -> {
            radioRecentOpen = !radioRecentOpen;
            radioRecentHeader.setText((radioRecentOpen ? "▾  " : "▸  ") + "最近播放");
            refreshRadioRecent();
        });
        radioRecentHeader.setVisibility(View.GONE);
        body.addView(radioRecentHeader);
        radioRecentBox = new LinearLayout(this);
        radioRecentBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(radioRecentBox);

        // 短波在线收听——默认折叠，放在最近播放下面
        radioLinkHeader = radioSectionHeader("短波在线收听", radioLinkOpen, () -> {
            radioLinkOpen = !radioLinkOpen;
            radioLinkHeader.setText((radioLinkOpen ? "▾  " : "▸  ") + "短波在线收听");
            radioLinkBox.setVisibility(radioLinkOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioLinkHeader);
        radioLinkBox = new LinearLayout(this);
        radioLinkBox.setOrientation(LinearLayout.VERTICAL);
        radioLinkBox.setVisibility(radioLinkOpen ? View.VISIBLE : View.GONE);
        buildRadioLinks();
        body.addView(radioLinkBox);


        sv.addView(body);
        page.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));



        refreshRadioProvinceGrid();
        refreshRadioCityGrid();
        renderRadioStations();
        refreshRadioFavorites();
        refreshRadioRecent();
        if (p.showNetRadio()) refreshRadioCatalogAsync();
        return page;
    }

    private Button radioRoundButton(String text) {
        Button b = btn(text);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(roundBg(BLUE, 999));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    private TextView radioChip(String text, boolean selected, Runnable click) {
        TextView v = tv(text, 13, selected ? 0xFFFFFFFF : (DARK ? 0xFFE5E7EB : 0xFF334155));
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(dp(2), dp(8), dp(2), dp(8));
        v.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        v.setBackground(roundBg(selected ? BLUE : (DARK ? 0xFF232C38 : 0xFFF1F5F9), 12));
        v.setOnClickListener(x -> click.run());
        return v;
    }

    private void refreshRadioProvinceGrid() {
        if (radioProvinceGrid == null) return;
        radioProvinceGrid.removeAllViews();
        java.util.List<String> provinces = RadioCatalog.provinces(radioStations);
        LinearLayout row = null;
        int col = 0;
        for (String province : provinces) {
            if (row == null || col == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                radioProvinceGrid.addView(row);
                col = 0;
            }
            final String pv = province;
            TextView chip = radioChip(province, province.equals(radioProvince), () -> {
                radioProvince = pv;
                radioCity = "全部城市";
                refreshRadioProvinceGrid();
                refreshRadioCityGrid();
                renderRadioStations();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            row.addView(chip, lp);
            col++;
            if (col == 6) row = null;
        }
        if (row != null) while (row.getChildCount() < 6) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private void refreshRadioCityGrid() {
        if (radioCityGrid == null) return;
        // [3.2.44] 网络台没有城市；"全部"也不显示城市，避免出现“网络台 + 全部城市”
        boolean showCity = !radioProvince.isEmpty()
                && !"全部".equals(radioProvince)
                && !"网络台".equals(radioProvince)
                && !"短波广播".equals(radioProvince);
        if (radioCityHeader != null) {
            radioCityHeader.setVisibility(showCity ? View.VISIBLE : View.GONE);
        }
        if (!showCity) {
            radioCityGrid.removeAllViews();
            radioCityGrid.setVisibility(View.GONE);
            return;
        }
        radioCityGrid.setVisibility(radioCityOpen ? View.VISIBLE : View.GONE);
        radioCityGrid.removeAllViews();
        java.util.List<String> cities = RadioCatalog.cities(radioStations, radioProvince);
        LinearLayout row = null;
        int col = 0;
        for (String city : cities) {
            if (row == null || col == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                radioCityGrid.addView(row);
                col = 0;
            }
            final String cv = city;
            TextView chip = radioChip(city, city.equals(radioCity), () -> {
                radioCity = cv;
                refreshRadioCityGrid();
                renderRadioStations();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(chip, lp);
            col++;
            if (col == 5) row = null;
        }
        if (row != null) while (row.getChildCount() < 5) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private java.util.List<RadioStation> filteredRadioStations() {
        java.util.ArrayList<RadioStation> out = new java.util.ArrayList<>();
        String q = radioSearch == null ? "" : radioSearch.getText().toString().trim().toLowerCase(java.util.Locale.US);
        if (q.isEmpty() && "全部".equals(radioProvince)) return out;
        for (RadioStation s : radioStations) {
            if (!q.isEmpty()) {
                String all = (s.name + " " + s.province + " " + s.city + " " + s.category + " " + s.sourceType).toLowerCase(java.util.Locale.US);
                if (!all.contains(q)) continue;
            }
            if (!"全部".equals(radioProvince) && !radioProvince.equals(s.province)) continue;
            if (!"全部城市".equals(radioCity) && !radioCity.equals(s.city)) continue;
            out.add(s);
            if (out.size() >= 200) break;
        }
        return out;
    }

    private void renderRadioStations() {
        if (radioListBox == null) return;
        radioListBox.removeAllViews();
        java.util.List<RadioStation> list = filteredRadioStations();
        if (list.isEmpty()) {
            radioListBox.addView(tv("请选择省份/城市，或输入电台名搜索", 12, SUB));
            return;
        }
        radioListBox.addView(tv("共 " + list.size() + " 个电台", 11, SUB));
        for (RadioStation s : list) radioListBox.addView(radioStationRow(s));
    }

    private View radioStationRow(final RadioStation s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(roundBg(DARK ? 0xFF232C38 : 0xFFF8FAFD, 14));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(7));
        row.setLayoutParams(rlp);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView n = tv(s.name, 14, TXT);
        n.setTypeface(null, Typeface.BOLD);
        n.setSingleLine(true);
        info.addView(n);
        info.addView(tv(s.subtitle(), 10, SUB));
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(info);
        TextView star = tv(p.radioIsFavorite(s.id) ? "★" : "☆", 22, p.radioIsFavorite(s.id) ? GREEN : SUB);
        star.setGravity(Gravity.CENTER);
        star.setPadding(dp(8), 0, dp(8), 0);
        star.setOnClickListener(v -> { p.radioToggleFavorite(s.id); renderRadioStations(); refreshRadioFavorites(); refreshRadioRecent(); });
        row.addView(star);
        TextView play = tv("▶", 20, BLUE);
        play.setGravity(Gravity.CENTER);
        play.setPadding(dp(8), 0, dp(4), 0);
        play.setOnClickListener(v -> playRadioStation(s));
        row.addView(play);
        row.setOnClickListener(v -> playRadioStation(s));
        return row;
    }

    /** [3.2.47] 收藏列表：点星取消收藏，列表实时更新。 */
    private void refreshRadioFavorites() {
        if (radioFavoriteBox == null) return;
        radioFavoriteBox.removeAllViews();
        java.util.LinkedHashMap<String, RadioStation> map = new java.util.LinkedHashMap<>();
        for (RadioStation st : radioStations) map.put(st.id, st);
        int n = 0;
        for (String id : p.radioFavorites().split("\n")) {
            RadioStation fs = map.get(id == null ? "" : id.trim());
            if (fs == null) continue;
            radioFavoriteBox.addView(radioStationRow(fs));
            if (++n >= 20) break;
        }
        boolean has = n > 0;
        if (radioFavoriteHeader != null) {
            radioFavoriteHeader.setVisibility(has ? View.VISIBLE : View.GONE);
            radioFavoriteHeader.setText((radioFavoriteOpen ? "▾  " : "▸  ") + "收藏");
        }
        radioFavoriteBox.setVisibility(has && radioFavoriteOpen ? View.VISIBLE : View.GONE);
    }

    private void refreshRadioRecent() {
        if (radioRecentBox == null) return;
        radioRecentBox.removeAllViews();
        java.util.LinkedHashMap<String, RadioStation> map = new java.util.LinkedHashMap<>();
        for (RadioStation s : radioStations) map.put(s.id, s);
        int n = 0;
        for (String id : p.radioRecent().split("\n")) {
            RadioStation rs = map.get(id == null ? "" : id.trim());
            if (rs == null) continue;
            View row = radioStationRow(rs);
            final String sid = rs.id;
            final String sname = rs.name;
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("从最近播放中移除《" + sname + "》？")
                        .setPositiveButton("移除", (d, w) -> { p.radioRemoveRecent(sid); refreshRadioRecent(); })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            row.setOnTouchListener(new View.OnTouchListener() {
                float x0, y0;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            x0 = e.getX(); y0 = e.getY();
                            return false;
                        case android.view.MotionEvent.ACTION_UP: {
                            float dx = e.getX() - x0, dy = e.getY() - y0;
                            if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                p.radioRemoveRecent(sid);
                                refreshRadioRecent();
                                toast("已从最近播放移除");
                                return true;
                            }
                            return false;
                        }
                    }
                    return false;
                }
            });
            radioRecentBox.addView(row);
            if (++n >= 5) break;
        }
        boolean has = n > 0;
        if (radioRecentHeader != null) {
            radioRecentHeader.setVisibility(has ? View.VISIBLE : View.GONE);
            radioRecentHeader.setText((radioRecentOpen ? "▾  " : "▸  ") + "最近播放");
        }
        radioRecentBox.setVisibility(has && radioRecentOpen ? View.VISIBLE : View.GONE);
    }

    private void playRadioStation(RadioStation s) {
        if (s == null) return;
        radioCurrent = s;
        radioUserPaused = false;
        radioPausedForPage = false;
        radioPausedForOther = false;
        radioPausedForPtt = false;
        p.radioMarkRecent(s.id);
        if (radioNowTitle != null) radioNowTitle.setText(s.name);
        if (radioNowSub != null) radioNowSub.setText(s.subtitle());
        if (radioStatus != null) { radioStatus.setText("正在连接…"); radioStatus.setTextColor(BLUE); }
        if (radioPlayer != null) radioPlayer.play(s);
        renderRadioStations();
        refreshRadioRecent();
    }

    private void updateNetRadioPlayerState(int state, String msg) {
        if (radioStatus != null) {
            String text = msg == null ? "" : msg;
            boolean playing = radioPlayer != null && radioPlayer.isPlaying();
            String icon = (state == 1 && playing) ? "⏸ " : "▶ ";
            radioStatus.setText(icon + text);
            radioStatus.setTextColor(state == 3 ? RED : (state == 1 ? GREEN : BLUE));
        }
        if (radioPlayBtn != null) radioPlayBtn.setText(radioPlayer != null && radioPlayer.isPlaying() ? "⏸" : "▶");
        if (radioWave != null) radioWave.setActive(radioPlayer != null && radioPlayer.isPlaying());
    }

    private void stopRadioPlayback() {
        radioUserPaused = true;
        radioPausedForPage = false;
        radioPausedForOther = false;
        radioPausedForPtt = false;
        if (radioResumeTask != null) { ui.removeCallbacks(radioResumeTask); radioResumeTask = null; }
        if (radioPlayer != null) radioPlayer.stop();
        if (radioWave != null) radioWave.setActive(false);
        if (radioStatus != null) { radioStatus.setText("▶ 未播放"); radioStatus.setTextColor(SUB); }
        if (radioPlayBtn != null) radioPlayBtn.setText("▶");
    }

    private void refreshRadioCatalogAsync() {
        if (!p.showNetRadio()) return;
        radioCatalogLastRefresh = System.currentTimeMillis();
        RadioCatalog.refreshAsync(this, list -> ui.post(() -> {
            if (list == null || list.isEmpty()) {
                toast("电台列表更新失败，已使用本地数据");
                return;
            }
            radioStations = list;
            refreshRadioProvinceGrid();
            refreshRadioCityGrid();
            renderRadioStations();
            refreshRadioFavorites();
            refreshRadioRecent();
        }));
    }

    private void radioOtherSignal() {
        if (radioPlayer == null || !radioPlayer.isPlaying() || radioPausedForPtt) return;
        String mode = p.radioInterruptMode();
        if ("off".equals(mode)) return;
        if ("pause".equals(mode)) {
            if (!radioPausedForOther) {
                radioPausedForOther = true;
                radioPlayer.pause();
            }
        } else if ("duck".equals(mode)) {
            radioPausedForOther = true;
            radioPlayer.setVolume(0.2f);
        }
        if (radioResumeTask != null) ui.removeCallbacks(radioResumeTask);
        radioResumeTask = () -> {
            radioResumeTask = null;
            if (!radioPausedForOther || radioPausedForPtt) return;
            radioPausedForOther = false;
            if ("pause".equals(p.radioInterruptMode())) {
                if (!radioUserPaused && radioPlayer != null) radioPlayer.resume();
            } else if (radioPlayer != null) {
                radioPlayer.setVolume(radioUserVolume);
            }
        };
        ui.postDelayed(radioResumeTask, 1800L);
    }

    private void radioPauseForPtt() {
        if (radioPlayer == null || !radioPlayer.isPlaying()) return;
        radioPausedForPtt = true;
        radioPlayer.pause();
    }

    private void radioReleasePtt() {
        if (!radioPausedForPtt) return;
        ui.postDelayed(() -> {
            radioPausedForPtt = false;
            if (radioUserPaused || radioPlayer == null) return;
            if (radioPausedForOther) {
                if ("duck".equals(p.radioInterruptMode())) {
                    radioPlayer.resume();
                    radioPlayer.setVolume(0.2f);
                }
                return;
            }
            if ("pause".equals(p.radioInterruptMode())) radioPlayer.resume();
            else if ("duck".equals(p.radioInterruptMode())) radioPlayer.setVolume(radioUserVolume);
        }, 600L);
    }

    private TextView sectionTitle(String s) {
        TextView t = tv("▍ " + s, 12, ACCENT);
        t.setTypeface(null, Typeface.BOLD);
        t.setLetterSpacing(0.10f);
        t.setPadding(0, dp(2), 0, dp(8));
        return t;
    }

    private String gainText(int db) { return (db > 0 ? "+" : "") + db + " dB"; }

    private void setLoginStatus(TextView view, String text, int color) {
        if (view == null) return;
        view.setText(text);
        view.setTextColor(color);
        view.setTypeface(null, Typeface.BOLD);
        view.setBackground(roundStroke(withAlpha(color, DARK ? 34 : 20), withAlpha(color, 90), 10));
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
    }

    private void refreshAccountStatus() {
        boolean configured = !p.callsign().isEmpty() && p.password().length() >= 6;
        if (accountSaveBtn != null) accountSaveBtn.setText(configured ? "已保存" : "保存 / 校验协会身份");
    }

    private void setBmStatus(boolean connected, boolean connecting, String detail) {
        String label = connected ? "已登录" : (connecting ? "登录中…" : "已断开");
        int color = connected ? BLUE : SUB;
        if (bmState != null) {
            bmState.setText(connected ? "空闲" : label);
            bmState.setTextColor(connected ? SUB : color);
        }
        if (bmCallLine != null && bmCallLine.getCurrentTextColor() != RED
                && System.currentTimeMillis() >= bmCallHoldUntil) {
            bmCallLine.setText(connected ? "等待呼叫" : "未登录");
            bmCallLine.setTextColor(TXT);
        }
        if (bmServerLabel != null) {
            bmServerLabel.setText("当前网络: " + p.bmServerName() + " · " + label + " ▾");
            bmServerLabel.setTextColor(connected ? BLUE : color);
        }
        if (!connected) bmCallHoldUntil = 0;
        if (bmLoginBtn != null) setLoginStatus(bmLoginBtn, label, color);
    }

    private CheckBox chk(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        ts(c, 13.5f);
        c.setTextColor(TXT);
        c.setIncludeFontPadding(false);
        c.setMinHeight(dp(44));
        c.setPadding(0, dp(4), 0, dp(4));
        c.setChecked(on);
        return c;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        ts(b, 13.5f);
        b.setMinHeight(dp(44));
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setIncludeFontPadding(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setTextColor(ACCENT);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(stateBg(SURFACE2, withAlpha(ACCENT, DARK ? 40 : 26), 12));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }


    private LinearLayout actionToolbar(Button... buttons) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.setBackground(roundStroke(SURFACE2, HAIRLINE, 14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        bar.setLayoutParams(blp);
        for (Button b : buttons) {
            b.setAllCaps(false);
            ts(b, 11.5f);
            b.setMinHeight(dp(42));
            b.setPadding(dp(3), dp(4), dp(3), dp(4));
            b.setTextColor(ACCENT);
            b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", Typeface.NORMAL));
            android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
            d.addState(new int[]{android.R.attr.state_pressed},
                    roundBg(withAlpha(ACCENT, DARK ? 54 : 28), 10));
            d.addState(new int[]{android.R.attr.state_focused},
                    roundStroke(withAlpha(ACCENT, DARK ? 18 : 8), withAlpha(ACCENT, 120), 10));
            d.addState(new int[]{}, roundBg(0x00000000, 10));
            b.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(b, lp);
        }
        return bar;
    }

    private SeekBar seek(int progress0to40) {
        SeekBar s = new SeekBar(this);
        s.setMax(60);
        s.setProgress(progress0to40);
        return s;
    }

    private SeekBar seekRange(int min, int max, int value) {
        SeekBar s = new SeekBar(this);
        s.setMax(Math.max(1, max - min));
        s.setProgress(Math.max(0, Math.min(max - min, value - min)));
        return s;
    }

    private View buildSet() {
        LinearLayout l = wrap();

        // ── 协会服务器独立身份 ──
        LinearLayout c1 = card();
        TextView sN = tv("网络配置 · 协会服务器", 16, TXT); sN.setTypeface(null, Typeface.BOLD);
        c1.addView(sN);
        c1.addView(tv("协会服务器独立身份与密码", 11, SUB));
        callEd = new EditText(this);
        callEd.setTextColor(TXT);
        callEd.setHint("呼号");
        callEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        callEd.setText(p.callsign());
        callEd.addTextChangedListener(new TextWatcher() {
            private boolean changing;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (changing) return;
                String u = s.toString().toUpperCase(java.util.Locale.US);
                if (!u.equals(s.toString())) {
                    changing = true; callEd.setText(u); callEd.setSelection(u.length()); changing = false;
                }
            }
        });
        c1.addView(row("呼号", callEd));
        idEd = new EditText(this);
        idEd.setTextColor(TXT);
        idEd.setHint("");
        idEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        idEd.setText(p.dmrId() == 0 ? "" : String.valueOf(p.dmrId()));
        c1.addView(row("DMR ID", idEd));
        essidEd = new EditText(this);
        essidEd.setTextColor(TXT);
        essidEd.setHint("01");
        essidEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        essidEd.setText(String.format(java.util.Locale.US, "%02d", p.essid()));
        c1.addView(row("ESSID", essidEd));
        passEd = new EditText(this);
        passEd.setTextColor(TXT);
        passEd.setHint("至少6位");
        passEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        passEd.setText(p.password());
        c1.addView(row("协会密码", passEd));
        Button save = btn("保存 / 校验协会身份");
        accountSaveBtn = save;
        save.setOnClickListener(v -> {
            String cs = callEd.getText().toString().trim().toUpperCase(java.util.Locale.US);
            String idText = idEd.getText().toString().trim();
            String pw = passEd.getText().toString();
            int essid = 1;
            try { essid = Integer.parseInt(essidEd.getText().toString().trim()); } catch (Exception ignored) {}
            essid = Math.max(0, Math.min(99, essid));
            int did;
            try { did = Integer.parseInt(idText); } catch (Exception e) { did = 0; }
            if (cs.isEmpty() || did <= 0 || pw.length() < 6) {
                toast("呼号、DMR ID、ESSID 必填，密码至少 6 位");
                return;
            }
            final int fEssid = essid, fDid = did;
            save.setEnabled(false); save.setText("校验中…");
            new Thread(() -> saveAccount(cs, fDid, fEssid, pw, save), "account-save").start();
        });
        c1.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        // ── BrandMeister 独立账号 ──

        LinearLayout cb = card();
        LinearLayout bh = new LinearLayout(this);
        bh.setOrientation(LinearLayout.HORIZONTAL);
        bh.setGravity(Gravity.CENTER_VERTICAL);
        bh.addView(circleIcon(android.R.drawable.ic_menu_compass, BLUE, 46));
        LinearLayout bht = new LinearLayout(this);
        bht.setOrientation(LinearLayout.VERTICAL);
        TextView bmTitle = tv("网络配置 · BM服务器", 16, TXT);
        bmTitle.setTypeface(null, Typeface.BOLD);
        bht.addView(bmTitle);
        bht.addView(tv("统一使用账号呼号、DMR ID 与 ESSID；BM 热点密码由身份注册同步", 11, SUB));
        bh.addView(bht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cb.addView(bh);
        cb.addView(row("呼号", tv(p.callsign().isEmpty() ? "未配置" : p.callsign(), 14, TXT)));
        cb.addView(row("DMR ID", tv(p.dmrId() > 0 ? String.valueOf(p.dmrId()) : "未配置", 14, TXT)));
        cb.addView(row("ESSID", tv(String.format(java.util.Locale.US, "%02d", p.essid()), 14, TXT)));
        cb.addView(row("热点密码", tv(p.dmrServerPassword().isEmpty() ? "未同步" : "已同步", 14, TXT)));
        LinearLayout bmServerTile = settingTile(android.R.drawable.ic_menu_compass, BLUE,
                "\u4e3b\u7ad9\u670d\u52a1\u5668", p.bmServerName(), true,
                v -> showBmServerPicker());
        cb.addView(bmServerTile);
        try {
            LinearLayout bmServerCol = (LinearLayout) bmServerTile.getChildAt(1);
            bmServerValue = (TextView) bmServerCol.getChildAt(1);
        } catch (Throwable ignored) { bmServerValue = null; }
        Button saveBm = btn("编辑身份与密码");
        bmSaveBtn = saveBm;
        saveBm.setOnClickListener(v -> startActivity(new Intent(this, IdentityActivity.class)));
        bmLoginBtn = btn("已断开");
        setLoginStatus(bmLoginBtn, "已断开", SUB);
        bmLoginBtn.setOnClickListener(v -> {
            if (bmHbp != null && bmHbp.isRunning()) { disconnectBm(); return; }
            if (hbp != null) { try { hbp.stop(); } catch (Throwable ignored) {} hbp = null; }
            bmLastConnectAttempt = 0;
            connectBm();
        });
        LinearLayout bmActions = new LinearLayout(this);
        bmActions.setOrientation(LinearLayout.HORIZONTAL);
        bmActions.setGravity(Gravity.CENTER_VERTICAL);
        bmActions.addView(saveBm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bmActions.addView(bmLoginBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cb.addView(bmActions);




        // ── 音频 ──
        LinearLayout ca = card();
        ca.addView(sectionTitle("统一音频处理"));
        ca.addView(tv("所有语音模式共用同一套收发 DSP，模式只负责编解码。", 10, SUB));

        final TextView rxVal = tv(gainText(p.rxGainDb()), 14, BLUE);
        final SeekBar rx = seek(p.rxGainDb() + 30);
        rx.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                rxVal.setText(gainText(pr - 30));
                if (fromUser) { p.set("rxDb", String.valueOf(pr - 30)); applyAudioPrefs(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        ca.addView(row("接收音量", rxVal));
        ca.addView(rx);

        final TextView rxAVal = tv(gainText(p.rxAgcTargetDb()), 14, BLUE);
        final SeekBar rxA = seekRange(-12, 12, p.rxAgcTargetDb());
        rxA.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                rxAVal.setText(gainText(pr - 30));
                if (fromUser) { p.set("rxAgcTargetDb", String.valueOf(pr - 30)); applyAudioPrefs(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        ca.addView(row("接收 AGC 调整", rxAVal));
        ca.addView(rxA);
        final Switch rxAgc = switchRow(ca, "接收 AGC", "0 为默认；正值更大，负值更小", p.rxAgc(),
                (v, c) -> { p.set("rxAgc", c ? "1" : "0"); applyAudioPrefs(); });
        switchRow(ca, "接收音量增强", "在统一增益基础上增加约 6 dB", p.rxVolumeBoost(),
                (v, c) -> { p.set("rxVolumeBoost", c ? "1" : "0"); applyAudioPrefs(); });
        switchRow(ca, "接收降噪", "抑制接收底噪和弱背景声", p.rxNoiseReduction(),
                (v, c) -> { p.set("rxNoiseReduction", c ? "1" : "0"); applyAudioPrefs(); });
        final Button listenTune = btn(AudioProfile.tuningLabel(p.listeningTuning()));
        listenTune.setOnClickListener(v -> showAudioTuningPicker(false, listenTune));
        ca.addView(row("收听调音模式", listenTune));
        final TextView txVal = tv(gainText(p.txGainDb()), 14, BLUE);
        final SeekBar tx = seek(p.txGainDb() + 30);
        tx.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                txVal.setText(gainText(pr - 30));
                if (fromUser) { p.set("txDb", String.valueOf(pr - 30)); applyAudioPrefs(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        ca.addView(row("发射音量", txVal));
        ca.addView(tx);

        final TextView txAVal = tv(gainText(p.txAgcTargetDb()), 14, BLUE);
        final SeekBar txA = seekRange(-12, 12, p.txAgcTargetDb());
        txA.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) {
                txAVal.setText(gainText(pr - 30));
                if (fromUser) { p.set("txAgcTargetDb", String.valueOf(pr - 30)); applyAudioPrefs(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        ca.addView(row("发射 AGC 调整", txAVal));
        ca.addView(txA);
        final Switch txAgc = switchRow(ca, "发射 AGC", "0 为默认；正值更大，负值更小", p.txAgc(),
                (v, c) -> { p.set("txAgc", c ? "1" : "0"); applyAudioPrefs(); });
        switchRow(ca, "发射降噪", "麦克风自适应降噪，默认开启", p.txNoiseReduction(),
                (v, c) -> { p.set("txNoiseReduction", c ? "1" : "0"); applyAudioPrefs(); });
        final Button voiceTune = btn(AudioProfile.tuningLabel(p.voiceTuning()));
        voiceTune.setOnClickListener(v -> showAudioTuningPicker(true, voiceTune));
        ca.addView(row("发射语音调音", voiceTune));

        Button resetAudio = btn("恢复音频默认设置");
        resetAudio.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("恢复音频默认设置")
                .setMessage("恢复收发音量、AGC、降噪和调音模式到默认值。通信身份、频道和网络配置不会改变。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    p.resetAudioDefaults();
                    applyAudioPrefs();
                    recreatePreservingScroll();
                })
                .setNegativeButton("取消", null)
                .show());
        ca.addView(resetAudio, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        switchRow(ca, "通话静音", "来电或有其它声音时自动静音", p.callMute(),
                (v, c) -> { p.set("callMute", c ? "1" : "0"); updateMute(); });
        switchRow(ca, "发射提示音", "", p.pttTone(),
                (v, c) -> p.set("pttTone", c ? "1" : "0"));
        final Runnable syncAudio = new Runnable() {
            @Override public void run() {
                rxA.setEnabled(p.rxAgc());
                txA.setEnabled(p.txAgc());
                rxAVal.setText(gainText(p.rxAgcTargetDb()));
                txAVal.setText(gainText(p.txAgcTargetDb()));
            }
        };
        rxAgc.setOnCheckedChangeListener((v, c) -> syncAudio.run());
        txAgc.setOnCheckedChangeListener((v, c) -> syncAudio.run());
        syncAudio.run();
        // ── 工作模式：2 列 tile 开关，不再是一整列 checkbox ──
        LinearLayout cm = card();
        cm.addView(sectionTitle("网络配置 · 工作模式"));
        java.util.List<View> modeTiles = new java.util.ArrayList<>();
        if (!BuildConfig.PUBLIC_EDITION) {
            modeTiles.add(toggleTile("数字中继", "本地 DMR / 直频", p.showDigital(), (v,c) -> {
                p.set("showDigital", c ? "1" : "0"); p.set("modeDigital", c ? "1" : "0");
                if (!c && hbp != null) { p.set("dmrAuto", "0"); hbp.stop(); hbp = null; }
                rebuildNavForSettings();
            }));
            modeTiles.add(toggleTile("模拟互联", "FMO / FM 网关", p.showAnalog(), (v,c) -> {
                p.set("showAnalog", c ? "1" : "0"); p.set("modeAnalog", c ? "1" : "0");
                if (!c) stopFmo();
                rebuildNavForSettings();
            }));
        }
        modeTiles.add(toggleTile("DMR", "BrandMeister / 直连", p.showBm(), (v,c) -> {
            p.set("showBm", c ? "1" : "0"); if (!c) disconnectBm();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile("D-Star", "反射器 / 模块", p.showDstar(), (v,c) -> {
            p.set("showDstar", c ? "1" : "0"); if (!c) disconnectDstarDirect();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile("YSF", "C4FM 房间", p.showYsf(), (v,c) -> {
            p.set("showYsf", c ? "1" : "0"); p.set("modeYsf", c ? "1" : "0");
            if (!c && ysfActive()) disconnectYsf();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile("P25", "P25 网关", p.showP25(), (v,c) -> {
            p.set("showP25", c ? "1" : "0"); p.set("modeP25", c ? "1" : "0");
            if (!c && p25Active()) disconnectP25();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile("NXDN", "Icom / Kenwood", p.showNxdn(), (v,c) -> {
            p.set("showNxdn", c ? "1" : "0"); p.set("modeNxdn", c ? "1" : "0");
            if (!c && nxdnActive()) disconnectNxdn();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile(BuildConfig.PUBLIC_EDITION ? "Net" : "网络对讲", "APP 房间 / 语音", p.showNetwork(), (v,c) -> {
            p.set("showNetwork", c ? "1" : "0");
            if (!c) leaveRoom();
            rebuildNavForSettings();
        }));
        modeTiles.add(toggleTile("NRL", "UDP 多服务器 / 对讲", p.showNrl(), (v,c) -> {
            p.set("showNrl", c ? "1" : "0");
            if (!c) disconnectNrl();
            rebuildNavForSettings();
        }));
        if (!BuildConfig.PUBLIC_EDITION) {
            modeTiles.add(toggleTile("网络收音机", "在线电台 / SDR 列表", p.showNetRadio(), (v,c) -> {
                p.set("showNetRadio", c ? "1" : "0");
                if (!c) stopRadioPlayback();
                rebuildNavForSettings();
            }));
        }
        addToggleGrid(cm, modeTiles);

        LinearLayout csip = null;
        if (!BuildConfig.PUBLIC_EDITION) {
            csip = card();
            csip.addView(sectionTitle("SIP 电话"));
            csip.addView(tv("默认关闭。开启后保持注册，来电可接听，断网后自动重连。", 10, SUB));
            final Switch sipOn = switchRow(csip, "启用 SIP 电话", "IPPBX 注册 / 拨号 / 接听", p.sipEnabled(), (v, c) -> {
                p.setSipEnabled(c);
                SipRuntime.get(this).setEnabled(c);
            });
            final EditText sipServerEd = identityField("服务器地址", p.get("sipServer", ""), InputType.TYPE_CLASS_TEXT);
            final EditText sipPortEd = identityField("端口", p.get("sipPort", "5060"), InputType.TYPE_CLASS_NUMBER);
            final EditText sipNumberEd = identityField("电话号 / 认证用户名", p.get("sipNumber", ""), InputType.TYPE_CLASS_PHONE);
            final EditText sipPasswordEd = identityField("密码", p.get("sipPassword", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            csip.addView(row("服务器地址", sipServerEd));
            csip.addView(row("端口", sipPortEd));
            csip.addView(row("电话号", sipNumberEd));
            csip.addView(row("密码", sipPasswordEd));
            Button sipSave = btn("保存 SIP 设置");
            sipSave.setOnClickListener(v -> {
                String server = sipServerEd.getText().toString().trim();
                String number = sipNumberEd.getText().toString().trim();
                int port;
                try { port = Integer.parseInt(sipPortEd.getText().toString().trim()); }
                catch (Throwable t) { port = 5060; }
                if (sipOn.isChecked() && (server.isEmpty() || number.isEmpty() || port <= 0 || port > 65535)) {
                    toast("请填写 SIP 服务器、有效端口和电话号");
                    return;
                }
                p.set("sipServer", server);
                p.set("sipPort", String.valueOf(port));
                p.set("sipNumber", number);
                p.set("sipPassword", sipPasswordEd.getText().toString());
                p.setSipEnabled(sipOn.isChecked());
                SipRuntime runtime = SipRuntime.get(this);
                if (sipOn.isChecked()) runtime.reloadConfig(); else runtime.setEnabled(false);
                toast(sipOn.isChecked() ? "SIP 设置已保存，正在保持注册" : "SIP 已关闭");
                rebuildNavForSettings();
            });
            csip.addView(sipSave, new LinearLayout.LayoutParams(-1, dp(46)));
            Button sipOpen = btn("打开 SIP 拨号盘");
            sipOpen.setOnClickListener(v -> openSipPhone());
            csip.addView(sipOpen, new LinearLayout.LayoutParams(-1, dp(46)));
        }

        LinearLayout cl = card();
        cl.addView(sectionTitle("启动与监听"));
        java.util.List<View> listenTiles = new java.util.ArrayList<>();
        listenTiles.add(toggleTile("多路监听", "同时监听所有音频", p.multiListen(), (v,c) ->
                p.set("multiListen", c ? "1" : "0")));
        if (!BuildConfig.PUBLIC_EDITION) {
            listenTiles.add(toggleTile("双守听", "同时监听中继双时隙", p.dualWatch(), (v,c) ->
                    toggleDualWatch(c)));
        }
        addToggleGrid(cl, listenTiles);
        cl.addView(sectionTitle("开机自动连接"));
        java.util.List<View> autoTiles = new java.util.ArrayList<>();
        if (!BuildConfig.PUBLIC_EDITION) {
            autoTiles.add(toggleTile("数字中继", "启动时自动连接", p.autoDigital(), (v,c) ->
                    p.set("autoDigital", c ? "1" : "0")));
            autoTiles.add(toggleTile("模拟互联", "启动时自动连接", p.autoAnalog(), (v,c) ->
                    p.set("autoAnalog", c ? "1" : "0")));
        }
        autoTiles.add(toggleTile("Net", "启动时自动连接", p.autoNetwork(), (v,c) ->
                p.set("autoNetwork", c ? "1" : "0")));
        autoTiles.add(toggleTile("NRL", "启动时自动连接", p.autoNrl(), (v,c) ->
                p.set("autoNrl", c ? "1" : "0")));
        autoTiles.add(toggleTile("DMR", "启动时自动连接", p.autoBm(), (v,c) -> {
            p.set("autoBm", c ? "1" : "0");
            p.set("bmAutoLogin", c ? "1" : "0");
        }));
        autoTiles.add(toggleTile("D-Star", "启动时自动连接", p.autoDstar(), (v,c) ->
                p.set("autoDstar", c ? "1" : "0")));
        autoTiles.add(toggleTile("YSF", "启动时自动连接", p.autoYsf(), (v,c) ->
                p.set("autoYsf", c ? "1" : "0")));
        autoTiles.add(toggleTile("P25", "启动时自动连接", p.autoP25(), (v,c) ->
                p.set("autoP25", c ? "1" : "0")));
        autoTiles.add(toggleTile("NXDN", "启动时自动连接", p.autoNxdn(), (v,c) ->
                p.set("autoNxdn", c ? "1" : "0")));
        addToggleGrid(cl, autoTiles);


        // ── 显示 ──
        LinearLayout c3 = card();
        c3.addView(sectionTitle("系统设置"));
        switchRow(c3, "深色主题", "关闭使用浅色界面", p.dark(), (v, c) -> {
            p.set("theme", c ? "dark" : "light");
            reThemeInPlace();
        });
        switchRow(c3, "屏幕常亮", "保持屏幕点亮", p.keepScreenOn(),
                (v, c) -> { p.set("keepScreen", c ? "1" : "0"); applyKeepScreenOn(); });
        switchRow(c3, "开机自启", "开机后自动启动 APP 和后台通信", p.bootAutoStart(),
                (v, c) -> {
                    p.setBootAutoStart(c);
                    toast(c ? "开机自启已开启" : "开机自启已关闭，重启后不会自动启动");
                });
        if (!BuildConfig.OVERSEAS_EDITION) {
            switchRow(c3, "呼号增强显示", "接收时放大对方呼号；待机和发射时保持普通布局",
                    p.callsignEnhanced(), (v, c) -> {
                        p.setCallsignEnhanced(c);
                        toast(c ? "呼号增强显示已开启" : "呼号增强显示已关闭");
                        recreatePreservingScroll();
                    });
        }
        TextView accentTitle = tv("主题颜色", 12, SUB);
        accentTitle.setPadding(0, dp(8), 0, dp(2));
        c3.addView(accentTitle);
        LinearLayout accentRow = new LinearLayout(this);
        accentRow.setOrientation(LinearLayout.HORIZONTAL);
        accentRow.setGravity(Gravity.CENTER);
        String currentAccent = accentKey();
        for (int ai = 0; ai < ACCENT_KEYS.length; ai++) {
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            chipLp.setMargins(dp(1), 0, dp(1), 0);
            accentRow.addView(accentChip(ACCENT_KEYS[ai], ACCENT_LABELS[ai],
                    ACCENT_KEYS[ai].equals(currentAccent)), chipLp);
        }
        c3.addView(accentRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c3.addView(tv("选择后立即应用；深色/浅色主题会分别使用对应的强调色。", 10, SUB));
        // [3.2.32] 界面字号：直接做成页内单选按钮（与「发射方式」同款控件，不依赖弹窗/整行点击）
        c3.addView(tv("界面字号（自动=按屏幕分辨率算，可手动微调）", 12, SUB));
        {
            final String[] fk = { "auto", "small", "normal", "large", "xlarge" };
            final String[] fl = { "自动", "小", "标准", "大", "特大" };
            final String[] fd = { "自动（推荐）", "小 90%", "标准 100%", "大 112%", "特大 125%" };
            RadioGroup fontGroup = new RadioGroup(this);
            fontGroup.setOrientation(RadioGroup.HORIZONTAL);
            String curMode = "auto";
            try { curMode = p.uiFontMode(); } catch (Throwable ignored) {}
            for (int i = 0; i < fk.length; i++) {
                RadioButton rb = new RadioButton(this);
                rb.setText(fl[i]);
                rb.setTextColor(TXT);
                rb.setId(FONT_GROUP_ID_BASE + i);
                if (fk[i].equals(curMode)) rb.setChecked(true);
                fontGroup.addView(rb);
            }
            fontGroup.setOnCheckedChangeListener((g, id) -> {
                int idx = id - FONT_GROUP_ID_BASE;
                if (idx < 0 || idx >= fk.length) return;
                p.set("uiFontMode", fk[idx]);
                toast("界面字号：" + fd[idx]);
                recreatePreservingScroll();
            });
            c3.addView(fontGroup);
        }


        // ── 音频输出 / 连接 / 通知 ──
        LinearLayout cx = card();
        cx.addView(sectionTitle("提醒功能"));
        switchRow(cx, "使用听筒", "关闭则用扬声器", p.isEarpiece(), (v, c) -> {
            p.set("audioRoute", c ? "earpiece" : "speaker");
            applyAudioRoute();
        });
        switchRow(cx, "掉线自动重连", "连接中断后自动恢复", p.autoReconnect(),
                (v, c) -> p.set("autoReconnect", c ? "1" : "0"));
        switchRow(cx, "后台常驻通知", "关闭后容易被系统杀掉后台连接", p.showNotification(), (v, c) -> {
            p.set("showNotify", c ? "1" : "0");
            toast(c ? "下次启动生效" : "后台保活已关闭，注意系统可能断连");
        });
        switchRow(cx, "收到通话时提醒", "有新通话时提示", p.alertCalls(),
                (v, c) -> p.set("alertCalls", c ? "1" : "0"));
        switchRow(cx, "连接状态变化提醒", "连接或断开时提示", p.alertConnection(),
                (v, c) -> p.set("alertConnection", c ? "1" : "0"));
        switchRow(cx, "提醒时振动", "配合通知使用", p.alertVibrate(),
                (v, c) -> p.set("alertVibrate", c ? "1" : "0"));
        if (BuildConfig.HYTERA_EDITION) {
            switchRow(cx, "海能达原生来电振动", "海能达原生来电/通话时振动", p.hyteraIncomingVibrate(),
                    (v, c) -> p.set("hyteraIncomingVibrate", c ? "1" : "0"));
        }
        switchRow(cx, "悬浮 PTT", "在其他软件上发射所选模式", p.overlayPtt(), (v, c) -> {
            p.set("overlayPtt", c ? "1" : "0");
            if (c) requestOverlayPtt(); else stopOverlayPtt();
        });
        cx.addView(tv("安卓系统还需允许：悬浮窗、后台弹出界面、自启动/后台运行；最近任务中锁定 ARCH。", 10, SUB));



        LinearLayout ce = card();
        ce.addView(sectionTitle("发射模式"));
        switchRow(ce, "启用外接 PTT", "厂商广播键、蓝牙耳机、蓝牙 PTT", p.externalPttEnabled(), (v, c) -> {
            p.set("extPttEnabled", c ? "1" : "0");
            if (c) p.set("extPttKeySource", "broadcast");
            ensureKeepAliveService();
        });
        if (BuildConfig.HYTERA_EDITION) {
            switchRow(ce, "海能达信道旋钮", "旋转切换当前模式的房间或通话组", p.hyteraKnobEnabled(),
                    (v, c) -> p.set("hyteraKnobEnabled", c ? "1" : "0"));
        }
        ce.addView(sectionTitle("发射方式"));
        RadioGroup pttModeGroup = new RadioGroup(this);
        pttModeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton holdMode = new RadioButton(this); holdMode.setText("按住发射"); holdMode.setTextColor(TXT);
        RadioButton tapMode = new RadioButton(this); tapMode.setText("点按发射"); tapMode.setTextColor(TXT);
        pttModeGroup.addView(holdMode); pttModeGroup.addView(tapMode);
        if ("tap".equals(p.pttMode())) tapMode.setChecked(true); else holdMode.setChecked(true);
        pttModeGroup.setOnCheckedChangeListener((g, id) -> p.set("pttMode", id == tapMode.getId() ? "tap" : "hold"));
        ce.addView(pttModeGroup);

        final TextView totLabel = tv("发射超时 TOT：" + p.pttTimeout() + " 秒", 12, SUB);
        SeekBar totSeek = new SeekBar(this);
        totSeek.setMax(570);
        totSeek.setProgress(p.pttTimeout() - 30);
        totSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int seconds = progress + 30;
                totLabel.setText("发射超时 TOT：" + seconds + " 秒");
                if (fromUser) p.set("pttTimeout", String.valueOf(seconds));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        ce.addView(totLabel); ce.addView(totSeek);

        switchRow(ce, "PTT 忙锁", "频道忙时不允许抢占", p.pttBusyLock(),
                (v, c) -> p.set("pttBusyLock", c ? "1" : "0"));
        switchRow(ce, "PTT 唤醒屏幕", "按键时点亮屏幕并回到 APP", p.pttWakeFocus(),
                (v, c) -> p.set("pttWakeFocus", c ? "1" : "0"));
        switchRow(ce, "显示屏幕 PTT", "在页面底部显示发射按钮", p.pttOnScreen(),
                (v, c) -> { p.set("pttOnScreen", c ? "1" : "0"); recreatePreservingScroll(); });
        switchRow(ce, "后台 / 锁屏 PTT", "配合广播值或悬浮 PTT 使用", p.pttBackground(),
                (v, c) -> { p.set("pttBackground", c ? "1" : "0"); ensureKeepAliveService(); });
        ce.addView(settingTile(android.R.drawable.ic_menu_send, BLUE, "PTT 发射模式",
                pttTargetLabel(p.pttTargetMode()), true, v -> showPttModePicker()));
        extPttKeyStatus = tv("", 11, SUB);
        extPttKeyStatus.setLineSpacing(0, 1.25f);
        ce.addView(extPttKeyStatus);
        updateExtKeyStatus();
        Button setKey = btn("广播键");
        setKey.setOnClickListener(v -> showBroadcastPttDialog());
        Button keyDiag = btn("诊断");
        keyDiag.setOnClickListener(v -> showKeyDiagDialog());
        Button addBc = btn("手动添加");
        addBc.setOnClickListener(v -> manualBroadcastPtt());
        ce.addView(actionToolbar(setKey, keyDiag, addBc));
        switchRow(ce, "设备按键后台生效", "设备实体 PTT 在后台 / 锁屏也生效", p.externalPttBackground(),
                (v, c) -> { p.set("extPttBackground", c ? "1" : "0"); updateExtKeyStatus(); });
        ce.addView(tv("实体 PTT 优先使用设备厂商广播值；不支持广播的老设备才使用无障碍键码兼容（默认关闭）。", 10, SUB));


        // ── 系统 ──
        LinearLayout c4 = card();
        c4.addView(sectionTitle("更新服务"));
        Button up = btn("系统更新");
        up.setOnClickListener(v -> new Updater(MainActivity.this).check(false, null));
        Button listUp = btn("服务器列表");
        listUp.setOnClickListener(v -> updateServerLists());
        Button cu = btn("通讯录");
        cu.setOnClickListener(v -> {
            contactStatus.setText("通讯录: 正在更新…");
            contacts.refreshAsync(() -> ui.post(this::refreshContactInfo));
        });
        c4.addView(actionToolbar(up, listUp, cu));

        c3.addView(settingTile(android.R.drawable.ic_menu_manage, BLUE,
                "后台常驻设置", "忽略电池优化并保持后台连接", true,
                v -> requestBatteryOptimizationExemption()));
        contactStatus = tv("通讯录: 正在加载…", 12, SUB);
        contactStatus.setPadding(0, dp(6), 0, 0);
        c4.addView(contactStatus);
        c4.addView(tv("版本 " + BuildConfig.DISPLAY_VERSION_LABEL, 12, SUB));


        LinearLayout caprs = card();
        caprs.addView(sectionTitle("APRS"));
        final Switch aprsEnable = switchRow(caprs, "启用 APRS-IS", "连接 APRS-IS 并发送位置信标", p.aprsEnabled(),
                (v, c) -> { if (c) requestLocationPermission(); });
        final EditText aprsCall = new EditText(this); aprsCall.setTextColor(TXT); aprsCall.setHint("台站呼号（不含SSID），例如 BG2GA"); aprsCall.setText(p.aprsCallsign());
        caprs.addView(row("台站呼号", aprsCall));
        final EditText aprsSsid = new EditText(this); aprsSsid.setTextColor(TXT); aprsSsid.setInputType(InputType.TYPE_CLASS_NUMBER); aprsSsid.setText(p.aprsSsid());
        caprs.addView(row("台站 SSID（必填 0-15）", aprsSsid));
        final String[] symbolLabels = {"汽车", "摩托车", "自行车", "步行", "房屋", "便携设备", "中继台", "气象站"};
        final String[] symbolValues = {">", "<", "b", "[", "-", "O", "r", "_"};
        Spinner aprsSymbol = new Spinner(this);
        ArrayAdapter<String> symbolAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, symbolLabels);
        symbolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aprsSymbol.setAdapter(symbolAdapter);
        int symbolIndex = 0;
        for (int i = 0; i < symbolValues.length; i++) if (symbolValues[i].equals(p.aprsSymbol())) symbolIndex = i;
        aprsSymbol.setSelection(symbolIndex, false);
        caprs.addView(row("台站图标", aprsSymbol));
        final String[] aprsServerLabels = {
                "亚洲 · asia.aprs2.net", "中国 · 202.141.176.2:14580",
                "北美 · noam.aprs2.net", "南美 · soam.aprs2.net",
                "欧洲与非洲 · euro.aprs2.net", "大洋洲 · aunz.aprs2.net"
        };
        final String[] aprsServerValues = {
                "asia.aprs2.net", "202.141.176.2", "noam.aprs2.net",
                "soam.aprs2.net", "euro.aprs2.net", "aunz.aprs2.net"
        };
        Spinner aprsHost = new Spinner(this);
        ArrayAdapter<String> aprsServerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, aprsServerLabels);
        aprsServerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aprsHost.setAdapter(aprsServerAdapter);
        int aprsServerIndex = 0;
        for (int i = 0; i < aprsServerValues.length; i++)
            if (aprsServerValues[i].equalsIgnoreCase(p.aprsServer())) aprsServerIndex = i;
        aprsHost.setSelection(aprsServerIndex, false);
        caprs.addView(row("APRS-IS 服务器", aprsHost));
        final EditText aprsPort = new EditText(this); aprsPort.setTextColor(TXT); aprsPort.setInputType(InputType.TYPE_CLASS_NUMBER); aprsPort.setText("14580"); aprsPort.setEnabled(false);
        caprs.addView(row("端口（固定）", aprsPort));
        final EditText aprsPass = new EditText(this); aprsPass.setTextColor(TXT); aprsPass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED); aprsPass.setText(AprsClient.passcodeFor(aprsCall.getText().toString()));
        caprs.addView(row("APRS passcode（自动生成）", aprsPass));
        aprsCall.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { aprsPass.setText(AprsClient.passcodeFor(s == null ? "" : s.toString())); }
        });
        final EditText aprsInterval = new EditText(this); aprsInterval.setTextColor(TXT); aprsInterval.setInputType(InputType.TYPE_CLASS_NUMBER); aprsInterval.setText(String.valueOf(p.aprsInterval()));
        caprs.addView(row("信标间隔（秒）", aprsInterval));
        final EditText aprsComment = new EditText(this); aprsComment.setTextColor(TXT); aprsComment.setText(p.aprsComment());
        caprs.addView(row("状态内容", aprsComment));
        LinearLayout aprsActions = new LinearLayout(this); aprsActions.setOrientation(LinearLayout.HORIZONTAL);
        Button aprsSave = btn("\u4fdd\u5b58\u8bbe\u7f6e"); Button aprsTest = btn("\u5df2\u65ad\u5f00");
        aprsStateBtn = aprsTest;
        aprsTest.setClickable(false);
        aprsTest.setFocusable(false);
        aprsSave.setOnClickListener(v -> {
            int port = 14580, interval = 60;
            try { port = Integer.parseInt(aprsPort.getText().toString().trim()); } catch (Exception ignored) {}
            try { interval = Integer.parseInt(aprsInterval.getText().toString().trim()); } catch (Exception ignored) {}
            String ssid = aprsSsid.getText().toString().trim();
            int ssidValue = -1;
            try { ssidValue = Integer.parseInt(ssid); } catch (Exception ignored) {}
            if (ssidValue < 0 || ssidValue > 15) { toast("APRS 台站 SSID 必填，范围 0-15"); return; }
            if (aprsEnable.isChecked()) requestLocationPermission();
            String autoPass = AprsClient.passcodeFor(aprsCall.getText().toString()); aprsPass.setText(autoPass);
            p.set("aprsEnabled", aprsEnable.isChecked() ? "1" : "0");
            p.set("aprsCallsign", aprsCall.getText().toString().trim().toUpperCase(java.util.Locale.US));
            p.set("aprsSsid", ssid);
            p.set("aprsSymbol", symbolValues[aprsSymbol.getSelectedItemPosition()]);
            p.set("aprsServer", aprsServerValues[aprsHost.getSelectedItemPosition()]);
            p.set("aprsPort", "14580");
            p.set("aprsPasscode", autoPass);
            p.set("aprsInterval", String.valueOf(interval));
            p.set("aprsComment", aprsComment.getText().toString().trim());
            if (aprs != null) { aprs.stop(); if (p.aprsEnabled()) aprs.start(); }
            refreshAprsStatusButton();
            ui.postDelayed(MainActivity.this::refreshAprsStatusButton, 1200L);
            toast(p.aprsEnabled() ? "APRS-IS 已保存并启动" : "APRS-IS 已关闭");
        });

        aprsActions.addView(aprsSave, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        aprsActions.addView(aprsTest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        caprs.addView(aprsActions);
        caprs.addView(tv("SSID 和台站图标必填；开启时请求 GPS 权限，发送带经纬度的 APRS 位置信标。", 10, SUB));

        LinearLayout c5 = card();
        TextView a1 = tv("ARCH 业余无线电融合中枢", 13, BLUE);
        a1.setPaintFlags(a1.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        a1.setOnClickListener(v -> openUrl("https://arch.hljrsa.cn"));
        c5.addView(a1);
        // 第二行：协会（可点击，仅协会版显示）
        if (!BuildConfig.PUBLIC_EDITION) {
            TextView a2 = tv("黑龙江省无线电运动协会", 11, BLUE);
            a2.setPaintFlags(a2.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            a2.setOnClickListener(v -> openUrl("https://www.by2aa.org.cn"));
            c5.addView(a2);
        }
        // 第三行：作者
        TextView author = tv("项目作者：BG2GA · bg2ga@by2aa.org.cn", 11, SUB);
        author.setOnClickListener(v -> openUrl("mailto:bg2ga@by2aa.org.cn"));
        c5.addView(author);
        TextView legal = tv("开源许可与免责声明", 11, BLUE);
        legal.setPaintFlags(legal.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        legal.setOnClickListener(v -> showLegalDialog());
        c5.addView(legal);

        Button exitApp = btn("退出 APP（完全停止后台）");
        exitApp.setTextColor(RED);
        exitApp.setSingleLine(false);
        exitApp.setGravity(Gravity.CENTER);
        exitApp.setMinHeight(dp(50));
        exitApp.setPadding(dp(10), dp(8), dp(10), dp(8));
        exitApp.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("退出 APP")
                .setMessage("退出后将断开所有通信并停止全部后台服务。下次使用需要手动打开 APP。")
                .setNegativeButton("取消", null)
                .setPositiveButton("完全退出", (d, w) -> performCompleteExit())
                .show());
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exitLp.setMargins(0, dp(12), 0, dp(4));
        c5.addView(exitApp, exitLp);


        // 身份设置二级菜单
        LinearLayout cid = card();
        cid.addView(sectionTitle("身份设置与注册"));
        cid.addView(settingTile(android.R.drawable.ic_menu_myplaces, ACCENT,
                "身份设置与注册", "呼号、DMR ID、ESSID 与协会密码", true,
                v -> startActivity(new Intent(MainActivity.this, IdentityActivity.class))));

        // 设置中心：按类别切换，避免所有设置项一次堆满一页。
        settingsTabIndex = Math.max(0, Math.min(3, settingsTabIndex));
        String[] tabLabels = { "常用", "模式", "音频", "高级" };
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabBar.setBackground(roundStroke(SURFACE2, HAIRLINE, 14));
        LinearLayout.LayoutParams tabBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tinyScreen ? 36 : 42));
        tabBarLp.setMargins(0, 0, 0, dp(8));
        l.addView(tabBar, tabBarLp);

        FrameLayout tabHost = new FrameLayout(this);
        tabHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        l.addView(tabHost);
        final LinearLayout[] tabPages = new LinearLayout[4];
        final ScrollView[] tabScrolls = new ScrollView[4];
        final Button[] tabButtons = new Button[4];
        for (int i = 0; i < 4; i++) {
            tabPages[i] = new LinearLayout(this);
            tabPages[i].setOrientation(LinearLayout.VERTICAL);
            tabPages[i].setPadding(0, dp(6), 0, dp(6));
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            sv.addView(tabPages[i], new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tabHost.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tabScrolls[i] = sv;

            Button b = new Button(this);
            b.setText(tabLabels[i]);
            ts(b, tinyScreen ? 10 : 12);
            b.setAllCaps(false);
            b.setIncludeFontPadding(false);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(0, dp(2), 0, dp(2));
            b.setContentDescription("设置分类：" + tabLabels[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            blp.setMargins(dp(2), 0, dp(2), 0);
            tabBar.addView(b, blp);
            tabButtons[i] = b;
        }
        tabPages[0].addView(cid);
        tabPages[0].addView(c3);
        tabPages[0].addView(cx);
        tabPages[1].addView(cm);
        tabPages[1].addView(cl);
        if (csip != null) tabPages[1].addView(csip);
        tabPages[2].addView(ca);
        tabPages[2].addView(ce);
        tabPages[3].addView(caprs);
        tabPages[3].addView(c4);
        tabPages[3].addView(c5);

        final Runnable[] paintTabs = new Runnable[1];
        paintTabs[0] = () -> {
            for (int i = 0; i < 4; i++) {
                boolean on = i == settingsTabIndex;
                tabButtons[i].setTextColor(on ? ACCENT : SUB);
                tabButtons[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                tabButtons[i].setBackground(on
                        ? roundBg(withAlpha(ACCENT, DARK ? 48 : 24), 10)
                        : roundBg(0x00000000, 10));
                tabScrolls[i].setVisibility(on ? View.VISIBLE : View.GONE);
            }
            settingsScroll = tabScrolls[settingsTabIndex];
        };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabButtons[i].setOnClickListener(v -> {
                settingsTabIndex = idx;
                paintTabs[0].run();
            });
        }
        paintTabs[0].run();
        return l;
    }

 /**
 * 三个模式共用的页面骨架：内容区固定占满剩余空间，按住发射键钉在底部固定位置。
 * 主功能页不使用 ScrollView，避免用户还要在页面里上下挪动。
 */
    private LinearLayout pageWithPtt(View content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(content);
        root.addView(bigPtt());
        return root;
    }

 /** 内容较长页面的可滚动版本：PTT 固定在底部，其他内容可上下查看。 */
    private LinearLayout pageWithPttScroll(View content) {
        return pageWithPttScroll(content, false);
    }

    private LinearLayout pageWithPttScroll(View content, boolean rememberNetworkScroll) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        content.setLayoutParams(new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(content);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (rememberNetworkScroll) networkScroll = sv;
        root.addView(sv);
        root.addView(bigPtt());
        return root;
    }

 /**
 * 三个模式共用的「按住发射」大按钮。
 * 尺寸与位置统一按数字中继：容器 260dp、圆钮 230dp、上边距 8dp。
 * 按下变红、松开回蓝（颜色就是发射状态指示）。
 */
    private View bigPtt() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(pttHeightDp()));
        wlp.setMargins(dp(18), dp(8), dp(18), dp(12));
        wrap.setLayoutParams(wlp);

        final GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                pttGradient());
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(2), 0x77FFFFFF);

        final LinearLayout face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER);
        face.setPadding(0, dp(8), 0, dp(6));
        face.setBackground(bg);
        face.setElevation(dp(10));
        face.setTranslationZ(dp(7));
        face.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final View mic = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight(), cx = w / 2f;
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFFFFFFFF);
                float bw = w * 0.27f, top = h * 0.06f, bot = h * 0.59f;
                c.drawRoundRect(cx - bw, top, cx + bw, bot, bw, bw, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth((float)dp(3));
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                c.drawArc(new android.graphics.RectF(cx - bw * 1.65f, top + bw * 0.7f,
                        cx + bw * 1.65f, bot + bw * 0.9f), 0f, 180f, false, p);
                c.drawLine(cx, bot + bw * 1.0f, cx, h * 0.88f, p);
                c.drawLine(cx - bw * 1.15f, h * 0.88f, cx + bw * 1.15f, h * 0.88f, p);
            }
        };
        mic.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        mic.setClickable(false);
        face.addView(mic);

        final TextView ptt = new TextView(this);
        ptt.setText("PTT");
        ts(ptt, 34);
        ptt.setTypeface(null, Typeface.BOLD);
        ptt.setLetterSpacing(0.20f);
        ptt.setTextColor(0xFFFFFFFF);
        ptt.setGravity(Gravity.CENTER);
        ptt.setClickable(false);
        face.addView(ptt);
        TextView pttHint = tv("按住发射", tinyScreen ? 9 : 11, 0xE6FFFFFF);
        pttHint.setLetterSpacing(0.08f);
        pttHint.setTypeface(null, Typeface.BOLD);
        pttHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(2);
        face.addView(pttHint, hintLp);

        final boolean tapMode = "tap".equals(p.pttMode());
        face.setOnTouchListener((v, e) -> {
            try {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    face.animate().scaleX(0.985f).scaleY(0.985f).translationZ(dp(3)).setDuration(80L).start();
                    if (tapMode && (txing || hqTxRequested)) {
                        bg.setColors(pttGradient());
                        ptt.setText("PTT"); ts(ptt, 34); ptt.setLetterSpacing(0.20f);
                        face.invalidate(); pttUp(); return true;
                    }
                    bg.setColors(new int[]{0xFFFFB8B8, 0xFFF25C5C, 0xFFDC2626, 0xFF761414});
                    ptt.setText(tapMode ? "停止" : "发射中"); ts(ptt, tapMode ? 30 : 27); ptt.setLetterSpacing(0.08f);
                    face.invalidate(); pttDown(); return true;
                }
                if (!tapMode && (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)) {
                    face.animate().scaleX(1f).scaleY(1f).translationZ(dp(7)).setDuration(160L).start();
                    v.performClick();
                    bg.setColors(pttGradient());
                    ptt.setText("PTT"); ts(ptt, 34); ptt.setLetterSpacing(0.20f);
                    face.invalidate(); pttUp(); return true;
                }
                if (tapMode && e.getAction() == MotionEvent.ACTION_CANCEL) {
                    face.animate().scaleX(1f).scaleY(1f).translationZ(dp(7)).setDuration(160L).start();
                    bg.setColors(pttGradient());
                    ptt.setText("PTT"); ts(ptt, 34); ptt.setLetterSpacing(0.20f);
                    face.invalidate(); pttUp(); return true;
                }
            } catch (Throwable t) { log("PTT 触摸异常: " + t); }
            return true;
        });
        FrameLayout pttFrame = new FrameLayout(this);
        pttFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pttFrame.setClipChildren(false);
        pttFrame.setClipToPadding(false);
        pttFrame.setPadding(dp(7), dp(5), dp(7), dp(9));
        View halo = new View(this);
        GradientDrawable haloBg = new GradientDrawable();
        haloBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        haloBg.setGradientRadius(dp(150));
        haloBg.setGradientCenter(0.5f, 0.5f);
        haloBg.setColors(new int[]{ withAlpha(ACCENT, DARK ? 80 : 42), withAlpha(ACCENT, 0) });
        halo.setBackground(haloBg);
        pttFrame.addView(halo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pttFrame.addView(face);
        pttFrame.addView(new HudCornersView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(pttFrame);
        wrap.setVisibility(p.pttOnScreen() ? View.VISIBLE : View.GONE);
        return wrap;
    }

    private View buildFmoPage() {
        LinearLayout l = wrap();
        fmoCallState = tv("未登录", 12, SUB);
        fmoCallLine = tv("未登录", 24, TXT); fmoCallLine.setTypeface(null, Typeface.BOLD);
        fmoNetworkLine = tv("当前网络: BY2AA-FMO · 未登录", 12, BLUE);
        muteAnalogBtn = muteButton("analog");
        fmoLastLine = tv("上次呼叫：--", 15, SUB);
        l.addView(statusCard(muteAnalogBtn, fmoCallState, fmoCallLine, fmoLastLine, fmoNetworkLine));
        LinearLayout c1 = card();
        LinearLayout fmoHead = new LinearLayout(this);
        fmoHead.setOrientation(LinearLayout.HORIZONTAL);
        fmoHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView fmoTitle = tv("BY2AA-FMO服务器", 21, TXT);
        fmoTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fmoHead.addView(fmoTitle);
        c1.addView(fmoHead);
        c1.addView(tv("哈尔滨 438.500", 14, SUB));
        fmoButton = btn("未登录");
        setLoginStatus(fmoButton, "未登录", SUB);
        fmoButton.setOnClickListener(v -> { if (fmo != null && fmo.isRunning()) stopFmo(); else startFmo(); });
        c1.addView(fmoButton);
        l.addView(c1);


        LinearLayout c2 = card();
        c2.addView(tv("BY2AA-FMO 在线用户", 15, SUB));
        LinearLayout onlineWrap = new LinearLayout(this);
        onlineWrap.setOrientation(LinearLayout.VERTICAL);
        onlineWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        onlineBox = new LinearLayout(this); onlineBox.setOrientation(LinearLayout.VERTICAL);
        onlineBox.addView(onlineRow4("呼号", "UID", "频率MHz", "通话模式", true));
        onlineBox.addView(tv("暂无在线用户", 12, SUB));
        onlineWrap.addView(onlineBox);
        c2.addView(onlineWrap);
        LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2.setLayoutParams(c2lp);
        l.addView(c2);

        return pageWithPttScroll(l);
    }


    private View buildBmPage() {
        LinearLayout l = wrap();

        bmState = tv("已断开", 12, SUB);
        bmCallLine = tv("未登录", 24, TXT); bmCallLine.setTypeface(null, Typeface.BOLD);
        bmNow = tv("当前组: " + p.bmTg() + " · 单工", 15, TXT);
        bmServerLabel = tv("当前网络: " + p.bmServerName() + " · 已断开 ▾", 12, BLUE);
        bmServerLabel.setClickable(true);
        bmServerLabel.setFocusable(true);
        bmServerLabel.setContentDescription("点击选择 DMR 服务器");
        bmServerLabel.setOnClickListener(v -> showBmServerPicker());
        muteBmBtn = muteButton("bm");
        bmLastLine = tv("上次呼叫：--", 15, SUB);
        LinearLayout head = statusCard(muteBmBtn, bmState, bmCallLine, bmLastLine, bmServerLabel);
        l.addView(head);

        LinearLayout cg = card();
        cg.addView(sectionTitle("我的通话组"));
        cg.addView(tv("\u70b9\u51fb\u4e0b\u65b9\u5361\u7247\u5373\u53ef\u5207\u6362\u5e76\u81ea\u52a8\u9644\u7740", 11, SUB));
        bmTgRow = new LinearLayout(this);
        bmTgRow.setOrientation(LinearLayout.VERTICAL);
        cg.addView(bmTgRow);
        LinearLayout custom = new LinearLayout(this);
        custom.setOrientation(LinearLayout.HORIZONTAL);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        bmCustomTgEd = new EditText(this);
        bmCustomTgEd.setTextColor(TXT);
        bmCustomTgEd.setHint("\u901a\u8bdd\u7ec4 ID");
        bmCustomTgEd.setInputType(InputType.TYPE_CLASS_NUMBER);
        bmCustomTgEd.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ts(bmCustomTgEd, 14);
        bmCustomTgEd.setHintTextColor(withAlpha(SUB, 170));
        bmCustomTgEd.setBackground(roundStroke(SURFACE2, HAIRLINE, 10));
        bmCustomTgEd.setPadding(dp(12), dp(10), dp(12), dp(10));
        bmCustomTgEd.setMinHeight(dp(46));
        custom.addView(bmCustomTgEd);
        bmTgNameEd = new EditText(this);
        bmTgNameEd.setTextColor(TXT);
        bmTgNameEd.setHint("\u540d\u79f0");
        bmTgNameEd.setInputType(InputType.TYPE_CLASS_TEXT);
        bmTgNameEd.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        ts(bmTgNameEd, 14);
        bmTgNameEd.setHintTextColor(withAlpha(SUB, 170));
        bmTgNameEd.setBackground(roundStroke(SURFACE2, HAIRLINE, 10));
        bmTgNameEd.setPadding(dp(12), dp(10), dp(12), dp(10));
        bmTgNameEd.setMinHeight(dp(46));
        custom.addView(bmTgNameEd);
        Button addTg = btn("\u6dfb\u52a0");
        addTg.setOnClickListener(v -> addBmTalkgroup());
        custom.addView(addTg);
        cg.addView(custom);
        cg.addView(tv("\u957f\u6309\u5361\u7247\u53ef\u5220\u9664", 11, SUB));
        l.addView(cg);

        LinearLayout ch = card();
        ch.addView(sectionTitle("通话记录"));
        LinearLayout tableHead = new LinearLayout(this);
        tableHead.setOrientation(LinearLayout.HORIZONTAL);
        tableHead.addView(tableCell("\u65f6\u95f4", 0.72f, BLUE, true));
        tableHead.addView(tableCell("\u547c\u53f7", 1.15f, BLUE, true));
        tableHead.addView(tableCell("\u76ee\u6807", 0.72f, BLUE, true));
        tableHead.addView(tableCell("\u65f6\u957f", 0.55f, BLUE, true));
        ch.addView(tableHead);
        bmLogBox = new LinearLayout(this);
        bmLogBox.setOrientation(LinearLayout.VERTICAL);
        bmLogBox.setPadding(0, dp(6), 0, 0);
        bmLogBox.setMinimumHeight(dp(100));
        ch.addView(bmLogBox);
        ch.setMinimumHeight(dp(160));
        l.addView(ch);
        renderBmLogs();

        buildBmTgButtons();
        return pageWithPttScroll(l);
    }


    private void buildBmTgButtons() {
        if (bmTgRow == null) return;
        bmTgRow.removeAllViews();
        java.util.ArrayList<String[]> list = new java.util.ArrayList<>();
        for (String part : p.bmTalkgroups().split(",")) {
            if (part == null || part.trim().length() == 0) continue;
            String[] kv = part.trim().split(":", 2);
            int id;
            try { id = Integer.parseInt(kv[0].trim()); } catch (Exception e) { continue; }
            if (id <= 0) continue;
            String name = kv.length > 1 && kv[1].trim().length() > 0 ? kv[1].trim() : String.valueOf(id);
            list.add(new String[]{String.valueOf(id), name});
        }
        if (list.isEmpty()) {
            bmTgRow.addView(tv("\u8fd8\u6ca1\u6709\u901a\u8bdd\u7ec4\uff0c\u8bf7\u5728\u4e0b\u65b9\u8f93\u5165\u7ec4\u53f7\u6dfb\u52a0\u3002", 12, SUB));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(2), 0, dp(2));
                bmTgRow.addView(row);
            }
            int tg = Integer.parseInt(list.get(i)[0]);
            String name = list.get(i)[1];
            boolean selected = tg == p.bmTg();
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(12), dp(4), dp(12), dp(4));
            tile.setBackground(tgButtonBg(selected));
            TextView idv = tv(String.valueOf(tg), 17, selected ? 0xFFFFFFFF : BLUE);
            idv.setTypeface(null, Typeface.BOLD);
            TextView namev = tv(name, 11, selected ? 0xE8FFFFFF : SUB);
            namev.setSingleLine(true);
            namev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView state = tv(selected ? "\u2713 \u5df2\u9644\u7740" : "\u70b9\u51fb\u9644\u7740", 10, selected ? 0xDDFFFFFF : SUB);
            tile.addView(idv); tile.addView(namev); tile.addView(state);
            final int ftg = tg;
            tile.setOnClickListener(v -> switchBmTg(ftg));
            tile.setOnLongClickListener(v -> { confirmRemoveBmTalkgroup(ftg); return true; });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tlp.setMargins(dp(4), dp(3), dp(4), dp(3));
            tile.setLayoutParams(tlp);
            row.addView(tile);
        }
        if (row != null && row.getChildCount() == 1) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        }
    }

    private void addBmTalkgroup() {
        int tg;
        try { tg = Integer.parseInt(bmCustomTgEd.getText().toString().trim()); }
        catch (Exception e) { toast("请输入正确的通话组 ID"); return; }
        if (tg <= 0) { toast("请输入正确的通话组 ID"); return; }
        String name = bmTgNameEd.getText().toString().trim();
        if (name.length() == 0) name = String.valueOf(tg);
        java.util.LinkedHashMap<Integer,String> map = new java.util.LinkedHashMap<>();
        for (String part : p.bmTalkgroups().split(",")) {
            if (part == null || part.trim().length() == 0) continue;
            String[] kv = part.trim().split(":", 2);
            try {
                int id = Integer.parseInt(kv[0].trim());
                if (id > 0) map.put(id, kv.length > 1 && kv[1].trim().length() > 0 ? kv[1].trim() : String.valueOf(id));
            } catch (Exception ignored) {}
        }
        map.put(tg, name);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer,String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        p.set("bmTalkgroups", sb.toString());
        bmCustomTgEd.setText(""); bmTgNameEd.setText("");
        buildBmTgButtons();
        toast("已添加 TG" + tg);
    }


    private void confirmRemoveBmTalkgroup(int tg) {
        new AlertDialog.Builder(this)
                .setTitle("删除通话组")
                .setMessage("确定要删除 TG" + tg + " 吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> removeBmTalkgroup(tg))
                .show();
    }

    private void removeBmTalkgroup(int tg) {
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (String part : p.bmTalkgroups().split(",")) {
            if (part == null || part.trim().length() == 0) continue;
            String[] kv = part.trim().split(":", 2);
            int id = -1;
            try { id = Integer.parseInt(kv[0].trim()); } catch (Exception ignored) {}
            if (id == tg) { removed = true; continue; }
            if (sb.length() > 0) sb.append(',');
            sb.append(part.trim());
        }
        if (!removed) return;
        p.set("bmTalkgroups", sb.toString());
        if (p.bmTg() == tg) {
            int first = 91;
            for (String part : sb.toString().split(",")) {
                try { first = Integer.parseInt(part.split(":", 2)[0].trim()); break; } catch (Exception ignored) {}
            }
            p.set("bmTg", String.valueOf(first));
        }
        buildBmTgButtons();
        toast("已删除 TG" + tg);
        if (bmHbp != null) { disconnectBm(); bmLastConnectAttempt = 0; connectBm(); }
    }


    private void switchBmTg(int tg) {
        p.set("bmTg", String.valueOf(tg));
        if (bmNow != null) bmNow.setText("当前组: " + tg + " · 单工");
        setBmStatus(bmHbp != null && bmHbp.isConnected(), false, null);
        buildBmTgButtons();
        updateModeLabel();
        if (bmHbp != null && bmHbp.isRunning()) {
            // Recreate the HBP session so BrandMeister reads the selected
            // talkgroup from RPTO during login. Some BM masters accept a
            // dynamic RPTO only inconsistently.
            disconnectBm();
            bmLastConnectAttempt = 0;
            connectBm();
            scheduleBmAutoAttach(2600);
        } else if (p.bmAutoLogin() && p.bmCallsign().length() > 0 && p.dmrServerId() > 0) {
            bmLastConnectAttempt = 0;
            connectBm();
        }
    }

    private String bmTgDisplayName() {
        int current = p.bmTg();
        for (String part : p.bmTalkgroups().split(",")) {
            if (part == null || part.trim().isEmpty()) continue;
            String[] kv = part.trim().split(":", 2);
            try {
                if (Integer.parseInt(kv[0].trim()) == current)
                    return kv.length > 1 && !kv[1].trim().isEmpty() ? kv[1].trim() : (current + "组");
            } catch (Throwable ignored) {}
        }
        return current + "组";
    }

    private String bmOptions() {
        return (p.bmSlot() == 2 ? "TS2=" : "TS1=") + p.bmTg() + ";";
    }

    private void scheduleBmAutoAttach(long delay) {
        final int generation = ++bmAttachGeneration;
        ui.postDelayed(() -> {
            if (generation == bmAttachGeneration) autoStaticBmTalkgroup();
        }, delay);
    }

    /**
     * BM simplex Auto Static: one TG4000 LC header, wait for the private
     * control burst to finish, then one target-group LC header and RPTO.
     * No audio frames, terminator, or microphone are used.
     */
    private void autoStaticBmTalkgroup() {
        final HbpClient client = bmHbp;
        if (client == null || !client.isConnected() || bmAutoAttachRunning) return;
        if (txing || hqTxRequested || rxActive) { scheduleBmAutoAttach(1800L); return; }
        final int targetTg = p.bmTg();
        final int targetSlot = p.bmSlot();
        if (targetTg <= 0) return;
        bmAutoAttachRunning = true;
        new Thread(() -> {
            try {
                if (!client.isConnected() || txing || hqTxRequested || rxActive) {
                    if (client.isConnected()) scheduleBmAutoAttach(1800L);
                    return;
                }
                String serverKey = p.bmServerKey();
                if ("860".equals(serverKey) || "46020".equals(serverKey)) {
                    client.setTalkgroup(targetTg, targetSlot);
                    android.util.Log.i("ARCH-DMR", "DMR server attach: " + serverKey + " TG" + targetTg);
                    log(p.bmServerName() + " 组设置完成：TG" + targetTg);
                    return;
                }
                android.util.Log.i("ARCH-DMR", "BM auto-static begin: TG4000 -> TG" + targetTg
                        + " peer=" + p.dmrServerId() + "/" + p.dmrServerEssid());
                if (!client.txHeaderTo(4000)) return;
                Thread.sleep(2000L);
                if (!client.isConnected()) return;
                if (txing || hqTxRequested || rxActive) {
                    scheduleBmAutoAttach(1800L);
                    return;
                }
                if (!client.txHeaderTo(targetTg)) return;
                Thread.sleep(120L);
                client.setTalkgroup(targetTg, targetSlot);
                android.util.Log.i("ARCH-DMR", "BM auto-static complete: TG" + targetTg);
                log("DMR 自动附着完成：TG" + targetTg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                android.util.Log.w("ARCH-DMR", "BM auto-static failed", t);
                log("DMR 自动附着异常: " + t);
            } finally {
                bmAutoAttachRunning = false;
            }
        }, "bm-auto-static").start();
    }

    private void connectBm() {
        long now = System.currentTimeMillis();
        if (now - bmLastConnectAttempt < 1500) return;
        bmLastConnectAttempt = now;
        String serverKey = p.bmServerKey();
        String cs = p.bmCallsign();
        int id = p.dmrServerId();
        int es = p.dmrServerEssid();
        if (es <= 0 && !"860".equals(serverKey) && !"46020".equals(serverKey)) es = 1;
        String pw = p.dmrServerPassword();
        if (cs.isEmpty() || id <= 0 || pw.isEmpty()) {
            toast("请在设置 → 常用 → 身份设置与注册填写 " + p.bmServerName() + " 的 DMR ID、ESSID 和密码");
            setPage(4);
            return;
        }
        if (bmHbp != null && bmHbp.isConnected()) {
            bmHbp.setTalkgroup(p.bmTg());
            return;
        }
        if (bmHbp != null) { try { bmHbp.stop(); } catch (Throwable ignored) {} bmHbp = null; }
        int peerId = id * 100 + es;
        setBmStatus(false, true, null);
        bmHbp = new HbpClient(p.bmServerHost(), p.bmServerPort(), peerId, id, p.bmTg(), 0,
                p.bmSlot(), 7, cs, pw, bmOptions(), new HbpSourceListener(true));
        bmHbp.start();
        updateModeLabel();
    }

    private void disconnectBm() {
        if (bmHbp != null) { try { bmHbp.stop(); } catch (Throwable ignored) {} bmHbp = null; }
        setBmStatus(false, false, null);
        if (bmNow != null) bmNow.setText("当前组: " + p.bmTg() + " · 单工");
        updateModeLabel();
    }




    /** Standard DMR ID used for registration and server login. */


 /**
 * ARCH-only mapped air ID: keep all digits except the first, and use
     * Official/private servers keep the standard ID unchanged.
 */


 /** Registration identity is always the standard 7-digit DMR ID. */








    private EditText identityField(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setTextColor(TXT);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setText(value == null ? "" : value);
        return e;
    }

    private LinearLayout identityForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);
        return form;
    }

    private void showIdentityMenu() {
        String[] items = {"协会服务器身份 / 注册", "DMR身份", "D-Star 呼号", "YSF 呼号"};
        new AlertDialog.Builder(this).setTitle("身份设置").setItems(items, (d, which) -> {
            if (which == 0) showAssociationIdentityDialog();
            else if (which == 1) showBmIdentityDialog();
            else if (which == 2) showDstarIdentityDialog();
            else if (which == 3) showYsfIdentityDialog();
            else if (which == 3) showYsfIdentityDialog();
        }).setNegativeButton("取消", null).show();
    }

    private void showAssociationIdentityDialog() {
        LinearLayout form = identityForm();
        EditText cs = identityField("呼号", p.callsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText id = identityField("DMR ID", p.dmrId() == 0 ? "" : String.valueOf(p.dmrId()), InputType.TYPE_CLASS_NUMBER);
        EditText es = identityField("ESSID", String.format(java.util.Locale.US, "%02d", p.essid()), InputType.TYPE_CLASS_NUMBER);
        EditText pw = identityField("协会服务器密码", p.password(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(row("呼号", cs)); form.addView(row("DMR ID", id)); form.addView(row("ESSID", es)); form.addView(row("密码", pw));
        new AlertDialog.Builder(this).setTitle("协会服务器身份").setView(form).setPositiveButton("保存并注册", (d, w) -> {
            String csText = cs.getText().toString().trim().toUpperCase(java.util.Locale.US);
            int did = 0, essid = 1;
            try { did = Integer.parseInt(id.getText().toString().trim()); } catch (Throwable ignored) {}
            try { essid = Integer.parseInt(es.getText().toString().trim()); } catch (Throwable ignored) {}
            essid = Math.max(0, Math.min(99, essid));
            String pwText = pw.getText().toString();
            if (csText.isEmpty() || did <= 0 || pwText.length() < 6) {
                toast("呼号、DMR ID 必填，协会密码至少 6 位");
                return;
            }
            if (callEd != null) callEd.setText(csText);
            if (idEd != null) idEd.setText(String.valueOf(did));
            if (essidEd != null) essidEd.setText(String.format(java.util.Locale.US, "%02d", essid));
            if (passEd != null) passEd.setText(pwText);
            saveAccount(csText, did, essid, pwText, null);
        }).setNegativeButton("取消", null).show();
    }

    private void showBmIdentityDialog() {
        LinearLayout form = identityForm();
        EditText cs = identityField("BM 呼号", p.bmCallsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText id = identityField("BM DMR ID", p.bmId() == 0 ? "" : String.valueOf(p.bmId()), InputType.TYPE_CLASS_NUMBER);
        EditText es = identityField("BM ESSID", String.format(java.util.Locale.US, "%02d", p.bmEssid()), InputType.TYPE_CLASS_NUMBER);
        EditText pw = identityField("BM 服务器密码", p.bmPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(row("呼号", cs)); form.addView(row("DMR ID", id)); form.addView(row("ESSID", es)); form.addView(row("密码", pw));
        new AlertDialog.Builder(this).setTitle("DMR身份").setView(form).setPositiveButton("保存", (d, w) -> {
            p.set("bmCallsign", cs.getText().toString().trim().toUpperCase(java.util.Locale.US));
            p.set("bmId", id.getText().toString().trim());
            p.set("bmEssid", es.getText().toString().trim());
            p.set("bmPassword", pw.getText().toString());
            toast("BM 身份已保存");
        }).setNegativeButton("取消", null).show();
    }

    private void showDstarIdentityDialog() {
        LinearLayout form = identityForm();
        EditText cs = identityField("D-Star 呼号", p.dstarCallsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText ut = identityField("用户文本（最多20个ASCII字符）", p.dstarUserText(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(row("D-Star 呼号", cs));
        form.addView(row("用户文本", ut));
        new AlertDialog.Builder(this).setTitle("D-Star 身份").setView(form).setPositiveButton("保存", (d, w) -> {
            p.set("dstarCallsign", cs.getText().toString().trim().toUpperCase(java.util.Locale.US));
            p.set("dstarUserText", ut.getText().toString().trim());
            toast("D-Star 身份已保存");
        }).setNegativeButton("取消", null).show();
    }

    private void showYsfIdentityDialog() {
        LinearLayout form = identityForm();
        EditText cs = identityField("YSF 呼号", p.ysfCallsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText dg = identityField("DG-ID（0=全部）", String.valueOf(p.ysfDgId()), InputType.TYPE_CLASS_NUMBER);
        EditText options = identityField("反射器选项（YSFO，可空）", p.ysfOptions(), InputType.TYPE_CLASS_TEXT);
        form.addView(row("YSF 呼号", cs));
        form.addView(row("DG-ID", dg));
        form.addView(row("反射器选项", options));
        new AlertDialog.Builder(this).setTitle("YSF 身份与反射器").setView(form).setPositiveButton("保存", (d, w) -> {
            p.set("ysfCallsign", cs.getText().toString().trim().toUpperCase(java.util.Locale.US));
            try { p.setYsfDgId(Integer.parseInt(dg.getText().toString().trim())); } catch (Throwable ignored) { p.setYsfDgId(0); }
            p.setYsfOptions(options.getText().toString());
            updateYsfSelectors();
            toast("YSF 身份与反射器参数已保存");
        }).setNegativeButton("取消", null).show();
    }







































































    private boolean dstarActive() { return dstarDirectActive && dstarDirect != null && dstarDirect.isRunning(); }
    private String[] dstarServerInfo(String id) {
        if (id != null) for (String[] row : DSTAR_SERVERS) if (row[0].equalsIgnoreCase(id)) return row;
        return DSTAR_SERVERS[0];
    }
    private String dstarServerName(String id) { return DStarServers.displayName(id); }
    private String dstarSupportedModules(String id) { return dstarServerInfo(id)[2]; }
    private String dstarHost(String id) { return dstarServerInfo(id)[3]; }
    private String dstarDisplayName() { return dstarServerName(p.dstarServer()) + " " + p.dstarModule(); }
    private void updateDstarSelectors() {
        String sid = dstarServerInfo(p.dstarServer())[0];
        String modules = dstarSupportedModules(sid);
        if (modules == null || modules.trim().isEmpty()) modules = "A";
        String mod = p.dstarModule();
        if (mod.length() != 1 || modules.indexOf(mod.charAt(0)) < 0) mod = modules.toUpperCase(java.util.Locale.US).indexOf('A') >= 0 ? "A" : String.valueOf(modules.charAt(0));
        p.set("dstarServer", sid); p.set("dstarModule", mod);
        if (dstarServerBtn != null) dstarServerBtn.setText(dstarServerName(sid));
        if (dstarModuleBtn != null) dstarModuleBtn.setText("模块 " + mod);
        if (dstarServerLine != null) dstarServerLine.setText("DCS 服务器: " + dstarServerName(sid) + " · 模块: " + mod);
    }
    private void showDstarServerPicker() {
        String[] labels = new String[DSTAR_SERVERS.length]; int current = 0;
        for (int i=0;i<DSTAR_SERVERS.length;i++){
            labels[i]=DStarServers.displayName(DSTAR_SERVERS[i][0]);
            if(DSTAR_SERVERS[i][0].equalsIgnoreCase(p.dstarServer())) current=i;
        }
        showSearchablePicker("选择 D-Star 服务器", labels, current, which -> {
            String id=DSTAR_SERVERS[which][0]; p.set("dstarServer",id);
            String mods=dstarSupportedModules(id);
            if (mods == null || mods.trim().isEmpty()) mods = "A";
            p.set("dstarModule","XLXHLJ".equalsIgnoreCase(id) ? "A" : String.valueOf(mods.charAt(0)));
            disconnectDstarDirect(); updateDstarSelectors(); updateModeLabel();
        });
    }
    private void showDstarModulePicker() {
        String sid = p.dstarServer(); String mods = dstarSupportedModules(sid); String[] labels = new String[mods.length()]; int current=0;
        for(int i=0;i<mods.length();i++){ char mc=mods.charAt(i); String moduleLabel = DStarServers.moduleLabel(sid, String.valueOf(mc)); String suffix = moduleLabel.isEmpty() ? "" : " · " + moduleLabel; labels[i]="模块 "+mc+suffix; if(String.valueOf(mc).equalsIgnoreCase(p.dstarModule())) current=i; }
        showSearchablePicker("选择 D-Star 模块", labels, current, which -> {
            p.set("dstarModule",String.valueOf(mods.charAt(which))); disconnectDstarDirect(); updateDstarSelectors(); updateModeLabel();
        });
    }
    private void connectDstar() {
        if (dstarActive()) { disconnectDstarDirect(); return; }
        joinDstar();
    }
    private void joinDstar() {
        if (p.dstarCallsign().isEmpty()) { toast("请先在设置里保存 D-Star 呼号"); return; }
        dstarUserWanted = true;
        if (dstarReconnectTask != null) { ui.removeCallbacks(dstarReconnectTask); dstarReconnectTask = null; }
        updateDstarSelectors();
        final String sid = dstarServerInfo(p.dstarServer())[0];
        final String host = dstarHost(sid);
        final String mod = p.dstarModule();
        final String call = p.dstarCallsign();
        // D-Star mode uses the direct DCS/XLX transport (UDP 30051).
        // XLXHLJ also exposes a DMR/Homebrew fusion endpoint, but that is a
        // different transport and must not be used for D-Star-to-D-Star calls.
        if (dstarDirect == null) dstarDirect = new DStarClient(this);
        dstarDirect.setUserText(p.dstarUserText());
        dstarConnecting = true; paintDstarConnectIcon();
        new Thread(() -> dstarDirect.connect(host, 30051, sid, call, mod), "dstar-connect").start();
    }
    private void disconnectDstarDirect() {
        dstarUserWanted = false; dstarReconnectAttempts = 0;
        if (dstarReconnectTask != null) { ui.removeCallbacks(dstarReconnectTask); dstarReconnectTask = null; }
        if (dstarDirect != null) dstarDirect.stop();
        if (audio != null) audio.stopDstar();
        dstarDirectActive = false; dstarConnecting = false;
        paintDstarConnectIcon();
        if (dstarCallState != null) { dstarCallState.setText("未连接"); dstarCallState.setTextColor(SUB); }
        if (dstarCallLine != null) { dstarCallLine.setText("未登录"); dstarCallLine.setTextColor(TXT); }
        updateModeLabel();
    }
    private void paintDstarConnectIcon() {
        if (dstarConnectBtn == null) return;
        boolean on = dstarDirectActive && dstarDirect != null && dstarDirect.isRunning();
        dstarConnectBtn.setText(on ? "断开" : "连接");
        dstarConnectBtn.setTextColor(Color.WHITE);
        dstarConnectBtn.setBackground(roundBg(on ? GREEN : Color.BLACK, 999));
        dstarConnectBtn.setElevation(dp(on ? 4 : 0));
    }
    private void startDstarPtt() {
        if (!dstarActive()) { toast("请先连接 D-Star 反射器"); return; }
        if (dstarDirect == null || !dstarDirect.isLinkHealthy()) {
            if (dstarDirect != null) dstarDirect.stop();
            dstarDirectActive = false;
            toast("D-Star 链路已失效，正在重新连接，请稍后再按 PTT");
            joinDstar();
            return;
        }
        if (txing) return;
        txing = true; activePttTarget = "dstar"; txCallStart = System.currentTimeMillis(); dstarTxStart = txCallStart;
        dstarDirect.startTx();
        // D-Star is half-duplex: mute local playback while the mic is open.
        if (audio != null) audio.muteSpk(true);
        scheduleTxTimeout();
        if (dstarCallState != null) { dstarCallState.setText("发射中"); dstarCallState.setTextColor(BLUE); }
        if (dstarCallLine != null) { dstarCallLine.setText(p.dstarCallsign()); dstarCallLine.setTextColor(TXT); }
        
        ui.post(this::paintNav);
        final boolean pdcDstar = HyteraDeviceProfile.FAMILY_PDC.equals(HyteraDeviceProfile.detect().family);
        if (pdcDstar) HyteraAudioRoute.setTransmitting(this, true);
        AudioEngine.MicCb dstarMic = frame -> { if (txing && dstarDirect != null) dstarDirect.sendPcm(frame); };
        boolean dstarMicStarted = audio != null && (pdcDstar
                ? audio.startDstarPdcMic(dstarMic) : audio.startDstarMic(dstarMic));
        if (!dstarMicStarted) micStartFailed();
        if (audio != null) audio.muteSpk(true);
    }
    private void stopDstarPtt() {
        if (HyteraDeviceProfile.FAMILY_PDC.equals(HyteraDeviceProfile.detect().family)) HyteraAudioRoute.setTransmitting(this, false);
        try { if (audio != null) audio.startMic(null); } catch (Throwable ignored) {}
        try { if (audio != null) audio.muteSpk(false); } catch (Throwable ignored) {}
        if (dstarDirect != null) dstarDirect.stopTx();
        int sec = dstarTxStart == 0 ? 0 : (int)((System.currentTimeMillis() - dstarTxStart) / 1000);
        if (sec == 0 && txing) sec = 1;
        if (txing) addDstarLog("发射", p.dstarCallsign(), sec);
        dstarTxStart = 0;
        txing = false; activePttTarget = null; txCallStart = 0;
        pttToneStop(); cancelTxTimeout();
        if (dstarCallState != null) { dstarCallState.setText("空闲"); dstarCallState.setTextColor(SUB); }
        if (dstarCallLine != null) { dstarCallLine.setText("等待呼叫"); dstarCallLine.setTextColor(TXT); }
        
        ui.post(this::paintNav);
    }
    private void scheduleDstarRxEnd() {
        if (dstarRxEndTask != null) ui.removeCallbacks(dstarRxEndTask);
        dstarRxEndTask = () -> {
            dstarRxEndTask = null;
            long idle = System.currentTimeMillis() - dstarRxLastAt;
            if (idle < 1200L) { ui.postDelayed(this::scheduleDstarRxEnd, 1200L - idle); return; }
            int sec = dstarRxStart == 0 ? 0 : (int)((System.currentTimeMillis() - dstarRxStart) / 1000);
            if (sec == 0 && dstarRxStart != 0) sec = 1;
            if (dstarRxStart != 0) addDstarLog("接收", dstarRxPeer, sec);
            dstarRxStart = 0; dstarRxPeer = ""; dstarLastPeer = "";
        };
        ui.postDelayed(dstarRxEndTask, 1200L);
    }
    private void addDstarLog(String direction, String peer, int sec) {
        final String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        ui.post(() -> addDstarLogView(time, direction, peer, sec));
    }
    private void addDstarLogView(String time, String direction, String peer, int sec) {
        if (dstarLogBox == null) return;
        if (dstarLogBox.getChildCount() == 1 && dstarLogBox.getChildAt(0) instanceof TextView
                && "暂无 D-Star 通话记录".contentEquals(((TextView)dstarLogBox.getChildAt(0)).getText())) {
            dstarLogBox.removeAllViews();
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        r.addView(tableCell(time, 0.85f, SUB, false));
        r.addView(tableCell(peer == null ? "" : peer, 1.15f, TXT, true));
        r.addView(tableCell(direction, 0.72f, BLUE, false));
        r.addView(tableCell(sec > 0 ? sec + "s" : "--", 0.55f, SUB, false));
        dstarLogBox.addView(r, 0);
        while (dstarLogBox.getChildCount() > visibleRows) dstarLogBox.removeViewAt(dstarLogBox.getChildCount() - 1);
    }
    private void addYsfLog(String direction, String peer, int sec) {
        final String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        ui.post(() -> addYsfLogView(time, direction, peer, sec));
    }
    private void addYsfLogView(String time, String direction, String peer, int sec) {
        if (ysfLogBox == null) return;
        if (ysfLogBox.getChildCount() == 1 && ysfLogBox.getChildAt(0) instanceof TextView
                && "暂无 YSF 通话记录".contentEquals(((TextView)ysfLogBox.getChildAt(0)).getText())) {
            ysfLogBox.removeAllViews();
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        r.addView(tableCell(time, 0.85f, SUB, false));
        r.addView(tableCell(peer == null ? "" : peer, 1.15f, TXT, true));
        r.addView(tableCell(direction, 0.72f, BLUE, false));
        r.addView(tableCell(sec > 0 ? sec + "s" : "--", 0.55f, SUB, false));
        ysfLogBox.addView(r, 0);
        while (ysfLogBox.getChildCount() > visibleRows) ysfLogBox.removeViewAt(ysfLogBox.getChildCount() - 1);
    }
    private int ysfServerIndex(){return YsfServers.indexOf(p.get("ysfServer","CN-46002"));}
    private String ysfServerName(){return YsfServers.name(ysfServerIndex());}
    private void updateYsfSelectors(){if(ysfServerBtn!=null)ysfServerBtn.setText(ysfServerName());if(ysfServerLine!=null)ysfServerLine.setText("YSF 反射器: "+ysfServerName()+" · DG-ID "+p.ysfDgId());}
    private void showYsfServerPicker(){
        if(YsfServers.count()<=0){toast("YSF 服务器列表为空，请先更新服务器列表");return;}
        String[] labels=new String[YsfServers.count()];
        for(int i=0;i<labels.length;i++)labels[i]=YsfServers.name(i);
        showSearchablePicker("选择 YSF 反射器", labels, ysfServerIndex(), which -> { p.set("ysfServer",YsfServers.id(which)); disconnectYsf(); updateYsfSelectors(); updateModeLabel(); });
    }
    private void ensureYsfClient(){if(ysfDirect!=null)return;ysfDirect=new YsfClient();ysfDirect.setListener(new YsfClient.Listener(){@Override public void onState(int st,String msg){ysfActive=st==1;ysfConnecting=st==0;safeUi(()->{paintYsfConnectIcon();if(ysfCallState!=null){if(st==1){ysfCallState.setText("空闲");ysfCallState.setTextColor(SUB);if(ysfCallLine!=null){ysfCallLine.setText("等待呼叫");ysfCallLine.setTextColor(TXT);}}else if(st==2||st==3){ysfCallState.setText("未连接");ysfCallState.setTextColor(SUB);}else{ysfCallState.setText("连接中");ysfCallState.setTextColor(BLUE);}}updateModeLabel();});}@Override public void onVoice(String call,short[] pcm){if(!ysfActive||pcm==null||pcm.length==0)return;markModeSignal(5);if(!listenMode(5))return;if(audio!=null&&!rxPlaybackBlocked())audio.playRaw(pcm);if(call==null||call.trim().isEmpty())return;final String who=call.trim();ysfLastPeer=who;if(ysfRxStart==0)ysfRxStart=System.currentTimeMillis();long now=System.currentTimeMillis();boolean changed=!who.equals(ysfUiLastCall);if(!changed&&now-ysfUiLastAt<500L)return;ysfUiLastCall=who;ysfUiLastAt=now;safeUi(()->{setLastCall(5,who);if(ysfCallState!=null){ysfCallState.setText("接收中");ysfCallState.setTextColor(BLUE);}if(ysfCallLine!=null){ysfCallLine.setText(who);ysfCallLine.setTextColor(RED);}});}@Override public void onEnd(String call,int sec){String peer=(call==null||call.trim().isEmpty())?ysfLastPeer:call.trim();addYsfLog("接收",peer,sec);ysfLastPeer="";ysfRxStart=0;ysfUiLastCall="";ysfUiLastAt=0L;safeUi(()->{if(ysfCallState!=null){ysfCallState.setText("空闲");ysfCallState.setTextColor(SUB);}if(ysfCallLine!=null){ysfCallLine.setText("等待呼叫");ysfCallLine.setTextColor(TXT);}});}@Override public void onLog(String msg){log(msg);}});}
    private void connectYsf() {
        if (ysfActive()) { disconnectYsf(); return; }
        ensureYsfClient();
        int i = ysfServerIndex();
        if (i < 0) { i = 0; if (YsfServers.count() > 0) p.set("ysfServer", YsfServers.id(0)); }
        String call = p.ysfCallsign();
        if (call.isEmpty()) { toast("请先在设置里保存 YSF 呼号"); return; }
        if (YsfServers.count() <= 0) { toast("YSF 服务器列表为空，请先更新服务器列表"); return; }
        ysfConnecting = true;
        paintYsfConnectIcon();
        final int serverIndex = i;
        log("YSF 连接: " + YsfServers.name(serverIndex) + " 呼号=" + call + " DG-ID=" + p.ysfDgId());
        new Thread(() -> {
            try {
                ysfDirect.connect(YsfServers.host(serverIndex), YsfServers.port(serverIndex), call, p.ysfDgId(), p.ysfOptions());
            } catch (Throwable t) {
                ysfConnecting = false;
                log("YSF 连接失败: " + t);
                toast("YSF 连接失败: " + t);
            }
        }, "ysf-connect").start();
    }

    private void disconnectYsf(){ysfConnecting=false;ysfActive=false;if(ysfDirect!=null)ysfDirect.stop();paintYsfConnectIcon();if(ysfCallState!=null){ysfCallState.setText("未连接");ysfCallState.setTextColor(SUB);}if(ysfCallLine!=null){ysfCallLine.setText("未登录");ysfCallLine.setTextColor(TXT);}updateModeLabel();}
    private boolean ysfActive(){return ysfActive&&ysfDirect!=null&&ysfDirect.isRunning();}
    private void paintYsfConnectIcon(){if(ysfConnectBtn==null)return;boolean on=ysfActive();ysfConnectBtn.setText(on?"断开":"连接");ysfConnectBtn.setTextColor(Color.WHITE);ysfConnectBtn.setBackground(roundBg(on?GREEN:Color.BLACK,999));}
    private void autoConnectDmrIfNeeded() {
        if (autoConnectSuppressed()) return;
        if (p == null || !p.showDigital() || !p.autoDigital() || !p.dmrAuto()) return;
        if (dmrUp() || (hbp != null && hbp.isRunning())) { refreshDmrConnectionUi(); return; }
        if (p.dmrId() <= 0 || p.callsign().isEmpty() || p.password().length() < 6) return;
        log("数字中继启动自动连接检查通过");
        connect();
    }

    /** 启动后做几轮巡检，避免账号校验/网络握手期间首次自动登录被漏掉。 */
    private void startupAutoLoginSweep() {
        if (autoConnectSuppressed()) return;
        startupAutoLoginSweepCount++;
        try {
            autoConnectAll();
            autoConnectDmrIfNeeded();
        } catch (Throwable t) {
            log("启动自动登录巡检失败: " + t);
        }
        if (startupAutoLoginSweepCount < 3) {
            ui.postDelayed(this::startupAutoLoginSweep, 3000L);
        }
    }

    private void autoConnectYsfIfNeeded() {
        if (autoConnectSuppressed()) return;
        if (p == null || !p.showYsf() || !p.autoYsf()) return;
        if (ysfActive() || ysfConnecting) return;
        if (p.ysfCallsign().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - ysfAutoAttemptAt < 5000L) return;
        ysfAutoAttemptAt = now;
        log("YSF 启动自动登录检查通过，准备连接 " + ysfServerName());
        connectYsf();
    }

    private void startYsfPtt(){if(!ysfActive()){toast("请先连接 YSF 反射器");return;}if(txing)return;txing=true;activePttTarget="ysf";txCallStart=System.currentTimeMillis();ysfDirect.startTx();pttToneStart();scheduleTxTimeout();if(ysfCallState!=null){ysfCallState.setText("发射中");ysfCallState.setTextColor(BLUE);}if(ysfCallLine!=null){ysfCallLine.setText(p.ysfCallsign());ysfCallLine.setTextColor(TXT);}ui.post(this::paintNav);if(!audio.startYsfMic(frame->{if(txing&&ysfDirect!=null)ysfDirect.sendPcm(frame);})) micStartFailed();}
    private void stopYsfPtt(){try{if(audio!=null)audio.startMic(null);}catch(Throwable ignored){}if(ysfDirect!=null)ysfDirect.stopTx();int sec=txCallStart==0?0:(int)((System.currentTimeMillis()-txCallStart)/1000);if(sec==0&&txing)sec=1;if(txing)addYsfLog("发射",p.ysfCallsign(),sec);txing=false;activePttTarget=null;txCallStart=0;pttToneStop();cancelTxTimeout();if(ysfCallState!=null){ysfCallState.setText("空闲");ysfCallState.setTextColor(SUB);}if(ysfCallLine!=null){ysfCallLine.setText("等待呼叫");ysfCallLine.setTextColor(TXT);}ui.post(this::paintNav);}
    private View buildYsfPage(){
        LinearLayout l=wrap();
        ysfCallState=tv("未连接",12,SUB);
        ysfCallLine=tv("未登录",24,TXT); ysfCallLine.setTypeface(null,Typeface.BOLD);
        ysfLastLine=tv("上次呼叫：--",14,SUB);
        ysfServerLine=tv("YSF 反射器: "+ysfServerName(),13,BLUE);
        muteYsfBtn=muteButton("ysf");
        l.addView(statusCard(muteYsfBtn,ysfCallState,ysfCallLine,ysfLastLine,ysfServerLine));
        LinearLayout c=card();
        LinearLayout h=new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t=sectionTitle("YSF 连接"); t.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); h.addView(t);
        ysfConnectBtn=tv("连接",14,Color.WHITE); ysfConnectBtn.setGravity(Gravity.CENTER); ysfConnectBtn.setTypeface(null,Typeface.BOLD); ysfConnectBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(34))); ysfConnectBtn.setOnClickListener(v->connectYsf()); h.addView(ysfConnectBtn); c.addView(h);
        ysfServerBtn=btn(ysfServerName()); ysfServerBtn.setOnClickListener(v->showYsfServerPicker()); c.addView(row("服务器",ysfServerBtn));
        l.addView(c);
        LinearLayout hist=card(); hist.addView(sectionTitle("通话记录"));
        LinearLayout dh=new LinearLayout(this); dh.setOrientation(LinearLayout.HORIZONTAL);
        dh.addView(tableCell("时间",0.85f,BLUE,true)); dh.addView(tableCell("呼号",1.15f,BLUE,true)); dh.addView(tableCell("方向",0.72f,BLUE,true)); dh.addView(tableCell("通话时长",0.55f,BLUE,true)); hist.addView(dh);
        ysfLogBox=new LinearLayout(this); ysfLogBox.setOrientation(LinearLayout.VERTICAL); ysfLogBox.setMinimumHeight(dp(60)); ysfLogBox.addView(tv("暂无 YSF 通话记录",12,SUB)); hist.addView(ysfLogBox); l.addView(hist);
        updateYsfSelectors(); paintYsfConnectIcon(); return pageWithPttScroll(l);
    }



    private View buildDstarPage() {
        LinearLayout l=wrap(); dstarCallState=tv("未连接",12,SUB); dstarCallLine=tv("未登录",24,TXT); dstarCallLine.setTypeface(null,Typeface.BOLD);
        dstarLastLine=tv("上次呼叫：--",14,SUB); dstarServerLine=tv("DCS 服务器: "+dstarServerName(p.dstarServer())+" · 模块: "+p.dstarModule(),13,BLUE);
        muteDstarBtn=muteButton("dstar"); l.addView(statusCard(muteDstarBtn,dstarCallState,dstarCallLine,dstarLastLine,dstarServerLine));
        LinearLayout c=card();
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=sectionTitle("D-Star 连接"); title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); head.addView(title);
        dstarConnectBtn=tv("连接",14,Color.WHITE); dstarConnectBtn.setGravity(Gravity.CENTER); dstarConnectBtn.setTypeface(null,Typeface.BOLD); dstarConnectBtn.setTextSize(14); dstarConnectBtn.setIncludeFontPadding(false);
        LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(dp(64),dp(34)); dstarConnectBtn.setLayoutParams(ilp); dstarConnectBtn.setOnClickListener(v->connectDstar()); head.addView(dstarConnectBtn); c.addView(head);
        dstarServerBtn=btn(dstarServerName(p.dstarServer())); dstarServerBtn.setOnClickListener(v->showDstarServerPicker()); c.addView(row("服务器",dstarServerBtn));
        dstarModuleBtn=btn("模块 "+p.dstarModule()); dstarModuleBtn.setOnClickListener(v->showDstarModulePicker()); c.addView(row("模块",dstarModuleBtn));
        l.addView(c);
        LinearLayout h=card(); h.addView(sectionTitle("通话记录")); LinearLayout dh=new LinearLayout(this); dh.setOrientation(LinearLayout.HORIZONTAL); dh.addView(tableCell("时间",0.85f,BLUE,true)); dh.addView(tableCell("呼号",1.15f,BLUE,true)); dh.addView(tableCell("方向",0.72f,BLUE,true)); dh.addView(tableCell("通话时长",0.55f,BLUE,true)); h.addView(dh); dstarLogBox=new LinearLayout(this); dstarLogBox.setOrientation(LinearLayout.VERTICAL); dstarLogBox.setMinimumHeight(dp(60)); dstarLogBox.addView(tv("暂无 D-Star 通话记录",12,SUB)); h.addView(dstarLogBox); l.addView(h);
        updateDstarSelectors(); paintDstarConnectIcon(); return pageWithPttScroll(l);
    }

    private View buildNetworkPage() {
        LinearLayout l = wrap();
        hqCallState = tv("未连接", 12, SUB);
        hqCallLine = tv("未登录", 24, TXT); hqCallLine.setTypeface(null, Typeface.BOLD);
        hqGroupLine = tv("当前组: " + hqName(), 15, TXT);
        hqNetLine = null;   // 省一行空间，让在线用户列表完整可见
        muteNetworkBtn = muteButton("network");
        netLastLine = tv("上次呼叫：--", 14, SUB);
        LinearLayout callBar = statusCard(muteNetworkBtn, hqCallState, hqCallLine, netLastLine, hqGroupLine);
        l.addView(callBar);
        LinearLayout c1 = card();
        LinearLayout netHead = new LinearLayout(this);
        netHead.setOrientation(LinearLayout.HORIZONTAL);
        netHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView netTitle = sectionTitle("网络频道");
        netTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        netHead.addView(netTitle);
        c1.addView(netHead);
        roomBox = new LinearLayout(this);
        roomBox.setOrientation(LinearLayout.VERTICAL);
        roomBox.setPadding(0, dp(6), 0, 0);
        c1.addView(roomBox);
        hqStatus = tv("状态: 未连接", 12, SUB);
        hqStatus.setPadding(dp(8), dp(6), dp(8), dp(6));
        hqStatus.setBackground(roundBg(DARK ? 0x18FFFFFF : 0x0D0F172A, 12));
        hqStatus.setOnClickListener(v -> toggleRoomStatus());
        c1.addView(hqStatus);
        l.addView(c1);


        LinearLayout c2 = card();
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        final Button tabOnline = new Button(this);
        final Button tabHistory = new Button(this);
        for (Button b : new Button[]{tabOnline, tabHistory}) {
            b.setAllCaps(false); ts(b, 13); b.setIncludeFontPadding(false); b.setMinHeight(dp(34));
            b.setPadding(0, dp(7), 0, dp(7));
            b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tabs.addView(b);
        }
        tabOnline.setText("在线用户"); tabHistory.setText("通话记录");
        appOnlineBox = new LinearLayout(this);
        appOnlineBox.setOrientation(LinearLayout.VERTICAL);
        appOnlineBox.setMinimumHeight(dp(60));
        // Net页不显示空用户占位提示
        netLogBox = new LinearLayout(this);
        netLogBox.setOrientation(LinearLayout.VERTICAL);
        netLogBox.addView(tv("暂无网络通话记录", 12, SUB));
        netLogBox.setVisibility(View.GONE);
        appOnlineBox.setVisibility(View.VISIBLE);
        final Runnable paintTabs = () -> {
            boolean online = appOnlineBox.getVisibility() == View.VISIBLE;
            tabOnline.setTextColor(online ? BLUE : SUB); tabHistory.setTextColor(online ? SUB : BLUE);
            tabOnline.setBackground(roundBg(online ? (DARK ? 0x303B82F6 : 0x1F2563EB) : 0x00000000, 12));
            tabHistory.setBackground(roundBg(online ? 0x00000000 : (DARK ? 0x303B82F6 : 0x1F2563EB), 12));
        };
        FrameLayout panel = new FrameLayout(this);
        panel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(appOnlineBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(netLogBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabOnline.setOnClickListener(v -> { appOnlineBox.setVisibility(View.VISIBLE); netLogBox.setVisibility(View.GONE); paintNav(); paintTabs.run(); });
        tabHistory.setOnClickListener(v -> { appOnlineBox.setVisibility(View.GONE); netLogBox.setVisibility(View.VISIBLE); paintTabs.run(); });
        c2.addView(tabs); c2.addView(panel);
        c2.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(c2);
        paintTabs.run();
        buildRoomList();
        return pageWithPttScroll(l, true);
    }


    private void buildRoomList() {
        if (roomBox == null || page != PAGE_NETWORK || !"network".equals(activeVoiceKind)) return;
        try {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastRoomListAt < 250L) { roomListBurst++; if (roomListBurst > 4) return; }
            else roomListBurst = 0;
            lastRoomListAt = nowMs;
            StringBuilder sig = new StringBuilder();
            sig.append(hqActive).append('|').append(networkConnecting).append('|').append(p.netRoom())
               .append('|').append(netLiveMembers).append('|');
            for (String[] rr : NET_ROOMS) if (rr != null && rr.length >= 2)
                sig.append(rr[0]).append(':').append(rr.length > 2 ? rr[2] : "0").append(';');
            String sigS = sig.toString();
            if (sigS.equals(lastRoomListSig)) return;   // 内容没变就不重建，避免主线程空转
            lastRoomListSig = sigS;
        } catch (Throwable ignored) {}
        roomBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < NET_ROOMS.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(3), 0, dp(3));
                roomBox.addView(row);
            }
            String[] r = NET_ROOMS.get(i);
            final String id = r[0];
            boolean on = "network".equals(activeVoiceKind) && (hqActive || networkConnecting) && id.equals(p.netRoom());
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(14), dp(12), dp(14), dp(12));
            tile.setBackground(tgButtonBg(on));
            int online = 0;
            try { if (r.length > 2) online = Integer.parseInt(r[2]); } catch (Exception ignored) {}
            if (on) {
                // 已连接房间用实时成员列表（更准更及时），其余房间用服务器人数
                online = netLiveMembers >= 0 ? Math.max(0, netLiveMembers) : Math.max(0, online);
            }
            TextView name = tv(r[1], 14, on ? 0xFFFFFFFF : BLUE);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.addView(name);
            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            String tileState = on ? (networkConnecting ? "连接中…" : "✓ 已连接") : "点击连接";
            TextView state = tv(tileState, 10, on ? 0xDDFFFFFF : SUB);
            state.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView count = tv(String.valueOf(Math.max(0, online)), 12, on ? 0xFFFFFFFF : BLUE);
            count.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            footer.addView(state); footer.addView(count);
            tile.addView(footer);
            tile.setOnClickListener(v -> toggleRoom(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(4), dp(2), dp(4), dp(2));
            tile.setLayoutParams(lp);
            row.addView(tile);
        }
        if (row != null && row.getChildCount() == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void toggleRoom(String id) {
        if (accountCheckRunning) { toast("正在校验账号，请稍候再点"); return; }
        if (id == null || id.isEmpty()) return;
        // 只拦截“同一个目标正在连接”的重复点击；显式切换其他频道永远立即响应。
        if ((networkConnecting || networkSwitching) && id.equals(p.netRoom())) {
            toast("正在连接该频道，请稍候");
            return;
        }
        if (hqActive && !networkSwitching && "network".equals(activeVoiceKind) && id.equals(p.netRoom())) {
            // 卡片只负责连接/切换；断开请点状态栏，避免用户反复点卡片把连接点掉
            toast("已在该频道内；点上方状态栏可断开");
            return;
        }
        beginRoomSwitch(id);
    }

    private void toggleRoomStatus() {
        try {
            if (duplexBusy()) { endDuplexCall(); return; }
            if (hqActive && "network".equals(activeVoiceKind) && effectiveNetRoom().equals(p.netRoom())) { leaveRoom(); return; }
            toggleRoom(effectiveNetRoom());
        } catch (Throwable t) { log("切换频道状态异常: " + t); }
    }

    private void beginRoomSwitch(String id) {
        if (id == null || id.isEmpty()) return;
        // 先声明目标与“正在切换”，但不再人为延迟 250ms，也不做 4 秒限速。
        // 旧 WebSocket 由 connectWithTokenServer() 内部一次性关闭，避免双重 stop 回调。
        networkWanted = true;
        networkConnecting = false;
        networkSwitching = true;
        pendingNetworkRoom = id;
        if (networkReconnectTask != null) {
            ui.removeCallbacks(networkReconnectTask);
            networkReconnectTask = null;
        }
        if (networkSwitchTask != null) {
            ui.removeCallbacks(networkSwitchTask);
            networkSwitchTask = null;
        }
        if (hqTxRequested || txing || hqMicStarting) stopNetworkPttRequest();
        cancelDuplexForNavigation();
        hqActive = false;
        hqPlaybackReady = false;
        hqRxStart = 0; hqRxPeer = ""; hqTxFrames = 0;
        hqCallHoldUntil = 0; hqLastPeer = "";
        hqStopAsync();
        p.set("netRoom", id);
        p.set("modeNetwork", "1");
        if (hqStatus != null) setHqStatus("状态: 正在切换频道… " + roomName(id));
        if (hqCallState != null) { hqCallState.setText("切换中"); hqCallState.setTextColor(BLUE); }
        if (hqCallLine != null) { hqCallLine.setText("正在连接 " + roomName(id)); hqCallLine.setTextColor(TXT); }
        renderVoiceOnline(java.util.Collections.emptyList());
        buildRoomList(); updateModeLabel(); refreshNetworkCallBar(); pushPresence();
        joinRoom();
    }

    private void joinRoom() {
        if (!accountValidated) {
            if (accountCheckRunning) {
                networkConnecting = true;
                if (hqStatus != null) setHqStatus("状态: 正在校验账号… " + hqName());
                return;
            }
            networkConnecting = true;
            hqActive = true;
            if (hqStatus != null) setHqStatus("状态: 正在校验账号… " + hqName());
            refreshNetworkCallBar(); buildRoomList(); updateModeLabel();
            ensureAccountThen("network", () -> { networkConnecting = false; joinRoom(); });
            return;
        }
        if (networkConnecting) return;
        if (p.callsign().isEmpty() || p.password().length() < 6) { toast("请先在「身份设置与注册」里保存呼号和协会密码"); return; }
        String rid = effectiveNetRoom();
        p.set("netRoom", rid);
        activeVoiceKind = "network";
        networkWanted = true;
        networkConnecting = true;
        networkSwitching = false;
        pendingNetworkRoom = null;
        netOnlineAutoScrolled = false;
        if (networkReconnectTask != null) { ui.removeCallbacks(networkReconnectTask); networkReconnectTask = null; }
        hqActive = true;
        hqPlaybackReady = false;
        // First inbound voice starts a clean AudioTrack; idle rooms keep the audio HAL closed.
        if (hqStatus != null) setHqStatus("状态: 正在连接 · " + hqName());
        refreshNetworkCallBar();
        String wsUrl = "wss://hljrsa.cn:50113/voice";
        bmNet.connectWithTokenServer(wsUrl, rid,
                p.callsign(), p.callsign(), p.dmrId(), p.essid(), p.password());
        p.set("modeNetwork", "1");
        buildRoomList(); updateModeLabel(); pushPresence();
        addNetLog("连接", p.callsign(), hqName(), 0);
    }

    private String roomName(String id) {
        for (String[] r : NET_ROOMS) if (r != null && r.length > 1 && id.equals(r[0])) return r[1];
        return id;
    }

    private void leaveRoom() {
        networkWanted = false;
        networkConnecting = false;
        networkSwitching = false;
        netBitrateBps = 0;
        // 断开后短时间内忽略同一房间的重复点击，避免连点时反复连断
        networkUiCooldownUntil = System.currentTimeMillis() + 2500L;
        pendingNetworkRoom = null;
        if (networkSwitchTask != null) { ui.removeCallbacks(networkSwitchTask); networkSwitchTask = null; }
        if (networkReconnectTask != null) { ui.removeCallbacks(networkReconnectTask); networkReconnectTask = null; }
        if (hqTxRequested || txing || hqMicStarting) stopNetworkPttRequest();
        cancelDuplexForNavigation();
        hqActive = false;
        hqPlaybackReady = false;
        hqRxStart = 0; hqRxPeer = ""; hqTxFrames = 0;
        hqCallHoldUntil = 0; hqLastPeer = "";
        stopDuplexRingtone();
        if (bmNet != null) bmNet.stop();
        hqStopAsync();
        if (needsLegacyAudio()) startAudioAsync();
        activeVoiceKind = "network";
        p.set("modeNetwork", "0");
        if (hqStatus != null) setHqStatus("状态: 未连接");
        renderVoiceOnline(java.util.Collections.emptyList());
        buildRoomList(); updateModeLabel(); refreshNetworkCallBar(); pushPresence();
        addNetLog("断开", p.callsign(), hqName(), 0);
    }

 /** Keep the current network state text and append the live Opus bitrate. */
    private void setHqStatus(String text) {
        hqStatusBase = text == null ? "" : text;
        String out = hqStatusBase;
        if (hqActive && netBitrateBps > 0 && !out.contains("kbps")) out += " · " + (netBitrateBps / 1000) + " kbps";
        if (hqStatus != null) hqStatus.setText(out);
        if (dstarStatus != null) dstarStatus.setText("dstar".equals(activeVoiceKind) ? out : "状态: 未连接");
        paintDstarConnectIcon();
    }

    private void applyVoiceCallBar(TextView stateView, TextView callView,
                                   String stateText, int stateColor, String callText, int callColor) {
        if (stateView != null) { stateView.setText(stateText); stateView.setTextColor(stateColor); }
        if (callView != null) { callView.setText(callText); callView.setTextColor(callColor); }
    }

    private void refreshNetworkCallBar() {
        if (hqGroupLine != null) hqGroupLine.setText("当前组: " + hqName());
        if (hqNetLine != null) hqNetLine.setText("当前网络: 协会服务器");
        if (dstarServerLine != null) dstarServerLine.setText("DCS 服务器: " + dstarServerName(p.dstarServer()) + " · 模块: " + p.dstarModule());
        String stateText, callText; int stateColor = SUB, callColor = TXT;
        if (pendingNetworkRoom != null && "network".equals(activeVoiceKind)) { stateText="切换中"; stateColor=BLUE; callText="等待新频道"; }
        else if (duplexActive) { stateText="双工通话"; stateColor=GREEN; callText=duplexPeerName; }
        else if (duplexIncoming) { stateText="来电"; stateColor=RED; callText=duplexPeerName; callColor=RED; }
        else if (duplexOutgoing) { stateText="呼叫中"; stateColor=BLUE; callText=duplexPeerName; }
        else if (hqTxRequested || txing) { stateText="发射中"; stateColor=BLUE; callText=p.callsign(); }
        else if (hqRxStart > 0 || hqRemoteActive) { stateText="接收中"; stateColor=BLUE; callText=hqRxPeer.length()>0?hqRxPeer:(hqLastPeer.length()>0?hqLastPeer:"正在接收"); callColor=RED; }
        else if (hqActive && hqCallHoldUntil > System.currentTimeMillis() && hqLastPeer.length()>0) { stateText="空闲"; callText=hqLastPeer; }
        else if (hqActive) { stateText="空闲"; callText="等待呼叫"; }
        else { stateText="未连接"; callText="未登录"; }
        applyVoiceCallBar(hqCallState, hqCallLine, stateText, stateColor, callText, callColor);
        if (!dstarDirectActive) {
            applyVoiceCallBar(dstarCallState, dstarCallLine, "未连接", SUB, "未登录", TXT);
        }
        if (duplexActive && hqGroupLine != null) hqGroupLine.setText("正在与 " + duplexPeerName + " 双工通话");
        if (duplexIncoming && hqGroupLine != null) hqGroupLine.setText("呼叫人正在发起双工通话");
        if (duplexOutgoing && hqGroupLine != null) hqGroupLine.setText("正在发起双工通话");
        if (page == PAGE_NETWORK && appOnlineBox != null) {
            ui.post(() -> {
                try {
                    renderVoiceOnline(lastVoiceMembers);
                } catch (Throwable ignored) {}
            });
        }
    }

    private String hqName() {
        if ("dstar".equals(activeVoiceKind)) return dstarDisplayName();
        for (String[] r : NET_ROOMS) if (r[0].equals(p.netRoom())) return r[1];
        return "网络频道";
    }

    private String effectiveNetRoom() {
        String want = p.netRoom();
        for (String[] r : NET_ROOMS) if (r[0].equals(want)) return want;
        return NET_ROOMS.isEmpty() ? "hrb48k" : NET_ROOMS.get(0)[0];
    }


    private void addBmLog(String kind, String who, int tg, int sec) {
        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
        bmHistory.add(0, time + "\u001F" + kind + " " + who + "\u001F" + tg + "\u001F" + sec);
        while (bmHistory.size() > visibleRows) bmHistory.remove(bmHistory.size() - 1);
        renderBmLogs();
    }

    private void renderBmLogs() {
        if (bmLogBox == null) return;
        bmLogBox.removeAllViews();
        if (bmHistory.isEmpty()) {
            bmLogBox.addView(tv("暂无 DMR 通话记录", 12, SUB));
            return;
        }
        for (int i = 0; i < bmHistory.size(); i++) {
            String[] parts = bmHistory.get(i).split("\u001F", -1);
            if (parts.length < 4) continue;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(7), 0, dp(7));
            r.addView(tableCell(parts[0], 0.72f, SUB, false));
            r.addView(tableCell(parts[1], 1.15f, TXT, false));
            r.addView(tableCell("TG" + parts[2], 0.72f, BLUE, false));
            r.addView(tableCell(parts[3] + "s", 0.55f, SUB, false));
            bmLogBox.addView(r);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Throwable t) { toast("无法打开链接：" + url); }
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

 /** 把当前模式/房间上报给服务器（93 的 APP 用户页要用） */
    private void pushPresence() {
        if (fmo == null) return;
        try {
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            j.addProperty("digitalOn", p.modeDigital());
            j.addProperty("analogOn", p.modeAnalog());
            j.addProperty("networkOn", bmUp());
            j.addProperty("digitalTg", p.tg());
            j.addProperty("digitalRoom", tgName());
            j.addProperty("networkRoomId", p.bmServerKey());
            j.addProperty("networkRoom", p.bmServerName());
            j.addProperty("dstarOn", "dstar".equals(activeVoiceKind));
            j.addProperty("dstarServer", p.dstarServer());
            j.addProperty("dstarModule", p.dstarModule());
            j.addProperty("bmTg", p.bmTg());
            j.addProperty("essid", p.essid());
            j.addProperty("ver", BuildConfig.VERSION_NAME);
            fmo.presenceExtra = j.toString();
        } catch (Throwable ignored) {}
    }

    private int defaultPage() {
        if (pageEnabled(PAGE_NETWORK)) return PAGE_NETWORK;
        if (!BuildConfig.PUBLIC_EDITION) {
            if (pageEnabled(PAGE_ANALOG)) return PAGE_ANALOG;
            if (pageEnabled(PAGE_DIGITAL)) return PAGE_DIGITAL;
        }
        if (pageEnabled(PAGE_BM)) return PAGE_BM;
        if (pageEnabled(PAGE_P25)) return PAGE_P25;
        if (pageEnabled(PAGE_YSF)) return PAGE_YSF;
        if (pageEnabled(PAGE_DSTAR)) return PAGE_DSTAR;
        if (pageEnabled(PAGE_NXDN)) return PAGE_NXDN;
        if (pageEnabled(PAGE_NRL)) return PAGE_NRL;
        return PAGE_SETTINGS;
    }

    private boolean pageEnabled(int i) {
        if (BuildConfig.PUBLIC_EDITION && (i == PAGE_ANALOG || i == PAGE_DIGITAL)) return false;
        if (i == PAGE_SETTINGS) return true;
        if (i == PAGE_SIP) return !BuildConfig.PUBLIC_EDITION && p.sipConfigured();
        if (i == PAGE_NETWORK) return p.showNetwork();
        if (i == PAGE_ANALOG) return p.showAnalog();
        if (i == PAGE_DIGITAL) return p.showDigital();
        if (i == PAGE_BM) return p.showBm();
        if (i == PAGE_DSTAR) return p.showDstar();
        if (i == PAGE_YSF) return p.showYsf();
        if (i == PAGE_P25) return p.showP25();
        if (i == PAGE_NXDN) return p.showNxdn();
        if (i == PAGE_NET_RADIO) return p.showNetRadio();
        if (i == PAGE_NRL) return p.showNrl();
        return false;
    }

    private int navModeForPage(int i) {
        if (i == PAGE_DIGITAL) return 0;
        if (i == PAGE_ANALOG) return 1;
        if (i == PAGE_BM) return 2;
        if (i == PAGE_NETWORK) return 3;
        if (i == PAGE_DSTAR) return 4;
        if (i == PAGE_YSF) return 5;
        if (i == PAGE_P25) return 6;
        if (i == PAGE_NXDN) return 7;
        if (i == PAGE_NRL) return 8;
        if (i == PAGE_SIP) return 9;
        return -1;
    }
    private int navOrderIndex(int i) {
        if (i == PAGE_ANALOG) return 0;
        if (i == PAGE_DIGITAL) return 1;
        if (i == PAGE_NETWORK) return 2;
        if (i == PAGE_BM) return 3;
        if (i == PAGE_DSTAR) return 4;
        if (i == PAGE_YSF) return 5;
        if (i == PAGE_P25) return 6;
        if (i == PAGE_NXDN) return 7;
        if (i == PAGE_NRL) return 8;
        if (i == PAGE_SIP) return 9;
        return 8;
    }

    private View pageView(int i) {
        if (i == PAGE_DIGITAL) return pageRadio;
        if (i == PAGE_BM) return pageBm;
        if (i == PAGE_ANALOG) return pageFmo;
        if (i == PAGE_NETWORK) return pageNetwork;
        if (i == PAGE_DSTAR) return pageDstar;
        if (i == PAGE_YSF) return pageYsf;
        if (i == PAGE_P25) return pageP25;
        if (i == PAGE_NXDN) return pageNxdn;
        if (i == PAGE_NRL) return pageNrl;
        if (i == PAGE_NET_RADIO) return pageNetRadio;
        if (i == PAGE_SETTINGS) return pageSet;
        return null;
    }

    private void animatePageSwitch(View oldView, View newView, boolean animate) {
        if (newView == null) return;
        if (!animate) {
            newView.animate().cancel();
            newView.setAlpha(1f);
            newView.setTranslationY(0f);
            return;
        }
        if (oldView != null && oldView != newView) {
            oldView.animate().cancel();
            oldView.setVisibility(View.VISIBLE);
            oldView.setAlpha(1f);
            oldView.setTranslationY(0f);
            oldView.animate().alpha(0f).translationY(-dp(6)).setDuration(110L)
                    .withEndAction(() -> {
                        oldView.setVisibility(View.GONE);
                        oldView.setAlpha(1f);
                        oldView.setTranslationY(0f);
                    }).start();
        }
        newView.animate().cancel();
        newView.setAlpha(0f);
        newView.setTranslationY(dp(10));
        newView.animate().alpha(1f).translationY(0f).setDuration(190L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void setPage(int i) {
        if (i == PAGE_SIP) { openSipPhone(); return; }
        int oldPage = page;
        if (!pageEnabled(i)) i = defaultPage();
        // [3.2.45] 收音机只在本页发声：切到别的模式立即暂停，回到本页再恢复
        if (page == PAGE_NET_RADIO && i != PAGE_NET_RADIO) {
            if (radioPlayer != null && radioPlayer.isPlaying()) {
                radioPausedForPage = true;
                radioPlayer.pause();
                if (radioPlayBtn != null) radioPlayBtn.setText("▶");
            }
        } else if (i == PAGE_NET_RADIO && radioPausedForPage) {
            radioPausedForPage = false;
            if (!radioUserPaused && radioPlayer != null) {
                radioPlayer.resume();
                if (radioPausedForOther) radioPlayer.setVolume(0.2f);
            }
        }
        // NRL 离开页面时不保留旧播放队列；返回时重新建立干净的 AudioTrack。
        // 多路监听开启时不强制停止，保留用户已有的多路守听行为。
        if (oldPage == PAGE_NRL && i != PAGE_NRL && !p.multiListen() && nrlAudio != null) {
            try { nrlAudio.stopPlayback(); } catch (Throwable ignored) {}
        }
        page = i;
        int newMode = navModeForPage(i);
        mode = newMode;
        if (newMode >= 0 && newMode <= 8) lastCommMode = newMode;
        if (newMode >= 0 && newMode <= 8) { try { p.set("lastVoiceMode", String.valueOf(newMode)); } catch (Throwable ignored) {} }
        int[] allPages = { PAGE_DIGITAL, PAGE_BM, PAGE_ANALOG, PAGE_NETWORK, PAGE_DSTAR,
                PAGE_YSF, PAGE_P25, PAGE_NXDN, PAGE_NRL, PAGE_NET_RADIO, PAGE_SETTINGS };
        for (int pi : allPages) {
            View pv = pageView(pi);
            if (pv != null) pv.setVisibility(pi == i ? View.VISIBLE : View.GONE);
        }
        if (audioLevelMeter != null) {
            audioLevelMeter.setMeterVisible(i != PAGE_NET_RADIO);
        }
        RadioSpectrum.setUiVisible(i == PAGE_NET_RADIO);
        // [3.2.51] 每次进入收音机页检查服务器目录（10 分钟内不重复下载）
        if (i == PAGE_NET_RADIO && p.showNetRadio()
                && System.currentTimeMillis() - radioCatalogLastRefresh > 10L * 60L * 1000L) {
            refreshRadioCatalogAsync();
        }
        animatePageSwitch(pageView(oldPage), pageView(i), oldPage != i);
        paintNav();
        updateModeLabel();
        if (i == PAGE_DIGITAL) refreshDmrConnectionUi();
        if (!rebuildingUiInPlace) {
            if (i == PAGE_DIGITAL && p.showDigital() && p.autoDigital() && p.dmrId() > 0
                    && !p.callsign().isEmpty() && p.password().length() >= 6
                    && (!dmrUp() || dmrStale())) ui.postDelayed(this::connect, 250);
            if (i == PAGE_BM && p.showBm() && p.bmAutoLogin() && p.dmrServerId() > 0 && !p.bmCallsign().isEmpty() && !p.dmrServerPassword().isEmpty() && !bmUp()) ui.postDelayed(this::connectBm, 250);
            if (i == PAGE_ANALOG && p.showAnalog() && p.modeAnalog()
                    && !p.callsign().isEmpty() && p.password().length() >= 6
                    && !fmoUp()) { fmoWanted = true; ui.postDelayed(() -> startFmo(true), 250); }
            if (i == PAGE_DSTAR && p.showDstar() && p.autoDstar() && !p.dstarCallsign().isEmpty() && !dstarActive()) ui.postDelayed(this::connectDstar, 250);
            if (i == PAGE_YSF && p.showYsf() && p.autoYsf() && !p.ysfCallsign().isEmpty() && !ysfActive()) ui.postDelayed(this::connectYsf, 250);
            if (i == PAGE_P25 && p.showP25() && p.autoP25() && !p.p25Callsign().isEmpty() && !p25Active()) ui.postDelayed(this::connectP25, 250);
            if (i == PAGE_NXDN && p.showNxdn() && p.autoNxdn() && !p.nxdnCallsign().isEmpty() && !nxdnActive()) ui.postDelayed(this::connectNxdn, 250);
            if (i == PAGE_NRL && p.showNrl() && p.autoNrl() && !nrlActive) ui.postDelayed(this::connectNrl, 250);
            if (i == PAGE_NETWORK && !hqActive && !accountCheckRunning && p.dmrId() > 0 && !p.callsign().isEmpty() && p.password().length() >= 6) ui.postDelayed(this::joinRoom, 250);
        }
        if (i == PAGE_NETWORK) { ui.postDelayed(this::buildRoomList, 50); if (bmNet != null && bmNet.isRunning()) bmNet.requestPresence(); }
        if (i == PAGE_DSTAR) updateDstarSelectors();
        if (i == PAGE_YSF) { updateYsfSelectors(); YsfServers.refresh(this, () -> ui.post(this::updateYsfSelectors)); }
        if (i == PAGE_P25) { updateP25Selectors(); P25Servers.refresh(this, () -> ui.post(this::updateP25Selectors)); }
        if (i == PAGE_NXDN) { updateNxdnSelectors(); NxdnServers.refresh(this, () -> ui.post(this::updateNxdnSelectors)); }
        if (i == PAGE_NRL) {
            refreshNrlServerButton();
            refreshNrlDirectory(false);
            if (nrlActive && nrlAudio != null && !p.multiListen()) {
                runAudioIo(() -> { if (nrlAudio != null) nrlAudio.startPlayback(); });
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (out != null) out.putInt("arch.page", page);
    }

    private void switchGroup(int idx) {
        if (idx < 0 || idx >= TGS.length) return;
        int newTg = TGS[idx][0];
        int newSlot = TGS[idx][1];
        boolean wasConnected = hbp != null && hbp.isRunning();
        if (hbp != null) { try { hbp.stop(); } catch (Throwable ignored) {} hbp = null; }
        p.set("tg", String.valueOf(newTg));
        p.set("tgSlot", String.valueOf(newSlot));
        int monitor = 0;
        for (int[] t : TGS) if (t[0] != newTg) { monitor = t[0]; break; }
        p.set("monitorTg", String.valueOf(monitor));
        updateGroupUI();
        updateModeLabel();
        log("选择通话组: " + tgDescription());
        pushPresence();
        if (wasConnected) {
            switchingGroup = true;
            statusText.setText("切换中…");
            statusDot.setTextColor(SUB);
            ui.postDelayed(() -> { switchingGroup = false; connect(); }, 350);
        } else {
            switchingGroup = false;
            connect();
        }
    }

    private void toggleDualWatch(boolean on) {
        p.set("dualWatch", on ? "1" : "0");
        updateGroupUI();
        if (hbp != null) {
            switchingGroup = true;
            hbp.stop(); hbp = null;
            statusText.setText("应用双守听…");
            statusDot.setTextColor(SUB);
            ui.postDelayed(() -> { switchingGroup = false; connect(); }, 350);
        }
    }

 /** 把当前选中的通话组按钮画成蓝色，其余灰色 */
    private void paintTgButtons() {
        if (tgBtns == null) return;
        for (int i = 0; i < tgBtns.length && i < TGNAMES.length; i++) {
            Button b = tgBtns[i];
            if (b == null) continue;
            boolean on = (p.tg() == TGS[i][0]);
            b.setTextColor(on ? 0xFFFFFFFF : TXT);
            b.setBackground(tgButtonBg(on));
            b.setElevation(dp(on ? 3 : 0));
            b.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", on ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    private void updateGroupUI() {
        String d = tgDescription();
        if (tgLabel != null) tgLabel.setText("当前组: " + d);
        if (nowTG != null) nowTG.setText(d);
        if (setGroupInfo != null) setGroupInfo.setText("当前： " + d);
        paintTgButtons();
    }

    private String tgDescription() {
        return tgName();        // 只显示组名，不显示时隙和组号
    }

    private void updateModeLabel() {
        String terminalChannel;
        if (mode == 8) {
            String nrlName = nrlGroupNameCache;
            if (nrlName == null || nrlName.trim().isEmpty()) nrlName = defaultNrlGroupName(p.nrlGroup());
            terminalChannel = nrlName;
        } else if (mode == 7) terminalChannel = "NXDN · " + nxdnServerName();
        else if (mode == 6) terminalChannel = "P25 · " + p25ServerName();
        else if (mode == 5) terminalChannel = "YSF · " + ysfServerName();
        else if (mode == 4) terminalChannel = "D-Star · " + dstarDisplayName();
        else if (mode == 2) terminalChannel = bmTgDisplayName();
        else if (mode == 3) terminalChannel = hqName();
        else if (mode == 1) terminalChannel = "BY2AA-FMO";
        else if (mode == 0) terminalChannel = tgName();
        else terminalChannel = "设置";
        if (modeLabel != null) modeLabel.setText("当前网络: " + terminalChannel);
        try {
            HyteraPdc760Bridge.setDisplay(this, terminalChannel, p.callsign(), String.valueOf(p.dmrId()));
        } catch (Throwable ignored) {}
    }


    private void toggleFmo(boolean on) {
        if (on) startFmo(); else stopFmo();
    }


    private void startFmo() {
        startFmo(true);
    }

    private void startFmo(boolean markAnalog) {
        if (!accountValidated) { ensureAccountThen("analog", () -> startFmo(markAnalog)); return; }
        String cs = p.callsign();
        if (cs.length() == 0) {
            if (fmoEnable != null) fmoEnable.setChecked(false);
            new AlertDialog.Builder(this).setMessage("请先保存呼号，再连接 FMO 房间").setPositiveButton("好", null).show();
            return;
        }
        if (fmo != null && fmo.isRunning()) {
            if (markAnalog) { p.set("fmoAuto", "1"); p.set("modeAnalog", "1"); }
            updateModeLabel(); return;
        }
        if (fmo != null) { try { fmo.stop(); } catch (Exception ignored) {} fmo = null; }
        if (fmoStatus != null) fmoStatus.setText("FMO: 正在申请证书并登录…");
        fmoWanted = true;
        if (fmo != null && fmo.isRunning()) {
            if (markAnalog) { p.set("fmoAuto", "1"); p.set("modeAnalog", "1"); }
            updateModeLabel(); return;
        }
        if (markAnalog) { p.set("fmoAuto", "1"); p.set("modeAnalog", "1"); }
        // 每次连接都回到默认预缓冲，避免历史欠载把门槛顶到 2.4s 后长期不出声
        fmoPrebufferMs = FMO_DIRECT_PREBUFFER_MS;
        fmoPrimed = false;
        fmo = new FmoClient(getFilesDir(), "fmo.hljrsa.cn", 8081, cs, p.dmrId(), this);
        fmo.start(); updateFmoUi();
    }

    private void stopFmo() {
        fmoWanted = false;
        p.set("fmoAuto", "0");
        p.set("modeAnalog", "0");
        if (fmo != null) { fmo.stop(); fmo = null; }
        if (fmoStatus != null) fmoStatus.setText("FMO: 未连接");
        updateModeLabel(); updateFmoUi();
        renderOnline(java.util.Collections.emptyList());
    }

    private void updateFmoUi() {
        boolean running = fmo != null && fmo.isRunning();
        if (fmoEnable != null) fmoEnable.setChecked(running);
        if (fmoButton != null) setLoginStatus(fmoButton, running ? "已登录" : "未登录", running ? BLUE : SUB);
    }

    private void renderAppOnline(java.util.List<FmoClient.AppUser> users) {
        if (appOnlineBox == null) return;
        appOnlineBox.removeAllViews();
        if (users == null || users.size() == 0) { appOnlineBox.addView(tv("暂无 APP 用户", 12, SUB)); return; }
        appOnlineBox.addView(onlineRow("呼号", "DMR ID", "状态", true));
        int n = Math.min(users.size(), visibleRows);
        for (int i = 0; i < n; i++) {
            final FmoClient.AppUser u = users.get(i);
            View row = onlineRow(u.callsign, String.valueOf(u.dmrId),
                    u.dmrId == p.dmrId() ? "本机" : "在线", false, u.dmrId);
            if (u.dmrId != p.dmrId()) row.setOnClickListener(v -> showDuplexMenu(u));
            appOnlineBox.addView(row);
        }
    }

    private void renderVoiceOnline(java.util.List<NetworkRoomClient.Member> users) {
        if (appOnlineBox == null) return;
        try { lastVoiceMembers = users == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(users); } catch (Throwable ignored) {}

        netLiveMembers = users == null ? -1 : users.size();
        appOnlineBox.removeAllViews();
        if (users == null || users.isEmpty()) {
            appOnlineBox.addView(tv("暂无在线用户", 12, SUB));
            return;
        }
        appOnlineBox.addView(onlineRow("呼号", "DMR ID", "状态", true));
        for (int i = 0; i < users.size(); i++) {
            final NetworkRoomClient.Member u = users.get(i);
            final boolean dstarRoom = effectiveNetRoom().startsWith("dstar:");
            String status = u.dmrId == p.dmrId() ? "本机"
                    : (u.duplexWith != 0 ? "双工中" : (u.pendingWith != 0 ? "呼叫中" : (u.tx ? "发射中" : "在线")));
            View row = onlineRow(u.callsign, String.valueOf(u.dmrId), status, false, dstarRoom ? 0 : u.dmrId);
            if (!dstarRoom && u.dmrId != p.dmrId()) row.setOnClickListener(v -> showDuplexMenu(u.callsign, u.dmrId));
            appOnlineBox.addView(row);
        }
        appOnlineBox.post(() -> {
            try {
                appOnlineBox.requestLayout(); appOnlineBox.invalidate();
                if (appOnlineBox.getParent() != null) ((View) appOnlineBox.getParent()).requestLayout();
            } catch (Throwable ignored) {}
            try { buildRoomList(); } catch (Throwable ignored) {}
        });
        maybeAutoScrollNetworkUsers();
    }

 /** 第一次确认房间内不止自己时，把在线用户区自动带到可见位置。 */
    private void maybeAutoScrollNetworkUsers() {
        if (netOnlineAutoScrolled || page != 3 || networkScroll == null || appOnlineBox == null) return;
        if (netLiveMembers <= 1) return;
        netOnlineAutoScrolled = true;
        appOnlineBox.postDelayed(() -> {
            try {
                int[] boxPos = new int[2], scrollPos = new int[2];
                appOnlineBox.getLocationInWindow(boxPos);
                networkScroll.getLocationInWindow(scrollPos);
                int target = networkScroll.getScrollY() + boxPos[1] - scrollPos[1] - dp(12);
                networkScroll.smoothScrollTo(0, Math.max(0, target));
            } catch (Throwable ignored) {}
        }, 180L);
    }

 /**
 * FMO 在线用户：三列对齐（左呼号 / 中 UID / 右频率）。
 * 呼号、UID、频率长度都不一样，用等宽列 + 各自对齐，保证上下行对得齐。
 */
    private View onlineRow4(String a, String b, String c, String mode, boolean header) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(header ? 3 : 6), dp(6), dp(header ? 3 : 6));
        r.setBackground(header ? roundBg(DARK ? 0x142563EB : 0x0D2563EB, 10) : glassPanelBg(12));
        r.setElevation(dp(header ? 0 : 2));
        r.setTranslationZ(dp(header ? 0 : 1));
        r.setMinimumHeight(dp(header ? 30 : 46));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(4));
        r.setLayoutParams(rowLp);
        int col = header ? SUB : TXT;
        String[] values = {a, b, c, mode};
        float[] weights = {1.22f, 0.78f, 1.0f, 0.85f};
        int[] aligns = {Gravity.START | Gravity.CENTER_VERTICAL, Gravity.CENTER,
                Gravity.END | Gravity.CENTER_VERTICAL, Gravity.CENTER};
        for (int i = 0; i < values.length; i++) {
            TextView t = tv(values[i], header ? 12 : 14, i == 3 && !header ? BLUE : col);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
            t.setGravity(aligns[i]);
            t.setIncludeFontPadding(false);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(t);
        }
        return r;
    }

    private View onlineRow(String a, String b, String c, boolean header) {
        return onlineRow(a, b, c, header, 0);
    }

    private View onlineRow(String a, String b, String c, boolean header, int callDmrId) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(header ? 3 : 6), dp(6), dp(header ? 3 : 6));
        r.setBackground(roundBg(header ? (DARK ? 0x142563EB : 0x0D2563EB) : (DARK ? 0x0AFFFFFF : 0x080F172A), 10));
        r.setMinimumHeight(dp(header ? 30 : 46));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(4));
        r.setLayoutParams(rowLp);
        int col = header ? SUB : TXT;
        LinearLayout nameWrap = new LinearLayout(this);
        nameWrap.setOrientation(LinearLayout.HORIZONTAL);
        nameWrap.setGravity(Gravity.CENTER_VERTICAL);
        nameWrap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        TextView t1 = tv(a, header ? 13 : 15, col);
        t1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t1.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t1.setIncludeFontPadding(false); t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameWrap.addView(t1);
        if (!header && callDmrId > 0 && callDmrId != p.dmrId()) {
            final int targetId = callDmrId;
            final String targetCall = a;
            final boolean callActive = (duplexActive || duplexOutgoing || duplexIncoming) && duplexPeerId == targetId;
            TextView call = tv("☎", 17, callActive ? RED : BLUE);
            call.setGravity(Gravity.CENTER);
            call.setIncludeFontPadding(false);
            call.setTypeface(null, Typeface.BOLD);
            call.setContentDescription((callActive ? "挂断 " : "呼叫 ") + a);
            call.setBackground(roundBg(callActive ? (DARK ? 0x33EF4444 : 0x22DC2626) : (DARK ? 0x223B82F6 : 0x1A2563EB), 16));
            LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(dp(34), dp(32));
            callLp.setMargins(dp(4), 0, 0, 0);
            call.setLayoutParams(callLp);
            call.setOnClickListener(v -> {
                if (callActive) endDuplexCall();
                else initiateDuplexCall(targetCall, targetId);
            });
            nameWrap.addView(call);
        }
        TextView t2 = tv(b, header ? 13 : 15, col);
        t2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        t2.setGravity(Gravity.CENTER);
        t2.setIncludeFontPadding(false); t2.setSingleLine(true);
        TextView t3 = tv(c, header ? 13 : 15, col);
        t3.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        t3.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        t3.setIncludeFontPadding(false); t3.setSingleLine(true);
        t3.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(nameWrap); r.addView(t2); r.addView(t3);
        return r;
    }

    private void renderOnline(java.util.List<FmoClient.User> users) {
        if (onlineBox == null) return;
        onlineBox.removeAllViews();
        fmoOnlineNameViews.clear();
        onlineBox.addView(onlineRow4("呼号", "UID", "频率MHz", "通话模式", true));
        if (users == null || users.size() == 0) {
            onlineBox.addView(tv("暂无在线用户", 12, SUB));
            return;
        }
        int n = users.size();
        for (int i = 0; i < n; i++) {
            FmoClient.User u = users.get(i);
            View row = onlineRow4(u.callsign, String.valueOf(u.uid),
                    String.format(java.util.Locale.US, "%.3f", u.txMhz), "模拟", false);
            try {
                TextView name = (TextView) ((LinearLayout) row).getChildAt(0);
                fmoOnlineNameViews.put(u.uid, name);
                name.setTextColor(u.uid == fmoSpeakerUid ? RED : TXT);
            } catch (Throwable ignored) {}
            onlineBox.addView(row);
        }
    }

    private void showFmoIncoming(String callsign) {
        try {
            String who = callsign == null ? "" : callsign.trim();
            if (who.length() == 0) return;
            if (fmoCallState != null) { fmoCallState.setText("接收中"); fmoCallState.setTextColor(BLUE); }
            if (fmoCallLine != null) { fmoCallLine.setText(who); fmoCallLine.setTextColor(RED); }
            if (!who.equals(fmoLastPeer)) { fmoLastPeer = who; setLastCall(1, who); }
            if (fmoCallResetTask != null) ui.removeCallbacks(fmoCallResetTask);
            fmoCallResetTask = () -> {
                fmoCallResetTask = null;
                if (txing && "analog".equals(activePttTarget)) return;
                if (fmoCallState != null) { fmoCallState.setText("空闲"); fmoCallState.setTextColor(SUB); }
                if (fmoCallLine != null) { fmoCallLine.setText("等待呼叫"); fmoCallLine.setTextColor(TXT); }
                clearModeSignal(1);
            };
            // FMO has no explicit end packet. Last voice packet itself is complete;
            // 650 ms covers packetization/jitter without leaving the callsign for 10 s.
            ui.postDelayed(fmoCallResetTask, 650L);
        } catch (Throwable t) { log("FMO 呼叫栏更新失败: " + t); }
    }

    private void markFmoSpeaker(int uid) {
        fmoSpeakerUid = uid;
        for (java.util.Map.Entry<Integer, TextView> e : fmoOnlineNameViews.entrySet()) {
            TextView v = e.getValue();
            if (v != null) v.setTextColor(e.getKey() == uid ? RED : TXT);
        }
        if (fmoSpeakerResetTask != null) ui.removeCallbacks(fmoSpeakerResetTask);
        fmoSpeakerResetTask = () -> {
            fmoSpeakerResetTask = null;
            fmoSpeakerUid = 0;
            for (TextView v : fmoOnlineNameViews.values()) if (v != null) v.setTextColor(TXT);
        };
        ui.postDelayed(fmoSpeakerResetTask, 1400L);
    }

    private org.json.JSONObject accountPost(String path, org.json.JSONObject body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://hljrsa.cn:50113" + path).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (java.io.OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        c.disconnect();
        org.json.JSONObject j = new org.json.JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        if (code >= 400 || !j.optBoolean("ok", false))
            throw new Exception(j.optString("error", "HTTP " + code));
        return j;
    }

    private void saveAccount(String cs, int dmrId, int essid, String password, Button save) {
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("callsign", cs); body.put("dmrId", dmrId); body.put("essid", essid); body.put("password", password);
            org.json.JSONObject signed;
            try {
                signed = accountPost("/api/account/login", body);
            } catch (Exception loginError) {
                signed = accountPost("/api/account/register", body);
            }
            final org.json.JSONArray tgs = signed.optJSONObject("account") == null ? null
                    : signed.optJSONObject("account").optJSONArray("talkgroups");
            ui.post(() -> {
                if (tgs != null) applyTalkgroups(tgs, true);
                p.set("callsign", cs);
                p.set("dmrid", String.valueOf(dmrId));
                p.set("essid", String.format(java.util.Locale.US, "%02d", essid));
                p.set("password", password);
                accountValidated = false;
                maybeAutoDownloadContacts(dmrId, essid);
                if (callEd != null && !cs.equals(callEd.getText().toString().trim())) callEd.setText(cs);
                if (idEd != null && !String.valueOf(dmrId).equals(idEd.getText().toString().trim())) idEd.setText(String.valueOf(dmrId));
                if (essidEd != null) essidEd.setText(String.format(java.util.Locale.US, "%02d", essid));
                if (passEd != null && !password.equals(passEd.getText().toString())) passEd.setText(password);
                refreshAccountStatus();
                if (save != null) {
                    save.setEnabled(true); save.setText("✓ 注册成功");
                    ui.postDelayed(() -> { save.setText("已注册"); save.setEnabled(true); }, 1800);
                }
                toast("协会身份已保存");
            });
        } catch (Exception e) {
            ui.post(() -> {
                if (save != null) { save.setEnabled(true); save.setText("注册"); }
                toast("协会身份保存失败：" + e.getMessage() + "；如已注册请确认密码或联系管理员重置");
            });
        }
    }

    private void ensureAccountThen(Runnable action) {
        ensureAccountThen("default", action);
    }

    private void ensureAccountThen(String key, Runnable action) {
        if (accountValidated) { action.run(); return; }
        if (accountCheckRunning) {
            pendingAccountActions.put(key, action);
            log("账号校验进行中，已排队连接动作: " + key);
            return;
        }
        String cs = p.callsign(); String pw = p.password(); int did = p.dmrId(), essid = p.essid();
        if (cs.isEmpty() || pw.length() < 6) {
            toast("请先在「身份设置与注册」里保存呼号和协会密码");
            setPage(4); return;
        }
        accountCheckRunning = true;
        new Thread(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("callsign", cs); body.put("dmrId", did); body.put("essid", essid); body.put("password", pw);
                org.json.JSONObject signed = accountPost("/api/account/login", body);
                final org.json.JSONArray tgs = signed.optJSONObject("account") == null ? null
                        : signed.optJSONObject("account").optJSONArray("talkgroups");
                ui.post(() -> {
                    if (tgs != null) applyTalkgroups(tgs, true);
                    accountCheckRunning = false; accountValidated = true;
                    maybeAutoDownloadContacts(did, essid);
                    refreshAccountStatus();
                    flushPendingAccountActions();
                    try { action.run(); } catch (Throwable t) { log("账号校验后连接失败: " + t); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    accountCheckRunning = false;
                    accountValidated = true;      // 账号服务器异常时直接尝试连接，由语音服务器再校验
                    log("账号校验失败，直接尝试连接: " + e.getMessage());
                    toast("账号服务器繁忙，正在直接连接…");
                    flushPendingAccountActions();
                    try { action.run(); } catch (Throwable ignored2) {}
                });
            }
        }, "account-check").start();
    }

    private void flushPendingAccountActions() {
        if (pendingAccountActions.isEmpty()) return;
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>(pendingAccountActions.values());
        pendingAccountActions.clear();
        for (Runnable r : actions) {
            try { r.run(); } catch (Throwable t) { log("账号校验后的连接动作失败: " + t); }
        }
    }

    private void connect() {
        ensureAccountThen("dmr", this::connectNow);
    }

    private void connectNow() {
        String cs = p.callsign(); int id = p.dmrId();
        if (cs.isEmpty() || id == 0) {
            new AlertDialog.Builder(this).setMessage("请先在「设置」里填协会呼号和 DMR ID 并保存")
                    .setPositiveButton("好", null).show();
            setPage(4); return;
        }
        if (hbp != null) { hbp.stop(); hbp = null; }
        p.set("dmrAuto", "1");
        updateGroupUI();
        statusText.setText("登录中…");
        int peerId = id * 100 + (p.essid() > 0 ? p.essid() - 1 : 0);
        String options = (p.slot() == 2 ? "TS2=" : "TS1=") + p.tg() + ";";
        hbp = new HbpClient(p.host(), p.port(), peerId, id, p.tg(), p.monitorTg(), p.slot(), p.cc(), cs, "", options, new HbpSourceListener(false));
        hbp.start();
    }

    private void startNetworkPttRequest() {
        if (duplexActive) { toast("双工通话中不能按 PTT"); return; }
        if (!hqActive || bmNet == null || !bmNet.isRunning()) { toast("请先连接网络频道"); return; }
        if (hqTxRequested) return;
        hqTxRequested = true;
        hqPttGranted = false;
        activePttTarget = "dstar".equals(activeVoiceKind) ? "dstar" : "network";
        clearHqPttPreroll();
        hqTxFrames = 0;
        hqTxLastFrameMs = System.currentTimeMillis();
        ensureHqTxWatchdog();
        clearNetworkReceiveIfIdle();
        hqFlushPlayback();
        startNetworkMic();              // capture immediately; no audio is sent before grant
        bmNet.requestPtt(true);
        if (hqStatus != null) setHqStatus("状态: 正在申请发射权…");
        nowState.setText("请求发射…");
        refreshNetworkCallBar();
    }

    private void startNetworkMic() {
        if (hqMicStarting || duplexActive) return;
        if (hqAudio != null && hqAudio.isMicOn()) {
            if (hqPttGranted) beginNetworkTransmit();
            return;
        }
        final int generation = ++networkMicGeneration;
        hqMicStarting = true;
        runAudioIo(() -> {
            boolean ok = false; String err = "";
            for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                try { ok = startNetworkMicInner(generation); }
                catch (Throwable t) { err = String.valueOf(t); log("网络发射启动异常: " + t); }
                if (!ok && attempt == 0) {
                    try { Thread.sleep(220L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
            final boolean fok = ok; final String ferr = err;
            ui.post(() -> {
                if (generation != networkMicGeneration) return;
                hqMicStarting = false;
                if (fok && hqTxRequested) {
                    if (hqPttGranted) beginNetworkTransmit();
                } else if (!fok && hqTxRequested) {
                    log("网络麦克风启动失败，保留 PTT 等待自动恢复 " + ferr);
                    if (hqStatus != null) setHqStatus("状态: 麦克风重试中…");
                }
            });
        });
    }

 /** 网络发射看门狗：状态卡死/连接已断时强制松开 PTT，避免一直显示“发射中”。 */
    private void ensureHqTxWatchdog() {
        if (hqTxWatchdogTask != null) return;
        hqTxWatchdogTask = () -> {
            hqTxWatchdogTask = null;
            if (!(txing || hqTxRequested)) return;
            long idle = System.currentTimeMillis() - hqTxLastFrameMs;
            boolean down = (bmNet == null || !bmNet.isRunning());
            if (!down && hqTxRequested && idle > 2500L && !hqMicRestarting
                    && hqAudio != null && !hqAudio.isMicHealthy()) {
                hqMicRestarting = true;
                log("麦克风停止出帧，自动重建 idle=" + idle + "ms");
                runAudioIo(() -> {
                    boolean ok = false;
                    try { ok = hqAudio != null && hqAudio.restartMic(); }
                    catch (Throwable t) { log("麦克风自动重建异常: " + t); }
                    final boolean rebuilt = ok;
                    ui.post(() -> {
                        hqMicRestarting = false;
                        if (rebuilt) {
                            hqTxLastFrameMs = System.currentTimeMillis();
                            log("麦克风自动重建成功");
                        } else {
                            log("麦克风自动重建失败");
                        }
                    });
                });
            }
            // 静音不代表松开 PTT。只有网络连接断开时才自动复位；
            // 持续时长由系统 TOT 控制，不能因没有音频帧自行结束发射。
            if (down) {
                log("网络连接已断开，自动结束发射 idle=" + idle + "ms");
                try { stopNetworkPttRequest(); } catch (Throwable ignored) {}
                try { toast("网络已断开，发射结束"); } catch (Throwable ignored) {}
                return;
            }
            ensureHqTxWatchdog();
        };
        ui.postDelayed(hqTxWatchdogTask, 1000L);
    }

    private void stopHqTxWatchdog() {
        if (hqTxWatchdogTask != null) { ui.removeCallbacks(hqTxWatchdogTask); hqTxWatchdogTask = null; }
    }

 /** 在后台线程创建录音/播放设备：个别机型 AudioRecord 初始化会阻塞主线程，容易触发闪退/ANR。 */
    private boolean startNetworkMicInner(final int generation) {
        if (!hqTxRequested || generation != networkMicGeneration || duplexActive) return false;
        try { audio.stop(); } catch (Throwable ignored) {}
        audioReady = false;
        if (hqAudio == null) return false;
        boolean micOk = hqAudio.startMic(frame -> {
            try {
                if (!hqTxRequested || generation != networkMicGeneration || bmNet == null) return;
                if (netOpus == null) return;
                byte[] opusFrame = netOpus.encode(frame);
                if (opusFrame == null) return;
                hqTxLastFrameMs = System.currentTimeMillis();
                synchronized (hqPttPreroll) {
                    if (!hqPttGranted) {
                        while (hqPttPreroll.size() >= HQ_PREROLL_OPUS_FRAMES) hqPttPreroll.removeFirst();
                        hqPttPreroll.addLast(opusFrame.clone());
                        return;
                    }
                    bmNet.sendVoice(opusFrame);
                }
                hqTxFrames++;
                if (hqTxFrames == 1 || hqTxFrames % 25 == 0) {
                    final int n = hqTxFrames;
                    safeUi(() -> { if (hqStatus != null) setHqStatus("状态: 发射中 · 已发送 " + n + " 帧"); });
                }
            } catch (Throwable t) { log("网络发射帧处理异常: " + t); }
        });
        if (!micOk) return false;
        if (!hqTxRequested || generation != networkMicGeneration) {
            hqAudio.startMic(null);
            return false;
        }
        hqTxLastFrameMs = System.currentTimeMillis();
        return true;
    }

    private void beginNetworkTransmit() {
        if (!hqTxRequested || txing || bmNet == null) return;
        txing = true;
        txCallStart = System.currentTimeMillis();
        hqTxFrames = 0;
        hqTxLastFrameMs = System.currentTimeMillis();
        int queuedCount = 0;
        synchronized (hqPttPreroll) {
            while (!hqPttPreroll.isEmpty()) {
                if (!hqTxRequested || bmNet == null) break;
                bmNet.sendVoice(hqPttPreroll.removeFirst());
                hqTxFrames++;
                queuedCount++;
            }
            hqPttGranted = true;
        }
        pttToneStart();
        scheduleTxTimeout();
        ensureHqTxWatchdog();
        if (nowState != null) nowState.setText("Net发射中");
        if (nowCall != null) nowCall.setText(p.callsign());
        if (hqStatus != null) setHqStatus("状态: 发射中 · 已补发前滚 " + queuedCount + " 帧");
        refreshNetworkCallBar();
        ui.post(this::paintNav);
    }

    private void clearHqPttPreroll() {
        synchronized (hqPttPreroll) { hqPttPreroll.clear(); }
    }

    private void stopNetworkPttRequest() {
        try { stopNetworkPttRequestInner(); }
        catch (Throwable t) { log("停止网络发射异常: " + t); }
    }

    private void stopNetworkPttRequestInner() {
        boolean wasStarting = hqMicStarting;
        // 让尚在后台初始化的 startNetworkMicInner() 失效；即使 AudioRecord 已经创建，随后也会被关掉。
        networkMicGeneration++;
        hqMicStarting = false;
        stopHqTxWatchdog();
        if (!hqTxRequested && !txing && !wasStarting) return;
        hqTxRequested = false;
        hqPttGranted = false;
        clearHqPttPreroll();
        hqFlushPlayback();
        if (bmNet != null) bmNet.requestPtt(false);
        int sec = txCallStart == 0 ? 0 : (int)((System.currentTimeMillis() - txCallStart) / 1000);
        hqMicOffAsync();
        if (txing) {
            txing = false; txCallStart = 0;
            pttToneStop();
            cancelTxTimeout();
            if (hqTxFrames > 0 && sec == 0) sec = 1;
            addNetLog("发射", p.callsign(), hqName(), sec);
            hqTxFrames = 0;
        } else {
            txCallStart = 0; hqTxFrames = 0;
            cancelTxTimeout();
        }
        ui.post(this::paintNav);
        // Network playback owns the receive track. Only restore the legacy 8k engine
        // after actually leaving the network room, never immediately after an RF PTT.
        if (!hqActive && !audioReady && needsLegacyAudio()) startAudioAsync();
        activePttTarget = null;
        nowState.setText("空闲"); nowTimer.setText("");
        if (hqStatus != null) setHqStatus(hqActive
                ? "状态: 已连接 · " + hqName() + " · 点击断开"
                : "状态: 未连接");
    }

    /**
     * 发射期间一律不播放来话。
     * 部分机型在录音的同时一旦开始放音，音频 HAL 会直接掐掉录音，
     * 表现为「按下 PTT 亮红、说了半秒就没声音了」。
     * 多模互通的语音会从其它模式绕回本机，必须在这里挡掉。
     */
    private boolean rxPlaybackBlocked() {
        return txing || hqTxRequested;
    }

    private void pttDown() { pttDownFor(currentPttTarget()); }

    private void pttDownFor(String target) {
        radioPauseForPtt();
        // 所有模式统一在按下时给出提示音；下面各分支的重复调用由幂等保护忽略。
        pttToneStart();
        if (p != null && (p.overlayPtt() || p.externalPttEnabled() || p.pttBackground())) ensureKeepAliveService();
        if ("ysf".equals(target)) { startYsfPtt(); return; }
        if ("p25".equals(target)) { startP25Ptt(); return; }
        if ("nxdn".equals(target)) { startNxdnPtt(); return; }
        if ("dstar".equals(target)) { startDstarPtt(); return; }
        if ("network".equals(target)) { startNetworkPttRequest(); return; }
        if ("nrl".equals(target)) { startNrlPtt(); return; }
        if ("analog".equals(target)) {
            if (fmo == null || !fmo.isRunning()) { startFmo(true); toast("正在连接模拟互联，请稍后再按 PTT"); return; }
            txing = true; activePttTarget = target; txCallStart = System.currentTimeMillis();
            clearFmoAudio();
            if (fmoCallState != null) { fmoCallState.setText("发射中"); fmoCallState.setTextColor(BLUE); }
            if (fmoCallLine != null) { fmoCallLine.setText(p.callsign()); fmoCallLine.setTextColor(TXT); }
            ui.post(this::paintNav);
            fmo.startTx();
            pttToneStart();
            scheduleTxTimeout();
            nowState.setText("模拟互联发射中"); nowCall.setText(p.callsign());
            if (!audio.startFmoMic(frame -> {
                if (!(txing && fmo != null)) return;
                // 与参考 APP 一致：采集后直接送 Opus，不做额外放大/限幅
                fmo.sendPcm(frame);
            }, p.fmoEnhance())) micStartFailed();
            return;
        }
        boolean wantBm = "bm".equals(target);
        HbpClient txClient = wantBm ? bmHbp : hbp;
        if (txClient == null || !txClient.isConnected()) {
            if (wantBm) connectBm(); else connect();
            toast("正在连接" + pttTargetLabel(target) + "，请稍后再按 PTT");
            return;
        }
        if (voc != null) synchronized (voc) { voc.reset(); }
        txing = true; activePttTarget = target;
        txAcc = new byte[0]; txBursts = 0; txCallStart = System.currentTimeMillis();
        txClient.txStart();
        pttToneStart();
        scheduleTxTimeout();
        nowState.setText(wantBm ? "DMR 发射中" : "数字中继发射中");
        nowCall.setText(wantBm ? p.bmCallsign() : p.callsign());
        if (wantBm) {
            bmCallHoldUntil = 0;
            if (bmCallResetTask != null) { ui.removeCallbacks(bmCallResetTask); bmCallResetTask = null; }
            if (bmState != null) { bmState.setText("BM互联发射中"); bmState.setTextColor(BLUE); }
            if (bmCallLine != null) { bmCallLine.setText(p.bmCallsign()); bmCallLine.setTextColor(TXT); }
        }
        if (wantBm) {
            bmCallHoldUntil = 0;
            if (bmCallResetTask != null) { ui.removeCallbacks(bmCallResetTask); bmCallResetTask = null; }
            if (bmState != null) { bmState.setText("BM互联发射中"); bmState.setTextColor(BLUE); }
            if (bmCallLine != null) { bmCallLine.setText(p.bmCallsign()); bmCallLine.setTextColor(TXT); }
        }
        ui.post(this::paintNav);
        if (!audio.startMic(frame -> {
            if (!txing || voc == null) return;
            byte[] a9 = new byte[9];
            synchronized (voc) { if (!voc.encode(frame, a9)) return; }
            byte[] tx = new byte[txAcc.length + 9];
            System.arraycopy(txAcc, 0, tx, 0, txAcc.length);
            System.arraycopy(a9, 0, tx, txAcc.length, 9);
            txAcc = tx;
            if (txAcc.length >= 27) {
                byte[] b27 = new byte[27];
                System.arraycopy(txAcc, 0, b27, 0, 27);
                byte[] rest = new byte[txAcc.length - 27];
                System.arraycopy(txAcc, 27, rest, 0, rest.length);
                txAcc = rest;
                txClient.txVoice(b27);
                txBursts++;
                final int sec = txBursts * 3 / 50;
                ui.post(() -> nowTimer.setText("0:" + (sec < 10 ? "0" : "") + sec));
            }
        })) micStartFailed();
    }

    private void scheduleTxTimeout() {
        cancelTxTimeout();
        if (p == null) return;
        txTimeoutTask = () -> {
            if (txing || activePttTarget != null || hqTxRequested) {
                toast("发射超时，已自动停止");
                pttUp();
            }
        };
        ui.postDelayed(txTimeoutTask, p.pttTimeout() * 1000L);
    }

    private void cancelTxTimeout() {
        if (txTimeoutTask != null) {
            ui.removeCallbacks(txTimeoutTask);
            txTimeoutTask = null;
        }
    }

    private void pttToneStart() {
        if (p == null || !p.pttTone()) return;
        // 有人正在说话时抢麦：播放独立的拒绝音，不播放正常发射 ACK。
        if (AudioLevelBus.rxActive()) {
            pttToneOn = false;
            pttToneBusy();
            return;
        }
        // 快速连续按 PTT 时，每次 start 都重新给提示音，不能只看 pttToneOn 状态。
        pttToneOn = true;
        PttTone.start();
    }
    private void pttToneBusy() {
        // 频道占用/发射被拒绝时使用独立 NACK 双短音，不和发射 ACK 混在一起。
        if (p == null) return;
        long now = System.currentTimeMillis();
        if (now - lastPttBusyToneAt < 450L) return;
        lastPttBusyToneAt = now;
        PttTone.busy();
    }

    private void pttToneStop() {
        if (p == null || !p.pttTone()) return;
        if (!pttToneOn) return;
        pttToneOn = false;
        PttTone.stop();
    }


    private void micStartFailed() {
        toast("麦克风启动失败，请检查录音权限或麦克风是否被占用");
        try { pttUp(); } catch (Throwable ignored) {}
    }

    private void pttUp() {
        cancelHardwarePttRetry();
        radioReleasePtt();
        String target = activePttTarget != null ? activePttTarget : currentPttTarget();
        if ("ysf".equals(target)) { stopYsfPtt(); return; }
        if ("p25".equals(target)) { stopP25Ptt(); return; }
        if ("nxdn".equals(target)) { stopNxdnPtt(); return; }
        if ("nrl".equals(target)) { stopNrlPtt(); return; }
        if ("dstar".equals(target)) { stopDstarPtt(); return; }
        if ("network".equals(target)) { stopNetworkPttRequest(); return; }
        if (!txing) {
            pttToneStop();
            activePttTarget = null;
            return;
        }
        try { audio.startMic(null); } catch (Throwable ignored) {}
        txing = false;
        ui.post(this::paintNav);
        int sec = txCallStart == 0 ? 0 : (int)((System.currentTimeMillis() - txCallStart) / 1000);
        txCallStart = 0;
        if ("analog".equals(target)) {
            if (fmo != null) fmo.stopTx();
            clearFmoAudio();
            if (fmoCallState != null) { fmoCallState.setText("空闲"); fmoCallState.setTextColor(SUB); }
            if (fmoCallLine != null) { fmoCallLine.setText("等待呼叫"); fmoCallLine.setTextColor(TXT); }
            pttToneStop();
            cancelTxTimeout();
            nowState.setText("空闲"); nowTimer.setText("");
            addLog("发射", p.callsign(), 438500, 0, sec);
            activePttTarget = null;
            return;
        }
        if ("bm".equals(target)) { if (bmHbp != null) bmHbp.txEnd(); }
        else if (hbp != null) hbp.txEnd();
        pttToneStop();
        cancelTxTimeout();
        nowState.setText("空闲"); nowTimer.setText("");
        if ("bm".equals(target)) {
            addBmLog("发射", p.bmCallsign(), p.bmTg(), sec);
            bmCallHoldUntil = 0;
            if (bmCallResetTask != null) { ui.removeCallbacks(bmCallResetTask); bmCallResetTask = null; }
            if (bmState != null) { bmState.setText("空闲"); bmState.setTextColor(SUB); }
            if (bmCallLine != null) { bmCallLine.setText("等待呼叫"); bmCallLine.setTextColor(TXT); }
        } else {
            addLog("发射", p.callsign(), p.tg(), p.slot(), sec);
            if (dmrCallResetTask != null) { ui.removeCallbacks(dmrCallResetTask); dmrCallResetTask = null; }
            if (nowCall != null) { nowCall.setText("等待呼叫"); nowCall.setTextColor(TXT); }
        }
        activePttTarget = null;
    }

    // ────────── HbpClient.Listener ──────────
    private class HbpSourceListener implements HbpClient.Listener {
        private final boolean bm;
        HbpSourceListener(boolean bm) { this.bm = bm; }
        @Override public void onState(int st, String text) { handleHbpState(bm, st, text); }
        @Override public void onRxStart(int src, int dst) { handleHbpRxStart(bm, src, dst); }
        @Override public void onRxVoice(byte[] ambe27) { handleHbpRxVoice(bm, ambe27); }
        @Override public void onRxEnd(int src, int dst, int seconds) { handleHbpRxEnd(bm, src, dst, seconds); }
        @Override public void onLog(String s) { handleHbpLog(bm, s); }
    }

    private void refreshDmrConnectionUi() {
        try {
            if (statusText == null) return;
            boolean up = dmrUp();
            boolean running = hbp != null && hbp.isRunning();
            if (up) {
                statusText.setText("已登录");
                statusDot.setTextColor(GREEN);
                if (page == PAGE_DIGITAL && !txing && !rxActive && nowState != null && nowCall != null) {
                    nowState.setText("空闲"); nowState.setTextColor(SUB);
                    nowCall.setText("等待呼叫"); nowCall.setTextColor(TXT);
                }
            } else if (running) {
                statusText.setText("登录中...");
                statusDot.setTextColor(SUB);
                if (page == PAGE_DIGITAL && !txing && !rxActive && nowState != null && nowCall != null) {
                    nowState.setText("登录中"); nowState.setTextColor(BLUE);
                    nowCall.setText("登录中..."); nowCall.setTextColor(TXT);
                }
            } else {
                statusText.setText("未连接");
                statusDot.setTextColor(SUB);
                if (page == PAGE_DIGITAL && !txing && !rxActive && nowState != null && nowCall != null) {
                    nowState.setText("空闲"); nowState.setTextColor(SUB);
                    nowCall.setText("未连接"); nowCall.setTextColor(TXT);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void handleHbpState(boolean bm, int st, String text) {
        if (st == 0 && switchingGroup) return;
        ui.post(() -> {
            if (bm) {
                setBmStatus(st == 2, st == 1, text);
                if (bmNow != null) bmNow.setText("当前组: " + p.bmTg() + " · 单工");
                if (st == 2) {
                    scheduleBmAutoAttach(650);
                    if (p.alertConnection()) notifyAlert("DMR 已连接", p.bmServerName());
                } else if (st == 3) {
                    ui.postDelayed(MainActivity.this::connectBm, 4000);
                }
                return;
            }
            statusText.setText(st == 2 ? "已连接" : (st == 0 ? "登录中..." : (text == null ? "未连接" : text)));
            statusDot.setTextColor(st == 2 ? GREEN : (st == 3 ? RED : SUB));
            if (st == 0) {
                if (dmrCallResetTask != null) { ui.removeCallbacks(dmrCallResetTask); dmrCallResetTask = null; }
                if (page == PAGE_DIGITAL && nowCall != null) { nowCall.setText("未连接"); nowCall.setTextColor(TXT); }
                if (page == PAGE_DIGITAL && nowState != null) { nowState.setText("空闲"); nowState.setTextColor(SUB); }
            } else if (st == 2) {
                if (p.alertConnection()) notifyAlert("数字中继已连接", pttTargetLabel(currentPttTarget()));
                if (dmrCallResetTask == null && page == PAGE_DIGITAL && nowCall != null) { nowCall.setText("等待呼叫"); nowCall.setTextColor(TXT); }
                if (page == PAGE_DIGITAL && nowState != null) { nowState.setText("空闲"); nowState.setTextColor(SUB); }
                updateGroupUI();
            }
            refreshDmrConnectionUi();

            if (st == 3) ui.postDelayed(MainActivity.this::connectIfAuto, 4000);
        });
    }

    private void handleHbpRxStart(boolean bm, int src, int dst) {
        markModeSignal(bm ? 2 : 0);
        if (!rxActive && voc != null) synchronized (voc) { voc.reset(); }
        rxActive = true; rxCallSrc = src; rxCallDst = dst; rxCallStart = System.currentTimeMillis();
        if (p.alertCalls()) notifyAlert(bm ? "DMR 通话" : "ARCH 通话", contactLabel(src) + " 正在发射");
        ui.post(() -> {
            if (bm) {
                bmCallHoldUntil = 0;
                if (bmCallResetTask != null) { ui.removeCallbacks(bmCallResetTask); bmCallResetTask = null; }
                if (bmState != null) { bmState.setText("接收中"); bmState.setTextColor(BLUE); }
                if (bmNow != null) bmNow.setText("当前组: " + p.bmTg() + " · 单工");
                if (bmCallLine != null) { bmCallLine.setText(contactLabel(src)); bmCallLine.setTextColor(RED); }
            } else {
                if (dmrCallResetTask != null) { ui.removeCallbacks(dmrCallResetTask); dmrCallResetTask = null; }
                if (page == PAGE_DIGITAL) {
                    nowState.setText("接收中"); nowState.setTextColor(BLUE);
                    nowCall.setText(contactLabel(src)); nowCall.setTextColor(RED);
                }
            }
        });
    }

    private void handleHbpRxVoice(boolean bm, byte[] ambe27) {
        markModeSignal(bm ? 2 : 0);
        if (voc == null || ambe27 == null || ambe27.length < 27) return;
        if (modeMuted(bm ? "bm" : "digital")) return;
        if (!listenMode(bm ? 2 : 0)) return;
        synchronized (voc) {
            short[] combined = new short[480];
            int combinedLen = 0;
            for (int off = 0; off + 9 <= 27; off += 9) {
                try {
                    byte[] a9 = new byte[9];
                    System.arraycopy(ambe27, off, a9, 0, 9);
                    short[] pcm = new short[160];
                    if (voc.decode(a9, pcm)) {
                        System.arraycopy(pcm, 0, combined, combinedLen, pcm.length);
                        combinedLen += pcm.length;
                    }
                } catch (Exception e) {
                    log("语音解码播放异常: " + e.getClass().getSimpleName());
                }
            }
            if (combinedLen > 0 && !rxPlaybackBlocked()) {
                if (combinedLen == combined.length) audio.playDmr(combined);
                else audio.playDmr(java.util.Arrays.copyOf(combined, combinedLen));
            }
        }
    }

    private void handleHbpRxEnd(boolean bm, int src, int dst, int seconds) {
        clearModeSignal(bm ? 2 : 0);
        int finalSec = rxActive && seconds <= 0 && rxCallStart > 0
                ? (int)((System.currentTimeMillis() - rxCallStart) / 1000) : seconds;
        int finalTg = bm ? dst : (rxCallDst == 0 ? dst : rxCallDst);
        rxActive = false; rxCallStart = 0;
        ui.post(() -> {
            setLastCall(bm ? 2 : 0, contactLabel(src));
            if (bm) {
                addBmLog("接收", contactLabel(src), finalTg, finalSec);
                bmCallHoldUntil = System.currentTimeMillis() + 10000L;
                if (bmState != null) { bmState.setText("空闲"); bmState.setTextColor(SUB); }
                if (bmCallLine != null) { bmCallLine.setText(contactLabel(src)); bmCallLine.setTextColor(TXT); }
                setBmStatus(true, false, null);
                if (bmCallResetTask != null) ui.removeCallbacks(bmCallResetTask);
                bmCallResetTask = () -> {
                    bmCallResetTask = null;
                    if (bmCallLine != null && bmCallLine.getCurrentTextColor() != RED) {
                        bmCallLine.setText(bmHbp != null && bmHbp.isConnected() ? "等待呼叫" : "未登录");
                        bmCallLine.setTextColor(TXT);
                    }
                };
                ui.postDelayed(bmCallResetTask, 10000L);
            } else {
                if (page == PAGE_DIGITAL) {
                    nowState.setText("空闲"); nowState.setTextColor(SUB);
                    nowCall.setText(contactLabel(src)); nowCall.setTextColor(TXT); nowTimer.setText("");
                }
                if (dmrCallResetTask != null) ui.removeCallbacks(dmrCallResetTask);
                dmrCallResetTask = () -> {
                    dmrCallResetTask = null;
                    if (nowCall.getCurrentTextColor() != RED) {
                        nowCall.setText(hbp != null && hbp.isConnected() ? "等待呼叫" : "未连接");
                        nowCall.setTextColor(TXT);
                    }
                };
                ui.postDelayed(dmrCallResetTask, 10000L);
                addLog("接收", contactName(src), finalTg, finalTg == 460451 ? 2 : 1, finalSec);
            }
        });
    }

    private void handleHbpLog(boolean bm, String s) { log((bm ? "BM " : "DMR ") + s); }

    @Override public void onFmoState(int st, String text) {
        try {
        ui.post(() -> {
            if (fmoStatus != null) fmoStatus.setText("FMO: " + text);
            if (fmoCallState != null) {
                if (st == 2) { fmoCallState.setText("空闲"); fmoCallState.setTextColor(SUB); }
                else if (st == 3) { fmoCallState.setText("连接失败"); fmoCallState.setTextColor(RED); }
                else { fmoCallState.setText("未登录"); fmoCallState.setTextColor(SUB); }
            }
            if (fmoCallLine != null) {
                if (st == 2 && "未登录".equals(fmoCallLine.getText().toString())) { fmoCallLine.setText("等待呼叫"); fmoCallLine.setTextColor(TXT); }
                else if (st != 2) { fmoCallLine.setText("未登录"); fmoCallLine.setTextColor(TXT); }
            }
            if (fmoNetworkLine != null) fmoNetworkLine.setText("当前网络: BY2AA-FMO · " + (st == 2 ? "已登录" : (st == 3 ? "连接失败" : "未登录")));
            updateFmoUi();
            // 只在 FOM 页面时才用 FMO 状态覆盖顶部栏，避免在电台页误导用户以为 DMR 掉线
            if (st == 2) pushPresence();
            if (mode == 1) statusText.setText(st == 2 ? "FOM 已连接" : (st == 3 ? "FOM 连接失败" : text));
            if (st == 3 && fmoWanted) ui.postDelayed(MainActivity.this::startFmoIfAuto, 6000);
        });
        } catch (Throwable t) { log("FMO 状态处理异常: " + t); }
    }
    @Override public void onFmoVoice(int uid, String callsign, short[] pcm) {
        try {
            
            if (txing && "analog".equals(activePttTarget)) return;
            markModeSignal(1);
            final String who = callsign == null ? "" : callsign.trim();
            safeUi(() -> { markFmoSpeaker(uid); showFmoIncoming(who); });
            // FMO 已连接即接收：不再依赖当前页面/上次通信模式，避免收得到却不播。
            if (pcm != null && !modeMuted("analog")) {
                fmoRxFrames++;
                if (fmoRxFrames % 5 == 0) ui.post(this::fmoUpdateDiag);
                enqueueFmo(pcm);
            }
        } catch (Throwable t) { log("FMO 语音处理异常: " + t); }
    }
    @Override public void onFmoOnline(java.util.List<FmoClient.User> users) {
        safeUi(() -> renderOnline(users));
    }
    @Override public void onFmoAppOnline(java.util.List<FmoClient.AppUser> users) {
        // 网络页在线用户只以当前语音房间的 WebSocket 成员为准。
    }
    @Override public void onHqVoice(int dmrId, String callsign, short[] pcm) {
        try {
        String speakerKey = (callsign == null || callsign.trim().isEmpty()) ? String.valueOf(dmrId) : callsign.trim();
        boolean suppressReleasedTail = hqUiReleased && !hqRemoteActive
                && speakerKey.equals(hqReleasedPeer);
        if (!suppressReleasedTail && !hqReleasedPeer.isEmpty() && !speakerKey.equals(hqReleasedPeer)) {
            hqUiReleased = false;
            hqReleasedPeer = "";
        }
        if (!speakerKey.equals(lastHqPlaybackSpeaker)) {
            lastHqPlaybackSpeaker = speakerKey;
        }
        boolean dstarVoice = "dstar".equals(activeVoiceKind);
        int voiceMode = dstarVoice ? 4 : 3;
        String muteKey = dstarVoice ? "dstar" : "network";
        if (!suppressReleasedTail) {
            try { if (callsign != null && !callsign.trim().isEmpty()) setLastCall(voiceMode, callsign); } catch (Throwable ignored) {}
        }
        if (hqActive && pcm != null && listenMode(voiceMode)) {
            // Never start an AudioTrack while the local microphone is transmitting.
            if (rxPlaybackBlocked()) return;
            markModeSignal(voiceMode);
            if (duplexActive) {
                touchDuplex();
                if (!hqPlaybackReady && hqAudio != null) startHqPlaybackAsync();
                if (!modeMuted(muteKey) && hqAudio != null && !rxPlaybackBlocked()) {
                    final short[] frame = pcm;
                    runAudioIo(() -> { if (hqAudio != null) hqAudio.play(frame); });
                    hqReceivePlaybackDirty = true;
                }
                if (hqStatus != null) setHqStatus("状态: 双工通话中 · " + duplexPeerName + " · 点击结束");
                return;
            }
            long now = System.currentTimeMillis();
            hqLastRxMs = now;
            hqRemoteLastAt = now;
            if (!suppressReleasedTail) {
                hqRemoteActive = true;
                if (hqRemoteResetTask == null) scheduleHqRemoteReset();
                if (hqRxStart == 0) {
                    hqRxStart = now;
                    hqRxPeer = callsign == null ? String.valueOf(dmrId) : callsign;
                    hqLastPeer = hqRxPeer;
                    hqCallHoldUntil = 0;
                    if (hqCallResetTask != null) { ui.removeCallbacks(hqCallResetTask); hqCallResetTask = null; }
                    if (hqStatus != null) setHqStatus("状态: 接收中 · " + hqRxPeer);
                    refreshNetworkCallBar();
                }
            }
            // A PTT release ends the callsign/UI immediately, while already queued
            // frames are still played here so the last syllable is never cut.
            if (!modeMuted(muteKey) && hqAudio != null) {
                if (!hqPlaybackReady) startHqPlaybackAsync();
                final short[] frame = pcm;
                runAudioIo(() -> { if (hqAudio != null) hqAudio.play(frame); });
                hqReceivePlaybackDirty = true;
            }
            if (!suppressReleasedTail) scheduleHqRxEnd();
        }
        } catch (Throwable t) { log("网络接收处理异常: " + t); }
    }

    private void endRemoteNetworkReceiveUiNow(String callsign) {
        String peer = callsign == null ? "" : callsign.trim();
        hqUiReleased = true;
        hqReleasedPeer = peer;
        hqRemoteActive = false;
        hqRxStart = 0;
        hqRxPeer = "";
        hqRemoteLastAt = 0;
        hqCallHoldUntil = 0;
        if (hqRemoteResetTask != null) { ui.removeCallbacks(hqRemoteResetTask); hqRemoteResetTask = null; }
        if (hqRxEndTask != null) { ui.removeCallbacks(hqRxEndTask); hqRxEndTask = null; }
        if (hqCallResetTask != null) { ui.removeCallbacks(hqCallResetTask); hqCallResetTask = null; }
        clearModeSignal(3);
        clearModeSignal(4);
        if (hqCallState != null) { hqCallState.setText("空闲"); hqCallState.setTextColor(SUB); }
        if (hqCallLine != null) { hqCallLine.setText("等待呼叫"); hqCallLine.setTextColor(TXT); }
        setHqStatus(hqActive ? "状态: 已连接 · " + hqName() + " · 点击断开" : "状态: 未连接");
        refreshNetworkCallBar();
        // Deliberately do not flush hqAudio/hqReceiveGeneration here. Late decoded
        // frames remain audible but cannot reactivate the callsign UI.
    }

    private void scheduleHqRemoteReset() {
        if (hqRemoteResetTask != null) ui.removeCallbacks(hqRemoteResetTask);
        hqRemoteResetTask = () -> {
            hqRemoteResetTask = null;
            if (!hqRemoteActive) return;
            long idle = System.currentTimeMillis() - hqRemoteLastAt;
            if (idle < 2000L) {
                hqRemoteResetTask = this::scheduleHqRemoteReset;
                ui.postDelayed(hqRemoteResetTask, 2000L - idle);
                return;
            }
            log("网络接收看门狗复位 idle=" + idle);
            clearNetworkReceiveIfIdle();
            if (hqLastPeer.length() > 0) hqCallHoldUntil = System.currentTimeMillis() + 10000L;
            if (hqStatus != null) setHqStatus(hqActive
                    ? "状态: 已连接 · " + hqName() + " · 点击断开" : "状态: 未连接");
            refreshNetworkCallBar();
        };
        ui.postDelayed(hqRemoteResetTask, 2000L);
    }

    private void clearNetworkReceiveIfIdle() {
        hqUiReleased = false;
        hqReleasedPeer = "";
        // A presence snapshot with no tx holder is authoritative for receive UI.
        // Never let stale local tx flags keep "接收中" or the green nav signal alive.
        hqFlushPlayback();
        clearModeSignal(3);
        clearModeSignal(4);
        hqReceiveGeneration++;
        hqRemoteActive = false;
        hqRxStart = 0;
        hqRxPeer = "";
        hqLastPeer = "";
        hqLastRxMs = 0;
        hqRemoteLastAt = 0;
        lastHqPlaybackSpeaker = "";
        hqCallHoldUntil = 0;
        if (hqRemoteResetTask != null) {
            ui.removeCallbacks(hqRemoteResetTask);
            hqRemoteResetTask = null;
        }
        if (hqRxEndTask != null) { ui.removeCallbacks(hqRxEndTask); hqRxEndTask = null; }
        if (hqCallResetTask != null) { ui.removeCallbacks(hqCallResetTask); hqCallResetTask = null; }
        if (hqCallState != null) {
            hqCallState.setText("空闲");
            hqCallState.setTextColor(SUB);
        }
        if (hqCallLine != null) {
            hqCallLine.setText("等待呼叫");
            hqCallLine.setTextColor(TXT);
        }
        refreshNetworkCallBar();
    }

    private void handleNetworkPttState(int st, String callsign, String message) {
        if (duplexActive) return;
        // 多模互通会把本机在 NXDN/P25/YSF/DMR 里的语音转发进网络频道，
        // 频道随后把这条语音当成“别人正在发射”回报给本机；
        // 以前这里会把手机关掉的全局 txing 清掉，导致模式里刚按下 PTT 就断（红一下就灭）。
        // 模式正在发射时，网络频道的 PTT 状态一律不影响它。
        if (activePttTarget != null && !"network".equals(activePttTarget)) return;
        if (st == 2 || st == 3) markModeSignal(3);
        if (st == 1) {
            if (hqTxRequested) {
                clearNetworkReceiveIfIdle();
                startNetworkMic();
                beginNetworkTransmit();
            }
            return;
        }
        if (st == 2) {
            pttToneBusy();
            hqRemoteActive = false;
            if (hqRemoteResetTask != null) { ui.removeCallbacks(hqRemoteResetTask); hqRemoteResetTask = null; }
            hqTxRequested = false;
            hqPttGranted = false;
            clearHqPttPreroll();
            txing = false; txCallStart = 0; hqTxFrames = 0;
            String who = callsign == null ? "" : callsign.trim();
            clearNetworkReceiveIfIdle();
            if (hqStatus != null) setHqStatus("状态: " + (who.isEmpty() ? "频道忙" : who + " 正在发射"));
            if (page == PAGE_NETWORK) { nowState.setText("空闲"); nowTimer.setText(""); }
            toast(who.isEmpty() ? "频道正在被占用，请稍后再按" : who + " 正在发射，请稍后再按");
            return;
        }
        if (st == 3) {
            if (hqTxRequested || txing) stopNetworkPttRequest();
            hqUiReleased = false;
            hqReleasedPeer = "";
            if (hqStatus != null) setHqStatus("状态: " + callsign + " 正在发射");
            hqLastPeer = callsign == null ? "" : callsign;
            hqRemoteActive = true;
            hqRemoteLastAt = System.currentTimeMillis();
            hqCallHoldUntil = 0;
            scheduleHqRemoteReset();
            if (hqCallState != null) { hqCallState.setText("接收中"); hqCallState.setTextColor(BLUE); }
            if (hqCallLine != null) { hqCallLine.setText(callsign); hqCallLine.setTextColor(RED); }
            return;
        }
        if (st == 0) {
            boolean selfReleased = message != null && message.contains("本机发射已释放");
            if (hqTxRequested || txing) stopNetworkPttRequest();
            if (selfReleased) {
                hqFlushPlayback();
                clearNetworkReceiveIfIdle();
            } else {
                // Remote PTT release: clear callsign now, keep the receive queue
                // draining so the final syllables still play.
                endRemoteNetworkReceiveUiNow(callsign);
            }
            if (hqStatus != null && !selfReleased) setHqStatus(hqActive ? "状态: 已连接 · " + hqName() + " · 点击断开" : "状态: 未连接");

        }
    }

    private void showDuplexMenu(FmoClient.AppUser u) {
        if (u != null) showDuplexMenu(u.callsign, u.dmrId);
    }

    private void showDuplexMenu(String callsign, int dmrId) {
        if (effectiveNetRoom().startsWith("dstar:")) { toast("D-Star 使用 PTT 发射"); return; }
        if (dmrId == p.dmrId()) return;
        if (!hqActive || bmNet == null || !bmNet.isRunning()) { toast("请先连接网络频道"); return; }
        if (duplexBusy()) { toast(duplexActive ? "当前已在双工通话中" : "已有双工呼叫正在进行"); return; }
        new AlertDialog.Builder(this)
                .setTitle("双工通话")
                .setMessage("是否呼叫 " + callsign + "（DMR ID " + dmrId + "）？")
                .setPositiveButton("呼叫", (d, w) -> initiateDuplexCall(callsign, dmrId))
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean duplexBusy() {
        return duplexActive || duplexIncoming || duplexOutgoing
                || (bmNet != null && bmNet.isDuplexPending());
    }

    private void initiateDuplexCall(String callsign, int dmrId) {
        if (effectiveNetRoom().startsWith("dstar:")) { toast("D-Star 使用 PTT 发射"); return; }
        if (dmrId == p.dmrId()) return;
        if (!hqActive || bmNet == null || !bmNet.isRunning()) { toast("请先连接网络频道"); return; }
        if (duplexBusy()) { toast(duplexActive ? "当前已在双工通话中" : "已有双工呼叫正在进行"); return; }
        if (!bmNet.requestDuplex(dmrId)) { toast("呼叫请求发送失败"); return; }
        duplexOutgoing = true;
        duplexIncoming = false;
        duplexPeerId = dmrId;
        duplexPeerName = callsign == null ? String.valueOf(dmrId) : callsign;
        if (hqHangupBtn != null) {
            hqHangupBtn.setText("取消呼叫");
            hqHangupBtn.setVisibility(View.VISIBLE);
        }
        setHqStatus("状态: 正在呼叫 · " + duplexPeerName);
        refreshNetworkCallBar();
        toast("正在呼叫 " + duplexPeerName);
    }

    private void handleDuplexState(int st, int peerDmrId, String peerCallsign, String message) {
        if (st == 1 || st == 2 || st == 3) markModeSignal(3);
        String peer = peerCallsign == null || peerCallsign.trim().isEmpty()
                ? String.valueOf(peerDmrId) : peerCallsign;
        if (st == 1) {
            stopDuplexRingtone();
            dismissDuplexDialog();
            duplexActive = true;
            duplexIncoming = false;
            duplexOutgoing = false;
            duplexPeerId = peerDmrId;
            duplexPeerName = peer;
            touchDuplex();
            startDuplexAudio(peerDmrId, peer);
            return;
        }
        if (st == 2) {
            if (duplexActive || duplexOutgoing || duplexIncoming) {
                if (bmNet != null) bmNet.answerDuplex(false);
                toast("当前正忙，已挂断");
                return;
            }
            duplexIncoming = true;
            duplexOutgoing = false;
            duplexPeerId = peerDmrId;
            duplexPeerName = peer;
            startDuplexRingtone();
            if (hqHangupBtn != null) {
                hqHangupBtn.setText("挂断来电");
                hqHangupBtn.setVisibility(View.VISIBLE);
            }
            setHqStatus("状态: 来电 · " + peer);
            refreshNetworkCallBar();
            dismissDuplexDialog();
            final String incomingPeer = peer;
            duplexDialog = new AlertDialog.Builder(this)
                    .setTitle("双工来话")
                    .setMessage("呼叫人：" + incomingPeer + "\n正在向你发起双工通话")
                    .setCancelable(false)
                    .setPositiveButton("接听", (d, w) -> {
                        duplexDialog = null;
                        stopDuplexRingtone();
                        duplexIncoming = false;
                        if (hqHangupBtn != null) hqHangupBtn.setText("正在接通…");
                        setHqStatus("状态: 正在接通 · " + incomingPeer);
                        refreshNetworkCallBar();
                        if (bmNet != null) bmNet.answerDuplex(true);
                    })
                    .setNegativeButton("挂断", (d, w) -> {
                        duplexDialog = null;
                        stopDuplexRingtone();
                        duplexIncoming = false;
                        if (bmNet != null) bmNet.answerDuplex(false);
                        stopDuplexAudio();
                        showDuplexNotice("已挂断来电");
                    })
                    .show();
            return;
        }
        if (st == 3) {
            duplexOutgoing = true;
            duplexIncoming = false;
            duplexPeerId = peerDmrId;
            duplexPeerName = peer;
            if (hqHangupBtn != null) {
                hqHangupBtn.setText("取消呼叫");
                hqHangupBtn.setVisibility(View.VISIBLE);
            }
            setHqStatus("状态: 正在呼叫 · " + peer);
            refreshNetworkCallBar();
            return;
        }

        String reason = message == null ? "" : message;
        boolean wasActive = duplexActive;
        boolean wasIncoming = duplexIncoming;
        boolean wasOutgoing = duplexOutgoing;
        stopDuplexAudio();
        if (!(wasActive || wasIncoming || wasOutgoing)) return;
        String notice;
        if ("declined".equals(reason)) notice = peer + " 拒绝双工通话";
        else if ("busy".equals(reason)) notice = "对方正忙，无法接听";
        else if ("not_found".equals(reason)) notice = "对方已离线";
        else if ("timeout".equals(reason)) notice = wasIncoming ? "未接来电" : "无人接听";
        else if ("cancelled".equals(reason)) notice = wasIncoming ? "对方已取消双工通话" : "呼叫已取消";
        else if ("disconnected".equals(reason)) notice = "连接已断开，双工通话结束";
        else notice = wasActive ? "双工通话已结束" : "双工呼叫已结束";
        showDuplexNotice(notice);
    }

    private void startDuplexRingtone() {
        if (duplexRingtone == null) duplexRingtone = new NokiaRingtone(this);
        try { duplexRingtone.start(); } catch (Throwable ignored) {}
    }

    private void stopDuplexRingtone() {
        if (duplexRingtone != null) {
            try { duplexRingtone.stop(); } catch (Throwable ignored) {}
        }
    }

    private void dismissDuplexDialog() {
        if (duplexDialog != null) {
            try { duplexDialog.dismiss(); } catch (Throwable ignored) {}
            duplexDialog = null;
        }
    }

    private void showDuplexNotice(String text) {
        String notice = text == null || text.isEmpty() ? "双工呼叫已结束" : text;
        toast(notice);
        if (hqStatus != null) setHqStatus("状态: " + notice);
        ui.postDelayed(() -> {
            if (!duplexBusy() && hqActive && hqStatus != null) {
                setHqStatus("状态: 已连接 · " + hqName() + " · 点击断开");
                refreshNetworkCallBar();
            }
        }, 3500L);
    }

    private void startDuplexAudio(int peerDmrId, String peerCallsign) {
        stopDuplexRingtone();
        duplexActive = true;
        touchDuplex();
        duplexIncoming = false;
        duplexOutgoing = false;
        duplexPeerId = peerDmrId;
        duplexPeerName = peerCallsign == null ? String.valueOf(peerDmrId) : peerCallsign;
        runAudioIo(() -> {
            if (!duplexActive) return;
            try { audio.stop(); } catch (Throwable ignored) {}
            audioReady = false;
            boolean ok = hqAudio != null && hqAudio.startMic(frame -> {
                if (!duplexActive || netOpus == null) return;
                byte[] opusFrame = netOpus.encode(frame);
                if (opusFrame != null) bmNet.sendVoice(opusFrame);
            });
            if (!duplexActive) {
                if (hqAudio != null) { try { hqAudio.startMic(null); } catch (Throwable ignored) {} }
                return;
            }
            ui.post(() -> {
                if (!duplexActive) return;
                if (!ok) {
                    toast("双工通话音频初始化失败");
                    endDuplexCall();
                    return;
                }
                nowState.setText("双工通话中"); nowCall.setText(duplexPeerName); nowTimer.setText("");
                if (hqHangupBtn != null) {
                    hqHangupBtn.setText("挂断双工通话");
                    hqHangupBtn.setVisibility(View.VISIBLE);
                }
                setHqStatus("状态: 双工通话中 · " + duplexPeerName + " · 点击挂断");
                refreshNetworkCallBar();
                addNetLog("双工", duplexPeerName, hqName(), 0);
            });
        });
    }

    /** [3.2.52] 双工状态卡死看门狗：30 秒无音频/状态自动结束。 */
    private void touchDuplex() {
        duplexLastActivityAt = System.currentTimeMillis();
        ensureDuplexWatchdog();
    }

    private void ensureDuplexWatchdog() {
        if (duplexWatchdogTask != null) return;
        duplexWatchdogTask = () -> {
            duplexWatchdogTask = null;
            if (!duplexActive) return;
            long idle = System.currentTimeMillis() - duplexLastActivityAt;
            if (idle > 30000L) {
                log("双工看门狗超时 idle=" + idle);
                try { if (bmNet != null) bmNet.endDuplex(); } catch (Throwable ignored) {}
                stopDuplexAudio();
                toast("双工通话超时，已自动结束");
                return;
            }
            ensureDuplexWatchdog();
        };
        ui.postDelayed(duplexWatchdogTask, 5000L);
    }

    private void stopDuplexAudio() {
        if (duplexWatchdogTask != null) { ui.removeCallbacks(duplexWatchdogTask); duplexWatchdogTask = null; }
        stopDuplexRingtone();
        dismissDuplexDialog();
        duplexActive = false;
        duplexIncoming = false;
        duplexOutgoing = false;
        duplexPeerId = 0;
        duplexPeerName = "";
        hqMicOffAsync();
        if (!audioReady && needsLegacyAudio()) startAudioAsync();
        nowState.setText("空闲"); nowTimer.setText("");
        if (hqHangupBtn != null) {
            hqHangupBtn.setVisibility(View.GONE);
            hqHangupBtn.setText("挂断双工通话");
        }
        if (hqStatus != null && hqActive) setHqStatus("状态: 已连接 · " + hqName() + " · 点击断开");
        refreshNetworkCallBar();
    }

    private void endDuplexCall() {
        if (duplexIncoming) {
            if (bmNet != null) bmNet.answerDuplex(false);
            stopDuplexAudio();
            showDuplexNotice("已挂断来电");
            return;
        }
        if (bmNet != null) bmNet.endDuplex();
        stopDuplexAudio();
    }

 /** Leave/switch room must cancel ringing, pending invitation and active duplex first. */
    private void cancelDuplexForNavigation() {
        if (!duplexBusy()) return;
        if (duplexIncoming && bmNet != null) bmNet.answerDuplex(false);
        else if (bmNet != null) bmNet.endDuplex();
        stopDuplexAudio();
    }

    private void scheduleHqRxEnd() {
        if (hqRxEndTask != null) ui.removeCallbacks(hqRxEndTask);
        hqRxEndTask = () -> {
            if (hqRxStart == 0) return;
            long idle = System.currentTimeMillis() - hqLastRxMs;
            if (idle < 650) { ui.postDelayed(hqRxEndTask, 650 - idle); return; }
            int sec = Math.max(1, (int)((hqLastRxMs - hqRxStart + 999) / 1000));
            addNetLog("接收", hqRxPeer, hqName(), sec);
            hqLastPeer = hqRxPeer;
            hqCallHoldUntil = System.currentTimeMillis() + 10000L;
            hqRxStart = 0; hqRxPeer = "";
            refreshNetworkCallBar();
            if (hqCallResetTask != null) ui.removeCallbacks(hqCallResetTask);
            hqCallResetTask = () -> { hqCallResetTask = null; refreshNetworkCallBar(); };
            ui.postDelayed(hqCallResetTask, 10000L);
            if (hqStatus != null && hqActive && !duplexActive) setHqStatus("状态: 已连接 · " + hqName() + " · 点击断开");
        };
        ui.postDelayed(hqRxEndTask, 700);
    }
    @Override public void onFmoLog(String s) { log(s); }

 /** 93 控制台下发的网络频道表（topic: FMO/QSO/ARCH-ROOMS） */
    @Override public void onRooms(String json) { applyRoomsJson(json); }

 /** 启动后直接拉取语音服务器在线频道，避免首次进入Net时列表为空或很慢。 */
    private void startRoomPolling() {
        if (roomPollRun) return;
        roomPollRun = true;
        new Thread(() -> {
            while (roomPollRun) {
                try { fetchNetworkRooms(); } catch (Throwable t) { log("频道人数更新失败: " + t.getMessage()); }
                try { if (bmNet != null && bmNet.isRunning()) bmNet.requestPresence(); } catch (Throwable ignored) {}
                try { Thread.sleep(30000); } catch (InterruptedException e) { return; }
            }
        }, "voice-room-poller").start();
    }

    private void fetchNetworkRooms() throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://hljrsa.cn:50113/api/rooms").openConnection();
        c.setConnectTimeout(3500);
        c.setReadTimeout(5000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); return; }
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close(); c.disconnect();
        applyRoomsJson(new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private void applyRoomsJson(String json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("rooms")) return;
            com.google.gson.JsonArray arr = o.getAsJsonArray("rooms");
            java.util.ArrayList<String[]> next = new java.util.ArrayList<>();
            final String apiRoom = effectiveNetRoom();
            Boolean apiBusy = null;
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonObject r = arr.get(i).getAsJsonObject();
                String id = r.has("id") ? r.get("id").getAsString() : "";
                String name = r.has("name") ? r.get("name").getAsString() : id;
                if (id.equals(apiRoom)) {
                    apiBusy = r.has("txHolder") && !r.get("txHolder").isJsonNull();
                }
                boolean en = !r.has("enabled") || r.get("enabled").getAsBoolean();
                if (r.has("targets") && r.getAsJsonArray("targets").size() > 0) {
                    boolean hit = false;
                    for (int k = 0; k < r.getAsJsonArray("targets").size(); k++)
                        if (r.getAsJsonArray("targets").get(k).getAsInt() == p.dmrId()) { hit = true; break; }
                    if (!hit) continue;
                }
                if (!en || id.length() == 0) continue;
                // [FIX 3.2.29] 公开版屏蔽：服务端 publicHidden 标记 + 438.500中继/省红十字 双保险
                boolean publicHidden = r.has("publicHidden") && !r.get("publicHidden").isJsonNull()
                        && r.get("publicHidden").getAsBoolean();
                if (!isRoomVisible(id, name, publicHidden)) continue;
                int online = r.has("online") ? r.get("online").getAsInt() : onlineForRoom(id);
                next.add(new String[]{ id, name, String.valueOf(Math.max(0, online)) });
            }
            if (next.isEmpty()) return;
            final java.util.ArrayList<String[]> f = next;
            final Boolean fBusy = apiBusy;
            ui.post(() -> {
                applyNetworkRooms(f);
                if (fBusy != null && !fBusy.booleanValue() && hqActive
                        && apiRoom.equals(effectiveNetRoom())) {
                    // /api/rooms txHolder is authoritative; recover from a lost release.
                    if (hqRemoteActive || hqRxStart > 0) log("API显示频道空闲，清理残留接收状态");
                    clearNetworkReceiveIfIdle();
                }
            });
        } catch (Throwable t) { log("频道表解析失败: " + t); }
    }

    private int onlineForRoom(String id) {
        for (String[] r : NET_ROOMS) if (r.length > 2 && r[0].equals(id)) {
            try { return Integer.parseInt(r[2]); } catch (Exception ignored) {}
        }
        return 0;
    }

    private void applyNetworkRooms(java.util.ArrayList<String[]> f) {
        if (f == null) return;
        // Server room table is the authoritative complete list. Do not merge
        // old rooms back in: targets/publicHidden/disabled filtering must stick.
        java.util.ArrayList<String[]> authoritative = new java.util.ArrayList<>();
        for (String[] r : f) if (r != null && r.length >= 2 && r[0] != null) authoritative.add(r);
        f = authoritative;
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (String[] r : NET_ROOMS) a.append(r[0]).append('|').append(r[1]).append('|').append(r.length > 2 ? r[2] : "0").append(';');
        for (String[] r : f) b.append(r[0]).append('|').append(r[1]).append('|').append(r.length > 2 ? r[2] : "0").append(';');
        if (a.toString().equals(b.toString())) return;
        String oldRoom = p.netRoom();
        NET_ROOMS = f;
        String keep = effectiveNetRoom();
        p.set("netRoom", keep);
        buildRoomList(); updateModeLabel();
        if (hqActive && !keep.equals(oldRoom)) beginRoomSwitch(keep);
    }

 /** 兼容旧入口：等价于连接/断开当前网络频道 */
    private void toggleHq() { toggleRoom(p.netRoom()); }

    private void hqPttDown() {
        if (!hqActive || bmNet == null || !bmNet.isRunning()) return;
        hqTxStart = System.currentTimeMillis();
        bmNet.setMic(true);
        pttToneStart();
        scheduleTxTimeout();
        nowState.setText("Net发射中"); nowCall.setText(p.callsign());
        refreshNetworkCallBar();
    }

    private void hqPttUp() {
        if (bmNet != null) bmNet.setMic(false);
        pttToneStop();
        cancelTxTimeout();
        int sec = hqTxStart == 0 ? 0 : (int)((System.currentTimeMillis() - hqTxStart) / 1000);
        hqTxStart = 0;
        addNetLog("发射", p.callsign(), hqName(), sec);
        nowState.setText("空闲");
        refreshNetworkCallBar();
    }

    private short[] scalePcm(short[] src, float gain) {
        if (src == null) return null;
        short[] out = new short[src.length];
        for (int i = 0; i < src.length; i++) {
            int v = Math.round(src[i] * gain);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            out[i] = (short) v;
        }
        return out;
    }

    private void fmoUpdateDiag() { }
    private void enqueueFmo(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        try {
            if (txing && "analog".equals(activePttTarget)) return;
            long nowMs = System.currentTimeMillis();
            boolean relay = fmo != null && fmo.isRelayActive();
            long gen = fmo == null ? -1L : fmo.relayGeneration();
            if (relay && gen != fmoRelayGeneration) {
                synchronized (fmoQueue) { fmoQueue.clear(); fmoQueuedSamples = 0; }
                fmoRelayGeneration = gen;
                fmoPrebufferMs = FMO_RELAY_PREBUFFER_MS;
                fmoStreamUnderruns = 0;
            } else if (!relay && (lastFmoRx == 0 || nowMs - lastFmoRx > FMO_STREAM_RESET_MS)) {
                if (fmoStreamUnderruns == 0) {
                    fmoPrebufferMs = Math.max(FMO_DIRECT_PREBUFFER_MS + 160, fmoPrebufferMs - FMO_PREBUFFER_STEP_DOWN_MS);
                } else {
                    fmoPrebufferMs = Math.min(FMO_PREBUFFER_MAX_MS, Math.max(FMO_DIRECT_PREBUFFER_MS, fmoPrebufferMs) + FMO_PREBUFFER_STEP_UP_MS);
                }
                fmoStreamUnderruns = 0;
                log("FMO 直连回退缓冲 " + fmoPrebufferMs + "ms");
            }
            if (relay) fmoPrebufferMs = FMO_RELAY_PREBUFFER_MS;
            lastFmoRx = nowMs;
            synchronized (fmoQueue) {
                int maxSamples = FMO_SAMPLE_RATE * FMO_MAX_BUFFER_MS / 1000;
                while (!fmoQueue.isEmpty() && fmoQueuedSamples + pcm.length > maxSamples) {
                    short[] old = fmoQueue.removeFirst();
                    fmoQueuedSamples -= old.length;
                }
                fmoQueue.addLast(pcm);
                fmoQueuedSamples += pcm.length;
                if (!fmoPlayerRun) {
                    fmoPlayerRun = true;
                    try { new Thread(this::fmoPlayLoop, "fmo-jitter").start(); }
                    catch (Throwable t) { fmoPlayerRun = false; log("FMO 播放线程启动失败: " + t); }
                }
            }
        } catch (Throwable t) { log("FMO 入队异常: " + t); }
    }

    private void clearFmoAudio() {
        synchronized (fmoQueue) { fmoQueue.clear(); fmoQueuedSamples = 0; }
        fmoStreamUnderruns = 0;
    }

    private volatile long lastFmoRx = 0L;
    private static final int FMO_SAMPLE_RATE = 8000;
    private static final int FMO_PREBUFFER_MIN_MS = 480;
    private static final int FMO_PREBUFFER_MAX_MS = 2400;
    private static final int FMO_PREBUFFER_STEP_UP_MS = 160;
    private static final int FMO_PREBUFFER_STEP_DOWN_MS = 40;
    private static final int FMO_RELAY_PREBUFFER_MS = 600;   // 覆盖实测 MQTT 抖动，同时总延迟仍远低于旧的 1.8 秒
    private static final int FMO_DIRECT_PREBUFFER_MS = 600;    // 服务器流不可用时的直连回退
    private static final int FMO_MAX_BUFFER_MS = 2400;
    private static final int FMO_STREAM_RESET_MS = 1800;
    private static final int FMO_UNDERRUN_ACTIVE_MS = 600;
    private static final int FMO_RELAY_REBUFFER_MS = 600;
    private static final short[] FMO_SILENCE_20 = new short[160];   // 20ms @8kHz，欠载时补静音
    private static final float FMO_RX_SCALE = 0.72f;   // FMO 下行音量，独立于 DMR/网络模式
    private static final float FMO_TX_SCALE = 1.10f;   // 不再强推 6dB，避免语音削波

 /**
 * FMO 抖动缓冲（固定节拍 + 欠载补静音）。
 *
 * 实测对端（BY2AA 房间）每包帧数在 1~3 之间跳（408/752/1096 字节），到达间隔并不均匀。
 * 旧实现用「队列不够就 sleep(10) 干等」，一旦欠载播放就整段停住，下一包到了再补，听感就是断续。
 * 现在改成：以 20ms 为一个固定节拍连续输出，按 600ms 音频时间预缓冲；
 * 取不到就立刻输出静音，不再阻塞等待网络包；只有连续 2.5 秒没有任何数据才结束本次流。
 */
    private void fmoPlayLoop() {
        long next = System.currentTimeMillis();
        boolean primed = false;
        short[] frame = null;
        int frameOffset = 0;
        short[] out = new short[160];
        short[] fmoOut = new short[160];
        short[] src = new short[160];
        short[] lastFrame = new short[160];
        float plcGain = 1f;
        long playerRelayGen = -1L;
        while (fmoPlayerRun) {
          try {
            boolean relay = fmo != null && fmo.isRelayActive();
            long relayGen = fmo == null ? -1L : fmo.relayGeneration();
            int got = 0;
            boolean underrun = false;
            synchronized (fmoQueue) {
                if (relay && relayGen != playerRelayGen) {
                    fmoQueue.clear(); fmoQueuedSamples = 0; frame = null; frameOffset = 0;
                    primed = false; playerRelayGen = relayGen; fmoPrebufferMs = FMO_RELAY_PREBUFFER_MS;
                }
                int remaining = frame == null ? 0 : Math.max(0, frame.length - frameOffset);
                int buffered = fmoQueuedSamples + remaining;
                if (!primed && buffered >= FMO_SAMPLE_RATE * fmoPrebufferMs / 1000) {
                    primed = true;
                    log("FMO 缓冲就绪 " + (buffered * 1000 / FMO_SAMPLE_RATE) + "ms（目标 " + fmoPrebufferMs + "ms）");
                }
                if (primed) {
                    while (got < 160) {
                        if (frame == null || frameOffset >= frame.length) {
                            if (fmoQueue.isEmpty()) break;
                            frame = fmoQueue.pollFirst();
                            fmoQueuedSamples -= frame.length;
                            frameOffset = 0;
                        }
                        int n = Math.min(160 - got, frame.length - frameOffset);
                        System.arraycopy(frame, frameOffset, src, got, n);
                        frameOffset += n;
                        got += n;
                        if (frameOffset >= frame.length) { frame = null; frameOffset = 0; }
                    }
                    underrun = got < 160;
                }
            }
            fmoPrimed = primed;
            if (!primed) {
                long d = next - System.currentTimeMillis();
                if (d > 0) { try { Thread.sleep(Math.min(d, 20)); } catch (InterruptedException e) { return; } }
                else next = System.currentTimeMillis();
                continue;
            }

            long rxNow = System.currentTimeMillis();
            if (relay && got < 160 && lastFmoRx > 0 && rxNow - lastFmoRx > FMO_RELAY_REBUFFER_MS) {
                // 服务器平滑流已经结束：立即回到预缓冲，不再播放尾帧或连续补 PLC。
                primed = false; next = rxNow; continue;
            }

            java.util.Arrays.fill(out, (short)0);
            if (got == out.length) {
                // 完整 20ms：直接播放（唯一正常路径）
                plcGain = 1f;
                System.arraycopy(src, 0, out, 0, out.length);
                System.arraycopy(out, 0, lastFrame, 0, out.length);
            } else if (got > 0) {
                // 部分样本：先播已有，不足部分用上一帧衰减补齐，绝不拉伸变调
                System.arraycopy(src, 0, out, 0, got);
                for (int i = got; i < out.length; i++) out[i] = (short)Math.round(lastFrame[i] * plcGain);
                plcGain *= 0.95f;
                System.arraycopy(out, 0, lastFrame, 0, out.length);
            } else {
                // 完全欠载：重复上一帧并衰减（标准 PLC），而不是插值拉伸
                boolean silent = plcGain < 0.10f;
                for (int i = 0; i < out.length; i++)
                    out[i] = silent ? (short)0 : (short)Math.round(lastFrame[i] * plcGain);
                plcGain *= 0.80f;
            }

            long nowMs = System.currentTimeMillis();
            boolean streamActive = lastFmoRx > 0 && nowMs - lastFmoRx < 600;
            if (primed && underrun && streamActive) {
                fmoUnderruns++;
                fmoStreamUnderruns++;
                if (nowMs - fmoLastUnderrunLog > 1000) {
                    fmoLastUnderrunLog = nowMs;
                    log("FMO 缓冲欠载 total=" + fmoUnderruns + " stream=" + fmoStreamUnderruns);
                }
            }

            for (int i = 0; i < out.length; i++) {
                int v = Math.round(out[i] * FMO_RX_SCALE);
                if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                fmoOut[i] = (short) v;
            }
            // [FIX 3.2.27] only play the simulation/FMO audio while the simulation page is showing
            // (previously it leaked into every other mode, incl. DMR dual-watch)
            if (page == PAGE_ANALOG && !rxPlaybackBlocked()) audio.playFmoRaw(fmoOut);
            if (frame == null && lastFmoRx > 0 && System.currentTimeMillis() - lastFmoRx > FMO_STREAM_RESET_MS) {
                primed = false;
                next = System.currentTimeMillis();
                continue;
            }
            next += 20;
            long d = next - System.currentTimeMillis();
            if (d > 0) { try { Thread.sleep(d); } catch (InterruptedException e) { return; } }
            else if (d < -400) next = System.currentTimeMillis();
          } catch (Throwable t) {
            log("FMO 播放异常: " + t);
            try { Thread.sleep(20); } catch (InterruptedException ie) { return; }
          }
        }
    }
    private boolean isDigitalHistory(String text) {
        if (text == null || text.length() == 0) return false;
        String[] parts = text.split("\u001F", -1);
        String group = parts.length >= 4 ? parts[3] : text;
        return !group.contains("FMO") && !group.contains("438.500") && !group.contains("438500")
                && !group.contains("模拟") && !group.contains("网络")
                && !group.contains("BrandMeister");
    }

    private void addLog(String direction, String identity, int tg, int slot, int sec) {
        if (logBox == null) return;
        if (slot == 0 && tg == 438500) return; // 模拟/FMO 记录不进入数字中继
        String group = tg == 438500 ? "FMO 438.500" : tgName(tg);
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String text = time + "\u001F" + identity + "\u001F" + direction + "\u001F" + group + "\u001F" + sec + "s";
        if (!isDigitalHistory(text)) return;
        addLogView(text);
        callHistory.add(0, text);
        while (callHistory.size() > visibleRows) callHistory.remove(callHistory.size() - 1);
        saveHistory();
    }

    private void addNetLog(String direction, String peer, String room, int sec) {
        if (netLogBox == null) return;
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String text = time + "\u001F" + peer + "\u001F" + direction + "\u001F" + sec + "s";
        ui.post(() -> addNetLogView(text));
    }

    private void addNetLogView(String text) {
        if (netLogBox == null || text == null || text.length() == 0) return;
        if (netLogBox.getChildCount() == 1 && netLogBox.getChildAt(0) instanceof TextView
                && "暂无网络通话记录".contentEquals(((TextView) netLogBox.getChildAt(0)).getText())) {
            netLogBox.removeAllViews();
        }
        String[] p4 = text.split("\u001F", -1);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(10), dp(8));
        r.setBackground(roundBg(text.contains("发射") ? 0x142563EB : 0x1222C55E, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(6));
        r.setLayoutParams(rlp);
        float[] w = {0.20f, 0.36f, 0.24f, 0.20f};
        for (int i = 0; i < 4 && i < p4.length; i++) {
            TextView c = tv(p4[i], 12, i == 0 ? SUB : TXT);
            c.setSingleLine(true);
            c.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w[i]));
            if (i == 2) c.setGravity(Gravity.CENTER);
            if (i == 3) c.setGravity(Gravity.END);
            r.addView(c);
        }
        netLogBox.addView(r, 0);
        while (netLogBox.getChildCount() > visibleRows) netLogBox.removeViewAt(netLogBox.getChildCount() - 1);
    }

    private void addLogView(String text) {
        if (logBox == null || text == null || text.length() == 0) return;
        if (text.indexOf('\u001F') > 0) {          // 单行四列：时间 / 呼号 / 组 / 秒数
            String[] p4 = text.split("\u001F", -1);
            if (p4.length >= 5 && "接收".equals(p4[2])) p4[1] = p4[1].replaceAll("\\s*\\(\\d+\\)$", "");
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(dp(8), dp(6), dp(8), dp(6));
            r.setBackground(roundBg(text.contains("发射") ? 0x112563EB : 0x1122C55E, 10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(5));
            r.setLayoutParams(rlp);
            boolean five = p4.length >= 5;
            float[] w = five ? new float[]{0.16f,0.25f,0.15f,0.24f,0.20f}
                    : new float[]{0.20f,0.38f,0.28f,0.14f};
            int cols = five ? 5 : 4;
            for (int i = 0; i < cols && i < p4.length; i++) {
                TextView c = tv(p4[i], 12, i == 0 ? SUB : TXT);
                c.setSingleLine(true);
                c.setEllipsize(android.text.TextUtils.TruncateAt.END);
                c.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, w[i]));
                if ((five && i == 2) || (!five && i == 1)) c.setTextColor(BLUE);
                if (i == cols - 1) c.setGravity(Gravity.END);
                r.addView(c);
            }
            logBox.addView(r, 0);
            while (logBox.getChildCount() > visibleRows) logBox.removeViewAt(logBox.getChildCount() - 1);
            return;
        }
        TextView t = tv(text, 13, TXT);
        t.setLineSpacing(dp(2), 1.0f);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(text.contains(" 发射\n") ? 0x112563EB : 0x1122C55E);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(7));
        t.setLayoutParams(lp);
        logBox.addView(t, 0);
        while (logBox.getChildCount() > visibleRows) logBox.removeViewAt(logBox.getChildCount() - 1);
    }

    private void loadHistory() {
        String raw = p.get("callHistory", "");
        if (raw.length() == 0) return;
        String[] items = raw.split("\u001E", -1);
        callHistory.clear();
        for (int i = 0; i < items.length && callHistory.size() < visibleRows; i++) {
            if (isDigitalHistory(items[i])) callHistory.add(items[i]);
        }
        for (int i = callHistory.size() - 1; i >= 0; i--) addLogView(callHistory.get(i));
        saveHistory();
    }

    private void saveHistory() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < callHistory.size(); i++) {
            String line = callHistory.get(i);
            if (!isDigitalHistory(line)) continue;
            if (b.length() > 0) b.append('\u001E');
            b.append(line);
        }
        p.set("callHistory", b.toString());
    }
    private void notifyAlert(String title, String text) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "arch-alerts", "ARCH 提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(this, "arch-alerts")
                    : new android.app.Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
            if (p.alertVibrate()) b.setVibrate(new long[]{0, 120, 80, 120});
            nm.notify((int)(System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Throwable ignored) {}
    }
 /** 网络线程回调统一走这里：任何异常只记日志，绝不让 App 退出。 */
    private void safeUi(Runnable r) {
        ui.post(() -> { try { r.run(); } catch (Throwable t) { log("UI callback error: " + t); } });
    }

    private void log(String s) {
        android.util.Log.i("ARCH", s);
    }

    private int baseDmrId(int id) {
        return id > 9999999 ? id / 100 : id;
    }

    private String contactLabel(int id) {
        int did = baseDmrId(id);
        String c = contacts == null ? null : contacts.get(did);
        String country = "";
        if (c != null && !c.trim().isEmpty()) country = CountryBook.countryFromCallsign(c);
        country = country == null ? "" : country.trim();
        if (country.isEmpty()) country = CountryBook.country(did);
        country = country == null ? "" : country.trim();
        if ("?".equals(country) || "??".equals(country)) country = "";
        String suffix = country.isEmpty() ? "" : " " + country;
        if (c == null) requestContactRefreshOnMiss();
        return c == null ? String.valueOf(did) + suffix : c + " (" + did + ")" + suffix;
    }


    private String contactName(int id) {
        int did = baseDmrId(id);
        String c = contacts == null ? null : contacts.get(did);
        if (c == null) requestContactRefreshOnMiss();
        return c == null ? String.valueOf(did) : c;
    }

    /** 单次未命中只触发一次后台刷新，避免语音包高频回调反复联网。 */
    private synchronized void requestContactRefreshOnMiss() {
        long now = System.currentTimeMillis();
        if (now - contactMissRefreshAt < 6L * 60L * 60L * 1000L) return;
        contactMissRefreshAt = now;
        if (contacts != null) contacts.refreshAsync(() -> ui.post(this::refreshContactInfo));
    }

    private void updateServerLists() {
        toast("正在从 ARCH 服务器更新 YSF / D-Star / DMR / P25 / NXDN 列表和 NXDN 通讯录...");
        final int[] left = {6};
        final boolean[] changed = {false};
        final Runnable done = () -> {
            synchronized (left) {
                left[0]--;
                if (left[0] > 0) return;
            }
            ui.post(() -> {
                DSTAR_SERVERS = DStarServers.ALL;
                updateDstarSelectors();
                updateYsfSelectors();
                updateP25Selectors();
                updateNxdnSelectors();
                if (bmServerValue != null) bmServerValue.setText(p.bmServerName());
                toast(changed[0] ? "服务器列表已更新" : "服务器列表更新失败，请检查网络");
                log("服务器列表更新完成: YSF=" + YsfServers.count() + " D-Star=" + DSTAR_SERVERS.length + " DMR=" + BmServers.ALL.length + " P25=" + P25Servers.count() + " NXDN=" + NxdnServers.count());
            });
        };
        YsfServers.refresh(this, () -> { changed[0] = true; done.run(); });
        DStarServers.refresh(this, () -> { changed[0] = true; done.run(); });
        BmServers.refresh(this, () -> { changed[0] = true; done.run(); });
        P25Servers.refresh(this, () -> { changed[0] = true; done.run(); });
        NxdnServers.refresh(this, () -> { changed[0] = true; done.run(); });
        NxdnContacts.refresh(this, () -> { changed[0] = true; done.run(); });
    }

    private void refreshContactInfo() {
        if (contactStatus == null) return;
        if (contacts != null && contacts.isLoaded())
            contactStatus.setText("通讯录: 已加载 " + contacts.count() + " 条");
        else
            contactStatus.setText("通讯录: 未加载，暂时显示DMR ID");
    }

 /** 每个账号第一次成功登录协会服务器时自动下载一次通讯录，之后只手动更新。 */
    private void maybeAutoDownloadContacts(final int dmrId, final int essid) {
        if (p == null || contacts == null || dmrId <= 0) return;
        if (p.contactAutoDownloaded(dmrId, essid)) return;
        p.markContactAutoDownloaded(dmrId, essid);
        if (contactStatus != null) contactStatus.setText("通讯录: 首次登录，正在自动下载…");
        contacts.refreshAsync(() -> ui.post(() -> {
            refreshContactInfo();
            if (contacts.lastError() != null) toast("通讯录自动下载失败，请手动更新");
        }));
    }

    private String tgName() { return tgName(p.tg()); }

    private String tgName(int tg) {
        for (int i = 0; i < TGS.length && i < TGNAMES.length; i++)
            if (TGS[i][0] == tg) return TGNAMES[i];
        return "TG" + tg;
    }

    private void loadTalkgroupsFromPrefs() {
        // 启动时先只显示公开四组。授权组必须等账号登录返回 talkgroups 后再出现，
        // 防止旧缓存或旧自定义配置把红十字组/460175组泄露给未授权账号。
        applyTalkgroups(new org.json.JSONArray(), false);
    }

    private void appendDigitalTg(java.util.ArrayList<int[]> ids, java.util.ArrayList<String> names,
                                int id, int slot, String name) {
        for (int[] row : ids) if (row[0] == id) return;
        ids.add(new int[]{id, slot});
        names.add(name);
    }

    /**
     * Digital-relay catalog contract:
     *   base: 460451 TS1, 46002 TS2, 46020 TS1, 46001 TS2
     *   privileged, only when returned by /api/account/login:
     *     10 TS1, 460175 TS2
     * Server talkgroup data is never allowed to change the canonical TG/slot mapping.
     */
    private boolean applyTalkgroups(org.json.JSONArray arr, boolean save) {
        boolean allow10 = false, allow175 = false;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    int id = arr.getJSONObject(i).optInt("id", 0);
                    if (id == 10) allow10 = true;
                    else if (id == 460175) allow175 = true;
                } catch (Throwable ignored) {}
            }
        }
        java.util.ArrayList<int[]> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        if (allow10) appendDigitalTg(ids, names, 10, 1, "红十字组");
        if (allow175) appendDigitalTg(ids, names, 460175, 2, "460175组");
        appendDigitalTg(ids, names, 460451, 1, "哈市组");
        appendDigitalTg(ids, names, 46002, 2, "二区组");
        appendDigitalTg(ids, names, 46020, 1, "广州组");
        appendDigitalTg(ids, names, 46001, 2, "全国组");
        TGS = ids.toArray(new int[0][]);
        TGNAMES = names.toArray(new String[0]);
        boolean found = false;
        for (int i = 0; i < TGS.length; i++) if (TGS[i][0] == p.tg()) { found = true; break; }
        if (!found) p.set("tg", String.valueOf(TGS[0][0]));
        int pos = 0;
        for (int i = 0; i < TGS.length; i++) if (TGS[i][0] == p.tg()) { pos = i; break; }
        p.set("tgSlot", String.valueOf(TGS[pos][1]));
        int mon = 0;
        for (int i = 0; i < TGS.length; i++) if (i != pos) { mon = TGS[i][0]; break; }
        p.set("monitorTg", String.valueOf(mon));
        if (save) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < TGS.length; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(TGS[i][0]).append(",\"slot\":").append(TGS[i][1])
                  .append(",\"name\":\"").append(TGNAMES[i].replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}");
            }
            sb.append(']');
            p.set("talkgroups", sb.toString());
        }
        if (tgRow != null) rebuildTalkgroupButtons();
        updateGroupUI();
        updateModeLabel();
        return true;
    }

    private void rebuildTalkgroupButtons() {
        if (tgRow == null) return;
        tgRow.removeAllViews();
        if (TGS.length == 0) return;
        tgBtns = new Button[TGS.length];
        int rows = (TGS.length + 1) / 2;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (rowIndex > 0) rowLp.setMargins(0, dp(8), 0, 0);
            row.setLayoutParams(rowLp);
            for (int col = 0; col < 2; col++) {
                final int idx = rowIndex * 2 + col;
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setGravity(Gravity.CENTER);
                b.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                b.setMinHeight(dp(52));
                b.setPadding(dp(4), 0, dp(4), 0);
                ts(b, 16);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (col == 0) lp.setMargins(0, 0, dp(4), 0);
                else lp.setMargins(dp(4), 0, 0, 0);
                b.setLayoutParams(lp);
                if (idx < TGS.length) {
                    b.setText(TGNAMES[idx]); // 只写组名，不写时隙/组号
                    b.setOnClickListener(v -> switchGroup(idx));
                    tgBtns[idx] = b;
                } else {
                    b.setText("空白组");
                    b.setTextColor(SUB);
                    b.setBackground(tgButtonBg(false));
                    b.setClickable(false);
                    b.setFocusable(false);
                }
                row.addView(b);
            }
            tgRow.addView(row);
        }
        paintTgButtons();
    }

 /** DMR(HBP) 链路是否处于“已登录且活着”的状态。 */
    private boolean dmrUp() { return hbp != null && hbp.isRunning() && hbp.isConnected(); }
    private boolean bmUp() { return bmHbp != null && bmHbp.isRunning() && bmHbp.isConnected(); }
    private boolean dmrStale() {
        if (hbp == null || !hbp.isRunning()) return false;
        long t = hbp.lastRxMs();
        return t > 0 && System.currentTimeMillis() - t > 12000;
    }
    private boolean bmStale() {
        if (bmHbp == null || !bmHbp.isRunning()) return false;
        long t = bmHbp.lastRxMs();
        return t > 0 && System.currentTimeMillis() - t > 12000;
    }
    private boolean fmoUp() { return fmo != null && fmo.isRunning(); }

    private void autoConnectAll() {
        if (autoConnectSuppressed()) return;
        try {
            if (p.autoDigital() && p.showDigital() && p.dmrAuto() && p.dmrId() > 0
                    && !p.callsign().isEmpty() && p.password().length() >= 6
                    && (!dmrUp() || dmrStale())) connect();
            if (p.autoBm() && p.showBm() && p.dmrServerId() > 0 && !p.bmCallsign().isEmpty() && !p.dmrServerPassword().isEmpty() && (!bmUp() || bmStale())) connectBm();
            if (p.autoAnalog() && p.showAnalog() && p.fmoAuto()
                    && !p.callsign().isEmpty() && p.password().length() >= 6
                    && !fmoUp()) { fmoWanted = true; startFmo(true); }
            if (p.autoNetwork() && p.showNetwork() && !hqActive && !networkConnecting) joinRoom();
            if (p.autoNrl() && p.showNrl() && !nrlActive && !nrlConnecting) connectNrl();
            if (p.autoDstar() && p.showDstar() && dstarDirect != null
                    && dstarDirect.isRunning() && !dstarDirect.isLinkHealthy()) dstarDirect.stop();
            if (p.autoDstar() && p.showDstar() && !dstarActive() && !dstarConnecting && !p.dstarCallsign().isEmpty()) joinDstar();
            autoConnectYsfIfNeeded();
            autoConnectP25IfNeeded();
            autoConnectNxdnIfNeeded();
        } catch (Throwable t) { log("自动连接失败: " + t); }
    }

    private void connectIfAuto() {
        if (!p.autoReconnect()) return;
        if (p.dmrAuto() && !dmrUp() && !p.callsign().isEmpty() && p.dmrId() > 0
                && p.password().length() >= 6) connect();
    }
    private void startFmoIfAuto() {
        if (!p.autoReconnect() && !fmoWanted) return;
        if (fmoWanted && !fmoUp() && !p.callsign().isEmpty() && p.password().length() >= 6) startFmo(p.modeAnalog());
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerOverlayReceiver() {
        overlayPttReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (ArchOverlayPttService.ACTION_DOWN.equals(action)) externalPttDown();
                else if (ArchOverlayPttService.ACTION_UP.equals(action)) externalPttUp();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ArchOverlayPttService.ACTION_DOWN);
        filter.addAction(ArchOverlayPttService.ACTION_UP);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(overlayPttReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                // 7.0-12L: sender must hold our signature-level permission.
                registerReceiver(overlayPttReceiver, filter, INTERNAL_PTT_PERMISSION, null);
            }
        } catch (Throwable t) {
            log("内部 PTT 接收器注册失败: " + t);
        }
    }

    private void requestOverlayPtt() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请允许 ARCH 显示在其他应用上层");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            Intent i = new Intent(this, ArchOverlayPttService.class)
                    .setAction(ArchOverlayPttService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable t) { toast("悬浮 PTT 启动失败：" + t); }
    }

    private void setOverlayVisible(boolean visible) {
        try {
            Intent i = new Intent(this, ArchOverlayPttService.class)
                    .setAction(visible ? ArchOverlayPttService.ACTION_SHOW : ArchOverlayPttService.ACTION_HIDE);
            startService(i);
        } catch (Throwable ignored) {}
    }

    private void stopOverlayPtt() {
        try { stopService(new Intent(this, ArchOverlayPttService.class)); } catch (Throwable ignored) {}
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (p != null && p.externalPttEnabled() && p.externalPttA11yFallback()) {
            int target = p.externalPttKeyCode();
            if (target > 0 && event.getKeyCode() == target) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) externalPttDown();
                else if (event.getAction() == android.view.KeyEvent.ACTION_UP) externalPttUp();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        try { Updater.resumePendingInstall(this); } catch (Throwable ignored) {}
        ArchAppState.visible = true;                        // [3.2.30] 供无障碍服务判断前后台
        if (page == PAGE_NET_RADIO && radioPausedForPage) {
            radioPausedForPage = false;
            if (!radioUserPaused && radioPlayer != null) {
                radioPlayer.resume();
                if (radioPausedForOther) radioPlayer.setVolume(0.2f);
            }
        }
        try { updateExtKeyStatus(); } catch (Throwable ignored) {}
        try { refreshAccountStatus(); } catch (Throwable ignored) {}
        if (p != null && (p.showNotification() || p.overlayPtt() || p.externalPttEnabled()
                || p.pttBackground() || p.modeNetwork())) ensureKeepAliveService();
        if (p.aprsEnabled() && aprs != null) aprs.start();
        if (p.overlayPtt() && Settings.canDrawOverlays(this)) {
            requestOverlayPtt();
            setOverlayVisible(false);
        }
        if (resumeRestoreTask != null) ui.removeCallbacks(resumeRestoreTask);
        resumeRestorePending = true;
        resumeRestoreTask = () -> {
            resumeRestorePending = false;
            if (isFinishing() || isDestroyed()) return;
            try {
                if (p.callsign().isEmpty() && p.ysfCallsign().isEmpty()) return;
                // These methods only enqueue/start workers; all hardware release and
                // playback preparation paths are kept off the UI thread.
                autoConnectAll();
                ui.postDelayed(MainActivity.this::autoConnectYsfIfNeeded, 1200L);
                ui.postDelayed(MainActivity.this::autoConnectP25IfNeeded, 1450L);
                ui.postDelayed(MainActivity.this::autoConnectNxdnIfNeeded, 1700L);
                ui.postDelayed(MainActivity.this::autoConnectDmrIfNeeded, 1600L);
                if (bmNet != null && bmNet.isRunning()) bmNet.requestPresence();
                refreshAprsStatusButton();
            } catch (Throwable t) {
                log("resume reconnect failed: " + t);
            }
        };
        ui.removeCallbacks(resumeRestoreTask);
        ui.postDelayed(resumeRestoreTask, 650L);
        try {
            if (HyteraPdc760Bridge.consumePendingPttDown(this)) {
                ui.postDelayed(this::externalPttDown, 180L);
            }
        } catch (Throwable ignored) {}
    }

    private void openSipPhone() {
        if (BuildConfig.PUBLIC_EDITION) return;
        if (!p.sipEnabled()) { toast("请先在设置中启用 SIP 电话"); setPage(PAGE_SETTINGS); return; }
        if (p.get("sipServer", "").trim().isEmpty() || p.get("sipNumber", "").trim().isEmpty()) {
            toast("请先在设置中填写 SIP 服务器和电话号");
            setPage(PAGE_SETTINGS);
            return;
        }
        try {
            SipRuntime.get(this).ensureStarted();
            startActivity(new Intent(this, SipPhoneActivity.class));
        } catch (Throwable t) { toast("SIP 电话无法打开"); }
    }

    private void refreshAprsStatusButton() {
        if (aprsStateBtn == null) return;
        boolean connected = p.aprsEnabled() && aprs != null && aprs.isConnected();
        aprsStateBtn.setText(connected ? "已连接" : "已断开");
        aprsStateBtn.setTextColor(connected ? GREEN : SUB);
    }

    private void startHqPlaybackAsync() {
        if (hqPlaybackReady || hqPlaybackStarting) return;
        hqPlaybackStarting = true;
        final int playbackGeneration = hqPlaybackGeneration;
        runAudioIo(() -> {
            // Network-room receive owns the speaker while active. Close the legacy 8 kHz
            // player first so two AudioTracks never compete on the same audio HAL.
            try { if (audio != null) audio.stop(); } catch (Throwable ignored) {}
            audioReady = false;
            boolean ok = hqAudio != null && hqAudio.startPlayback();
            ui.post(() -> {
                if (playbackGeneration != hqPlaybackGeneration) return;
                hqPlaybackStarting = false;
                hqPlaybackReady = ok;
                if (hqStatus != null && ok) setHqStatus("状态: 已连接 · " + hqName() + " · 点击断开");
                refreshNetworkCallBar();
            });
        });
    }

    @Override protected void onPause() {
        ArchAppState.visible = false;                       // [3.2.30]
        // [3.2.47] 只有真正切到别的应用才暂停；息屏时继续播放
        boolean screenOff = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            screenOff = pm != null && !pm.isInteractive();
        } catch (Throwable ignored) {}
        if (!screenOff && page == PAGE_NET_RADIO && radioPlayer != null && radioPlayer.isPlaying()) {
            radioPausedForPage = true;
            radioPlayer.pause();
            if (radioPlayBtn != null) radioPlayBtn.setText("▶");
        }
        if (resumeRestoreTask != null) { ui.removeCallbacks(resumeRestoreTask); resumeRestoreTask = null; }
        resumeRestorePending = false;
        if (!txing && !hqTxRequested) {
            legacyMicOffAsync();
        }
        if (p.overlayPtt() && Settings.canDrawOverlays(this)) setOverlayVisible(true);
        // Net或悬浮 PTT 在后台工作时，保持带 microphone 类型的前台服务。
        try {
            // 只要还有实际在跑的链路（DMR/BM/模拟/网络就绪）就拉起前台服务，
            // 否则数字中继/BM 挂后台久了会被系统冻结，回来就掉线。
            boolean linkAlive = (hbp != null && hbp.isRunning())
                    || (bmHbp != null && bmHbp.isRunning())
                    || (dstarDirect != null && dstarDirect.isRunning())
                    || nrlActive
                    || fmoUp();
            if (p.showNotification() || p.modeNetwork() || p.overlayPtt() || p.externalPttEnabled() || linkAlive) {
                android.content.Intent keep = new android.content.Intent(this, ArchKeepAliveService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
            }
        } catch (Throwable ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        boolean retain = false;
        try { retain = BuildConfig.HYTERA_EDITION && p != null && p.externalPttBackground()
                && !completeExitInProgress; } catch (Throwable ignored) {}
        try { log("Activity onDestroy finishing=" + isFinishing() + ", retainRuntime=" + retain); } catch (Throwable ignored) {}
        super.onDestroy();
        if (retain) {
            backgroundRuntimeRetained = true;
            log("Activity 无窗口销毁，保留后台 PTT 运行时");
            return;
        }
        try { SipRuntime.get(this).removeListener(sipStatusListener); } catch (Throwable ignored) {}
        shutdownRuntime();
        detachHardwarePttActivity(this);
    }

    private void performCompleteExit() {
        if (completeExitInProgress) return;
        completeExitInProgress = true;
        try { p.setUserExited(true); } catch (Throwable ignored) {}
        try {
            if (txing || hqTxRequested || hqMicStarting || hqPttGranted) pttUp();
        } catch (Throwable ignored) {}
        try { SipRuntime.get(this).setEnabled(false); } catch (Throwable ignored) {}
        try { shutdownRuntime(); } catch (Throwable ignored) {}
        try { stopOverlayPtt(); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, ArchKeepAliveService.class)); } catch (Throwable ignored) {}
        try { if (overlayPttReceiver != null) unregisterReceiver(overlayPttReceiver); } catch (Throwable ignored) {}
        overlayPttReceiver = null;
        try { HyteraPdc760Bridge.setListener(null); } catch (Throwable ignored) {}
        try { detachHardwarePttActivity(this); } catch (Throwable ignored) {}
        try { finishAndRemoveTask(); } catch (Throwable ignored) {}
        try {
            ui.postDelayed(() -> {
                try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
            }, 900L);
        } catch (Throwable ignored) {}
    }

    private void shutdownRuntime() {
        if (runtimeShutdown) return;
        runtimeShutdown = true;
        backgroundRuntimeRetained = false;
        // 关键：撤掉本实例所有延时任务，并声明“不再想连 FMO”，
        // 否则 6 秒后的 startFmoIfAuto 会在已销毁的界面上新建客户端，
        // 和重建后的新客户端用同一个 MQTT client id 互踢 —— 表现就是反复断开重连。
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        cancelHardwarePttRetry();
        try { HyteraPdc760Bridge.setListener(null); } catch (Throwable ignored) {}
        if (aprs != null) aprs.stop();
        fmoWanted = false;
        if (hbp != null) hbp.stop();
        if (bmHbp != null) bmHbp.stop();
        fmoPlayerRun = false;
        if (fmo != null) fmo.stop();
        if (bmNet != null) bmNet.stop();
        if (nrl != null) nrl.stop();
        if (nrlAudio != null) nrlAudio.stop();
        if (dstarDirect != null) dstarDirect.stop();
        if (ysfDirect != null) ysfDirect.stop();
        if (p25Direct != null) p25Direct.stop();
        if (nxdnDirect != null) nxdnDirect.stop();
        hqStopAsync();
        if (netOpus != null) netOpus.close();
        if (audio != null) audio.stop();
        if (voc != null) voc.close();
        if (radioPlayer != null) radioPlayer.release();
    }
    // ────────────────────────── P25 Phase 1（直连反射器） ──────────────────────────
    private int p25ServerIndex(){return P25Servers.indexOf(p.p25Server());}
    private String p25ServerName(){return P25Servers.name(p25ServerIndex());}
    private void updateP25Selectors(){
        if(p25ServerBtn!=null)p25ServerBtn.setText(p25ServerName());
        if(p25ServerLine!=null)p25ServerLine.setText("P25 反射器: "+p25ServerName());
    }
    private void showP25ServerPicker(){
        if(P25Servers.count()<=0){toast("P25 服务器列表为空，请先更新服务器列表");return;}
        String[] labels=new String[P25Servers.count()];
        for(int i=0;i<labels.length;i++)labels[i]=P25Servers.name(i);
        showSearchablePicker("选择 P25 反射器", labels, p25ServerIndex(), which -> { p.setP25Server(P25Servers.id(which)); disconnectP25(); updateP25Selectors(); updateModeLabel(); });
    }

    private void ensureP25Client(){
        if(p25Direct!=null)return;
        p25Direct=new P25Client();
        p25Direct.setListener(new P25Client.Listener(){
            @Override public void onState(int st,String msg){
                p25Active=st==1; p25Connecting=st==0;
                safeUi(()->{
                    paintP25ConnectIcon();
                    if(p25CallState!=null){
                        if(st==1){p25CallState.setText("空闲");p25CallState.setTextColor(SUB);if(p25CallLine!=null){p25CallLine.setText("等待呼叫");p25CallLine.setTextColor(TXT);}}
                        else if(st==2||st==3){p25CallState.setText("未连接");p25CallState.setTextColor(SUB);}
                        else {p25CallState.setText("连接中");p25CallState.setTextColor(BLUE);}
                    }
                    updateModeLabel();
                });
            }
            @Override public void onVoice(int sid,int did,short[] pcm){
                if(!p25Active||pcm==null||pcm.length==0)return;
                markModeSignal(6);
                if(!listenMode(6))return;
                if(audio!=null&&!rxPlaybackBlocked())audio.playRaw(pcm);
                final String who=contactLabel(sid);
                p25LastPeer=who;
                if(p25RxStart==0)p25RxStart=System.currentTimeMillis();
                safeUi(()->{
                    setLastCall(6,who);
                    if(p25CallState!=null){p25CallState.setText("接收中");p25CallState.setTextColor(BLUE);}
                    if(p25CallLine!=null){p25CallLine.setText(who);p25CallLine.setTextColor(RED);}
                });
            }
            @Override public void onEnd(int sid,int did,int sec){
                String peer=p25LastPeer==null||p25LastPeer.isEmpty()?contactLabel(sid):p25LastPeer;
                if(p25RxStart>0&&sec<=0)sec=Math.max(1,(int)((System.currentTimeMillis()-p25RxStart)/1000L));
                addP25Log("接收",peer,sec);
                p25LastPeer="";p25RxStart=0;
                safeUi(()->{
                    if(p25CallState!=null){p25CallState.setText("空闲");p25CallState.setTextColor(SUB);}
                    if(p25CallLine!=null){p25CallLine.setText("等待呼叫");p25CallLine.setTextColor(TXT);}
                });
            }
            @Override public void onLog(String msg){log(msg);}
        });
    }

    private void connectP25(){
        if(p25Active()){disconnectP25();return;}
        ensureP25Client();
        if(P25Servers.count()<=0){toast("P25 服务器列表为空，请先更新服务器列表");return;}
        String call=p.p25Callsign();
        int sourceId=p.dmrId() & 0xFFFFFF;
        if(call.isEmpty()){toast("P25 呼号为空，请到身份设置与注册中保存");return;}
        if(sourceId<=0){toast("P25 需要数字 ID，请到身份设置与注册中填写 DMR ID");return;}
        int serverIndex=p25ServerIndex();
        p25Connecting=true; paintP25ConnectIcon();
        final int si=serverIndex;
        final int destinationId=P25Servers.designator(si);
        log("P25 连接: "+P25Servers.name(si)+" 呼号="+call+" ID="+sourceId+" TG="+destinationId);
        new Thread(()->{
            try{ p25Direct.connect(P25Servers.host(si),P25Servers.port(si),call,sourceId,destinationId); }
            catch(Throwable t){ p25Connecting=false; log("P25 连接失败: "+t); toast("P25 连接失败: "+t); }
        },"p25-connect").start();
    }

    private void disconnectP25(){
        p25Connecting=false;p25Active=false;
        if(p25Direct!=null)p25Direct.stop();
        paintP25ConnectIcon();
        if(p25CallState!=null){p25CallState.setText("未连接");p25CallState.setTextColor(SUB);}
        if(p25CallLine!=null){p25CallLine.setText("未登录");p25CallLine.setTextColor(TXT);}
        updateModeLabel();
    }
    private boolean p25Active(){return p25Active&&p25Direct!=null&&p25Direct.isRunning();}
    private void paintP25ConnectIcon(){
        if(p25ConnectBtn==null)return;
        boolean on=p25Active();
        p25ConnectBtn.setText(on?"断开":"连接");
        p25ConnectBtn.setTextColor(Color.WHITE);
        p25ConnectBtn.setBackground(roundBg(on?GREEN:Color.BLACK,999));
    }
    private void autoConnectP25IfNeeded(){
        if(autoConnectSuppressed())return;
        if(p==null||!p.showP25()||!p.autoP25())return;
        if(p25Active()||p25Connecting)return;
        if(p.p25Callsign().isEmpty()||p.dmrId()<=0)return;
        long now=System.currentTimeMillis();
        if(now-p25AutoAttemptAt<5000L)return;
        p25AutoAttemptAt=now;
        log("P25 启动自动登录检查通过，准备连接 "+p25ServerName());
        connectP25();
    }
    private void startP25Ptt(){
        if(!p25Active()){toast("请先连接 P25 反射器");return;}
        if(txing)return;
        txing=true;activePttTarget="p25";txCallStart=System.currentTimeMillis();
        p25Direct.startTx();pttToneStart();scheduleTxTimeout();
        if(p25CallState!=null){p25CallState.setText("发射中");p25CallState.setTextColor(BLUE);}
        if(p25CallLine!=null){p25CallLine.setText(p.p25Callsign());p25CallLine.setTextColor(TXT);}
        ui.post(this::paintNav);
        if(!audio.startYsfMic(frame->{if(txing&&p25Direct!=null)p25Direct.sendPcm(frame);})) micStartFailed();
    }
    private void stopP25Ptt(){
        try{if(audio!=null)audio.startMic(null);}catch(Throwable ignored){}
        if(p25Direct!=null)p25Direct.stopTx();
        int sec=txCallStart==0?0:(int)((System.currentTimeMillis()-txCallStart)/1000);
        if(sec==0&&txing)sec=1;
        if(txing)addP25Log("发射",p.p25Callsign(),sec);
        txing=false;activePttTarget=null;txCallStart=0;pttToneStop();cancelTxTimeout();
        if(p25CallState!=null){p25CallState.setText("空闲");p25CallState.setTextColor(SUB);}
        if(p25CallLine!=null){p25CallLine.setText("等待呼叫");p25CallLine.setTextColor(TXT);}
        ui.post(this::paintNav);
    }

    private void addP25Log(String direction,String peer,int sec){
        final String time=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date());
        ui.post(()->addP25LogView(time,direction,peer,sec));
    }
    private void addP25LogView(String time,String direction,String peer,int sec){
        if(p25LogBox==null)return;
        if(p25LogBox.getChildCount()==1&&p25LogBox.getChildAt(0) instanceof TextView
                &&"暂无 P25 通话记录".contentEquals(((TextView)p25LogBox.getChildAt(0)).getText())){
            p25LogBox.removeAllViews();
        }
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0,dp(5),0,dp(5));
        r.addView(tableCell(time,0.85f,SUB,false));
        r.addView(tableCell(peer==null?"":peer,1.15f,TXT,true));
        r.addView(tableCell(direction,0.72f,BLUE,false));
        r.addView(tableCell(sec>0?sec+"s":"--",0.55f,SUB,false));
        p25LogBox.addView(r,0);
        while(p25LogBox.getChildCount()>visibleRows)p25LogBox.removeViewAt(p25LogBox.getChildCount()-1);
    }
    private View buildP25Page(){
        LinearLayout l=wrap();
        p25CallState=tv("未连接",12,SUB);
        p25CallLine=tv("未登录",24,TXT);p25CallLine.setTypeface(null,Typeface.BOLD);
        p25LastLine=tv("上次呼叫：--",14,SUB);
        p25ServerLine=tv("P25 反射器: "+p25ServerName(),13,BLUE);
        muteP25Btn=muteButton("p25");
        l.addView(statusCard(muteP25Btn,p25CallState,p25CallLine,p25LastLine,p25ServerLine));
        LinearLayout c=card();
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.HORIZONTAL);h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t=sectionTitle("P25 连接");t.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));h.addView(t);
        p25ConnectBtn=tv("连接",14,Color.WHITE);p25ConnectBtn.setGravity(Gravity.CENTER);p25ConnectBtn.setTypeface(null,Typeface.BOLD);p25ConnectBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(34)));p25ConnectBtn.setOnClickListener(v->connectP25());h.addView(p25ConnectBtn);c.addView(h);
        p25ServerBtn=btn(p25ServerName());p25ServerBtn.setOnClickListener(v->showP25ServerPicker());c.addView(row("服务器",p25ServerBtn));
        l.addView(c);
        LinearLayout hist=card();hist.addView(sectionTitle("通话记录"));
        LinearLayout dh=new LinearLayout(this);dh.setOrientation(LinearLayout.HORIZONTAL);
        dh.addView(tableCell("时间",0.85f,BLUE,true));dh.addView(tableCell("呼号",1.15f,BLUE,true));dh.addView(tableCell("方向",0.72f,BLUE,true));dh.addView(tableCell("通话时长",0.55f,BLUE,true));hist.addView(dh);
        p25LogBox=new LinearLayout(this);p25LogBox.setOrientation(LinearLayout.VERTICAL);p25LogBox.setMinimumHeight(dp(60));p25LogBox.addView(tv("暂无 P25 通话记录",12,SUB));hist.addView(p25LogBox);l.addView(hist);
        updateP25Selectors();paintP25ConnectIcon();return pageWithPttScroll(l);
    }

// ────────────────────────── NXDN（直连反射器） ──────────────────────────
    private int nxdnServerIndex(){return NxdnServers.indexOf(p.nxdnServer());}
    private String nxdnServerName(){return NxdnServers.name(nxdnServerIndex());}
    private void updateNxdnSelectors(){
        if(nxdnServerBtn!=null)nxdnServerBtn.setText(nxdnServerName());
        if(nxdnServerLine!=null)nxdnServerLine.setText("NXDN 反射器: "+nxdnServerName());
    }
    private void showNxdnServerPicker(){
        if(NxdnServers.count()<=0){toast("NXDN 服务器列表为空，请先更新服务器列表");return;}
        String[] labels=new String[NxdnServers.count()];
        for(int i=0;i<labels.length;i++)labels[i]=NxdnServers.name(i);
        showSearchablePicker("选择 NXDN 反射器", labels, nxdnServerIndex(), which -> { p.setNxdnServer(NxdnServers.id(which)); disconnectNxdn(); updateNxdnSelectors(); updateModeLabel(); });
    }

    private void ensureNxdnClient(){
        if(nxdnDirect!=null)return;
        nxdnDirect=new NxdnClient();
        nxdnDirect.setListener(new NxdnClient.Listener(){
            @Override public void onState(int st,String msg){
                nxdnActive=st==1; nxdnConnecting=st==0;
                safeUi(()->{
                    paintNxdnConnectIcon();
                    if(nxdnCallState!=null){
                        if(st==1){nxdnCallState.setText("空闲");nxdnCallState.setTextColor(SUB);if(nxdnCallLine!=null){nxdnCallLine.setText("等待呼叫");nxdnCallLine.setTextColor(TXT);}}
                        else if(st==2||st==3){nxdnCallState.setText("未连接");nxdnCallState.setTextColor(SUB);}
                        else {nxdnCallState.setText("连接中");nxdnCallState.setTextColor(BLUE);}
                    }
                    updateModeLabel();
                });
            }
            @Override public void onSignal(int sid,int did){
                if(!nxdnActive)return;
                markModeSignal(7);
                final String who=nxdnContactLabel(sid);
                nxdnLastPeer=who;
                long now=System.currentTimeMillis();
                boolean changed=!who.equals(nxdnUiLastPeer);
                if(!changed&&now-nxdnUiLastAt<500L)return;
                nxdnUiLastPeer=who;nxdnUiLastAt=now;
                if(nxdnRxStart==0)nxdnRxStart=now;
                safeUi(()->{
                    setLastCall(7,who);
                    if(nxdnCallState!=null){nxdnCallState.setText("接收中");nxdnCallState.setTextColor(BLUE);}
                    if(nxdnCallLine!=null){nxdnCallLine.setText(who);nxdnCallLine.setTextColor(RED);}
                });
            }
            @Override public void onVoice(int sid,int did,short[] pcm){
                if(!nxdnActive||pcm==null||pcm.length==0)return;
                markModeSignal(7);
                if(!listenMode(7))return;
                if(audio!=null&&!rxPlaybackBlocked())audio.playRaw(pcm);
            }
            @Override public void onEnd(int sid,int did,int sec){
                String peer=nxdnLastPeer==null||nxdnLastPeer.isEmpty()?nxdnContactLabel(sid):nxdnLastPeer;
                if(nxdnRxStart>0&&sec<=0)sec=Math.max(1,(int)((System.currentTimeMillis()-nxdnRxStart)/1000L));
                addNxdnLog("接收",peer,sec);
                nxdnLastPeer="";nxdnRxStart=0;
                safeUi(()->{
                    if(nxdnCallState!=null){nxdnCallState.setText("空闲");nxdnCallState.setTextColor(SUB);}
                    if(nxdnCallLine!=null){nxdnCallLine.setText("等待呼叫");nxdnCallLine.setTextColor(TXT);}
                });
            }
            @Override public void onLog(String msg){log(msg);}
        });
    }

    private void connectNxdn(){
        if(nxdnActive()){disconnectNxdn();return;}
        ensureNxdnClient();
        if(NxdnServers.count()<=0){toast("NXDN 服务器列表为空，请先更新服务器列表");return;}
        String call=p.nxdnCallsign();
        int sourceId=p.nxdnId();
        if(call.isEmpty()||sourceId<=0){toast("请先在设置里保存 NXDN 呼号和 DMR ID");return;}
        int serverIndex=nxdnServerIndex();
        nxdnConnecting=true; paintNxdnConnectIcon();
        final int si=serverIndex;
        final int groupId=NxdnServers.designator(si);
        log("NXDN 连接: "+NxdnServers.name(si)+" 呼号="+call+" ID="+sourceId);
        new Thread(()->{
            try{ nxdnDirect.connect(NxdnServers.host(si),NxdnServers.port(si),call,sourceId,groupId); }
            catch(Throwable t){ nxdnConnecting=false; log("NXDN 连接失败: "+t); toast("NXDN 连接失败: "+t); }
        },"nxdn-connect").start();
    }

    private String nxdnContactLabel(int id){
        String c=NxdnContacts.get(id);
        if(c==null||c.trim().isEmpty())c=String.valueOf(id);
        String country=CountryBook.countryFromCallsign(c);
        if(country==null||country.trim().isEmpty()||"?".equals(country.trim())||"??".equals(country.trim()))country="";
        return country.isEmpty()?c:c+" ("+id+") "+country;
    }

    private void disconnectNxdn(){
        nxdnConnecting=false;nxdnActive=false;
        if(nxdnDirect!=null)nxdnDirect.stop();
        paintNxdnConnectIcon();
        if(nxdnCallState!=null){nxdnCallState.setText("未连接");nxdnCallState.setTextColor(SUB);}
        if(nxdnCallLine!=null){nxdnCallLine.setText("未登录");nxdnCallLine.setTextColor(TXT);}
        updateModeLabel();
    }
    private boolean nxdnActive(){return nxdnActive&&nxdnDirect!=null&&nxdnDirect.isRunning();}
    private void paintNxdnConnectIcon(){
        if(nxdnConnectBtn==null)return;
        boolean on=nxdnActive();
        nxdnConnectBtn.setText(on?"断开":"连接");
        nxdnConnectBtn.setTextColor(Color.WHITE);
        nxdnConnectBtn.setBackground(roundBg(on?GREEN:Color.BLACK,999));
    }
    private void autoConnectNxdnIfNeeded(){
        if(autoConnectSuppressed())return;
        if(p==null||!p.showNxdn()||!p.autoNxdn())return;
        if(nxdnActive()||nxdnConnecting)return;
        if(p.nxdnCallsign().isEmpty()||p.nxdnId()<=0)return;
        long now=System.currentTimeMillis();
        if(now-nxdnAutoAttemptAt<5000L)return;
        nxdnAutoAttemptAt=now;
        log("NXDN 启动自动登录检查通过，准备连接 "+nxdnServerName());
        connectNxdn();
    }
    private void startNxdnPtt(){
        if(!nxdnActive()){toast("请先连接 NXDN 反射器");return;}
        if(txing)return;
        txing=true;activePttTarget="nxdn";txCallStart=System.currentTimeMillis();
        nxdnDirect.startTx();pttToneStart();scheduleTxTimeout();
        if(nxdnCallState!=null){nxdnCallState.setText("发射中");nxdnCallState.setTextColor(BLUE);}
        if(nxdnCallLine!=null){nxdnCallLine.setText(p.nxdnCallsign());nxdnCallLine.setTextColor(TXT);}
        ui.post(this::paintNav);
        if(!audio.startYsfMic(frame->{if(txing&&nxdnDirect!=null)nxdnDirect.sendPcm(frame);})) micStartFailed();
    }
    private void stopNxdnPtt(){
        try{if(audio!=null)audio.startMic(null);}catch(Throwable ignored){}
        if(nxdnDirect!=null)nxdnDirect.stopTx();
        int sec=txCallStart==0?0:(int)((System.currentTimeMillis()-txCallStart)/1000);
        if(sec==0&&txing)sec=1;
        if(txing)addNxdnLog("发射",p.nxdnCallsign(),sec);
        txing=false;activePttTarget=null;txCallStart=0;pttToneStop();cancelTxTimeout();
        if(nxdnCallState!=null){nxdnCallState.setText("空闲");nxdnCallState.setTextColor(SUB);}
        if(nxdnCallLine!=null){nxdnCallLine.setText("等待呼叫");nxdnCallLine.setTextColor(TXT);}
        ui.post(this::paintNav);
    }

    private void addNxdnLog(String direction,String peer,int sec){
        final String time=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date());
        ui.post(()->addNxdnLogView(time,direction,peer,sec));
    }
    private void addNxdnLogView(String time,String direction,String peer,int sec){
        if(nxdnLogBox==null)return;
        if(nxdnLogBox.getChildCount()==1&&nxdnLogBox.getChildAt(0) instanceof TextView
                &&"暂无 NXDN 通话记录".contentEquals(((TextView)nxdnLogBox.getChildAt(0)).getText())){
            nxdnLogBox.removeAllViews();
        }
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0,dp(5),0,dp(5));
        r.addView(tableCell(time,0.85f,SUB,false));
        r.addView(tableCell(peer==null?"":peer,1.15f,TXT,true));
        r.addView(tableCell(direction,0.72f,BLUE,false));
        r.addView(tableCell(sec>0?sec+"s":"--",0.55f,SUB,false));
        nxdnLogBox.addView(r,0);
        while(nxdnLogBox.getChildCount()>visibleRows)nxdnLogBox.removeViewAt(nxdnLogBox.getChildCount()-1);
    }
    private View buildNxdnPage(){
        LinearLayout l=wrap();
        nxdnCallState=tv("未连接",12,SUB);
        nxdnCallLine=tv("未登录",24,TXT);nxdnCallLine.setTypeface(null,Typeface.BOLD);
        nxdnLastLine=tv("上次呼叫：--",14,SUB);
        nxdnServerLine=tv("NXDN 反射器: "+nxdnServerName(),13,BLUE);
        muteNxdnBtn=muteButton("nxdn");
        l.addView(statusCard(muteNxdnBtn,nxdnCallState,nxdnCallLine,nxdnLastLine,nxdnServerLine));
        LinearLayout c=card();
        LinearLayout h=new LinearLayout(this);h.setOrientation(LinearLayout.HORIZONTAL);h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t=sectionTitle("NXDN 连接");t.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));h.addView(t);
        nxdnConnectBtn=tv("连接",14,Color.WHITE);nxdnConnectBtn.setGravity(Gravity.CENTER);nxdnConnectBtn.setTypeface(null,Typeface.BOLD);nxdnConnectBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(34)));nxdnConnectBtn.setOnClickListener(v->connectNxdn());h.addView(nxdnConnectBtn);c.addView(h);
        nxdnServerBtn=btn(nxdnServerName());nxdnServerBtn.setOnClickListener(v->showNxdnServerPicker());c.addView(row("服务器",nxdnServerBtn));
        l.addView(c);
        LinearLayout hist=card();hist.addView(sectionTitle("通话记录"));
        LinearLayout dh=new LinearLayout(this);dh.setOrientation(LinearLayout.HORIZONTAL);
        dh.addView(tableCell("时间",0.85f,BLUE,true));dh.addView(tableCell("呼号",1.15f,BLUE,true));dh.addView(tableCell("方向",0.72f,BLUE,true));dh.addView(tableCell("通话时长",0.55f,BLUE,true));hist.addView(dh);
        nxdnLogBox=new LinearLayout(this);nxdnLogBox.setOrientation(LinearLayout.VERTICAL);nxdnLogBox.setMinimumHeight(dp(60));nxdnLogBox.addView(tv("暂无 NXDN 通话记录",12,SUB));hist.addView(nxdnLogBox);l.addView(hist);
        updateNxdnSelectors();paintNxdnConnectIcon();return pageWithPttScroll(l);
    }



    // ────────────────────────── NRL（NRL2 / UDP / G.711） ──────────────────────────
    private View buildNrlPage() {
        LinearLayout l = wrap();
        nrlGroups = loadNrlGroupCache(p.nrlServerKey());
        syncCachedNrlGroupName();
        nrlCallState = tv("未连接", 12, SUB);
        nrlCallLine = tv("未登录", 24, TXT);
        nrlCallLine.setTypeface(null, Typeface.BOLD);
        nrlGroupLine = tv(nrlGroupDisplayText(), 15, TXT);
        nrlServerLine = tv("NRL 服务器: " + p.nrlServerName(), 13, BLUE);
        nrlLastLine = tv("上次呼叫：--", 14, SUB);
        muteNrlBtn = muteButton("nrl");
        l.addView(statusCard(muteNrlBtn, nrlCallState, nrlCallLine, nrlGroupLine, nrlServerLine, nrlLastLine));

        LinearLayout c = card();
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle("NRL2 网络连接");
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(title);
        nrlConnectBtn = tv("连接", 14, Color.WHITE);
        nrlConnectBtn.setGravity(Gravity.CENTER);
        nrlConnectBtn.setTypeface(null, Typeface.BOLD);
        nrlConnectBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        nrlConnectBtn.setOnClickListener(v -> connectNrl());
        h.addView(nrlConnectBtn);
        c.addView(h);

        LinearLayout serverLine = new LinearLayout(this);
        serverLine.setOrientation(LinearLayout.HORIZONTAL);
        serverLine.setGravity(Gravity.CENTER_VERTICAL);
        nrlServerBtn = btn(p.nrlServerName());
        nrlServerBtn.setOnClickListener(v -> showNrlServerPicker());
        serverLine.addView(row("当前服务器", nrlServerBtn), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(serverLine);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        nrlDirectoryRefreshBtn = btn(tinyScreen ? "刷目录" : "刷新目录");
        nrlDirectoryRefreshBtn.setOnClickListener(v -> refreshNrlDirectory(true));
        actions.addView(nrlDirectoryRefreshBtn, nrlActionParams());
        nrlAddServerBtn = btn(tinyScreen ? "添加" : "添加服务器");
        nrlAddServerBtn.setOnClickListener(v -> showNrlAddServerDialog());
        actions.addView(nrlAddServerBtn, nrlActionParams());
        Button registerServerBtn = btn(tinyScreen ? "注册" : "注册服务器");
        registerServerBtn.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, NrlRegisterActivity.class)));
        actions.addView(registerServerBtn, nrlActionParams());
        c.addView(actions);

        nrlDirectoryLine = tv(NrlServers.statusText(this), 11, SUB);
        nrlDirectoryLine.setPadding(dp(4), dp(7), dp(4), dp(1));
        c.addView(nrlDirectoryLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        l.addView(c);

        LinearLayout cg = card();
        LinearLayout gh = new LinearLayout(this);
        gh.setOrientation(LinearLayout.HORIZONTAL);
        gh.setGravity(Gravity.CENTER_VERTICAL);
        TextView gt = sectionTitle("NRL 频道 / 房间");
        gt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        gh.addView(gt);
        Button refresh = btn(tinyScreen ? "刷新" : "刷新频道");
        refresh.setOnClickListener(v -> refreshNrlGroups());
        gh.addView(refresh, new LinearLayout.LayoutParams(dp(92), dp(36)));
        cg.addView(gh);
        nrlGroupBox = new LinearLayout(this);
        nrlGroupBox.setOrientation(LinearLayout.VERTICAL);
        nrlGroupBox.setPadding(0, dp(5), 0, 0);
        cg.addView(nrlGroupBox);
        buildNrlGroupButtons(null);
        l.addView(cg);

        LinearLayout hist = card();
        hist.addView(sectionTitle("NRL 通话记录"));
        LinearLayout dh = new LinearLayout(this);
        dh.setOrientation(LinearLayout.HORIZONTAL);
        dh.addView(tableCell("时间", 0.85f, BLUE, true));
        dh.addView(tableCell("呼号", 1.15f, BLUE, true));
        dh.addView(tableCell("方向", 0.72f, BLUE, true));
        dh.addView(tableCell("时长", 0.55f, BLUE, true));
        hist.addView(dh);
        nrlLogBox = new LinearLayout(this);
        nrlLogBox.setOrientation(LinearLayout.VERTICAL);
        nrlLogBox.setMinimumHeight(dp(55));
        nrlLogBox.addView(tv("暂无 NRL 通话记录", 12, SUB));
        hist.addView(nrlLogBox);
        l.addView(hist);
        refreshNrlServerButton();
        paintNrlConnectIcon();
        return pageWithPttScroll(l);
    }

    private void syncCachedNrlGroupName() {
        nrlGroupNameCache = defaultNrlGroupName(p.nrlGroup());
        if (nrlGroups == null) return;
        for (NrlClient.Group g : nrlGroups) {
            if (g.id == p.nrlGroup()) {
                nrlGroupNameCache = g.name;
                return;
            }
        }
    }

    private java.util.List<NrlClient.Group> loadNrlGroupCache(String serverKey) {
        java.util.ArrayList<NrlClient.Group> out = new java.util.ArrayList<>();
        try {
            String raw = p.get("nrlGroupCache:" + serverKey, "");
            if (raw == null || raw.trim().isEmpty()) return out;
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length() && i < 500; i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", 0);
                String name = o.optString("name", "").trim();
                if (id > 0) out.add(new NrlClient.Group(id, name));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void saveNrlGroupCache(java.util.List<NrlClient.Group> groups) {
        if (groups == null || groups.isEmpty()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < groups.size() && i < 500; i++) {
                NrlClient.Group g = groups.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                arr.put(o);
            }
            p.set("nrlGroupCache:" + p.nrlServerKey(), arr.toString());
        } catch (Throwable ignored) {}
    }

    private LinearLayout.LayoutParams nrlActionParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private void refreshNrlGroups() {
        if (p == null || !p.showNrl()) { toast("请先在设置里开启 NRL"); return; }
        if (nrlActive || nrlConnecting) {
            if (nrl != null) nrl.requestGroupList();
            toast("正在获取 NRL 频道…");
            return;
        }
        toast("正在连接服务器并获取频道");
        connectNrl();
    }

    private void buildNrlGroupButtons(java.util.List<NrlClient.Group> groups) {
        if (nrlGroupBox == null) return;
        if (groups != null) nrlGroups = new java.util.ArrayList<>(groups);
        java.util.List<NrlClient.Group> list = groups == null ? nrlGroups : groups;
        if (nrlGroupBox != null) nrlGroupBox.removeAllViews();
        if (list == null || list.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setPadding(dp(6), dp(9), dp(6), dp(9));
            TextView emptyTitle = tv("暂无 NRL 频道", 13, TXT);
            emptyTitle.setTypeface(null, Typeface.BOLD);
            String detail;
            if (nrlConnecting) detail = "正在连接服务器，连接成功后会自动获取频道。";
            else if (nrlActive) detail = "服务器已连接，但还没有返回频道；请点“刷新频道”重试。";
            else detail = "连接服务器后会自动获取频道，也可以点“刷新频道”。";
            TextView hint = tv(detail, 11, SUB);
            hint.setPadding(0, dp(4), 0, 0);
            empty.addView(emptyTitle);
            empty.addView(hint);
            nrlGroupBox.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (NrlClient.Group g : list) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.HORIZONTAL);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(12), dp(10), dp(12), dp(10));
            tile.setBackground(tgButtonBg(g.id == p.nrlGroup()));
            String rowName = g.id + " · " + g.name + (g.id == p.nrlGroup() ? "（当前）" : "");
            TextView name = tv(rowName, 13, TXT);
            name.setTypeface(null, Typeface.BOLD);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView arrow = tv("›", 16, ACCENT);
            tile.addView(name); tile.addView(arrow);
            tile.setOnClickListener(v -> {
                p.setNrlGroup(g.id);
                nrlGroupNameCache = g.name;
                if (nrl != null) nrl.joinGroup(g.id);
                if (nrlGroupLine != null) nrlGroupLine.setText(nrlGroupDisplayText());
                buildNrlGroupButtons(list);
                updateModeLabel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(5));
            nrlGroupBox.addView(tile, lp);
        }
    }

    private String defaultNrlGroupName(int id) {
        if (id == 1004 || id == 46002) return "46002多模融合";
        if (id == 10) return "红十字组";
        return "组 " + id;
    }

    private String nrlGroupDisplayText() {
        int id = p.nrlGroup();
        String name = nrlGroupNameCache;
        if (name == null || name.trim().isEmpty()) name = defaultNrlGroupName(id);
        return "当前房间: " + name + " · " + id;
    }

    private void refreshNrlDirectory(boolean manual) {
        if (nrlDirectoryRefreshBtn != null) {
            nrlDirectoryRefreshBtn.setText("刷新中");
            nrlDirectoryRefreshBtn.setEnabled(false);
            nrlDirectoryRefreshBtn.setAlpha(0.55f);
        }
        NrlServers.refresh(this, manual, (success, message) -> safeUi(() -> {
            if (nrlDirectoryRefreshBtn != null) {
                nrlDirectoryRefreshBtn.setText(tinyScreen ? "刷目录" : "刷新目录");
                nrlDirectoryRefreshBtn.setEnabled(true);
                nrlDirectoryRefreshBtn.setAlpha(1f);
            }
            refreshNrlServerButton();
            if (manual) toast(success ? "NRL 服务器目录已更新" : "NRL 服务器目录更新失败，继续使用本地列表");
        }));
    }

    private void refreshNrlServerButton() {
        if (nrlServerBtn != null) nrlServerBtn.setText(p.nrlServerName());
        if (nrlDirectoryLine != null) nrlDirectoryLine.setText(NrlServers.statusText(this));
        if (nrlServerLine != null) {
            String ep = p.nrlServerHost() + ":" + p.nrlServerPort();
            nrlServerLine.setText("NRL: " + p.nrlServerName() + " · " + ep);
        }
    }

    private void applyNrlServerSelection(NrlServers.Server s) {
        if (s == null) return;
        p.selectNrlServer(s.key, s.name, s.host, s.port, s.password);
        nrlGroups = loadNrlGroupCache(s.key);
        nrlGroupNameCache = "";
        syncCachedNrlGroupName();
        disconnectNrl();
        refreshNrlServerButton();
        if (nrlGroupLine != null) {
            nrlGroupLine.setText(nrlGroups.isEmpty()
                    ? "当前房间: 等待服务器返回频道列表"
                    : nrlGroupDisplayText());
        }
        buildNrlGroupButtons(nrlGroups.isEmpty() ? null : nrlGroups);
        if (!p.nrlCallsign().isEmpty() && p.nrlDmrId() > 0) {
            toast("正在连接服务器并获取频道");
            ui.postDelayed(this::connectNrl, 350L);
        } else {
            toast("服务器已切换，请先完善 NRL 呼号和 DMR ID");
        }
    }

    private void showNrlServerPicker() {
        java.util.List<NrlServers.Server> list = NrlServers.all(this);
        String[] labels = new String[list.size()];
        int current = 0;
        for (int i = 0; i < list.size(); i++) {
            NrlServers.Server s = list.get(i);
            labels[i] = s.pickerLabel();
            if (s.key.equals(p.nrlServerKey())) current = i;
        }
        final int cur = current;
        showSearchablePicker("选择 NRL 服务器", labels, cur, which -> {
            if (which < 0 || which >= list.size()) return;
            NrlServers.Server s = list.get(which);
            applyNrlServerSelection(s);
        });
    }

    private void showNrlAddServerDialog() {
        LinearLayout form = identityForm();
        EditText name = identityField("名称", "", InputType.TYPE_CLASS_TEXT);
        EditText host = identityField("地址（可带 :端口）", "", InputType.TYPE_CLASS_TEXT);
        EditText port = identityField("端口", "60050", InputType.TYPE_CLASS_NUMBER);
        EditText pw = identityField("服务器密码（可空）", "", InputType.TYPE_CLASS_TEXT);
        form.addView(row("名称", name));
        form.addView(row("地址", host));
        form.addView(row("端口", port));
        form.addView(row("密码", pw));
        new AlertDialog.Builder(this)
                .setTitle("添加 NRL 服务器")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String h = host.getText().toString().trim();
                    if (h.isEmpty()) { toast("服务器地址不能为空"); return; }
                    String line = (n.isEmpty() ? h : n) + "|" + h + "|" + port.getText().toString().trim() + "|" + pw.getText().toString().trim();
                    String old = p.nrlCustomServers();
                    p.set("nrlCustomServers", old == null || old.trim().isEmpty() ? line : old.trim() + "\n" + line);
                    java.util.List<NrlServers.Server> list = NrlServers.all(this);
                    String wantedName = n.isEmpty() ? h : n;
                    for (NrlServers.Server s : list) {
                        if (s.name.equals(wantedName)) {
                            applyNrlServerSelection(s);
                            break;
                        }
                    }
                    toast("NRL 服务器已添加");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void connectNrl() {
        if (p == null || !p.showNrl()) { toast("请先在设置里开启 NRL"); return; }
        if (nrlActive || nrlConnecting) { disconnectNrl(); return; }
        if (p.nrlCallsign().isEmpty() || p.nrlDmrId() <= 0) {
            toast("请先在身份设置里填写 NRL 呼号和 DMR ID");
            setPage(PAGE_SETTINGS);
            return;
        }
        if (nrl == null) nrl = new NrlClient();
        nrlConnecting = true;
        nrlActive = false;
        paintNrlConnectIcon();
        if (nrlCallState != null) { nrlCallState.setText("连接中"); nrlCallState.setTextColor(BLUE); }
        if (nrlCallLine != null) { nrlCallLine.setText("正在连接 " + p.nrlServerName()); nrlCallLine.setTextColor(BLUE); }
        refreshNrlServerButton();
        if (nrlGroups == null || nrlGroups.isEmpty()) nrlGroups = loadNrlGroupCache(p.nrlServerKey());
        syncCachedNrlGroupName();
        if (nrlGroupLine != null) {
            nrlGroupLine.setText(nrlGroups.isEmpty()
                    ? nrlGroupDisplayText() + "（待服务器确认）"
                    : nrlGroupDisplayText());
        }
        buildNrlGroupButtons(nrlGroups.isEmpty() ? null : nrlGroups);
        nrl.connect(p.nrlServerHost(), p.nrlServerPort(), p.nrlCallsign(), p.nrlDmrId(),
                p.nrlSsid(), p.nrlPassword(), p.nrlGroup());
    }

    private void disconnectNrl() {
        if (nrl != null) nrl.stop();
        if (nrlAudio != null) nrlAudio.stop();
        if (audio != null) try { audio.startMic(null); } catch (Throwable ignored) {}
        nrlActive = false;
        nrlConnecting = false;
        txing = false;
        activePttTarget = null;
        p.set("modeNrl", "0");
        paintNrlConnectIcon();
        if (nrlCallState != null) { nrlCallState.setText("未连接"); nrlCallState.setTextColor(SUB); }
        if (nrlCallLine != null) { nrlCallLine.setText("未登录"); nrlCallLine.setTextColor(TXT); }
        if (nrlGroupLine != null) nrlGroupLine.setText("当前组: " + p.nrlGroup());
        updateModeLabel();
    }

    private void paintNrlConnectIcon() {
        if (nrlConnectBtn == null) return;
        nrlConnectBtn.setText(nrlActive ? "断开" : (nrlConnecting ? "取消" : "连接"));
        nrlConnectBtn.setTextColor(Color.WHITE);
        nrlConnectBtn.setBackground(roundBg(nrlActive ? GREEN : ACCENT, 999));
    }

    private void onNrlVoice(String callsign, int ssid, short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        markModeSignal(8);
        if (!listenMode(8) || txing) return;
        if (nrlAudio != null) nrlAudio.play(pcm);
        String who = callsign == null || callsign.trim().isEmpty() ? "NRL" : callsign.trim();
        if (ssid > 0) who += "-" + ssid;
        final String peer = who;
        nrlRxStart = System.currentTimeMillis();
        nrlLastPeer = peer;
        setLastCall(8, peer);
        if (nrlCallState != null) { nrlCallState.setText("接收中"); nrlCallState.setTextColor(BLUE); }
        if (nrlCallLine != null) { nrlCallLine.setText(peer); nrlCallLine.setTextColor(RED); }
        ui.postDelayed(() -> {
            if (System.currentTimeMillis() - nrlRxStart < 900L) return;
            if (nrlCallState != null && !txing) { nrlCallState.setText("空闲"); nrlCallState.setTextColor(SUB); }
            if (nrlCallLine != null && !txing) { nrlCallLine.setText("等待呼叫"); nrlCallLine.setTextColor(TXT); }
        }, 1200L);
    }

    private void startNrlPtt() {
        if (!nrlActive || nrl == null) { toast("请先连接 NRL 服务器"); return; }
        if (txing) return;
        txing = true;
        activePttTarget = "nrl";
        txCallStart = System.currentTimeMillis();
        pttToneStart();
        scheduleTxTimeout();
        if (nrlCallState != null) { nrlCallState.setText("发射中"); nrlCallState.setTextColor(BLUE); }
        if (nrlCallLine != null) { nrlCallLine.setText(p.callsign()); nrlCallLine.setTextColor(TXT); }
        ui.post(this::paintNav);
        nrlAudio.startMic(frame -> { if (txing && nrl != null) nrl.sendOpus(frame); });
    }

    private void stopNrlPtt() {
        try { if (nrlAudio != null) nrlAudio.stopMic(); } catch (Throwable ignored) {}
        int sec = txCallStart == 0 ? 0 : (int) ((System.currentTimeMillis() - txCallStart) / 1000);
        if (sec == 0 && txing) sec = 1;
        if (txing) addNrlLog("发射", p.callsign(), sec);
        txing = false;
        activePttTarget = null;
        txCallStart = 0;
        pttToneStop();
        cancelTxTimeout();
        if (nrlCallState != null) { nrlCallState.setText("空闲"); nrlCallState.setTextColor(SUB); }
        if (nrlCallLine != null) { nrlCallLine.setText("等待呼叫"); nrlCallLine.setTextColor(TXT); }
        ui.post(this::paintNav);
    }

    private void addNrlLog(String direction, String peer, int sec) {
        final String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        ui.post(() -> {
            if (nrlLogBox == null) return;
            if (nrlLogBox.getChildCount() == 1 && nrlLogBox.getChildAt(0) instanceof TextView
                    && "暂无 NRL 通话记录".contentEquals(((TextView) nrlLogBox.getChildAt(0)).getText())) {
                nrlLogBox.removeAllViews();
            }
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(5), 0, dp(5));
            r.addView(tableCell(time, 0.85f, SUB, false));
            r.addView(tableCell(peer == null ? "" : peer, 1.15f, TXT, true));
            r.addView(tableCell(direction, 0.72f, BLUE, false));
            r.addView(tableCell(sec > 0 ? sec + "s" : "--", 0.55f, SUB, false));
            nrlLogBox.addView(r, 0);
            while (nrlLogBox.getChildCount() > visibleRows) nrlLogBox.removeViewAt(nrlLogBox.getChildCount() - 1);
        });
    }

    private void autoConnectNrlIfNeeded() {
        if (autoConnectSuppressed() || p == null || !p.showNrl() || !p.autoNrl()) return;
        if (nrlActive || nrlConnecting || p.callsign().isEmpty() || p.dmrId() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - nrlAutoAttemptAt < 5000L) return;
        nrlAutoAttemptAt = now;
        log("NRL 启动自动登录检查通过，准备连接 " + p.nrlServerName());
        connectNrl();
    }

    private class AudioLevelMeterView extends View {
        private static final long AUDIO_TICK_MS = 33L;
        private static final int LED_SEGMENTS = 52;
        private static final int LED_SEGMENTS_TINY = 44;
        private static final int GLASS_GREEN = 0xFF39D353;
        private static final int GLASS_YELLOW = 0xFFFFD60A;
        private static final int GLASS_RED = 0xFFFF3B30;

        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private volatile boolean ticking;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                ticking = false;
                if (getVisibility() != VISIBLE) return;
                invalidate();
                if (AudioLevelBus.rxActive() || AudioLevelBus.txActive()) ensureTicking();
            }
        };

        AudioLevelMeterView(android.content.Context c) {
            super(c);
            setWillNotDraw(false);
            android.graphics.drawable.GradientDrawable bg = cardBg();
            bg.setCornerRadius(dp(14));
            setBackground(bg);
            setElevation(dp(2));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            AudioLevelBus.setOnChange(this::ensureTicking);
        }

        @Override protected void onDetachedFromWindow() {
            AudioLevelBus.setOnChange(null);
            removeCallbacks(tick);
            ticking = false;
            super.onDetachedFromWindow();
        }

        void setMeterVisible(boolean visible) {
            setVisibility(visible ? VISIBLE : GONE);
            if (!visible) {
                removeCallbacks(tick);
                ticking = false;
                return;
            }
            invalidate();
            if (AudioLevelBus.rxActive() || AudioLevelBus.txActive()) ensureTicking();
        }

        private synchronized void ensureTicking() {
            if (ticking || getVisibility() != VISIBLE) return;
            if (!AudioLevelBus.rxActive() && !AudioLevelBus.txActive()) return;
            ticking = true;
            postDelayed(tick, AUDIO_TICK_MS);
        }

        private float currentDb() {
            float rx = AudioLevelBus.rxActive() ? AudioLevelBus.rxDb() : -120f;
            float tx = AudioLevelBus.txActive() ? AudioLevelBus.txDb() : -120f;
            return Math.max(rx, tx);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = dp(2);
            float barL = pad;
            float barR = Math.max(barL + dp(20), w - pad);
            float cy = h / 2f;
            drawMeterBase(c, barL, barR, cy);
            drawAudioSegments(c, barL, barR, cy);
        }

        private void drawMeterBase(android.graphics.Canvas c, float barL, float barR, float cy) {
            float barH = dp(tinyScreen ? 10 : 11);
            float top = cy - barH / 2f;
            float bottom = cy + barH / 2f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(withAlpha(0xFFFFFFFF, DARK ? 10 : 18));
            c.drawRoundRect(barL, top, barR, bottom, dp(6), dp(6), p);
            p.setColor(withAlpha(0xFFFFFFFF, DARK ? 20 : 80));
            c.drawRoundRect(barL + dp(1), top + dp(1), barR - dp(1), top + dp(2), dp(1), dp(1), p);
            p.setColor(withAlpha(0xFF000000, DARK ? 40 : 10));
            c.drawRoundRect(barL + dp(1), bottom - dp(1), barR - dp(1), bottom, dp(1), dp(1), p);
        }

        private void drawAudioSegments(android.graphics.Canvas c, float barL, float barR, float cy) {
            float barH = dp(tinyScreen ? 8 : 9);
            float top = cy - barH / 2f;
            float bottom = cy + barH / 2f;
            float db = currentDb();
            boolean active = AudioLevelBus.rxActive() || AudioLevelBus.txActive();
            float norm = active ? (db + 60f) / 60f : 0f;
            norm = Math.max(0f, Math.min(1f, norm));

            int gap = Math.max(1, dp(1));
            int width = Math.round(barR - barL);
            int count = tinyScreen ? LED_SEGMENTS_TINY : LED_SEGMENTS;
            int segW = Math.max(2, (width - gap * (count - 1)) / count);
            int totalW = count * segW + (count - 1) * gap;
            float startX = barL + Math.max(0, (width - totalW) / 2f);
            float centerIndex = (count - 1) / 2f;
            float reach = norm * (count / 2f + 0.4f);
            float segRadius = dp(tinyScreen ? 1 : 2);

            for (int i = 0; i < count; i++) {
                float d = Math.abs(i - centerIndex);
                float intensity = active
                        ? Math.max(0f, Math.min(1f, (reach - d + 1.2f) / 1.2f))
                        : 0f;
                float r = d / (count / 2f);
                int color = r <= 0.5f
                        ? blend(GLASS_GREEN, GLASS_YELLOW, r * 2f)
                        : blend(GLASS_YELLOW, GLASS_RED, (r - 0.5f) * 2f);
                float x = startX + i * (segW + gap);
                drawGlassSegment(c, x, top, bottom, segW, segRadius, intensity, color);
            }
        }

        private void drawGlassSegment(android.graphics.Canvas c, float x, float top, float bottom,
                                      int segW, float segRadius, float intensity, int color) {
            intensity = Math.max(0f, Math.min(1f, intensity));
            float lit = (float)Math.pow(intensity, 0.85f);
            float coreAlpha = (float)Math.sqrt(lit);
            float bezel = dp(1) / 2f;
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.FILL);

            if (lit > 0.02f) {
                for (int k = 3; k >= 1; k--) {
                    float e = dp(k) * 1.1f;
                    int a2 = Math.round(38f * lit * (4 - k) / 3f);
                    if (a2 <= 0) continue;
                    p.setColor(withAlpha(color, a2));
                    c.drawRoundRect(x - e, top - e, x + segW + e, bottom + e,
                            segRadius + e, segRadius + e, p);
                }
            }

            p.setColor(withAlpha(0xFF0B1520, DARK ? 92 : 55));
            c.drawRoundRect(x - bezel, top - bezel, x + segW + bezel, bottom + bezel,
                    segRadius + bezel, segRadius + bezel, p);

            p.setColor(withAlpha(color, DARK ? 42 : 58));
            c.drawRoundRect(x, top, x + segW, bottom, segRadius, segRadius, p);

            p.setColor(withAlpha(0xFFFFFFFF, DARK ? 45 : 110));
            c.drawRoundRect(x + dp(1), top + dp(1), x + segW - dp(1),
                    top + Math.max(dp(1), (bottom - top) * 0.38f), dp(1), dp(1), p);

            p.setColor(withAlpha(0xFF000000, DARK ? 55 : 20));
            c.drawRoundRect(x + dp(1), bottom - dp(2), x + segW - dp(1), bottom - dp(1),
                    dp(1), dp(1), p);

            if (lit > 0.015f) {
                p.setColor(withAlpha(color, Math.round(205f * coreAlpha)));
                c.drawRoundRect(x, top, x + segW, bottom, segRadius, segRadius, p);
                p.setColor(withAlpha(color, Math.round(235f * coreAlpha)));
                c.drawRoundRect(x + dp(1), top + dp(1), x + segW - dp(1), bottom - dp(1),
                        segRadius, segRadius, p);
                float midY = (top + bottom) / 2f;
                p.setColor(withAlpha(0xFFFFFFFF, Math.round(190f * coreAlpha)));
                c.drawRoundRect(x + dp(1), midY - dp(1), x + segW - dp(1), midY + dp(1),
                        dp(1), dp(1), p);
            }
        }
    }
    private class TechGridDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint grid = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint cyberHorizon = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Shader shader;
        private android.graphics.Shader cyberShader;
        private int shaderW = -1, shaderH = -1;

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawColor(BG);
            int w = b.width(), h = b.height();
            if (shader == null || shaderW != w || shaderH != h) {
                shader = new android.graphics.RadialGradient(w / 2f, -h * 0.06f,
                        Math.max(w, h) * 0.58f,
                        new int[]{withAlpha(ACCENT, DARK ? 34 : 14), withAlpha(ACCENT, 0)},
                        null, android.graphics.Shader.TileMode.CLAMP);
                shaderW = w;
                shaderH = h;
            }
            glow.setShader(shader);
            c.drawRect(b, glow);
            if (cyberTheme) {
                if (cyberShader == null) {
                    cyberShader = new android.graphics.LinearGradient(0f, h * 0.48f, 0f, h,
                            new int[]{ 0x0000E5FF, 0x1100E5FF, 0x2A00E5FF },
                            null, android.graphics.Shader.TileMode.CLAMP);
                }
                cyberHorizon.setShader(cyberShader);
                c.drawRect(b, cyberHorizon);
            }
            grid.setStyle(android.graphics.Paint.Style.STROKE);
            grid.setStrokeWidth(1f);
            grid.setColor(cyberTheme ? withAlpha(0xFF00E5FF, 18) : withAlpha(TXT, DARK ? 10 : 7));
            int step = dp(cyberTheme ? 24 : 36);
            for (int x = b.left; x <= b.right; x += step) c.drawLine(x, b.top, x, b.bottom, grid);
            for (int y = b.top; y <= b.bottom; y += step) c.drawLine(b.left, y, b.right, y, grid);
            if (cyberTheme) {
                grid.setStyle(android.graphics.Paint.Style.FILL);
                grid.setColor(withAlpha(0xFF00E5FF, 5));
                for (int y = b.top; y <= b.bottom; y += dp(4)) c.drawRect(b.left, y, b.right, y + 1, grid);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private class HudCornersView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        HudCornersView(android.content.Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            int w = getWidth(), h = getHeight(), m = dp(14), len = dp(24);
            p.setColor(withAlpha(0xFFFFFF, DARK ? 95 : 70));
            c.drawLine(m, m, m + len, m, p); c.drawLine(m, m, m, m + len, p);
            c.drawLine(w - m, m, w - m - len, m, p); c.drawLine(w - m, m, w - m, m + len, p);
            c.drawLine(m, h - m, m + len, h - m, p); c.drawLine(m, h - m, m, h - m - len, p);
            c.drawLine(w - m, h - m, w - m - len, h - m, p); c.drawLine(w - m, h - m, w - m, h - m - len, p);
            p.setColor(withAlpha(ACCENT, DARK ? 190 : 150));
            c.drawLine(w / 2 - dp(10), m, w / 2 + dp(10), m, p);
        }
    }

}


