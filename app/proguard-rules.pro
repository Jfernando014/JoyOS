# Keep JavaScript Interface annotations, generic signatures, and all JoyOS / USB Serial classes
-keepattributes JavascriptInterface,*Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.joyos.launcher.** { *; }
-keepclassmembers class com.joyos.launcher.** { *; }

-keep class com.hoho.android.usbserial.** { *; }
-keepclassmembers class com.hoho.android.usbserial.** { *; }

-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
