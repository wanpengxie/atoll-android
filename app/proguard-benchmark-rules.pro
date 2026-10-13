# Benchmark build type only (release does not use this file).
# The instrumented test runner lives in the test APK but resolves
# androidx.tracing.Trace from the app APK, so R8 must not drop it here.
-keep class androidx.tracing.Trace { *; }

# The test APK shares library dependencies with the app and calls their
# public API directly (kotlin.LazyKt, coroutines builders, Compose, androidx.test
# helpers). Keep the public ABI of shared libraries in benchmark builds so the
# test runner can link. No allowoptimization: the test APK also subclasses
# them (TestScopeImpl extends JobSupport), and R8 would mark overridable
# members final. App code is still shrunk and optimized.
-keep class kotlin.**, kotlinx.**, androidx.** { public *; protected *; }
