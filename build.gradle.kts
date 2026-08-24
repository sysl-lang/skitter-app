// Top level: the plugin version, applied in `app/build.gradle.kts`.
//
// `apply false` here and `id(...)` without a version there is the modern arrangement — it puts the
// version in one file and lets the subproject say only which plugins it wants.
//
// **There is no Kotlin plugin, and its absence is the point.** `org.jetbrains.kotlin.android` was
// required until AGP 9.0 and is now refused outright — "The 'org.jetbrains.kotlin.android' plugin is
// no longer required for Kotlin support since AGP 9.0" — because AGP compiles Kotlin itself. So the
// Kotlin half of this project costs no plugin, no version to keep in step, and no line anywhere.
plugins {
    id("com.android.application") version "9.3.1" apply false
}
