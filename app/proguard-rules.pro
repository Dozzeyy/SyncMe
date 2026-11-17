# Room entities read by column names
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# WorkManager reflective initialization
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keepattributes *Annotation*
-keepattributes Signature
-dontwarn com.google.errorprone.annotations.**
