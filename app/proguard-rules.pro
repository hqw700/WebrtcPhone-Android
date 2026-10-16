# WebRTC (JNI 反射调用 Java 层，必须整体保留)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 终端 WebView JS 桥
-keepclassmembers class com.webrtc_phone.android.ui.stream.terminal.TerminalBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 发布包去掉 verbose/debug 日志
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
