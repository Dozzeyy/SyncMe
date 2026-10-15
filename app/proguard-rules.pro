-keep class org.vahak.syncme.data.** { *; }
-keep class org.vahak.syncme.crypto.** { *; }
-keep class org.vahak.syncme.backup.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keepattributes *Annotation*
-keepattributes Signature
-dontwarn com.google.errorprone.annotations.**
