package cn.arch.dmr;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import okhttp3.Dns;

/** System DNS with a last-resort mapping for the ARCH voice endpoint. */
final class ArchDns implements Dns {
    private static final ArchDns INSTANCE = new ArchDns();
    private static final String VOICE_HOST = "hljrsa.cn";
    private static final String VOICE_FALLBACK_IP = "1.62.77.180";

    static ArchDns get() { return INSTANCE; }

    private ArchDns() {}

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        try {
            return Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException first) {
            if (VOICE_HOST.equalsIgnoreCase(hostname)) {
                android.util.Log.w("ARCH-DNS", "system DNS failed, using voice fallback");
                return Arrays.asList(InetAddress.getByName(VOICE_FALLBACK_IP));
            }
            throw first;
        }
    }
}
