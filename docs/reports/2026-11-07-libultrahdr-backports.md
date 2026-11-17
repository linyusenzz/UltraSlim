# libultrahdr 选择性移植与 Apple 分支审查（2026-11-07）

## 本次源码改动

保留 v2.0.2 基础及 UltraSlim 的质量、速度、线程、色彩和无效 tmap 元数据保护补丁。

| 来源 | 改动 | 本次验证 |
| --- | --- | --- |
| [#491](https://github.com/google/libultrahdr/pull/491) | Apple XMP headroom 按线性值读取，Adobe 保持 exp2 | 实际解析器回归通过；恢复旧 Apple 算法后相同测试失败 |
| [#499](https://github.com/google/libultrahdr/pull/499) | 先检查 EXIF 相对偏移再相加，限制 Maker Notes IFD 偏移 | 有效 EXIF 与 40 组无效偏移通过；本机为 64 位，未复现 32 位 ASan 情况 |
| [#498](https://github.com/google/libultrahdr/pull/498) | probe 和完整解码检查主图/增益图尺寸；复制前检查实际解码平面 | 已完成源码适配与审阅，HEIF 构建和运行回归未完成 |
| [#515](https://github.com/google/libultrahdr/pull/515) | 增益图生成先填充队列，部分线程启动失败后由已有线程和调用线程完成，join 所有已启动线程 | JPEG-only 核心编译通过；本机未运行线程启动故障注入 |

#498 的 probe 适配使用函数内尺寸谓词，避免引入上游后来增加的运行时路由体系；保留本地无效 tmap 解析回退。#515 仅覆盖生成增益图的三个启动阶段，不代表所有线程代码都能恢复。

新增 [upstream_metadata.cpp](../../native/host_test/upstream_metadata.cpp)，注册为 host CTest。上游 GoogleTest 文件同步加入 #491/#499 的测试和样本预期修正。

## 实际验证范围

- Windows UCRT GCC 16.1，Release、静态 core/image_io、系统 libjpeg、HEIF/GLES 关闭：核心编译通过。
- 独立链接真实核心运行 upstream_metadata：Apple XMP 的 4 倍 headroom、EXIF headroom、40 组异常偏移及 Adobe 元数据往返通过。
- 在 build 目录临时恢复旧 exp2 算法并重新链接同一测试：Apple 4 倍预期失败（负对照）。未改变工作区修复。
- 仓库结构、链接、素材与许可证校验通过；git diff --check 通过。
- 本机缺 native/prefix/host 和 Android 依赖；完整 host CTest、HEIF/AVIF 和 Android APK/设备回归未完成。JPEG-only 编译不能验证被条件编译排除的 HEIF 代码。
- 上游完整 GoogleTest 配置另受缺失 tests/data 阻碍，未将上述定向测试冒充完整单测通过。

当前改动是待完整回归的源码移植，没有发布或安装 APK。

## Apple 导出分支审查

审查对象：[ae0fa11e7275a71b98973fe52c6b787c5cd22ece](https://github.com/gregbenz/libultrahdr/tree/ae0fa11e7275a71b98973fe52c6b787c5cd22ece)，分支 codex/apple-jpeg-gainmap-export。

这个分支引入 uhdr_dec_export_gainmap：
- ISO/Adobe JPEG 增益图保留原码流。
- Apple-only JPEG 转成主图全尺寸灰度增益图，再以 JPEG Q100 输出；包含数值转换和有损重编码。
- 只支持 JPEG 输入。导出参数保证 SDR 端点，近似完整 headroom 端点；中间显示亮度采用 ISO 适配规则。
- 原始 Apple 增益图及其解析元数据，不被该分支视为可直接用于 ISO 重组的一对组件。

当前 UltraSlim 在没有 ISO 数据时，把解析元数据交给 isoFromMetadata，同时保留原增益图像素。对 Apple-only 输入，这个假设需要独立验证。此次 #491 修复的是参数读取，不等于完成 Apple → ISO 的亮度一致性验证。已有基于相同库的输入/输出一致性测试不能独立证明 Apple 渲染正确。

需要特别核对：
1. 分支使用 gain^2.2；[Apple 官方说明](https://developer.apple.com/documentation/appkit/applying-apple-hdr-effect-to-your-photos)写的是 Rec.709 逆传递函数。两者不能仅凭代码内部 oracle 一致便判为等价。
2. 该分支优先从完整 Maker Notes 读取 headroom，再回退 XMP；现有 #491 路线优先 XMP。其 Apple 新样本导出测试预期为 8，而独立 #491 XMP 预期约为 4.532783，必须比较实际 Apple 解码结果。
3. 新的 JPEG 主图重建还涉及色度中心采样和扩展范围 sRGB，会改变渲染结果；需要与增益图转换分开测量。
4. 增益图升到全尺寸并 JPEG 重编码不符合当前严格逐像素保留承诺，且可能增加内存和体积。

决定：暂不引入 Apple 导出分支或改变默认 preserve 行为。下一步需要在 macOS 使用 ImageIO/Core Image 对 Apple-only JPEG、ISO JPEG 分别输出线性 HDR，比较多个显示 headroom、亮暗区域和色彩；保留原文件并明确标记所有转换。参考输出获得之前，不宣称 Apple-only 转 ISO 已验收。
