# The instrumented test APK (benchmark build type) is minified with R8.
# error_prone annotations reference this compile-time-only class.
-dontwarn javax.lang.model.element.Modifier
