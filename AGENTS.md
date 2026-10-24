# UltraSlim

把 Ultra HDR (gain map) 照片转码为体积更小的 HEIC / AVIF / JXL（保留 gain map），也支持纯 SDR 图片转码。Android app：Kotlin + Jetpack Compose (Material 3)，原生核心为 libultrahdr v2.0.2。

## 结构

- `app/` — Android 应用模块（Compose / Room / WorkManager / DataStore / Coil3）
- `native/` — 原生代码
  - `jni/transcode.cpp` — 转码核心（平台无关）：probe + JPEG/HEIC/AVIF/JXL → JPEG/HEIC/AVIF/JXL（HDR 路径走 libultrahdr + ISO 21496-1 jhgm/tmap，SDR 路径走直编码；支持速度档位 speedLevel 0/1/2、分辨率缩放 resizePercent）
  - `jni/ultraslim_jni.cpp` — JNI 桥接（`app.ultraslim.bridge.UltraHdrBridge`）
  - `CMakeLists.txt` — App 原生构建（Gradle externalNativeBuild 入口；`ULTRASLIM_HOST_TEST=ON` 时构建主机测试程序）
  - `deps/` — 依赖超级构建：libjpeg-turbo 3.1.1 / x265 4.1 / aom 3.12.1(仅解码) / SVT-AV1 3.0.0(AV1 编码) / libde265 1.0.16 / libheif(4a3f74bc + ISO 21496-1 gain map patch) / libjxl 0.11.1 (含 highway, brotli, skcms)
- `third_party/libultrahdr` — 官方 google/libultrahdr v2.0.2（vendored，基线 `e5f5a022`）；来源与 5 个本地修改文件见 `third_party/libultrahdr/ULTRASLIM_UPSTREAM.md`。不是独立 Git 仓库。
- `native/prefix/<flavor>/` — 依赖产物（`host`、`android-arm64-v8a`），被 .gitignore 忽略

## 构建

前置：JDK 17（`/opt/homebrew/opt/openjdk@17`）、Android SDK（`~/Library/Android/sdk`，`local.properties` 指向它）、NDK 27.2.12479018、cmake + ninja（brew）。

```bash
# 1. 构建原生依赖（每 ABI 一次；产物进 native/prefix/）
native/deps/build_deps.sh android arm64-v8a ~/Library/Android/sdk/ndk/27.2.12479018 26

# 2. 构建 APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug
```

## 主机冒烟测试（不依赖设备，改动 transcode.cpp 后必跑）

```bash
native/deps/build_deps.sh host                      # 主机依赖（一次性）
cmake -G Ninja -S native -B build/host-test -DULTRASLIM_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/host-test --parallel
build/host-test/transcode_test <输入图片> <输出.heic|.avif> [quality=90] [gainmapScale=4] [gainmapQuality=60]
```

测试样本：`tests/fixtures/libultrahdr/`（Apple gainmap JPEG、SDR JPEG）。Google 风格 Ultra HDR 样本可用 `build/uhdr-app/ultrahdr_app` 生成（先 `-m 1` 解码成 raw rgba1010102，再 `-m 0 -a 5` 编码）。

## 关键实现说明

