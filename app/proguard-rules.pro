# OkHttp kuralları
-dontwarn okhttp3.**
-dontwarn okio.**

# FFmpegKit kuralları
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# youtubedl-android / Jackson kuralları (JSON ayrıştırma için)
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-dontwarn com.fasterxml.jackson.**
