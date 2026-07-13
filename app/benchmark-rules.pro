# Macrobenchmark and AndroidX test startup code load Kotlin runtime helpers
# reflectively before the first measured frame.
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.** { *; }
-keep class androidx.tracing.Trace { *; }
-keep class androidx.test.** { *; }
