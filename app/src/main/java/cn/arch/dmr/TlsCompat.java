package cn.arch.dmr;

import android.content.Context;
import android.os.Build;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Adds the current public CA root for Android 5 devices while retaining system trust. */
final class TlsCompat {
    private static SSLContext sslContext;
    private static X509TrustManager trustManager;

    private TlsCompat() {}

    static synchronized void init(Context context) {
        if (sslContext != null && trustManager != null) return;
        try {
            TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            system.init((KeyStore) null);
            X509TrustManager systemTm = firstTrustManager(system);

            KeyStore rooted = KeyStore.getInstance(KeyStore.getDefaultType());
            rooted.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream in = context.getAssets().open("certs/isrg_root_x1.pem")) {
                int index = 0;
                for (Certificate cert : cf.generateCertificates(in)) {
                    rooted.setCertificateEntry("arch-root-" + (index++), cert);
                }
            }
            TrustManagerFactory extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extra.init(rooted);
            X509TrustManager extraTm = firstTrustManager(extra);

            final X509TrustManager systemDelegate = systemTm;
            final X509TrustManager extraDelegate = extraTm;
            X509TrustManager combined = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    systemDelegate.checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        systemDelegate.checkServerTrusted(chain, authType);
                    } catch (CertificateException first) {
                        extraDelegate.checkServerTrusted(chain, authType);
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    X509Certificate[] a = systemDelegate.getAcceptedIssuers();
                    X509Certificate[] b = extraDelegate.getAcceptedIssuers();
                    X509Certificate[] all = new X509Certificate[a.length + b.length];
                    System.arraycopy(a, 0, all, 0, a.length);
                    System.arraycopy(b, 0, all, a.length, b.length);
                    return all;
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{combined}, null);
            trustManager = combined;
            sslContext = ctx;
        } catch (Throwable t) {
            android.util.Log.e("ARCH-TLS", "custom trust init failed", t);
        }
    }

    private static X509TrustManager firstTrustManager(TrustManagerFactory factory) {
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("X509TrustManager unavailable");
    }

    static SSLSocketFactory socketFactory(Context context) {
        init(context.getApplicationContext());
        return sslContext == null ? null : sslContext.getSocketFactory();
    }

    static X509TrustManager trustManager(Context context) {
        init(context.getApplicationContext());
        return trustManager;
    }

    static HttpURLConnection open(Context context, URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            init(context.getApplicationContext());
            if (sslContext != null && trustManager != null) {
                ((HttpsURLConnection) c).setSSLSocketFactory(sslContext.getSocketFactory());
            }
        }
        return c;
    }
}
