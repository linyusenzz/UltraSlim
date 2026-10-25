import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

abstract class GenerateOpenSourceNotices : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalFiles: ConfigurableFileCollection
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeArchives: ConfigurableFileCollection
    @get:Input abstract val components: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val root = sourceRoot.get().asFile
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        val text = StringBuilder("UltraSlim — 开源许可与声明\n原创代码：GPL-3.0-only\n\n")
        legalFiles.files.sortedBy { it.relativeTo(root).path }.forEach {
            text.append("\n===== ${it.relativeTo(root).invariantSeparatorsPath} =====\n").append(it.readText()).append('\n')
        }
        text.append("\n===== 本次 Release 运行时依赖 =====\n")
        text.append(components.get().sorted().joinToString("\n")).append('\n')
        // Preserve notices in each AAR and its nested classes/libs JARs before packaging excludes.
        fun scan(bytes: ByteArray, label: String) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').uppercase()
                    if (name.contains("LICENSE") || name.contains("NOTICE") || name.startsWith("COPYING") || name == "COPYRIGHT") {
                        text.append("\n===== $label/${entry.name} =====\n").append(zip.readBytes().toString(Charsets.UTF_8)).append('\n')
                    } else if (entry.name.endsWith(".jar")) scan(zip.readBytes(), "$label/${entry.name}")
                }
            }
        }
        runtimeArchives.files.sortedBy { it.name }.forEach { scan(it.readBytes(), it.name) }
        output.resolve("open-source-notices.txt").writeText(text.toString())
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.ksp)
}

val releaseSigningKeys = listOf("ULTRASLIM_KEYSTORE", "ULTRASLIM_STORE_PASSWORD", "ULTRASLIM_KEY_ALIAS", "ULTRASLIM_KEY_PASSWORD")
val releaseSigningValues = releaseSigningKeys.map { providers.environmentVariable(it).orNull }
require(releaseSigningValues.all { it == null } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Release signing requires all four ULTRASLIM_KEYSTORE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD variables"
}
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }

android {
    namespace = "app.ultraslim"
    compileSdk { version = release(37) }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.ultraslim"
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 公开发行版仍沿用测试期递增的 versionCode，确保覆盖升级。
        versionCode = 26
        versionName = "1.0.3"

        // App 自身只有中文文案；剥掉 androidx/Compose 带入的其他语言资源。
        // AGP 9 起 resourceConfigurations 已弃用（Gradle 9.6 有显式警告）。
        androidResources.localeFilters += setOf("zh-rCN", "zh-rTW", "zh-rHK", "en")

        // Filter transitive JNI libraries too, not only our CMake target.
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    if (hasReleaseSigning) {
        signingConfigs.create("publicRelease") {
            storeFile = file(releaseSigningValues[0]!!)
            storePassword = releaseSigningValues[1]
            keyAlias = releaseSigningValues[2]
            keyPassword = releaseSigningValues[3]
        }
    }
    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("publicRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = providers.gradleProperty("ultraslimDebugSuffix").orElse(".debug").get()
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            providers.environmentVariable("ULTRASLIM_BUILD_ROOT").orNull?.let { buildStagingDirectory = file("$it/cxx") }
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    sourceSets.getByName("androidTest").assets.srcDir("../tests/fixtures/libultrahdr")
    sourceSets.getByName("androidTest").assets.srcDir("schemas")

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    val screenshotReferenceDir = file("src/screenshotTestDebug/reference").absoluteFile
    val screenshotProjectRoot = rootProject.projectDir.absoluteFile
    tasks.withType<Test>().configureEach {
        if (name.startsWith("validate") && name.endsWith("ScreenshotTest")) {
            doFirst {
                // 测试引擎直接解析 referenceImageDir，不能让模块工作目录拼出 app/app/src。
                systemProperty("PreviewScreenshotTestEngineInput.referenceImageDir", screenshotReferenceDir.absolutePath)
                systemProperty("PreviewScreenshotTestEngineInput.projectRoot", screenshotProjectRoot.absolutePath)
                systemProperty("file.encoding", "UTF-8")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // minSdk ≥ 28 起 AGP 默认把 dex 不压缩直存（供 ART mmap），26→31 后实测 Debug APK
        // 40MB→89MB。本仓库 APK 走 GitHub/Gitea 直链下载，体积优先，压回 deflate。
        dex.useLegacyPackaging = true
        // Project-built JNI replacement takes precedence; verification checks the packaged bytes.
        jniLibs.pickFirsts += "lib/arm64-v8a/libandroidx.graphics.path.so"
        // 同步盘冲突副本（"xxx 2.so"）不得进 APK——曾把 3 份 20MB 的 libultraslim.so 打进包。
        // 必须用带空格的通配形式：Gradle 的 Ant 风格模式里 [0-9] 是「匹配 0、-、9 之一」的
        // 字符类，不是数字范围，原来的 "**/* [0-9].so" 实际什么都没排除（已实测：
        // libandroidx.graphics.path 2.so 就是这么漏进 APK 的）。冲突副本一律含空格，故直接
        // 排除所有带空格的 .so。
        jniLibs.excludes += "**/* *.so"
        resources {
            // Keep AndroidX version metadata for Compose Layout Inspector / Android CLI.
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/proguard/**", "META-INF/com.android.tools/**",
                "META-INF/gradle/incremental.annotation.processors",
            )
        }
    }
}

dependencies {
    constraints {
        implementation("androidx.graphics:graphics-path") {
            version { strictly("1.0.1") }
            because("The aligned JNI replacement is built from the matching 1.0.1 source")
        }
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
implementation(libs.compose.navigation)
implementation(libs.compose.adaptive)
implementation(libs.compose.adaptive.navigation)
implementation(libs.compose.navigation.suite)
implementation(libs.androidx.lifecycle.navigation)
implementation(libs.kotlinx.serialization.core)
screenshotTestImplementation(libs.screenshot.validation.api)
screenshotTestImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val generateOpenSourceNotices = tasks.register<GenerateOpenSourceNotices>("generateOpenSourceNotices") {
    sourceRoot.set(rootProject.layout.projectDirectory)
    legalFiles.from(rootProject.files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"))
    legalFiles.from(rootProject.fileTree("third_party/licenses"))
    legalFiles.from(rootProject.fileTree("third_party/libultrahdr") {
        include("LICENSE*", "ULTRASLIM_UPSTREAM.md", "adobe-hdr-gain-map-license/NOTICE", "third_party/**/LICENSE*")
    })
    legalFiles.from(rootProject.file("third_party/androidx-graphics-path/LICENSE.txt"))
    val runtime = configurations.named("releaseRuntimeClasspath")
    runtimeArchives.from(runtime)
    components.set(runtime.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
        artifacts.map { "${it.id.componentIdentifier.displayName} (${it.file.name})" }
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/open-source-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateOpenSourceNotices, GenerateOpenSourceNotices::outputDirectory)
}
