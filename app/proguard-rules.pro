# TorPlay ProGuard rules (Stage 1)
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# LibVLC native bindings
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