- **HEIC 相册兼容**：App 新任务默认开启 `heicGainmapCompatibility`，与 AVIF 开关独立；原生/JNI 默认 false，旧快照缺字段按 false。兼容增益图采用 HEVC YCbCr 4:2:0：RGB 转 BT.601 限定域，存在色度采样与舍入损失；单通道使用 full-range Y + 中性 U/V，保持灰度值。保留增益图尺寸和 HDR 参数，不能标为 RGB 逐值无损。严格关闭时仍用原有 GBR/mono；部分相册可能偏色或不识别 HDR。API 34+ 保存兼容 HEIC/AVIF 前必须由 `HdrOutputValidation` 确认平台读取增益图，否则不发布。主图边长 >4096 的 HEIC 也采用 2048 网格，解决单图解码失败；HEVC ICC 和 AV1 一样排在 NCLX 之后。tmap 的 HLG 标记不是本次偏色原因，不改为 PQ。
- **AVIF 相册兼容**：App 新任务默认开启 `avifGainmapCompatibility`；用户显式关闭仍保留，原生/JNI 默认 false，旧快照缺字段按 false。彩色增益图转换为实际 BT.601 限定域 YCbCr 4:4:4，再 AOM 无损编码；不缩小或重建，不保证 RGB 逐值无损。关闭开关时保留 Identity GBR，但必须提示部分 Apple / Android 相册会偏色。不要将兼容模式标为逐值无损。
- AVIF 增益图超过 512 像素时使用 512×512 HEIF grid，避免单包超出 MediaCodec 输入缓冲。主图任一边超过 4096 时用 2048×2048 grid，保持原分辨率、ICC/NCLX 和 EXIF。`patch_avif_grid.py` 同时修复 libheif grid 的内存 ISPE，防止后续 tmap 写成 0×0；分块不得复用单图 gainmap 缓存。
- 奇数尺寸 HDR AVIF 主图使用 AOM 4:4:4，避免 4:2:0 偶数画布及 clap 影响 gainmap。纯 SDR（包括边长 >4096 的奇数尺寸）使用 SVT 4:2:0，分块画布补齐偶数并由 clap 恢复原始可见尺寸；Android 15/16 的采样与全尺寸解码已验证，Android 16 反而拒绝原来的奇数 SDR 4:4:4 分块图。补齐仅影响编码画布，不缩放或裁掉源像素；HDR 例外保持不变。
- SDR 保存前在 API 29+ 且系统声明支持目标 MIME 时经 `SdrOutputValidation` 做平台解码检查；失败不发布且保留原图。系统不支持的格式（如 JXL）不以平台解码失败否定文件有效性。
- `tmap` compatible brand 只在 libheif 实际创建 tone-map item 时添加，禁止因编译了增益图功能就给所有 HEIC/AVIF 输出加此标记；纯 SDR 带 `tmap` 却没有 item 会被 libavif 拒绝，即使 Android ImageDecoder / Apple ImageIO 能读取也不代表封装合规。修改该补丁后必须重建 host 和 Android libheif。
- App 在 API 34+ 保存兼容 HDR AVIF 前检查平台能否解码主图并读出 gainmap；失败不保存，保留原图并明确提示。API 34 以下没有此系统检查能力。平台可读不代表色彩逐值无损或所有相册视觉验收通过。

- 两条转码路径，按请求自动选择：
  - **preserve（原样保留 gainmap）**：默认且唯一的 HDR 路径；主图重压缩/缩放/换格式，严格模式下 gainmap 像素逐值保留（HEIF/AVIF 走无损 GBR/mono 直写）；显式相册兼容选项按上文规则转换，不承诺彩色样本逐值无损。旧 gainmapScale / gainmapQuality / multiChannelGainmap 参数对保留行为无影响（仅为接口/快照兼容保留）。*不要*对 gainmap 做下采样——实验证明即使 2x bilinear 下采样也会砍掉高光增益。
  - **rebuild（重建 gainmap）**：仅在显式传 `preserveGainMap=false` 时走的 libultrahdr 全解码重编码逃生通道（自一致、已验证）。preserve 不支持的情况（>8 位增益图、容器 HDR 转 JPEG）明确报错（errorCode 31）并保持输出为空，绝不静默回退 rebuild。
