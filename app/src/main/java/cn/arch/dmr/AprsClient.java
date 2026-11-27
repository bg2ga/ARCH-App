package cn.arch.dmr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

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
    private Thread worker;

    AprsClient(Context context, Prefs prefs, Log logger) {
        ctx = context.getApplicationContext();
        p = prefs;
        log = logger;
        locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { lastLocation = location; }
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

    void start() {
        if (running || !p.aprsEnabled()) return;
        running = true;
        startLocationUpdates();
        worker = new Thread(this::loop, "arch-aprs");
        worker.start();
    }

    void stop() {
        running = false;
        stopLocationUpdates();
        close();
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    void test() {
        new Thread(() -> {
            try {
                connect();
                sendBeacon();
                log.onLog("APRS-IS 已连接并完成登录");
            } catch (Throwable t) {
                log.onLog("APRS-IS 测试失败: " + t.getMessage());
            } finally {
                close();
            }
        }, "arch-aprs-test").start();
    }

    private void loop() {
        while (running) {
            try {
                connect();
                while (running) {
                    sendBeacon();
                    Thread.sleep(Math.max(30, p.aprsInterval()) * 1000L);
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable t) {
                log.onLog("APRS-IS 断开: " + t.getMessage());
                close();
                try { Thread.sleep(10000); } catch (InterruptedException ignored) { return; }
            }
        }
    }
    private void connect() throws Exception {
        close();
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        String configured = p.aprsServer().trim();
        if (!configured.isEmpty()) hosts.add(configured);
        hosts.add("asia.aprs2.net");
        hosts.add("rotate.aprs2.net");
        hosts.add("aprs.tv");
        Exception first = null;
        for (String host : hosts) {
            try {
                connectHost(host, 14580);
                return;
            } catch (Exception e) {
                if (first == null) first = e;
                log.onLog("APRS 服务器失败 " + host + ": " + e.getMessage());
            }
        }
        throw first == null ? new IllegalStateException("APRS 连接失败") : first;
    }

    private void connectHost(String host, int port) throws Exception {
        close();
        String station = stationId();
        String base = p.aprsCallsign().trim().toUpperCase(Locale.US);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 12000);
        s.setSoTimeout(12000);
        socket = s;
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
        s.setSoTimeout(0);
        final BufferedReader readerIn = in;
        Thread reader = new Thread(() -> {
            try { while (running && readerIn.readLine() != null) {} }
            catch (Throwable ignored) {}
        }, "arch-aprs-rx");
        reader.setDaemon(true);
        reader.start();
        log.onLog("APRS-IS 已连接 " + host + ":" + port + " / " + station + " / 登录成功");
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
        if (loc != null) sendPosition(loc); else sendStatus();
    }

    private void sendPosition(Location loc) throws Exception {
        String call = stationId();
        String comment = p.aprsComment().trim();
        if (comment.isEmpty()) comment = "ARCH Amateur Radio Convergence Hub";
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
        writeLine(packet);
    }

    private void sendStatus() throws Exception {
        String call = stationId();
        String text = p.aprsComment().trim();
        if (text.isEmpty()) text = "ARCH Amateur Radio Convergence Hub";
        writeLine(call + ">APRS,TCPIP*:>" + text + "\r\n");
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

    private synchronized void writeLine(String line) throws Exception {
        Socket s = socket;
        if (s == null || s.isClosed()) throw new IllegalStateException("未连接");
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                s.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
        out.write(line);
        out.flush();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private Location currentLocation() {
        Location loc = lastLocation;
        if (loc != null) return loc;
        if (!hasLocationPermission() || locationManager == null) return null;
        String[] providers = { LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER };
        for (String provider : providers) {
            try { loc = locationManager.getLastKnownLocation(provider); } catch (Throwable ignored) {}
            if (loc != null) return loc;
        }
        return null;
    }

    private boolean hasLocationPermission() {
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