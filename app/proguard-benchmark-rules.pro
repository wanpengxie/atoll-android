# Benchmark build type only (release does not use this file).
# The instrumented test runner lives in the test APK but resolves
# androidx.tracing.Trace from the app APK, so R8 must not drop it here.
-keep class androidx.tracing.Trace { *; }
