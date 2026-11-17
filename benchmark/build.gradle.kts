plugins { alias(libs.plugins.android.test) }

android {
    namespace = "app.ultraslim.benchmarks"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") { matchingFallbacks += "release" }
        create("baseline") { matchingFallbacks += "release" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants { variant ->
    variant.enable = variant.buildType == "benchmark" || variant.buildType == "baseline"
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.uiautomator)
}
