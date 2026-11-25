package cn.arch.dmr;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** APRS-IS client with GPS position beacons, Asia APRS-IS default, rotate.aprs2.net and APRS.TV fallback, automatic passcode. */
final class AprsClient {
    interface Log { void onLog(String message); }

    private final Context ctx;
    private final Prefs p;
    private final Log log;
    private final LocationManager locationManager;
    private final LocationListener locationListener;
    private volatile Location lastLocation;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile long lastPositionSentAt;
    private volatile long lastLocationLogAt;
    private static final long MAX_LOCATION_AGE_MS = 10L * 60L * 1000L;
    private static final float MAX_POSITION_ACCURACY_M = 5000f;
    private Thread worker;
    private volatile ExecutorService positionIo;
    private volatile int generation;

    AprsClient(Context context, Prefs prefs, Log logger) {
        ctx = context.getApplicationContext();
        p = prefs;
        log = logger;
        locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                Location usable = usableLocation(location);
                long now = System.currentTimeMillis();
                if (usable == null) {
                    if (now - lastLocationLogAt > 30000L) {
                        lastLocationLogAt = now;
                        logMessage(location == null ? "APRS 未收到定位"
                                : "APRS 定位不可用：provider=" + location.getProvider()
                                + " accuracy=" + location.getAccuracy()
                                + " ageSec=" + Math.max(0L, (now - location.getTime()) / 1000L));
                    }
                    return;
                }
                lastLocation = usable;
                lastLocationLogAt = now;
                logMessage("APRS 定位有效：provider=" + usable.getProvider()
                        + " accuracy=" + usable.getAccuracy());
                final Socket connection = socket;
                if (!running || connection == null) return;
                if (now - lastPositionSentAt < 15000L) return;
                lastPositionSentAt = now;
                final Location fixed = usable;
                final int currentGeneration = generation;
                ExecutorService sender = positionIo;
                if (sender == null) return;
                try {
                    sender.execute(() -> {
                        if (!running || currentGeneration != generation || connection != socket) return;
                        try {
                            sendPosition(fixed, connection, currentGeneration);
                            logMessage("APRS 已发送定位 " + fixed.getLatitude() + "," + fixed.getLongitude());
                        } catch (Throwable t) {
                            if (currentGeneration == generation) {
                                logMessage("APRS 定位发送失败: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
    }

    /** Standard APRS-IS passcode generated from the base callsign (SSID ignored). */
    static String passcodeFor(String callsign) {
        String c = callsign == null ? "" : callsign.trim().toUpperCase(Locale.US);
        int dash = c.indexOf('-'); if (dash >= 0) c = c.substring(0, dash);
        int hash = 0x73e2;
        for (int i = 0; i < c.length(); i += 2) {
            hash ^= (c.charAt(i) << 8);
            if (i + 1 < c.length()) hash ^= c.charAt(i + 1);
        }
        return String.valueOf(hash & 0x7fff);
    }

    boolean isRunning() { return running; }
    boolean isConnected() {
        Socket s = socket;
        return running && s != null && s.isConnected() && !s.isClosed();
    }

    synchronized void start() {
        if (running || !p.aprsEnabled()) return;
        running = true;
        final int currentGeneration = ++generation;
        ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "arch-aprs-location");
            t.setDaemon(true);
            return t;
        });
        positionIo = sender;
        startLocationUpdates();
        worker = new Thread(() -> loop(currentGeneration), "arch-aprs");
        worker.start();
    }

    synchronized void stop() {
        running = false;
        generation++;
        stopLocationUpdates();
        close();
        ExecutorService sender = positionIo;
        positionIo = null;
        if (sender != null) sender.shutdownNow();
        Thread t = worker;
        worker = null;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    void test() {
        if (running) {
            try {
                sendBeacon();
                logMessage("APRS-IS 测试使用当前运行连接");
            } catch (Throwable t) {
                logMessage("APRS-IS 当前连接测试失败: " + t.getMessage());
            }
            return;
        }
        new Thread(() -> {
            try {
                connect();
                sendBeacon();
                logMessage("APRS-IS 已连接并完成登录");
            } catch (Throwable t) {
                logMessage("APRS-IS 测试失败: " + t.getMessage());
            } finally {
                close();
            }
        }, "arch-aprs-test").start();
    }

    private void loop(int currentGeneration) {
        while (running && currentGeneration == generation) {
            try {
                NetworkRecovery recovery = NetworkRecovery.get(ctx);
                if (!recovery.isOnline()) {
                    logMessage("APRS-IS 等待系统网络恢复");
                    while (running && currentGeneration == generation && !recovery.isOnline()) {
                        recovery.awaitOnline(30000L);
                    }
                    if (!running || currentGeneration != generation) return;
                }
                connect(currentGeneration, true);
                while (running && currentGeneration == generation) {
                    sendBeacon();
                    Thread.sleep(Math.max(30, p.aprsInterval()) * 1000L);
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable t) {
                if (!running || currentGeneration != generation) return;
                logMessage("APRS-IS 断开: " + t.getMessage());
                close();
                try {
                    NetworkRecovery recovery = NetworkRecovery.get(ctx);
                    if (!recovery.isOnline()) {
                        while (running && currentGeneration == generation && !recovery.isOnline()) {
                            recovery.awaitOnline(30000L);
                        }
                    } else {
                        Thread.sleep(10000L);
                    }
                } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void connect() throws Exception {
        connect(0, false);
    }
    private void connect(int currentGeneration, boolean requireRunning) throws Exception {
        close();
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        String configured = p.aprsServer().trim();
        if (!configured.isEmpty()) hosts.add(configured);
        int configuredPort = p.aprsPort();
        if (configuredPort <= 0 || configuredPort > 65535) configuredPort = 14580;
        hosts.add("asia.aprs2.net");
        hosts.add("rotate.aprs2.net");
        hosts.add("aprs.tv");
        Exception first = null;
        for (String host : hosts) {
            int port = (!configured.isEmpty() && configured.equals(host)) ? configuredPort : 14580;
            try {
                connectHost(host, port, currentGeneration, requireRunning);
                return;
            } catch (Exception e) {
                if (first == null) first = e;
                logMessage("APRS 服务器失败 " + host + ": " + e.getMessage());
            }
        }
        throw first == null ? new IllegalStateException("APRS 连接失败") : first;
    }

    private void connectHost(String host, int port, int currentGeneration, boolean requireRunning) throws Exception {
        close();
        String station = stationId();
        String base = p.aprsCallsign().trim().toUpperCase(Locale.US);
        Socket s = new Socket();
        socket = s;
        s.connect(new InetSocketAddress(host, port), 12000);
        if (requireRunning && (!running || currentGeneration != generation || socket != s)) {
            try { s.close(); } catch (Throwable ignored) {}
            throw new InterruptedException("APRS cancelled");
        }
        s.setSoTimeout(12000);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        BufferedReader in = new BufferedReader(new InputStreamReader(
                s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        String line = "user " + station + " pass " + passcodeFor(base)
                + " vers ARCH " + BuildConfig.VERSION_NAME + "\r\n";
        out.write(line);
        out.flush();
        boolean verified = false;
        String response;
        while ((response = in.readLine()) != null) {
            String low = response.toLowerCase(Locale.US);
            if (low.contains("# ok") || low.contains("verified") || low.contains("login by")) {
                verified = true;
                break;
            }
            if (low.contains("login failed") || low.contains("unverified")
                    || low.contains("invalid") || low.contains("denied")
                    || low.contains("passcode") || low.contains("not allowed")) {
                throw new IllegalStateException("APRS 登录失败: " + response);
            }
        }
        if (!verified) throw new IllegalStateException("APRS 登录未确认");
        if (requireRunning && (!running || currentGeneration != generation || socket != s)) {
            try { s.close(); } catch (Throwable ignored) {}
            throw new InterruptedException("APRS cancelled");
        }
        s.setSoTimeout(0);
        final BufferedReader readerIn = in;
        Thread reader = new Thread(() -> {
            try { while (running && readerIn.readLine() != null) {} }
            catch (Throwable ignored) {}
        }, "arch-aprs-rx");
        reader.setDaemon(true);
        reader.start();
        logMessage("APRS-IS 已连接 " + host + ":" + port + " / " + station + " / 登录成功");
    }

    private String stationId() {
        String base = p.aprsCallsign().trim().toUpperCase(Locale.US).replace("-", "");
        String ssid = p.aprsSsid().trim();
        if (base.isEmpty()) throw new IllegalArgumentException("APRS 台站呼号为空");
        if (!ssid.matches("\\d{1,2}")) throw new IllegalArgumentException("APRS SSID 必填，范围 0-15");
        int n;
        try { n = Integer.parseInt(ssid); } catch (Exception e) { n = -1; }
        if (n < 0 || n > 15) throw new IllegalArgumentException("APRS SSID 必填，范围 0-15");
        return base + "-" + n;
    }

    private synchronized void sendBeacon() throws Exception {
        Location loc = currentLocation();
        if (loc != null) {
            sendPosition(loc);
            lastPositionSentAt = System.currentTimeMillis();
            logMessage("APRS 已发送定位 " + loc.getLatitude() + "," + loc.getLongitude());
        } else {
            logMessage("APRS 暂未获取定位，等待 GPS/网络定位");
        }
        // The position comment is user-owned (first line). The status packet is
        // software-owned (second line) and always carries current model/voltage.
        sendStatus();
    }

    private void sendPosition(Location loc) throws Exception {
        sendPosition(loc, socket, generation);
    }

    private void sendPosition(Location loc, Socket expectedSocket, int expectedGeneration) throws Exception {
        String call = stationId();
        String comment = userAprsComment();
        String symbolValue = p.aprsSymbol().trim();
        String table = "/";
        String symbol = symbolValue.isEmpty() ? ">" : symbolValue;
        if (symbol.length() >= 2 && (symbol.charAt(0) == '/' || symbol.charAt(0) == '_')) {
            table = symbol.substring(0, 1);
            symbol = symbol.substring(1, 2);
        } else {
            symbol = symbol.substring(0, 1);
        }
        String packet = call + ">APRS,TCPIP*:!" + formatLat(loc.getLatitude()) + table
                + formatLon(loc.getLongitude()) + symbol + comment + "\r\n";
        writeLine(packet, expectedSocket, expectedGeneration);
    }

    private void sendStatus() throws Exception {
        String call = stationId();
        String text = autoAprsStatus();
        writeLine(call + ">APRS,TCPIP*:>" + text + "\r\n", socket, generation);
        logMessage("APRS 状态包已发送: " + text);
    }

    private String userAprsComment() {
        return sanitizeAprsComment(p.aprsComment());
    }

    private String autoAprsStatus() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim().replace(" ", "");
        if (model.isEmpty()) model = "Android";
        String voltage = "N/A";
        try {
            Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int millivolts = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (millivolts > 0) voltage = String.format(Locale.US, "%.2fV", millivolts / 1000.0);
        } catch (Throwable ignored) {
        }
        return sanitizeAprsComment("ARCH业余无线电中枢 arch.hljrsa.cn " + model + " " + voltage);
    }

    private String sanitizeAprsComment(String value) {
        if (value == null) return "ARCH";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll(" {2,}", " ")
                .trim();
    }
    private String formatLat(double value) {
        char hemi = value >= 0 ? 'N' : 'S';
        double abs = Math.abs(value);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%02d%05.2f%c", deg, min, hemi);
    }

    private String formatLon(double value) {
        char hemi = value >= 0 ? 'E' : 'W';
        double abs = Math.abs(value);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%03d%05.2f%c", deg, min, hemi);
    }

    private synchronized void writeLine(String line, Socket expectedSocket, int expectedGeneration) throws Exception {
        Socket s = expectedSocket == null ? socket : expectedSocket;
        if (s == null || s.isClosed()) throw new IllegalStateException("未连接");
        if (expectedSocket != null && (socket != expectedSocket || generation != expectedGeneration)) {
            throw new IllegalStateException("APRS 会话已切换");
        }
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        out.write(line);
        out.flush();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private Location currentLocation() {
        Location loc = usableLocation(lastLocation);
        if (loc != null) return loc;
        if (!hasLocationPermission() || locationManager == null) return null;
        String[] providers = { LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER };
        for (String provider : providers) {
            try { loc = usableLocation(locationManager.getLastKnownLocation(provider)); }
            catch (Throwable ignored) {}
            if (loc != null) return loc;
        }
        return null;
    }

    private Location usableLocation(Location loc) {
        if (loc == null) return null;
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null;
        if (Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001) return null;
        long age = System.currentTimeMillis() - loc.getTime();
        if (loc.getTime() > 0L && age > MAX_LOCATION_AGE_MS) return null;
        if (!loc.hasAccuracy()) return null;
        float accuracy = loc.getAccuracy();
        if (!Float.isFinite(accuracy) || accuracy < 0f || accuracy > MAX_POSITION_ACCURACY_M) return null;
        return loc;
    }

    private void logMessage(String message) {
        android.util.Log.i("ARCH-APRS", message);
        if (log != null) log.onLog(message);
    }

    private boolean hasLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (locationManager == null || !hasLocationPermission()) return;
        // PDC760 的定位服务通常由 fused/passive provider 提供，单纯请求 GPS 会拿不到位置。
        requestUpdates(LocationManager.FUSED_PROVIDER, 10000L, 20f);
        requestUpdates(LocationManager.GPS_PROVIDER, 10000L, 20f);
        requestUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 50f);
        requestUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void requestUpdates(String provider, long minTime, float minDistance) {
        try {
            locationManager.requestLocationUpdates(provider, minTime, minDistance,
                    locationListener, Looper.getMainLooper());
        } catch (Throwable ignored) {}
    }

    private void stopLocationUpdates() {
        if (locationManager == null) return;
        try { locationManager.removeUpdates(locationListener); } catch (Throwable ignored) {}
    }

    private synchronized void close() {
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }
}