- 验证方法：rendition_dump/gm_dump（host_test/）把文件解出 SDR/HDR 渲染版，在线性域（hlgInvOetf 之后）对比均值——*必须*用线性域，任何非线性 tonemap 都会因 Jensen 不等式产生伪"亮度差"。真机照片（5888x4416、多通道 gainmap）与样本均已验证 ±3%。
- Android 15 及更早的平台解码器不认识 ISO 21496-1 tmap HEIC，预览一律走 `nativeDecodePreview` 原生兜底（UltraAsyncImage，带 PreviewCache）。
- 编码输出固定 8-bit（base + gainmap），所以 x265 只编 8-bit；AV1 编码走 **SVT-AV1**（比 aomenc 快数倍），AV1 解码走 dav1d（aom 解码器在大图上有横向拖影，仅保留无损 gainmap 编码用途），HEVC 解码走 libde265（支持 HEIC/AVIF 输入）。
- 速度档位（speedLevel 0/1/2）：HEVC 映射 x265 preset slow/medium/veryfast，AV1 映射 "speed" 4/6/9；经 vendored libultrahdr 补丁注入（`heifultrahdr.cpp` / `avifultrahdr.cpp`，读取全局弱符号 `ultraslim_codec_speed_level`，强定义在 transcode.cpp）。svt 插件没有 "chroma" 参数，`set_internal_color_format` 已改为容错。
- 质量映射按编码器区分：HEVC 沿用 `remapQualityForLibheif`，AVIF（SVT 及奇数尺寸 AOM）使用 `remapQualityForAvif` 传递完整 1–100 范围；禁止套用 HEVC 曲线把 AVIF 100 限制到 libheif 84。SDR、preserve 和显式 rebuild 路径须一致。100 仍有 8-bit RGB/YUV 转换损失，不承诺 RGB 无损；最高档可能接近或超过原文件大小，既有 `retrySmaller` 仍可降质一次。主机 `avif_quality` 检查实际解码纹理保真。
- 分辨率缩放：HDR 路径用 `uhdr_add_effect_resize`（解码时生效），SDR 路径用自带 bilinear（RGBA8888）。
- 预览兜底：平台解码器不认识 ISO 21496-1 tmap HEIC（Android 15 及更早）时，`UltraAsyncImage`（ui/components/Components.kt）会走 `nativeDecodePreview`（原生解码主图 → JPEG → Bitmap）显示预览；历史/队列/详情共用。
- x265 4.1 在 Clang + CMake≥4 下需要补丁（`native/deps/patches/patch_x265.sh`）；手写 aarch64 .S 汇编含绝对重定位，Android 共享库禁止（bionic 拒绝 textrel），故保持 `ENABLE_ASSEMBLY=OFF`，通过 `ULTRASLIM_ARM_INTRINSICS` 单独编译并注册 PIC-safe NEON intrinsics；不能仅设置 `HAVE_NEON`。16×16 DCT 使用 C++ intrinsic 实现，依赖汇编的 planar 预测保留 C 回退。
- HDR 路径：libultrahdr 解码为 RGBA1010102/HLG → 重新编码（重建 gainmap，可配置 scale/quality/多通道/速度/分辨率）。SDR 路径：libjpeg/libheif 解码 → BT.601 限定域 YUV420 → libheif 直编码（或 libjpeg 出 JPEG）。
- EXIF 透传：HDR 路径经 `uhdr_enc_set_exif_data`，SDR 路径经 APP1 marker + `heif_context_add_exif_metadata`（JPEG 输出经 `jpeg_write_marker`）。
- ABI 只出 arm64-v8a（见 `app/build.gradle.kts`）；加 x86_64 需要给依赖构建装 nasm。
- vendored libultrahdr 的本地修改：`cmake/Findlibheif.cmake`（多 define 的 -D 处理）、两个 encoder 文件（速度/线程 + chroma 容错 + 质量映射）、`ultrahdrcommon.h`（质量映射）、`jpegrutils.cpp`（Apple `use_base_cg` 初始化）。v2.0.2 之后官方 main 另有颜色与元数据修复，已选择性合入 RGB 绿通道、ARM NEON 系数、sRGB NCLX 三项修复（924967a759 / 2472ff7ad1 / b4880a1c21），ISO 布局未合入；审计记录见 `ULTRASLIM_UPSTREAM.md`，不能直接把官方新补丁视为当前 preserve 路径偏色的根因。

## 性能与历史页（2026-10-08）

- SVT-AV1 使用 `COMPILE_C_ONLY=OFF`，ARM 优化由运行时 CPU 检测分派，禁止全局强制 SVE2 ISA。
- `retrySmaller` 在原生层仅重试一次主图质量（下降 5，最低 80）；复用解码/缩放结果。非分块 HEIF 增益图码流缓存限定在一个 `GainMapRetryScope` 内，图像/编码格式/增益图参数必须不变，退出或异常时清理；JXL 复用同一 payload 的 jhgm。此改动依赖重建 libheif 本地补丁。
- HEIC 增益图随速度档位采用 slow/medium/veryfast；AVIF 无损增益图采用 AOM speed 4/6/8，仍保持像素逐值一致。
- 百万像素以上的缩放/YUV 预处理最多 4 个工作线程，编码前 join；批次仍逐张转换，避免多张全分辨率 HDR 同时驻留。
- 历史按本地自然日分组，右滑日期标题确认删除；按日期查询包含当前分页外、同筛选条件的记录。只删除应用内成品及记录，已存相册图片不删除。
- 交互测试可用 `-PultraslimDebugSuffix=.perfqa` 构建独立测试包，避免污染日常使用的历史。同步目录建议外置 `ULTRASLIM_BUILD_ROOT` 并使用 `-Pkotlin.incremental=false`。

