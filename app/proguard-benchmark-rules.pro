# Benchmark build type only (release does not use this file).
# The instrumented test runner lives in the test APK but resolves
# androidx.tracing.Trace from the app APK, so R8 must not drop it here.
-keep class androidx.tracing.Trace { *; }

# The minified test APK is linked against this APK's mapping and calls both
# shared libraries and app APIs directly (fixtures, Before hooks). A full DEX
# reference scan (artifacts/sol-2-r8/20261014-003245-benchmark/abi-report.json)
# found 60 missing methods across 33 app classes plus non-public library
# helpers, so keep these boundaries wholesale instead of one class at a time.
# No allowoptimization on libraries: the test APK subclasses them
# (TestScopeImpl extends JobSupport) and R8 would finalize their members.
-keep class kotlin.**, kotlinx.**, androidx.** { *; }
# App classes may still be renamed (the test APK uses the mapping) and their
# bodies optimized; only their public/protected surface must stay present.
-keep,allowobfuscation,allowoptimization class dev.atoll.app.** { public *; protected *; }
