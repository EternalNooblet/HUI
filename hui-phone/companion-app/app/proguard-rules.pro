# ProGuard rules for release build
-keep class com.hui.companion.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.security.crypto.** { *; }
# ML Kit barcode scanning (Play services backend loads these reflectively).
# Without these, R8 shrinks them and the scanner silently never returns.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.barcode.** { *; }
-keep class com.google.android.gms.mlkit.** { *; }

-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
    @androidx.room.Database *;
}

-dontwarn org.tensorflow.lite.**
-dontwarn okhttp3.**
-dontwarn com.google.gson.**