## 批量规模与防御性缺口（2026-10-10）

- probe 次数收敛：`transcodeImage` 的输入 probe 把已初始化的 uhdr decoder 直接移交 `transcodeUnchecked`（JXL 分支复用 probe 结果，SDR 不再建 decoder）；rebuild 路径从内存缓冲校验输出，不再整读输出文件；`TranscodeResult` 携带 hasGainMap/hasExif/maxContentBoost，worker 不再对输出做第二次 probe。preserve 路径的输出元数据由 `tryReuseGainMap` 直接报告。
- 所有解码路径共享 1 亿像素上限 `kMaxPixels`（JXL 此前缺失）；JXL box 上限为 `kMaxBoxBytes`。
- 批次快照解析（decodeDefaults/JSONArray）必须容错：旧快照缺字段按默认值，损坏批次显式置 FAILED，不得在 checkpoint 循环外抛异常静默死亡。仅两个兼容开关例外：缺字段按 false（像素精确语义）。
- Room 迁移加列的写法：带 Kotlin 默认值的字段（如 TranscodeResult 的新字段），期望 schema 是**可空且无 DEFAULT**；但 Room 读取非空内嵌字段时**不会**把 NULL 回退为 Kotlin 构造器默认值，必须显式回填或做读取兜底。`MIGRATION_6_7` 修复已有任务的新输出元数据，空结果保持 NULL；v3/v6 含真实旧结果的设备迁移测试必须覆盖，JVM 单测与 host ctest 不能替代。
- 前台服务 API 35+ 用 `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`（manifest 同时声明 dataSync|mediaProcessing）；`setForeground` 失败只标记未完成项；温控重试有指数退避与 8 次上限，超限停回 READY 等手动继续。
- 历史页展开态按行虚拟化（每条记录独立 LazyColumn item，折叠态不发射行）；首页队列流经 150ms 采样后再进 UI 状态；回收站原图解析（逐条 MediaStore 查询）只在 Default 线程做，组合期只读缓存。
- HDR 扫描（整库/相册）经 `probePermits` 并发（4 路），probe 是轻量头部解析不是编码；PreviewCache 解码在 diskLock 之外、原生解码槽位 2。转码写盘路径必须检查 flush/close（stdio 缓冲写失败到 close 才暴露），readAll/writeAll 对 EINTR 重试。`apple-live` 本机跑 `swift test` 必须外置 scratch 目录（`--scratch-path /tmp/...`），iCloud 同步目录的 FileProvider xattr 会让 codesign 拒绝测试包；native/deps 依赖声明必须带 URL_HASH（改版本时重新计算 SHA256）。

## Apple Live Photo 导出（2026-10-10）

- Android HEIC 成品详情菜单导出 ZIP（AppleLiveBundle/AppleLiveExporter），包内为照片、原视频与时间点；本身不是 Apple Live Photo。保留 Android 原有动态导出。
- `apple-live/` 是 macOS 15+ SwiftPM 配套工具，`script/build_and_run.sh` 构建/启动。只修改 HEIC 配对元数据，视频 AVFoundation passthrough 成 MOV；PhotoKit 识别后用户点击导入「照片」。不能把只传 ZIP/HEIC 到 iPhone 说成可自动成为 Live Photo。
- HEIC 配对时不经过 ImageIO 重编码主图/增益图。改 iloc/iinf/iref 必须验证 Apple ImageIO；升级表版本可能导致 libheif 可读但 Apple 不可读。EXIF 序列化临时图的 1x1 尺寸必须还原。
- 原图保留/兼容仍为默认；用户允许研究重建，但尚未切换。详见 docs/reports/2026-10-10-ultrahdr-rebuild-evaluation.md 与 apple-live/README.md。

## Expressive 与自适应 UI（2026-10-12）

