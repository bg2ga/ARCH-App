package cn.arch.dmr;

import android.app.Notification;
import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.media.AudioManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the PDC760 third-party POC callback and network PTT process alive in background. */
public class NetworkKeepAliveService extends Service {
    private static final String CHANNEL = "arch_network_voice";
    private static final int ID = 2101;
    static final String ACTION_VOLUME_UP = "cn.arch.dmr.action.NETWORK_VOLUME_UP";
    static final String ACTION_VOLUME_DOWN = "cn.arch.dmr.action.NETWORK_VOLUME_DOWN";
    static final String ACTION_ENSURE_MICROPHONE = "cn.arch.dmr.action.ENSURE_MICROPHONE";
    static final String ACTION_ENSURE_LOCATION = "cn.arch.dmr.action.ENSURE_LOCATION";
    static final String ACTION_HW_PTT_DOWN = "cn.arch.dmr.action.HW_PTT_DOWN";
    static final String ACTION_HW_PTT_UP = "cn.arch.dmr.action.HW_PTT_UP";
    static final String ACTION_P50_INDICATOR = "cn.arch.dmr.action.P50_INDICATOR";
    static final String EXTRA_SERVICE_RESTORE = "arch_service_restore";
    private static final String EXTRA_P50_INDICATOR = "p50_indicator_mode";
    private static volatile boolean serviceRunning;
    private volatile boolean foregroundMicrophone;
    private volatile boolean foregroundLocation;
    private volatile int p50IndicatorMode;
    private ArchPttBroadcastReceiver pttBroadcastReceiver;
    private BroadcastReceiver screenStateReceiver;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private HyteraDeviceProfile hardwareProfile;
    private NetworkRuntime networkRuntime;
    private HqAudio networkAudio;
    private NetworkRoomClient.Listener networkBackgroundListener;
    private volatile boolean networkModeEnabled;
    private int softwareVolumeStream = -1;
    private int softwareVolumeLevel = -1;
    private long softwareVolumeAt = 0L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable servicePttTimeout = () -> handleNativePtt(false, false);
    private final ExecutorService serviceAudioIo = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "arch-service-audio"));

    @Override public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        if (new Prefs(this).userExited()) {
            serviceRunning = false;
            try { startForegroundCompat(foregroundTypes(false, false)); } catch (Throwable ignored) {}
            stopSelf();
            return;
        }
        hardwareProfile = HyteraDeviceProfile.detect();
        HyteraDeviceProfile hardware = HyteraDeviceProfile.detect();
        if (MainActivity.supportsPdc760Bridge()) try { HyteraPdc760Bridge.install(this); } catch (Throwable ignored) {}
        if (hardware.isHyteraLike()) {
            try { HyteraHardwareKeyBridge.install(this, down -> handleNativePtt(down, false)); } catch (Throwable ignored) {}
        }
        if (hardware.isPncSmall()) {
            try {
                Intent voice = new Intent("com.hytera.android.poc.voip.conc.on");
                voice.setComponent(new android.content.ComponentName("com.meigsmart.setting",
                        "com.meigsmart.setting.activitys.BootWifiReceiver"));
                sendBroadcast(voice);
            } catch (Throwable ignored) {}
        }
        if (!hardware.isHyteraLike()) {
            try {
                pttBroadcastReceiver = new ArchPttBroadcastReceiver(this);
                IntentFilter filter = ArchPttBroadcast.buildFilter(this);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(pttBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
                else registerReceiver(pttBroadcastReceiver, filter);
            } catch (Throwable ignored) {}
        }
        startForegroundCompat(foregroundTypes(false, false));
        SipRuntime.get(this).ensureStarted();
        networkAudio = new HqAudio(24000, 24000);
        applyNetworkAudioPrefs();
        networkBackgroundListener = buildNetworkBackgroundListener();
        networkRuntime = NetworkRuntime.get(this);
        networkRuntime.setBackgroundListener(networkBackgroundListener);
        registerScreenStateReceiver();
        networkRuntime.ensureConfiguredConnection();
        if (hardware.meigHardKey) MeigHardKeyBridge.install(this::handleNativePtt);
        updatePowerLocks();
        ui.postDelayed(this::restoreAppIfNeeded, 800L);
    }

    static void ensureMicrophoneForeground(Context context) {
        startTypeService(context, ACTION_ENSURE_MICROPHONE);
    }

    static void ensureLocationForeground(Context context) {
        startTypeService(context, ACTION_ENSURE_LOCATION);
    }

    static void dispatchPtt(Context context, boolean down) {
        startTypeService(context, down ? ACTION_HW_PTT_DOWN : ACTION_HW_PTT_UP);
    }

    private static void startTypeService(Context context, String action) {
        if (context == null || action == null) return;
        try {
            Intent intent = new Intent(context.getApplicationContext(), NetworkKeepAliveService.class);
            intent.setAction(action);
            Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
            else app.startService(intent);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-NET", "foreground type request failed: " + action, t);
        }
    }

    private boolean communicationModeEnabled() {
        Prefs p = new Prefs(this);
        return p.showNetwork() || p.showDigital() || p.dmrDirectEnabled() || p.sipEnabled();
    }

    private boolean activeNetworkUsesWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT < 23) {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected()
                        && info.getType() == android.net.ConnectivityManager.TYPE_WIFI;
            }
            android.net.Network network = cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private synchronized void updatePowerLocks() {
        try {
            boolean enabled = communicationModeEnabled();
            boolean wifiActive = enabled && activeNetworkUsesWifi();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean interactive = pm == null || pm.isInteractive();
            if (wifiActive) {
                if (wifiLock == null) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null) {
                        int mode = Build.VERSION.SDK_INT >= 29
                                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                        wifiLock = wm.createWifiLock(mode, "ARCH:VOICE");
                        wifiLock.setReferenceCounted(false);
                    }
                }
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (enabled) {
                if (wakeLock == null && pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARCH:PTT");
                    wakeLock.setReferenceCounted(false);
                }
                boolean needWake = !interactive;
                if (wakeLock != null && needWake && !wakeLock.isHeld()) wakeLock.acquire();
                if (wakeLock != null && !needWake && wakeLock.isHeld()) wakeLock.release();
            } else {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            }
            android.util.Log.i("ARCH-POWER", "enabled=" + enabled + " interactive=" + interactive
                    + " wifiTransport=" + wifiActive
                    + " wake=" + (wakeLock != null && wakeLock.isHeld())
                    + " wifi=" + (wifiLock != null && wifiLock.isHeld()));
        } catch (Throwable t) {
            android.util.Log.w("ARCH-POWER", "lock update failed", t);
        }
    }

    private void registerScreenStateReceiver() {
        try {
            screenStateReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    boolean interactive = pm == null || pm.isInteractive();
                    if (networkRuntime != null) networkRuntime.onScreenInteractiveChanged(interactive);
                    updatePowerLocks();
                    android.util.Log.i("ARCH-NET-RX", "screen broadcast="
                            + (intent == null ? "" : intent.getAction()) + " interactive=" + interactive);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenStateReceiver, filter);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (networkRuntime != null) networkRuntime.onScreenInteractiveChanged(pm == null || pm.isInteractive());
        } catch (Throwable t) {
            android.util.Log.w("ARCH-NET-RX", "screen receiver register failed", t);
        }
    }

    private void adjustNetworkVolume(int direction) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            String mode = ArchAppState.activePageMode;
            int stream = ArchAppState.activeVolumeStream;
            if (mode.isEmpty()) {
                Prefs p = new Prefs(this);
                mode = p.lastPageMode();
                if ("network".equals(mode)) {
                    stream = NetworkAudioProfile.streamType(hardwareProfile, p.audioRoute());
                } else if ("radio".equals(mode) || "digital".equals(mode) || "dmr".equals(mode)) {
                    stream = ("earpiece".equals(p.audioRoute()) || "bluetooth".equals(p.audioRoute()))
                            ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
                } else {
                    stream = AudioManager.STREAM_MUSIC;
                }
            }
            int before = am.getStreamVolume(stream);
            int max = Math.max(1, am.getStreamMaxVolume(stream));
            long now = android.os.SystemClock.elapsedRealtime();
            int base = before;
            if (stream == softwareVolumeStream && softwareVolumeLevel >= 0
                    && now - softwareVolumeAt < 5000L) {
                base = softwareVolumeLevel;
            }
            int delta = direction == AudioManager.ADJUST_RAISE ? 1
                    : (direction == AudioManager.ADJUST_LOWER ? -1 : 0);
            int target = Math.max(0, Math.min(max, base + delta));
            am.adjustStreamVolume(stream, direction, 0);
            int after = am.getStreamVolume(stream);
            if (after == before && target != before) {
                try { am.setStreamVolume(stream, target, 0); } catch (Throwable ignored) {}
                after = am.getStreamVolume(stream);
                if (after == before) after = target;
            }
            final float volume = Math.max(0f, Math.min(1f, after / (float)max));
            softwareVolumeStream = stream;
            softwareVolumeLevel = after;
            softwareVolumeAt = now;

            boolean uiTrack = false;
            try { uiTrack = MainActivity.dispatchOutputVolume(stream, volume); } catch (Throwable ignored) {}
            final HqAudio netAudio = networkAudio;
            if (netAudio != null && "network".equals(mode)) {
                serviceAudioIo.execute(() -> netAudio.setOutputVolume(volume));
            }
            android.util.Log.i("ARCH-VOL", "service mode=" + mode + " dir=" + direction
                    + " stream=" + stream + " value=" + before + "->" + after + "/" + max
                    + " volume=" + volume + " uiTrack=" + uiTrack);
        } catch (Throwable t) {
            android.util.Log.w("ARCH-VOL", "service adjust failed", t);
        }
    }

    static void refreshAudioPreferences(Context context) {
        if (!serviceRunning || context == null) return;
        try { context.startService(new Intent(context, NetworkKeepAliveService.class)); }
        catch (Throwable ignored) {}
    }
    private void applyNetworkAudioPrefs() {
        HqAudio audio = networkAudio;
        if (audio == null) return;
        final Prefs p = new Prefs(this);
        networkModeEnabled = p.showNetwork();
        // AudioTrack construction and stop/reopen can block on the vendor HAL.
        // Keep this work off the Service main thread.
        serviceAudioIo.execute(() -> {
            try {
                int stream = NetworkAudioProfile.streamType(hardwareProfile, p.audioRoute());
                if (hardwareProfile != null && hardwareProfile.isPnc380()) {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    NetworkAudioProfile.clearTerminalParameters(am, hardwareProfile);
                }
                audio.setStreamType(stream);
                if (hardwareProfile != null && hardwareProfile.isPnc370() && p.showNetwork()) {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am != null) {
                        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        am.setSpeakerphoneOn(true);
                    }
                }
                if (networkModeEnabled && hardwareProfile != null && hardwareProfile.isPnc370()) {
                    audio.primeRealtime();
                } else if (networkModeEnabled && (hardwareProfile == null || !hardwareProfile.isPnc380())) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isInteractive()) audio.primeRealtime();
                }
                int max = ((AudioManager) getSystemService(AUDIO_SERVICE)).getStreamMaxVolume(stream);
                int level = ((AudioManager) getSystemService(AUDIO_SERVICE)).getStreamVolume(stream);
                if (max > 0) audio.setOutputVolume(level / (float)max);
                float rxGain = (float)Math.pow(10.0, p.rxGainDb() / 20.0);
                if (hardwareProfile != null && hardwareProfile.isP50Pro()) {
                    rxGain *= NetworkAudioProfile.rxTrim(hardwareProfile);
                }
                audio.setRxGain(rxGain);
                audio.setAgc(p.agc(), p.agcDb());
            } catch (Throwable ignored) {}
        });
        updatePowerLocks();
    }

    /** Plays network receive audio only while MainActivity is not attached as output. */
    private NetworkRoomClient.Listener buildNetworkBackgroundListener() {
        return new NetworkRoomClient.Listener() {
            @Override public void onState(int state, String message) {
                if (state == 1) {
                    DeviceIndicatorBridge.noteP50PocConnected(NetworkKeepAliveService.this);
                    if (networkModeEnabled && networkAudio != null
                        && (hardwareProfile == null || !hardwareProfile.isPnc380())) networkAudio.primeRealtime();
                } else if (networkAudio != null) {
                    DeviceIndicatorBridge.noteP50PocDisconnected(NetworkKeepAliveService.this);
                    if (hardwareProfile != null && hardwareProfile.isPnc380()) networkAudio.stopPlaybackForPnc380();
                    else networkAudio.stopPlayback();
                }
            }
            @Override public void onLog(String message) {
                android.util.Log.i("ARCH-NET-SERVICE", message == null ? "" : message);
            }
            @Override public void onVoice(int dmrId, String callsign, short[] pcm) {
                if (pcm == null || pcm.length == 0) return;
                if (!networkModeEnabled || ArchAppState.hwTx) return;
                HqAudio audio = networkAudio;
                if (audio != null && !ArchAppState.hwTx) audio.playRealtime(pcm);
            }
            @Override public void onMembers(java.util.List<NetworkRoomClient.Member> members) {}
            @Override public void onBitrate(int bps) {}
            @Override public void onPtt(int state, String callsign, String message) {
                if (state == 0 && networkAudio != null) {
                    if (hardwareProfile != null && hardwareProfile.isPnc380()) networkAudio.stopPlaybackForPnc380();
                    else networkAudio.clearPlaybackQueue();
                }
            }
            @Override public void onDuplex(int state, int peerDmrId, String peerCallsign, String message) {}
            @Override public void onUdpReady(boolean ready, String message) {}
            @Override public void onVoiceEnded() {
                if (networkAudio != null) {
                    if (hardwareProfile != null && hardwareProfile.isPnc380()) networkAudio.stopPlaybackForPnc380();
                    else networkAudio.clearPlaybackQueue();
                }
            }
        };
    }

    private static Notification buildNotification(Context context) {
        return buildNotification(context, -1);
    }

    /**
     * P50 Pro does not expose the vendor DSP binder to ordinary apps, but its
     * foreground notification is pinned by NotificationManagerService. Updating
     * that existing ID is more reliable than posting a second notification that
     * the P50 firmware can discard while the screen is locked.
     *
     * mode: 0 idle, 1 receive (green), 2 transmit (red)
     */
    static boolean setP50StatusLight(Context context, int mode) {
        try {
            Intent intent = new Intent(context.getApplicationContext(), NetworkKeepAliveService.class);
            intent.setAction(ACTION_P50_INDICATOR);
            intent.putExtra(EXTRA_P50_INDICATOR, mode);
            if (Build.VERSION.SDK_INT >= 26) context.getApplicationContext().startForegroundService(intent);
            else context.getApplicationContext().startService(intent);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ARCH-LED", "P50 foreground LED request failed", t);
            return false;
        }
    }

    private static Notification buildNotification(Context context, int p50Mode) {
        boolean p50 = p50Mode >= 0;
        String channel = p50 ? "arch_p50_voice_status_v2" : CHANNEL;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channel,
                    p50 ? "ARCH P50 收发状态灯" : "网络对讲",
                    p50 ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
            ch.enableLights(p50);
            if (p50) ch.setLightColor(p50Mode == 2 ? 0x00000001
                    : p50Mode == 1 ? 0xFF00FF00 : 0);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("ARCH 网络对讲")
                .setContentText("网络对讲与实体 PTT 服务运行中")
                .setOngoing(true)
                .setOnlyAlertOnce(!p50)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null);
        if (p50) {
            // P50's vendor NotificationManagerService always calls
            // setFlashing(); do not add an explicit on/off pulse here.
            // The legacy fields below are populated after build() with the
            // P50 raw color only, leaving the HAL at its default timing.
        } else {
            b.setLights(0, 0, 0);
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        Notification n = b.build();
        if (p50) {
            int color = p50Mode == 2 ? 0x00000001 : p50Mode == 1 ? 0x00000002 : 0;
            n.ledARGB = color;
            // Do not set ledOnMS/ledOffMS on P50. The vendor NMS reads those
            // fields as flashing parameters and 1000/0 still blinks on this
            // firmware. Leave them at Android's default zero and use only
            // the raw RGB color + SHOW_LIGHTS contract.
            n.flags |= Notification.FLAG_SHOW_LIGHTS;
            n.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
        }
        return n;
    }

    static void reassertNotification(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, buildNotification(context));
        } catch (Throwable ignored) {}
    }

    private void handleNativePtt(boolean down, boolean longPress) {
        try {
            Prefs p = new Prefs(this);
            boolean dedicatedHardware = hardwareProfile != null && hardwareProfile.isHyteraLike();
            if (!dedicatedHardware && !p.externalPttEnabled()) return;
            if (!dedicatedHardware && !p.externalPttBackground() && !ArchAppState.visible) return;
            android.util.Log.i("ARCH-PTT", "hardware " + (down ? "down" : "up")
                    + " dedicated=" + dedicatedHardware + " visible=" + ArchAppState.visible);
            if (down) {
                if (ArchAppState.hwTx) return;
                ArchAppState.hwTx = true;
                HqAudio serviceAudio = networkAudio;
                if (serviceAudio != null && hardwareProfile != null && hardwareProfile.isPnc380()) {
                    serviceAudio.setTxMuted(true);
                    try { serviceAudioIo.execute(serviceAudio::stopPlaybackForPnc380); } catch (Throwable ignored) {}
                }
                if (hardwareProfile != null && hardwareProfile.isP50Pro()) {
                    DeviceIndicatorBridge.transmitNow(this);
                }
                ArchAppState.pendingPttDown = false;
                ArchAppState.pendingPttAt = 0L;
                ui.removeCallbacks(servicePttTimeout);
                ui.postDelayed(servicePttTimeout, Math.max(10, p.pttTimeout()) * 1000L);
                boolean delivered = false;
                try { delivered = MainActivity.dispatchHardwarePtt(true); } catch (Throwable ignored) {}
                if (!delivered) {
                    ArchAppState.pendingPttDown = true;
                    ArchAppState.pendingPttAt = System.currentTimeMillis();
                    restoreAppIfNeeded();
                    send(ArchOverlayPttService.ACTION_DOWN);
                }
            } else {
                if (!ArchAppState.hwTx) return;
                ui.removeCallbacks(servicePttTimeout);
                ArchAppState.hwTx = false;
                if (hardwareProfile != null && hardwareProfile.isP50Pro()) {
                    DeviceIndicatorBridge.idleNow(this);
                }
                if (networkAudio != null && hardwareProfile != null && hardwareProfile.isPnc380()) networkAudio.setTxMuted(false);
                ArchAppState.pendingPttDown = false;
                ArchAppState.pendingPttAt = 0L;
                boolean delivered = false;
                try { delivered = MainActivity.dispatchHardwarePtt(false); } catch (Throwable ignored) {}
                if (!delivered) send(ArchOverlayPttService.ACTION_UP);
            }
        } catch (Throwable ignored) {}
    }

    private void send(String action) {
        try { sendBroadcast(new Intent(action).setPackage(getPackageName())); } catch (Throwable ignored) {}
    }

    private void restoreAppIfNeeded() {
        try {
            Prefs prefs = new Prefs(this);
            if (prefs.userExited() || !prefs.authLoggedIn() || MainActivity.hasActiveActivity()) return;
            Intent launch = new Intent(this, MainActivity.class);
            launch.putExtra(EXTRA_SERVICE_RESTORE, true);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (new Prefs(this).userExited()) {
            serviceRunning = false;
            try { stopForeground(true); } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ENSURE_MICROPHONE.equals(action)) {
            ensureForegroundTypes(true, foregroundLocation);
            return START_STICKY;
        }
        if (ACTION_ENSURE_LOCATION.equals(action)) {
            ensureForegroundTypes(foregroundMicrophone, true);
            return START_STICKY;
        }
        if (ACTION_VOLUME_UP.equals(action)) {
            adjustNetworkVolume(AudioManager.ADJUST_RAISE);
            return START_STICKY;
        }
        if (ACTION_VOLUME_DOWN.equals(action)) {
            adjustNetworkVolume(AudioManager.ADJUST_LOWER);
            return START_STICKY;
        }
        if (ACTION_HW_PTT_DOWN.equals(action)) {
            handleNativePtt(true, false);
            return START_STICKY;
        }
        if (ACTION_HW_PTT_UP.equals(action)) {
            handleNativePtt(false, false);
            return START_STICKY;
        }
        if (ACTION_P50_INDICATOR.equals(action)) {
            p50IndicatorMode = intent.getIntExtra(EXTRA_P50_INDICATOR, 0);
            startForegroundCompat(foregroundTypes(foregroundMicrophone, foregroundLocation));
            return START_STICKY;
        }
        try {
            if (networkRuntime == null) networkRuntime = NetworkRuntime.get(this);
            if (networkBackgroundListener == null) networkBackgroundListener = buildNetworkBackgroundListener();
            networkRuntime.setBackgroundListener(networkBackgroundListener);
            applyNetworkAudioPrefs();
            networkRuntime.ensureConfiguredConnection();
        } catch (Throwable ignored) {}
        return START_STICKY;
    }

    private synchronized void ensureForegroundTypes(boolean microphone, boolean location) {
        boolean micAllowed = microphone && canUseMicrophoneType();
        boolean locationAllowed = location && canUseLocationType();
        boolean changed = foregroundMicrophone != micAllowed || foregroundLocation != locationAllowed;
        foregroundMicrophone = micAllowed;
        foregroundLocation = locationAllowed;
        if (changed) startForegroundCompat(foregroundTypes(micAllowed, locationAllowed));
    }

    private boolean canUseMicrophoneType() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean canUseLocationType() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private int foregroundTypes(boolean microphone, boolean location) {
        int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (Build.VERSION.SDK_INT >= 29) {
            if (microphone && canUseMicrophoneType()) types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (location && canUseLocationType()) types |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return types;
    }

    private void startForegroundCompat(int types) {
        try {
            int indicatorMode = hardwareProfile != null && hardwareProfile.isP50Pro()
                    ? p50IndicatorMode : -1;
            Notification notification = buildNotification(this, indicatorMode);
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, types);
            else startForeground(ID, notification);
        } catch (Throwable t) {
            android.util.Log.e("ARCH-NET", "startForeground failed types=" + types, t);
            // If a newly requested microphone/location type is rejected, retain
            // the legal data-sync foreground service instead of falling back to
            // the untyped declaration that caused the Android 14 failure.
            try {
                if (Build.VERSION.SDK_INT >= 29 && types
                        != android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
                    startForeground(ID, buildNotification(this),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(ID, buildNotification(this));
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override public void onDestroy() {
        serviceRunning = false;
        if (ArchAppState.hwTx) {
            boolean delivered = false;
            try { delivered = MainActivity.dispatchHardwarePtt(false); } catch (Throwable ignored) {}
            if (!delivered) {
                try {
                    sendBroadcast(new Intent(ArchOverlayPttService.ACTION_UP).setPackage(getPackageName()));
                } catch (Throwable ignored) {}
            }
        }
        ArchAppState.hwTx = false;
        ArchAppState.pendingPttDown = false;
        ArchAppState.pendingPttAt = 0L;
        try { if (screenStateReceiver != null) unregisterReceiver(screenStateReceiver); } catch (Throwable ignored) {}
        screenStateReceiver = null;
        try { if (pttBroadcastReceiver != null) unregisterReceiver(pttBroadcastReceiver); } catch (Throwable ignored) {}
        try { if (pttBroadcastReceiver != null) pttBroadcastReceiver.release(); } catch (Throwable ignored) {}
        pttBroadcastReceiver = null;
        HyteraHardwareKeyBridge.uninstall();
        MeigHardKeyBridge.uninstall();
        if (networkRuntime != null && networkBackgroundListener != null) {
            networkRuntime.clearBackgroundListener(networkBackgroundListener);
        }
        networkBackgroundListener = null;
        if (networkAudio != null) try { networkAudio.stopAsync(); } catch (Throwable ignored) {}
        networkAudio = null;
        ui.removeCallbacksAndMessages(null);
        ui.removeCallbacks(servicePttTimeout);
        serviceAudioIo.shutdownNow();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wifiLock = null;
        wakeLock = null;
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        ui.removeCallbacks(this::restoreAppIfNeeded);
        if (new Prefs(this).userExited()) {
            stopSelf();
            return;
        }
        ui.postDelayed(this::restoreAppIfNeeded, 1200L);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

