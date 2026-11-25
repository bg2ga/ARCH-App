# ARCH APP R8 keep rules (3.2.54)
# JNI: 原生方法名必须保留（Java_cn_arch_dmr_* 符号）
-keepclasseswithmembernames class * { native <methods>; }

# 通过 System.loadLibrary / JNI 直接引用的类
-keep class cn.arch.dmr.ArchAppState { *; }
-keep class cn.arch.dmr.ArchPttBroadcast { *; }
-keep class cn.arch.dmr.BuildConfig { *; }

# Manifest 组件（AGP 通常会自动 keep，这里显式声明更稳）
-keep class cn.arch.dmr.ArchKeepAliveService { *; }
-keep class cn.arch.dmr.ArchOverlayPttService { *; }
-keep class cn.arch.dmr.ArchPttAccessibilityService { *; }
-keep class cn.arch.dmr.ArchPttBroadcastReceiver { *; }
-keep class cn.arch.dmr.RadioPlaybackService { *; }
-keep class cn.arch.dmr.MainActivity { *; }

# 三方库的可选依赖
-dontwarn sun.security.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# 海能达 VM780 AutoSDK（厂商系统服务通过隐藏类 android.os.ServiceManager 暴露）
-dontwarn android.os.ServiceManager
-keep class com.meige.autosdk.** { *; }
-keep interface com.meige.autosdk.** { *; }
-dontwarn android.content.pm.IPackageDeleteObserver**
-dontwarn android.content.pm.IPackageInstallObserver**
-dontwarn android.os.LedManager
-dontwarn android.os.storage.**

# Minimal PJSIP/SIP JNI bindings
-keep class org.pjsip.pjsua2.** { *; }
-keepclassmembers class org.pjsip.pjsua2.** { *; }
-keep class cn.arch.dmr.SipPhoneActivity { *; }
-keep class cn.arch.dmr.SipClient { *; }
