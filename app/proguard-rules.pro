# SibirskySpeak R8/ProGuard rules for release builds.
# Compose, Room, and WorkManager ship their own consumer rules; these cover the
# few reflective entry points specific to this app.

# WorkManager instantiates the daily-reminder worker by class name via reflection.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Enums are read back from strings (e.g. CardState.valueOf during JSON import /
# full-state restore). Keep their synthetic values()/valueOf() members.
-keepclassmembers enum com.sibirskyspeak.data.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