- UI 使用 MaterialExpressiveTheme，Material 3 1.5.0-beta01；BOM、Adaptive、Navigation 3 版本在 gradle/libs.versions.toml。工具链 AGP 9.4.1 / Gradle 9.6.0 / compileSdk 37.0，targetSdk 仍为 36；Kotlin 由 AGP 内置，勿重新应用 kotlin-android 插件。
- AppRoot 的两个标签各自保存 NavBackStack，必须同时保留 saveable 与 ViewModel decorators。成品库的 ListDetailSceneStrategy 要接管单栏及双栏，避免窗口变化时重新挂载布局。
- 页面内容使用 AdaptiveWorkspace 的实际约束和 FoldingFeature 坐标分区，不按设备型号或全局像素宽度猜布局。History 在 tabletop 使用单一导航场景，内部列表/统计避让横向铰链。
- 改 UI 后跑 AdaptiveUiTest 与现有滑动/撤销/历史/自动替换回归。截图参考图变动须先检查差异。设计依据见 docs/design/2026-10-12-expressive-adaptive.md。

## 优化批次（2026-10-13）

- 原生正确性：HDR 重建路径的输入内存释放（`data.clear()`）必须发生在 HEIF EXIF 回退提取**之后**；SDR/重建编码失败统一 `ftruncate(0)` 清输出 fd；`decodeJpegSdr` 短读视为失败（截断码流不再产出灰底图）；JXL box 倍增受 `kMaxBoxBytes` 约束（errorCode 32）。
- `ultraslim` target 已加 `-fvisibility=hidden` + `--gc-sections` + `--exclude-libs,ALL`（动态符号 10,262 → 216，.so 约 20.6MB → 17.5MB）；`core`/`image_io` 带 `-ffunction-sections` 才能让 gc-sections 生效。`decodePreviewJpeg` 入口自行初始化 `ultraslim_codec_threads`（不经过 transcodeUnchecked），否则 parallelRows 退化为单线程。
- 同步盘冲突副本（`xxx 2.so` / `libheif 3.a`）曾泄漏进 APK（3 份 20MB .so）；packaging 加 `jniLibs.excludes += "**/* *.so"` 防御，prefix 副本已清理。项目仍在同步目录时此防御不能撤。**勿改回 `"**/* [0-9].so"`**：Gradle 的 Ant 模式里 `[0-9]` 是「匹配 `0`、`-`、`9` 之一」的字符类而非数字范围，那个写法一个文件都排除不掉（2026-10-17 实测查实，见下文该批次）。
- Room 当前版本 7：v6 新增 `(saveState, createdAt)` 索引，v7 回填旧任务的输出元数据。批量 API：`HistoryDao.noteReplacement/deleteByIds`、`ReplacementDao.deleteAll/byIds`、`OutputStore.deleteBatch`、`ConversionRepository.updateAll`——批量场景禁止逐条 update/byId（每条都触发全量 diff、表 invalidation 或 provider 往返）。
- Home 统计改用单条 `flowStats()` + 500ms 节流（原 totalSaved+countDone 两次全表聚合且未节流）；扫描/导入进度按批（16/8 张）发射；`scannedProbes` 在扫描结束 finally 清空（persist 有权限时返回原 URI，键才匹配）。
- `PreviewCache` 的 content URI version（size:mtime）有 2 秒 TTL 缓存，内存命中不再必先做 provider 查询；大图档降采样走 `inSampleSize`；设置页清缓存挪到 IO 线程。`DetailViewModel.requestTrashOriginal` 改 suspend + IO（原在主线程做 MediaStore 查询）；DetailViewModel 只有一条 flowById 订阅，按 inputUri 去重后才做回收站检查。
- 自动替换管线：`stage` 中 source/target 解析到同一 URI 时只哈希一次；`saved()` 的 validate 跳过原图重哈希（`recheckSource=false`，回收前的 trash 时校验仍保留）——saved 时机不做破坏性操作，安全性不依赖这次哈希。
- 依赖：room 2.8.5 / work 2.12.0 / coroutines 1.11.0 / coreKtx 1.19.1 / splashscreen 1.2.0 / serialization 1.11.0 / coil 3.6.3；硬编码版本已全部迁入 libs.versions.toml。defaultConfig 加了 resourceConfigurations（zh/en）。

## 优化批次（2026-10-17）

