# Release shrinking rules. Most libraries ship their own consumer rules (Retrofit, OkHttp, Room,
# Hilt, WorkManager, Firebase, kotlinx.serialization 1.7). Only what they do not cover is here.

# Keep line numbers so a stack trace from a resident can be read (the app is open source).
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# kotlinx.serialization: generated serializers of the API contract and local models.
-keepclassmembers class org.neighborhoodwatchdog.android.** {
    *** Companion;
}
-keepclasseswithmembers class org.neighborhoodwatchdog.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.neighborhoodwatchdog.android.**$$serializer { *; }

# Retrofit service interfaces are created by reflection; keep generic signatures of suspend functions.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface org.neighborhoodwatchdog.android.data.api.WatchdogApi { *; }
-keep interface org.neighborhoodwatchdog.android.data.api.AuthApi { *; }

# OkHttp optional platforms that are not on Android.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
