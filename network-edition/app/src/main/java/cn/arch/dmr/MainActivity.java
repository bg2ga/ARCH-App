package cn.arch.dmr;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.provider.Settings;
import android.net.Uri;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Network-intercom-only edition: network room + radio + APRS + identity/settings. */
public class MainActivity extends Activity implements NetworkRoomClient.Listener {
    private static final String VOICE_URL = "wss://hljrsa.cn:50113/voice";
    private static final String ROOMS_URL = "https://hljrsa.cn:50113/api/rooms";
    private static final String ACCOUNT_API = "https://hljrsa.cn:50113/api/account/";
    private static final int REQ_PERMS = 7;
    private static final int REQ_BACKGROUND_LOCATION = 8;
    private static final String[] ACCENT_KEYS = {"blue", "cyan", "violet", "amber", "rose", "cyber"};
    private static final String[] ACCENT_LABELS = {"科技蓝", "青蓝", "紫罗兰", "琥珀", "玫红", "赛博"};
    private static final int[] ACCENT_LIGHT = {0xFF0284C7, 0xFF0891B2, 0xFF7C3AED, 0xFFD97706, 0xFFE11D48, 0xFFFF2BD6};
    private static final int[] ACCENT_DARK = {0xFF38BDF8, 0xFF22D3EE, 0xFFA78BFA, 0xFFFBBF24, 0xFFFB7185, 0xFFFF2BD6};
    private static final int FONT_GROUP_ID_BASE = 7400;
    private static final int ROOM_LOAD_MAX_ATTEMPTS = 12;
    private static final long ROOM_LOAD_RETRY_MS = 2500L;
    private static final int FOCUS_TAG_KEY = 0x0F0C0101;
    private static final int FOCUS_BG_TAG_KEY = 0x0F0C0102;
    private static final int FOCUS_BG_ON_TAG_KEY = 0x0F0C0103;
    private static final int FOCUS_TEXT_COLOR_TAG_KEY = 0x0F0C0104;
    private static final int FOCUS_ACTION_TAG_KEY = 0x0F0C0105;
    private static final int DMR_GROUP_TAG_KEY = 0x0F0C0106;
    private static final int TOP_NAV_TAG_KEY = 0x0F0C0107;
    private static final int APRS_NAV_TAG_KEY = 0x0F0C0108;
    private static final int PNC370_FOCUS_AT_TAG_KEY = 0x0F0C0109;
    private static final int TOP_NAV_FOCUS_TEXT = 0xFFFFB020;
    private static final int MODE_SIGNAL_DIGITAL = 0;
    private static final int MODE_SIGNAL_NETWORK = 1;
    private static final int MODE_SIGNAL_DMR = 2;
    private final java.util.concurrent.atomic.AtomicLongArray modeSignalUntil =
            new java.util.concurrent.atomic.AtomicLongArray(3);
    private final Runnable modeSignalRefreshRunnable = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long wait = 0L;
            for (int i = 0; i < 3; i++) {
                long remain = modeSignalUntil.get(i) - now;
                if (remain > wait) wait = remain;
            }
            refreshTopNavColors();
            if (wait > 0L) ui.postDelayed(this, wait + 80L);
        }
    };
    private static volatile MainActivity activeActivity;
    private boolean completeExitInProgress;
    static boolean supportsPdc760Bridge() {
        return HyteraDeviceProfile.detect().isPdcBridge();
    }

    static boolean supportsHyteraPtt() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase(Locale.US);
        // PNC370/PNC380 use the PNC key bridge. Do not bind the PDC760 call manager
        // on these devices: some PNC firmware blocks the UI thread during that bind.
        if (HyteraDeviceProfile.detect().isPncSmall()) return false;
        return maker.equalsIgnoreCase("Hytera") || model.startsWith("PDC") || model.startsWith("PTC")
                || model.startsWith("VM7") || model.startsWith("DSJ-HYT");
    }
    static boolean hasActiveActivity() { return activeActivity != null; }
    public static boolean dispatchHardwarePtt(boolean down) {
        MainActivity a = activeActivity;
        if (a == null) return false;
        a.ui.post(() -> { if (down) a.pttDown(); else a.pttUp(); });
        return true;
    }

    /** Apply the screen-off hardware volume level to the active playback track. */
    static boolean dispatchOutputVolume(int stream, float volume) {
        MainActivity a = activeActivity;
        if (a == null) return false;
        a.ui.post(() -> a.applyCurrentOutputVolume(stream, volume));
        return true;
    }

    /** Re-apply the intercom route after a SIP call or SIP failure. */
    static void restoreAudioRouteAfterSip() {
        MainActivity a = activeActivity;
        if (a == null) return;
        a.ui.postDelayed(a::restoreAudioRouteAfterSipNow, 120L);
    }

    private void restoreAudioRouteAfterSipNow() {
        try {
            pncAudioPrepared = false;
            applyAudioRouteNow(false);
            android.util.Log.i("ARCH-AUDIO", "intercom route restored after SIP");
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "intercom route restore failed: " + t);
        }
    }
    public static boolean dispatchHyteraKnob(boolean next) {
        MainActivity a = activeActivity;
        if (a == null) return false;
        a.ui.post(() -> a.rotateRoom(next));
        return true;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private OkHttpClient roomHttp;
    private final ExecutorService audioIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "network-audio-io");
        t.setDaemon(true);
        return t;
    });

    private void runAudioIo(Runnable task) {
        if (task == null || audioIo.isShutdown()) return;
        try { audioIo.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private final ExecutorService toneIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arch-ptt-tone");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService routeIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arch-audio-route");
        t.setDaemon(true);
        return t;
    });

    private long lastIncomingVoiceAt;
    private final Runnable receiveWatchdog = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long remaining = 1500L - (now - lastIncomingVoiceAt);
            if (remaining > 0L) {
                ui.postDelayed(this, remaining);
                return;
            }
            lastIncomingVoiceAt = 0L;
            if (!pttHeld && !pttGranted) {
                // The network layer does not always deliver onVoiceEnded when a
                // talkspurt stops. Clear the PDC POC receive state explicitly.
                try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
                flushReceivePlayback();
                setNetworkUiState("已连接", false);
            }
        }
    };

    private Prefs p;
    private NetworkRoomClient net;
    private int networkHandoffToken;
    private HqAudio audio;
    private AudioEngine dmrAudio;
    private AudioEngine bmAudio;
    private HbpClient dmrHbp;
    private HbpClient bmHbp;
    private Vocoder dmrVoc;
    private Vocoder bmVoc;
    private ContactBook dmrContacts;
    private volatile boolean dmrConnected;
    private volatile boolean dmrRxActive;
    private volatile boolean dmrTxing;
    private volatile boolean dmrManualDisconnect;
    private volatile boolean bmPage;
    private volatile boolean bmConnected;
    private volatile boolean bmRxActive;
    private volatile boolean bmManualDisconnect;
    private boolean bmPendingPtt;
    private volatile boolean bmCredentialSyncRunning;
    private volatile boolean bmAutoStaticRunning;
    private int bmAttachGeneration;
    private volatile int dmrConnectionGeneration;
    private volatile int bmConnectionGeneration;
    private long bmLastConnectAttempt;
    private long bmRxStart;
    private Runnable bmReconnectTask;
    private Runnable bmConnectTask;
    private NetworkRecovery.Listener networkRecoveryListener;
    private volatile boolean networkLossSeen;
private volatile String bmActiveCallsign = "";
    private volatile int bmActiveId;
    private int bmRxVoiceFrames;
    private volatile long bmLastVoiceAt;
    private volatile long bmRxTalkMs;
    private static final long BM_RX_HANG_MS = 1200L;
    private final Runnable bmRxWatchdog = new Runnable() {
        @Override public void run() {
            if (!bmRxActive) return;
            long now = System.currentTimeMillis();
            long idle = now - bmLastVoiceAt;
            if (idle < BM_RX_HANG_MS) {
                ui.postDelayed(this, BM_RX_HANG_MS - idle);
                return;
            }
            bmRxTalkMs = bmRxStart > 0 && bmLastVoiceAt > bmRxStart
                    ? bmLastVoiceAt - bmRxStart : 0L;
            bmRxActive = false;
            bmActiveCallsign = "";
            bmActiveId = 0;
            try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
            clearModeSignal(MODE_SIGNAL_DMR);
            refreshDeviceIndicatorForAudio();
            if (bmPage) updateBmUi("空闲", false);
            android.util.Log.i("ARCH-DMR", "RX idle watchdog: green LED off");
        }
    };
    private final java.util.ArrayList<View> bmTgButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> bmTgValues = new java.util.ArrayList<>();
    private Button bmAddButton, bmManageButton;
    private long dmrRxStart;
    private long dmrTxStart;
    private int dmrTxBursts;
    private byte[] dmrTxAcc = new byte[0];
    private volatile boolean dmrAutoAttachRunning;
    private int dmrAttachGeneration;
    private final Object dmrPlayLock = new Object();
    private final java.util.ArrayDeque<short[]> dmrPlayQueue = new java.util.ArrayDeque<>();
    private volatile Thread dmrPlayThread;
    private volatile boolean dmrPlayRun;
    private volatile int dmrPlayGeneration;
    private volatile long dmrLastVoiceAtNs;
    private final Object bmPlayLock = new Object();
    private final java.util.ArrayDeque<short[]> bmPlayQueue = new java.util.ArrayDeque<>();
    private volatile Thread bmPlayThread;
    private volatile boolean bmPlayRun;
    private volatile int bmPlayGeneration;
    private volatile long bmLastVoiceAtNs;
    private Runnable dmrReconnectTask;
    private final java.util.ArrayList<Button> dmrTgButtons = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> dmrTgValues = new java.util.ArrayList<>();
    private String lastPlaybackSpeaker = "";
    private volatile boolean receivePlaybackDirty = false;
    private volatile boolean networkReceiving = false;
    private volatile boolean networkUiReleased = false;
    private String networkReleasedPeer = "";
private volatile int receivePlaybackGeneration = 0;
    private final Object receivePlaybackLock = new Object();
    private FmoOpus opus;
    private AprsClient aprs;
        private volatile AudioDeviceInfo pnc380MainMicDevice;
private RadioPlayer radio;
    private volatile boolean digitalPage = true;
    private volatile int lastHbpMode = MODE_SIGNAL_DIGITAL;
    private volatile boolean netPage = false;
    private boolean radioPage = false;
    private volatile boolean pttHeld;
    private volatile boolean pttGranted;
    private volatile int activePttMode = -1;
    private volatile int pttGeneration;
    private boolean localPttVisualActive;
    private boolean micOn;
    private long pttRequestAt;
    private long pncLastKeyboardRequestAt;
    private long pttFirstFrameAt;
    private volatile boolean networkWanted;
    private boolean radioWasPlayingForPtt;
    // 10 frames = 200 ms of pre-roll.  The pre-roll is drained at the same
    // 20 ms cadence as live audio; never burst it into the network in one shot.
    private static final int PTT_PREBUFFER_FRAMES = 10;
    private static final int PTT_SEND_QUEUE_FRAMES = 30;
    private static final long PTT_FRAME_NS = 20_000_000L;
    private final Object pttSendLock = new Object();
    private final ArrayDeque<short[]> pttPrebuffer = new ArrayDeque<>();
    private final ArrayDeque<short[]> pttSendQueue = new ArrayDeque<>();
    private volatile boolean pttSenderRun;
    private volatile int pttSenderGeneration;
    private Thread pttSenderThread;
    private String currentRoom = "";
    private String aprsState = "APRS: 未启动";
    private String networkStatus = "准备连接";

    private int BG, CARD, TXT, SUB, ACCENT, GREEN, RED, BORDER;
    private LinearLayout body;
    private LinearLayout roomBox;
    private LinearLayout networkDock;
    private TextView pncOnlineStrip;
    private LinearLayout userBox;
    private LinearLayout stationBox;
    private TextView bitrateText;
    private TextView radioStateText;
    private Button netTab;
    private Button radioTab;
    private Button pttButton;
    private LinearLayout pttWrap;
    private EditText radioSearch;
    private RadioWaveView radioWave;
    private LinearLayout radioProvinceGrid, radioCityGrid, radioRecentBox, radioListBox, radioFavoriteBox, radioLinkBox;
    private TextView radioStatus, radioNowTitle, radioNowSub;
    private TextView radioProvinceHeader, radioCityHeader, radioListHeader, radioRecentHeader, radioFavoriteHeader, radioLinkHeader;
    private boolean radioProvinceOpen = true, radioCityOpen = true, radioListOpen = true, radioRecentOpen = true, radioFavoriteOpen = true, radioLinkOpen = false;
    private final List<RadioStation> radioStations = new ArrayList<>();
    private RadioStation radioCurrent;
    private String radioProvince = "全部", radioCity = "全部城市";
    private long radioCatalogLastRefresh = 0L;
    private List<Room> rooms = new ArrayList<>();
    private List<NetworkRoomClient.Member> members = new ArrayList<>();
    private List<RadioStation> allStations = new ArrayList<>();
    private String roomSelection = "";
    private String lockedRoom = "";
    private int lockedDigitalTg = 0;
    private int lockedBmTg = 0;
    private boolean tinyScreen;
    private boolean shortScreen;
    private boolean keyNavigation;
    private boolean pnc370ConfirmDownSeen;
    private long pnc370LastConfirmActionAt;
    private boolean groupConfirmGestureActive;
    private int groupConfirmGestureKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private View groupConfirmGestureTarget;
    private boolean pnc370NumericEditing;
    private boolean authScreen;
    private int authRegisterStep;
    private View authFooter;
    private String pendingRegCallsign = "";
    private String pendingRegDmrId = "";
    private String pendingRegEssid = "0";
    private boolean roomsLoading;
    private int roomsLoadAttempt;
    private ScrollView mainScroll;
    private View activeRoomView;
    private final List<View> roomFocusViews = new ArrayList<>();
    private final List<String> roomFocusIds = new ArrayList<>();
    private boolean roomConfirmLongPressed;
    private Runnable roomConfirmLongPressAction;
    private float fontFactor = 1f;
    private DeviceUiProfile uiProfile;
    private HyteraDeviceProfile hardwareProfile;
    private boolean cyberTheme;
    private Dialog activeSettingsDialog;
    private TextView settingsFocusHint;
    private ImageButton radioIconButton;
    private ImageButton satIconButton;
    private ImageButton sipIconButton;
    private volatile boolean sipRegistered;
    private volatile long lastNetworkVoiceUiAt;
    private final SipRuntime.Listener sipStatusListener = new SipRuntime.Listener() {
        @Override public void onSipState(String state, boolean registered) {
            applySipNavState(registered);
        }
        @Override public void onIncoming(String remoteUri) {
            ui.post(MainActivity.this::pauseIntercomAudioForSip);
        }
        @Override public void onCallState(String state, String remoteUri) {
            if (state != null && !state.startsWith("已挂断")
                    && !state.startsWith("通话结束") && !state.startsWith("通话失败")) {
                ui.post(MainActivity.this::pauseIntercomAudioForSip);
            }
        }
        @Override public void onError(String message) {}
    };
    private TextView bmIconButton;
    private TextView dmrIconButton;
    private TextView netIconButton;
    private TextView dmrCallText, dmrTgText, dmrLastText, dmrTimerText, dmrCallsignText, dmrGroupStatusText;
    private volatile String dmrActiveCallsign = "";
    private LinearLayout dmrTgRow, dmrLogBox, bmTgRow;
    private TextView bmTgText, bmCallsignText, bmLastText, bmGroupStatusText, bmServerText, bmCountryText;
    private LinearLayout bmCallerBox;
    private ImageButton settingsIconButton;
    private FrameLayout rootFrame;
    private AudioManager audioManager;
    private volatile ToneGenerator pttTone;
    private volatile boolean pttToneInitializing;
    private BroadcastReceiver overlayPttReceiver;
    private boolean pttToggleActive;
    private boolean micRestarting;
    private boolean micHealthCheckPending;
    private volatile boolean pncAudioPrepared;
    private Runnable pttTotTask;
    private Runnable pttHealthWatchdog;
    private TextView callSignText;
    private TextView callIdentityText;
    private TextView callRoomText;
    private View callRoomBox;
    private TextView callStateText;
    private TextView callPartyText;
    private ImageView callLockIcon;
    private int deviceIndicatorMode = -1;
    // UI order: 红十字、460175、哈市、二区、广州、中国。
    // 460451 must stay on TS1; 46002 must stay on TS2.
    private static final int[] DMR_DEFAULT_TGS = {10, 460175, 460451, 46002, 46020, 46001, 4602, 11};
    private static final String[] DMR_DEFAULT_TG_NAMES = {"红十字组", "460175组", "哈市组", "二区组", "广州组", "全国组", "协会组", "私聊组"};
    private static final int[] DMR_DEFAULT_TG_SLOTS = {1, 2, 1, 2, 1, 2, 2, 2};
    private static final int[] DMR_BASE_TGS = {460451, 46002, 46020, 46001, 4602, 11};
    private boolean dmrAllow10 = false;
    private boolean dmrAllow175 = false;
    private final java.util.concurrent.atomic.AtomicBoolean accountGroupRefreshRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final class Room {
        final String id, name, desc;
        final int online;
        final boolean restricted;
        Room(String id, String name, String desc, int online) {
            this(id, name, desc, online, false);
        }
        Room(String id, String name, String desc, int online, boolean restricted) {
            this.id = id == null ? "" : id;
            this.name = name == null ? id : name;
            this.desc = desc == null ? "" : desc;
            this.online = online;
            this.restricted = restricted;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        activeActivity = this;
        p = new Prefs(this);
        Intent launchIntent = getIntent();
        boolean backgroundLaunch = launchIntent != null
                && (launchIntent.getBooleanExtra(ArchBootReceiver.EXTRA_BOOT_START, false)
                    || launchIntent.getBooleanExtra(NetworkKeepAliveService.EXTRA_SERVICE_RESTORE, false));
        if (!backgroundLaunch) p.setUserExited(false);
        BmServers.load(this);
        dmrAllow10 = p.dmrAllow10();
        dmrAllow175 = p.dmrAllow175();
        ensureVisibleDmrTg();
        normalizeDmrTalkgroupSlot();
        try { dmrContacts = new ContactBook(this); dmrContacts.loadAsync(null); } catch (Throwable ignored) {}
        uiProfile = DeviceUiProfile.detect(this);
        hardwareProfile = HyteraDeviceProfile.detect();
        if (hardwareProfile.isPncSmall()) installPncLauncherAsync();
        if (uiProfile.keyNavigation || hardwareProfile.isPncSmall()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        if (hardwareProfile.isPncSmall()) {
            try {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(Color.BLACK);
                getWindow().setNavigationBarColor(Color.BLACK);
            } catch (Throwable ignored) {}
            try {
                Intent voice = new Intent("com.hytera.android.poc.voip.conc.on");
                voice.setComponent(new android.content.ComponentName("com.meigsmart.setting",
                        "com.meigsmart.setting.activitys.BootWifiReceiver"));
                sendBroadcast(voice);
            } catch (Throwable ignored) {}
        }
        if (uiProfile.vm780 && !"1".equals(p.get("keepScreenFlagMigration50", "0"))) {
            p.set("keepScreen", "0");
            p.set("keepScreenFlagMigration50", "1");
        }
        roomHttp = new OkHttpClient.Builder()
                .dns(ArchDns.get())
                .sslSocketFactory(TlsCompat.socketFactory(this), TlsCompat.trustManager(this))
                .build();
        lockedRoom = p.lockedNetRoom();
        lockedDigitalTg = p.lockedDigitalTg();
        lockedBmTg = p.lockedBmTg();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        // ToneGenerator creation talks to the vendor AudioService. On some PNC
        // firmware that binder call can stall during boot, so never do it on the UI thread.
        loadFontFactor();
        setTheme(p.dark() ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        applyColors();
        currentRoom = p.netRoom();
        roomSelection = currentRoom;
        tinyScreen = uiProfile.tinyScreen;
        shortScreen = uiProfile.shortScreen;
        keyNavigation = uiProfile.keyNavigation;

        net = NetworkRuntime.get(this).client();
        // Regression fix: match the long-stable network build and Hytera internal
        // edition. Network voice is 24 kHz mono and must not keep a warm recorder.
        int networkRecordRate = (hardwareProfile != null && hardwareProfile.isPnc380()) ? 48000 : 24000;
        audio = new HqAudio(24000, networkRecordRate);
        configureNetworkMicDevice(audio);
        opus = new FmoOpus(24000);
        radio = new RadioPlayer(this);
        radio.setListener((st, msg) -> ui.post(() -> updateRadioPlayerState(st, msg)));
        applyAudioPrefs();
        applyAudioRoute(false);
        registerOverlayPttReceiver();
        if (supportsPdc760Bridge()) {
            try {
                HyteraPdc760Bridge.setListener(msg -> android.util.Log.i("ARCH-HYTERA", msg == null ? "" : msg));
                HyteraPdc760Bridge.install(this);
                refreshHyteraDisplay();
            } catch (Throwable ignored) {}
        }
        requestNeededPermissions();
        if (p.authLoggedIn() && identityReady()) {
            enterMainUi();
            refreshAccountTalkgroupsInBackground();
        } else if (p.authLoggedIn() && !p.callsign().isEmpty() && p.password().length() >= 6) {
            restoreAccountSession();
        } else showLoginScreen();
    }

    private void installPncLauncherAsync() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try { PncLauncherBridge.install(app); } catch (Throwable ignored) {}
        }, "pnc-launcher-install").start();
    }

    private void startNetworkRecovery() {
        if (networkRecoveryListener != null) return;
        networkRecoveryListener = new NetworkRecovery.Listener() {
            @Override public void onNetworkLost() {
                ui.post(() -> {
                    networkLossSeen = true;
                    if (digitalPage) updateDmrUi("网络已断开，等待恢复", false);
                    if (bmPage) updateBmUi("网络已断开，等待恢复", false);
                    if (netPage) setNetworkUiState("网络已断开，等待恢复", true);
                    if (aprs != null) aprsState = "APRS: 网络已断开";
                    android.util.Log.i("ARCH-NET", "modes waiting for OS network restore");
                });
            }

            @Override public void onNetworkRestored() {
                ui.post(MainActivity.this::reconnectModesAfterNetworkRestore);
            }
        };
        NetworkRecovery recovery = NetworkRecovery.get(this);
        networkLossSeen = !recovery.isOnline();
        recovery.addListener(networkRecoveryListener);
    }

    private void reconnectModesAfterNetworkRestore() {
        networkLossSeen = false;
        android.util.Log.i("ARCH-NET", "network restored/switched, reconcile enabled modes");
        if (p.showDigital() && !dmrManualDisconnect
                && (dmrHbp == null || !dmrHbp.isConnected())) {
            disconnectDmr(false);
            connectDmrIfNeeded();
        }
        if (p.dmrDirectEnabled() && !bmManualDisconnect
                && (bmHbp == null || !bmHbp.isConnected())) {
            disconnectBm();
            bmLastConnectAttempt = 0L;
            connectBmIfNeeded(true);
        }
        if (p.showNetwork()) {
            NetworkRuntime runtime = NetworkRuntime.get(this);
            runtime.ensureConfiguredConnection();
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
        }
        if (p.aprsEnabled() && (aprs == null || !aprs.isConnected())) restartAprs();
    }

    private void startKeepAliveService() {
        try {
            Intent keep = new Intent(this, NetworkKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
        } catch (Throwable ignored) {}
    }

    private void maybeStartPendingPtt() {
        if (bmPage && bmPendingPtt && bmHbp != null && bmHbp.isConnected() && !pttHeld) {
            bmPendingPtt = false; pttDown(); return;
        }
        long at = ArchAppState.pendingPttAt;
        if (!ArchAppState.pendingPttDown || System.currentTimeMillis() - at > 8000L) return;
        if (digitalPage) {
            if (dmrHbp != null && dmrHbp.isConnected() && !pttHeld) {
                ArchAppState.pendingPttDown = false;
                pttDown();
            } else {
                connectDmrIfNeeded();
            }
            return;
        }
        if (net != null && net.isRunning() && !pttHeld) {
            ArchAppState.pendingPttDown = false;
            pttDown();
        }
    }

    private void stopOtherVoiceAppsAfterBoot() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            String[] packages = {
                    "com.hytalkpro.ocean",
                    "com.corget",
                    "com.kylindev.totalk",
                    "com.nawensoft.nsptt"
            };
            for (String pkg : packages) {
                try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private boolean hasTouchscreen() {
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private void loadFontFactor() {
        float user = p == null ? 0f : p.uiFontFactor();
        if (user <= 0f) user = 1f;
        float screenScale = uiProfile == null ? 1f : uiProfile.fontScale;
        fontFactor = user * screenScale;
    }

    private void applyColors() {
        boolean dark = p.dark();
        if (dark) {
            BG = 0xFF070B10; CARD = 0xFF0F151D; TXT = 0xFFF3F7FB; SUB = 0xFF93A1B2;
            ACCENT = 0xFF22D3EE; GREEN = 0xFF4ADE80; RED = 0xFFFF6B6B; BORDER = 0xFF233041;
        } else {
            BG = 0xFFF3F6FA; CARD = 0xFFFFFFFF; TXT = 0xFF0B1220; SUB = 0xFF5F6F82;
            ACCENT = 0xFF0284C7; GREEN = 0xFF16A34A; RED = 0xFFDC2626; BORDER = 0xFFDCE5EF;
        }
        String accent = accentKey();
        cyberTheme = "cyber".equals(accent);
        if (cyberTheme) {
            BG = 0xFF05020A; CARD = 0xFF0C0616; TXT = 0xFFF6E9FF; SUB = 0xFFB39AD0;
            ACCENT = 0xFFFF2BD6; GREEN = 0xFF39FF14; RED = 0xFFFF3864; BORDER = 0xFF3A1450;
        } else {
            ACCENT = accentColorFor(accent, dark);
        }
    }

    private String accentKey() {
        String key = p == null ? "blue" : p.themeAccent();
        for (String k : ACCENT_KEYS) if (k.equals(key)) return key;
        return "blue";
    }

    private static int accentColorFor(String key, boolean dark) {
        int idx = 0;
        for (int i = 0; i < ACCENT_KEYS.length; i++) if (ACCENT_KEYS[i].equals(key)) { idx = i; break; }
        return dark ? ACCENT_DARK[idx] : ACCENT_LIGHT[idx];
    }

    private void applyWindowTheme() {
        try {
            boolean forceDarkSystemBar = uiProfile != null && uiProfile.keyNavigation;
            int bar = forceDarkSystemBar ? Color.BLACK : CARD;
            getWindow().setStatusBarColor(bar);
            getWindow().setNavigationBarColor(bar);
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!forceDarkSystemBar && !p.dark() && !cyberTheme) flags |= 0x00002000;
            else flags &= ~0x00002000;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    private void reThemeInPlace() {
        if (authScreen || !p.authLoggedIn()) {
            showLoginScreen();
            return;
        }
        // Recreate the Activity so the framework theme, custom colors, fonts,
        // dialog resources and all child controls are rebuilt together.
        try { recreate(); } catch (Throwable t) { setContentView(buildRoot()); applyWindowTheme(); }
    }

    private void enterMainUi() {
        try { InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0); } catch (Throwable ignored) {}
        authScreen = false;
        authRegisterStep = 0;
        // Restore the last active page first; enabled modes connect independently.
        String initialMode = p.lastPageMode();
        digitalPage = "digital".equals(initialMode) && p.showDigital();
        netPage = "network".equals(initialMode) && p.showNetwork();
        bmPage = "dmr".equals(initialMode) && p.dmrDirectEnabled();
        radioPage = "radio".equals(initialMode) && p.showNetRadio();
        if (!digitalPage && !netPage && !bmPage && !radioPage) {
            if (p.showDigital()) digitalPage = true;
            else if (p.showNetwork()) netPage = true;
            else if (p.dmrDirectEnabled()) bmPage = true;
            else if (p.showNetRadio()) radioPage = true;
        }
        android.util.Log.i("ARCH-STATE", "initial page digital=" + digitalPage
                + " network=" + netPage + " dmr=" + bmPage + " radio=" + radioPage
                + " last=" + initialMode);
        syncVolumeControlStream();
        setContentView(buildRoot());
        applyWindowTheme();
        initPttToneAsync();
        startKeepAliveService();
        startNetworkRecovery();
        if (p.showNetwork()) loadRooms();
        loadRadioCatalog(true);
        restartAprs();
        if (getIntent() != null && getIntent().getBooleanExtra(ArchBootReceiver.EXTRA_BOOT_START, false)) {
            ui.postDelayed(this::stopOtherVoiceAppsAfterBoot, 1500L);
            ui.postDelayed(this::stopOtherVoiceAppsAfterBoot, 12000L);
        }

        ui.postDelayed(this::maybeStartPendingPtt, 1200L);
        ui.postDelayed(this::maybeStartPendingPtt, 2600L);
        ui.postDelayed(() -> new Updater(this).check(true, null), 5000L);
        ui.postDelayed(() -> {
            try { if (HyteraPdc760Bridge.consumePendingPttDown(this)) pttDown(); } catch (Throwable ignored) {}
        }, 900L);
    }

    private void clearMainViewRefs() {
        body = null;
        roomBox = null;
        userBox = null;
        activeRoomView = null;
        roomFocusViews.clear();
        mainScroll = null;
    }

    private LinearLayout authField(String label, EditText edit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView caption = text(label, authLabelSp(), SUB, false);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, 0, 0, dp(shortScreen ? 1 : 2));
        box.addView(caption);
        box.addView(edit, new LinearLayout.LayoutParams(-1, dp(authFieldHeightDp())));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(shortScreen ? 1 : 4), 0, dp(shortScreen ? 1 : 4));
        box.setLayoutParams(lp);
        return box;
    }

    private EditText authInput(String hint, String value, int inputType) {
        EditText e = input(hint, value, inputType);
        e.setSingleLine(true);
        e.setTextSize(Math.max(11f, authInputSp() * fontFactor));
        e.setGravity(Gravity.CENTER);
        e.setPadding(dp(9), dp(2), dp(9), dp(2));
        GradientDrawable bg = roundBg(p.dark() ? 0xFF121B27 : 0xFFF6F9FC, 12);
        bg.setStroke(dp(1), BORDER);
        e.setBackground(bg);
        return e;
    }

    private Button authButton(String label, boolean primary) {
        Button b = button(label, primary);
        b.setTextSize(Math.max(11f, authButtonSp() * fontFactor));
        b.setMinHeight(dp(authActionHeightDp()));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : CARD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), primary ? ACCENT : BORDER);
        b.setBackground(bg);
        return b;
    }

    private void setAuthStatus(TextView status, String message, int color) {
        if (status == null) return;
        boolean show = message != null && !message.trim().isEmpty();
        status.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            status.setTextColor(color);
            status.setText(message);
        }
    }

    private void prepareAuthWindow() {
        try {
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) {}
    }

    private float displayWidthDp() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels / Math.max(1f, dm.density);
    }

    private boolean tabletAuthLayout() {
        return uiProfile != null && uiProfile.tablet;
    }

    private int authFormWidthPx() {
        float widthDp = displayWidthDp();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (widthDp >= 850f) return dp(460);
        if (widthDp >= 600f) return dp(430);
        return Math.max(dp(260), dm.widthPixels - dp(12));
    }

    private int authFieldHeightDp() {
        return uiProfile == null ? 44 : uiProfile.authFieldHeightDp;
    }

    private int authActionHeightDp() {
        return uiProfile == null ? 46 : uiProfile.authActionHeightDp;
    }

    private int authLabelSp() { return uiProfile == null ? 11 : uiProfile.authLabelSp; }
    private float authInputSp() { return uiProfile == null ? 15f : uiProfile.authInputSp; }
    private float authButtonSp() { return uiProfile == null ? 14f : uiProfile.authButtonSp; }
    private int authLogoDp() { return uiProfile == null ? 46 : uiProfile.authLogoDp; }
    private int authBrandSp() { return uiProfile == null ? 24 : uiProfile.authBrandSp; }

    private FrameLayout authScaffold(LinearLayout body, View footer) {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                p.dark() ? new int[]{0xFF070B10, 0xFF101A26, 0xFF070B10}
                        : new int[]{0xFFF4F8FC, 0xFFEAF3FA, 0xFFF7FAFD});
        frame.setBackground(bg);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        mainScroll = sv;
        FrameLayout authContent = new FrameLayout(this);
        int formWidth = authFormWidthPx();
        FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(
                formWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        authContent.addView(body, bodyLp);
        sv.addView(authContent, new ScrollView.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (footer != null) {
            authFooter = footer;
            GradientDrawable footerBg = new GradientDrawable();
            footerBg.setColor(p.dark() ? 0xFF0F151D : 0xFFFFFFFF);
            footerBg.setCornerRadius(dp(16));
            footerBg.setStroke(dp(1), BORDER);
            footer.setBackground(footerBg);
            footer.setElevation(dp(6));
            footer.setPadding(dp(shortScreen ? 8 : 12), dp(shortScreen ? 5 : 7),
                    dp(shortScreen ? 8 : 12), dp(shortScreen ? 5 : 7));
            int footerWidth = authFormWidthPx();
            LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                    footerWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerLp.gravity = Gravity.CENTER_HORIZONTAL;
            page.addView(footer, footerLp);
        }
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (footer != null) {
            final View footerToToggle = footer;
            frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    Rect visible = new Rect();
                    frame.getWindowVisibleDisplayFrame(visible);
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    boolean keyboardVisible = screenHeight - visible.bottom > Math.max(dp(90), screenHeight / 4);
                    footerToToggle.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
                } catch (Throwable ignored) {}
            });
        }
        return frame;
    }

    private LinearLayout authRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (uiProfile != null && uiProfile.pnc370) ? 12 : (shortScreen ? 12 : tinyScreen ? 16 : 22);
        root.setPadding(dp(pad), dp(shortScreen ? 8 : 16), dp(pad), dp(shortScreen ? 8 : 18));
        GradientDrawable authCard = new GradientDrawable();
        authCard.setColor(CARD);
        authCard.setCornerRadius(dp(18));
        authCard.setStroke(dp(1), BORDER);
        root.setBackground(authCard);
        root.setElevation(dp(4));
        return root;
    }

    private void chainAuthInputs(Runnable finalAction, EditText... fields) {
        if (fields == null || fields.length == 0) return;
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            EditText field = fields[i];
            field.setImeOptions(i == fields.length - 1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (index < fields.length - 1) {
                    EditText next = fields[index + 1];
                    next.requestFocus();
                    showSoftKeyboard(next);
                } else if (finalAction != null) {
                    finalAction.run();
                } else {
                    try {
                        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
                    } catch (Throwable ignored) {}
                }
                return true;
            });
        }
    }

    private void showLoginScreen() {
        authScreen = true;
        authRegisterStep = 0;
        clearMainViewRefs();
        LinearLayout root = authRoot();

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(shortScreen ? 0 : 6), 0, dp(shortScreen ? 4 : 14));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int authLogo = dp(authLogoDp());
        brand.addView(logo, new LinearLayout.LayoutParams(authLogo, authLogo));
        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(10), 0, 0, 0);
        brandText.addView(text("ARCH 网络对讲", authBrandSp(), TXT, true));
        brand.addView(brandText, new LinearLayout.LayoutParams(-2, -2));
        root.addView(brand);
        EditText callsign = authInput("", p.callsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText password = authInput("", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(authField("呼号", callsign));
        root.addView(authField("密码", password));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button login = authButton("登录", true);
        Button register = authButton("注册新账号", false);
        actions.addView(login, new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1));
        LinearLayout.LayoutParams regLp = new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1);
        regLp.setMargins(dp(6), 0, 0, 0);
        actions.addView(register, regLp);

        TextView status = text("", shortScreen ? 9 : 10, RED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(shortScreen ? 3 : 6), 0, 0);
        status.setVisibility(View.GONE);
        root.addView(status);

        login.setOnClickListener(v -> loginAccount(value(callsign), value(password), status, login));
        register.setOnClickListener(v -> {
            pendingRegCallsign = value(callsign).toUpperCase(Locale.US);
            pendingRegDmrId = "";
            pendingRegEssid = "0";
            showRegisterStep1();
        });
        chainAuthInputs(login::performClick, callsign, password);

        FrameLayout frame = authScaffold(root, actions);
        setContentView(frame);
        prepareAuthWindow();
        applyWindowTheme();
        installKeyNavigation(frame);
        if (keyNavigation) callsign.post(callsign::requestFocus);
        else callsign.clearFocus();
    }

    private void showRegisterStep1() {
        authScreen = true;
        authRegisterStep = 1;
        clearMainViewRefs();
        LinearLayout root = authRoot();

        TextView title = text("注册新账号", shortScreen ? 15 : 18, TXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        root.addView(text("1 / 2", shortScreen ? 9 : 11, ACCENT, true));

        EditText callsign = authInput("", pendingRegCallsign, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText dmrId = authInput("7位", pendingRegDmrId, InputType.TYPE_CLASS_NUMBER);
        EditText essid = authInput("", pendingRegEssid, InputType.TYPE_CLASS_NUMBER);
        root.addView(authField("呼号", callsign));
        root.addView(authField("DMR ID", dmrId));
        root.addView(authField("ESSID", essid));
        chainAuthInputs(null, callsign, dmrId, essid);

        TextView status = text("", shortScreen ? 9 : 10, RED, false);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        Button back = authButton("返回", false);
        Button next = authButton("下一步", true);
        back.setOnClickListener(v -> showLoginScreen());
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1));
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1);
        nextLp.setMargins(dp(6), 0, 0, 0);
        actions.addView(next, nextLp);
        next.setOnClickListener(v -> {
            String cs = value(callsign).toUpperCase(Locale.US);
            int id = parseInt(value(dmrId), 0);
            int es = parseInt(value(essid), 0);
            if (!cs.matches("[A-Z0-9-]{3,16}")) {
                setAuthStatus(status, "呼号格式不正确", RED); return;
            }
            if (id <= 0) {
                setAuthStatus(status, "请输入正确的 DMR ID", RED); return;
            }
            if (es < 0 || es > 99) {
                setAuthStatus(status, "ESSID 必须是 00-99", RED); return;
            }
            pendingRegCallsign = cs;
            pendingRegDmrId = String.valueOf(id);
            pendingRegEssid = String.valueOf(es);
            showRegisterStep2();
        });

        FrameLayout frame = authScaffold(root, actions);
        setContentView(frame);
        prepareAuthWindow();
        applyWindowTheme();
        installKeyNavigation(frame);
        if (keyNavigation) callsign.post(callsign::requestFocus);
        else callsign.clearFocus();
    }

    private void showRegisterStep2() {
        authScreen = true;
        authRegisterStep = 2;
        clearMainViewRefs();
        LinearLayout root = authRoot();

        TextView title = text("设置登录密码", shortScreen ? 15 : 18, TXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        root.addView(text("2 / 2", shortScreen ? 9 : 11, ACCENT, true));
        root.addView(text(pendingRegCallsign + "  ·  DMR ID " + pendingRegDmrId + "  ·  ESSID " + pendingRegEssid,
                shortScreen ? 9 : 11, SUB, false));

        EditText password = authInput("", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText confirm = authInput("", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(authField("密码", password));
        root.addView(authField("确认密码", confirm));
        chainAuthInputs(null, password, confirm);
        TextView status = text("", shortScreen ? 9 : 10, RED, false);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        Button back = authButton("上一步", false);
        Button create = authButton("创建账号", true);
        back.setOnClickListener(v -> showRegisterStep1());
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1));
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(0, dp(authActionHeightDp()), 1);
        createLp.setMargins(dp(6), 0, 0, 0);
        actions.addView(create, createLp);
        create.setOnClickListener(v -> registerAccount(pendingRegCallsign, pendingRegDmrId, pendingRegEssid,
                value(password), value(confirm), status, create));

        FrameLayout frame = authScaffold(root, actions);
        setContentView(frame);
        prepareAuthWindow();
        applyWindowTheme();
        installKeyNavigation(frame);
        if (keyNavigation) password.post(password::requestFocus);
        else password.clearFocus();
    }

    private void restoreAccountSession() {
        final String cs = p.callsign();
        final String pw = p.password();
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", cs);
                req.put("password", pw);
                JSONObject out = postAccount("login", req);
                JSONObject account = out.optJSONObject("account");
                if (account == null) throw new Exception("服务器未返回账号信息");
                String resultCallsign = account.optString("callsign", cs).toUpperCase(Locale.US);
                int resultDmrId = account.optInt("dmrId", 0);
                int resultEssid = account.optInt("essid", 0);
                int resultNxdnId = account.optInt("nxdnId", 0);
                if (resultDmrId <= 0) throw new Exception("账号缺少 DMR ID");
                ui.post(() -> {
                    if (isFinishing()) return;
                    p.saveUnifiedIdentity(resultCallsign, resultDmrId, resultEssid, pw, "", resultNxdnId);
                    p.setAuthLoggedIn(true);
                    applyAccountTalkgroups(account);
                    enterMainUi();
                });
            } catch (Throwable e) {
                ui.post(() -> { if (!isFinishing()) showLoginScreen(); });
            }
        }, "account-session-restore").start();
    }
    private void loginAccount(String callsign, String password, TextView status, Button button) {
        String cs = callsign == null ? "" : callsign.trim().toUpperCase(Locale.US);
        String pw = password == null ? "" : password;
        if (!cs.matches("[A-Z0-9-]{3,16}")) {
            setAuthStatus(status, "请输入正确呼号", RED); return;
        }
        if (pw.length() < 6) {
            setAuthStatus(status, "请输入至少 6 位密码", RED); return;
        }
        button.setEnabled(false);
        setAuthStatus(status, "正在登录…", ACCENT);
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", cs);
                req.put("password", pw);
                JSONObject out = postAccount("login", req);
                JSONObject account = out.optJSONObject("account");
                if (account == null) throw new Exception("服务器未返回账号信息");
                String resultCallsign = account.optString("callsign", cs).toUpperCase(Locale.US);
                int resultDmrId = account.optInt("dmrId", 0);
                int resultEssid = account.optInt("essid", 0);
                int resultNxdnId = account.optInt("nxdnId", 0);
                if (resultDmrId <= 0) throw new Exception("账号缺少 DMR ID，请联系管理员");
                ui.post(() -> {
                    p.saveUnifiedIdentity(resultCallsign, resultDmrId, resultEssid, pw, "", resultNxdnId);
                    p.setAuthLoggedIn(true);
                    applyAccountTalkgroups(account);
                    toast("登录成功");
                    enterMainUi();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    button.setEnabled(true);
                    setAuthStatus(status, "登录失败：" + safeError(e), RED);
                });
            }
        }, "account-login").start();
    }

    private void registerAccount(String callsign, String dmrId, String essid, String password,
                                 String confirm, TextView status, Button button) {
        String cs = callsign == null ? "" : callsign.trim().toUpperCase(Locale.US);
        String pw = password == null ? "" : password;
        int id = parseInt(dmrId, 0);
        int es = parseInt(essid, 0);
        if (pw.length() < 6) {
            setAuthStatus(status, "密码至少 6 位", RED); return;
        }
        if (!pw.equals(confirm)) {
            setAuthStatus(status, "两次输入的密码不一致", RED); return;
        }
        button.setEnabled(false);
        setAuthStatus(status, "正在创建账号…", ACCENT);
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", cs);
                req.put("dmrId", id);
                req.put("essid", es);
                req.put("password", pw);
                JSONObject out = postAccount("register", req);
                JSONObject account = out.optJSONObject("account");
                String resultCallsign = account == null ? cs : account.optString("callsign", cs).toUpperCase(Locale.US);
                int resultDmrId = account == null ? id : account.optInt("dmrId", id);
                int resultEssid = account == null ? es : account.optInt("essid", es);
                int resultNxdnId = account == null ? 0 : account.optInt("nxdnId", 0);
                ui.post(() -> {
                    p.saveUnifiedIdentity(resultCallsign, resultDmrId, resultEssid, pw, "", resultNxdnId);
                    p.setAuthLoggedIn(true);
                    applyAccountTalkgroups(account);
                    toast("注册成功，已登录");
                    enterMainUi();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    button.setEnabled(true);
                    setAuthStatus(status, "注册失败：" + safeError(e), RED);
                });
            }
        }, "account-register").start();
    }

    private String safeError(Exception e) {
        String msg = e == null ? "" : e.getMessage();
        return msg == null || msg.trim().isEmpty() ? "网络或服务器错误" : msg;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出账号")
                .setMessage("退出后需要重新输入呼号和密码登录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> performLogout())
                .show();
    }

    private void performLogout() {
        try { if (pttHeld) pttUp(); } catch (Throwable ignored) {}
        networkWanted = false;
        try { disconnectDmr(true); } catch (Throwable ignored) {}
        try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
        try { flushReceivePlayback(); } catch (Throwable ignored) {}
        try { if (aprs != null) aprs.stop(); } catch (Throwable ignored) {}
        try { DeviceIndicatorBridge.idleNow(this); } catch (Throwable ignored) {}
        ArchAppState.hwTx = false;
        ArchAppState.pendingPttDown = false;
        ArchAppState.pendingPttAt = 0L;
        try { if (radio != null && radio.isPlaying()) radio.pause(); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, NetworkKeepAliveService.class)); } catch (Throwable ignored) {}
        p.set(roomCacheKey(), "");
        p.clearIdentity();
        lockedRoom = "";
        p.set("lockedNetRoom", "");
        rooms.clear();
        members.clear();
        currentRoom = "room5";
        showLoginScreen();
        toast("已退出账号");
    }

    private void applyKeepScreenOn() {
        // The display must follow the Android system timeout. Keep only
        // partial wake locks for networking/PTT, never a display lock.
        try {
            clearKeepScreenOnRecursive(rootFrame);
            View decor = getWindow().getDecorView();
            decor.setKeepScreenOn(false);
            clearKeepScreenOnRecursive(decor);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}
    }

    /** Some WebView/child controls can set their own keep-screen flag. */
    private void clearKeepScreenOnRecursive(View view) {
        if (view == null) return;
        try { view.setKeepScreenOn(false); } catch (Throwable ignored) {}
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearKeepScreenOnRecursive(group.getChildAt(i));
            }
        }
    }

    private View buildRoot() {
        FrameLayout frame = new FrameLayout(this);
        rootFrame = frame;
        if (cyberTheme) {
            GradientDrawable cyber = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF05020A, 0xFF100621, 0xFF05020A});
            frame.setBackground(cyber);
        } else {
            GradientDrawable page = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    p.dark() ? new int[]{0xFF070B12, 0xFF0D1420, 0xFF101827}
                            : new int[]{0xFFF8FAFD, 0xFFEEF4FA, 0xFFF8FAFC});
            frame.setBackground(page);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        boolean pdcFamily = hardwareProfile != null
                && (HyteraDeviceProfile.FAMILY_PDC.equals(hardwareProfile.family)
                    || hardwareProfile.isP50Pro());
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(pdcFamily ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(shortScreen ? 5 : tinyScreen ? 8 : 14), dp(shortScreen ? 2 : tinyScreen ? 5 : 10),
                dp(shortScreen ? 5 : tinyScreen ? 8 : 12), dp(shortScreen ? 2 : tinyScreen ? 4 : 8));
        header.setBackgroundColor(CARD);
        header.setElevation(dp(2));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        int logoSize = dp(shortScreen ? 20 : tinyScreen ? 22 : 28);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setContentDescription("ARCH");
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.setMargins(0, 0, dp(4), 0);
        brandRow.addView(logo, logoLp);
        boolean compactPncTop = uiProfile != null && uiProfile.pnc370;
        TextView title = text("ARCH公网版",
                shortScreen ? (compactPncTop ? 13 : 14) : tinyScreen ? 14 : 18, TXT, true);
        title.setSingleLine(true);
        brandRow.addView(title);
        String headerVersion = BuildConfig.VERSION_NAME.replace("-NETWORK", "");
        TextView version = text(headerVersion, shortScreen ? 12 : tinyScreen ? 9 : 11, ACCENT, false);
        version.setSingleLine(true);
        version.setEllipsize(android.text.TextUtils.TruncateAt.END);
        version.setPadding(dp(3), 0, 0, 0);
        version.setVisibility(View.GONE);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(0, -2, 1f);
        brandRow.addView(version, versionLp);
        titleBox.addView(brandRow);
        header.addView(titleBox, pdcFamily
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2)
                : new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout navHost = header;
        if (pdcFamily) {
            navHost = new LinearLayout(this);
            navHost.setOrientation(LinearLayout.HORIZONTAL);
            navHost.setGravity(Gravity.CENTER_VERTICAL);
        }
        int iconSize = dp(uiProfile != null && uiProfile.pnc370 ? 36
                : (uiProfile != null && uiProfile.p50Pro ? 30 : (shortScreen ? 30 : tinyScreen ? 34 : 42)));
        boolean compactPncNav = uiProfile != null && (uiProfile.pnc370 || uiProfile.p50Pro);
        bmIconButton = topNavButton(0, "DMR");
        bmIconButton.setContentDescription("DMR直连");
        netIconButton = topNavButton(0, "网络");
        netIconButton.setContentDescription("网络对讲");
        dmrIconButton = topNavButton(0, "数字");
        dmrIconButton.setContentDescription("数字中继");
        sipIconButton = topNavIconButton(R.drawable.ic_sip_phone, "SIP电话");
        satIconButton = topNavIconButton(R.drawable.ic_satellite_antenna, "APRS");
        radioIconButton = topNavIconButton(R.drawable.ic_radio, "收音机");
        settingsIconButton = topNavIconButton(R.drawable.ic_settings_gear, "设置");
        bmIconButton.setId(View.generateViewId());
        netIconButton.setId(View.generateViewId());
        dmrIconButton.setId(View.generateViewId());
        sipIconButton.setId(View.generateViewId());
        satIconButton.setId(View.generateViewId());
        radioIconButton.setId(View.generateViewId());
        settingsIconButton.setId(View.generateViewId());
        bmIconButton.setOnClickListener(v -> toggleDmrDirectMode());
        netIconButton.setOnClickListener(v -> {
            if (!p.showNetwork()) return;
            switchPage(true);
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
        });
        dmrIconButton.setOnClickListener(v -> showDigitalPage());
        sipIconButton.setOnClickListener(v -> openSipPhone());
        satIconButton.setOnClickListener(v -> showAprsActionDialog());
        radioIconButton.setOnClickListener(v -> {
            if (p.showNetRadio()) switchPage(false);
        });
        settingsIconButton.setOnClickListener(v -> showSettings());

        wireTopNavFocus();
        int navHeight = dp(uiProfile != null && uiProfile.pnc370 ? 30 : (shortScreen ? 28 : tinyScreen ? 32 : 38));
        int compactWidth = uiProfile != null && uiProfile.pnc370 ? dp(34) : (shortScreen ? dp(44) : iconSize);
        int compactIcon = uiProfile != null && uiProfile.pnc370 ? dp(30) : (shortScreen ? dp(34) : iconSize);
        int navGap = dp(uiProfile != null && uiProfile.pnc370 ? 1 : 3);
        int satIconSize = uiProfile != null && uiProfile.pnc370 ? dp(34) : compactIcon;
        LinearLayout.LayoutParams dmrLp = new LinearLayout.LayoutParams(compactWidth, navHeight);
        dmrLp.setMargins(0, 0, navGap, 0); dmrLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(dmrIconButton, dmrLp);
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(compactWidth, navHeight);
        netLp.setMargins(0, 0, navGap, 0); netLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(netIconButton, netLp);
        LinearLayout.LayoutParams bmLp = new LinearLayout.LayoutParams(compactWidth, navHeight);
        bmLp.setMargins(0, 0, navGap, 0); bmLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(bmIconButton, bmLp);
        LinearLayout.LayoutParams sipLp = new LinearLayout.LayoutParams(compactIcon, navHeight);
        sipLp.setMargins(0, 0, navGap, 0); sipLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(sipIconButton, sipLp);
        LinearLayout.LayoutParams satLp = new LinearLayout.LayoutParams(satIconSize, navHeight);
        satLp.setMargins(0, 0, navGap, 0); satLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(satIconButton, satLp);
        LinearLayout.LayoutParams radioLp = new LinearLayout.LayoutParams(compactIcon, navHeight);
        radioLp.setMargins(0, 0, navGap, 0); radioLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(radioIconButton, radioLp);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(compactIcon, navHeight);
        settingsLp.gravity = Gravity.CENTER_VERTICAL; navHost.addView(settingsIconButton, settingsLp);
        if (pdcFamily) {
            View[] pdcActions = {dmrIconButton, netIconButton, bmIconButton, sipIconButton,
                    satIconButton, radioIconButton, settingsIconButton};
            for (View action : pdcActions) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) action.getLayoutParams();
                lp.width = 0;
                lp.height = navHeight;
                lp.weight = 1f;
                lp.rightMargin = dp(1);
                lp.gravity = Gravity.CENTER_VERTICAL;
                action.setLayoutParams(lp);
            }
            LinearLayout.LayoutParams last =
                    (LinearLayout.LayoutParams) settingsIconButton.getLayoutParams();
            last.rightMargin = 0;
            settingsIconButton.setLayoutParams(last);
            LinearLayout.LayoutParams navHostLp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, navHeight);
            navHostLp.topMargin = dp(2);
            header.addView(navHost, navHostLp);
        }
        refreshTopNavVisibility();
        refreshAprsButton();
        installKeyNavigation(header);
        root.addView(header);

        networkDock = new LinearLayout(this);
        networkDock.setOrientation(LinearLayout.VERTICAL);
        networkDock.setPadding(dp(3), dp(2), dp(3), dp(2));
        networkDock.setVisibility(View.GONE);
        root.addView(networkDock);

        mainScroll = new ScrollView(this);
        ScrollView scroll = mainScroll;
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(shortScreen ? 4 : tinyScreen ? 6 : 10), dp(shortScreen ? 1 : tinyScreen ? 2 : 4),
                dp(shortScreen ? 4 : tinyScreen ? 6 : 10), dp(shortScreen ? 4 : tinyScreen ? 8 : 18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        pttWrap = new LinearLayout(this);
        pttWrap.setPadding(dp(shortScreen ? 5 : tinyScreen ? 8 : 18), dp(shortScreen ? 1 : tinyScreen ? 3 : 8),
                dp(shortScreen ? 5 : tinyScreen ? 8 : 18), dp(shortScreen ? 2 : tinyScreen ? 6 : 16));
        pttButton = button("按住发射", true);
        pttButton.setTextSize((shortScreen ? 15 : tinyScreen ? 17 : 22) * fontFactor);
        pttButton.setTypeface(null, uiProfile != null && uiProfile.pnc370 ? Typeface.NORMAL : Typeface.BOLD);
        GradientDrawable pttBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT, withAlpha(ACCENT, 210)});
        pttBg.setCornerRadius(dp(18));
        pttBg.setStroke(dp(1), withAlpha(Color.WHITE, 70));
        pttButton.setBackground(pttBg);
        pttButton.setElevation(dp(7));
        pttWrap.addView(pttButton, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 42 : tinyScreen ? 58 : 94)));
        root.addView(pttWrap);
        setPttWrapVisibility(true);
        pttButton.setOnTouchListener((v, e) -> {
            if ("toggle".equals(p.pttMode())) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { pttDown(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { pttUp(); return true; }
            return false;
        });
        pttButton.setOnClickListener(v -> {
            if (!"toggle".equals(p.pttMode())) return;
            if (pttToggleActive) pttUp(); else pttDown();
        });
        applyKeepScreenOn();
        activateSelectedPageMode();
        body.post(this::focusInitialControl);
        return frame;
    }

    private boolean hardwarePttOnly() {
        return uiProfile != null && uiProfile.keyNavigation;
    }

    private void refreshTopNavVisibility() {
        if (dmrIconButton != null) dmrIconButton.setVisibility(p.showDigital() ? View.VISIBLE : View.GONE);
        if (netIconButton != null) netIconButton.setVisibility(p.showNetwork() ? View.VISIBLE : View.GONE);
        if (bmIconButton != null) bmIconButton.setVisibility(p.dmrDirectEnabled() ? View.VISIBLE : View.GONE);
        if (sipIconButton != null) sipIconButton.setVisibility(p.sipEnabled() ? View.VISIBLE : View.GONE);
        if (satIconButton != null) satIconButton.setVisibility(p.aprsEnabled() ? View.VISIBLE : View.GONE);
        if (radioIconButton != null) radioIconButton.setVisibility(p.showNetRadio() ? View.VISIBLE : View.GONE);
        wireTopNavFocus();
        refreshTopNavColors();
    }

    private void wireTopNavFocus() {
        if (settingsIconButton == null) return;
        java.util.ArrayList<View> items = new java.util.ArrayList<>();
        for (View v : new View[]{dmrIconButton, netIconButton, bmIconButton, sipIconButton,
                satIconButton, radioIconButton, settingsIconButton}) {
            if (v != null && v.getVisibility() == View.VISIBLE) items.add(v);
        }
        int count = items.size();
        for (int i = 0; i < count; i++) {
            View current = items.get(i);
            View left = items.get((i - 1 + count) % count);
            View right = items.get((i + 1) % count);
            current.setNextFocusLeftId(left.getId());
            current.setNextFocusRightId(right.getId());
        }
    }

    /**
     * API 22 devices (PNC370) apply focus by replacing the background, not a foreground
     * ring. Rebuild the saved normal/focused drawables whenever a card's selected state
     * changes; otherwise focus-loss restores a stale blank background and the selected
     * talkgroup becomes visually indistinguishable.
     */
    private void refreshLegacyFocusBackground(View v) {
        if (v == null || Build.VERSION.SDK_INT >= 23) return;
        final boolean iconControl = v instanceof ImageButton;
        android.graphics.drawable.Drawable base = v.getBackground();
        if (base == null) {
            GradientDrawable blank = new GradientDrawable();
            blank.setColor(Color.TRANSPARENT);
            blank.setCornerRadius(dp(10));
            base = blank;
        }
        android.graphics.drawable.Drawable focusedBase =
                base.getConstantState() == null ? base : base.getConstantState().newDrawable();
        GradientDrawable outerRing = new GradientDrawable();
        outerRing.setColor(Color.TRANSPARENT);
        if (iconControl) outerRing.setShape(GradientDrawable.OVAL); else outerRing.setCornerRadius(dp(12));
        outerRing.setStroke(dp(5), 0xFFFF5A00);
        GradientDrawable innerRing = new GradientDrawable();
        innerRing.setColor(Color.TRANSPARENT);
        if (iconControl) innerRing.setShape(GradientDrawable.OVAL); else innerRing.setCornerRadius(dp(12));
        innerRing.setStroke(dp(2), 0xFF111111);
        GradientDrawable glow = new GradientDrawable();
        glow.setColor(0x30FF9F0A);
        if (iconControl) glow.setShape(GradientDrawable.OVAL); else glow.setCornerRadius(dp(12));
        android.graphics.drawable.Drawable focusedSurface = focusedBase;
        if (uiProfile != null && uiProfile.pnc370) {
            GradientDrawable solid = new GradientDrawable();
            solid.setColor(0xFFFFD54F);
            if (iconControl) solid.setShape(GradientDrawable.OVAL); else solid.setCornerRadius(dp(10));
            focusedSurface = solid;
        }
        android.graphics.drawable.LayerDrawable focused =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{focusedSurface, glow, outerRing, innerRing});
        v.setTag(FOCUS_BG_TAG_KEY, base);
        v.setTag(FOCUS_BG_ON_TAG_KEY, focused);
        v.setBackground(v.isFocused() ? focused : base);
        v.invalidate();
    }
    private GradientDrawable topNavPillBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999));
        g.setColor(focused ? 0xFF111111 : (uiProfile != null && uiProfile.pnc370 ? 0xFFFFFFFF : CARD));
        g.setStroke(dp(focused ? 2 : 1), focused ? 0xFFFF5A00 : BORDER);
        return g;
    }

    private void paintTopNavState(View view, boolean focused) {
        if (view == null || !Boolean.TRUE.equals(view.getTag(TOP_NAV_TAG_KEY))) return;
        view.setBackground(topNavPillBg(focused));
        if (view instanceof ImageButton) {
            boolean sipStatusIcon = view == sipIconButton;
            int normal = sipStatusIcon ? (sipRegistered ? GREEN : RED)
                    : (view == radioIconButton && radioPage ? ACCENT
                    : (view == satIconButton && p != null && p.aprsEnabled() ? GREEN : navIconColor()));
            // SIP 图标本身表示注册状态，因此聚焦时也保持绿色/红色；
            // 聚焦位置仍由黑色胶囊背景和橙色描边表示。
            ((ImageButton) view).setColorFilter(sipStatusIcon ? normal
                    : (focused ? TOP_NAV_FOCUS_TEXT : normal));
        } else if (view instanceof TextView) {
            boolean modeCall = false;
            int normal = TXT;
            if (view == bmIconButton) {
                modeCall = isModeSignalActive(MODE_SIGNAL_DMR);
                normal = bmPage ? ACCENT : TXT;
            } else if (view == dmrIconButton) {
                modeCall = isModeSignalActive(MODE_SIGNAL_DIGITAL);
                normal = digitalPage ? ACCENT : TXT;
            } else if (view == netIconButton) {
                modeCall = isModeSignalActive(MODE_SIGNAL_NETWORK);
                normal = netPage ? ACCENT : TXT;
            }
            ((TextView) view).setTextColor(modeCall ? GREEN
                    : (focused ? TOP_NAV_FOCUS_TEXT : normal));
        }
        view.invalidate();
    }

    private void refreshTopNavColors() {
        for (View v : new View[]{bmIconButton, netIconButton, dmrIconButton, sipIconButton,
                satIconButton, radioIconButton, settingsIconButton}) {
            if (v != null) paintTopNavState(v, v.isFocused());
        }
    }

    private void applySipNavState(boolean registered) {
        sipRegistered = registered;
        if (sipIconButton == null) return;
        sipIconButton.setContentDescription(registered ? "SIP 已连接" : "SIP 未连接");
        paintTopNavState(sipIconButton, sipIconButton.isFocused());
    }

    private int activeSignalMode() {
        if (digitalPage) return MODE_SIGNAL_DIGITAL;
        if (netPage) return MODE_SIGNAL_NETWORK;
        if (bmPage) return MODE_SIGNAL_DMR;
        return -1;
    }

    private boolean shouldPlayMode(int mode) {
        if (pttHeld) return false;
        return activeSignalMode() == mode || p.multiListen();
    }

    private void showTransmitIndicator() {
        deviceIndicatorMode = DeviceIndicatorBridge.TRANSMIT;
        try { DeviceIndicatorBridge.transmitNow(this); } catch (Throwable ignored) {}
    }

    private void refreshDeviceIndicatorForAudio() {
        ui.post(() -> {
            if (pttHeld) return;
            boolean receiving = (dmrRxActive && shouldPlayMode(MODE_SIGNAL_DIGITAL))
                    || (bmRxActive && shouldPlayMode(MODE_SIGNAL_DMR))
                    || (networkReceiving && shouldPlayMode(MODE_SIGNAL_NETWORK));
            int next = receiving ? DeviceIndicatorBridge.RECEIVE : DeviceIndicatorBridge.IDLE;
            if (next == deviceIndicatorMode) return;
            deviceIndicatorMode = next;
            try {
                if (receiving) DeviceIndicatorBridge.setMode(this, next);
                else DeviceIndicatorBridge.idleNow(this);
            } catch (Throwable ignored) {}
        });
    }

    private boolean isModeSignalActive(int mode) {
        return mode >= 0 && modeSignalUntil.get(mode) > System.currentTimeMillis();
    }

    private void markModeSignal(int mode) {
        if (mode < 0) return;
        long now = System.currentTimeMillis();
        boolean wasActive = modeSignalUntil.get(mode) > now;
        modeSignalUntil.set(mode, now + 1400L);
        if (!wasActive) {
            ui.removeCallbacks(modeSignalRefreshRunnable);
            ui.post(this::refreshTopNavColors);
            ui.postDelayed(modeSignalRefreshRunnable, 1450L);
        }
    }

    private void clearModeSignal(int mode) {
        if (mode < 0) return;
        modeSignalUntil.set(mode, 0L);
        ui.post(this::refreshTopNavColors);
    }

    private void refreshAprsButton() {
        if (satIconButton == null || p == null) return;
        boolean enabled = p.aprsEnabled();
        satIconButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        satIconButton.setContentDescription(enabled ? "关闭APRS" : "开启APRS");
        paintTopNavState(satIconButton, satIconButton.isFocused());
    }

    private void showAprsActionDialog() {
        String[] items = {"打开 APRS 地图", "开启 APRS 功能", "关闭 APRS 功能"};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("APRS")
                .setItems(items, (d, which) -> {
                    if (which == 0) openAprsMap();
                    else if (which == 1) setAprsEnabledFromMenu(true);
                    else setAprsEnabledFromMenu(false);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> {
            try { dialog.getListView().requestFocus(); } catch (Throwable ignored) {}
        });
        dialog.show();
    }

    private boolean pendingAprsEnable;

    private void setAprsEnabledFromMenu(boolean enable) {
        if (enable && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingAprsEnable = true;
            requestNeededPermissions();
            toast("请授予定位权限，授权后将自动开启 APRS");
            return;
        }
        pendingAprsEnable = false;
        if (enable == p.aprsEnabled()) {
            toast(enable ? "APRS 已开启" : "APRS 已关闭");
            return;
        }
        p.set("aprsEnabled", enable ? "1" : "0");
        if (enable) {
            requestBackgroundLocationForAprs();
            restartAprs();
            toast("APRS 已开启");
        } else {
            if (aprs != null) { try { aprs.stop(); } catch (Throwable ignored) {} aprs = null; }
            aprsState = "APRS: 已关闭";
            toast("APRS 已关闭");
        }
        refreshAprsButton();
        refreshTopNavVisibility();
        if (!enable && satIconButton != null && satIconButton.isFocused()) {
            View next = radioIconButton != null && radioIconButton.getVisibility() == View.VISIBLE
                    ? radioIconButton : settingsIconButton;
            if (next != null) next.post(next::requestFocus);
        }
    }

    private void setPttWrapVisibility(boolean showForPage) {
        if (pttWrap == null) return;
        pttWrap.setVisibility(showForPage && !hardwarePttOnly() ? View.VISIBLE : View.GONE);
    }

    private int navIconColor() {
        return uiProfile != null && uiProfile.pnc370 ? 0xFF075985 : TXT;
    }

    private ImageButton topNavIconButton(int drawable, String desc) {
        boolean pnc = uiProfile != null && uiProfile.pnc370;
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setColorFilter(navIconColor());
        b.setContentDescription(desc);
        b.setPadding(dp(pnc ? 4 : 6), dp(2), dp(pnc ? 4 : 6), dp(2));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setBackground(roundBg(pnc ? 0xFFFFFFFF : CARD, 999));
        b.setTag(TOP_NAV_TAG_KEY, Boolean.TRUE);
        if ("APRS".equals(desc)) b.setTag(APRS_NAV_TAG_KEY, Boolean.TRUE);
        makeFocusable(b);
        return b;
    }

    private TextView topNavButton(int drawable, String label) {
        boolean vm780 = uiProfile != null && uiProfile.vm780;
        boolean pnc = uiProfile != null && uiProfile.pnc370;
        boolean pncSmall = uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380 || uiProfile.p50Pro);
        int navColor = pnc ? 0xFF111111 : TXT;
        TextView button = text(vm780 ? "" : label,
                pncSmall ? 12 : 10, navColor, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setCompoundDrawablesWithIntrinsicBounds(drawable, 0, 0, 0);
        tintCompoundDrawables(button, navColor);
        button.setCompoundDrawablePadding(dp(vm780 ? 0 : 2));
        button.setPadding(dp(pncSmall ? 2 : 3), 0, dp(pncSmall ? 2 : 3), 0);
        button.setBackground(roundBg(pnc ? 0xFFFFFFFF : CARD, 999));
        button.setContentDescription(label);
        button.setClickable(true);
        button.setTag(TOP_NAV_TAG_KEY, Boolean.TRUE);
        makeFocusable(button);
        return button;
    }

    private void tintCompoundDrawables(TextView button, int color) {
        if (button == null) return;
        android.graphics.drawable.Drawable[] ds = button.getCompoundDrawables();
        for (android.graphics.drawable.Drawable d : ds) {
            if (d == null) continue;
            d.mutate();
            if (Build.VERSION.SDK_INT >= 23) {
                d.setTint(color);
            } else {
                d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private ImageButton iconButton(int drawable, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setColorFilter(TXT);
        b.setContentDescription(desc);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setBackground(circleIconBg(false));
        makeFocusable(b);
        return b;
    }

    private void makeFocusable(View v) {
        if (v == null) return;
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        if (v.getTag(FOCUS_TAG_KEY) != null) return;
        v.setTag(FOCUS_TAG_KEY, Boolean.TRUE);
        final boolean editable = v instanceof EditText;
        final boolean iconControl = v instanceof ImageButton;
        v.setFocusable(true);
        v.setFocusableInTouchMode(editable || keyNavigation);
        if (editable && isPnc370DedicatedInput()) {
            // PNC370 has only a D-pad: moving focus must not open the IME.
            try { ((EditText) v).setShowSoftInputOnFocus(false); } catch (Throwable ignored) {}
        }

        GradientDrawable outerRing = new GradientDrawable();
        outerRing.setColor(Color.TRANSPARENT);
        if (iconControl) outerRing.setShape(GradientDrawable.OVAL); else outerRing.setCornerRadius(dp(12));
        outerRing.setStroke(dp(5), 0xFFFF5A00);
        GradientDrawable innerRing = new GradientDrawable();
        innerRing.setColor(Color.TRANSPARENT);
        if (iconControl) innerRing.setShape(GradientDrawable.OVAL); else innerRing.setCornerRadius(dp(12));
        innerRing.setStroke(dp(2), 0xFF111111);

        if (Build.VERSION.SDK_INT >= 23) {
            StateListDrawable focus = new StateListDrawable();
            android.graphics.drawable.LayerDrawable ringLayer =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{outerRing, innerRing});
            focus.addState(new int[]{android.R.attr.state_focused}, ringLayer);
            focus.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            v.setForeground(focus);
        } else {
            refreshLegacyFocusBackground(v);
        }

        v.setOnFocusChangeListener((view, hasFocus) -> {
            android.util.Log.i("ARCH-FOCUS", (hasFocus ? "gain " : "lose ")
                    + view.getClass().getSimpleName() + " " + view.getContentDescription());
            if (Build.VERSION.SDK_INT < 23) {
                Object bg = view.getTag(hasFocus ? FOCUS_BG_ON_TAG_KEY : FOCUS_BG_TAG_KEY);
                if (bg instanceof android.graphics.drawable.Drawable) {
                    view.setBackground((android.graphics.drawable.Drawable) bg);
                }
            }
            boolean topNav = Boolean.TRUE.equals(view.getTag(TOP_NAV_TAG_KEY));
            if (!topNav && uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380)) setFocusedTextColors(view, hasFocus);
            if (topNav) paintTopNavState(view, hasFocus);
            if (hasFocus) {
                Object action = view.getTag(FOCUS_ACTION_TAG_KEY);
                if (action instanceof Runnable) view.post((Runnable) action);
            }
            if (view instanceof ImageButton) {
                if (!topNav) {
                    if (hasFocus) ((ImageButton) view).setColorFilter(Color.BLACK);
                    else ((ImageButton) view).setColorFilter(navIconColor());
                }
                view.animate().scaleX(hasFocus ? 1.12f : 1f)
                        .scaleY(hasFocus ? 1.12f : 1f)
                        .translationZ(hasFocus ? dp(7) : 0f).setDuration(90L).start();
            } else {
                view.animate().scaleX(1f).scaleY(1f)
                        .translationZ(hasFocus ? dp(4) : 0f).setDuration(90L).start();
            }
            view.invalidate();
            if (!hasFocus && editable && isPnc370DedicatedInput()) {
                try { ((EditText) view).setShowSoftInputOnFocus(false); } catch (Throwable ignored) {}
                view.setTag(PNC370_FOCUS_AT_TAG_KEY, 0L);
            }
            if (hasFocus) {
                if (editable && isPnc370DedicatedInput()) view.setTag(PNC370_FOCUS_AT_TAG_KEY, System.currentTimeMillis());
                updateSettingsFocusHint(view);
                view.post(() -> ensureFocusVisible(view));
                if (editable && isPnc370DedicatedInput()) {
                    view.postDelayed(() -> {
                        if (System.currentTimeMillis() - pncLastKeyboardRequestAt < 450L) return;
                        hideSoftKeyboard(view);
                    }, 80L);
                    view.postDelayed(() -> {
                        if (System.currentTimeMillis() - pncLastKeyboardRequestAt < 450L) return;
                        hideSoftKeyboard(view);
                    }, 260L);
                }
            }
        });

        if (editable) {
            EditText edit = (EditText) v;
            edit.setCursorVisible(true);
            edit.setOnClickListener(view -> {
                if (isPnc370DedicatedInput()) {
                    Object stamp = view.getTag(PNC370_FOCUS_AT_TAG_KEY);
                    long focusedAt = stamp instanceof Long ? (Long) stamp : 0L;
                    if (focusedAt > 0L && System.currentTimeMillis() - focusedAt < 180L) return;
                }
                showSoftKeyboard(edit);
            });
            if (!isPnc370DedicatedInput()) {
                edit.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && isConfirmActivationKey(keyCode)) {
                        showSoftKeyboard(edit);
                        return true;
                    }
                    return false;
                });
            }
        } else {
            if (!isPnc370DedicatedInput()) {
                v.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && isConfirmActivationKey(keyCode) && view.isClickable()) {
                        view.performClick();
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private boolean isPnc370DedicatedInput() {
        return hardwareProfile != null && HyteraDeviceProfile.FAMILY_PNC370.equals(hardwareProfile.family);
    }

    private boolean isNumericEdit(EditText edit) {
        if (edit == null) return false;
        int cls = edit.getInputType() & InputType.TYPE_MASK_CLASS;
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE;
    }

    private boolean handlePnc370NumericEditKey(KeyEvent event, EditText edit) {
        if (!pnc370NumericEditing || edit == null || !isNumericEdit(edit)) return false;
        int code = event.getKeyCode();
        int digit = numericKeyValue(code);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return digit >= 0 || code == KeyEvent.KEYCODE_DEL
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (digit >= 0) {
            if (event.getRepeatCount() != 0) return true;
            replaceEditSelection(edit, String.valueOf(digit));
            return true;
        }
        int start = Math.max(0, edit.getSelectionStart());
        int end = Math.max(0, edit.getSelectionEnd());
        if (code == KeyEvent.KEYCODE_DEL) {
            android.text.Editable value = edit.getText();
            if (start != end) value.delete(Math.min(start, end), Math.max(start, end));
            else if (start > 0) value.delete(start - 1, start);
            edit.setSelection(Math.min(edit.length(), start != end ? Math.min(start, end) : Math.max(0, start - 1)));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            int pos = start != end ? Math.min(start, end) : Math.max(0, start - 1);
            edit.setSelection(pos);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int pos = start != end ? Math.max(start, end) : Math.min(edit.length(), start + 1);
            edit.setSelection(pos);
            return true;
        }
        return false;
    }

    private int numericKeyValue(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
            return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9)
            return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private void replaceEditSelection(EditText edit, String text) {
        int start = Math.max(0, edit.getSelectionStart());
        int end = Math.max(0, edit.getSelectionEnd());
        int lo = Math.min(start, end), hi = Math.max(start, end);
        edit.getText().replace(lo, hi, text);
        edit.setSelection(Math.min(edit.length(), lo + text.length()));
    }
    private void hideSoftKeyboard(View view) {
        if (view == null) return;
        pnc370NumericEditing = false;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void showSoftKeyboard(EditText edit) {
        if (edit == null) return;
        pncLastKeyboardRequestAt = System.currentTimeMillis();
        pnc370NumericEditing = isPnc370DedicatedInput() && isNumericEdit(edit);
        if (isPnc370DedicatedInput()) {
            try { edit.setShowSoftInputOnFocus(true); } catch (Throwable ignored) {}
        }
        edit.requestFocus();
        edit.setSelection(edit.getText().length());
        edit.postDelayed(() -> {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(edit,
                        isPnc370DedicatedInput() ? InputMethodManager.SHOW_FORCED : InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) {}
        }, 50L);
    }

    private void scaleTextSizes(View root, float scale) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            float old = textView.getTextSize();
            if (old > 0f) textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    Math.min(old * scale, old + dp(3)));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) scaleTextSizes(group.getChildAt(i), scale);
        }
    }

    private void installKeyNavigation(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        if (root.isClickable()) makeFocusable(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) installKeyNavigation(group.getChildAt(i));
        }
    }

    private void ensureFocusVisible(View view) {
        if (view == null) return;
        try {
            Rect rect = new Rect(0, 0, Math.max(1, view.getWidth()), Math.max(1, view.getHeight()));
            view.requestRectangleOnScreen(rect, true);
        } catch (Throwable ignored) {}
    }

    private void focusInitialControl() {
        if (!keyNavigation) return;
        if (bmPage) {
            View target = null;
            int desiredBmTg = lockedBmTg > 0 ? lockedBmTg : p.bmTg();
            for (int i = 0; i < bmTgButtons.size() && i < bmTgValues.size(); i++) {
                if (bmTgValues.get(i) == desiredBmTg) { target = bmTgButtons.get(i); break; }
            }
            if (target == null && !bmTgButtons.isEmpty()) target = bmTgButtons.get(0);
            if (target == null) target = bmIconButton != null ? bmIconButton : settingsIconButton;
            if (target != null) target.requestFocus();
        } else if (digitalPage) {
            View target = null;
            int desiredDigitalTg = lockedDigitalTg > 0 ? lockedDigitalTg : p.tg();
            for (int i = 0; i < dmrTgButtons.size() && i < dmrTgValues.size(); i++) {
                if (dmrTgValues.get(i) == desiredDigitalTg) { target = dmrTgButtons.get(i); break; }
            }
            if (target == null && !dmrTgButtons.isEmpty()) target = dmrTgButtons.get(0);
            if (target == null) target = dmrIconButton;
            if (target instanceof View && !target.isFocusable()) target = dmrIconButton;
            if (target != null) target.requestFocus();
            else if (settingsIconButton != null) settingsIconButton.requestFocus();
        } else if (netPage) {
            View target;
            if (!lockedRoom.isEmpty() && activeRoomView != null) {
                target = activeRoomView;
            } else if (uiProfile != null && uiProfile.pnc370 && !roomFocusViews.isEmpty()) {
                target = roomFocusViews.get(0);
            } else {
                target = activeRoomView != null ? activeRoomView
                        : (roomFocusViews.isEmpty() ? null : roomFocusViews.get(0));
            }
            if (target != null) target.requestFocus();
            else if (settingsIconButton != null) settingsIconButton.requestFocus();
        } else if (radioPage && radioStatus != null) radioStatus.requestFocus();
        else if (settingsIconButton != null) settingsIconButton.requestFocus();
    }

    private void moveRoomFocusBy(View from, int delta) {
        if (from == null || roomFocusViews.isEmpty()) return;
        if (!lockedRoom.isEmpty()) {
            View lockedTarget = activeRoomView != null ? activeRoomView : from;
            lockedTarget.requestFocus();
            ensureFocusVisible(lockedTarget);
            return;
        }
        int idx = roomFocusViews.indexOf(from);
        if (idx < 0) idx = 0;
        idx = Math.max(0, Math.min(roomFocusViews.size() - 1, idx + delta));
        View target = roomFocusViews.get(idx);
        target.requestFocus();
        ensureFocusVisible(target);
    }

    private GradientDrawable circleIconBg(boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(active ? (cyberTheme ? 0xFF220A33 : CARD) : CARD);
        g.setStroke(dp(1), active ? ACCENT : BORDER);
        return g;
    }


    private int dmrSlotForTg(int tg, int fallback) {
        for (int i = 0; i < DMR_DEFAULT_TGS.length; i++)
            if (DMR_DEFAULT_TGS[i] == tg) return DMR_DEFAULT_TG_SLOTS[i];
        return fallback == 2 ? 2 : 1;
    }

    private void normalizeDmrTalkgroupSlot() {
        int tg = p.tg();
        int canonical = dmrSlotForTg(tg, p.tgSlot());
        if (canonical != p.tgSlot()) p.set("tgSlot", String.valueOf(canonical));
    }

    private int[] visibleDmrTgs() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (dmrAllow10) out.add(10);
        if (dmrAllow175) out.add(460175);
        for (int id : DMR_BASE_TGS) out.add(id);
        int[] result = new int[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = out.get(i);
        return result;
    }

    private void ensureVisibleDmrTg() {
        int[] visible = visibleDmrTgs();
        boolean found = false;
        for (int id : visible) if (id == p.tg()) { found = true; break; }
        if (!found) p.rememberDmrTg(visible[0], dmrSlotForTg(visible[0], 1));
    }

    private void refreshAccountTalkgroupsInBackground() {
        if (p == null || !p.authLoggedIn() || !identityReady()) return;
        final String cs = p.callsign();
        final String pw = p.password();
        if (cs.isEmpty() || pw.length() < 6
                || !accountGroupRefreshRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", cs);
                req.put("password", pw);
                JSONObject out = postAccount("login", req);
                JSONObject account = out.optJSONObject("account");
                if (account == null) throw new Exception("服务器未返回账号信息");
                ui.post(() -> {
                    try {
                        if (!isFinishing() && cs.equalsIgnoreCase(p.callsign()))
                            applyAccountTalkgroups(account);
                    } finally {
                        accountGroupRefreshRunning.set(false);
                    }
                });
            } catch (Throwable t) {
                accountGroupRefreshRunning.set(false);
                android.util.Log.w("ARCH-ACCOUNT", "talkgroup refresh failed", t);
            }
        }, "account-group-refresh").start();
    }

    private void applyAccountTalkgroups(JSONObject account) {
        boolean allow10 = false, allow175 = false;
        JSONArray arr = account == null ? null : account.optJSONArray("talkgroups");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            try {
                int id = arr.optJSONObject(i).optInt("id", 0);
                if (id == 10) allow10 = true;
                else if (id == 460175) allow175 = true;
            } catch (Throwable ignored) {}
        }
        dmrAllow10 = allow10;
        dmrAllow175 = allow175;
        p.setDmrAllowedGroups(allow10, allow175);
        int[] visible = visibleDmrTgs();
        boolean found = false;
        for (int id : visible) if (id == p.tg()) { found = true; break; }
        if (!found) p.rememberDmrTg(visible[0], dmrSlotForTg(visible[0], 1));
        normalizeDmrTalkgroupSlot();
        refreshDmrGroupButtons();
    }

    private String dmrTgName(int tg) {
        for (int i = 0; i < DMR_DEFAULT_TGS.length; i++)
            if (DMR_DEFAULT_TGS[i] == tg) return DMR_DEFAULT_TG_NAMES[i];
        return "TG" + tg;
    }

    private String dmrTgLabel() { return dmrTgName(p.tg()) + " · 时隙 " + p.tgSlot(); }

    private String dmrContact(int id) {
        String name = dmrContacts == null ? null : dmrContacts.get(id);
        return name == null || name.trim().isEmpty() ? String.valueOf(id) : name.trim();
    }

    private void activateSelectedPageMode() {
        if (digitalPage && p.showDigital()) {
            dmrManualDisconnect = false;
            showDigitalPage();
            return;
        }
        if (netPage && p.showNetwork()) {
            switchPage(true);
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
            return;
        }
        if (bmPage && p.dmrDirectEnabled()) {
            showBmDirectPage();
            return;
        }
        if (radioPage && p.showNetRadio()) {
            switchPage(false);
            return;
        }

        // Restore the last page the user actually used. The selected room or
        // talkgroup for each page remains stored independently.
        String lastMode = p.lastPageMode();
        android.util.Log.i("ARCH-STATE", "restore lastMode=" + lastMode
                + " digitalTg=" + p.tg() + " netRoom=" + p.netRoom() + " bmTg=" + p.bmTg());
        if ("network".equals(lastMode) && p.showNetwork()) {
            switchPage(true);
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
            return;
        }
        if ("dmr".equals(lastMode) && p.dmrDirectEnabled()) {
            showBmDirectPage();
            return;
        }
        if ("digital".equals(lastMode) && p.showDigital()) {
            dmrManualDisconnect = false;
            showDigitalPage();
            return;
        }
        if ("radio".equals(lastMode) && p.showNetRadio()) {
            switchPage(false);
            return;
        }

        if (p.showDigital()) {
            dmrManualDisconnect = false;
            showDigitalPage();
        } else if (p.showNetwork()) {
            switchPage(true);
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
        } else if (p.dmrDirectEnabled()) {
            showBmDirectPage();
        } else if (p.showNetRadio()) {
            switchPage(false);
        } else {
            showNoModePage();
        }
    }

    private void showNoModePage() {
        networkWanted = false;
        try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
        if (dmrHbp != null) try { disconnectDmr(true); } catch (Throwable ignored) {}
        if (bmHbp != null) try { disconnectBm(); } catch (Throwable ignored) {}
        if (radio != null && radio.isPlaying()) radio.pause();
        releaseWarmNetworkMic("no-mode");
        try { flushReceivePlayback(); } catch (Throwable ignored) {}
        try { runAudioIo(() -> { if (audio != null) audio.stop(); }); } catch (Throwable ignored) {}
        digitalPage = false;
        netPage = false;
        bmPage = false;
        radioPage = false;
        ArchAppState.setActiveVolumeContext("idle", AudioManager.STREAM_MUSIC);
        if (networkDock != null) networkDock.setVisibility(View.GONE);
        RadioSpectrum.setUiVisible(false);
        setPttWrapVisibility(false);
        refreshTopNavColors();
        if (body == null) return;
        body.removeAllViews();
        body.setGravity(Gravity.CENTER);
        TextView title = text("通信模式未开启", uiProfile != null && uiProfile.pnc370 ? 23 : 21, TXT, true);
        title.setGravity(Gravity.CENTER);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("请进入设置，在“模式启用”中打开需要的模式。", 13, SUB, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(dp(10), dp(8), dp(10), dp(10));
        body.addView(hint, hintLp);
        installKeyNavigation(body);
        body.post(this::focusInitialControl);
    }

    private void showDigitalPage() {
        if (!p.showDigital()) return;
        if (!digitalPage && pttHeld) {
            try { pttUp(); } catch (Throwable ignored) {}
        }
        if (radio != null && radio.isPlaying()) radio.pause();
        releaseWarmNetworkMic("digital");
        digitalPage = true;
        netPage = false;
        bmPage = false;
        radioPage = false;
        syncVolumeControlStream();
        lastHbpMode = MODE_SIGNAL_DIGITAL;
        p.rememberDmrTg(p.tg(), dmrSlotForTg(p.tg(), p.tgSlot()));
        p.setLastPageMode("digital");
        // Digital mode uses ARCH/FreeDMR; DMR-direct uses BM. They are
        // independent servers and may remain connected together.
        refreshTopNavColors();
        if (networkDock != null) networkDock.setVisibility(View.GONE);
        RadioSpectrum.setUiVisible(false);
        setPttWrapVisibility(true);
        body.removeAllViews();
        body.setPadding(dp(shortScreen ? 4 : tinyScreen ? 6 : 10),
                dp(shortScreen ? 1 : tinyScreen ? 2 : 4),
                dp(shortScreen ? 4 : tinyScreen ? 6 : 10),
                dp(shortScreen ? 4 : tinyScreen ? 8 : 18));
        dmrConnected = dmrHbp != null && dmrHbp.isConnected();
        buildDigitalBody();
        installKeyNavigation(body);
        body.post(this::focusInitialControl);
        updateDmrUi(dmrConnected ? "数字中继已连接" : "正在连接…", false);
        refreshHyteraDisplay();
        syncModeConnections();
        if (!p.multiListen()) flushNonCurrentModeAudio();
    }

    private void buildDigitalBody() {
        LinearLayout status = card();
        int tgSize = uiProfile != null && uiProfile.pnc370 ? 19 : uiProfile != null && uiProfile.pnc380 ? 17 : (shortScreen ? 14 : 16);
        dmrTgText = text(dmrTgLabel(), tgSize, TXT, true);
        dmrCallsignText = text("", uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380) ? 24 : (shortScreen ? 20 : 24), ACCENT, true);
        dmrCallsignText.setSingleLine(true);
        dmrCallsignText.setGravity(Gravity.CENTER);
        dmrCallsignText.setPadding(dp(8), dp(2), dp(8), dp(2));
        dmrCallsignText.setBackground(roundBg(withAlpha(ACCENT, p.dark() ? 45 : 22), 999));
        dmrCallsignText.setVisibility(View.GONE);
        dmrCallText = null;
        dmrLastText = text("上次呼叫: --", shortScreen ? 12 : 14, SUB, false);
        dmrLastText.setVisibility(View.GONE);
        dmrTimerText = null;
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(dp(shortScreen ? 42 : 54));
        top.addView(dmrTgText, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams callsignLp = new LinearLayout.LayoutParams(-2, -2);
        callsignLp.setMargins(dp(5), 0, dp(2), 0);
        top.addView(dmrCallsignText, callsignLp);
        status.addView(top);
        status.addView(dmrLastText);
        body.addView(status);

        LinearLayout groups = card();
        LinearLayout groupHeader = new LinearLayout(this);
        groupHeader.setGravity(Gravity.CENTER_VERTICAL);
        groupHeader.addView(text("数字中继通话组", 12, SUB, false), new LinearLayout.LayoutParams(0, -2, 1f));
        dmrGroupStatusText = text("已断开", 11, RED, true);
        dmrGroupStatusText.setGravity(Gravity.END);
        groupHeader.addView(dmrGroupStatusText, new LinearLayout.LayoutParams(-2, -2));
        groups.addView(groupHeader);
        dmrTgRow = new LinearLayout(this);
        dmrTgRow.setOrientation(LinearLayout.VERTICAL);
        groups.addView(dmrTgRow);
        body.addView(groups);

        refreshDmrGroupButtons();
        updateDmrUi("", false);
        ui.postDelayed(this::autoConnectDmrIfNeeded, 120L);
    }

    private GradientDrawable dmrPanelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(2), p.dark() ? 0xFF40566F : 0xFF9FB2C5);
        return g;
    }

    private GradientDrawable dmrTgButtonBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(shortScreen ? 10 : 12));
        if (on) {
            g.setColor(p.dark() ? 0xFF0C4A6E : 0xFFE0F2FE);
            g.setStroke(dp(2), p.dark() ? 0xFF7DD3FC : 0xFF0284C7);
        } else {
            g.setColor(CARD);
            g.setStroke(dp(1), BORDER);
        }
        return g;
    }

    private void refreshDmrGroupButtons() {
        if (dmrTgRow == null) return;
        dmrTgRow.removeAllViews();
        dmrTgButtons.clear();
        dmrTgValues.clear();
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        for (int id : visibleDmrTgs()) ids.add(id);
        int columns = 2;
        LinearLayout row = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rlp.setMargins(0, dp(4), 0, 0);
                dmrTgRow.addView(row, rlp);
            }
            final int tg = ids.get(i);
            Button b = button(dmrTgName(tg), false);
            b.setMinHeight(dp(shortScreen ? 32 : 44));
            float tgSp = uiProfile != null && uiProfile.pnc370
                    ? 16f : (shortScreen ? 13f : 15f);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(12f, tgSp * fontFactor));
            b.setSingleLine(false);
            b.setMaxLines(2);
            b.setEllipsize(null);
            b.setOnClickListener(v -> selectDmrTg(tg));
            b.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                toggleDigitalTgLock(tg);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, lp);
            dmrTgButtons.add(b);
            dmrTgValues.add(tg);
        }
        paintDmrGroupButtons();
    }

    private void paintDmrGroupButtons() {
        for (int i = 0; i < dmrTgButtons.size(); i++) {
            Button b = dmrTgButtons.get(i);
            int tg = i < dmrTgValues.size() ? dmrTgValues.get(i) : 0;
            boolean selected = tg == p.tg();
            int groupTextColor = p.dark() ? 0xFF7DD3FC : 0xFF075985;
            b.setText(dmrTgName(tg));
            b.setTextColor(groupTextColor);
            b.setBackground(dmrTgButtonBg(selected));
            b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            b.setTag(DMR_GROUP_TAG_KEY, Boolean.TRUE);
            b.setTag(FOCUS_TEXT_COLOR_TAG_KEY, null);
            android.graphics.drawable.Drawable lock = null;
            if (lockedDigitalTg == dmrTgValues.get(i)) {
                lock = getResources().getDrawable(R.drawable.ic_lock_small);
                lock.setColorFilter(ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            b.setCompoundDrawablesWithIntrinsicBounds(null, null, lock, null);
            b.setCompoundDrawablePadding(dp(4));
            refreshLegacyFocusBackground(b);
            b.invalidate();
        }
        if (dmrGroupStatusText != null) {
            dmrGroupStatusText.setText(dmrConnected ? "已连接" : "已断开");
            dmrGroupStatusText.setTextColor(dmrConnected ? GREEN : RED);
        }
    }

    private void selectDmrTg(int tg) {
        if (lockedDigitalTg > 0 && tg != lockedDigitalTg) {
            toast("数字中继通话组已锁定，长按确认键解锁");
            focusDigitalTgButton(lockedDigitalTg);
            return;
        }
        int slot = dmrSlotForTg(tg, p.tgSlot());
        p.rememberDmrTg(tg, slot);
        if (dmrTgText != null) dmrTgText.setText("当前组: " + dmrTgLabel());
        paintDmrGroupButtons();
        dmrManualDisconnect = false;
        if (dmrHbp != null && dmrHbp.isRunning()) {
            // 一些 HBP 主站对已登录会话中的动态 RPTO 不稳定。
            // 选组后重建会话，让主站从登录阶段的 TSx=TG; 选项读取新组。
            disconnectDmr(false);
            final int expectedGeneration = dmrConnectionGeneration;
            ui.postDelayed(() -> {
                if (expectedGeneration == dmrConnectionGeneration && !dmrManualDisconnect)
                    connectDmrIfNeeded();
            }, 180L);
        } else {
            final int expectedGeneration = dmrConnectionGeneration;
            ui.postDelayed(() -> {
                if (expectedGeneration == dmrConnectionGeneration && !dmrManualDisconnect)
                    connectDmrIfNeeded();
            }, 60L);
        }
        toast("已切换数字中继通话组：" + dmrTgName(tg));
        refreshHyteraDisplay();
    }

    private void focusDigitalTgButton(int tg) {
        for (int i = 0; i < dmrTgButtons.size() && i < dmrTgValues.size(); i++) {
            if (dmrTgValues.get(i) == tg) {
                View target = dmrTgButtons.get(i);
                target.requestFocus();
                ensureFocusVisible(target);
                return;
            }
        }
    }

    private void toggleDigitalTgLock(int tg) {
        if (tg <= 0) return;
        if (lockedDigitalTg == tg) {
            lockedDigitalTg = 0;
            p.rememberLockedDigitalTg(0);
            toast("数字中继通话组已解锁");
        } else {
            if (p.tg() != tg) selectDmrTg(tg);
            lockedDigitalTg = tg;
            p.rememberLockedDigitalTg(tg);
            toast("数字中继通话组已锁定：" + dmrTgName(tg));
        }
        refreshDmrGroupButtons();
        focusDigitalTgButton(tg);
    }

    private void toggleBmTgLock(int tg) {
        if (tg <= 0) return;
        if (lockedBmTg == tg) {
            lockedBmTg = 0;
            p.rememberLockedBmTg(0);
            toast("DMR 直连通话组已解锁");
        } else {
            if (p.bmTg() != tg) selectBmTg(tg);
            lockedBmTg = tg;
            p.rememberLockedBmTg(tg);
            toast("DMR 直连通话组已锁定：" + bmTgName(tg));
        }
        refreshBmGroupButtons();
        focusBmTalkgroup(tg);
    }

    private boolean ensureDmrAudio() {
        try {
            if (dmrAudio == null) {
                dmrAudio = new AudioEngine();
                dmrAudio.setRxGainDb(p.rxGainDb());
                dmrAudio.setTxGainDb(p.txGainDb());
                dmrAudio.setAgc(p.agc());
                dmrAudio.setDenoise(false);
                dmrAudio.setStreamType(preferredAudioStreamType());
                configureDmrMicDevice(dmrAudio);
                if (!dmrAudio.start()) {
                    dmrAudio = null;
                    return false;
                }
            }
            return dmrAudio.isReady();
        } catch (Throwable t) {
            dmrAudio = null;
            return false;
        }
    }

    private boolean ensureBmAudio() {
        try {
            if (bmAudio == null) {
                bmAudio = new AudioEngine();
                bmAudio.setRxGainDb(p.rxGainDb());
                bmAudio.setTxGainDb(p.txGainDb());
                bmAudio.setAgc(p.agc());
                bmAudio.setDenoise(false);
                bmAudio.setStreamType(preferredAudioStreamType());
                configureDmrMicDevice(bmAudio);
                if (!bmAudio.start()) {
                    bmAudio = null;
                    return false;
                }
            }
            return bmAudio.isReady();
        } catch (Throwable t) {
            bmAudio = null;
            return false;
        }
    }

    private void autoConnectDmrIfNeeded() {
        // 网络对讲版必须保持数字中继会话，不提供手动完全断开。
        connectDmrIfNeeded();
    }

    private String dmrOptions() {
        int slot = dmrSlotForTg(p.tg(), p.tgSlot());
        return (slot == 2 ? "TS2=" : "TS1=") + p.tg() + ";";
    }

    private void scheduleDmrAutoAttach(long delay) {
        final int generation = ++dmrAttachGeneration;
        ui.postDelayed(() -> {
            if (generation == dmrAttachGeneration) autoAttachDmrTalkgroup();
        }, delay);
    }

    private void autoAttachDmrTalkgroup() {
        final HbpClient client = dmrHbp;
        if (client == null || !client.isConnected() || dmrAutoAttachRunning || pttHeld || dmrTxing) return;
        dmrAutoAttachRunning = true;
        new Thread(() -> {
            try {
                byte[] ambe = new byte[27];
                try {
                    short[] silence = new short[160];
                    byte[] a9 = new byte[9];
                    synchronized (dmrVoc) {
                        if (dmrVoc != null && dmrVoc.encode(silence, a9)) {
                            System.arraycopy(a9, 0, ambe, 0, 9);
                            System.arraycopy(a9, 0, ambe, 9, 9);
                            System.arraycopy(a9, 0, ambe, 18, 9);
                        }
                    }
                } catch (Throwable ignored) {}
                if (!client.isConnected()) return;
                client.txStart();
                for (int i = 0; i < 3 && client.isConnected(); i++) {
                    client.txVoice(ambe);
                    try { Thread.sleep(60L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                if (client.isConnected()) client.txEnd();
                dmrLog("DMR 自动附着已发送 · " + dmrOptions());
            } catch (Throwable t) {
                dmrLog("DMR 自动附着异常: " + t);
            } finally {
                dmrAutoAttachRunning = false;
            }
        }, "dmr-auto-attach").start();
    }

    private void connectDmrIfNeeded() {
        normalizeDmrTalkgroupSlot();
        if (!p.showDigital() || dmrManualDisconnect) return;
        dmrManualDisconnect = false;
        if (!identityReady()) {
            updateDmrUi("请先在设置中登录账号", false);
            return;
        }
        if (dmrHbp != null && (dmrHbp.isConnected() || dmrHbp.isConnecting())) return;
        if (dmrHbp != null) { try { dmrHbp.stop(); } catch (Throwable ignored) {} dmrHbp = null; }
        if (!NetworkRecovery.get(this).isOnline()) { updateDmrUi("网络未连接，等待恢复", false); return; }
        if (dmrVoc == null) dmrVoc = new Vocoder();
        if (!dmrVoc.ok()) {
            toast("数字中继声码器加载失败");
            updateDmrUi("声码器加载失败", false);
            return;
        }
        if (!ensureDmrAudio()) {
            toast("数字中继音频初始化失败");
            updateDmrUi("音频初始化失败", false);
            return;
        }
        int peerId = p.dmrId() * 100 + (p.essid() > 0 ? p.essid() - 1 : 0);
        final int generation = ++dmrConnectionGeneration;
        dmrHbp = new HbpClient(p.dmrHost(), p.dmrPort(), peerId, p.dmrId(), p.tg(), 0,
                p.tgSlot(), p.cc(), p.callsign(), p.dmrPassword(), dmrOptions(), new DmrSourceListener(generation));
        dmrHbp.start();
        dmrConnected = false;
        updateDmrUi("正在登录数字中继…", false);
    }

    private void disconnectDmr(boolean manual) {
        dmrConnectionGeneration++;
        if (manual) dmrManualDisconnect = true;
        if (dmrReconnectTask != null) {
            ui.removeCallbacks(dmrReconnectTask);
            dmrReconnectTask = null;
        }
        if (activePttMode == MODE_SIGNAL_DIGITAL && (pttHeld || dmrTxing)) {
            try { dmrPttUp(); } catch (Throwable ignored) {}
        }
        if (dmrHbp != null) {
            try { dmrHbp.stop(); } catch (Throwable ignored) {}
            dmrHbp = null;
        }
        stopDmrPlaybackQueue();
        if (dmrAudio != null) {
            try { dmrAudio.stop(); } catch (Throwable ignored) {}
            dmrAudio = null;
        }
        if (dmrVoc != null) {
            try { synchronized (dmrVoc) { dmrVoc.close(); } } catch (Throwable ignored) {}
            dmrVoc = null;
        }
        dmrConnected = false;
        dmrRxActive = false;
        clearModeSignal(MODE_SIGNAL_DIGITAL);
        refreshDeviceIndicatorForAudio();
        updateDmrUi(manual ? "已断开数字中继" : "未连接", false);
    }

    private void scheduleDmrReconnect() {
        if (!p.dmrAuto() || dmrManualDisconnect || dmrReconnectTask != null) return;
        if (!NetworkRecovery.get(this).isOnline()) return;
        dmrReconnectTask = () -> {
            dmrReconnectTask = null;
            if (p.showDigital() && !dmrManualDisconnect) connectDmrIfNeeded();
        };
        ui.postDelayed(dmrReconnectTask, 4000L);
    }

    private static final long DMR_FRAME_NS = 60_000_000L;
    private static final long DMR_PRIME_NS = 120_000_000L;

    private void resetDmrPlaybackQueue() {
        int generation;
        Thread old;
        synchronized (dmrPlayLock) {
            generation = ++dmrPlayGeneration;
            dmrPlayQueue.clear();
            dmrLastVoiceAtNs = 0L;
            old = dmrPlayThread;
            dmrPlayThread = null;
            dmrPlayLock.notifyAll();
        }
        if (old != null && old != Thread.currentThread()) {
            old.interrupt();
            try { old.join(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void stopDmrPlaybackQueue() {
        Thread old;
        synchronized (dmrPlayLock) {
            dmrPlayRun = false;
            dmrPlayGeneration++;
            dmrPlayQueue.clear();
            old = dmrPlayThread;
            dmrPlayThread = null;
            dmrPlayLock.notifyAll();
        }
        if (old != null && old != Thread.currentThread()) {
            old.interrupt();
            try { old.join(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void enqueueDmrPlayback(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (dmrPlayLock) {
            if (dmrPlayQueue.size() >= 3) dmrPlayQueue.removeFirst();
            dmrPlayQueue.addLast(pcm);
            dmrLastVoiceAtNs = System.nanoTime();
            if (dmrPlayThread == null || !dmrPlayThread.isAlive()) {
                dmrPlayRun = true;
                final int generation = dmrPlayGeneration;
                dmrPlayThread = new Thread(() -> dmrPlaybackLoop(generation), "dmr-play");
                dmrPlayThread.start();
            } else {
                dmrPlayLock.notifyAll();
            }
        }
    }

    private void dmrPlaybackLoop(int generation) {
        boolean primed = false;
        long primeStarted = 0L;
        long next = 0L;
        int underruns = 0;
        while (dmrPlayRun && generation == dmrPlayGeneration) {
            try {
                long now = System.nanoTime();
                int queued;
                synchronized (dmrPlayLock) { queued = dmrPlayQueue.size(); }
                if (!primed) {
                    if (queued >= 2) {
                        primed = true;
                        primeStarted = 0L;
                        next = System.nanoTime();
                    } else {
                        if (queued > 0 && primeStarted == 0L) primeStarted = now;
                        if (queued > 0 && now - primeStarted >= DMR_PRIME_NS) {
                            primed = true;
                            primeStarted = 0L;
                            next = now;
                        } else {
                            synchronized (dmrPlayLock) { dmrPlayLock.wait(5L); }
                            continue;
                        }
                    }
                }
                if (next <= 0L) next = System.nanoTime();
                long wait = next - System.nanoTime();
                while (wait > 0L && dmrPlayRun && generation == dmrPlayGeneration) {
                    Thread.sleep(Math.min(wait, 10_000_000L) / 1_000_000L,
                            (int)(Math.min(wait, 10_000_000L) % 1_000_000L));
                    wait = next - System.nanoTime();
                }
                if (!dmrPlayRun || generation != dmrPlayGeneration) return;
                short[] pcm;
                synchronized (dmrPlayLock) { pcm = dmrPlayQueue.pollFirst(); }
                if (pcm != null && shouldPlayMode(MODE_SIGNAL_DIGITAL) && dmrAudio != null) {
                    dmrAudio.playDmr(pcm);
                    underruns = 0;
                } else if (pcm == null) {
                    underruns++;
                    long last = dmrLastVoiceAtNs;
                    if (underruns >= 4 && (last == 0L || System.nanoTime() - last >= 180_000_000L)) {
                        break;
                    }
                }
                next += DMR_FRAME_NS;
                if (next < System.nanoTime() - 120_000_000L) next = System.nanoTime() + DMR_FRAME_NS;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                android.util.Log.w("ARCH-DMR", "playback queue error", t);
                next = 0L;
                primed = false;
            }
        }
        synchronized (dmrPlayLock) {
            if (generation == dmrPlayGeneration) dmrPlayThread = null;
        }
    }

    private void resetBmPlaybackQueue() {
        int generation;
        Thread old;
        synchronized (bmPlayLock) {
            generation = ++bmPlayGeneration;
            bmPlayQueue.clear();
            bmLastVoiceAtNs = 0L;
            old = bmPlayThread;
            bmPlayThread = null;
            bmPlayLock.notifyAll();
        }
        if (old != null && old != Thread.currentThread()) {
            old.interrupt();
            try { old.join(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void stopBmPlaybackQueue() {
        Thread old;
        synchronized (bmPlayLock) {
            bmPlayRun = false;
            bmPlayGeneration++;
            bmPlayQueue.clear();
            old = bmPlayThread;
            bmPlayThread = null;
            bmPlayLock.notifyAll();
        }
        if (old != null && old != Thread.currentThread()) {
            old.interrupt();
            try { old.join(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void enqueueBmPlayback(short[] pcm) {
        if (pcm == null || pcm.length == 0 || !shouldPlayMode(MODE_SIGNAL_DMR)) return;
        synchronized (bmPlayLock) {
            if (bmPlayQueue.size() >= 3) bmPlayQueue.removeFirst();
            bmPlayQueue.addLast(pcm);
            bmLastVoiceAtNs = System.nanoTime();
            if (bmPlayThread == null || !bmPlayThread.isAlive()) {
                bmPlayRun = true;
                final int generation = bmPlayGeneration;
                bmPlayThread = new Thread(() -> bmPlaybackLoop(generation), "bm-play");
                bmPlayThread.start();
            } else {
                bmPlayLock.notifyAll();
            }
        }
    }

    private void bmPlaybackLoop(int generation) {
        boolean primed = false;
        long primeStarted = 0L;
        long next = 0L;
        int underruns = 0;
        while (bmPlayRun && generation == bmPlayGeneration) {
            try {
                long now = System.nanoTime();
                int queued;
                synchronized (bmPlayLock) { queued = bmPlayQueue.size(); }
                if (!primed) {
                    if (queued >= 2) {
                        primed = true;
                        primeStarted = 0L;
                        next = System.nanoTime();
                    } else {
                        if (queued > 0 && primeStarted == 0L) primeStarted = now;
                        if (queued > 0 && now - primeStarted >= DMR_PRIME_NS) {
                            primed = true;
                            primeStarted = 0L;
                            next = now;
                        } else {
                            synchronized (bmPlayLock) { bmPlayLock.wait(5L); }
                            continue;
                        }
                    }
                }
                if (next <= 0L) next = System.nanoTime();
                long wait = next - System.nanoTime();
                while (wait > 0L && bmPlayRun && generation == bmPlayGeneration) {
                    Thread.sleep(Math.min(wait, 10_000_000L) / 1_000_000L,
                            (int)(Math.min(wait, 10_000_000L) % 1_000_000L));
                    wait = next - System.nanoTime();
                }
                if (!bmPlayRun || generation != bmPlayGeneration) return;
                short[] pcm;
                synchronized (bmPlayLock) { pcm = bmPlayQueue.pollFirst(); }
                if (pcm != null && shouldPlayMode(MODE_SIGNAL_DMR) && bmAudio != null) {
                    bmAudio.playDmr(pcm);
                    underruns = 0;
                } else if (pcm == null) {
                    underruns++;
                    long last = bmLastVoiceAtNs;
                    if (underruns >= 4 && (last == 0L || System.nanoTime() - last >= 180_000_000L)) break;
                }
                next += DMR_FRAME_NS;
                if (next < System.nanoTime() - 120_000_000L) next = System.nanoTime() + DMR_FRAME_NS;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                android.util.Log.w("ARCH-DMR", "BM playback queue error", t);
                next = 0L;
                primed = false;
            }
        }
        synchronized (bmPlayLock) {
            if (generation == bmPlayGeneration) bmPlayThread = null;
        }
    }

    private void dmrPttDown() {
        if (pttHeld) return;
        if (!identityReady()) { toast("请先登录账号"); return; }
        if (dmrHbp == null || !dmrHbp.isConnected()) {
            dmrManualDisconnect = false;
            ArchAppState.pendingPttDown = true;
            ArchAppState.pendingPttAt = System.currentTimeMillis();
            connectDmrIfNeeded();
            toast("正在连接数字中继，连接后自动发射");
            return;
        }
        if (dmrRxActive) {
            pttDeniedTone();
            toast("对方正在发射，禁止发射");
            return;
        }
        int perm = Build.VERSION.SDK_INT >= 23
                ? checkSelfPermission(Manifest.permission.RECORD_AUDIO) : PackageManager.PERMISSION_GRANTED;
        if (perm != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            toast("需要麦克风权限");
            return;
        }
        NetworkKeepAliveService.ensureMicrophoneForeground(this);
        if (dmrAudio == null && !ensureDmrAudio()) {
            toast("数字中继音频初始化失败");
            return;
        }
        releaseWarmNetworkMic("digital PTT");
        pttGeneration++;
        final int pttGen = pttGeneration;
        activePttMode = MODE_SIGNAL_DIGITAL;
        pttHeld = true;
        pttGranted = true;
        setPnc380SpeakerTx(true);
        flushAllReceiveAudioForPtt();
        localPttVisualActive = true;
        dmrTxing = true;
        dmrTxStart = System.currentTimeMillis();
        dmrTxBursts = 0;
        dmrTxAcc = new byte[0];
        try { synchronized (dmrVoc) { dmrVoc.reset(); } } catch (Throwable ignored) {}
        final HbpClient txClient = dmrHbp;
        if (txClient == null || !txClient.txStart()) {
            try { dmrPttUp(); } catch (Throwable ignored) {}
            toast("数字中继发射启动失败");
            return;
        }
        showTransmitIndicator();
        pttToggleActive = true;
        schedulePttTot();
        pttBeep(true);
        refreshPttUi();
        updateDmrUi("发射中 · " + p.callsign(), false);
        boolean dmrMicStarted = dmrAudio.startMicChecked(frame -> {
            if (!dmrTxing || pttGen != pttGeneration || activePttMode != MODE_SIGNAL_DIGITAL
                    || dmrVoc == null) return;
            byte[] a9 = new byte[9];
            try {
                synchronized (dmrVoc) { if (!dmrVoc.encode(frame, a9)) return; }
            } catch (Throwable ignored) { return; }
            byte[] tx = new byte[dmrTxAcc.length + 9];
            System.arraycopy(dmrTxAcc, 0, tx, 0, dmrTxAcc.length);
            System.arraycopy(a9, 0, tx, dmrTxAcc.length, 9);
            dmrTxAcc = tx;
            if (dmrTxAcc.length >= 27) {
                if (pttGen != pttGeneration || activePttMode != MODE_SIGNAL_DIGITAL || !dmrTxing) return;
                byte[] burst = new byte[27];
                System.arraycopy(dmrTxAcc, 0, burst, 0, 27);
                byte[] rest = new byte[dmrTxAcc.length - 27];
                System.arraycopy(dmrTxAcc, 27, rest, 0, rest.length);
                dmrTxAcc = rest;
                if (!txClient.txVoice(burst)) {
                    ui.post(() -> {
                        if (pttGen == pttGeneration && pttHeld && activePttMode == MODE_SIGNAL_DIGITAL) {
                            toast("数字中继语音发送失败，已结束发射");
                            try { dmrPttUp(); } catch (Throwable ignored) {}
                        }
                    });
                    return;
                }
                dmrTxBursts++;
                final int seconds = dmrTxBursts * 3 / 50;
                ui.post(() -> { if (dmrTimerText != null) dmrTimerText.setText("发射 " + seconds + " 秒"); });
            }
        }, error -> ui.post(() -> {
            if (pttGen == pttGeneration && pttHeld && activePttMode == MODE_SIGNAL_DIGITAL) {
                toast("麦克风读取失败，已结束发射");
                try { dmrPttUp(); } catch (Throwable ignored) {}
            }
        }));
        if (!dmrMicStarted) {
            try { dmrPttUp(); } catch (Throwable ignored) {}
            toast("麦克风启动失败，发射已取消");
        }
    }

    private void dmrPttUp() {
        pttGeneration++;
        if (!pttHeld && !dmrTxing) {
            ArchAppState.pendingPttDown = false;
            return;
        }
        android.util.Log.i("ARCH-PTT", "dmrPttUp duration="
                + (dmrTxStart == 0 ? 0 : (System.currentTimeMillis() - dmrTxStart)) + "ms");
        pttHeld = false;
        pttGranted = false;
        activePttMode = -1;
        localPttVisualActive = false;
        pttToggleActive = false;
        dmrTxing = false;
        stopPttTot();
        if (dmrAudio != null) try { dmrAudio.startMic(null); } catch (Throwable ignored) {}
        if (dmrHbp != null) try { dmrHbp.txEnd(); } catch (Throwable ignored) {}
        setPnc380SpeakerTx(false);
        refreshDeviceIndicatorForAudio();
        int sec = dmrTxStart == 0 ? 0 : (int)((System.currentTimeMillis() - dmrTxStart) / 1000);
        dmrTxStart = 0;
        dmrTxAcc = new byte[0];
        dmrTxBursts = 0;
        pttBeep(false);
        refreshPttUi();
        updateDmrUi(dmrConnected ? "空闲" : "未连接", false);
        dmrLog("发射 " + p.callsign() + " · " + sec + " 秒");
    }

    private void dmrLog(String line) {
        if (dmrLogBox == null) return;
        TextView row = text(line, 10, SUB, false);
        row.setPadding(0, dp(2), 0, dp(2));
        dmrLogBox.addView(row, 0);
        while (dmrLogBox.getChildCount() > 10) dmrLogBox.removeViewAt(dmrLogBox.getChildCount() - 1);
    }

    private void updateDmrUi(String status, boolean rx) {
        if (dmrTgText != null) dmrTgText.setText("当前组: " + dmrTgLabel());
        if (dmrCallsignText != null) {
            if (dmrRxActive) {
                String peer = dmrActiveCallsign == null ? "" : dmrActiveCallsign.trim();
                dmrCallsignText.setText(peer.isEmpty() ? "接收中" : peer);
                dmrCallsignText.setTextColor(GREEN);
                dmrCallsignText.setVisibility(View.VISIBLE);
            } else if (dmrTxing || pttHeld) {
                dmrCallsignText.setText(p.callsign());
                dmrCallsignText.setTextColor(RED);
                dmrCallsignText.setVisibility(View.VISIBLE);
            } else {
                dmrCallsignText.setText("");
                dmrCallsignText.setVisibility(View.GONE);
            }
        }
        paintDmrGroupButtons();
    }

    private final class DmrSourceListener implements HbpClient.Listener {
        private final int generation;
        DmrSourceListener(int generation) { this.generation = generation; }
        @Override public void onState(int state, String text) {
            if (generation != dmrConnectionGeneration) return;
            android.util.Log.i("ARCH-DMR", "state=" + state + " " + (text == null ? "" : text));
            ui.post(() -> {
                if (generation != dmrConnectionGeneration) return;
                if (state == 2) {
                    dmrConnected = true;
                    dmrManualDisconnect = false;
                    updateDmrUi("数字中继已连接 · " + p.dmrHost(), false);
                    final int attachTg = p.tg();
                    final int attachSlot = p.tgSlot();
                    final HbpClient attachClient = dmrHbp;
                    final int attachGeneration = dmrConnectionGeneration;
                    ui.postDelayed(() -> {
                        if (generation == dmrConnectionGeneration && attachGeneration == dmrConnectionGeneration
                                && dmrHbp == attachClient && attachClient != null && attachClient.isConnected())
                            attachClient.setTalkgroup(attachTg, attachSlot);
                    }, 350L);
                    ui.postDelayed(() -> {
                        if (generation == dmrConnectionGeneration && attachGeneration == dmrConnectionGeneration
                                && dmrHbp == attachClient && attachClient != null && attachClient.isConnected())
                            attachClient.setTalkgroup(p.tg(), p.tgSlot());
                    }, 1500L);
                    ui.postDelayed(MainActivity.this::maybeStartPendingPtt, 420L);
                } else if (state == 3 || state == 0) {
                    HbpClient failed = dmrHbp;
                    boolean ownsPtt = activePttMode == MODE_SIGNAL_DIGITAL;
                    if (ownsPtt && failed != null) { try { failed.txEnd(); } catch (Throwable ignored) {} }
                    dmrConnected = false;
                    dmrRxActive = false;
                    clearModeSignal(MODE_SIGNAL_DIGITAL);
                    if (ownsPtt) {
                        pttGeneration++;
                        dmrTxing = false;
                        pttHeld = false;
                        pttGranted = false;
                        activePttMode = -1;
                        localPttVisualActive = false;
                        pttToggleActive = false;
                        stopPttTot();
                        clearPttPrebuffer();
                        dmrTxAcc = new byte[0]; dmrTxBursts = 0; dmrTxStart = 0L;
                        try { if (dmrAudio != null) dmrAudio.startMic(null); } catch (Throwable ignored) {}
                    }
                    refreshDeviceIndicatorForAudio();
                    updateDmrUi(state == 3 ? "连接异常，准备重连" : "未连接", false);
                    refreshPttUi();
                    if (state == 3 && !dmrManualDisconnect) {
                        dmrConnectionGeneration++;
                        dmrHbp = null;
                        scheduleDmrReconnect();
                    }
                }
            });
        }
        @Override public void onRxStart(int src, int dst) {
            if (generation != dmrConnectionGeneration) return;
            markModeSignal(MODE_SIGNAL_DIGITAL);
            try { ensureDmrAudio(); } catch (Throwable ignored) {}
            resetDmrPlaybackQueue();
            dmrRxActive = true;
            dmrActiveCallsign = dmrContact(src);
            dmrRxStart = System.currentTimeMillis();
            try { synchronized (dmrVoc) { if (dmrVoc != null) dmrVoc.reset(); } } catch (Throwable ignored) {}
            refreshDeviceIndicatorForAudio();
            ui.post(() -> {
                if (digitalPage) updateDmrUi("接收中 · " + dmrContact(src), true);
            });
        }
        @Override public void onRxVoice(byte[] ambe27) {
            if (generation != dmrConnectionGeneration) return;
            markModeSignal(MODE_SIGNAL_DIGITAL);
            if (ambe27 == null || ambe27.length < 27 || dmrVoc == null || pttHeld
                    || !shouldPlayMode(MODE_SIGNAL_DIGITAL)) return;
            short[] combined = new short[480];
            int len = 0;
            for (int off = 0; off + 9 <= 27; off += 9) {
                byte[] a9 = new byte[9];
                System.arraycopy(ambe27, off, a9, 0, 9);
                short[] pcm = new short[160];
                try { synchronized (dmrVoc) { if (dmrVoc.decode(a9, pcm)) { System.arraycopy(pcm, 0, combined, len, 160); len += 160; } } }
                catch (Throwable ignored) {}
            }
            if (len > 0 && !pttHeld) {
                try {
                    ensureDmrAudio();
                    if (dmrAudio != null) {
                        short[] pcmOut = len == combined.length ? combined : java.util.Arrays.copyOf(combined, len);
                        // Digital relay uses a short jitter queue. HBP is UDP and
                        // real sequence gaps were observed, so writing each 60 ms
                        // block straight into AudioTrack turns minor network jitter
                        // into underruns and clicks. DMR-direct keeps its separate
                        // playback path unchanged.
                        enqueueDmrPlayback(pcmOut);
                    }
                } catch (Throwable ignored) {}
            }
        }
        @Override public void onRxEnd(int src, int dst, int seconds) {
            if (generation != dmrConnectionGeneration) return;
            clearModeSignal(MODE_SIGNAL_DIGITAL);
            dmrRxActive = false;
            dmrActiveCallsign = "";
            int sec = seconds > 0 ? seconds : (dmrRxStart == 0 ? 0 : (int)((System.currentTimeMillis() - dmrRxStart) / 1000));
            dmrRxStart = 0;
            refreshDeviceIndicatorForAudio();
            ui.post(() -> {
                if (digitalPage && dmrLastText != null) {
                    dmrLastText.setText("上次呼叫: " + dmrContact(src) + " · " + sec + " 秒");
                    dmrLastText.setVisibility(View.VISIBLE);
                    updateDmrUi(dmrConnected ? "空闲" : "未连接", false);
                    dmrLog("接收 " + dmrContact(src) + " · " + sec + " 秒");
                }
            });
        }
        @Override public void onLog(String s) { if (s != null && !s.isEmpty()) ui.post(() -> dmrLog(s)); }
    }

    private void toggleDmrDirectMode() {
        // DMR is a navigation tab. Repeated confirm must never toggle it off.
        if (!p.dmrDirectEnabled()) return;
        if (!bmPage) showBmDirectPage();
        else {
            refreshTopNavColors();
            focusInitialControl();
        }
    }

    private void showBmDirectPage() {
        if (!p.dmrDirectEnabled()) return;
        android.util.Log.i("ARCH-DMR", "open DMR direct page");
        if (pttHeld) { try { pttUp(); } catch (Throwable ignored) {} }
        if (radio != null && radio.isPlaying()) radio.pause();
        releaseWarmNetworkMic("DMR");
        bmPage = true; digitalPage = false; netPage = false; radioPage = false;
        syncVolumeControlStream();
        lastHbpMode = MODE_SIGNAL_DMR;
        p.rememberBmTg(p.bmTg());
        p.setLastPageMode("dmr");
        // DMR-direct uses BM and must not tear down the FreeDMR digital relay.
        refreshTopNavColors();
        if (networkDock != null) networkDock.setVisibility(View.GONE);
        RadioSpectrum.setUiVisible(false); setPttWrapVisibility(true);
        body.removeAllViews();
        body.setPadding(dp(shortScreen ? 4 : 6), dp(shortScreen ? 1 : 3), dp(shortScreen ? 4 : 6), dp(shortScreen ? 8 : 12));
        bmConnected = bmHbp != null && bmHbp.isConnected();
        buildBmBody(); installKeyNavigation(body); body.post(this::focusInitialControl);
        updateBmUi(bmConnected ? "空闲" : "正在连接…", false);
        refreshHyteraDisplay();
        syncModeConnections();
        if (!p.multiListen()) flushNonCurrentModeAudio();
    }

    private void buildBmBody() {
        LinearLayout status = card();
        bmTgText = text(bmTgLabel(), uiProfile != null && uiProfile.pnc370 ? 19 : (shortScreen ? 18 : 17), TXT, true);
        boolean pncSmall = uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380);
        bmCallsignText = text("", pncSmall ? 27 : (shortScreen ? 24 : 22), ACCENT, true);
        bmCallsignText.setSingleLine(true);
        bmCallsignText.setIncludeFontPadding(false);
        bmCallsignText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bmCallsignText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bmCallsignText.setVisibility(View.GONE);
        bmCountryText = text("", shortScreen ? 16 : 14, RED, true);
        bmCountryText.setSingleLine(true);
        bmCountryText.setIncludeFontPadding(false);
        bmCountryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bmCountryText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bmCountryText.setVisibility(View.GONE);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(bmTgText, new LinearLayout.LayoutParams(0, -1, 1f));
        bmCallerBox = new LinearLayout(this);
        bmCallerBox.setOrientation(LinearLayout.VERTICAL);
        bmCallerBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bmCallerBox.setPadding(dp(5), 0, dp(8), 0);
        bmCallerBox.setVisibility(View.GONE);
        int callerHeight = pncSmall ? 66 : (shortScreen ? 54 : 58);
        if (pncSmall) {
            bmCallerBox.addView(bmCallsignText, new LinearLayout.LayoutParams(-1, dp(40)));
            bmCallerBox.addView(bmCountryText, new LinearLayout.LayoutParams(-1, dp(24)));
        } else {
            bmCallerBox.addView(bmCallsignText, new LinearLayout.LayoutParams(-1, 0, 1.35f));
            bmCallerBox.addView(bmCountryText, new LinearLayout.LayoutParams(-1, 0, 0.8f));
        }
        top.addView(bmCallerBox, new LinearLayout.LayoutParams(0, dp(callerHeight), 1.18f));
        status.addView(top, new LinearLayout.LayoutParams(-1, dp(callerHeight)));
        bmLastText = text("上次呼叫: --", shortScreen ? 13 : 14, GREEN, true);
        bmLastText.setSingleLine(true);
        bmLastText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bmLastText.setVisibility(View.GONE);
        bmServerText = text("", shortScreen ? 14 : 13, ACCENT, true);
        bmServerText.setSingleLine(true);
        bmServerText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        lastRow.addView(bmLastText, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams serverLp = new LinearLayout.LayoutParams(-2, -2);
        serverLp.setMargins(dp(8), 0, 0, 0);
        lastRow.addView(bmServerText, serverLp);
        status.addView(lastRow);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(shortScreen ? 1 : 3), 0, dp(shortScreen ? 2 : 4));
        status.setLayoutParams(statusLp);
        body.addView(status);
        LinearLayout groups = card();
        groups.setBackground(dmrPanelBg());
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("DMR 通话组", shortScreen ? 15 : 14, TXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        bmGroupStatusText = text("已断开", shortScreen ? 14 : 13, RED, true); header.addView(bmGroupStatusText); groups.addView(header);
        bmTgRow = new LinearLayout(this); bmTgRow.setOrientation(LinearLayout.VERTICAL); groups.addView(bmTgRow);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(0, dp(2), 0, dp(4));
        bmAddButton = button("添加组", false); bmManageButton = button("管理组", false);
        bmAddButton.setId(View.generateViewId()); bmManageButton.setId(View.generateViewId());
        bmAddButton.setSingleLine(true); bmManageButton.setSingleLine(true);
        styleDmrActionFocus(bmAddButton); styleDmrActionFocus(bmManageButton);
        bmAddButton.setIncludeFontPadding(false); bmManageButton.setIncludeFontPadding(false);
        bmAddButton.setOnClickListener(v -> showAddBmGroupDialog()); bmManageButton.setOnClickListener(v -> showManageBmGroupsDialog());
        int actionHeight = dp(shortScreen ? 34 : 40);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, actionHeight, 1f); alp.setMargins(dp(3), dp(2), dp(3), 0); actions.addView(bmAddButton, alp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, actionHeight, 1f); mlp.setMargins(dp(3), dp(2), dp(3), 0); actions.addView(bmManageButton, mlp);
        groups.addView(actions);
        // Restore the original internal spacing, then keep the card's rounded
        // bottom border away from the screen edge with an external margin.
        // Match the digital page: the gap belongs inside the rounded group
        // card, directly below the action buttons. Keep the page itself free
        // of an extra bottom spacer or card margin.
        groups.setPadding(dp(shortScreen ? 6 : 8), dp(shortScreen ? 4 : 7), dp(shortScreen ? 6 : 8), dp(8));
        LinearLayout.LayoutParams groupsLp = new LinearLayout.LayoutParams(-1, -2);
        body.addView(groups, groupsLp);
        refreshBmGroupButtons(); updateBmUi(bmConnected ? "空闲" : "未连接", false);
    }

    private java.util.LinkedHashMap<Integer,String> bmTalkgroupMap() {
        java.util.LinkedHashMap<Integer,String> map = new java.util.LinkedHashMap<>();
        for (String part : p.bmTalkgroups().split(",")) {
            if (part == null || part.trim().isEmpty()) continue;
            String[] kv = part.trim().split(":", 2);
            try {
                int id = Integer.parseInt(kv[0].trim());
                if (id > 0) map.put(id, kv.length > 1 && !kv[1].trim().isEmpty() ? kv[1].trim() : String.valueOf(id));
            } catch (Throwable ignored) {}
        }
        if (map.isEmpty()) map.put(p.bmTg(), String.valueOf(p.bmTg()));
        return map;
    }

    private void saveBmTalkgroups(java.util.LinkedHashMap<Integer,String> map) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer,String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        p.set("bmTalkgroups", sb.toString());
    }

    private String bmTgName(int tg) {
        for (String part : p.bmTalkgroups().split(",")) {
            String[] kv = part.trim().split(":", 2);
            try { if (Integer.parseInt(kv[0].trim()) == tg) return kv.length > 1 ? kv[1].trim() : String.valueOf(tg); }
            catch (Throwable ignored) {}
        }
        return String.valueOf(tg);
    }

    private String bmTgLabel() { return "当前组: " + bmTgName(p.bmTg()) + " · " + p.bmTg(); }

    /** DMR group card: name on the left, group ID on the right. */
    private final class BmTgCard extends LinearLayout {
        private final TextView nameView;
        private final TextView idView;
        private final ImageView lockView;
        private final int tg;
        BmTgCard(String name, int tg) {
            super(MainActivity.this);
            this.tg = tg;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(10), dp(3), dp(10), dp(3));
            setMinimumHeight(dp(shortScreen ? 36 : 44));
            setClickable(true);
            nameView = text(name, uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380) ? 19 : 16, TXT, true);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            idView = text(String.valueOf(tg), uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380) ? 18 : 15, SUB, true);
            idView.setSingleLine(true);
            idView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            addView(nameView, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(-2, -2);
            idLp.setMargins(dp(8), 0, 0, 0);
            addView(idView, idLp);
            lockView = new ImageView(MainActivity.this);
            lockView.setImageResource(R.drawable.ic_lock_small);
            lockView.setColorFilter(ACCENT);
            lockView.setContentDescription("已锁定");
            lockView.setVisibility(View.GONE);
            LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lockLp.setMargins(dp(5), 0, 0, 0);
            addView(lockView, lockLp);
            setContentDescription("DMR通话组 " + name + " TG" + tg);
            setOnClickListener(v -> selectBmTg(tg));
            setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                toggleBmTgLock(tg);
                return true;
            });
            makeFocusable(this);
        }
        void paint(boolean selected) {
            int groupTextColor = p.dark() ? 0xFF7DD3FC : 0xFF075985;
            setBackground(dmrTgButtonBg(selected));
            nameView.setTextColor(selected ? groupTextColor : (p.dark() ? 0xFFE2E8F0 : TXT));
            idView.setTextColor(selected ? groupTextColor : (p.dark() ? 0xFFB8C7D9 : SUB));
            nameView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            idView.setTypeface(null, Typeface.BOLD);
            lockView.setVisibility(selected && lockedBmTg == tg ? View.VISIBLE : View.GONE);
            setTag(DMR_GROUP_TAG_KEY, Boolean.TRUE);
            refreshLegacyFocusBackground(this);
            invalidate();
        }
    }

    private void styleDmrActionFocus(final Button button) {
        if (button == null) return;
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD);
        normal.setCornerRadius(dp(999));
        normal.setStroke(dp(2), p.dark() ? 0xFF60758B : 0xFF9FB2C5);
        button.setBackground(normal);
        button.setTextColor(ACCENT);
        button.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(focused ? 0xFF111111 : CARD);
            bg.setCornerRadius(dp(999));
            bg.setStroke(dp(2), focused ? 0xFFFF9F0A : (p.dark() ? 0xFF60758B : 0xFF9FB2C5));
            view.setBackground(bg);
            button.setTextColor(focused ? Color.WHITE : ACCENT);
            view.invalidate();
            if (focused) {
                ensureFocusVisible(view);
                if (mainScroll != null) mainScroll.postDelayed(
                        () -> mainScroll.smoothScrollBy(0, dp(12)), 80L);
            }
        });
    }

    private void refreshBmGroupButtons() {
        if (bmTgRow == null) return;
        bmTgRow.removeAllViews(); bmTgButtons.clear(); bmTgValues.clear();
        LinearLayout row = null; int idx = 0;
        for (java.util.Map.Entry<Integer,String> e : bmTalkgroupMap().entrySet()) {
            if (idx % 2 == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); bmTgRow.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            final int tg = e.getKey();
            BmTgCard b = new BmTgCard(e.getValue(), tg);
            b.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(shortScreen ? 36 : 44), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, lp); bmTgButtons.add(b); bmTgValues.add(tg); idx++;
        }
        wireBmTalkgroupNavigation();
        paintBmGroupButtons();
        focusBmTalkgroup(p.bmTg());
    }

    private void paintBmGroupButtons() {
        for (int i = 0; i < bmTgButtons.size(); i++) {
            View b = bmTgButtons.get(i);
            int tg = i < bmTgValues.size() ? bmTgValues.get(i) : 0;
            if (b instanceof BmTgCard) ((BmTgCard) b).paint(tg == p.bmTg());
        }
        if (bmGroupStatusText != null) {
            bmGroupStatusText.setText(bmConnected ? "已连接" : "已断开");
            bmGroupStatusText.setTextColor(bmConnected ? GREEN : RED);
        }
    }

    private void wireBmTalkgroupNavigation() {
        for (int i = 0; i < bmTgButtons.size(); i++) {
            View b = bmTgButtons.get(i);
            if ((i & 1) == 1) b.setNextFocusLeftId(bmTgButtons.get(i - 1).getId());
            if ((i & 1) == 0 && i + 1 < bmTgButtons.size()) b.setNextFocusRightId(bmTgButtons.get(i + 1).getId());
            if (i >= 2) b.setNextFocusUpId(bmTgButtons.get(i - 2).getId());
            if (i + 2 < bmTgButtons.size()) b.setNextFocusDownId(bmTgButtons.get(i + 2).getId());
            else if (bmAddButton != null) b.setNextFocusDownId(bmAddButton.getId());
        }
        if (bmAddButton != null && bmManageButton != null) {
            int last = Math.max(0, bmTgButtons.size() - 1);
            int up = bmTgButtons.get(last).getId();
            bmAddButton.setNextFocusUpId(up);
            bmAddButton.setNextFocusRightId(bmManageButton.getId());
            bmManageButton.setNextFocusUpId(up);
            bmManageButton.setNextFocusLeftId(bmAddButton.getId());
        }
    }

    private void focusBmTalkgroup(int tg) {
        if (!bmPage || !keyNavigation || bmTgButtons.isEmpty()) return;
        int index = bmTgValues.indexOf(tg);
        if (index < 0) index = 0;
        final View target = bmTgButtons.get(index);
        target.post(() -> {
            if (!bmPage || target.getParent() == null) return;
            target.requestFocus();
            ensureFocusVisible(target);
        });
    }

    private void selectBmTg(int tg) {
        if (lockedBmTg > 0 && tg != lockedBmTg) {
            toast("DMR 直连通话组已锁定，长按确认键解锁");
            focusBmTalkgroup(lockedBmTg);
            return;
        }
        p.rememberBmTg(tg);
        if (bmTgText != null) bmTgText.setText(bmTgLabel());
        boolean restart = bmHbp != null && bmHbp.isRunning();
        if (restart) disconnectBm();
        bmManualDisconnect = false;
        bmLastConnectAttempt = 0L;
        refreshBmGroupButtons();
        refreshHyteraDisplay();
        if (bmPage && p.dmrDirectEnabled()) connectBmIfNeeded(true);
    }

    private void showAddBmGroupDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(12), dp(4), dp(12), 0);
        EditText id = input("通话组 ID", "", InputType.TYPE_CLASS_NUMBER);
        EditText name = input("名称", "", InputType.TYPE_CLASS_TEXT);
        form.addView(id); form.addView(name);
        new AlertDialog.Builder(this).setTitle("添加 DMR 通话组").setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (d,w) -> {
                    int tg; try { tg = Integer.parseInt(value(id)); } catch (Throwable t) { toast("通话组 ID 无效"); return; }
                    if (tg <= 0) { toast("通话组 ID 无效"); return; }
                    java.util.LinkedHashMap<Integer,String> map = bmTalkgroupMap();
                    map.put(tg, value(name).isEmpty() ? String.valueOf(tg) : value(name));
                    saveBmTalkgroups(map); refreshBmGroupButtons(); toast("已添加 TG" + tg);
                }).show();
    }

    private void showManageBmGroupsDialog() {
        java.util.LinkedHashMap<Integer,String> map = bmTalkgroupMap();
        String[] labels = new String[map.size()]; int i = 0;
        for (java.util.Map.Entry<Integer,String> e : map.entrySet()) labels[i++] = e.getKey() + " · " + e.getValue();
        new AlertDialog.Builder(this).setTitle("管理 DMR 通话组").setItems(labels, (d,w) -> {
            int idx = 0;
            for (java.util.Map.Entry<Integer,String> e : map.entrySet()) {
                if (idx++ == w) { showEditBmGroupDialog(e.getKey(), e.getValue()); break; }
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void showEditBmGroupDialog(final int oldTg, String oldName) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(12), dp(4), dp(12), 0);
        EditText id = input("通话组 ID", String.valueOf(oldTg), InputType.TYPE_CLASS_NUMBER);
        EditText name = input("名称", oldName, InputType.TYPE_CLASS_TEXT);
        form.addView(id); form.addView(name);
        new AlertDialog.Builder(this).setTitle("修改 DMR 通话组").setView(form)
                .setNegativeButton("取消", null)
                .setNeutralButton("删除", (d,w) -> removeBmGroup(oldTg))
                .setPositiveButton("保存", (d,w) -> {
                    int tg; try { tg = Integer.parseInt(value(id)); } catch (Throwable t) { toast("通话组 ID 无效"); return; }
                    if (tg <= 0) { toast("通话组 ID 无效"); return; }
                    java.util.LinkedHashMap<Integer,String> map = bmTalkgroupMap(); map.remove(oldTg);
                    map.put(tg, value(name).isEmpty() ? String.valueOf(tg) : value(name));
                    saveBmTalkgroups(map);
                    if (p.bmTg() == oldTg) p.rememberBmTg(tg);
                    refreshBmGroupButtons(); toast("DMR 通话组已保存");
                }).show();
    }

    private void removeBmGroup(int tg) {
        java.util.LinkedHashMap<Integer,String> map = bmTalkgroupMap();
        if (map.remove(tg) == null) return;
        saveBmTalkgroups(map);
        if (p.bmTg() == tg && !map.isEmpty()) p.rememberBmTg(map.keySet().iterator().next());
        refreshBmGroupButtons(); toast("已删除 TG" + tg);
        if (bmHbp != null) { disconnectBm(); bmLastConnectAttempt = 0L; connectBmIfNeeded(true); }
    }

    private void updateBmUi(String state, boolean receiving) {
        if (bmGroupStatusText != null) {
            String value = state == null ? "" : state;
            String label;
            if (bmConnected) label = "已连接";
            else if (value.contains("正在连接")) label = "连接中";
            else if (value.contains("正在同步")) label = "同步中";
            else if (value.contains("异常") || value.contains("失败") || value.contains("未配置")
                    || value.contains("密码") || value.contains("身份") || value.contains("音频")) label = value;
            else label = bmPage ? "已断开" : "已关闭";
            bmGroupStatusText.setText(label);
            bmGroupStatusText.setTextColor(bmConnected ? GREEN
                    : (("连接中".equals(label) || "同步中".equals(label)) ? ACCENT : RED));
        }
        if (bmTgText != null) bmTgText.setText(bmTgLabel());
        if (bmServerText != null) bmServerText.setText("服务器: " + p.bmServerKey());
        if (bmCallsignText != null) {
            String country = "";
            boolean showCaller = receiving || (pttHeld && bmPage);
            if (bmCallerBox != null) bmCallerBox.setVisibility(showCaller ? View.VISIBLE : View.GONE);
            if (receiving && !bmActiveCallsign.isEmpty()) {
                bmCallsignText.setText(bmActiveCallsign); bmCallsignText.setTextColor(GREEN); bmCallsignText.setVisibility(View.VISIBLE);
                country = CountryBook.countryFromCallsign(bmActiveCallsign);
                if (country.isEmpty()) country = CountryBook.country(bmActiveId);
            } else if (pttHeld && bmPage) {
                bmCallsignText.setText(p.bmCallsign()); bmCallsignText.setTextColor(RED); bmCallsignText.setVisibility(View.VISIBLE);
                country = CountryBook.countryFromCallsign(p.bmCallsign());
                if (country.isEmpty()) country = CountryBook.country(p.bmId());
            } else bmCallsignText.setVisibility(View.GONE);
            if (bmCountryText != null) {
                bmCountryText.setText(country);
                bmCountryText.setVisibility(country.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
        if (bmLastText != null && !bmLastText.getText().toString().contains("--")) bmLastText.setVisibility(View.VISIBLE);
    }

    private String bmOptions() { return (p.bmSlot() == 2 ? "TS2=" : "TS1=") + p.bmTg() + ";"; }

    private void scheduleBmAutoStatic(long delay) {
        final int generation = ++bmAttachGeneration;
        final int connectionGeneration = bmConnectionGeneration;
        ui.postDelayed(() -> {
            if (generation == bmAttachGeneration && connectionGeneration == bmConnectionGeneration)
                autoStaticBmTalkgroup();
        }, delay);
    }

    private void autoStaticBmTalkgroup() {
        final HbpClient client = bmHbp;
        if (client == null || !client.isConnected() || bmAutoStaticRunning) return;
        if (pttHeld || bmRxActive) {
            scheduleBmAutoStatic(1800L);
            return;
        }
        final int targetTg = p.bmTg();
        final int targetSlot = p.bmSlot();
        if (targetTg <= 0) return;
        bmAutoStaticRunning = true;
        new Thread(() -> {
            try {
                if (!client.isConnected() || pttHeld || bmRxActive) {
                    if (client.isConnected()) scheduleBmAutoStatic(1800L);
                    return;
                }
                android.util.Log.i("ARCH-DMR", "BM auto-static begin: TG4000 -> TG" + targetTg
                        + " peer=" + p.bmId() + "/" + p.bmEssid());

                if (!client.txHeaderTo(4000)) return;
                // BM answers TG4000 with a short private control burst. Sending
                // the target key-up before that finishes causes it to be lost.
                Thread.sleep(2000L);
                if (!client.isConnected()) return;
                if (pttHeld || bmRxActive) {
                    scheduleBmAutoStatic(1800L);
                    return;
                }

                if (!client.txHeaderTo(targetTg)) return;
                Thread.sleep(120L);

                client.setTalkgroup(targetTg, targetSlot);
                android.util.Log.i("ARCH-DMR", "BM auto-static complete: TG" + targetTg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                android.util.Log.w("ARCH-DMR", "BM auto-static failed", t);
            } finally {
                bmAutoStaticRunning = false;
            }
        }, "bm-auto-static").start();
    }

    private void connectBmIfNeeded() {
        if (!p.dmrDirectEnabled() || bmManualDisconnect) return; connectBmIfNeeded(false); }

    private void connectBmIfNeeded(boolean force) {
        if (!p.dmrDirectEnabled()) return;
        if (!NetworkRecovery.get(this).isOnline()) return;
        if (bmHbp != null && (bmHbp.isConnected() || bmHbp.isConnecting())) return;
        if (bmHbp != null) { try { bmHbp.stop(); } catch (Throwable ignored) {} bmHbp = null; }
        if (bmConnectTask != null) return;
        bmConnectTask = () -> {
            bmConnectTask = null;
            if (p.dmrDirectEnabled() && !bmManualDisconnect
                    && (bmHbp == null || (!bmHbp.isConnected() && !bmHbp.isConnecting()))) {
                connectBmNow(force);
            }
        };
        ui.postDelayed(bmConnectTask, 1500L);
    }

    private void connectBmNow(boolean force) {
        if (!p.dmrDirectEnabled()) return;
        long now = System.currentTimeMillis();
        // The server binds one HBP session per hotspot identity. Never allow a
        // second login to race the previous session's release, even for a UI
        // page refresh that asked for a forced connection.
        if (now - bmLastConnectAttempt < 3000L) return;
        bmLastConnectAttempt = now;
        if (bmHbp != null && (bmHbp.isConnected() || bmHbp.isConnecting())) return;
        if (bmHbp != null) { try { bmHbp.stop(); } catch (Throwable ignored) {} bmHbp = null; }
        if (!NetworkRecovery.get(this).isOnline()) return;
        if (p.bmCallsign().isEmpty() || p.bmId() <= 0) { updateBmUi("未配置 DMR 身份", false); return; }
        if (p.bmPassword().isEmpty()) { syncBmCredentialsThenConnect(); return; }
        if (bmVoc == null) bmVoc = new Vocoder();
        if (!ensureBmAudio()) { updateBmUi("音频初始化失败", false); return; }
        int peerId = p.bmId() * 100 + p.bmEssid();
        android.util.Log.i("ARCH-DMR", "DMR HBP connect id=" + p.bmId()
                + " essid=" + p.bmEssid() + " peerId=" + peerId
                + " tg=" + p.bmTg() + " slot=" + p.bmSlot()
                + " rptcSlots=4(simplex)");
        bmManualDisconnect = false;
        bmConnected = false;
        final int generation = ++bmConnectionGeneration;
        updateBmUi("正在连接 DMR…", false);
        bmHbp = new HbpClient(p.bmServerHost(), p.bmServerPort(), peerId, p.bmId(), p.bmTg(), 0,
                p.bmSlot(), 7, p.bmCallsign(), p.bmPassword(), bmOptions(), new BmSourceListener(generation));
        bmHbp.start();
    }

    private void syncBmCredentialsThenConnect() {
        if (bmCredentialSyncRunning) return;
        if (!p.authLoggedIn() || p.password().isEmpty()) {
            updateBmUi("请重新登录以同步 DMR 热点密码", false);
            return;
        }
        bmCredentialSyncRunning = true;
        final int syncGeneration = bmConnectionGeneration;
        updateBmUi("正在同步 DMR 凭据…", false);
        new Thread(() -> {
            String bmPassword = "";
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", p.callsign());
                req.put("dmrId", p.dmrId());
                req.put("essid", p.essid());
                req.put("password", p.password());
                JSONObject out = postAccount("login", req);
                JSONObject account = out.optJSONObject("account");
                if (account != null) {
                    bmPassword = account.optString("bmPassword", "");
                    if (!bmPassword.isEmpty()) p.set("bmPassword", bmPassword);
                    int nxdn = account.optInt("nxdnId", p.nxdnId());
                    if (nxdn > 0) p.set("nxdnId", String.valueOf(nxdn));

                }
            } catch (Throwable t) {
                bmCredentialSyncRunning = false;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || syncGeneration != bmConnectionGeneration) return;
                    updateBmUi("DMR 凭据同步失败", false);
                });
                return;
            }
            final String loadedPassword = bmPassword;
            bmCredentialSyncRunning = false;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || syncGeneration != bmConnectionGeneration) return;
                if (loadedPassword.isEmpty()) {
                    updateBmUi("DMR 热点密码未配置", false);
                    return;
                }
                bmLastConnectAttempt = 0L;
                connectBmIfNeeded(true);
            });
        }, "bm-credential-sync").start();
    }
    private void disconnectBm() {
        bmConnectionGeneration++;
        bmAttachGeneration++;
        if (bmConnectTask != null) { ui.removeCallbacks(bmConnectTask); bmConnectTask = null; }
        if (bmReconnectTask != null) { ui.removeCallbacks(bmReconnectTask); bmReconnectTask = null; }
        if (activePttMode == MODE_SIGNAL_DMR && (pttHeld || dmrTxing)) {
            try { bmPttUp(); } catch (Throwable ignored) {}
        }
        if (bmHbp != null) { try { bmHbp.stop(); } catch (Throwable ignored) {} bmHbp = null; }
        stopBmPlaybackQueue();
        if (bmAudio != null) {
            try { bmAudio.stop(); } catch (Throwable ignored) {}
            bmAudio = null;
        }
        if (bmVoc != null) {
            try { synchronized (bmVoc) { bmVoc.close(); } } catch (Throwable ignored) {}
            bmVoc = null;
        }
        bmConnected = false; bmRxActive = false; ui.removeCallbacks(bmRxWatchdog); clearModeSignal(MODE_SIGNAL_DMR); refreshDeviceIndicatorForAudio(); updateBmUi("未连接", false);
    }

    private void scheduleBmReconnect() {
        if (!p.dmrDirectEnabled() || bmManualDisconnect || bmReconnectTask != null) return;
        if (!NetworkRecovery.get(this).isOnline()) return;
        bmReconnectTask = () -> { bmReconnectTask = null; if (p.dmrDirectEnabled() && !bmManualDisconnect) connectBmIfNeeded(); };
        ui.postDelayed(bmReconnectTask, 4000L);
    }

    private void showBmServerPicker() {
        String[] labels = new String[BmServers.ALL.length]; int current = 0;
        for (int i=0;i<BmServers.ALL.length;i++){ labels[i]=BmServers.ALL[i][1]+" · "+BmServers.ALL[i][2]; if(BmServers.ALL[i][0].equals(p.bmServerKey())) current=i; }
        new AlertDialog.Builder(this).setTitle("DMR 服务器").setSingleChoiceItems(labels,current,(d,w)->{
            p.setBmServer(BmServers.ALL[w][0]); d.dismiss();
            if (bmServerText != null) bmServerText.setText("服务器: " + p.bmServerKey());
            if (bmHbp!=null && bmHbp.isRunning()) { disconnectBm(); bmLastConnectAttempt = 0L; connectBmIfNeeded(true); }
        }).setNegativeButton("取消", null).show();
    }

    private final class BmSourceListener implements HbpClient.Listener {
        private final int generation;
        BmSourceListener(int generation) { this.generation = generation; }

        @Override public void onState(int state, String text) {
            if (generation != bmConnectionGeneration) return;
            ui.post(() -> {
                if (generation != bmConnectionGeneration) return;
                if (state == 2) {
                    bmConnected = true; bmManualDisconnect = false; updateBmUi("已连接", false);
                    final int tg = p.bmTg(); final int slot = p.bmSlot();
                    final HbpClient attachClient = bmHbp;
                    final int attachGeneration = bmConnectionGeneration;
                    ui.postDelayed(() -> {
                        if (generation == bmConnectionGeneration && attachGeneration == bmConnectionGeneration
                                && bmHbp == attachClient && attachClient != null && attachClient.isConnected())
                            attachClient.setTalkgroup(tg, slot);
                    }, 350L);
                    ui.postDelayed(() -> {
                        if (generation == bmConnectionGeneration && attachGeneration == bmConnectionGeneration
                                && bmHbp == attachClient && attachClient != null && attachClient.isConnected())
                            attachClient.setTalkgroup(p.bmTg());
                    }, 1500L);
                    scheduleBmAutoStatic(650L);
                    if (bmPendingPtt) { bmPendingPtt = false; ui.postDelayed(() -> { if (bmPage) pttDown(); }, 420L); }
                } else if (state == 3 || state == 0) {
                    HbpClient failed = bmHbp;
                    boolean ownsPtt = activePttMode == MODE_SIGNAL_DMR;
                    if (ownsPtt && failed != null) { try { failed.txEnd(); } catch (Throwable ignored) {} }
                    bmConnected = false; bmRxActive = false; clearModeSignal(MODE_SIGNAL_DMR);
                    ui.removeCallbacks(bmRxWatchdog);
                    if (ownsPtt) {
                        pttGeneration++;
                        dmrTxing = false; pttHeld = false; pttGranted = false; activePttMode = -1;
                        localPttVisualActive = false; pttToggleActive = false;
                        stopPttTot();
                        clearPttPrebuffer();
                        dmrTxAcc = new byte[0]; dmrTxBursts = 0; dmrTxStart = 0L;
                        try { if (bmAudio != null) bmAudio.startMic(null); } catch (Throwable ignored) {}
                    }
                    refreshDeviceIndicatorForAudio();
                    if (state == 3) { bmConnectionGeneration++; bmHbp = null; }
                    updateBmUi(state == 3 ? "连接异常" : "未连接", false);
                    refreshPttUi();
                    if (state == 3 && p.dmrDirectEnabled() && !bmManualDisconnect) scheduleBmReconnect();
                }
            });
        }
        @Override public void onRxStart(int src, int dst) {
            if (generation != bmConnectionGeneration) return;
            markModeSignal(MODE_SIGNAL_DMR);
            android.util.Log.i("ARCH-DMR", "RX start src=" + src + " dst=" + dst + " call=" + dmrContact(src));
            try { if (bmAudio == null) ensureBmAudio(); } catch (Throwable ignored) {}
            resetBmPlaybackQueue(); bmRxActive = true; bmRxVoiceFrames = 0; bmActiveCallsign = dmrContact(src); bmActiveId = src;
            long rxNow = System.currentTimeMillis();
            bmRxStart = rxNow;
            bmLastVoiceAt = rxNow;
            bmRxTalkMs = 0L;
            // Match Hytera internal 3.3.86: DMR receive uses the same POC
            // receive state as a network talker; no generic LED call.
            try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, true, bmActiveCallsign); } catch (Throwable ignored) {}
            ui.removeCallbacks(bmRxWatchdog);
            ui.postDelayed(bmRxWatchdog, BM_RX_HANG_MS);
            if (bmVoc == null) bmVoc = new Vocoder();
            try { synchronized (bmVoc) { if (bmVoc != null) bmVoc.reset(); } } catch (Throwable ignored) {}
            refreshDeviceIndicatorForAudio();
            ui.post(() -> { if (bmPage) updateBmUi("接收中 · " + bmActiveCallsign, true); });
        }
        @Override public void onRxVoice(byte[] ambe27) {
            if (generation != bmConnectionGeneration) return;
            markModeSignal(MODE_SIGNAL_DMR);
            if (bmRxActive) {
                long voiceNow = System.currentTimeMillis();
                bmLastVoiceAt = voiceNow;
                if (bmRxStart > 0 && voiceNow > bmRxStart) bmRxTalkMs = voiceNow - bmRxStart;
                try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, true, bmActiveCallsign); } catch (Throwable ignored) {}
                ui.removeCallbacks(bmRxWatchdog);
                ui.postDelayed(bmRxWatchdog, BM_RX_HANG_MS);
            }
            if (ambe27 == null || ambe27.length < 27 || pttHeld
                    || !shouldPlayMode(MODE_SIGNAL_DMR)) return;
            if (bmVoc == null) bmVoc = new Vocoder();
            if (bmVoc == null || !bmVoc.ok()) return;
            short[] combined = new short[480]; int len = 0;
            for (int off=0; off+9<=27; off+=9) {
                byte[] a9 = new byte[9]; System.arraycopy(ambe27,off,a9,0,9); short[] pcm = new short[160];
                try { synchronized (bmVoc) { if (bmVoc.decode(a9,pcm)) { System.arraycopy(pcm,0,combined,len,160); len+=160; } } } catch (Throwable ignored) {}
            }
            if (len > 0) {
                try {
                    if (bmAudio == null) ensureBmAudio();
                    if (bmAudio != null) {
                        if (bmRxVoiceFrames < 8) {
                            int peak = 0;
                            for (int i = 0; i < len; i++) {
                                int a = combined[i] < 0 ? -combined[i] : combined[i];
                                if (a > peak) peak = a;
                            }
                            android.util.Log.i("ARCH-DMR", "RX decoded frame=" + bmRxVoiceFrames
                                    + " samples=" + len + " peak=" + peak + " audioReady=" + bmAudio.isReady());
                        }
                        short[] pcmOut = len == combined.length ? combined : java.util.Arrays.copyOf(combined, len);
                        bmRxVoiceFrames++;
                        // Match the proven association build: one decoded 60 ms
                        // PCM block is written directly to the 8 kHz AudioTrack.
                        // A second software queue adds drift and periodic underruns.
                        bmAudio.playDmr(pcmOut);
                    }
                } catch (Throwable t) { android.util.Log.w("ARCH-DMR", "RX decode/play enqueue failed", t); }
            } else if (bmRxVoiceFrames < 8) {
                bmRxVoiceFrames++;
                android.util.Log.w("ARCH-DMR", "RX decoder produced no PCM frame=" + bmRxVoiceFrames);
            }
        }
        @Override public void onRxEnd(int src, int dst, int seconds) {
            if (generation != bmConnectionGeneration) return;
            clearModeSignal(MODE_SIGNAL_DMR);
            ui.removeCallbacks(bmRxWatchdog);
            bmRxActive = false; bmActiveCallsign = ""; bmActiveId = 0;
            try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
            int sec = bmRxTalkMs > 0
                    ? (int)((bmRxTalkMs + 999L) / 1000L)
                    : (seconds > 0 ? seconds : (bmRxStart == 0 ? 0 : (int)((System.currentTimeMillis()-bmRxStart)/1000)));
            bmRxStart = 0;
            bmRxTalkMs = 0L;
            refreshDeviceIndicatorForAudio();
            ui.post(() -> {
                if (bmPage && bmLastText != null) {
                    String lastCall = dmrContact(src);
                    String lastCountry = CountryBook.countryFromCallsign(lastCall);
                    if (lastCountry.isEmpty()) lastCountry = CountryBook.country(src);
                    String lastText = "上次呼叫: " + lastCall + " · " + sec + " 秒";
                    if (!lastCountry.isEmpty()) lastText += " · " + lastCountry;
                    bmLastText.setText(lastText);
                    bmLastText.setTextColor(GREEN);
                    bmLastText.setVisibility(View.VISIBLE);
                    updateBmUi("空闲", false);
                }
            });
        }
        @Override public void onLog(String s) { if (generation == bmConnectionGeneration && s != null && !s.isEmpty()) android.util.Log.i("ARCH-DMR", "DMR " + s); }
    }

    private void bmPttDown() {
        if (pttHeld) return;
        if (bmAutoStaticRunning) { toast("正在自动附着通话组，请稍候"); return; }
        if (!p.dmrDirectEnabled()) { toast("DMR 模式未开启"); return; }
        if (bmHbp == null || !bmHbp.isConnected()) {
            bmPendingPtt = true; bmManualDisconnect = false; connectBmIfNeeded();
            toast("正在连接 DMR，连接后自动发射"); return;
        }
        if (bmRxActive) { pttDeniedTone(); toast("对方正在发射，禁止发射"); return; }
        int perm = Build.VERSION.SDK_INT >= 23 ? checkSelfPermission(Manifest.permission.RECORD_AUDIO) : PackageManager.PERMISSION_GRANTED;
        if (perm != PackageManager.PERMISSION_GRANTED) { requestNeededPermissions(); toast("需要麦克风权限"); return; }
        NetworkKeepAliveService.ensureMicrophoneForeground(this);
        if (bmAudio == null && !ensureBmAudio()) { toast("DMR 音频初始化失败"); return; }
        releaseWarmNetworkMic("DMR PTT");
        pttGeneration++;
        final int pttGen = pttGeneration;
        activePttMode = MODE_SIGNAL_DMR;
        pttHeld = true; pttGranted = true; localPttVisualActive = true;
        setPnc380SpeakerTx(true);
        flushAllReceiveAudioForPtt(); dmrTxing = true; dmrTxStart = System.currentTimeMillis(); dmrTxBursts = 0; dmrTxAcc = new byte[0];
        try { synchronized (bmVoc) { bmVoc.reset(); } } catch (Throwable ignored) {}
        final HbpClient txClient = bmHbp;
        if (txClient == null || !txClient.txStart()) {
            try { bmPttUp(); } catch (Throwable ignored) {}
            toast("DMR 发射启动失败");
            return;
        }
        try { HyteraPdc760Bridge.noteActivityPttDown(this); } catch (Throwable ignored) {}
        showTransmitIndicator();
        pttToggleActive = true; schedulePttTot(); pttBeep(true); refreshPttUi(); updateBmUi("发射中 · " + p.bmCallsign(), false);
        boolean started = bmAudio.startMicChecked(frame -> {
            if (!dmrTxing || pttGen != pttGeneration || activePttMode != MODE_SIGNAL_DMR
                    || bmVoc == null) return;
            byte[] a9 = new byte[9]; try { synchronized (bmVoc) { if (!bmVoc.encode(frame,a9)) return; } } catch (Throwable ignored) { return; }
            byte[] tx = new byte[dmrTxAcc.length+9]; System.arraycopy(dmrTxAcc,0,tx,0,dmrTxAcc.length); System.arraycopy(a9,0,tx,dmrTxAcc.length,9); dmrTxAcc=tx;
            if (dmrTxAcc.length >= 27) {
                if (pttGen != pttGeneration || activePttMode != MODE_SIGNAL_DMR || !dmrTxing) return;
                byte[] burst=new byte[27]; System.arraycopy(dmrTxAcc,0,burst,0,27); byte[] rest=new byte[dmrTxAcc.length-27]; System.arraycopy(dmrTxAcc,27,rest,0,rest.length); dmrTxAcc=rest;
                if (!txClient.txVoice(burst)) {
                    ui.post(() -> {
                        if (pttGen == pttGeneration && pttHeld && activePttMode == MODE_SIGNAL_DMR) {
                            toast("DMR 语音发送失败，已结束发射");
                            try { bmPttUp(); } catch (Throwable ignored) {}
                        }
                    });
                    return;
                }
                dmrTxBursts++;
            }
        }, error -> ui.post(() -> {
            if (pttGen == pttGeneration && pttHeld && activePttMode == MODE_SIGNAL_DMR) {
                toast("麦克风读取失败，已结束发射");
                try { bmPttUp(); } catch (Throwable ignored) {}
            }
        }));
        if (!started) { try { bmPttUp(); } catch (Throwable ignored) {} toast("麦克风启动失败，发射已取消"); }
    }

    private void bmPttUp() {
        pttGeneration++;
        if (!pttHeld && !dmrTxing) { bmPendingPtt = false; return; }
        pttHeld = false; pttGranted = false; activePttMode = -1; localPttVisualActive = false; pttToggleActive = false; dmrTxing = false; bmPendingPtt = false; stopPttTot();
        if (bmAudio != null) try { bmAudio.startMic(null); } catch (Throwable ignored) {}
        if (bmHbp != null) try { bmHbp.txEnd(); } catch (Throwable ignored) {}
        setPnc380SpeakerTx(false);
        try { HyteraPdc760Bridge.noteActivityPttUp(this); } catch (Throwable ignored) {}
        refreshDeviceIndicatorForAudio();
        dmrTxStart = 0; dmrTxAcc = new byte[0]; dmrTxBursts = 0; pttBeep(false); refreshPttUi(); updateBmUi("空闲", false);
    }

    private void switchPage(boolean network) {
        if (pttHeld) { try { pttUp(); } catch (Throwable ignored) {} }
        if (!network) releaseWarmNetworkMic("radio/no-mode");
        digitalPage = false;
        bmPage = false;
        netPage = network;
        radioPage = !network;
        if (network) {
            if (currentRoom != null && !currentRoom.trim().isEmpty()) p.setNetRoom(currentRoom);
            p.setLastPageMode("network");
            if (hardwareProfile != null && hardwareProfile.isP50Pro()) applyAudioRoute(true);
        } else {
            p.setLastPageMode("radio");
        }
        syncVolumeControlStream();
        refreshTopNavColors();
        if (radio != null && radio.isPlaying()) radio.pause();
        setPttWrapVisibility(network);
        if (networkDock != null) networkDock.setVisibility(network && uiProfile != null
                && (uiProfile.pnc370 || uiProfile.pnc380) ? View.VISIBLE : View.GONE);
        RadioSpectrum.setUiVisible(!network);
        body.removeAllViews();
        roomBox = null;
        userBox = null;
        activeRoomView = null;
        roomFocusViews.clear();
        if (network) {
            body.setPadding(dp(shortScreen ? 4 : tinyScreen ? 6 : 10), dp(shortScreen ? 1 : tinyScreen ? 2 : 4),
                    dp(shortScreen ? 4 : tinyScreen ? 6 : 10), dp(shortScreen ? 4 : tinyScreen ? 8 : 18));
            buildNetworkBody();
            if (rooms.isEmpty()) loadRooms();
        } else {
            body.setPadding(dp(9), dp(6), dp(9), dp(10));
            buildRadioBody();
        }
        installKeyNavigation(body);
        body.post(this::focusInitialControl);
        refreshHyteraDisplay();
        syncModeConnections();
        if (!p.multiListen()) flushNonCurrentModeAudio();
    }

    private void flushNonCurrentModeAudio() {
        int active = activeSignalMode();
        if (active != MODE_SIGNAL_NETWORK) flushReceivePlayback();
        if (active != MODE_SIGNAL_DIGITAL) {
            stopDmrPlaybackQueue();
            if (dmrAudio != null) dmrAudio.flushPlayback();
            dmrRxActive = false;
        }
        if (active != MODE_SIGNAL_DMR) {
            stopBmPlaybackQueue();
            if (bmAudio != null) bmAudio.flushPlayback();
            bmRxActive = false;
            ui.removeCallbacks(bmRxWatchdog);
        }
    }

    private void pauseIntercomAudioForSip() {
        try { if (pttHeld || pttGranted || dmrTxing) pttUp(); } catch (Throwable ignored) {}
        try {
            flushAllReceiveAudioForPtt();
            if (radio != null && radio.isPlaying()) radio.pause();
            android.util.Log.i("ARCH-AUDIO", "intercom audio paused for SIP");
        } catch (Throwable ignored) {}
    }

    private void flushAllReceiveAudioForPtt() {
        stopDmrPlaybackQueue();
        if (dmrAudio != null && (dmrRxActive || hardwareProfile == null || !hardwareProfile.isPnc380())) dmrAudio.flushPlayback();
        dmrRxActive = false;
        stopBmPlaybackQueue();
        if (bmAudio != null && (bmRxActive || hardwareProfile == null || !hardwareProfile.isPnc380())) bmAudio.flushPlayback();
        bmRxActive = false;
        networkReceiving = false;
        try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
        flushReceivePlayback();
    }

    private void syncModeConnections() {
        if (p == null || authScreen || !p.authLoggedIn() || !identityReady()) return;
        // Digital relay (ARCH/FreeDMR) and DMR-direct (BM) are separate HBP
        // servers and may run concurrently. Only duplicate sessions to the same
        // host:port are suppressed by HbpClient itself.
        if (!p.showDigital()) {
            if (dmrHbp != null) disconnectDmr(false);
        } else if (!dmrManualDisconnect && dmrHbp == null) {
            connectDmrIfNeeded();
        }
        if (!p.dmrDirectEnabled()) {
            if (bmHbp != null) disconnectBm();
        } else if (!bmManualDisconnect && bmHbp == null) {
            connectBmIfNeeded(true);
        }
        if (!p.showNetwork()) {
            if (activePttMode == MODE_SIGNAL_NETWORK && pttHeld) { try { pttUp(); } catch (Throwable ignored) {} }
            networkWanted = false;
            networkReceiving = false;
            try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
            clearModeSignal(MODE_SIGNAL_NETWORK);
            try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
            flushReceivePlayback();
        } else {
            networkWanted = true;
            if (net == null || (!net.isRunning() && !net.isConnecting())) connectRoom(currentRoom, false);
        }
    }

    private String dmrConnectionSignature() {
        return p.showDigital() + "|" + p.callsign() + "|" + p.dmrId() + "|"
                + p.essid() + "|" + p.dmrHost() + "|" + p.dmrPort() + "|"
                + p.dmrPassword() + "|" + p.tg() + "|" + p.tgSlot() + "|" + p.cc();
    }

    private String bmConnectionSignature() {
        return p.dmrDirectEnabled() + "|" + p.bmCallsign() + "|" + p.bmId() + "|"
                + p.bmEssid() + "|" + p.bmPassword() + "|" + p.bmServerHost() + "|"
                + p.bmServerPort() + "|" + p.bmTg() + "|" + p.bmSlot();
    }

    private String networkConnectionSignature() {
        return p.showNetwork() + "|" + p.callsign() + "|" + p.dmrId() + "|"
                + p.essid() + "|" + p.password() + "|" + p.netRoom();
    }

    private String dmrIdentityLine() {
        if (!identityReady()) return "DMR ID: 未设置 · ESSID: --";
        return "DMR ID: " + p.dmrId() + " · ESSID: " + String.format(Locale.US, "%02d", p.essid());
    }

    private void refreshCallsignCard() {
        String state = networkDisplayState();
        String label = "";
        String party = "";
        int split = state.indexOf(" · ");
        if (split >= 0) {
            label = state.substring(0, split).trim();
            party = state.substring(split + 3).trim();
        } else if (state.startsWith("接收中") || state.startsWith("发射中")) {
            label = state;
        }
        if (localPttVisualActive || pttGranted) {
            label = "发射中";
            party = p.callsign().isEmpty() ? "发射中" : p.callsign();
        }
        if (state.startsWith("发射中") && party.isEmpty()) party = p.callsign().isEmpty() ? "发射中" : p.callsign();
        boolean pnc = uiProfile != null && uiProfile.pnc370;
        if (pnc) {
            if (localPttVisualActive || pttGranted || state.startsWith("发射中")) {
                label = "发射中";
                party = p.callsign();
            } else if (state.startsWith("接收中")) {
                label = "接收中";
            } else {
                label = "";
                party = p.callsign();
            }
        }
        int color = state.startsWith("接收中") ? GREEN : ((localPttVisualActive || pttGranted || state.startsWith("发射中")) ? RED : (pnc ? ACCENT : SUB));
        if (callStateText != null) {
            callStateText.setText(label);
            callStateText.setTextColor(color);
            callStateText.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (callPartyText != null) {
            callPartyText.setText(party);
            callPartyText.setTextColor(color);
            callPartyText.setVisibility(party.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (callRoomText != null) setCallRoomName(roomName(currentRoom));
        if (callRoomBox != null) callRoomBox.setVisibility(View.VISIBLE);
        if (callLockIcon != null) callLockIcon.setVisibility(!lockedRoom.isEmpty() && lockedRoom.equals(currentRoom) ? View.VISIBLE : View.GONE);
    }

    private String networkDisplayState() {
        if (networkStatus == null || networkStatus.isEmpty() || "准备连接".equals(networkStatus)) {
            return net != null && net.isRunning() ? "已连接" : (networkWanted ? "连接中" : "未连接");
        }
        if (net != null && net.isRunning() && (networkStatus.contains("正在连接") || networkStatus.contains("连接中")))
            return "已连接";
        return networkStatus.replace("状态: ", "");
    }

    private void setCallRoomName(String name) {
        if (callRoomText == null) return;
        String value = name == null ? "" : name;
        final float baseSp = uiProfile != null && uiProfile.pnc370 ? 18f
                : uiProfile != null && uiProfile.pnc380 ? 17f
                : (shortScreen ? 15f : tinyScreen ? 15f : 18f);
        callRoomText.setTextSize(baseSp * fontFactor);
        callRoomText.setText(value);
        callRoomText.post(() -> {
            if (callRoomText == null) return;
            int available = callRoomText.getWidth() - callRoomText.getTotalPaddingLeft()
                    - callRoomText.getTotalPaddingRight();
            if (available <= 0 || value.isEmpty()) return;
            float width = callRoomText.getPaint().measureText(value);
            if (width > available) {
                float fitted = (baseSp * fontFactor) * available / width;
                float minimum = 8.5f * fontFactor;
                callRoomText.setTextSize(Math.max(minimum, fitted));
            }
        });
    }

    private int currentRoomOnline() {
        for (Room r : rooms) {
            if (r.id.equals(currentRoom))
                return net != null && net.isRunning() && !members.isEmpty() ? members.size() : r.online;
        }
        return members.size();
    }

    private void noteIncomingVoice() {
        lastIncomingVoiceAt = System.currentTimeMillis();
        ui.removeCallbacks(receiveWatchdog);
        ui.postDelayed(receiveWatchdog, 1500L);
    }

    private void clearReceiveWatchdog() {
        ui.removeCallbacks(receiveWatchdog);
        lastIncomingVoiceAt = 0L;
    }

    private void setNetworkUiState(String status, boolean refreshRooms) {
        networkStatus = status == null ? "" : status;
        if (networkStatus.startsWith("接收中")) {
            if (lastIncomingVoiceAt == 0L) noteIncomingVoice();
        } else {
            clearReceiveWatchdog();
        }
        if (pttHeld && activePttMode == MODE_SIGNAL_NETWORK) {
            if (deviceIndicatorMode != DeviceIndicatorBridge.TRANSMIT) {
                deviceIndicatorMode = DeviceIndicatorBridge.TRANSMIT;
                DeviceIndicatorBridge.setMode(this, DeviceIndicatorBridge.TRANSMIT);
            }
        } else {
            refreshDeviceIndicatorForAudio();
        }
        refreshCallsignCard();
        if (refreshRooms) renderRooms();
    }

    private void buildNetworkBody() {
        LinearLayout status = card();
        GradientDrawable hero = new GradientDrawable();
        hero.setColor(CARD);
        hero.setCornerRadius(dp(17));
        hero.setStroke(dp(1), p.dark() ? 0xFF314156 : 0xFFDCE5F0);
        status.setBackground(hero);
        status.setElevation(dp(3));
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setLongClickable(true);
        status.setOnLongClickListener(v -> { toggleRoomLock(currentRoom); return true; });
        callSignText = null;
        callIdentityText = null;
        bitrateText = null;

        LinearLayout callTop = new LinearLayout(this);
        callTop.setGravity(Gravity.CENTER_VERTICAL);
        callTop.setMinimumHeight(dp(uiProfile != null && uiProfile.pnc370 ? 44 : (shortScreen ? 32 : tinyScreen ? 36 : 42)));

        LinearLayout roomNameBox = new LinearLayout(this);
        callRoomBox = roomNameBox;
        roomNameBox.setGravity(Gravity.CENTER_VERTICAL);
        callRoomText = text(roomName(currentRoom), uiProfile != null && uiProfile.pnc380 ? 19 : shortScreen ? 17 : tinyScreen ? 16 : 18, TXT, true);
        callRoomText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        callRoomText.setSingleLine(true);
        callRoomText.setMaxLines(1);
        callRoomText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        roomNameBox.addView(callRoomText, new LinearLayout.LayoutParams(0, -2, 1f));
        callLockIcon = new ImageView(this);
        callLockIcon.setImageResource(R.drawable.ic_lock_small);
        callLockIcon.setColorFilter(ACCENT);
        callLockIcon.setContentDescription("房间已锁定");
        callLockIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(shortScreen ? 12 : 14), dp(shortScreen ? 12 : 14));
        lockLp.setMargins(dp(2), 0, 0, 0);
        roomNameBox.addView(callLockIcon, lockLp);
        callTop.addView(roomNameBox, new LinearLayout.LayoutParams(0, -2, 1f));

        callStateText = text("", shortScreen ? 10 : tinyScreen ? 9 : 11, SUB, true);
        callStateText.setGravity(Gravity.CENTER);
        callStateText.setSingleLine(true);
        callStateText.setVisibility(View.GONE);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-2, -2);
        stateLp.setMargins(dp(5), 0, dp(2), 0);
        callTop.addView(callStateText, stateLp);

        callPartyText = text("", uiProfile != null && uiProfile.pnc370 ? 23
                : uiProfile != null && uiProfile.pnc380 ? 29
                : (shortScreen ? 25 : tinyScreen ? 27 : 32), TXT, true);
        callPartyText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        callPartyText.setSingleLine(true);
        callPartyText.setVisibility(View.GONE);
        LinearLayout.LayoutParams partyLp = new LinearLayout.LayoutParams(-2, -2);
        partyLp.setMargins(0, 0, 0, 0);
        callTop.addView(callPartyText, partyLp);
        status.addView(callTop, new LinearLayout.LayoutParams(-1, -2));
        setCallRoomName(roomName(currentRoom));
        refreshCallsignCard();
        if (uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380) && networkDock != null) {
            networkDock.removeAllViews();
            networkDock.addView(status);
            pncOnlineStrip = text("在线用户：正在同步…", 10, SUB, false);
            pncOnlineStrip.setSingleLine(false);
            pncOnlineStrip.setMaxLines(2);
            pncOnlineStrip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pncOnlineStrip.setPadding(dp(8), dp(2), dp(8), dp(3));
            networkDock.addView(pncOnlineStrip);
        } else {
            body.addView(status);
        }

        LinearLayout roomCard = card();
        LinearLayout roomHeader = new LinearLayout(this);
        roomHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView roomTitle = text("选择房间", shortScreen ? 16 : 16, TXT, true);
        roomHeader.addView(roomTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button prevRoom = button("◀", false);
        Button nextRoom = button("▶", false);
        prevRoom.setContentDescription("上一个房间");
        nextRoom.setContentDescription("下一个房间");
        prevRoom.setTextSize(shortScreen ? 12 : 14);
        nextRoom.setTextSize(shortScreen ? 12 : 14);
        prevRoom.setOnClickListener(v -> rotateRoom(false));
        nextRoom.setOnClickListener(v -> rotateRoom(true));
        roomHeader.addView(prevRoom, new LinearLayout.LayoutParams(dp(shortScreen ? 34 : 40), dp(shortScreen ? 32 : 38)));
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(shortScreen ? 34 : 40), dp(shortScreen ? 32 : 38));
        nextLp.setMargins(dp(5), 0, 0, 0);
        roomHeader.addView(nextRoom, nextLp);
        roomCard.addView(roomHeader);
        roomBox = new LinearLayout(this);
        roomBox.setOrientation(LinearLayout.VERTICAL);
        roomCard.addView(roomBox);
        body.addView(roomCard);
        renderRooms();

        LinearLayout users = card();
        users.addView(text("在线用户", 15, TXT, true));
        userBox = new LinearLayout(this);
        userBox.setOrientation(LinearLayout.VERTICAL);
        users.addView(userBox);
        if (uiProfile != null && uiProfile.pnc370) body.addView(users, 0);
        else body.addView(users);
        renderUsers();

    }

    private void buildRadioBody() {
        radioStations.clear();
        radioStations.addAll(RadioCatalog.load(this));

        // 与原版网络收音机页面保持一致：频谱/当前电台卡片、省份、城市、电台、收藏、最近播放。
        LinearLayout spec = radioCard();
        radioWave = new RadioWaveView(this);
        spec.addView(radioWave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tinyScreen ? 60 : 78)));
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        radioNowTitle = text("未选择电台", 13, TXT, true);
        radioNowTitle.setSingleLine(true);
        radioNowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        radioNowTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        radioStatus = text("▶ 播放", 11, GREEN, true);
        radioStatus.setGravity(Gravity.CENTER);
        radioStatus.setPadding(dp(9), dp(3), dp(9), dp(3));
        radioStatus.setBackground(roundBg(withAlpha(GREEN, p.dark() ? 56 : 30), 999));
        radioStatus.setOnClickListener(v -> toggleRadioPlayback());
        radioStatus.setOnLongClickListener(v -> {
            stopRadio();
            toast("已停止播放");
            return true;
        });
        infoRow.addView(radioNowTitle);
        infoRow.addView(radioStatus);
        spec.addView(infoRow);
        body.addView(spec);
        radioNowSub = null;

        radioProvinceHeader = radioSectionHeader("选择省份", radioProvinceOpen, () -> {
            radioProvinceOpen = !radioProvinceOpen;
            radioProvinceHeader.setText((radioProvinceOpen ? "▾  " : "▸  ") + "选择省份");
            radioProvinceGrid.setVisibility(radioProvinceOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioProvinceHeader);
        radioStatus.setNextFocusDownId(radioProvinceHeader.getId());
        radioProvinceHeader.setNextFocusUpId(radioStatus.getId());
        radioProvinceGrid = new LinearLayout(this);
        radioProvinceGrid.setOrientation(LinearLayout.VERTICAL);
        radioProvinceGrid.setVisibility(radioProvinceOpen ? View.VISIBLE : View.GONE);
        body.addView(radioProvinceGrid);

        radioCityHeader = radioSectionHeader("选择城市", radioCityOpen, () -> {
            radioCityOpen = !radioCityOpen;
            radioCityHeader.setText((radioCityOpen ? "▾  " : "▸  ") + "选择城市");
            radioCityGrid.setVisibility(radioCityOpen ? View.VISIBLE : View.GONE);
        });
        body.addView(radioCityHeader);
        radioCityGrid = new LinearLayout(this);
        radioCityGrid.setOrientation(LinearLayout.VERTICAL);
        body.addView(radioCityGrid);

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

        radioLinkHeader = radioSectionHeader("短波在线收听", radioLinkOpen, () -> {
            radioLinkOpen = !radioLinkOpen;
            radioLinkHeader.setText((radioLinkOpen ? "▾  " : "▸  ") + "短波在线收听");
            radioLinkBox.setVisibility(radioLinkOpen ? View.VISIBLE : View.GONE);
            if (radioLinkOpen) {
                installKeyNavigation(radioLinkBox);
                View first = findFirstFocusable(radioLinkBox);
                if (first != null) radioLinkHeader.setNextFocusDownId(first.getId());
            }
        });
        body.addView(radioLinkHeader);
        radioLinkBox = new LinearLayout(this);
        radioLinkBox.setOrientation(LinearLayout.VERTICAL);
        radioLinkBox.setVisibility(radioLinkOpen ? View.VISIBLE : View.GONE);
        buildRadioLinks();
        body.addView(radioLinkBox);

        refreshRadioProvinceGrid();
        refreshRadioCityGrid();
        renderRadioStations();
        refreshRadioFavorites();
        refreshRadioRecent();
        refreshRadioCatalogAsync();
    }

    /** Radio keeps the original ARCH card proportions instead of the compact network cards. */
    private LinearLayout radioCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(22));
        g.setStroke(dp(1), BORDER);
        l.setBackground(g);
        l.setElevation(dp(4));
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, dp(6), dp(14));
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(shortScreen ? 6 : tinyScreen ? 8 : 14), dp(shortScreen ? 4 : tinyScreen ? 7 : 12),
                dp(shortScreen ? 6 : tinyScreen ? 8 : 14), dp(shortScreen ? 4 : tinyScreen ? 7 : 12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(16)); g.setStroke(dp(1), BORDER);
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(shortScreen ? 1 : tinyScreen ? 2 : 5), 0, dp(shortScreen ? 2 : tinyScreen ? 3 : 6));
        c.setLayoutParams(lp);
        c.setElevation(dp(2));
        return c;
    }

    private LinearLayout collapsibleSettingsCard(String title, LinearLayout content) {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(3), dp(2), dp(3));
        TextView titleView = text(title, shortScreen ? 13 : 15, TXT, true);
        TextView arrow = text("▸", shortScreen ? 13 : 15, SUB, false);
        arrow.setGravity(Gravity.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(arrow, new LinearLayout.LayoutParams(dp(24), -2));
        // All settings groups start collapsed on every model, including PNC370.
        // The focused header opens its content only when the user confirms it.
        boolean alwaysOpen = false;
        header.setClickable(!alwaysOpen);
        header.setContentDescription(title + (alwaysOpen ? "，已展开" : "，已收起"));
        makeFocusable(header);
        content.setVisibility(alwaysOpen ? View.VISIBLE : View.GONE);
        arrow.setText(alwaysOpen ? "▾" : "▸");
        if (alwaysOpen) installKeyNavigation(content);
        header.setOnClickListener(v -> {
            if (alwaysOpen) return;
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                installKeyNavigation(content);
                View first = findFirstFocusable(content);
                if (first != null) content.post(() -> {
                    first.requestFocus();
                    ensureFocusVisible(first);
                });
            }
            arrow.setText(open ? "▾" : "▸");
            header.setContentDescription(title + (open ? "，已展开" : "，已收起"));
        });
        card.addView(header);
        card.addView(content);
        return card;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        float resolvedSp = sp * fontFactor;
        // The PNC370/PNC380 panels are physically tiny; keep even secondary
        // labels above the readable threshold without enlarging phones/tablets.
        if (uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380)) resolvedSp = Math.max(12.5f, resolvedSp);
        v.setText(s); v.setTextSize(Math.max(8f, resolvedSp)); v.setTextColor(color); v.setIncludeFontPadding(false);
        if (bold && (uiProfile == null || !uiProfile.pnc370)) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTypeface(null, Typeface.NORMAL);
        float buttonSp = Math.max(9f, (tinyScreen ? 11 : 14) * fontFactor);
        if (uiProfile != null && (uiProfile.pnc370 || uiProfile.pnc380)) buttonSp = Math.max(13.5f, buttonSp);
        b.setTextSize(buttonSp); b.setTextColor(primary ? Color.WHITE : ACCENT);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD); g.setCornerRadius(dp(999)); g.setStroke(dp(1), primary ? ACCENT : BORDER);
        b.setBackground(g); b.setMinHeight(dp(tinyScreen ? 32 : 42));
        makeFocusable(b);
        return b;
    }

    private CheckBox styledCheckBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(TXT);
        box.setTextSize(Math.max(10f, 12f * fontFactor));
        box.setChecked(checked);
        box.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, SUB}));
        box.setPadding(0, dp(4), 0, dp(4));
        box.setMinHeight(dp(38));
        return box;
    }

    private void styleRadio(RadioButton rb) {
        rb.setTextColor(TXT);
        rb.setTextSize(Math.max(10f, 12f * fontFactor));
        rb.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, SUB}));
        rb.setPadding(0, dp(3), dp(4), dp(3));
        rb.setMinHeight(dp(36));
    }

    private TextView settingLink(String label, Runnable action) {
        TextView v = text(label, tinyScreen ? 10 : 11, ACCENT, false);
        v.setPadding(0, dp(5), 0, dp(5));
        v.setPaintFlags(v.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        v.setClickable(true);
        makeFocusable(v);
        v.setOnClickListener(view -> action.run());
        return v;
    }

    private GradientDrawable buttonBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? ACCENT : CARD); g.setCornerRadius(dp(999)); g.setStroke(dp(1), selected ? ACCENT : BORDER);
        return g;
    }

    private EditText input(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value == null ? "" : value); e.setSingleLine(true);
        e.setTextSize(Math.max(11f, 13 * fontFactor)); e.setTextColor(TXT); e.setHintTextColor(SUB); e.setInputType(type);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.dark() ? 0xFF121B27 : 0xFFF5F8FC);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), p.dark() ? 0xFF263445 : 0xFFE2E9F2);
        e.setBackground(bg);
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setMinHeight(dp(42));
        makeFocusable(e);
        return e;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private boolean identityReady() {
        return !p.callsign().isEmpty() && p.dmrId() > 0 && p.password().length() >= 6;
    }

    private void requestNeededPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 23) {
            // Android 12+ ignores a FINE-only request. Always include COARSE
            // in the same request whenever FINE still needs to be granted.
            boolean needFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED;
            if (needFine || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!need.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
                    need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (needFine) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && hardwareProfile != null && hardwareProfile.isPncSmall()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 23 && !need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_PERMS) {
            if (hardwareProfile != null && hardwareProfile.isPncSmall()) installPncLauncherAsync();
            if (Build.VERSION.SDK_INT >= 23) {
                boolean locationRequested = false;
                if (perms != null) for (String perm : perms) {
                    if (Manifest.permission.ACCESS_FINE_LOCATION.equals(perm)
                            || Manifest.permission.ACCESS_COARSE_LOCATION.equals(perm)) {
                        locationRequested = true;
                        break;
                    }
                }
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (locationRequested && (fine || coarse)) {
                    if (pendingAprsEnable && !p.aprsEnabled()) {
                        p.set("aprsEnabled", "1");
                        restartAprs();
                        requestBackgroundLocationForAprs();
                        toast("定位权限已授予，APRS 已开启");
                    } else if (p.aprsEnabled()) {
                        restartAprs();
                        requestBackgroundLocationForAprs();
                    }
                    pendingAprsEnable = false;
                } else if (locationRequested) {
                    pendingAprsEnable = false;
                    toast("未授予定位权限，APRS 无法上传位置");
                }
            }
        } else if (code == REQ_BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= 29) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "已允许后台定位，APRS 可在息屏后继续上传"
                    : "未允许后台定位，APRS 仅在 APP 前台持续更新位置");
            if (p.aprsEnabled()) restartAprs();
        }
    }

    private void requestBackgroundLocationForAprs() {
        if (Build.VERSION.SDK_INT < 29 || !p.aprsEnabled()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) return;
        new AlertDialog.Builder(this)
                .setTitle("APRS 后台定位")
                .setMessage("为了在息屏或切到后台后继续向 APRS.TV 上传位置，需要把定位权限设为“始终允许”。是否现在设置？")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                REQ_BACKGROUND_LOCATION);
                    } catch (Throwable t) {
                        toast("请在系统设置中把 ARCH 定位权限设为始终允许");
                    }
                })
                .setNegativeButton("仅前台定位", null)
                .show();
    }

    private void resetStaleNetworkReceiveState() {
        networkReceiving = false;
        clearModeSignal(MODE_SIGNAL_NETWORK);
        lastIncomingVoiceAt = 0L;
        clearReceiveWatchdog();
        refreshDeviceIndicatorForAudio();
        if (net != null && net.isRunning()) setNetworkUiState("已连接", false);
    }
    /**
     * PNC380 exposes its main "speaker-mic" as the Telephony RX input endpoint.
     * Pinning Built-In Mic id=5 is wrong on this HAL: it resolves to dmic-endfire.
     * This mapping is hardware-specific and must never be reused for PNC370/P50.
     */
    @android.annotation.SuppressLint({"NewApi", "WrongConstant"})
    private AudioDeviceInfo findPnc380MainMic() {
        if (pnc380MainMicDevice != null) return pnc380MainMicDevice;
        AudioDeviceInfo found = scanPnc380MainMic();
        if (found == null && audioManager != null) {
            try {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                boolean speaker = !"earpiece".equals(p.audioRoute());
                audioManager.setSpeakerphoneOn(speaker);
            } catch (Throwable ignored) {}
            found = scanPnc380MainMic();
        }
        if (found != null) {
            pnc380MainMicDevice = found;
            return found;
        }
        android.util.Log.e("ARCH-AUDIO", "PNC380 main speaker-mic endpoint unavailable");
        return null;
    }

    @android.annotation.SuppressLint({"NewApi", "WrongConstant"})
    private AudioDeviceInfo scanPnc380MainMic() {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            String product = String.valueOf(device.getProductName()).toLowerCase(Locale.US);
            // TYPE_TELEPHONY_RX is hidden on this old SDK; its public enum value is 13.
            if (device.getType() == 13
                    || product.contains("in call")
                    || product.contains("voice-call")
                    || product.contains("voice call")) {
                return device;
            }
        }
        return null;
    }

    @android.annotation.SuppressLint({"NewApi", "WrongConstant"})
    private void configureDmrMicDevice(AudioEngine target) {
        if (target == null || hardwareProfile == null || !hardwareProfile.isPnc380()) return;
        AudioDeviceInfo device = findPnc380MainMic();
        if (device != null) target.setPreferredInputDevice(device);
        android.util.Log.i("ARCH-AUDIO", "radio mic lock model=PNC380 endpoint="
                + (device == null ? "null" : device.getId() + "/" + device.getType() + "/" + device.getProductName()));
    }

    @android.annotation.SuppressLint({"NewApi", "WrongConstant"})
    private void configureNetworkMicDevice(HqAudio target) {
        if (target == null || audioManager == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            // FIX55/FIX60 hardware verification: PNC380 network PTT must leave
            // the input device to the vendor HAL. With VOICE_COMMUNICATION at
            // 48 kHz, the HAL selects `speaker-mic` (main microphone).
            // Pinning AudioDeviceInfo id=5 selects the wrong route, and MIC or
            // BACK_MIC select handset-mic or dmic-endfire respectively.
            if (hardwareProfile != null && hardwareProfile.isPnc380()) {
                AudioDeviceInfo device = findPnc380MainMic();
                target.setPreferredInputDevice(device);
                android.util.Log.i("ARCH-AUDIO", "network mic route=PNC380_MAIN endpoint="
                        + (device == null ? "HAL_DEFAULT" : device.getId() + "/" + device.getType() + "/" + device.getProductName()));
                return;
            }
            AudioDeviceInfo fallback = null;
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                String product = String.valueOf(device.getProductName()).toLowerCase(Locale.US);
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC
                        && product.contains("back")) {
                    target.setPreferredInputDevice(device);
                    android.util.Log.i("ARCH-AUDIO", "network preferred mic=BACK id=" + device.getId()
                            + " product=" + device.getProductName());
                    return;
                }
                if (fallback == null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) fallback = device;
            }
            if (fallback != null) {
                target.setPreferredInputDevice(fallback);
                android.util.Log.i("ARCH-AUDIO", "network preferred mic=BUILTIN id=" + fallback.getId()
                        + " product=" + fallback.getProductName());
            }
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "network preferred mic selection failed", t);
        }
    }
    private void applyAudioPrefs() {
        if (audio != null) {
            audio.setRxGain(networkRxGain());
            audio.setTxGain(dbToGain(p.txGainDb()));
            audio.setAgc(p.agc(), p.agcDb());
        }
        if (dmrAudio != null) {
            dmrAudio.setRxGainDb(p.rxGainDb());
            dmrAudio.setTxGainDb(p.txGainDb());
            dmrAudio.setAgc(p.agc());
            dmrAudio.setDenoise(false);
        }
        if (bmAudio != null) {
            bmAudio.setRxGainDb(p.rxGainDb());
            bmAudio.setTxGainDb(p.txGainDb());
            bmAudio.setAgc(p.agc());
            bmAudio.setDenoise(false);
        }
        NetworkKeepAliveService.refreshAudioPreferences(this);
    }


    private int preferredAudioStreamType() {
        String route = p.audioRoute();
        return ("earpiece".equals(route) || "bluetooth".equals(route))
                ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
    }

    private int networkAudioStreamType() {
        return NetworkAudioProfile.streamType(hardwareProfile, p.audioRoute());
    }

    private String formatSignedDb(int db) { return (db > 0 ? "+" : "") + db + " dB"; }
    private float dbToGain(int db) { return (float)Math.pow(10.0, db / 20.0); }

    private float networkRxGain() {
        float gain = dbToGain(p.rxGainDb());
        if (hardwareProfile != null && hardwareProfile.isP50Pro()) {
            gain *= NetworkAudioProfile.rxTrim(hardwareProfile);
        }
        return gain;
    }
    private void applyAudioRoute(boolean restartPlayback) {
        if (hardwareProfile != null && hardwareProfile.isPnc380()) {
            // Apply the vendor route on the same serialized thread as microphone start.
            pncAudioPrepared = false;
            int stream = preferredAudioStreamType();
            try {
                routeIo.execute(() -> {
                    if (audioManager != null) NetworkAudioProfile.clearTerminalParameters(audioManager, hardwareProfile);
                    if (dmrAudio != null) dmrAudio.setStreamType(stream);
                    if (bmAudio != null) bmAudio.setStreamType(stream);
                    if (audio != null) audio.setStreamType(networkAudioStreamType());
                    // PNC380 must stay fully closed while idle; opening a warm AudioTrack causes HAL white noise.
                });
            } catch (Throwable ignored) {}
            return;
        }
        try {
            routeIo.execute(() -> applyAudioRouteNow(restartPlayback));
        } catch (Throwable ignored) {}
    }

    private void applyAudioRouteNow(boolean restartPlayback) {
        try {
            if (audioManager == null) return;
            String route = p.audioRoute();
            boolean ear = "earpiece".equals(route);
            boolean bt = "bluetooth".equals(route);
            boolean networkPnc370 = hardwareProfile != null && hardwareProfile.isPnc370()
                    && netPage && p.showNetwork();
            if (bt) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(false);
                try { audioManager.startBluetoothSco(); audioManager.setBluetoothScoOn(true); } catch (Throwable ignored) {}
            } else if (networkPnc370) {
                try { audioManager.stopBluetoothSco(); audioManager.setBluetoothScoOn(false); } catch (Throwable ignored) {}
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
            } else if (hardwareProfile != null && hardwareProfile.isP50Pro()
                    && netPage && p.showNetwork()) {
                // HyTalk P50 uses MODE_IN_COMMUNICATION plus the vendor
                // half-duplex POC parameter for the main speaker/intercom mic.
                NetworkAudioProfile.prepare(audioManager, hardwareProfile, route);
            } else {
                try { audioManager.stopBluetoothSco(); audioManager.setBluetoothScoOn(false); } catch (Throwable ignored) {}
                audioManager.setMode(ear ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(!ear);
            }
            int routeStream = preferredAudioStreamType();
            int networkStream = networkAudioStreamType();
            if (audio != null) {
                audio.setStreamType(networkStream);
                if (restartPlayback) audio.stopPlayback();
                if (netPage && p.showNetwork()) audio.primeRealtime();
            }
            if (dmrAudio != null) dmrAudio.setStreamType(routeStream);
            if (bmAudio != null) bmAudio.setStreamType(routeStream);
            ui.post(this::syncVolumeControlStream);
        } catch (Throwable ignored) {}
    }

    private synchronized void initPttToneAsync() {
        if (pttTone != null || pttToneInitializing) return;
        pttToneInitializing = true;
        try {
            toneIo.execute(() -> {
                ToneGenerator created = null;
                try { created = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (Throwable ignored) {}
                if (created != null && (isFinishing() || isDestroyed())) {
                    try { created.release(); } catch (Throwable ignored) {}
                } else {
                    pttTone = created;
                }
                pttToneInitializing = false;
            });
        } catch (Throwable t) {
            pttToneInitializing = false;
        }
    }

    private void pttBeep(boolean down) {
        // PNC380 opens a 48 kHz ToneGenerator output stream even for a short tone;
        // the vendor HAL keeps voice-speaker alive for its 3 s standby delay.
        if (hardwareProfile != null && hardwareProfile.isPnc380()) return;
        if (!p.pttTone()) return;
        final ToneGenerator tone = pttTone;
        if (tone == null) { initPttToneAsync(); return; }
        try {
            toneIo.execute(() -> {
                try { tone.startTone(down ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 70); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void pttDeniedTone() {
        final ToneGenerator tone = pttTone;
        if (tone == null) return;
        try {
            toneIo.execute(() -> {
                try { tone.startTone(ToneGenerator.TONE_PROP_NACK, 220); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag")
    private void registerOverlayPttReceiver() {
        if (overlayPttReceiver != null) return;
        overlayPttReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                String action = intent == null ? "" : intent.getAction();
                if (ArchOverlayPttService.ACTION_DOWN.equals(action)) pttDown();
                else if (ArchOverlayPttService.ACTION_UP.equals(action)) pttUp();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ArchOverlayPttService.ACTION_DOWN);
        f.addAction(ArchOverlayPttService.ACTION_UP);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(overlayPttReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(overlayPttReceiver, f, "cn.arch.dmr.permission.INTERNAL_PTT", null);
            }
        } catch (Throwable ignored) {}
    }

    private void connectRoom(String room, boolean userInitiated) {
        if (userInitiated && !lockedRoom.isEmpty() && !lockedRoom.equals(room)) {
            toast("房间已锁定，长按锁定房间可解锁");
            return;
        }
        if (!identityReady()) {
            if (userInitiated) toast("请先在设置中填写并注册身份");
            return;
        }
        if (room == null || room.trim().isEmpty()) room = "room5";
        currentRoom = room; roomSelection = room; p.setNetRoom(room);
        networkWanted = true;
        setNetworkUiState("连接中", true);
        if (radio != null && radio.isPlaying()) radio.pause();
        if (supportsPdc760Bridge()) try { HyteraPdc760Bridge.setDisplay(this, roomName(room), p.callsign(), String.valueOf(p.dmrId())); } catch (Throwable ignored) {}
        try { HyteraPnc370Bridge.setDisplay(this, roomName(room), String.valueOf(p.dmrId())); } catch (Throwable ignored) {}
        // Player starts lazily on the first received voice packet.
        NetworkRuntime.get(this).connect(room);
    }

    private void applyPttButtonStyle(boolean transmitting) {
        if (pttButton == null) return;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                transmitting
                        ? new int[]{RED, p.dark() ? 0xFF991B1B : 0xFFB91C1C}
                        : new int[]{ACCENT, withAlpha(ACCENT, 210)});
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), withAlpha(Color.WHITE, 70));
        pttButton.setBackground(bg);
        pttButton.setElevation(dp(7));
    }

    private void refreshPttUi() {
        if (pttButton == null) return;
        if (digitalPage) {
            boolean active = dmrTxing || pttHeld;
            applyPttButtonStyle(active);
            pttButton.setText(active ? "发射中" : "按住发射");
            return;
        }
        if (pttGranted) {
            applyPttButtonStyle(true);
            pttButton.setText("发射中");
            setNetworkUiState("发射中 · " + p.callsign(), false);
        } else if (pttHeld) {
            applyPttButtonStyle(true);
            pttButton.setText("发射中");
            setNetworkUiState("发射中 · " + p.callsign(), false);
        } else {
            applyPttButtonStyle(false);
            pttButton.setText("按住发射");
        }
        android.util.Log.i("ARCH-PTT", "ui held=" + pttHeld + " granted=" + pttGranted);
    }

    private void schedulePttTot() {
        stopPttTot();
        final int seconds = p == null ? 180 : p.pttTimeout();
        pttTotTask = () -> {
            pttTotTask = null;
            if (!pttHeld) return;
            toast("发射超时，已自动松开 PTT");
            pttUp();
        };
        ui.postDelayed(pttTotTask, seconds * 1000L);
    }

    private void stopPttTot() {
        if (pttTotTask != null) {
            ui.removeCallbacks(pttTotTask);
            pttTotTask = null;
        }
    }

    private void ensurePttHealthWatchdog() {
        if (pttHealthWatchdog != null) return;
        pttHealthWatchdog = new Runnable() {
            @Override public void run() {
                if (!pttHeld) { pttHealthWatchdog = null; return; }
                if (net == null || !net.isRunning()) {
                    toast("网络已断开，发射结束");
                    pttUp();
                    pttHealthWatchdog = null;
                    return;
                }
                if (!micRestarting && !micHealthCheckPending && audio != null) {
                    micHealthCheckPending = true;
                    final HqAudio checkedAudio = audio;
                    try {
                        runMicIo(() -> {
                            boolean healthy = false;
                            try { healthy = checkedAudio.isMicHealthy(); } catch (Throwable ignored) {}
                            if (healthy) {
                                ui.post(() -> micHealthCheckPending = false);
                                return;
                            }
                            boolean rebuilt = false;
                            try { rebuilt = checkedAudio.restartMic(); } catch (Throwable ignored) {}
                            final boolean ok = rebuilt;
                            ui.post(() -> {
                                micHealthCheckPending = false;
                                micRestarting = false;
                                if (ok) {
                                    micOn = true;
                                    setNetworkUiState("发射中 · 麦克风已恢复", false);
                                } else if (pttHeld) {
                                    micOn = false;
                                    startMic();
                                    setNetworkUiState("发射中 · 麦克风重试中", false);
                                }
                            });
                        });
                    } catch (Throwable ignored) {
                        micHealthCheckPending = false;
                    }
                }
                ui.postDelayed(this, 1000L);
            }
        };
        ui.postDelayed(pttHealthWatchdog, 1000L);
    }

    private void stopPttHealthWatchdog() {
        if (pttHealthWatchdog != null) {
            ui.removeCallbacks(pttHealthWatchdog);
            pttHealthWatchdog = null;
        }
        micRestarting = false;
    }

    private void pttDown() {
        if (supportsPdc760Bridge()) try { HyteraPdc760Bridge.noteActivityPttDown(this); } catch (Throwable ignored) {}
        if (bmPage) { bmPttDown(); return; }
        if (digitalPage) { dmrPttDown(); return; }
        if (pttHeld) return;
        clearPttPrebuffer();
        if (!identityReady() || net == null || !net.isRunning()) { toast("请先连接网络频道"); return; }
        if (networkStatus != null && networkStatus.startsWith("接收中")) {
            pttDeniedTone();
            toast("对方正在发射，禁止发射");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions(); toast("需要麦克风权限"); return;
        }
        NetworkKeepAliveService.ensureMicrophoneForeground(this);
        clearReceiveWatchdog();
        pttHeld = true;
        pttGranted = false;
        setPnc380SpeakerTx(true);
        activePttMode = MODE_SIGNAL_NETWORK;
        pttGeneration++;
        flushAllReceiveAudioForPtt();
        localPttVisualActive = true;
        showTransmitIndicator();
        pttRequestAt = System.currentTimeMillis();
        pttFirstFrameAt = 0L;
        android.util.Log.i("ARCH-PTT", "down");
        pttToggleActive = true;
        radioWasPlayingForPtt = radio != null && radio.isPlaying();
        if (radioWasPlayingForPtt) radio.pause();
        refreshPttUi();
        net.requestPtt(true);
        startMic();
        schedulePttTot();
        ensurePttHealthWatchdog();
        pttBeep(true);
    }

    private void pttUp() {
        if (supportsPdc760Bridge()) try { HyteraPdc760Bridge.noteActivityPttUp(this); } catch (Throwable ignored) {}
        if (activePttMode == MODE_SIGNAL_DMR) { bmPttUp(); return; }
        if (activePttMode == MODE_SIGNAL_DIGITAL) { dmrPttUp(); return; }
        pttGeneration++;
        if (!pttHeld) return;
        pttHeld = false; pttGranted = false; activePttMode = -1; localPttVisualActive = false; pttToggleActive = false;
        refreshDeviceIndicatorForAudio();
        stopPttTot();
        stopPttHealthWatchdog();
        // Release the server grant before touching any vendor audio API. On PNC380,
        // ToneGenerator can block a Binder call for many seconds and must never delay PTT-up.
        net.requestPtt(false);
        clearPttPrebuffer();
        stopMic();
        flushReceivePlayback();
        setPnc380SpeakerTx(false);
        refreshPttUi();
        setNetworkUiState(net != null && net.isRunning() ? "已连接" : "未连接", false);
        pttBeep(false);
    }

    private void releaseWarmNetworkMic(String targetMode) {
        HqAudio current = audio;
        if (current == null) return;
        try {
            current.releaseMicForModeSwitch();
            android.util.Log.i("ARCH-AUDIO", "network mic released for " + targetMode);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "network mic release failed for " + targetMode + ": " + t);
        }
    }

    private void runMicIo(Runnable task) {
        if (task == null) return;
        try {
            if (hardwareProfile != null && hardwareProfile.isPnc380()) routeIo.execute(task);
            else runAudioIo(task);
        } catch (Throwable ignored) {}
    }

    /** PNC380 half-duplex TX: mute local playback without changing the main microphone route. */
    private void setPnc380SpeakerTx(boolean transmitting) {
        if (hardwareProfile == null || !hardwareProfile.isPnc380()) return;
        try {

            if (audio != null) audio.setTxMuted(transmitting);
            if (dmrAudio != null) dmrAudio.muteSpk(transmitting);
            if (bmAudio != null) bmAudio.muteSpk(transmitting);
            // Deliberately do not call setSpeakerphoneOn(false) here. On PNC380
            // that switches the input route from speaker-mic (main microphone)
            // to the handset microphone and makes transmit audio much quieter.
            // The AudioTrack/engine mute above is enough to stop local speaker
            // playback while preserving the main microphone route.
            android.util.Log.i("ARCH-AUDIO", "PNC380 TX speaker tx=" + transmitting
                    + " route=" + (p == null ? "" : p.audioRoute()));
        } catch (Throwable t) {
            android.util.Log.w("ARCH-AUDIO", "PNC380 TX speaker route failed", t);
        }
    }

    private void startMic() {
        if (!pttHeld) return;
        micOn = true;
        if (hardwareProfile != null && hardwareProfile.isPnc380()) {
            runMicIo(() -> {
                // Every PNC380 PTT uses the same serialized audio-route executor.
                // Fully release playback, then configure route, then open the mic.
                // This removes the old audioIo/routeIo race that could start the
                // microphone before the speaker HAL had closed.
                if (audio != null) {
                    audio.setTxMuted(true);
                    audio.stopPlaybackForPnc380();
                }
                boolean routed = pncAudioPrepared;
                if (!routed && audioManager != null) {
                    try {
                        String route = p.audioRoute();
                        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        if ("bluetooth".equals(route)) {
                            audioManager.setSpeakerphoneOn(false);
                            try { audioManager.startBluetoothSco(); audioManager.setBluetoothScoOn(true); } catch (Throwable ignored) {}
                        } else {
                            // Keep the configured speaker/earpiece route. PNC380's
                            // vendor HAL selects its main speaker-mic from this route;
                            // AudioTrack muting handles TX silence without changing it.
                            audioManager.setSpeakerphoneOn(!"earpiece".equals(route));
                        }
                        int routeStream = preferredAudioStreamType();
                        if (audio != null) audio.setStreamType(routeStream);
                        if (dmrAudio != null) dmrAudio.setStreamType(routeStream);
                        if (bmAudio != null) bmAudio.setStreamType(routeStream);
                        ui.post(this::syncVolumeControlStream);
                        routed = true;
                    } catch (Throwable ignored) {}
                }
                boolean ok = startMicNow();
                if (routed && ok) pncAudioPrepared = true;
            });
        } else {
            runMicIo(this::startMicNow);
        }
    }

    private boolean startMicNow() {
        if (!pttHeld || audio == null) return false;
        final int micGeneration = pttGeneration;
        boolean ok = audio.startMic(frame -> {
            if (!pttHeld || micGeneration != pttGeneration || activePttMode != MODE_SIGNAL_NETWORK) return;
            enqueuePttFrame(frame.clone(), micGeneration);
        });
        ui.post(() -> {
            android.util.Log.i("ARCH-PTT", "mic start ok=" + ok);
            if (!ok) {
                micOn = false;
                refreshPttUi();
                toast("麦克风启动失败，正在自动重试");
                setNetworkUiState("发射中 · 麦克风重试中", false);
            } else {
                micOn = true;
                refreshPttUi();
            }
        });
        return ok;
    }

    private void sendPttPcm(short[] pcm) {
        if (pcm == null || opus == null || net == null) return;
        byte[] encoded = opus.encode(pcm);
        if (encoded != null) {
            net.sendVoice(encoded);
            if (pttFirstFrameAt == 0L) {
                pttFirstFrameAt = System.currentTimeMillis();
                android.util.Log.i("ARCH-PTT", "first frame after " + Math.max(0L, pttFirstFrameAt - pttRequestAt) + "ms bytes=" + encoded.length);
            }
        } else if (pttFirstFrameAt == 0L) {
            android.util.Log.w("ARCH-PTT", "Opus encode returned null samples=" + pcm.length);
        }
    }

    private void enqueuePttFrame(short[] pcm, int generation) {
        if (pcm == null || pcm.length == 0 || generation != pttGeneration
                || !pttHeld || activePttMode != MODE_SIGNAL_NETWORK) return;
        synchronized (pttSendLock) {
            boolean serverGranted = pttGranted || (net != null && net.isTxGranted());
            if (!serverGranted) {
                if (pttPrebuffer.size() >= PTT_PREBUFFER_FRAMES) pttPrebuffer.removeFirst();
                pttPrebuffer.addLast(pcm);
                return;
            }
            if (!pttGranted) {
                // The UI listener is detached while the screen is off. Promote the
                // real server grant here so captured microphone frames are sent.
                pttGranted = true;
                while (!pttPrebuffer.isEmpty()) {
                    if (pttSendQueue.size() >= PTT_SEND_QUEUE_FRAMES) pttSendQueue.removeFirst();
                    pttSendQueue.addLast(pttPrebuffer.removeFirst());
                }
                ui.post(this::refreshPttUi);
            }
            if (!pttSenderRun) startPttSenderLocked();
            if (pttSendQueue.size() >= PTT_SEND_QUEUE_FRAMES) {
                pttSendQueue.removeFirst();
                android.util.Log.w("ARCH-PTT", "send queue full, dropped oldest frame");
            }
            pttSendQueue.addLast(pcm);
            pttSendLock.notifyAll();
        }
    }

    private void startPttSenderLocked() {
        if (pttSenderRun) return;
        pttSenderRun = true;
        final int generation = ++pttSenderGeneration;
        Thread t = new Thread(() -> runPttSender(generation), "arch-ptt-send");
        pttSenderThread = t;
        t.start();
    }

    private void runPttSender(int generation) {
        long nextNs = System.nanoTime();
        while (pttSenderRun && generation == pttSenderGeneration) {
            synchronized (pttSendLock) {
                while (pttSenderRun && generation == pttSenderGeneration && pttSendQueue.isEmpty()) {
                    try { pttSendLock.wait(20L); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!pttSenderRun || generation != pttSenderGeneration) return;
            }
            long waitNs = nextNs - System.nanoTime();
            if (waitNs > 0L) {
                try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            short[] pcm = null;
            synchronized (pttSendLock) {
                if (!pttSenderRun || generation != pttSenderGeneration) return;
                pcm = pttSendQueue.pollFirst();
            }
            if (pcm != null) sendPttPcm(pcm);
            nextNs += PTT_FRAME_NS;
            if (System.nanoTime() - nextNs > 100_000_000L) nextNs = System.nanoTime();
        }
    }

    private void flushPttPrebuffer() {
        synchronized (pttSendLock) {
            int frames = pttPrebuffer.size();
            while (!pttPrebuffer.isEmpty()) {
                if (pttSendQueue.size() >= PTT_SEND_QUEUE_FRAMES) pttSendQueue.removeFirst();
                pttSendQueue.addLast(pttPrebuffer.removeFirst());
            }
            if (!pttSenderRun) startPttSenderLocked();
            pttSendLock.notifyAll();
            android.util.Log.i("ARCH-PTT", "flush prebuffer frames=" + frames
                    + " paced=true cache=" + pttSendQueue.size());
        }
    }

    private void clearPttPrebuffer() {
        Thread t;
        synchronized (pttSendLock) {
            pttPrebuffer.clear();
            pttSendQueue.clear();
            pttSenderRun = false;
            pttSenderGeneration++;
            t = pttSenderThread;
            pttSenderThread = null;
            pttSendLock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    private void stopMic() {
        micOn = false;
        runMicIo(() -> audio.startMic(null));
    }

    /** Ends the current received talkspurt; the room-lifetime player stays ready for the next one. */
    private void flushReceivePlayback() {
        if (net != null) {
            try { net.clearReceiveAudio(); } catch (Throwable ignored) {}
        }
        synchronized (receivePlaybackLock) {
            boolean dirty = receivePlaybackDirty;
            // PNC380 PTT must invalidate even an onVoice() call that passed its
            // pre-lock check just before pttHeld became true.
            if (dirty || (hardwareProfile != null && hardwareProfile.isPnc380())) {
                receivePlaybackGeneration++;
                receivePlaybackDirty = false;
                lastPlaybackSpeaker = "";
            }
            if (audio != null) {
                if (hardwareProfile != null && hardwareProfile.isPnc380()) {
                    // Fully close the PNC380 output stream at every talkspurt end.
                    // Merely clearing Java queues leaves vendor HAL noise running.
                    try { routeIo.execute(audio::stopPlaybackForPnc380); } catch (Throwable ignored) {}
                } else if (dirty) {
                    // Other models keep the warm room-lifetime player.
                    runAudioIo(audio::clearPlaybackQueue);
                }
            }
        }
    }

    @Override public void onState(int st, String message) {
        if (isFinishing() || isDestroyed()) return;
        ui.post(() -> {
            if (st == 1) {
                DeviceIndicatorBridge.noteP50PocConnected(MainActivity.this);
                runAudioIo(() -> { if (audio != null) { if ((hardwareProfile == null || !hardwareProfile.isPnc380()) && netPage && p.showNetwork()) audio.primeRealtime(); audio.setExternalPacing(true); } });
                networkWanted = true;
                setNetworkUiState("已连接", true);
                maybeStartPendingPtt();
                if (rooms.isEmpty()) loadRooms();
            } else if (st == 0) {
                DeviceIndicatorBridge.noteP50PocDisconnected(MainActivity.this);
                runAudioIo(() -> { if (audio != null) audio.setExternalPacing(false); });
                if (!networkWanted) setNetworkUiState("未连接", true);
            } else {
                DeviceIndicatorBridge.noteP50PocDisconnected(MainActivity.this);
                networkReceiving = false;
                try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
                runAudioIo(() -> { if (audio != null) audio.setExternalPacing(false); });
                clearModeSignal(MODE_SIGNAL_NETWORK);
                flushReceivePlayback();
                refreshDeviceIndicatorForAudio();
                setNetworkUiState(message == null ? "连接异常" : message, true);

            }
        });
    }
    @Override public void onLog(String message) {}
    @Override public void onUdpReady(boolean ready, String message) {
        if (isFinishing() || isDestroyed()) return;
        runAudioIo(() -> { if (audio != null) audio.setExternalPacing(ready); });
    }
    @Override public void onVoice(int dmrId, String callsign, short[] pcm) {
        if (isFinishing() || isDestroyed() || pcm == null) return;
        String speakerKey = (callsign == null || callsign.trim().isEmpty()) ? String.valueOf(dmrId) : callsign.trim();
        boolean suppressReleasedTail = networkUiReleased && !networkReceiving
                && speakerKey.equals(networkReleasedPeer);
        if (!suppressReleasedTail && !networkReleasedPeer.isEmpty() && !speakerKey.equals(networkReleasedPeer)) {
            networkUiReleased = false;
            networkReleasedPeer = "";
        }
        boolean startedReceiving = !networkReceiving && !suppressReleasedTail;
        if (!suppressReleasedTail) networkReceiving = true;
        markModeSignal(MODE_SIGNAL_NETWORK);
        if (startedReceiving) ui.post(() -> {
            try { if (radio != null && radio.isPlaying()) radio.pause(); } catch (Throwable ignored) {}
        });
        long voiceUiNow = android.os.SystemClock.elapsedRealtime();
        boolean updateVoiceUi = !suppressReleasedTail && voiceUiNow - lastNetworkVoiceUiAt >= 250L;
        if (updateVoiceUi) {
            lastNetworkVoiceUiAt = voiceUiNow;
            ui.post(this::refreshDeviceIndicatorForAudio);
        }
        if (!suppressReleasedTail) {
            // Refresh the native receive state only for the active callsign UI.
            try { HyteraPdc760Bridge.noteNetworkReceive(this, !pttHeld, speakerKey); } catch (Throwable ignored) {}
        }
        final boolean playable = shouldPlayMode(MODE_SIGNAL_NETWORK) && !pttHeld;
        if (playable) {
            final int voiceGeneration = receivePlaybackGeneration;
            synchronized (receivePlaybackLock) {
                if (!speakerKey.equals(lastPlaybackSpeaker)) lastPlaybackSpeaker = speakerKey;
                final short[] frame = pcm;
                if (voiceGeneration == receivePlaybackGeneration && audio != null
                        && !pttHeld && shouldPlayMode(MODE_SIGNAL_NETWORK)) {
                    audio.playRealtime(frame);
                    receivePlaybackDirty = true;
                }
            }
        }
        if (updateVoiceUi) {
            ui.post(() -> {
                noteIncomingVoice();
                setNetworkUiState("接收中 · " + (callsign == null ? String.valueOf(dmrId) : callsign), false);
            });
        }
    }

    private void endRemoteNetworkReceiveUiNow(String callsign) {
        networkUiReleased = true;
        networkReleasedPeer = callsign == null ? "" : callsign.trim();
        networkReceiving = false;
        lastIncomingVoiceAt = 0L;
        lastPlaybackSpeaker = "";
        clearReceiveWatchdog();
        try { HyteraPdc760Bridge.noteNetworkReceive(this, false, ""); } catch (Throwable ignored) {}
        clearModeSignal(MODE_SIGNAL_NETWORK);
        ui.post(() -> {
            refreshDeviceIndicatorForAudio();
            if (networkWanted) setNetworkUiState("已连接", false);
        });
        // Do not flushReceivePlayback() or increment receivePlaybackGeneration.
        // Already queued frames still play, but late frames cannot re-show the callsign.
    }

    @Override public void onVoiceEnded() {
        if (isFinishing() || isDestroyed()) return;
        networkReceiving = false;
        try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
        clearModeSignal(MODE_SIGNAL_NETWORK);
        flushReceivePlayback();
        ui.post(() -> { refreshDeviceIndicatorForAudio(); setNetworkUiState("已连接", false); });
    }

    @Override public void onMembers(List<NetworkRoomClient.Member> list) {
        if (isFinishing() || isDestroyed()) return;
        members = list == null ? new ArrayList<>() : new ArrayList<>(list);
        NetworkRoomClient.Member remoteTx = null;
        if (members != null) for (NetworkRoomClient.Member m : members) {
            if (m != null && m.tx && m.dmrId != p.dmrId()) { remoteTx = m; break; }
        }
        final boolean hasTx = remoteTx != null;
        final String txCall = hasTx && remoteTx.callsign != null ? remoteTx.callsign.trim() : "";
        final int txId = hasTx ? remoteTx.dmrId : 0;
        ui.post(() -> {
            renderUsers();
            refreshCallsignCard();
            renderRooms();
            if (hasTx && !networkUiReleased) {
                String who = txCall.isEmpty() ? String.valueOf(txId) : txCall;
                // Callsign follows the same 0.3s presence event as the green lamp.
                networkReceiving = true;
                noteIncomingVoice();
                setNetworkUiState("接收中 · " + who, false);
                refreshDeviceIndicatorForAudio();
            } else if (!hasTx) {
                if (networkUiReleased || receivePlaybackDirty) endRemoteNetworkReceiveUiNow(networkReleasedPeer);
                else {
                    networkReceiving = false;
                    try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
                    clearModeSignal(MODE_SIGNAL_NETWORK);
                    flushReceivePlayback();
                    refreshDeviceIndicatorForAudio();
                    lastIncomingVoiceAt = 0L;
                    clearReceiveWatchdog();
                    setNetworkUiState("已连接", false);
                }
            }
        });
    }

    @Override public void onBitrate(int bps) {
        if (opus != null) opus.setBitrate(bps);
    }
    @Override public void onPtt(int state, String callsign, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (state == 0) flushReceivePlayback();
        ui.post(() -> {
            if (state == 1) {
                if (!pttHeld || activePttMode != MODE_SIGNAL_NETWORK) {
                    if (net != null) net.requestPtt(false);
                    if (activePttMode != MODE_SIGNAL_NETWORK) return;
                    pttGranted = false;
                    localPttVisualActive = false;
                    stopPttTot();
                    stopPttHealthWatchdog();
                    stopMic();
                    refreshPttUi();
                    setNetworkUiState("已连接", false);
                    return;
                }
                pttGranted = true;
                android.util.Log.i("ARCH-PTT", "grant after " + Math.max(0L, System.currentTimeMillis() - pttRequestAt) + "ms");
                refreshPttUi();
                startMic();
                flushPttPrebuffer();
            } else if (state == 2) {
                if (activePttMode == MODE_SIGNAL_NETWORK && pttHeld) {
                    pttGeneration++;
                    pttGranted = false; pttHeld = false; activePttMode = -1; localPttVisualActive = false;
                    stopPttTot(); stopPttHealthWatchdog(); clearPttPrebuffer(); stopMic(); setPnc380SpeakerTx(false);
                    pttDeniedTone();
                    refreshPttUi();
                }
                setNetworkUiState(message == null ? "发射权被占用" : message, false);
            } else if (state == 3) {
                networkUiReleased = false;
                    networkReleasedPeer = "";
                    if (activePttMode == MODE_SIGNAL_NETWORK && pttHeld) {
                    pttGranted = false; pttHeld = false; activePttMode = -1; localPttVisualActive = false;
                    stopPttTot(); stopPttHealthWatchdog(); clearPttPrebuffer(); stopMic(); setPnc380SpeakerTx(false);
                    pttDeniedTone();
                    refreshPttUi();
                    setNetworkUiState(message == null ? "对方正在发射，禁止发射" : message, false);
                } else {
                    // PTT/presence can arrive before the first media frame and can
                    // also arrive stale after release. Do not flash the callsign
                    // from presence alone; onVoice() is the authoritative start.
                    String remote = callsign == null ? "" : callsign.trim();
                    android.util.Log.d("ARCH-NET", "remote active waiting for voice " + remote);
                }
            } else if (state == 0) {
                boolean serverReleasedSelf = "本机发射已释放".equals(message);
                if (serverReleasedSelf) {
                    networkUiReleased = false;
                    networkReleasedPeer = "";
                    networkReceiving = false;
                    clearModeSignal(MODE_SIGNAL_NETWORK);
                    refreshDeviceIndicatorForAudio();
                    flushReceivePlayback();
                    if (pttHeld && activePttMode == MODE_SIGNAL_NETWORK) {
                        pttGeneration++;
                        pttHeld = false;
                        pttGranted = false;
                        activePttMode = -1;
                        localPttVisualActive = false;
                        pttToggleActive = false;
                        stopPttTot();
                        stopPttHealthWatchdog();
                        pttBeep(false);
                        clearPttPrebuffer();
                        stopMic();
                        setPnc380SpeakerTx(false);
                    }
                    if (!pttHeld) setNetworkUiState("已连接", false);
                } else {
                    // Remote release: hide callsign immediately, keep queued audio playing.
                    endRemoteNetworkReceiveUiNow(callsign);
                }
                refreshPttUi();
            }
        });
    }
    @Override public void onDuplex(int state, int peerDmrId, String peerCallsign, String message) {
        if (isFinishing() || isDestroyed()) return;
        if (state == 0) {
            networkReceiving = false;
            clearModeSignal(MODE_SIGNAL_NETWORK);
            flushReceivePlayback();
            refreshDeviceIndicatorForAudio();
        } else {
            markModeSignal(MODE_SIGNAL_NETWORK);
        }
        ui.post(() -> setNetworkUiState(message == null ? "网络频道空闲" : message, false));
    }

    private void ensureNetworkRoomView() {
        if (!netPage || authScreen || p == null || !p.authLoggedIn()) return;
        if (roomBox == null) return;
        renderRooms();
        refreshCallsignCard();
    }

    private void renderRooms() {
        android.util.Log.i("ARCH-ROOMS", "render box=" + (roomBox != null) + " net=" + netPage + " rooms=" + rooms.size() + " locked=" + lockedRoom);
        if (roomBox == null || !netPage) return;
        View focusedBefore = getCurrentFocus();
        int previousFocusIndex = roomFocusViews.indexOf(focusedBefore);
        boolean restoreRoomFocus = previousFocusIndex >= 0;
        View activeBefore = activeRoomView;
        roomBox.removeAllViews();
        roomFocusViews.clear();
        roomFocusIds.clear();
        activeRoomView = null;
        if (rooms.isEmpty()) {
            roomBox.addView(text("正在加载房间…", tinyScreen ? 10 : 12, SUB, false));
            return;
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        boolean singleColumn = width < 260;
        List<Room> displayRooms = orderedRooms();
        if (singleColumn) {
            for (Room room : displayRooms) {
                LinearLayout item = roomCard(room);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(2), 0, dp(2));
                roomBox.addView(item, lp);
            }
        } else {
            for (int i = 0; i < displayRooms.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.TOP);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.setMargins(0, dp(2), 0, dp(2));
                row.setLayoutParams(rowLp);
                LinearLayout leftCard = roomCard(displayRooms.get(i));
                LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1);
                leftLp.setMargins(0, 0, dp(3), 0);
                row.addView(leftCard, leftLp);
                if (i + 1 < displayRooms.size()) {
                    LinearLayout rightCard = roomCard(displayRooms.get(i + 1));
                    LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1);
                    rightLp.setMargins(dp(3), 0, 0, 0);
                    row.addView(rightCard, rightLp);
                } else {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                }
                roomBox.addView(row);
            }
        }
        android.util.Log.i("ARCH-ROOMS", "rendered children=" + roomBox.getChildCount() + " display=" + displayRooms.size());
        installKeyNavigation(roomBox);
        boolean activeBecameAvailable = activeRoomView != null && activeBefore == null;
        android.util.Log.i("ARCH-ROOMS", "focus activeBefore=" + (activeBefore != null)
                + " activeAfter=" + (activeRoomView != null)
                + " becameAvailable=" + activeBecameAvailable
                + " restore=" + restoreRoomFocus);
        if (keyNavigation) {
            View target = (uiProfile != null && uiProfile.pnc370 && !roomFocusViews.isEmpty())
                    ? roomFocusViews.get(0)
                    : (activeRoomView != null ? activeRoomView : (roomFocusViews.isEmpty() ? null : roomFocusViews.get(0)));
            if (target != null) {
                if (settingsIconButton != null) settingsIconButton.setNextFocusDownId(target.getId());
                if (radioIconButton != null) radioIconButton.setNextFocusDownId(target.getId());
                if (activeBecameAvailable && activeRoomView != null) {
                    // The saved room may appear only after the authoritative room
                    // list arrives. Move both focus and viewport to it before any
                    // boot-time confirm key can hit the first room instead.
                    activeRoomView.requestFocus();
                    ensureFocusVisible(activeRoomView);
                } else if (restoreRoomFocus) {
                    int restore = Math.max(0, Math.min(roomFocusViews.size() - 1, previousFocusIndex));
                    roomFocusViews.get(restore).requestFocus();
                } else if (focusedBefore == null) {
                    target.requestFocus();
                    ensureFocusVisible(target);
                }
            }
        }
    }

    private List<Room> orderedRooms() {
        List<Room> ordered = new ArrayList<>(rooms);
        if (!lockedRoom.isEmpty()) {
            for (int i = 0; i < ordered.size(); i++) {
                if (lockedRoom.equals(ordered.get(i).id)) {
                    Room lock = ordered.remove(i);
                    ordered.add(0, lock);
                    break;
                }
            }
        }
        return ordered;
    }

    private void toggleRoomLock(String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) return;
        roomId = roomId.trim();
        if (!lockedRoom.isEmpty() && !lockedRoom.equals(roomId)) {
            toast("请先解锁当前房间");
            return;
        }
        if (lockedRoom.equals(roomId)) {
            lockedRoom = "";
            p.rememberLockedNetRoom("");
            toast("房间已解锁");
        } else {
            lockedRoom = roomId;
            p.rememberLockedNetRoom(roomId);
            toast("房间已锁定");
            if (!roomId.equals(currentRoom)) connectRoom(roomId, true);
        }
        renderRooms();
        refreshCallsignCard();
    }

    private LinearLayout roomCard(Room room) {
        if (uiProfile != null && uiProfile.vm780) return vm780RoomCard(room);
        boolean active = currentRoom.equals(room.id);
        boolean locked = room.id.equals(lockedRoom);
        boolean connected = active && net != null && net.isRunning();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.FILL);
        card.setMinimumHeight(dp(shortScreen ? 82 : tinyScreen ? 78 : 82));
        card.setId(View.generateViewId());
        card.setPadding(dp(shortScreen ? 9 : 10), dp(shortScreen ? 7 : 8),
                dp(shortScreen ? 9 : 10), dp(shortScreen ? 7 : 8));
        GradientDrawable bg = new GradientDrawable();
        if (active) bg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        if (active && cyberTheme) {
            bg.setColors(new int[]{0xFF7A00FF, 0xFFFF2BD6});
        } else if (active) {
            bg.setColors(new int[]{ACCENT, accentColorFor(accentKey(), p.dark())});
        } else {
            bg.setColor(p.dark() ? 0xFF151D29 : 0xFFF9FBFD);
        }
        bg.setCornerRadius(dp(shortScreen ? 12 : 14));
        bg.setStroke(dp(active ? 2 : 1), active ? ACCENT : (p.dark() ? 0xFF293646 : 0xFFE1E8F1));
        card.setBackground(bg);
        card.setElevation(dp(active ? 5 : 2));
        card.setClickable(true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.TOP);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text(room.name, shortScreen ? 16 : tinyScreen ? 15 : 16,
                active ? Color.WHITE : TXT, true);
        title.setMaxLines(2);
        title.setSingleLine(false);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.START | Gravity.TOP);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        if (locked) {
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_lock_small);
            lock.setColorFilter(active ? Color.WHITE : ACCENT);
            lock.setContentDescription("已锁定");
            LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lockLp.setMargins(dp(6), dp(1), 0, 0);
            titleRow.addView(lock, lockLp);
        }
        card.addView(titleRow, new LinearLayout.LayoutParams(-1, 0, 1f));

        String state = active ? (connected ? "已连接" : (networkWanted ? "连接中" : "未连接")) : "未连接";
        int online = active ? currentRoomOnline() : room.online;
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setGravity(Gravity.BOTTOM);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView footer = text(state, shortScreen ? 12 : tinyScreen ? 12 : 12,
                active ? withAlpha(Color.WHITE, 225) : SUB, true);
        footer.setSingleLine(true);
        footerRow.addView(footer, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView onlineText = text(online + " 人在线", shortScreen ? 12 : tinyScreen ? 12 : 12,
                active ? Color.WHITE : SUB, true);
        onlineText.setSingleLine(true);
        onlineText.setGravity(Gravity.END);
        footerRow.addView(onlineText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
        footerLp.setMargins(0, dp(4), 0, 0);
        card.addView(footerRow, footerLp);

        String footerText = state + " · " + online + "人在线";
        card.setContentDescription(room.name + "，" + footerText + (locked ? "，已锁定" : ""));
        makeFocusable(card);
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            toggleRoomLock(room.id);
            return true;
        });
        card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { moveRoomFocusBy(view, -1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { moveRoomFocusBy(view, 1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                int index = roomFocusViews.indexOf(view);
                int columns = getResources().getDisplayMetrics().widthPixels < 260 ? 1 : 2;
                if (index >= 0 && index < columns && settingsIconButton != null) {
                    settingsIconButton.requestFocus();
                    return true;
                }
                moveRoomFocusBy(view, -columns); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveRoomFocusBy(view, getResources().getDisplayMetrics().widthPixels < 260 ? 1 : 2); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                view.performClick();
                return true;
            }
            return false;
        });
        card.setOnClickListener(v -> connectRoom(room.id, true));
        roomFocusViews.add(card);
        roomFocusIds.add(room.id);
        if (active) activeRoomView = card;
        return card;
    }

    private LinearLayout vm780RoomCard(Room room) {
        boolean active = currentRoom.equals(room.id);
        boolean locked = room.id.equals(lockedRoom);
        boolean connected = active && net != null && net.isRunning();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(38));
        card.setId(View.generateViewId());
        card.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        if (active) bg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        if (active && cyberTheme) bg.setColors(new int[]{0xFF7A00FF, 0xFFFF2BD6});
        else if (active) bg.setColors(new int[]{ACCENT, accentColorFor(accentKey(), p.dark())});
        else bg.setColor(p.dark() ? 0xFF151D29 : 0xFFF9FBFD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(active ? 2 : 1), active ? ACCENT : (p.dark() ? 0xFF293646 : 0xFFE1E8F1));
        card.setBackground(bg);
        card.setElevation(dp(active ? 3 : 1));
        card.setClickable(true);

        TextView title = text(room.name, 13, active ? Color.WHITE : TXT, true);
        title.setMaxLines(1);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        String state = active ? (connected ? "已连接" : (networkWanted ? "连接中" : "未连接")) : "未连接";
        int online = active ? currentRoomOnline() : room.online;
        TextView footer = text(state + " · " + online + "人", 9,
                active ? Color.WHITE : SUB, true);
        footer.setSingleLine(true);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(6), dp(2), dp(6), dp(2));
        footer.setBackground(roundBg(active ? withAlpha(Color.WHITE, 34)
                : (p.dark() ? 0xFF202B3A : 0xFFEEF3F8), 999));
        card.addView(footer, new LinearLayout.LayoutParams(-2, -2));
        if (locked) {
            ImageView lock = new ImageView(this);
            lock.setImageResource(R.drawable.ic_lock_small);
            lock.setColorFilter(active ? Color.WHITE : ACCENT);
            lock.setContentDescription("已锁定");
            LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(14), dp(14));
            lockLp.setMargins(dp(5), 0, 0, 0);
            card.addView(lock, lockLp);
        }
        card.setContentDescription(room.name + "，" + state + "，" + online + "人在线");
        makeFocusable(card);
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            toggleRoomLock(room.id);
            return true;
        });
        card.setOnClickListener(v -> connectRoom(room.id, true));
        roomFocusViews.add(card);
        roomFocusIds.add(room.id);
        if (active) activeRoomView = card;
        return card;
    }

    private void renderUsers() {
        if (pncOnlineStrip != null) {
            StringBuilder names = new StringBuilder();
            for (NetworkRoomClient.Member m : members) {
                if (m == null || m.callsign == null || m.callsign.trim().isEmpty()) continue;
                if (names.length() > 0) names.append("、");
                names.append(m.callsign.trim());
            }
            pncOnlineStrip.setText(names.length() == 0 ? "在线用户：暂无" : "在线用户：" + names);
        }
        if (userBox == null || !netPage) return;
        userBox.removeAllViews();
        if (members.isEmpty()) {
            userBox.addView(text("暂无在线用户", 12, SUB, false));
            return;
        }
        for (NetworkRoomClient.Member m : members) {
            String state = m.tx ? "发射中" : (m.dmrId == p.dmrId() ? "本机" : "在线");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(5), dp(8), dp(5));
            row.setBackground(roundBg(p.dark() ? 0xFF141E2A : 0xFFF5F8FC, 10));
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(m.tx ? GREEN : ACCENT);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.setMargins(0, 0, dp(7), 0);
            row.addView(dot, dotLp);
            TextView name = text(m.callsign, shortScreen ? 11 : 13, TXT, true);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView meta = text(state + " · " + m.dmrId, shortScreen ? 9 : 10,
                    m.tx ? GREEN : SUB, false);
            meta.setSingleLine(true);
            row.addView(meta);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, 0, 0, dp(4));
            userBox.addView(row, rowLp);
        }
    }

    private void rotateRoom(boolean next) {
        if (!lockedRoom.isEmpty()) { toast("房间已锁定，长按锁定房间可解锁"); return; }
        if (rooms == null || rooms.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < rooms.size(); i++) if (rooms.get(i).id.equals(currentRoom)) { idx = i; break; }
        idx = (idx + (next ? 1 : rooms.size() - 1)) % rooms.size();
        connectRoom(rooms.get(idx).id, true);
    }

    private void refreshHyteraDisplay() {
        if (!supportsPdc760Bridge()) return;
        String channel;
        if (digitalPage) channel = dmrTgName(p.tg());
        else if (bmPage) channel = bmTgName(p.bmTg());
        else if (radioPage) channel = "网络收音机";
        else channel = roomName(currentRoom);
        try {
            HyteraPdc760Bridge.setDisplay(this, channel, p.callsign(), String.valueOf(p.dmrId()));
        } catch (Throwable ignored) {}
    }

    private String roomName(String id) {
        for (Room r : rooms) if (r.id.equals(id)) return r.name;
        if (id == null) return "";
        if (rooms.isEmpty() && id.matches("room\\d+")) return "正在载入房间";
        return id;
    }

    private String roomCacheKey() {
        return "roomsCache:" + p.dmrId();
    }

    private List<Room> loadCachedRooms() {
        List<Room> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.get(roomCacheKey(), "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                if (id.isEmpty()) continue;
                out.add(new Room(id, o.optString("name", id), o.optString("desc", ""), o.optInt("online")));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void saveCachedRooms(List<Room> rooms) {
        try {
            JSONArray arr = new JSONArray();
            for (Room r : rooms) {
                if (r.restricted) continue;
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name);
                o.put("desc", r.desc);
                o.put("online", r.online);
                arr.put(o);
            }
            p.set(roomCacheKey(), arr.toString());
        } catch (Throwable ignored) {}
    }

    private List<Room> fallbackRooms() {
        List<Room> out = new ArrayList<>();
        out.add(new Room("room1", "438.500中继", "", 0));
        out.add(new Room("room2", "46002多模融合", "", 0));
        out.add(new Room("room3", "省无线电运动协会", "", 0));
        out.add(new Room("room5", "语音测试组", "", 0));
        out.add(new Room("room6", "双工测试组", "", 0));
        out.add(new Room("room7", "46001中国组", "", 0));
        out.add(new Room("room8", "46020广州组", "", 0));
        return out;
    }

    private void seedRoomDirectoryIfNeeded() {
        if (!rooms.isEmpty()) return;
        List<Room> seed = loadCachedRooms();
        if (seed.isEmpty()) seed = fallbackRooms();
        rooms = seed;
        // Cached rooms may lag behind the server and often miss restricted or
        // newly added rooms. Never replace a saved room based on the cache;
        // wait for the authoritative room list from the server.
        if ((currentRoom == null || currentRoom.trim().isEmpty()) && !rooms.isEmpty()) {
            currentRoom = rooms.get(0).id;
            roomSelection = currentRoom;
            p.rememberNetRoom(currentRoom);
        } else {
            android.util.Log.i("ARCH-STATE", "cache kept saved netRoom=" + currentRoom);
        }
        ensureNetworkRoomView();
    }

    private void loadRooms() {
        if (roomsLoading) return;
        roomsLoading = true;
        roomsLoadAttempt++;
        final int attempt = roomsLoadAttempt;
        seedRoomDirectoryIfNeeded();
        new Thread(() -> {
            List<Room> out = new ArrayList<>();
            final boolean[] loaded = {false};
            try {
                String fetchUrl = ROOMS_URL + "?t=" + System.currentTimeMillis();
                Request request = new Request.Builder().url(fetchUrl)
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Connection", "close")
                        .build();
                try (Response response = roomHttp.newCall(request).execute()) {
                    if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
                    String body = response.body() == null ? "" : response.body().string();
                    JSONObject root = new JSONObject(body);
                    JSONArray arr = root.optJSONArray("rooms");
                    if (arr != null) {
                        loaded[0] = true;
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
                            if (!o.optBoolean("enabled", true)) continue;
                            String id = o.optString("id", "").trim();
                            if (id.isEmpty()) continue;
                            JSONArray targets = o.optJSONArray("targets");
                            boolean restricted = targets != null && targets.length() > 0;
                            if (restricted) {
                                boolean allowed = false;
                                for (int k = 0; k < targets.length(); k++) {
                                    if (targets.optInt(k, -1) == p.dmrId()) { allowed = true; break; }
                                }
                                if (!allowed) continue;
                            }
                            if (BuildConfig.PUBLIC_EDITION && o.optBoolean("publicHidden", false)) continue;
                            out.add(new Room(id, o.optString("name", id), o.optString("description"),
                                    o.optInt("online"), restricted));
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("ARCH-ROOMS", "load failed: " + t);
            }
            android.util.Log.i("ARCH-ROOMS", "loaded=" + loaded[0] + " count=" + out.size());
            ui.post(() -> {
                roomsLoading = false;
                if (loaded[0]) {
                    roomsLoadAttempt = 0;
                    String beforeRoom = currentRoom;
                    boolean wasConnected = net != null && net.isRunning();
                    rooms = out;
                    saveCachedRooms(out);
                    boolean lockPresent = false;
                    if (!lockedRoom.isEmpty()) {
                        for (Room r : rooms) if (r.id.equals(lockedRoom)) { lockPresent = true; break; }
                        if (!lockPresent) {
                            lockedRoom = "";
                            p.set("lockedNetRoom", "");
                        }
                    }
                    if (rooms.isEmpty()) {
                        // Keep the saved room even if the server returns no rooms
                        // transiently; it will be retried on the next connection.
                        networkWanted = false;
                        android.util.Log.w("ARCH-STATE", "server returned empty rooms; kept saved netRoom=" + currentRoom);
                        try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
                    } else {
                        boolean present = false;
                        for (Room r : rooms) if (r.id.equals(currentRoom)) { present = true; break; }
                        if (lockPresent) {
                            currentRoom = lockedRoom;
                            roomSelection = currentRoom;
                            p.rememberNetRoom(currentRoom);
                        } else if (!present) {
                            // The list API can temporarily omit a restricted room.
                            // Keep the user's saved selection instead of silently
                            // switching to the first room.
                            android.util.Log.w("ARCH-STATE", "saved netRoom not in current list; kept=" + currentRoom);
                        }
                        if (wasConnected && !currentRoom.equals(beforeRoom)) connectRoom(currentRoom, false);
                    }
                    ensureNetworkRoomView();
                } else if (!isFinishing() && attempt < ROOM_LOAD_MAX_ATTEMPTS) {
                    ui.postDelayed(this::loadRooms, ROOM_LOAD_RETRY_MS);
                } else {
                    renderRooms();
                }
            });
        }, "network-rooms").start();
    }

    private View wireRadioChipGrid(LinearLayout grid) {
        if (grid == null) return null;
        java.util.List<java.util.List<View>> rows = new ArrayList<>();
        java.util.List<View> firstRow = null;
        for (int r = 0; r < grid.getChildCount(); r++) {
            View child = grid.getChildAt(r);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup rowView = (ViewGroup) child;
            java.util.List<View> row = new ArrayList<>();
            for (int c = 0; c < rowView.getChildCount(); c++) {
                View item = rowView.getChildAt(c);
                if (item.isClickable() && item.isFocusable()) {
                    if (item.getId() == View.NO_ID) item.setId(View.generateViewId());
                    row.add(item);
                }
            }
            if (!row.isEmpty()) rows.add(row);
        }
        for (int r = 0; r < rows.size(); r++) {
            java.util.List<View> row = rows.get(r);
            if (firstRow == null) firstRow = row;
            for (int c = 0; c < row.size(); c++) {
                View item = row.get(c);
                if (c > 0) item.setNextFocusLeftId(row.get(c - 1).getId());
                if (c + 1 < row.size()) item.setNextFocusRightId(row.get(c + 1).getId());
                if (r > 0 && c < rows.get(r - 1).size()) item.setNextFocusUpId(rows.get(r - 1).get(c).getId());
                if (r + 1 < rows.size() && c < rows.get(r + 1).size()) item.setNextFocusDownId(rows.get(r + 1).get(c).getId());
            }
        }
        return firstRow == null || firstRow.isEmpty() ? null : firstRow.get(0);
    }

    private void loadRadioCatalog(boolean refresh) {
        List<RadioStation> loaded = RadioCatalog.load(this);
        radioStations.clear();
        radioStations.addAll(loaded);
        allStations = loaded;
        if (refresh) refreshRadioCatalogAsync();
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int)radiusDp));
        return g;
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private TextView radioSectionHeader(String title, boolean open, Runnable click) {
        // 原版样式：蓝色折叠标题，不使用白色卡片底。
        TextView header = text((open ? "▾  " : "▸  ") + title, 16, ACCENT, true);
        header.setId(View.generateViewId());
        header.setContentDescription(title);
        header.setPadding(dp(4), dp(9), dp(4), dp(5));
        header.setOnClickListener(v -> click.run());
        makeFocusable(header);
        return header;
    }

    private TextView radioChip(String label, boolean selected, Runnable click) {
        // 原版样式：13sp、6列/5列网格、12dp圆角。
        TextView chip = text(label, tinyScreen ? 14 : 17, selected ? Color.WHITE : TXT, selected);
        chip.setId(View.generateViewId());
        chip.setContentDescription(label);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(2), dp(8), dp(2), dp(8));
        chip.setBackground(roundBg(selected ? ACCENT : (p.dark() ? 0xFF232C38 : 0xFFF1F5F9), 12));
        chip.setOnClickListener(v -> click.run());
        makeFocusable(chip);
        return chip;
    }

    private void refreshRadioProvinceGrid() {
        if (radioProvinceGrid == null) return;
        radioProvinceGrid.removeAllViews();
        List<String> provinces = RadioCatalog.provinces(radioStations);
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
            String shown = tinyScreen && "短波广播".equals(province) ? "短波" : province;
            TextView chip = radioChip(shown, province.equals(radioProvince), () -> {
                radioProvince = pv;
                radioCity = "全部城市";
                refreshRadioProvinceGrid();
                refreshRadioCityGrid();
                revealRadioStationList(false);
                focusRadioGridItem(radioProvinceGrid, pv);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            row.addView(chip, lp);
            if (++col == 6) row = null;
        }
        if (row != null) while (row.getChildCount() < 6) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        installKeyNavigation(radioProvinceGrid);
        View first = wireRadioChipGrid(radioProvinceGrid);
        if (first != null && radioProvinceHeader != null) {
            radioProvinceHeader.setNextFocusDownId(first.getId());
            first.setNextFocusUpId(radioProvinceHeader.getId());
        }
    }

    private void refreshRadioCityGrid() {
        if (radioCityGrid == null) return;
        boolean showCity = !radioProvince.isEmpty()
                && !"全部".equals(radioProvince)
                && !"网络台".equals(radioProvince)
                && !"短波广播".equals(radioProvince);
        if (radioCityHeader != null) radioCityHeader.setVisibility(showCity ? View.VISIBLE : View.GONE);
        if (!showCity) {
            radioCityGrid.removeAllViews();
            radioCityGrid.setVisibility(View.GONE);
            return;
        }
        radioCityGrid.setVisibility(radioCityOpen ? View.VISIBLE : View.GONE);
        radioCityGrid.removeAllViews();
        LinearLayout row = null;
        int col = 0;
        for (String city : RadioCatalog.cities(radioStations, radioProvince)) {
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
                revealRadioStationList(false);
                focusRadioGridItem(radioCityGrid, cv);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(chip, lp);
            if (++col == 5) row = null;
        }
        if (row != null) while (row.getChildCount() < 5) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        installKeyNavigation(radioCityGrid);
        View first = wireRadioChipGrid(radioCityGrid);
        if (first != null && radioCityHeader != null) {
            radioCityHeader.setNextFocusDownId(first.getId());
            first.setNextFocusUpId(radioCityHeader.getId());
        }
    }

    private void focusRadioGridItem(View root, String label) {
        if (!keyNavigation || root == null || label == null || label.isEmpty()) return;
        root.post(() -> {
            View target = findFocusableByDescription(root, label);
            if (target != null) {
                target.requestFocus();
                ensureFocusVisible(target);
            }
        });
    }

    private View findFocusableByDescription(View root, String label) {
        if (root == null || label == null) return null;
        CharSequence description = root.getContentDescription();
        if (root.isFocusable() && description != null && label.contentEquals(description)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFocusableByDescription(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isDescendantOf(View ancestor, View child) {
        if (ancestor == null || child == null) return false;
        View current = child;
        while (current != null) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void refreshRadioCatalogPreservingFocus() {
        View focused = getCurrentFocus();
        View restoreRoot = null;
        String restoreLabel = null;
        if (focused != null && focused.getContentDescription() != null) {
            restoreLabel = focused.getContentDescription().toString();
            if (isDescendantOf(radioProvinceGrid, focused)) restoreRoot = radioProvinceGrid;
            else if (isDescendantOf(radioCityGrid, focused)) restoreRoot = radioCityGrid;
            else if (isDescendantOf(radioListBox, focused)) restoreRoot = radioListBox;
        }
        refreshRadioProvinceGrid();
        refreshRadioCityGrid();
        renderRadioStations();
        if (restoreRoot != null) focusRadioGridItem(restoreRoot, restoreLabel);
    }
    private void focusFirstCityControl() {
        if (radioCityHeader == null || radioCityHeader.getVisibility() != View.VISIBLE) return;
        View target = radioCityGrid != null && radioCityGrid.getVisibility() == View.VISIBLE
                ? findFirstFocusable(radioCityGrid) : radioCityHeader;
        if (target == null) target = radioCityHeader;
        final View focusTarget = target;
        focusTarget.post(() -> {
            focusTarget.requestFocus();
            ensureFocusVisible(focusTarget);
        });
    }

    private void revealRadioStationList(boolean focusHeader) {
        radioListOpen = true;
        if (radioListHeader != null) {
            radioListHeader.setText("▾  电台");
            radioListHeader.setVisibility(View.VISIBLE);
        }
        if (radioListBox != null) radioListBox.setVisibility(View.VISIBLE);
        renderRadioStations();
        if (focusHeader && keyNavigation) {
            radioListBox.post(() -> {
                View firstStation = findFirstFocusable(radioListBox);
                if (firstStation != null) {
                    firstStation.requestFocus();
                    ensureFocusVisible(firstStation);
                }
            });
        }
    }

    private List<RadioStation> filteredRadioStations() {
        List<RadioStation> out = new ArrayList<>();
        String q = radioSearch == null ? "" : radioSearch.getText().toString().trim().toLowerCase(Locale.US);
        if (q.isEmpty() && "全部".equals(radioProvince)) return out;
        for (RadioStation s : radioStations) {
            if (!q.isEmpty()) {
                String all = (s.name + " " + s.province + " " + s.city + " " + s.category + " " + s.sourceType).toLowerCase(Locale.US);
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
        List<RadioStation> list = filteredRadioStations();
        if (list.isEmpty()) {
            radioListBox.addView(text("请选择省份/城市，或输入电台名搜索", 14, SUB, false));
            return;
        }
        radioListBox.addView(text("共 " + list.size() + " 个电台", 13, SUB, false));
        View firstRow = null;
        for (RadioStation s : list) {
            View row = radioStationRow(s);
            if (firstRow == null) firstRow = row;
            radioListBox.addView(row);
        }
        installKeyNavigation(radioListBox);
        if (firstRow != null && radioListHeader != null) {
            if (firstRow.getId() == View.NO_ID) firstRow.setId(View.generateViewId());
            radioListHeader.setNextFocusDownId(firstRow.getId());
        }
    }

    private View radioStationRow(final RadioStation s) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setContentDescription(s.name);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(roundBg(p.dark() ? 0xFF232C38 : 0xFFF8FAFD, 14));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(7));
        row.setLayoutParams(rowLp);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(s.name, 16, TXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(n);
        info.addView(text(s.subtitle(), 12, SUB, false));
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(info);
        boolean favorite = p.radioIsFavorite(s.id);
        TextView star = text(favorite ? "★" : "☆", 22, favorite ? GREEN : SUB, false);
        star.setGravity(Gravity.CENTER);
        star.setPadding(dp(8), 0, dp(8), 0);
        star.setOnClickListener(v -> {
            p.radioToggleFavorite(s.id);
            renderRadioStations();
            refreshRadioFavorites();
        });
        row.addView(star);
        boolean playing = radioCurrent != null && s.id.equals(radioCurrent.id)
                && radio != null && radio.isPlaying();
        TextView play = text(playing ? "⏸" : "▶", 20, ACCENT, false);
        play.setGravity(Gravity.CENTER);
        play.setPadding(dp(8), 0, dp(4), 0);
        play.setOnClickListener(v -> playRadioStation(s));
        row.addView(play);
        row.setOnClickListener(v -> playRadioStation(s));
        return row;
    }

    private void refreshRadioFavorites() {
        if (radioFavoriteBox == null) return;
        radioFavoriteBox.removeAllViews();
        java.util.LinkedHashMap<String, RadioStation> map = new java.util.LinkedHashMap<>();
        for (RadioStation s : radioStations) map.put(s.id, s);
        int count = 0;
        for (String id : p.radioFavorites().split("\n")) {
            RadioStation s = map.get(id == null ? "" : id.trim());
            if (s != null) { radioFavoriteBox.addView(radioStationRow(s)); count++; }
            if (count >= 20) break;
        }
        if (radioFavoriteHeader != null) {
            radioFavoriteHeader.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            radioFavoriteHeader.setText((radioFavoriteOpen ? "▾  " : "▸  ") + "收藏");
        }
        radioFavoriteBox.setVisibility(count > 0 && radioFavoriteOpen ? View.VISIBLE : View.GONE);
        installKeyNavigation(radioFavoriteBox);
    }

    private void refreshRadioRecent() {
        if (radioRecentBox == null) return;
        radioRecentBox.removeAllViews();
        java.util.LinkedHashMap<String, RadioStation> map = new java.util.LinkedHashMap<>();
        for (RadioStation s : radioStations) map.put(s.id, s);
        int count = 0;
        for (String id : p.radioRecent().split("\n")) {
            final RadioStation s = map.get(id == null ? "" : id.trim());
            if (s == null) continue;
            View row = radioStationRow(s);
            row.setOnLongClickListener(v -> {
                p.radioRemoveRecent(s.id); refreshRadioRecent(); toast("已从最近播放移除"); return true;
            });
            radioRecentBox.addView(row);
            if (++count >= 5) break;
        }
        if (radioRecentHeader != null) {
            radioRecentHeader.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            radioRecentHeader.setText((radioRecentOpen ? "▾  " : "▸  ") + "最近播放");
        }
        radioRecentBox.setVisibility(count > 0 && radioRecentOpen ? View.VISIBLE : View.GONE);
        installKeyNavigation(radioRecentBox);
    }

    private void playRadioStation(RadioStation s) {
        if (s == null || radio == null) return;
        radioCurrent = s;
        p.radioMarkRecent(s.id);
        if (radioNowTitle != null) radioNowTitle.setText(s.name);
        if (radioNowSub != null) radioNowSub.setText(s.subtitle());
        if (radioStatus != null) { radioStatus.setText("正在连接…"); radioStatus.setTextColor(ACCENT); }
        radio.play(s);
        renderRadioStations(); refreshRadioRecent();
    }

    private void toggleRadioPlayback() {
        if (radio == null) return;
        if (radioCurrent == null) { toast("请先选择电台"); return; }
        if (radio.isPlaying()) { radio.pause(); return; }
        radio.resume();
    }

    private void stopRadio() {
        if (radio != null) radio.stop();
        if (radioWave != null) radioWave.setActive(false);
        if (radioStatus != null) { radioStatus.setText("▶ 未播放"); radioStatus.setTextColor(SUB); }
        renderRadioStations(); refreshRadioRecent();
    }

    private void updateRadioPlayerState(int state, String msg) {
        if (radioStatus != null) {
            boolean playing = radio != null && radio.isPlaying();
            String prefix = playing ? "⏸ " : "▶ ";
            radioStatus.setText(prefix + (msg == null ? "" : msg));
            radioStatus.setTextColor(state == 3 ? RED : (playing ? GREEN : ACCENT));
        }
        if (radioWave != null) radioWave.setActive(radio != null && radio.isPlaying());
    }

    private void refreshRadioCatalogAsync() {
        radioCatalogLastRefresh = System.currentTimeMillis();
        RadioCatalog.refreshAsync(this, list -> ui.post(() -> {
            if (list == null || list.isEmpty()) return;
            radioStations.clear(); radioStations.addAll(list); allStations = list;
            refreshRadioCatalogPreservingFocus();
            refreshRadioFavorites(); refreshRadioRecent();
        }));
    }

    private void buildRadioLinks() {
        if (radioLinkBox == null) return;
        radioLinkBox.removeAllViews();
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
        for (RadioLinks.Link link : RadioLinks.ALL) groups.add(link.group);
        boolean showGroup = groups.size() > 1;
        String group = "";
        java.util.ArrayList<View> linkRows = new java.util.ArrayList<>();
        for (RadioLinks.Link link : RadioLinks.ALL) {
            if (showGroup && !link.group.equals(group)) {
                group = link.group;
                TextView g = text(group, 13, SUB, false);
                g.setPadding(dp(4), dp(8), dp(4), dp(3));
                radioLinkBox.addView(g);
            }
            LinearLayout row = new LinearLayout(this);
            row.setId(View.generateViewId());
            row.setContentDescription(link.name);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundBg(p.dark() ? 0xFF232C38 : 0xFFF8FAFD, 12));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(5));
            row.setLayoutParams(rowLp);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView n = text(link.name, 15, TXT, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = text(link.sub, 12, SUB, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(n);
            info.addView(sub);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = text("↗", 16, ACCENT, false);
            go.setPadding(dp(8), 0, dp(4), 0);
            row.addView(info);
            row.addView(go);
            row.setOnClickListener(v -> openRadioLink(link.url));
            makeFocusable(row);
            radioLinkBox.addView(row);
            linkRows.add(row);
        }
        for (int i = 0; i < linkRows.size(); i++) {
            View row = linkRows.get(i);
            row.setNextFocusUpId(i == 0 ? radioLinkHeader.getId() : linkRows.get(i - 1).getId());
            row.setNextFocusDownId(i + 1 < linkRows.size() ? linkRows.get(i + 1).getId() : row.getId());
        }
        if (!linkRows.isEmpty()) radioLinkHeader.setNextFocusDownId(linkRows.get(0).getId());
        installKeyNavigation(radioLinkBox);
    }

    private void openRadioLink(String url) {
        // Key-first PNC devices use the in-app browser so the D-pad cursor is
        // available for player/station controls; phones keep the normal browser.
        if (keyNavigation) showEmbeddedBrowser(url, "没有可用的浏览器");
        else openExternal(url, "没有可用的浏览器");
    }

    private void openExternal(String url, String error) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            showEmbeddedBrowser(url, error);
        }
    }

    private TextView sipFieldLabel(String value) {
        TextView label = text(value, shortScreen ? 11 : 12, SUB, true);
        label.setPadding(0, dp(6), 0, dp(2));
        return label;
    }

    private void openSipPhone() {
        try {
            SipRuntime.get(this).ensureStarted();
            startActivity(new Intent(this, SipPhoneActivity.class));
        } catch (Throwable t) { toast("SIP 电话无法打开"); }
    }

    private void openAprsMap() {
        try {
            String callsign = p.aprsCallsign() == null ? "" : p.aprsCallsign().trim().toUpperCase(Locale.US);
            if (callsign.isEmpty()) callsign = p.callsign().trim().toUpperCase(Locale.US);
            String station = (callsign + "-" + p.aprsSsid()).replaceAll("[^A-Z0-9-]", "");
            String html = readAssetText("aprs_map.html").replace("__STATION__", station);
            WebView web = createEmbeddedWebView("APRS 地图");
            web.loadDataWithBaseURL("https://aprs.tv/", html, "text/html", "UTF-8", null);
            web.requestFocus();
        } catch (Throwable t) {
            toast("APRS 地图无法打开");
        }
    }

    private void showEmbeddedBrowser(String url, String error) {
        try {
            WebView web = createEmbeddedWebView("ARCH 浏览器");
            web.loadUrl(url);
            web.requestFocus();
        } catch (Throwable t) {
            toast(error);
        }
    }

    private WebCursorController activeWebCursorController;

    private WebView createEmbeddedWebView(String titleText) {
        final Dialog dialog = new Dialog(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(2), dp(6), dp(2));
        bar.setBackground(roundBg(0xFFF1F5F9, 0));
        TextView title = text(titleText, 13, TXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text("关闭", 12, ACCENT, true);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(v -> dialog.dismiss());
        makeFocusable(close);
        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 34 : 40)));

        WebView web = new WebView(this);
        web.setId(View.generateViewId());
        web.setBackgroundColor(Color.WHITE);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        FrameLayout webLayer = new FrameLayout(this);
        webLayer.setBackgroundColor(Color.WHITE);
        webLayer.addView(web, new FrameLayout.LayoutParams(-1, -1));
        final WebCursorController cursorController;
        if (keyNavigation) {
            TextView cursor = text("●", 18, 0xFFFF5A00, true);
            cursor.setGravity(Gravity.CENTER);
            cursor.setClickable(false);
            cursor.setFocusable(false);
            GradientDrawable cursorBg = roundBg(0xE6FFFFFF, 999);
            cursorBg.setStroke(dp(2), 0xFFFF5A00);
            cursor.setBackground(cursorBg);
            cursor.setElevation(dp(20));
            FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.LEFT);
            webLayer.addView(cursor, cursorLp);
            cursorController = new WebCursorController(web, cursor);
            web.setOnKeyListener(cursorController);
        } else {
            cursorController = null;
        }
        close.setNextFocusDownId(web.getId());
        web.setNextFocusUpId(close.getId());
        shell.addView(webLayer, new LinearLayout.LayoutParams(-1, 0, 1f));

        dialog.setContentView(shell);
        dialog.setOnDismissListener(d -> {
            if (activeWebCursorController == cursorController) activeWebCursorController = null;
            if (cursorController != null) cursorController.cancelPending();
            try { web.stopLoading(); } catch (Throwable ignored) {}
            try { web.loadUrl("about:blank"); } catch (Throwable ignored) {}
            try { web.destroy(); } catch (Throwable ignored) {}
        });
        dialog.show();
        activeWebCursorController = cursorController;
        if (cursorController != null) {
            dialog.setOnKeyListener((d, keyCode, event) -> cursorController.onKey(web, keyCode, event));
            web.post(cursorController::center);
            web.postDelayed(cursorController::center, 250L);
            web.postDelayed(cursorController::center, 800L);
            toast("方向键移动光标，确认键点击");
        }
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return web;
    }

    /** Direction-key pointer for WebView pages on non-touch / PNC devices. */
    private final class WebCursorController implements View.OnKeyListener {
        private final WebView web;
        private final TextView cursor;
        private int cursorX;
        private int cursorY;
        private Runnable longPressRunnable;
        private Runnable pendingSingleClick;
        private long pressDownAt;
        private long lastTapUpAt;
        private boolean longPressTriggered;
        private boolean doubleTapConsumed;
        private boolean repeatSeen;
        private boolean userMoved;
        private boolean confirmPressed;
        private boolean pressFallbackClicked;
        private boolean pressGestureCancelled;
        private float pressClickX;
        private float pressClickY;
        private int lastKeyCode = Integer.MIN_VALUE;
        private int lastKeyAction = Integer.MIN_VALUE;
        private long lastKeyEventTime = Long.MIN_VALUE;

        WebCursorController(WebView web, TextView cursor) {
            this.web = web;
            this.cursor = cursor;
        }

        void center() {
            if (userMoved) return;
            int w = web.getWidth();
            int h = web.getHeight();
            if (w <= 0 || h <= 0) return;
            cursorX = Math.max(0, (w - cursor.getWidth()) / 2);
            cursorY = Math.max(0, (h - cursor.getHeight()) / 2);
            applyPosition();
        }

        @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
            long eventTime = event.getEventTime();
            if (keyCode == lastKeyCode && event.getAction() == lastKeyAction && eventTime == lastKeyEventTime)
                return true;
            boolean movement = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean confirm = isConfirmActivationKey(keyCode);
            if (!movement && !confirm) return false;
            lastKeyCode = keyCode;
            lastKeyAction = event.getAction();
            lastKeyEventTime = eventTime;
            if (confirm) {
                // Confirmation keys need both DOWN and UP. The previous code
                // swallowed ACTION_UP here, so long-press duration could never
                // be measured and every tap fell through to the long timer.
                handleConfirmKey(event);
            } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int step = dp(14);
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) move(-step, 0);
                else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) move(step, 0);
                else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) move(0, -step);
                else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) move(0, step);
            }
            return true;
        }

        private void move(int dx, int dy) {
            userMoved = true;
            if (confirmPressed) pressGestureCancelled = true;
            cancelPending();
            int maxX = Math.max(0, web.getWidth() - cursor.getWidth());
            int maxY = Math.max(0, web.getHeight() - cursor.getHeight());
            int rawX = cursorX + dx;
            int rawY = cursorY + dy;
            boolean atEdge = rawX < 0 || rawX > maxX || rawY < 0 || rawY > maxY;
            cursorX = Math.max(0, Math.min(maxX, rawX));
            cursorY = Math.max(0, Math.min(maxY, rawY));
            applyPosition();
            if (atEdge) panContent(dx, dy);
        }

        private void handleConfirmKey(KeyEvent event) {
            final long doubleWindowMs = 480L;
            final long longPressMs = 650L;
            final long missingUpFallbackMs = 1200L;
            long now = android.os.SystemClock.uptimeMillis();

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    // A second distinct DOWN completes a double-click. Do not
                    // decide long-press from elapsed time alone: a normal tap
                    // also outlives any short timer and used to zoom out by
                    // accident on PNC hardware.
                    if (pendingSingleClick != null && lastTapUpAt > 0L
                            && now - lastTapUpAt <= doubleWindowMs) {
                        cancelPending();
                        doubleTapConsumed = true;
                        longPressTriggered = false;
                        repeatSeen = false;
                        lastTapUpAt = 0L;
                        android.util.Log.i("ARCH-WEBKEY", "double click -> zoom in");
                        zoom(true);
                        return;
                    }

                    cancelPending();
                    pressDownAt = now;
                    longPressTriggered = false;
                    doubleTapConsumed = false;
                    repeatSeen = false;
                    confirmPressed = true;
                    pressFallbackClicked = false;
                    pressGestureCancelled = false;
                    pressClickX = cursorX + Math.max(cursor.getWidth(), dp(26)) / 2f;
                    pressClickY = cursorY + Math.max(cursor.getHeight(), dp(26)) / 2f;

                    // Devices that do not report ACTION_UP still get a usable
                    // single click. A real long-press is detected from key
                    // repeats or from ACTION_UP duration below.
                    pendingSingleClick = () -> {
                        pendingSingleClick = null;
                        if (!longPressTriggered && !doubleTapConsumed && !pressGestureCancelled) {
                            lastTapUpAt = 0L;
                            pressFallbackClicked = true;
                            android.util.Log.i("ARCH-WEBKEY", "single click fallback");
                            clickAt(pressClickX, pressClickY);
                        }
                    };
                    ui.postDelayed(pendingSingleClick, missingUpFallbackMs);
                } else {
                    // Ignore repeats after a completed double-click gesture.
                    // Otherwise holding the second click would zoom in and
                    // then immediately zoom back out.
                    if (doubleTapConsumed) return;
                    // A repeat event is emitted only after the platform's
                    // long-press timeout. Treat the first one as the long press
                    // and cancel the pending single-click action immediately.
                    repeatSeen = true;
                    if (!longPressTriggered) {
                        longPressTriggered = true;
                        lastTapUpAt = 0L;
                        if (pendingSingleClick != null) {
                            ui.removeCallbacks(pendingSingleClick);
                            pendingSingleClick = null;
                        }
                        android.util.Log.i("ARCH-WEBKEY", "repeat long press -> zoom out");
                        zoom(false);
                    }
                }
                return;
            }

            if (event.getAction() != KeyEvent.ACTION_UP) return;
            confirmPressed = false;

            if (doubleTapConsumed) {
                longPressTriggered = false;
                repeatSeen = false;
                lastTapUpAt = 0L;
                pressFallbackClicked = false;
                pressGestureCancelled = false;
                return;
            }

            // A no-UP fallback already committed the click. A late UP must not
            // then add a zoom-out on top of that click.
            if (pressFallbackClicked || pressGestureCancelled) {
                longPressTriggered = false;
                repeatSeen = false;
                lastTapUpAt = 0L;
                pressFallbackClicked = false;
                pressGestureCancelled = false;
                return;
            }

            long heldMs = Math.max(0L, now - pressDownAt);
            if (longPressTriggered || heldMs >= longPressMs) {
                longPressTriggered = true;
                lastTapUpAt = 0L;
                if (pendingSingleClick != null) {
                    ui.removeCallbacks(pendingSingleClick);
                    pendingSingleClick = null;
                }
                if (!repeatSeen) {
                    android.util.Log.i("ARCH-WEBKEY", "held " + heldMs + "ms -> zoom out");
                    zoom(false);
                }
                return;
            }

            if (pendingSingleClick != null) ui.removeCallbacks(pendingSingleClick);
            lastTapUpAt = now;
            pendingSingleClick = () -> {
                pendingSingleClick = null;
                if (!longPressTriggered && !doubleTapConsumed) {
                    lastTapUpAt = 0L;
                    android.util.Log.i("ARCH-WEBKEY", "single click");
                    clickAt(pressClickX, pressClickY);
                }
            };
            ui.postDelayed(pendingSingleClick, doubleWindowMs);
        }

        private void zoom(boolean zoomIn) {
            try {
                web.evaluateJavascript("typeof window.__archZoom === 'function'", result -> {
                    boolean mapped = result != null && result.contains("true");
                    if (mapped) {
                        try { web.evaluateJavascript("window.__archZoom(" + (zoomIn ? 1 : -1) + ")", null); }
                        catch (Throwable ignored) {}
                    } else if (zoomIn) web.zoomIn();
                    else web.zoomOut();
                });
            } catch (Throwable t) {
                try { if (zoomIn) web.zoomIn(); else web.zoomOut(); } catch (Throwable ignored) {}
            }
        }

        void cancelPending() {
            if (longPressRunnable != null) ui.removeCallbacks(longPressRunnable);
            if (pendingSingleClick != null) ui.removeCallbacks(pendingSingleClick);
            longPressRunnable = null;
            pendingSingleClick = null;
        }

        private void panContent(int dx, int dy) {
            int x = dx * 4;
            int y = dy * 4;
            String js = "(function(){if(window.__archPan){window.__archPan(" + x + "," + y
                    + ");}else{window.scrollBy(" + x + "," + y + ");}})()";
            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        }

        private void applyPosition() {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cursor.getLayoutParams();
            lp.leftMargin = cursorX;
            lp.topMargin = cursorY;
            cursor.setLayoutParams(lp);
            cursor.bringToFront();
        }

        private void clickAt(float x, float y) {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 60L, MotionEvent.ACTION_UP, x, y, 0);
            try { web.dispatchTouchEvent(down); } catch (Throwable ignored) {}
            try { web.dispatchTouchEvent(up); } catch (Throwable ignored) {}
            down.recycle();
            up.recycle();
            cursor.animate().scaleX(1.25f).scaleY(1.25f).setDuration(70L)
                    .withEndAction(() -> cursor.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()).start();
        }
    }

    private String readAssetText(String name) {
        try (InputStream in = getAssets().open(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder(8192);
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString().trim();
        } catch (Throwable t) {
            return "无法读取：" + name;
        }
    }

    private void showAssetTextDialog(String title, String assetName) {
        TextView body = text(readAssetText(assetName), tinyScreen ? 10 : 12, TXT, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(dp(14), dp(10), dp(14), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(d -> scroll.requestFocus());
        dialog.show();
    }

    private View accentChip(String key, String label, boolean selected) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(4), dp(5), dp(4), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cyberTheme && "cyber".equals(key) ? 0xFF220A33 : CARD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(selected ? 2 : 1), selected ? ACCENT : BORDER);
        chip.setBackground(bg);
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor("cyber".equals(key) ? 0xFFFF2BD6 : accentColorFor(key, p.dark()));
        dot.setBackground(dg);
        chip.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView name = text(label, 9, selected ? ACCENT : TXT, selected);
        name.setGravity(Gravity.CENTER);
        chip.addView(name);
        chip.setOnClickListener(v -> {
            p.set("themeAccent", key);
            toast("主题颜色: " + label);
            if (activeSettingsDialog != null) activeSettingsDialog.dismiss();
            reThemeInPlace();
        });
        return chip;
    }

    private void capturePttKey(boolean release, TextView keyNow) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(release ? "绑定松开键" : "绑定 PTT 键")
                .setMessage("请按下要绑定的实体按键")
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && keyCode != KeyEvent.KEYCODE_BACK) {
                String name = KeyEvent.keyCodeToString(keyCode);
                if (release) {
                    p.set("extPttReleaseKeyCode", String.valueOf(keyCode));
                    p.set("extPttReleaseKeyName", name);
                } else {
                    p.set("extPttKeyCode", String.valueOf(keyCode));
                    p.set("extPttKeyName", name);
                    p.set("extPttEnabled", "1");
                }
                keyNow.setText("PTT 键: " + p.externalPttKeyName() + " · 松开键: " + p.externalPttReleaseKeyName());
                toast("已绑定: " + name);
                d.dismiss();
                return true;
            }
            return true;
        });
        dialog.show();
    }

    private void setFocusedTextColors(View view, boolean focused) {
        // 数字/DMR 通话组卡片始终保留自己的高对比文字色。BmTgCard 是
        // ViewGroup，必须在递归前拦截，否则深色模式聚焦时子文字会被刷黑。
        if (Boolean.TRUE.equals(view.getTag(DMR_GROUP_TAG_KEY))) return;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (Boolean.TRUE.equals(textView.getTag(TOP_NAV_TAG_KEY))) return;
            Object saved = textView.getTag(FOCUS_TEXT_COLOR_TAG_KEY);
            if (focused) {
                if (saved == null) textView.setTag(FOCUS_TEXT_COLOR_TAG_KEY, textView.getCurrentTextColor());
                textView.setTextColor(p != null && p.dark() ? Color.WHITE : Color.BLACK);
            } else if (saved instanceof Integer) {
                textView.setTextColor((Integer) saved);
                textView.setTag(FOCUS_TEXT_COLOR_TAG_KEY, null);
            }
            for (android.graphics.drawable.Drawable d : textView.getCompoundDrawables()) {
                if (d != null) d.setColorFilter(focused
                        ? (android.graphics.ColorFilter) new android.graphics.PorterDuffColorFilter(
                            p != null && p.dark() ? Color.WHITE : Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
                        : (android.graphics.ColorFilter) null);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setFocusedTextColors(group.getChildAt(i), focused);
        }
    }

    private View findFirstFocusable(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE || !root.isEnabled()) return null;
        if (root.isFocusable() && root.getVisibility() == View.VISIBLE) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void updateSettingsFocusHint(View focused) {
        if (settingsFocusHint == null || activeSettingsDialog == null || !activeSettingsDialog.isShowing()
                || focused == null || activeSettingsDialog.getWindow() == null) return;
        View dialogRoot = activeSettingsDialog.getWindow().getDecorView();
        if (focused.getRootView() != dialogRoot) return;
        String label = "";
        CharSequence description = focused.getContentDescription();
        if (description != null) label = description.toString();
        if (label.isEmpty() && focused instanceof TextView) {
            CharSequence text = ((TextView) focused).getText();
            if (text != null) label = text.toString();
        }
        if (label.trim().isEmpty()) label = "选项";
        if (label.length() > 16) label = label.substring(0, 16) + "…";
        settingsFocusHint.setText("当前：" + label);
    }

    private void performCompleteExit() {
        if (completeExitInProgress) return;
        completeExitInProgress = true;
        try { if (pttHeld || pttGranted || dmrTxing) pttUp(); } catch (Throwable ignored) {}
        try { p.setUserExited(true); } catch (Throwable ignored) {}
        networkWanted = false;
        networkReceiving = false;
        try { disconnectDmr(false); } catch (Throwable ignored) {}
        try { disconnectBm(); } catch (Throwable ignored) {}
        try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
        try { SipRuntime.get(this).setEnabled(false); } catch (Throwable ignored) {}
        try { if (aprs != null) { aprs.stop(); aprs = null; } } catch (Throwable ignored) {}
        try { if (radio != null) { radio.stop(); radio.release(); } } catch (Throwable ignored) {}
        try { if (audio != null) audio.stopAsync(); } catch (Throwable ignored) {}
        try { HyteraPdc760Bridge.setListener(null); } catch (Throwable ignored) {}
        try { DeviceIndicatorBridge.off(this); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, NetworkKeepAliveService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, RadioPlaybackService.class)); } catch (Throwable ignored) {}
        try { activeActivity = null; } catch (Throwable ignored) {}
        try { if (isFinishing()) { android.os.Process.killProcess(android.os.Process.myPid()); return; } } catch (Throwable ignored) {}
        finishAndRemoveTask();
        ui.postDelayed(() -> {
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
        }, 900L);
    }

    private void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(4), dp(10), dp(16));
        sv.addView(root);

        LinearLayout identity = card();
        LinearLayout accountHead = new LinearLayout(this);
        accountHead.setGravity(Gravity.CENTER_VERTICAL);
        accountHead.addView(text("账号", 13, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView signedIn = text("已登录", 11, GREEN, true);
        signedIn.setPadding(dp(7), dp(2), dp(7), dp(2));
        signedIn.setBackground(roundBg(withAlpha(GREEN, p.dark() ? 52 : 28), 999));
        accountHead.addView(signedIn);
        TextView logout = text("退出", 11, RED, true);
        logout.setGravity(Gravity.CENTER);
        logout.setPadding(dp(9), dp(2), dp(2), dp(2));
        logout.setClickable(true);
        makeFocusable(logout);
        accountHead.addView(logout);
        identity.addView(accountHead);
        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        accountRow.setPadding(0, dp(7), 0, dp(9));
        LinearLayout callCol = new LinearLayout(this);
        callCol.setOrientation(LinearLayout.VERTICAL);
        callCol.addView(text("呼号", 9, SUB, false));
        callCol.addView(text(p.callsign(), 13, TXT, true));
        LinearLayout idCol = new LinearLayout(this);
        idCol.setOrientation(LinearLayout.VERTICAL);
        idCol.addView(text("DMR ID", 9, SUB, false));
        idCol.addView(text(String.valueOf(p.dmrId()), 13, TXT, true));
        LinearLayout essCol = new LinearLayout(this);
        essCol.setOrientation(LinearLayout.VERTICAL);
        essCol.addView(text("ESSID", 9, SUB, false));
        final EditText settingsEssidEd = input("00-99", String.format(Locale.US, "%02d", p.essid()),
                InputType.TYPE_CLASS_NUMBER);
        settingsEssidEd.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(2)});
        settingsEssidEd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(11f, 12f * fontFactor));
        settingsEssidEd.setGravity(Gravity.CENTER);
        settingsEssidEd.setPadding(dp(5), dp(1), dp(5), dp(1));
        settingsEssidEd.setMinHeight(0);
        settingsEssidEd.setMinimumHeight(0);
        settingsEssidEd.setSelectAllOnFocus(true);
        settingsEssidEd.setContentDescription("ESSID");
        essCol.addView(settingsEssidEd, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 31 : 36)));
        accountRow.addView(callCol, new LinearLayout.LayoutParams(0, -2, 1.2f));
        accountRow.addView(idCol, new LinearLayout.LayoutParams(0, -2, 1f));
        accountRow.addView(essCol, new LinearLayout.LayoutParams(0, -2, 0.9f));
        identity.addView(accountRow);
        TextView essidHint = text("ESSID 00-99，同组设备不可重复", 9, SUB, false);
        essidHint.setPadding(0, 0, 0, dp(4));
        identity.addView(essidHint);
        root.addView(identity);

        CheckBox digitalOn = styledCheckBox("数字中继", p.showDigital());
        CheckBox networkOn = styledCheckBox("网络对讲", p.showNetwork());
        CheckBox bmOn = styledCheckBox("DMR 直连", p.dmrDirectEnabled());
        CheckBox sipOn = styledCheckBox("SIP 电话", p.sipEnabled());
        CheckBox aprsOn = styledCheckBox("APRS 位置", p.aprsEnabled());
        CheckBox radioOn = styledCheckBox("网络收音机", p.showNetRadio());
        LinearLayout modesCard = new LinearLayout(this);
        modesCard.setOrientation(LinearLayout.VERTICAL);
        modesCard.addView(text("各项可独立开启或关闭；开启的数字、网络和 DMR 会同时保持连接，是否播放其他模式音频由“全部监听”控制。", 11, SUB, false));
        modesCard.addView(digitalOn); modesCard.addView(networkOn); modesCard.addView(bmOn);
        modesCard.addView(sipOn); modesCard.addView(aprsOn); modesCard.addView(radioOn);
        Button modesSave = button("保存模式开关", true);
        modesSave.setOnClickListener(v -> {
            boolean pageDigital = digitalOn.isChecked();
            boolean pageNetwork = networkOn.isChecked();
            boolean pageBm = bmOn.isChecked();
            boolean pageRadio = radioOn.isChecked();
            p.set("showDigital", pageDigital ? "1" : "0");
            p.set("showNetwork", pageNetwork ? "1" : "0");
            p.set("dmrAuto", "1");
            p.set("autoDigital", pageDigital ? "1" : "0");
            p.set("autoReconnect", "1");
            p.setDmrDirectEnabled(pageBm);
            p.setSipEnabled(sipOn.isChecked());
            p.set("aprsEnabled", aprsOn.isChecked() ? "1" : "0");
            p.set("showNetRadio", pageRadio ? "1" : "0");
            refreshTopNavVisibility();
            refreshTopNavColors();
            SipRuntime runtime = SipRuntime.get(this);
            if (sipOn.isChecked()) runtime.reloadConfig(); else runtime.setEnabled(false);
            if (aprsOn.isChecked()) {
                boolean hasLocation = Build.VERSION.SDK_INT < 23
                        || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (hasLocation) {
                    requestBackgroundLocationForAprs();
                    restartAprs();
                } else {
                    pendingAprsEnable = true;
                    requestNeededPermissions();
                }
            } else {
                pendingAprsEnable = false;
                if (aprs != null) { try { aprs.stop(); } catch (Throwable ignored) {} aprs = null; }
            }
            activateSelectedPageMode();
            syncModeConnections();
            toast("模式开关已保存");
        });
        modesCard.addView(modesSave, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 40 : 42)));
        modesCard.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(shortScreen ? 10 : 6)));
        root.addView(collapsibleSettingsCard("模式启用", modesCard));
        EditText dmrHostEd = input("数字中继服务器", p.dmrHost(), InputType.TYPE_CLASS_TEXT);
        EditText dmrPortEd = input("端口", String.valueOf(p.dmrPort()), InputType.TYPE_CLASS_NUMBER);
        EditText dmrPasswordEd = input("数字中继密码（可留空）", p.dmrPassword(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText dmrTgEd = input("通话组", String.valueOf(p.tg()), InputType.TYPE_CLASS_NUMBER);
        EditText dmrSlotEd = input("时隙（1 或 2）", String.valueOf(p.tgSlot()), InputType.TYPE_CLASS_NUMBER);
        LinearLayout dmrModeCard = new LinearLayout(this);
        dmrModeCard.setOrientation(LinearLayout.VERTICAL);
        dmrModeCard.addView(sipFieldLabel("数字中继服务器"));
        dmrModeCard.addView(dmrHostEd);
        dmrModeCard.addView(sipFieldLabel("端口"));
        dmrModeCard.addView(dmrPortEd);
        dmrModeCard.addView(sipFieldLabel("数字中继密码"));
        dmrModeCard.addView(dmrPasswordEd);
        dmrModeCard.addView(sipFieldLabel("通话组"));
        dmrModeCard.addView(dmrTgEd);
        dmrModeCard.addView(sipFieldLabel("时隙"));
        dmrModeCard.addView(dmrSlotEd);
        Button dmrSave = button("保存并应用数字中继设置", true);
        dmrSave.setOnClickListener(v -> {
            try {
                int port = Integer.parseInt(dmrPortEd.getText().toString().trim());
                int tg = Integer.parseInt(dmrTgEd.getText().toString().trim());
                int slot = Integer.parseInt(dmrSlotEd.getText().toString().trim());
                if (port <= 0 || port > 65535 || tg <= 0 || (slot != 1 && slot != 2))
                    throw new Exception("端口、通话组或时隙无效");
                p.set("showDigital", digitalOn.isChecked() ? "1" : "0");
                p.set("showNetwork", networkOn.isChecked() ? "1" : "0");
                p.setDmrDirectEnabled(bmOn.isChecked());
                p.set("showNetRadio", radioOn.isChecked() ? "1" : "0");
                p.set("dmrAuto", "1");
                p.set("dmrHost", dmrHostEd.getText().toString().trim());
                p.set("dmrPassword", dmrPasswordEd.getText().toString());
                p.set("dmrPort", String.valueOf(port));
                p.rememberDmrTg(tg, slot);
                refreshTopNavVisibility();
                toast("数字中继设置已保存");
                if (activeSettingsDialog != null) activeSettingsDialog.dismiss();
                if (dmrHbp != null) disconnectDmr(false);
                activateSelectedPageMode();
                syncModeConnections();
            } catch (Throwable t) { toast("保存失败：" + t.getMessage()); }
        });
        dmrModeCard.addView(dmrSave, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 34 : 42)));
        root.addView(collapsibleSettingsCard("数字中继", dmrModeCard));
        Button bmServerBtn = button("服务器: " + p.bmServerName(), false);
        bmServerBtn.setOnClickListener(v -> showBmServerPicker());
        LinearLayout bmCard = new LinearLayout(this);
        bmCard.setOrientation(LinearLayout.VERTICAL);
        // Identity and hotspot credentials are account-level values. The DMR
        // card intentionally exposes only the BM master selection.
        bmCard.addView(bmServerBtn);
        root.addView(collapsibleSettingsCard("DMR 直连", bmCard));
        EditText sipServerEd = input("", p.get("sipServer", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText sipPortEd = input("", p.get("sipPort", "5060"), InputType.TYPE_CLASS_NUMBER);
        EditText sipNumberEd = input("", p.get("sipNumber", ""), InputType.TYPE_CLASS_PHONE);
        EditText sipPasswordEd = input("", p.get("sipPassword", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout sipCard = new LinearLayout(this);
        sipCard.setOrientation(LinearLayout.VERTICAL);
        sipCard.addView(sipFieldLabel("服务器地址"));
        sipCard.addView(sipServerEd);
        sipCard.addView(sipFieldLabel("端口"));
        sipCard.addView(sipPortEd);
        sipCard.addView(sipFieldLabel("电话号 / 认证用户名"));
        sipCard.addView(sipNumberEd);
        sipCard.addView(sipFieldLabel("密码"));
        sipCard.addView(sipPasswordEd);
        Button sipSave = button("保存 SIP 设置并打开", false);
        sipSave.setOnClickListener(v -> {
            int sipPort = parseInt(value(sipPortEd), 5060);
            if (sipOn.isChecked()
                    && (sipPort <= 0 || sipPort > 65535 || value(sipServerEd).isEmpty() || value(sipNumberEd).isEmpty())) {
                toast("请填写 SIP 服务器、端口和电话号"); return;
            }
            p.set("sipServer", value(sipServerEd));
            p.set("sipPort", String.valueOf(sipPort));
            p.set("sipNumber", value(sipNumberEd));
            p.set("sipPassword", value(sipPasswordEd));
            p.setSipEnabled(sipOn.isChecked());
            SipRuntime runtime = SipRuntime.get(this);
            refreshTopNavVisibility();
            applySipNavState(sipOn.isChecked() && runtime.isRegistered());
            if (sipOn.isChecked()) {
                runtime.reloadConfig();
                toast("SIP 设置已保存并保持在线");
                openSipPhone();
            } else {
                runtime.setEnabled(false);
                toast("SIP 设置已保存，SIP 已关闭");
            }
        });
        sipCard.addView(sipSave, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(collapsibleSettingsCard("SIP 电话", sipCard));
        EditText aprsCall = input("APRS 呼号", p.aprsCallsign(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText aprsSsid = input("APRS SSID", p.aprsSsid(), InputType.TYPE_CLASS_NUMBER);
        EditText aprsServer = input("APRS-IS 服务器", p.aprsServer(), InputType.TYPE_CLASS_TEXT);
        EditText aprsInterval = input("上传间隔（秒，默认60）", String.valueOf(p.aprsInterval()), InputType.TYPE_CLASS_NUMBER);
        EditText aprsComment = input("APRS 用户备注（第一行）", p.aprsComment(), InputType.TYPE_CLASS_TEXT);
        TextView aprsStateView = text(aprsState, 11, SUB, false);
        LinearLayout aprsCard = new LinearLayout(this);
        aprsCard.setOrientation(LinearLayout.VERTICAL);
        aprsCard.addView(aprsCall); aprsCard.addView(aprsSsid);
        aprsCard.addView(aprsServer); aprsCard.addView(aprsInterval); aprsCard.addView(aprsComment);
        aprsCard.addView(text("第二行自动显示：ARCH业余无线电中枢 arch.hljrsa.cn 机型 实时电压", 9, SUB, false));
        aprsCard.addView(aprsStateView);
        aprsCard.addView(text("地图台站: " + p.aprsCallsign() + "-" + p.aprsSsid(), 9, SUB, false));
        aprsCard.addView(settingLink("打开 APRS.TV 地图", this::openAprsMap));
        LinearLayout aprsSection = collapsibleSettingsCard("APRS", aprsCard);

        CheckBox agc = styledCheckBox("自动增益 AGC", p.agc());
        TextView gainMode = text(p.agc() ? "自动" : "手动", 10, ACCENT, true);
        gainMode.setGravity(Gravity.END);
        LinearLayout agcHead = new LinearLayout(this);
        agcHead.setGravity(Gravity.CENTER_VERTICAL);
        agcHead.addView(agc, new LinearLayout.LayoutParams(0, -2, 1));
        agcHead.addView(gainMode);

        LinearLayout txHead = new LinearLayout(this);
        txHead.setGravity(Gravity.CENTER_VERTICAL);
        txHead.addView(text("麦克风增益", 11, SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView txGainValue = text(formatSignedDb(p.txGainDb()), 11, ACCENT, true);
        txGainValue.setGravity(Gravity.END);
        txHead.addView(txGainValue);
        SeekBar txGainLevel = new SeekBar(this);
        txGainLevel.setMax(40);
        txGainLevel.setProgress(p.txGainDb() + 20);
        txGainLevel.setKeyProgressIncrement(1);
        txGainLevel.setContentDescription("麦克风增益");
        makeFocusable(txGainLevel);

        LinearLayout rxHead = new LinearLayout(this);
        rxHead.setGravity(Gravity.CENTER_VERTICAL);
        rxHead.addView(text("扬声器增益", 11, SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView rxGainValue = text(formatSignedDb(p.rxGainDb()), 11, ACCENT, true);
        rxGainValue.setGravity(Gravity.END);
        rxHead.addView(rxGainValue);
        SeekBar rxGainLevel = new SeekBar(this);
        rxGainLevel.setMax(40);
        rxGainLevel.setProgress(p.rxGainDb() + 20);
        rxGainLevel.setKeyProgressIncrement(1);
        rxGainLevel.setContentDescription("扬声器增益");
        makeFocusable(rxGainLevel);

        Runnable refreshGainControls = () -> {
            boolean auto = agc.isChecked();
            gainMode.setText(auto ? "自动" : "手动");
            txGainLevel.setEnabled(!auto);
            rxGainLevel.setEnabled(!auto);
            txGainLevel.setAlpha(auto ? 0.38f : 1f);
            rxGainLevel.setAlpha(auto ? 0.38f : 1f);
            txGainValue.setText(auto ? "自动" : formatSignedDb(txGainLevel.getProgress() - 20));
            rxGainValue.setText(auto ? "自动" : formatSignedDb(rxGainLevel.getProgress() - 20));
        };
        txGainLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (agc.isChecked()) return;
                txGainValue.setText(formatSignedDb(progress - 20));
                if (fromUser && audio != null) audio.setTxGain(dbToGain(progress - 20));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        rxGainLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (agc.isChecked()) return;
                rxGainValue.setText(formatSignedDb(progress - 20));
                if (fromUser && audio != null) audio.setRxGain(dbToGain(progress - 20)
                        * (hardwareProfile != null && hardwareProfile.isP50Pro()
                            ? NetworkAudioProfile.rxTrim(hardwareProfile) : 1f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        agc.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (audio != null) audio.setAgc(isChecked, p.agcDb());
            refreshGainControls.run();
        });
        refreshGainControls.run();
        CheckBox dark = styledCheckBox("深色主题", p.dark());
        CheckBox bootAutoStart = styledCheckBox("开机自启", p.bootAutoStart());
        CheckBox keep = styledCheckBox("屏幕常亮（已停用，跟随系统休眠）", false);
        keep.setEnabled(false);
        CheckBox extPtt = styledCheckBox("实体 PTT", p.externalPttEnabled());
        final String[] routeKeys = {"speaker", "earpiece", "bluetooth"};
        final String[] routeLabels = {"扬声器", "听筒", "蓝牙"};
        RadioGroup routeGroup = new RadioGroup(this);
        routeGroup.setOrientation(tinyScreen ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        for (int i = 0; i < routeKeys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(routeLabels[i]); styleRadio(rb); rb.setId(FONT_GROUP_ID_BASE + 20 + i);
            if (routeKeys[i].equals(p.audioRoute())) rb.setChecked(true);
            routeGroup.addView(rb);
        }
        final String[] pttModes = {"hold", "toggle"};
        final String[] pttModeLabels = {"按住发射", "点按开关"};
        RadioGroup pttModeGroup = new RadioGroup(this);
        pttModeGroup.setOrientation(tinyScreen ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        for (int i = 0; i < pttModes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(pttModeLabels[i]); styleRadio(rb); rb.setId(FONT_GROUP_ID_BASE + 30 + i);
            if (pttModes[i].equals(p.pttMode())) rb.setChecked(true);
            pttModeGroup.addView(rb);
        }
        CheckBox pttToneOn = styledCheckBox("PTT 提示音", p.pttTone());
        CheckBox multiListen = styledCheckBox("全部监听", p.multiListen());
        CheckBox extBackground = styledCheckBox("实体 PTT 后台生效", p.externalPttBackground());
        CheckBox a11yFallback = styledCheckBox("无障碍按键兼容", p.externalPttA11yFallback());
        EditText pttTimeoutInput = input("30-600", String.valueOf(p.pttTimeout()), InputType.TYPE_CLASS_NUMBER);
        EditText broadcastMap = new EditText(this);
        broadcastMap.setText(p.extBroadcastMap());
        broadcastMap.setHint("自定义PTT广播，每行：D|action|发送包名（Android 14+ 第三方发送者必须填写包名）");
        broadcastMap.setHintTextColor(SUB); broadcastMap.setTextColor(TXT); broadcastMap.setTextSize(Math.max(10f, 12 * fontFactor));
        broadcastMap.setMinLines(3); broadcastMap.setGravity(Gravity.TOP | Gravity.START);
        GradientDrawable mapBg = new GradientDrawable();
        mapBg.setColor(p.dark() ? 0xFF121B27 : 0xFFF5F8FC);
        mapBg.setCornerRadius(dp(12));
        mapBg.setStroke(dp(1), p.dark() ? 0xFF263445 : 0xFFE2E9F2);
        broadcastMap.setBackground(mapBg);
        broadcastMap.setPadding(dp(12), dp(8), dp(12), dp(8));
        makeFocusable(broadcastMap);
        final String oldAccent = accentKey();
        final String oldFont = p.uiFontMode();
        final boolean oldDark = p.dark();
        final String oldDmrSignature = dmrConnectionSignature();
        final String oldBmSignature = bmConnectionSignature();
        final String oldNetworkSignature = networkConnectionSignature();

        LinearLayout basics = new LinearLayout(this);
        basics.setOrientation(LinearLayout.VERTICAL);
        basics.addView(agcHead);
        basics.addView(txHead);
        basics.addView(txGainLevel);
        basics.addView(rxHead);
        basics.addView(rxGainLevel);
        basics.addView(dark);
        basics.addView(bootAutoStart);
        basics.addView(keep);
        if (!BuildConfig.HYTERA_EDITION) basics.addView(extPtt);

        TextView accentTitle = text("主题颜色", 13, SUB, false);
        accentTitle.setPadding(0, dp(8), 0, dp(3));
        basics.addView(accentTitle);
        String currentAccent = accentKey();
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < 3; col++) {
                int idx = rowIndex * 3 + col;
                View chip = accentChip(ACCENT_KEYS[idx], ACCENT_LABELS[idx], ACCENT_KEYS[idx].equals(currentAccent));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(chip, lp);
            }
            basics.addView(row);
        }

        TextView fontTitle = text("界面字号", 12, SUB, false);
        basics.addView(fontTitle);
        final String[] fk = {"normal", "large", "xlarge", "xxlarge", "xxxlarge"};
        final String[] fl = {"标准", "较大", "大", "特大", "超大"};
        String curFont = p.uiFontMode();
        int initialFontIndex = 0;
        for (int i = 0; i < fk.length; i++) if (fk[i].equals(curFont)) { initialFontIndex = i; break; }
        TextView fontValue = text(fl[initialFontIndex], 12, ACCENT, true);
        fontValue.setGravity(Gravity.END);
        basics.addView(fontValue);
        SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax(fl.length - 1);
        fontSeek.setProgress(initialFontIndex);
        fontSeek.setKeyProgressIncrement(1);
        fontSeek.setContentDescription("界面字号");
        makeFocusable(fontSeek);
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fontValue.setText(fl[Math.max(0, Math.min(fl.length - 1, progress))]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        basics.addView(fontSeek);
        LinearLayout fontRange = new LinearLayout(this);
        fontRange.setGravity(Gravity.CENTER_VERTICAL);
        fontRange.addView(text("标准", 9, SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView fontMax = text("超大", 9, SUB, false);
        fontMax.setGravity(Gravity.END);
        fontRange.addView(fontMax, new LinearLayout.LayoutParams(0, -2, 1));
        basics.addView(fontRange);

        View basicsCard = collapsibleSettingsCard("基础设置", basics);
        root.addView(basicsCard);

        LinearLayout pttCard = new LinearLayout(this);
        pttCard.setOrientation(LinearLayout.VERTICAL);
        pttCard.addView(text("网络语音编码", 11, SUB, false));
        pttCard.addView(text("Opus · 24 kHz · 24 kbps · 20 ms", 10, ACCENT, true));
        pttCard.addView(text("音频输出", 12, SUB, false));
        pttCard.addView(routeGroup);
        pttCard.addView(multiListen);
        pttCard.addView(text("默认只播放当前模式；开启后可同时监听其他已连接模式。", 10, SUB, false));
        pttCard.addView(text("屏幕 PTT 方式", 12, SUB, false));
        pttCard.addView(pttModeGroup);
        pttCard.addView(pttToneOn);
        if (!BuildConfig.HYTERA_EDITION) {
            pttCard.addView(extBackground);
            pttCard.addView(a11yFallback);
        }
        pttCard.addView(text("PTT 超时（秒）", 11, SUB, false));
        pttCard.addView(pttTimeoutInput);
        if (!BuildConfig.HYTERA_EDITION) {
            TextView keyNow = text("PTT 键: " + p.externalPttKeyName() + " · 松开键: " + p.externalPttReleaseKeyName(), 11, SUB, false);
            pttCard.addView(keyNow);
            LinearLayout keyRow = new LinearLayout(this);
            Button bindPtt = button("绑定PTT键", false), bindUp = button("绑定松开键", false), clearKeys = button("清除", false);
            bindPtt.setOnClickListener(v -> capturePttKey(false, keyNow));
            bindUp.setOnClickListener(v -> capturePttKey(true, keyNow));
            clearKeys.setOnClickListener(v -> {
                p.set("extPttKeyCode", "0"); p.set("extPttKeyName", "未设置");
                p.set("extPttReleaseKeyCode", "0"); p.set("extPttReleaseKeyName", "未设置");
                keyNow.setText("PTT 键: 未设置 · 松开键: 未设置");
            });
            keyRow.addView(bindPtt, new LinearLayout.LayoutParams(0, dp(42), 1));
            keyRow.addView(bindUp, new LinearLayout.LayoutParams(0, dp(42), 1));
            keyRow.addView(clearKeys, new LinearLayout.LayoutParams(0, dp(42), 1));
            pttCard.addView(keyRow);
            Button a11y = button("打开无障碍设置", false);
            a11y.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Throwable ignored) {} });
            pttCard.addView(a11y);
            pttCard.addView(text("自定义厂商 PTT 广播", 12, SUB, false));
            pttCard.addView(broadcastMap);
        }
        root.addView(collapsibleSettingsCard("PTT 与音频路由", pttCard));
        root.addView(aprsSection);

        LinearLayout legalCard = new LinearLayout(this);
        legalCard.setOrientation(LinearLayout.VERTICAL);
        legalCard.addView(text("版本：" + BuildConfig.DISPLAY_VERSION_LABEL, 11, TXT, true));
        legalCard.addView(text("类型：" + BuildConfig.APP_EDITION_LABEL, 10, SUB, false));
        TextView authorView = text("项目作者：BG2GA", 10, SUB, false);
        authorView.setPadding(0, dp(5), 0, dp(3));
        legalCard.addView(authorView);
        legalCard.addView(settingLink("联系作者：bg2ga@by2aa.org.cn",
                () -> openExternal("mailto:bg2ga@by2aa.org.cn", "没有可用的邮件应用")));
        legalCard.addView(settingLink("项目主页：arch.hljrsa.cn",
                () -> openExternal("https://arch.hljrsa.cn", "没有可用的浏览器")));
        legalCard.addView(settingLink("开源协议",
                () -> showAssetTextDialog("开源协议", "licenses/ARCH_LICENSE_ZH.txt")));
        legalCard.addView(settingLink("第三方组件与许可证",
                () -> showAssetTextDialog("第三方组件与许可证", "licenses/THIRD_PARTY_LICENSES_ZH.txt")));
        legalCard.addView(settingLink("免责声明",
                () -> showAssetTextDialog("免责声明", "disclaimer.txt")));
        root.addView(collapsibleSettingsCard("关于与法律", legalCard));
        // Requested order: account, basics, modes, digital, DMR direct, SIP, APRS, PTT, legal, update.
        root.removeView(basicsCard);
        root.addView(basicsCard, 1);

        Button update = button("检查更新", false);
        update.setIncludeFontPadding(false);
        update.setPadding(dp(10), dp(4), dp(10), dp(4));
        update.setOnClickListener(v -> new Updater(this).check(false, null));
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(-1, dp(shortScreen ? 44 : 48));
        updateLp.setMargins(dp(2), 0, dp(2), dp(6));
        root.addView(update, updateLp);

        Button exitApp = button("退出 APP（完全停止后台）", false);
        exitApp.setIncludeFontPadding(false);
        exitApp.setTextColor(RED);
        exitApp.setPadding(dp(10), dp(4), dp(10), dp(4));
        exitApp.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("退出 APP")
                .setMessage("退出后将断开数字中继、网络对讲、DMR、SIP、APRS 和收音机，并停止全部后台服务。下次使用需要手动打开 APP。")
                .setNegativeButton("取消", null)
                .setPositiveButton("完全退出", (d, w) -> performCompleteExit())
                .show());
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-1, dp(shortScreen ? 44 : 48));
        exitLp.setMargins(dp(2), dp(2), dp(2), dp(12));
        root.addView(exitApp, exitLp);

        installKeyNavigation(root);
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        activeSettingsDialog = dialog;
        dialog.setOnDismissListener(d -> { activeSettingsDialog = null; settingsFocusHint = null; });
        dark.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!dialog.isShowing()) return;
            p.setDark(isChecked);
            toast(isChecked ? "已切换深色主题" : "已切换浅色主题");
            dialog.dismiss();
            reThemeInPlace();
        });
        keep.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!dialog.isShowing()) return;
            p.set("keepScreen", isChecked ? "1" : "0");
            applyKeepScreenOn();
        });
        dialog.setOnShowListener(d -> {
            try {
                android.view.Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(BG));
                    window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
            } catch (Throwable ignored) {}
            if (logout != null) logout.requestFocus();
        });
        Button apply = button("应用并保存全部设置", true);
        apply.setOnClickListener(v -> {
            int dport;
            int dtg;
            int dslot;
            int dessid;
            try {
                dport = Integer.parseInt(value(dmrPortEd));
                dtg = Integer.parseInt(value(dmrTgEd));
                dslot = Integer.parseInt(value(dmrSlotEd));
                dessid = Integer.parseInt(value(settingsEssidEd));
                if (dport <= 0 || dport > 65535 || dtg <= 0 || (dslot != 1 && dslot != 2)
                        || dessid < 0 || dessid > 99)
                    throw new Exception();
            } catch (Throwable t) {
                toast("数字中继端口、通话组或 ESSID 无效");
                return;
            }
            java.util.LinkedHashMap<String, String> batch = new java.util.LinkedHashMap<>();
            batch.put("showDigital", digitalOn.isChecked() ? "1" : "0");
            batch.put("showNetwork", networkOn.isChecked() ? "1" : "0");
            batch.put("dmrAuto", "1");
            batch.put("autoDigital", digitalOn.isChecked() ? "1" : "0");
            batch.put("autoReconnect", "1");
            batch.put("bootAutoStart", bootAutoStart.isChecked() ? "1" : "0");
            batch.put("showBm", bmOn.isChecked() ? "1" : "0");
            batch.put("showNetRadio", radioOn.isChecked() ? "1" : "0");
            batch.put("dmrHost", value(dmrHostEd));
            batch.put("dmrPassword", value(dmrPasswordEd));
            batch.put("dmrPort", String.valueOf(dport));
            batch.put("tg", String.valueOf(dtg));
            batch.put("tgSlot", dslot == 2 ? "2" : "1");
            batch.put("essid", String.format(Locale.US, "%02d", dessid));
            batch.put("sipServer", value(sipServerEd));
            batch.put("sipPort", String.valueOf(Math.max(1, Math.min(65535, parseInt(value(sipPortEd), 5060)))));
            batch.put("sipNumber", value(sipNumberEd));
            batch.put("sipPassword", value(sipPasswordEd));
            batch.put("sipEnabled", sipOn.isChecked() ? "1" : "0");
            batch.put("aprsEnabled", aprsOn.isChecked() ? "1" : "0");
            batch.put("aprsCallsign", value(aprsCall));
            batch.put("aprsSsid", value(aprsSsid));
            batch.put("aprsServer", value(aprsServer));
            batch.put("aprsInterval", value(aprsInterval));
            batch.put("aprsComment", value(aprsComment));
            batch.put("rxDb", String.valueOf(rxGainLevel.getProgress() - 20));
            batch.put("txDb", String.valueOf(txGainLevel.getProgress() - 20));
            batch.put("agc", agc.isChecked() ? "1" : "0");
            batch.put("multiListen", multiListen.isChecked() ? "1" : "0");
            batch.put("agcDb", String.valueOf(p.agcDb()));
            batch.put("keepScreen", keep.isChecked() ? "1" : "0");
            batch.put("extPttEnabled", BuildConfig.HYTERA_EDITION ? "1" : (extPtt.isChecked() ? "1" : "0"));
            batch.put("extPttBackground", extBackground.isChecked() ? "1" : "0");
            batch.put("extPttA11yFallback", a11yFallback.isChecked() ? "1" : "0");
            batch.put("pttTone", pttToneOn.isChecked() ? "1" : "0");
            batch.put("pttTimeout", value(pttTimeoutInput));
            batch.put("extBroadcastMap", value(broadcastMap));
            batch.put("theme", dark.isChecked() ? "dark" : "light");
            batch.put("dark", dark.isChecked() ? "1" : "0");
            int routeId = routeGroup.getCheckedRadioButtonId() - (FONT_GROUP_ID_BASE + 20);
            if (routeId >= 0 && routeId < routeKeys.length) batch.put("audioRoute", routeKeys[routeId]);
            int pttModeId = pttModeGroup.getCheckedRadioButtonId() - (FONT_GROUP_ID_BASE + 30);
            if (pttModeId >= 0 && pttModeId < pttModes.length) batch.put("pttMode", pttModes[pttModeId]);
            int fontIdx = fontSeek.getProgress();
            if (fontIdx >= 0 && fontIdx < fk.length) batch.put("uiFontMode", fk[fontIdx]);
            p.setBatch(batch);
            refreshTopNavVisibility();
            refreshTopNavColors();
            SipRuntime sipRuntime = SipRuntime.get(this);
            if (sipOn.isChecked()) sipRuntime.reloadConfig(); else sipRuntime.setEnabled(false);
            boolean dmrConfigChanged = !oldDmrSignature.equals(dmrConnectionSignature());
            boolean bmConfigChanged = !oldBmSignature.equals(bmConnectionSignature());
            boolean networkConfigChanged = !oldNetworkSignature.equals(networkConnectionSignature());
            if (dmrConfigChanged && dmrHbp != null) disconnectDmr(false);
            if (bmConfigChanged && bmHbp != null) disconnectBm();
            if (networkConfigChanged && net != null) {
                networkWanted = false;
                networkReceiving = false;
                try { HyteraPdc760Bridge.noteNetworkReceive(MainActivity.this, false, ""); } catch (Throwable ignored) {}
                clearModeSignal(MODE_SIGNAL_NETWORK);
                try { NetworkRuntime.get(this).disconnect(); } catch (Throwable ignored) {}
                flushReceivePlayback();
            }
            applyAudioPrefs();
            if (aprsOn.isChecked()) restartAprs();
            else if (aprs != null) { try { aprs.stop(); } catch (Throwable ignored) {} aprs = null; }
            boolean themeChanged = oldDark != p.dark() || !oldAccent.equals(accentKey()) || !oldFont.equals(p.uiFontMode());
            toast("设置已保存");
            dialog.dismiss();
            activateSelectedPageMode();
            syncModeConnections();
            if (!p.multiListen()) flushNonCurrentModeAudio();
            applyKeepScreenOn();
            applyAudioRoute(true);
            syncVolumeControlStream();
            if (themeChanged) reThemeInPlace();
        });
        logout.setOnClickListener(v -> { dialog.dismiss(); confirmLogout(); });

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        LinearLayout settingsBar = new LinearLayout(this);
        settingsBar.setGravity(Gravity.CENTER_VERTICAL);
        settingsBar.setPadding(dp(10), dp(shortScreen ? 4 : 8), dp(10), dp(shortScreen ? 4 : 8));
        settingsBar.setBackgroundColor(CARD);
        TextView close = text("‹", shortScreen ? 24 : 28, TXT, true);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setContentDescription("关闭设置");
        makeFocusable(close);
        close.setOnClickListener(v -> dialog.dismiss());
        settingsBar.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout settingsTitleBox = new LinearLayout(this);
        settingsTitleBox.setOrientation(LinearLayout.VERTICAL);
        settingsTitleBox.setGravity(Gravity.CENTER);
        TextView settingsTitle = text("设置", shortScreen ? 14 : 18, TXT, true);
        settingsTitle.setGravity(Gravity.CENTER);
        settingsTitle.setSingleLine(true);
        settingsTitleBox.addView(settingsTitle, new LinearLayout.LayoutParams(-1, -2));
        settingsFocusHint = null;
        settingsBar.addView(settingsTitleBox, new LinearLayout.LayoutParams(0, -2, 1f));
        View titleSpacer = new View(this);
        settingsBar.addView(titleSpacer, new LinearLayout.LayoutParams(dp(38), dp(1)));
        shell.addView(settingsBar);
        shell.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        shell.addView(apply, new LinearLayout.LayoutParams(-1, dp(shortScreen ? 38 : 46)));
        LinearLayout.LayoutParams applyLp = (LinearLayout.LayoutParams) apply.getLayoutParams();
        applyLp.setMargins(dp(10), dp(5), dp(10), dp(7));
        apply.setLayoutParams(applyLp);
        installKeyNavigation(shell);
        scaleTextSizes(shell, shortScreen ? 1.05f : 1.12f);
        dialog.setContentView(shell);
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (isPnc370DedicatedInput() && isPnc370ConfirmEvent(keyCode)) {
                View focused = dialog.getCurrentFocus();
                if (focused == null && dialog.getWindow() != null) {
                    focused = dialog.getWindow().getDecorView().findFocus();
                }
                return handlePnc370ConfirmEvent(event, focused);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && isConfirmActivationKey(keyCode)) {
                View focused = dialog.getCurrentFocus();
                if (focused != null && focused.isClickable()) {
                    focused.performClick();
                    return true;
                }
            }
            return false;
        });
        dialog.show();
        close.post(close::requestFocus);
    }

    private void saveIdentityFields(EditText cs, EditText id, EditText es, EditText pw) {
        p.saveUnifiedIdentity(value(cs).toUpperCase(Locale.US), parseInt(value(id),0),
                parseInt(value(es),0), value(pw), "", 0);
    }

    private void submitIdentity(String action, EditText cs, EditText id, EditText es, EditText pw, TextView status) {
        String callsign = value(cs).toUpperCase(Locale.US);
        int dmrId = parseInt(value(id), 0);
        int essid = parseInt(value(es), 0);
        String password = value(pw);
        if (callsign.length() < 3 || dmrId <= 0 || password.length() < 6) {
            toast("请填写呼号、7位DMR ID和至少6位密码"); return;
        }
        status.setText("身份状态: 正在" + ("login".equals(action) ? "恢复…" : "注册…"));
        final String reqCallsign = callsign;
        final int reqDmrId = dmrId;
        final int reqEssid = essid;
        final String reqPassword = password;
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("callsign", reqCallsign); req.put("dmrId", reqDmrId);
                req.put("essid", reqEssid); req.put("password", reqPassword);
                JSONObject out = postAccount(action, req);
                JSONObject account = out.optJSONObject("account");
                String resultCallsign = reqCallsign;
                int resultDmrId = reqDmrId;
                int resultEssid = reqEssid;
                String resultBmPassword = p.bmPassword();
                int resultNxdnId = p.nxdnId();
                if (account != null) {
                    resultCallsign = account.optString("callsign", reqCallsign);
                    resultDmrId = account.optInt("dmrId", reqDmrId);
                    resultEssid = account.optInt("essid", reqEssid);
                    resultBmPassword = account.optString("bmPassword", resultBmPassword);
                    resultNxdnId = account.optInt("nxdnId", resultNxdnId);
                }
                final String fcs = resultCallsign; final int fid = resultDmrId; final int fes = resultEssid;
                final String fBmPassword = resultBmPassword; final int fNxdnId = resultNxdnId;
                ui.post(() -> {
                    p.saveUnifiedIdentity(fcs, fid, fes, password, fBmPassword, fNxdnId);
                    status.setText("身份状态: 成功，已保存");
                    toast("身份已保存");
                    loadRooms();
                });
            } catch (Exception e) {
                ui.post(() -> { status.setText("身份状态: 失败 · " + e.getMessage()); toast("失败: " + e.getMessage()); });
            }
        }, "identity-" + action).start();
    }

    private JSONObject postAccount(String action, JSONObject body) throws Exception {
        HttpURLConnection c = TlsCompat.open(this, new URL(ACCOUNT_API + action));
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "ARCH-Network/" + BuildConfig.VERSION_NAME);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject out = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
            if (code >= 400 || !out.optBoolean("ok", false))
                throw new Exception(out.optString("error", "HTTP " + code));
            return out;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void restartAprs() {
        if (aprs != null) { try { aprs.stop(); } catch (Throwable ignored) {} aprs = null; }
        if (!p.aprsEnabled() || p.aprsCallsign().isEmpty()) { aprsState = "APRS: 未启动"; refreshAprsButton(); return; }
        NetworkKeepAliveService.ensureLocationForeground(this);
        aprs = new AprsClient(this, p, msg -> ui.post(() -> aprsState = "APRS: " + msg));
        aprs.start();
        aprsState = aprs.isConnected() ? "APRS: 已连接" : "APRS: 正在连接";
        refreshAprsButton();
    }

    private String value(EditText e) { return e == null ? "" : e.getText().toString().trim(); }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private boolean isPnc370ConfirmEvent(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SOFT_LEFT
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private boolean handlePnc370ConfirmEvent(KeyEvent event, View focused) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            pnc370ConfirmDownSeen = true;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long now = android.os.SystemClock.uptimeMillis();
            if (pnc370ConfirmDownSeen && now - pnc370LastConfirmActionAt > 300L) {
                pnc370LastConfirmActionAt = now;
                if (focused instanceof EditText) showSoftKeyboard((EditText) focused);
                else if (focused != null && focused.isClickable()) focused.performClick();
                else if (focused != null && focused.getParent() instanceof View) {
                    View parent = (View) focused.getParent();
                    for (int i = 0; i < 4 && parent != null; i++) {
                        if (parent.isClickable()) { parent.performClick(); break; }
                        parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
                    }
                }
            }
            pnc370ConfirmDownSeen = false;
            return true;
        }
        return true;
    }

    private boolean isDeviceConfirmKey(int keyCode) {
        // P50 Pro uses F2 as the raw PTT key. Treating F2 as a generic
        // confirm key makes a PTT press also click the focused control.
        // Its physical confirm key is ENTER.
        if (hardwareProfile != null && hardwareProfile.isP50Pro()) {
            return keyCode == KeyEvent.KEYCODE_ENTER;
        }
        return keyCode == KeyEvent.KEYCODE_SOFT_LEFT
                || keyCode == KeyEvent.KEYCODE_F1
                || keyCode == KeyEvent.KEYCODE_F2
                || keyCode == KeyEvent.KEYCODE_F3;
    }

    private boolean isConfirmActivationKey(int keyCode) {
        return isDeviceConfirmKey(keyCode)
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void adjustAppVolume(int direction) {
        try {
            AudioManager am = audioManager != null
                    ? audioManager : (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int stream = appVolumeStream();
            int before = am.getStreamVolume(stream);
            int max = am.getStreamMaxVolume(stream);
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI);
            int after = am.getStreamVolume(stream);
            if (max > 0) applyCurrentOutputVolume(stream, after / (float)max);
            android.util.Log.i("ARCH-VOL", "app dir=" + direction + " stream=" + stream
                    + " value=" + before + "->" + after + "/" + max);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-VOL", "app adjust failed: " + t);
        }
    }

    private void applyCurrentOutputVolume(int stream, float volume) {
        float value = Math.max(0f, Math.min(1f, volume));
        if (audio != null && audio.getStreamType() == stream) audio.setOutputVolume(value);
        if (dmrAudio != null && dmrAudio.getStreamType() == stream) dmrAudio.setOutputVolume(value);
        if (bmAudio != null && bmAudio.getStreamType() == stream) bmAudio.setOutputVolume(value);
        android.util.Log.i("ARCH-VOL", "app output stream=" + stream + " volume=" + value);
    }

    private int appVolumeStream() {        int stream = AudioManager.STREAM_MUSIC;
        String mode = "idle";
        try {
            if (radioPage) {
                mode = "radio";
                stream = AudioManager.STREAM_MUSIC;
            } else if (digitalPage && dmrAudio != null) {
                mode = "digital";
                stream = dmrAudio.getStreamType();
            } else if (bmPage && bmAudio != null) {
                mode = "dmr";
                stream = bmAudio.getStreamType();
            } else if (netPage && audio != null) {
                mode = "network";
                stream = audio.getStreamType();
            } else if (digitalPage) {
                mode = "digital";
                stream = preferredAudioStreamType();
            } else if (bmPage) {
                mode = "dmr";
                stream = preferredAudioStreamType();
            } else if (netPage) {
                mode = "network";
                stream = networkAudioStreamType();
            }
        } catch (Throwable ignored) {}
        if (stream != AudioManager.STREAM_MUSIC && stream != AudioManager.STREAM_VOICE_CALL) {
            stream = AudioManager.STREAM_MUSIC;
        }
        setVolumeControlStream(stream);
        ArchAppState.setActiveVolumeContext(mode, stream);
        return stream;
    }

    private void syncVolumeControlStream() {
        appVolumeStream();
    }

    private void finishGroupConfirmGesture() {
        if (roomConfirmLongPressAction != null) {
            ui.removeCallbacks(roomConfirmLongPressAction);
            roomConfirmLongPressAction = null;
        }
        groupConfirmGestureActive = false;
        groupConfirmGestureKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        groupConfirmGestureTarget = null;
        roomConfirmLongPressed = false;
    }

    /**
     * Keep every repeat/up event of the physical confirm key inside one gesture.
     * Locking rebuilds the talk-group cards and therefore changes focus while the
     * key is still held. Without this latch those trailing events were delivered
     * to the newly focused top-bar mode button and switched pages.
     */
    private boolean consumeGroupConfirmGesture(KeyEvent event) {
        if (!groupConfirmGestureActive || event.getKeyCode() != groupConfirmGestureKeyCode) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) return true;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (roomConfirmLongPressAction != null) {
                ui.removeCallbacks(roomConfirmLongPressAction);
                roomConfirmLongPressAction = null;
            }
            boolean click = !roomConfirmLongPressed;
            View target = groupConfirmGestureTarget;
            finishGroupConfirmGesture();
            if (click && target != null && target.isAttachedToWindow() && target.isClickable()) {
                target.performClick();
            }
            return true;
        }
        return true;
    }

    private boolean handleModeConfirmLongPress(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        if (hardwareProfile != null && hardwareProfile.isP50Pro()
                && (key == KeyEvent.KEYCODE_F2 || key == KeyEvent.KEYCODE_F12)) return false;
        if (key != KeyEvent.KEYCODE_SOFT_LEFT && key != KeyEvent.KEYCODE_F1
                && key != KeyEvent.KEYCODE_F2 && key != KeyEvent.KEYCODE_F3
                && key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;

        // Consume repeats even after the focused card has been rebuilt.
        if (consumeGroupConfirmGesture(event)) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return false;

        View focused = getCurrentFocus();
        final int mode;
        final int tgValue;
        final String roomId;
        if (netPage) {
            int index = roomFocusViews.indexOf(focused);
            if (index < 0 || index >= roomFocusIds.size()) return false;
            mode = 1; tgValue = 0; roomId = roomFocusIds.get(index);
        } else if (digitalPage) {
            int index = dmrTgButtons.indexOf(focused);
            if (index < 0 || index >= dmrTgValues.size()) return false;
            mode = 2; tgValue = dmrTgValues.get(index); roomId = null;
        } else if (bmPage) {
            int index = bmTgButtons.indexOf(focused);
            if (index < 0 || index >= bmTgValues.size()) return false;
            mode = 3; tgValue = bmTgValues.get(index); roomId = null;
        } else {
            return false;
        }

        if (roomConfirmLongPressAction != null) ui.removeCallbacks(roomConfirmLongPressAction);
        roomConfirmLongPressed = false;
        groupConfirmGestureActive = true;
        groupConfirmGestureKeyCode = key;
        groupConfirmGestureTarget = focused;
        final View expectedFocus = focused;
        roomConfirmLongPressAction = () -> {
            if (getCurrentFocus() != expectedFocus) return;
            roomConfirmLongPressed = true;
            try { expectedFocus.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) {}
            android.util.Log.i("ARCH-GROUP-LOCK", "long-press mode=" + mode
                    + " room=" + roomId + " tg=" + tgValue);
            if (mode == 1) toggleRoomLock(roomId);
            else if (mode == 2) toggleDigitalTgLock(tgValue);
            else toggleBmTgLock(tgValue);
        };
        ui.postDelayed(roomConfirmLongPressAction, 700L);
        return true;
    }

    /**
     * A locked talk group may not move to another group, but the user must still
     * be able to leave the group grid and reach the top mode bar/actions.
     */
    private boolean handleLockedGroupNavigation(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT
                && key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        View focused = getCurrentFocus();

        if (digitalPage && lockedDigitalTg > 0
                && focused == dmrIconButton && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusDigitalTgButton(lockedDigitalTg);
            return true;
        }
        if (bmPage && lockedBmTg > 0
                && focused == bmIconButton && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusBmTalkgroup(lockedBmTg);
            return true;
        }
        if (bmPage && lockedBmTg > 0
                && (focused == bmAddButton || focused == bmManageButton)) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                focusBmTalkgroup(lockedBmTg);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && bmAddButton != null && bmAddButton.requestFocus()) {
                ensureFocusVisible(bmAddButton);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && bmManageButton != null && bmManageButton.requestFocus()) {
                ensureFocusVisible(bmManageButton);
                return true;
            }
        }

        if (digitalPage && lockedDigitalTg > 0 && dmrTgButtons.contains(focused)) {
            if (key == KeyEvent.KEYCODE_DPAD_UP && dmrIconButton != null && dmrIconButton.requestFocus()) {
                ensureFocusVisible(dmrIconButton);
                return true;
            }
            focusDigitalTgButton(lockedDigitalTg);
            return true;
        }

        if (bmPage && lockedBmTg > 0 && bmTgButtons.contains(focused)) {
            if (key == KeyEvent.KEYCODE_DPAD_UP && bmIconButton != null && bmIconButton.requestFocus()) {
                ensureFocusVisible(bmIconButton);
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                View action = bmAddButton != null ? bmAddButton : bmManageButton;
                if (action != null && action.requestFocus()) {
                    ensureFocusVisible(action);
                    return true;
                }
            }
            focusBmTalkgroup(lockedBmTg);
            return true;
        }
        return false;
    }


    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        android.util.Log.i("ARCH-KEY", "dispatch action=" + event.getAction() + " code=" + event.getKeyCode());
        // P50 Pro's physical PTT is KEY_F2 (132). The vendor layer also
        // emits F12 (142) in the PTT broadcast. Neither key may reach the
        // focus/confirm gesture logic; the hardware PTT bridge owns them.
        if (hardwareProfile != null && hardwareProfile.isP50Pro()
                && (event.getKeyCode() == KeyEvent.KEYCODE_F2
                || event.getKeyCode() == KeyEvent.KEYCODE_F12)) return true;
        if (handleLockedGroupNavigation(event)) return true;
        if (handleModeConfirmLongPress(event)) return true;
        if (isPnc370DedicatedInput()) {
            View focusedEdit = getCurrentFocus();
            if (focusedEdit instanceof EditText
                    && handlePnc370NumericEditKey(event, (EditText) focusedEdit)) return true;
        }
        WebCursorController webKeys = activeWebCursorController;
        if (isPnc370DedicatedInput() && isPnc370ConfirmEvent(event.getKeyCode())) {
            if (webKeys != null) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_SOFT_LEFT) {
                    return webKeys.onKey(null, event.getKeyCode(), event);
                }
                return true;
            }
            return handlePnc370ConfirmEvent(event, getCurrentFocus());
        }
        if (webKeys != null && event.getAction() == KeyEvent.ACTION_UP
                && isConfirmActivationKey(event.getKeyCode())
                && webKeys.onKey(null, event.getKeyCode(), event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                adjustAppVolume(event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            View focusedTop = getCurrentFocus();
            if (isConfirmActivationKey(event.getKeyCode())
                    && (focusedTop == sipIconButton || focusedTop == satIconButton || focusedTop == radioIconButton || focusedTop == settingsIconButton)) {
                focusedTop.performClick();
                return true;
            }
            if (isPnc370DedicatedInput() && focusedTop instanceof EditText
                    && isConfirmActivationKey(event.getKeyCode())) {
                showSoftKeyboard((EditText) focusedTop);
                return true;
            }
            if (keyNavigation && isDeviceConfirmKey(event.getKeyCode())) {
                View focused = getCurrentFocus();
                if (focused != null && focused.isClickable()) {
                    android.util.Log.i("ARCH-KEY", "device confirm code=" + event.getKeyCode());
                    focused.performClick();
                    return true;
                }
            }
            if (authScreen && event.getKeyCode() == KeyEvent.KEYCODE_BACK && authRegisterStep > 0) {
                showLoginScreen();
                return true;
            }
            if (hardwareProfile != null && hardwareProfile.isP50Pro()
                    && event.getKeyCode() == KeyEvent.KEYCODE_CALL) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !authScreen && p.sipEnabled()) openSipPhone();
                return true;
            }
            if (!authScreen && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSettings();
                return true;
            }
            if (!authScreen && event.getKeyCode() == KeyEvent.KEYCODE_BACK && !netPage) {
                if (p.showDigital()) showDigitalPage();
                else if (p.showNetwork()) { switchPage(true); connectRoom(currentRoom, false); }
                else if (p.dmrDirectEnabled()) showBmDirectPage();
                else if (p.showNetRadio()) switchPage(false);
                else finish();
                return true;
            }
        }
        if (p != null && p.externalPttEnabled()) {
            int ptt = p.externalPttKeyCode();
            int rel = p.externalPttReleaseKeyCode();
            boolean toggle = "toggle".equals(p.externalPttKeyMode());
            if (hardwareProfile != null && hardwareProfile.isPttKeyCode(event.getKeyCode())) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) pttDown();
                else if (event.getAction() == KeyEvent.ACTION_UP) pttUp();
                return true;
            }
            if (ptt > 0 && event.getKeyCode() == ptt) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (toggle) { if (pttToggleActive) pttUp(); else pttDown(); }
                    else pttDown();
                } else if (event.getAction() == KeyEvent.ACTION_UP && !toggle && rel <= 0) pttUp();
                return true;
            }
            if (rel > 0 && event.getKeyCode() == rel) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) pttUp();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            SipRuntime sipRuntime = SipRuntime.get(this);
            sipRuntime.addListener(sipStatusListener);
            applySipNavState(sipRuntime.isRegistered());
        } catch (Throwable ignored) {}
        try { Updater.resumePendingInstall(this); } catch (Throwable ignored) {}
        networkHandoffToken = 0;
        resetStaleNetworkReceiveState();
        try { NetworkRuntime.get(this).attachUi(this); } catch (Throwable ignored) {}
        ArchAppState.visible = true;
        syncVolumeControlStream();
        try { DeviceIndicatorBridge.refresh(this); } catch (Throwable ignored) {}
        applyKeepScreenOn();
        ui.postDelayed(this::applyKeepScreenOn, 500L);
        ui.postDelayed(this::syncModeConnections, 300L);
        if (netPage && body != null) body.postDelayed(this::ensureNetworkRoomView, 250L);
        if (rooms.isEmpty()) {
            if (!roomsLoading && roomsLoadAttempt >= ROOM_LOAD_MAX_ATTEMPTS) roomsLoadAttempt = 0;
            loadRooms();
        }
        if (keyNavigation && body != null) body.post(this::focusInitialControl);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        uiProfile = DeviceUiProfile.detect(this);
        hardwareProfile = HyteraDeviceProfile.detect();
        tinyScreen = uiProfile.tinyScreen;
        shortScreen = uiProfile.shortScreen;
        keyNavigation = uiProfile.keyNavigation;
        loadFontFactor();
        if (authScreen || !p.authLoggedIn()) showLoginScreen();
        else {
            setContentView(buildRoot());
              applyWindowTheme();
        }
    }

    private void persistCurrentSelection() {
        if (p == null) return;
        android.util.Log.i("ARCH-STATE", "persist page digital=" + digitalPage
                + " network=" + netPage + " dmr=" + bmPage + " radio=" + radioPage
                + " tg=" + p.tg() + " room=" + currentRoom + " bmTg=" + p.bmTg());
        if (digitalPage) {
            p.rememberDmrTg(p.tg(), dmrSlotForTg(p.tg(), p.tgSlot()));
            p.setLastPageMode("digital");
        } else if (netPage) {
            if (currentRoom != null && !currentRoom.trim().isEmpty()) p.setNetRoom(currentRoom);
            p.setLastPageMode("network");
        } else if (bmPage) {
            p.rememberBmTg(p.bmTg());
            p.setLastPageMode("dmr");
        } else if (radioPage) {
            p.setLastPageMode("radio");
        }
    }

    @Override protected void onPause() {
        persistCurrentSelection();
        try { SipRuntime.get(this).removeListener(sipStatusListener); } catch (Throwable ignored) {}
        try { if (pttHeld || pttGranted || dmrTxing) pttUp(); } catch (Throwable ignored) {}
        ArchAppState.visible = false;
        networkHandoffToken = NetworkRuntime.get(this).detachUi(this);
        final int handoffToken = networkHandoffToken;
        // Detach only this Activity's AudioTrack. Do not clear the process-level
        // NetworkRoomClient queue; the service takes over after the token completes.
        synchronized (receivePlaybackLock) {
            receivePlaybackGeneration++;
            receivePlaybackDirty = false;
            lastPlaybackSpeaker = "";
            if (audio != null) {
                runAudioIo(() -> {
                    try { audio.stopPlayback(); }
                    finally { NetworkRuntime.get(this).completeUiDetach(this, handoffToken); }
                });
            } else {
                NetworkRuntime.get(this).completeUiDetach(this, handoffToken);
            }
        }
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try { applyKeepScreenOn(); } catch (Throwable ignored) {}
            try { DeviceIndicatorBridge.refresh(this); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onDestroy() {
        try { SipRuntime.get(this).removeListener(sipStatusListener); } catch (Throwable ignored) {}
        activeActivity = null;
        try { if (overlayPttReceiver != null) unregisterReceiver(overlayPttReceiver); } catch (Throwable ignored) {}
        overlayPttReceiver = null;
        try { if (audioManager != null) { audioManager.stopBluetoothSco(); audioManager.setBluetoothScoOn(false); } } catch (Throwable ignored) {}
        pttTone = null;
        try { setPnc380SpeakerTx(false); } catch (Throwable ignored) {}
        try { toneIo.shutdownNow(); } catch (Throwable ignored) {}
        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}
        networkWanted = false;
        try { DeviceIndicatorBridge.off(this); } catch (Throwable ignored) {}
        try { HyteraPnc370Bridge.clear(this); } catch (Throwable ignored) {}
        try { if (pttHeld) pttUp(); } catch (Throwable ignored) {}
        try { disconnectDmr(false); } catch (Throwable ignored) {}
        try { disconnectBm(); } catch (Throwable ignored) {}
        if (networkHandoffToken == 0) networkHandoffToken = NetworkRuntime.get(this).detachUi(this);
        try { if (roomHttp != null) { roomHttp.dispatcher().cancelAll(); roomHttp.connectionPool().evictAll(); } } catch (Throwable ignored) {}
        ui.removeCallbacksAndMessages(null);
        try { stopDmrPlaybackQueue(); } catch (Throwable ignored) {}
        try { stopBmPlaybackQueue(); } catch (Throwable ignored) {}
        // disconnectDmr()/disconnectBm() above already stop their engines. Never
        // perform another synchronous vendor-HAL stop on Activity teardown.
        try { if (audio != null) audio.stopAsync(); } catch (Throwable ignored) {}
        try { NetworkRuntime.get(this).completeUiDetach(this, networkHandoffToken); } catch (Throwable ignored) {}
        networkHandoffToken = 0;
        try { if (radio != null) radio.release(); } catch (Throwable ignored) {}
        try { if (aprs != null) aprs.stop(); } catch (Throwable ignored) {}
        audioIo.shutdownNow();
        RadioSpectrum.setUiVisible(false);
        super.onDestroy();
    }
}













































