# 优化待办

来源：2026-10-13 全项目优化审计；2026-10-17 第二批次已按实测数据收敛；2026-10-24 修复第三轮审查问题。

## 已完成（2026-10-24 批次）

- **JXL 正确性与边界**：probe/preserve 共用有界 `jhgm` 解析，修复 ISO 末尾一字节越界；损坏 gain map 不再回退 SDR。JXL 不再叠加 EXIF 方向；`scaledSize` 使用 64 位中间值。JPEG mem destination 错误路径先同步当前指针再清理，保留成功路径原实现。
- **透明输入不再静默丢通道**：HEIF/AVIF/JXL 主图含 alpha 时，SDR/HDR preserve 返回 errorCode 33 且输出为空，预览仍可用。host 覆盖透明 JXL/HEIC、截断 `jhgm`、JXL codestream+EXIF 方向组合、极端尺寸及 gain map 自身带 alpha 的误拒检查。
- **保存与回收一致性**：`OutputStore.save` 默认统一更新替换日志和队列，Runner 专用 checkpoint 避免双写；保存完成后收尾不可取消。历史回收缓存按 `(id,inputUri)` 隔离并防旧异步结果回写，回收前重查当前行；重新关联不再在主线程重复做 Provider I/O。
- **Room v7**：显式回填旧任务 `result_hasGainMap/hasExif/maxContentBoost`，保留空结果和已有值；v3/v6 真实旧 schema 数据用例覆盖读取结果，修正“NULL 自动回退 Kotlin 默认值”的错误约束。
- **内存与批量 UI**：JPEG 实况 XMP 合并改为一次精确分配；自动替换结果回调改为分块 Room + 批量 MediaStore 查询；卷帘比例使用单一 `SliderState`，避免根组合逐帧读取。
- **构建与门禁**：Python `assert` 校验改为显式失败；依赖外层/内层构建并发可配置且受限；Android JNI 往返加入 JXL；标准 `verify.sh` 纳入 5 张截图。修复截图引擎 `PreviewScreenshotTestEngineInput.*` 路径配置，检查并更新 4 张既有自动保存文案基线。
- **验证**：`scripts/verify.sh` 连续三次全量通过（原生 5 项 CTest、JVM 单测、5 张截图、Debug/Release、Debug/Release lint、真实 APK 16KB 对齐）。ASan/UBSan `preview_probe` 通过；macOS 不支持 `detect_leaks`，未计泄漏检测。设备测试包已编译；本机无连接设备，未执行设备端迁移、回收、自动替换或 JXL JNI 测试。

## 已完成（2026-10-17 批次）

- **C1 内存峰值 / D1 重试重做转换**：新增 `PreparedBasePlanes` / `PreparedGainMapPlanes`，
  把 RGBA→YUV 转换（与质量无关）提到 `encodeWithRetry` 之外，转换后立即 `clear + shrink_to_fit`
  释放 RGBA；retrySmaller 只重跑编码器。结构安全性由 libheif 源码确认：编码器只读平面
  （`heif_image_get_plane_readonly`），`extend_padding_to_size` 的 `const_cast` 只增容量不改可见像素，
  `heif_image_add_plane` 有重复断言（故不可能也无需按次重建图像）。正确性由
  `gainmap_preservation` 的「缓存重试 == 独立低质量编码」逐字节断言保障。
- **C2 JXL 整幅 RGB 拷贝**：改为 `prepareJxlRgb` 在重试外转换一次；libjxl 的
  `JxlEncoderAddImageFrame`（非流式）内部会 `CopyBuffers()`，缓冲无需活过调用。
- **C4 输出校验整读**：`probeImageMemory` 接受 `BytesView` 视图，去掉重建路径的整份输出拷贝。
- **C3 orientPixels**：翻转改原地 swap，转置分块处理；1690 例等价性验证。
- **D2 色度子采样 NEON**：640 万例逐值一致。
- **encOpts 判空**：libheif options 分配失败不再解引用空指针。

## 已实测并决定不做（收益低于风险）

- **D3 encodeJpegToMem 三段拷贝**：实测 26MP 噪声图 q90/95/100 下，接管 libjpeg 缓冲
  仅比现有 `out.assign` 快 1–2%（78.89ms → 76.91ms）。用预分配 vector 交给 `jpeg_mem_dest`
  的方案**有正确性陷阱**：`term_mem_destination` 只在压缩结束才写回最终指针/长度，提前读取会把
  输出截断到初始缓冲大小（实测 B 方案在 q90 产出 13,000,704 B，而真实码流 23,254,732 B；
  现有测试固件都小于 256KB 初始缓冲，测不出来）。收益与风险不成比例，**保留原实现**，
  已在代码注释中记录原因。
