plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Sync clients may duplicate intermediate files; allow builds on a local scratch volume.
val externalBuildRoot = providers.environmentVariable("ULTRASLIM_BUILD_ROOT").orNull
if (externalBuildRoot != null) {
    layout.buildDirectory.set(file("$externalBuildRoot/root"))
    subprojects { layout.buildDirectory.set(file("$externalBuildRoot/$name")) }
}
