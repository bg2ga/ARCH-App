package cn.arch.dmr;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * One process-wide OS connectivity observer.
 *
 * This class deliberately does not ping any application server. Android reports
 * network availability changes once, and enabled modes reconnect when the OS
 * says the network is usable again. This avoids one polling loop per mode.
 */
final class NetworkRecovery {
    interface Listener {
        void onNetworkRestored();
        void onNetworkLost();
    }

    private static volatile NetworkRecovery instance;
    private final Context app;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object waitLock = new Object();
    private volatile boolean started;
    private volatile boolean online;
    private volatile Network currentNetwork;

    private final Runnable notifyRestored = () -> {
        if (!online) return;
        synchronized (waitLock) { waitLock.notifyAll(); }
        for (Listener listener : listeners) {
            try { listener.onNetworkRestored(); } catch (Throwable ignored) {}
        }
    };

    private final Runnable notifyLost = () -> {
        synchronized (waitLock) { waitLock.notifyAll(); }
        for (Listener listener : listeners) {
            try { listener.onNetworkLost(); } catch (Throwable ignored) {}
        }
    };

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            Network previous = currentNetwork;
            boolean switched = previous != null && !previous.equals(network);
            currentNetwork = network;
            refresh(network);
            if (switched && online) {
                main.removeCallbacks(notifyRestored);
                main.postDelayed(notifyRestored, 900L);
                android.util.Log.i("ARCH-NET", "default network switched; notify reconnect");
            }
        }

        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (currentNetwork == null || currentNetwork.equals(network)) {
                currentNetwork = network;
                applyCapabilities(capabilities);
            }
        }

        @Override public void onLost(Network network) {
            if (currentNetwork == null || currentNetwork.equals(network)) {
                currentNetwork = null;
                setOnline(false);
            }
        }
    };

    static NetworkRecovery get(Context context) {
        NetworkRecovery local = instance;
        if (local == null) {
            synchronized (NetworkRecovery.class) {
                local = instance;
                if (local == null) {
                    local = new NetworkRecovery(context.getApplicationContext());
                    instance = local;
                    local.start();
                }
            }
        }
        return local;
    }

    private NetworkRecovery(Context context) {
        app = context;
        cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        online = detectActiveNetwork();
    }

    synchronized void start() {
        if (started || cm == null) return;
        started = true;
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback);
            } else {
                cm.registerNetworkCallback(request, callback);
            }
        } catch (Throwable t) {
            // Keep the last known state; mode-specific transports still have
            // their bounded reconnect fallback, but no extra app-server probes.
            android.util.Log.w("ARCH-NET", "network callback register failed", t);
        }
    }

    boolean isOnline() { return online; }

    boolean awaitOnline(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        synchronized (waitLock) {
            while (!online) {
                long left = end - System.currentTimeMillis();
                if (left <= 0L) return false;
                waitLock.wait(left);
            }
        }
        return true;
    }

    void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void refresh(Network network) {
        try {
            applyCapabilities(cm.getNetworkCapabilities(network));
        } catch (Throwable t) {
            setOnline(false);
        }
    }

    private void applyCapabilities(NetworkCapabilities capabilities) {
        boolean usable = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            usable = usable && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        setOnline(usable);
    }

    private boolean detectActiveNetwork() {
        if (cm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                return caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setOnline(boolean nowOnline) {
        boolean changed = online != nowOnline;
        online = nowOnline;
        if (!changed) {
            synchronized (waitLock) { waitLock.notifyAll(); }
            return;
        }
        android.util.Log.i("ARCH-NET", "system network online=" + nowOnline);
        main.removeCallbacks(notifyRestored);
        if (nowOnline) {
            // Wait for route/DNS changes to settle after Wi-Fi <-> cellular handoff.
            main.postDelayed(notifyRestored, 900L);
        } else {
            main.post(notifyLost);
        }
    }
}
