package cn.arch.dmr;

/**
 * [3.2.49] 网络收音机里的“短波在线收听”入口。
 * 这些是 KiwiSDR 网页接收站，不是直连音频流：
 * 点击后用浏览器打开，并带上频率参数，用户进去即可收听。
 */
public final class RadioLinks {
    public static final class Link {
        public final String group, name, sub, url;
        Link(String group, String name, String sub, String url) {
            this.group = group; this.name = name; this.sub = sub; this.url = url;
        }
    }

    private RadioLinks() {}

    public static final Link[] ALL = new Link[] {
        // 香港放第一位
        new Link("短波", "香港・20m", "14.270 USB｜Waterfall Bay",
                "http://felineyogi.ydns.eu:8073/?f=14270usb"),
        new Link("短波", "东京・40m", "7.050 LSB｜江户川 KiwiSDR",
                "http://21344.proxy.kiwisdr.com/?f=7050lsb"),
        new Link("短波", "东京・20m", "14.270 USB｜江户川 KiwiSDR",
                "http://21344.proxy.kiwisdr.com/?f=14270usb"),
        new Link("短波", "札幌・40m", "7.050 LSB｜北海道 KiwiSDR",
                "http://sdrsapporo.ddns.net:8073/?f=7050lsb"),
    };
}
