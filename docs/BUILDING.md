# 构建与测试

以下流程在 Apple Silicon macOS 验证。Linux 可作为移植目标，但此仓库没有宣称 Windows/Linux 完整构建已验收。Android 只构建 arm64-v8a；x86_64 模拟器不能运行当前 APK。

## 工具准备

- JDK 17；Android SDK Platform 37.0、Build Tools 36.0.0。
- Android NDK 27.2.12479018；Android Gradle Plugin 配置见 `app/build.gradle.kts`。
- CMake（至少 3.22.1）、Ninja、Meson、pkg-config、Python 3、Git。首次原生依赖构建和 Gradle 解析需要网络。
- macOS 可用 Homebrew 安装命令行工具：`brew install openjdk@17 cmake ninja meson pkg-config python`。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export ULTRASLIM_BUILD_ROOT="$HOME/.cache/ultraslim-build"
```

SDK 不在上述位置时修改环境变量，或用 Android Studio 生成本机 `local.properties`（不提交）。`ULTRASLIM_BUILD_ROOT` 建议使用绝对路径，尤其是在同步目录内开发时。

## 原生依赖（首次构建）

```bash
native/deps/build_deps.sh host
native/deps/build_deps.sh android arm64-v8a "$ANDROID_HOME/ndk/27.2.12479018" 26
```

下载与中间产物保存在 `native/deps/build/<flavor>/`，静态库和头文件安装到 `native/prefix/<flavor>/`。两者均被 Git 忽略。源码 URL、版本、下载散列和编译选项在 [原生依赖配置](../native/deps/CMakeLists.txt)，本地补丁在 `native/deps/patches/`。修改补丁后需要重新运行对应依赖构建。

依赖超级构建默认总任务预算为 4、外层项目并发为 2，内层编译任务（含 dav1d 的 Ninja）按两者计算上限。可用正整数环境变量调整，例如 `ULTRASLIM_DEPS_TOTAL_JOBS=8 ULTRASLIM_DEPS_PROJECT_JOBS=2 native/deps/build_deps.sh host`；外层并发不会超过总预算。该预算约束 superbuild 及其直接调用的构建工具，不保证第三方自定义子进程。

## 一键验证

```bash
scripts/verify.sh
```

依次执行仓库结构/素材检查、主机 CTest、Debug/Release 构建、JVM 单测、lint、Compose 截图回归和最终 APK 的 16 KB 对齐检查。主机测试目录为 `$ULTRASLIM_BUILD_ROOT/host-test`；不再往仓库根目录创建 `build-host-test/`。

```bash
# 只构建 Android；已完成 Android 原生依赖准备即可。
./gradlew :app:assembleDebug -Pkotlin.incremental=false

# 单独执行主机测试，不依赖 Android SDK。
cmake -G Ninja -S native -B "$ULTRASLIM_BUILD_ROOT/host-test" \
  -DULTRASLIM_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$ULTRASLIM_BUILD_ROOT/host-test" --parallel 4
ctest --test-dir "$ULTRASLIM_BUILD_ROOT/host-test" --output-on-failure
```

未设置 `ULTRASLIM_BUILD_ROOT` 时，直接调用 Gradle 沿用 `app/build/`；`verify.sh` 会设置默认外置缓存目录。旧目录已移至 `build/legacy/` 的本地缓存仅作归档，CMake 缓存内含原绝对路径，不能当作新位置的可复用构建目录。

## 设备回归

使用专用 ARM64 模拟器/测试手机，避免在日常数据上执行仪器化测试。`verify.sh --device` 使用独立 `.verifyqa` 包，并构建匹配的 APK 后做对齐检查：

```bash
adb devices
# 多设备时设置目标序列号（不要提交真实序列号）。
export ANDROID_SERIAL=<device-serial>
scripts/verify.sh --device
```

测试素材已随仓库提供在 [tests/fixtures/libultrahdr](../tests/fixtures/libultrahdr/README.md)，主机和 Android 测试共用，不需要私有手机照片。部分相机样本/预览诊断测试需显式参数和外部素材，默认跳过；见 [测试说明](../tests/README.md)。后台测试会临时调整测试设备的温控/服务超时配置，并在结束时恢复，因此应使用专用测试设备。

## 产物与签名

- Debug：`$ULTRASLIM_BUILD_ROOT/app/outputs/apk/debug/app-debug.apk`。
- Release：未配置签名时为 `$ULTRASLIM_BUILD_ROOT/app/outputs/apk/release/app-release-unsigned.apk`；配置四个发行环境变量后为同目录的 `app-release.apk`，见 [签名入口](RELEASING.md#外部密钥签名入口)。
- 测试、lint 报告：`$ULTRASLIM_BUILD_ROOT/app/reports/`。
- Gradle 本机 Debug 签名不等于正式发行签名；换机器通常无法直接覆盖以前的 Debug 包。不得把个人 keystore 放进仓库。

```bash
python3 scripts/check_apk_alignment.py /path/to/app.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose /path/to/signed.apk
```

GitHub Actions 工作流目前暂缓启用，仓库没有 `.github/workflows/`。保留的 Gitea 配置位于 `.gitea/workflows/`，调用 `scripts/verify.sh`，需要 macOS + Android NDK + `macos-arm64` 自托管 runner。恢复 CI 时只执行受信任的 push 和手动触发，不自动执行外部 PR 中的代码。其它托管平台可复用该脚本，需要自行配置 runner。

## macOS 配套工具

Apple Silicon、macOS 15+ 和 Swift 工具链。构建不需要 Android 依赖，见 [apple-live/README.md](../apple-live/README.md)。SwiftPM 测试使用外置 scratch 目录，避免同步目录扩展属性导致签名失败。

## Expressive UI 与自适应布局

UI 使用 Compose BOM 2026.09.00、Material 3 **1.5.0-beta01**、Material Adaptive 1.3.0 和 Navigation 3 1.2.0。Material 3 的 Expressive 实现目前仍在 Beta；其余两项采用稳定版。构建使用 AGP 9.4.1 / Gradle 9.6.0、内置 Kotlin（Compose/Serialization 插件 2.3.20）和 KSP 2.3.12。最低系统仍为 Android 8.0，targetSdk 仍为 36；compileSdk 升到 37.0 是依赖要求。

```bash
# SDK Manager 使用的是带小版本的包名。
sdkmanager 'platforms;android-37.0'

# UI 使用独立包名，避免修改日常使用包的数据。
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PultraslimDebugSuffix=.uiqa -Pkotlin.incremental=false

# Compose Preview 截图：手机、折叠屏宽度、平板、2 倍字体、深色。
./gradlew :app:validateDebugScreenshotTest -Pkotlin.incremental=false
```

截图代码在 `app/src/screenshotTest/`。首次建立参考图时运行 `:app:updateDebugScreenshotTest`，检查渲染报告再将批准的图片放入 `app/src/screenshotTestDebug/reference/`。已有参考图发生变化时先检查差异，不自动接受。设备测试 `AdaptiveUiTest` 会调整模拟器窗口和字体，仅允许在模拟器中运行；铰链测试注入折叠区域，不能代替真实折叠设备验收。

设计依据与布局约束见 [Expressive 与折叠屏设计](design/2026-10-12-expressive-adaptive.md)。