- **B2/B3 容器重复解析**：实测单次容器解析 0.01–0.03 ms（HEIF/AVIF/JPEG 均如此；最大的
  32MB 文件 2.5 ms 也主要是磁盘读）。一次 26MP 真实转码耗时 **2126 ms**，故即便省掉全部
  4 次解析也只占 **0.002%**。另外 probe 里 HEIF 的「双解析」并非冗余：实测
  `uhdr_dec_get_exif` 对 HEIF/AVIF 返回 null（`uhdr.hasExif=no`）而 `probeHeifDims` 能读到
  `hasExif=yes`，即那次解析是**唯一**的 EXIF 来源，删掉会丢失该字段。**不做**。
- **E1 parallelRows 线程池**：实测 3 线程 spawn+join 开销 **0.03 ms/次**，每次转码约 5 次调用
  （≈0.15 ms），占 2126 ms 的 **0.007%**。换进程级线程池会引入静态析构顺序、fork 安全、
  JNI 环境等新风险。**不做**。
- **E2 增益图 grid tile 并行**：需改 libheif 补丁，侵入性高，维持不做。
- **F2 依赖库 LTO**：收益限于库内，构建时间翻倍，维持不做。

## 顺带修复的真实缺陷（本批次发现）

- **`jniLibs.excludes` 的同步盘防御其实是空转**：原模式 `**/* [0-9].so` 与
  `**/* [0-9].*[0-9].so` 在 Gradle 的 Ant 风格语义里，`[0-9]` 是「匹配 `0`、`-`、`9` 之一」
  的字符类，**不是数字范围**，实测两个模式一个文件都排除不掉。后果：Debug APK 里实际打进了
  `libandroidx.graphics.path 2.so`（陈旧冲突副本，来自 `app/build/intermediates/cxx/.../obj`）。
  改用 `**/* *.so`（冲突副本一律含空格；实测排除 4 个副本、对 6 个正常文件名零误伤），
  并清理了 `native/prefix` 下 50 个陈旧副本。**注意：项目在同步目录期间此防御不能撤。**
- **`-ffile-prefix-map` 源路径未归一，漏掉 vendored libultrahdr**：原写法
  `"-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}/..=/ultraslim-src"` 里的字面量
  `.../native/..` 并不是 `.../third_party/libultrahdr/...` 的文本前缀，所以对 vendored 源码
  完全不生效——实测 debug `.so` 里残留
  `.../third_party/libultrahdr/lib/src/dsp/arm/gainmapmath_neon.cpp`（104 字节，来自
  `assert` 里的 `__FILE__`；release 因 `NDEBUG` 去掉 assert 才看不到）。改为先
  `get_filename_component(... REALPATH)` 归一到仓库根再映射，复测 debug `.so` 中
  `/Users/<user>` 与仓库目录名 均 **0 次**，仅剩预期的 `/ultraslim-src`。

## 已完成（2026-10-23 批次）

- **流式 `readHeifXmp`**：优先使用 `HeifFdReader` 按需 `pread` 容器头与 ~1KB XMP 块，非 HEIF/AVIF（JPEG/JXL）直接早退，不再通过 `readAll(fd)` 把 15–40MB 实况视频尾部或完整 JXL 读入内存。
- **保存与自动替换去重**：`OutputStore.save` 复用 `MediaStoreSaver.save` 发布时的 `matches` 校验与 `AutoReplacement.stage` 的私有成品指纹；`AutoReplacement.stage` 复用 `ConversionRunner` 已探测的 `motionInfo`；`SourceMedia.timestamps` 对相册项单次查询 `DATE_TAKEN` 与 `DATE_MODIFIED`。转换前仍重新 probe，防止持久化队列引用的原图变化后使用旧尺寸/HDR/EXIF；指纹与自动替换校验完成后重新检查前台及批次取消状态，再决定是否发布。
- **HDR Preserve 路径提前释放输入内存**：`tryReuseGainMap` 在展开像素/进入缩放与编码前释放 `uhdr_decoder`、完整压缩输入 `data` 及中间 `baseBytes`/`gmBytes`/`jhgm`。
- **SDR 输出校验免整读（B1）**：SDR 路径尺寸与 EXIF 在编码前已确定，改为 `fstat` + 16B 容器魔数 `pread` 校验，不再 `probeImage(outFd)` 整读输出文件。
- **CPU 热点与并行**：`storeYuv444_8` NEON 4:4:4 YCbCr 转换（兼容增益图与奇数尺寸 HDR AVIF 主图，与标量逐值一致）；`prepareJxlRgb` 与 JXL 增益图转换改走 `parallelRows`；`resizeRgbaPlane` 双线性权重外提至通道循环外。
- **UI 状态与缓存分池**：`HomeUiState` 预计算 `convertibleCount`、`hasFinishedItems` 与分组列表并经 `flowOn(Dispatchers.Default)` 移出主线程；`HistoryScreen` 缓存 `selectedItems` / `pendingCount`；`PreviewCache` 拆分为缩略图池（12MB）与详情大图池（28MB），并按 16 次写入节流磁盘 `trim`。
- **构建与启动**：`native/CMakeLists.txt` 为 `core`/`image_io` 补齐 `-fvisibility=hidden -fvisibility-inlines-hidden`；新增 `app/src/main/baseline-prof.txt` ART 基准配置。

