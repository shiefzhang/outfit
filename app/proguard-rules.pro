# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Retain model classes for Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

-keep class com.outfitai.app.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}