- 原生内存与重试：新增 `PreparedBasePlanes` / `PreparedGainMapPlanes`，把与质量无关的 RGBA→YUV / `heif_image` 创建提到 `encodeWithRetry` 之外，转换后立即释放 RGBA，retrySmaller 只重跑编码器；JXL 同样先把 RGBA→RGB 转换一次（libjxl 非流式 `AddImageFrame` 内部 `CopyBuffers()`，缓冲无需活过调用）。HEIC/AVIF/JXL 的 SDR 路径与该结构共用。正确性由 `gainmap_preservation` 的「缓存重试 == 独立低质量编码」逐字节断言保障。
- `probeImageMemory` 接受 `BytesView` 视图，重建路径不再为校验整份复制输出；`orientPixels` 翻转改原地 swap、转置分块；色度子采样 NEON（640 万例逐值一致）。
- **不要**去"优化" `encodeJpegToMem` 的缓冲接管：`jpeg_mem_dest` 的最终指针/长度只在 `term_mem_destination`（压缩结束）写回，提前读取会把输出截断到初始缓冲大小；实测收益仅 1–2%（26MP q90：78.89ms → 76.91ms），且现有测试固件都小于 256KB 初始缓冲、测不出截断。保持由 libjpeg 自管缓冲。
- 已实测并否决的伪优化，勿重复尝试：容器重复解析（单次 0.01–0.03ms，占 26MP 整次转码 2126ms 的 0.002%；且 HEIF 的「双解析」是 `hasExif` 的唯一来源，`uhdr_dec_get_exif` 对 HEIF/AVIF 返回 null）；`parallelRows` 进程级线程池（spawn+join 0.03ms/次）；依赖库 LTO。
- 构建路径脱敏：`native/CMakeLists.txt` 的 `-ffile-prefix-map` 必须先用 `get_filename_component(... REALPATH)` 把 `${CMAKE_CURRENT_SOURCE_DIR}/..` 归一到仓库根再映射——字面量 `.../native/..` 不是 `.../third_party/libultrahdr/...` 的文本前缀，否则 vendored 源码的 `__FILE__`（assert 串）会残留构建机绝对路径。
- 2026-10-17 批次完整数据、验证结论与仍未立项的缺口见 [docs/TODO-optimizations.md](docs/TODO-optimizations.md) 与 [docs/reports/2026-10-17-optimization-batch.md](docs/reports/2026-10-17-optimization-batch.md)。

## 正确性与验证批次（2026-10-24）

- JXL 的 `jhgm` 必须由 `parseJhgmLayout` 做剩余长度校验，probe 与 preserve 共用；存在但损坏的 gain map 明确报错，不得当成 SDR。libjxl 默认已应用 codestream orientation，SDR/预览只对 JPEG 额外应用 EXIF 旋转。`scaledSize` 使用 64 位中间乘法；JPEG mem destination 错误路径在 destroy/free 前同步当前指针，不能改成已否决的成功路径缓冲接管。
- 当前编码仍不保留主图 alpha：HEIF/AVIF/JXL 含 alpha 时 SDR 和 HDR preserve 均返回 errorCode 33 并保持输出为空，不能静默丢通道；预览仍允许。完整 alpha 编码支持另立项，透明 AVIF 及透明 HDR HEIF/AVIF 的独立端到端覆盖仍待补。
- `OutputStore.save` 默认统一推进替换日志和队列；`ConversionRunner` 传 `finalizePublication=false` 并自行做专用 checkpoint，避免批量双写。history 保存状态落地后，队列与替换收尾不可被页面取消打断。历史回收缓存以 `(id, inputUri)` 为键，发起回收前重查当前行；重新关联的 Provider 检查只在 IO 收集器执行。
- JPEG 实况 XMP 合并使用一次精确分配，不再叠加约 3 倍静图堆；自动替换回调使用 `ReplacementDao.byIds` 和批量 `originalStates()`。详情卷帘比例只保留一个 `SliderState`，不在卡片根组合期逐帧读取。
- 发布/补丁 Python 校验不得依赖 `assert`（`python3 -O` 也必须失败）。原生依赖并发由 `ULTRASLIM_DEPS_TOTAL_JOBS`（默认 4）和 `ULTRASLIM_DEPS_PROJECT_JOBS`（默认 2）约束，内层含 dav1d Ninja 均显式限流。截图任务通过 `PreviewScreenshotTestEngineInput.referenceImageDir/projectRoot` 传绝对路径并固定 UTF-8；基线更新必须先检查实际差异。Android JNI 格式往返覆盖 codec 0–3，Room schema 同时打入 androidTest assets 供真实迁移测试。
