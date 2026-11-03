# Keep JavaScript Interface annotations and bridge methods for WebView
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.joyos.launcher.bridge.** { *; }
-keepclassmembers class com.joyos.launcher.bridge.** {
    public *;
}