## 已完成（2026-10-24 批次）

- **导入批量更新与相册元数据复用**：每 32 张探测统一更新开始/结束状态，避免每张两次全队列映射；工作线程已领取或已删除的项目不被迟到结果覆盖。相册查询同时带回名称和大小，分享/目录入口继续使用 provider 查询兜底。
- **预览并发复用**：同 URI 未命中请求取得锁后重新检查内存、磁盘与更大尺寸缓存，使等待期间完成的详情预览可供缩略图复用。
- **详情原图重关联**：复用唯一 IO 查询订阅，结果绑定输入 URI；旧 provider 查询迟到不能污染新原图的回收站状态。
- **待保存队列分页**：两个消费端共享批次级轻量通知，按 `(createdAt,id)` 每页 32 条获取待发布成品；保留同批次新增及取消通知、旧配置默认值和每张保存前的实时检查，无数据库结构变更。
- **HEIF/JXL 内存**：HEIC/AVIF 主图从解码平面直接缩到最终尺寸，避免额外完整 RGBA 副本；增益图维持原分辨率。JXL 在编码器复制输入后释放不再需要的像素，重试所需 RGB 保留，jhgm 缓存改为移动。

验证与设备边界见 [2026-10-24 优化记录](reports/2026-10-24-optimization-batch.md)。

## 仍未做（需独立立项）

1. **#20 queryImages 物化整库 URI**：当前只在「整个图库扫描」入口使用（一次性遍历+逐张 probe），
   改为直接消费 Flow 收益有限。
2. **#7 ConversionRunner 每张图 2 次 `batch()` 取消标志查询**：PK 查询开销小，缓存有 stale 风险。
3. **#21 PreviewCache LruCache 逐出不 recycle**：native heap 靠 GC，通常可接受。
4. **coil 后续 minor 升级**：沿用本次模式（只用到 AsyncImage/ImageRequest/crossfade 稳定 API，
   升级后仍需真机过一遍列表/详情图片加载）。
8. **完整 alpha 编码支持**：当前仅做到透明输入明确拒绝。需实现 HEIF alpha 平面、JXL alpha extra channel，
   并补透明 AVIF、透明 HDR HEIF/AVIF 的独立端到端固件与平台验证。
9. **JPEG 扩容后错误故障注入**：错误清理已修正，但 malloc 失败路径尚未由专门故障用例触发；
   后续用 ASan/分配故障注入验证双重释放与泄漏，不改动正常成功路径。
10. **设备端验收**：在专用 Android 设备执行 v3/v6 迁移、手动保存、重新关联回收、自动替换、
    JXL JNI 与 Adaptive/手势回归；本批仅完成设备测试包编译。

## 已确认不再需要

- **release signingConfig**：`app/build.gradle.kts:83` 已有 `publicRelease` 配置。
- **wrapper 对齐**：`gradle-wrapper.properties` 已固定 9.6.0，与 AGP 9.4.1 一致。

## 用户需求待评估（2026-10-23）

- **视频瘦身**：已记录公开版用户需求，当前版本仍只转码照片。需单独评估视频编码/容器、HDR 与色彩元数据、音轨、动态照片关联、硬件编码可用性、耗时/发热和中断恢复，不纳入本轮照片可靠性修复，也未承诺交付日期。
