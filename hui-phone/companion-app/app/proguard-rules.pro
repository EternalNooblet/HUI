# ProGuard rules for release build
-keep class com.hui.companion.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.security.crypto.** { *; }

-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
    @androidx.room.Database *;
}

-dontwarn org.tensorflow.lite.**
-dontwarn okhttp3.**
-dontwarn com.google.gson.**