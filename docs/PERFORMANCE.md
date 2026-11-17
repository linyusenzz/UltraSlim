# 性能测量与恢复验证

先保留基线，再使用相同照片、质量、速度、缩放和相册兼容选项对比。记录设备系统、测试包版本、批次规模及热状态；模拟器功能回归不能代替真机耗时、发热或相册视觉验收。当前未获得本轮真机性能数据，不宣称提升百分比。

## 转码分阶段日志与内存采样

使用专用 ARM64 测试设备，先安装并打开独立 `app.ultraslim.verifyqa` 或 `app.ultraslim.benchmark`，保持应用进程运行，然后执行：

```bash
python3 scripts/collect_performance.py --serial <device-serial> \
  --package app.ultraslim.verifyqa --seconds 120 --interval 2 \
  --output-dir /path/to/new-capture-directory
```

`--output-dir` 必须尚不存在，避免覆盖旧记录；可用 `--adb /path/to/adb` 指定工具。开始采集后，在 QA 应用中执行准备好的同一批照片。进程退出或重启会终止本次采集，需重新开始。

产物保留原始 `timings.log`、带原始 `dumpsys meminfo` 的 `memory.jsonl`、采集状态 `capture.json` 和汇总 `summary.json`。PSS 峰值是采样点中的最大值，可能错过短时内存峰值；缺失样本不填零。计时含诊断开销，不能与关闭诊断的跑分直接混用。汇总按格式和结果分组，保留中位数与 nearest-rank P95；少量样本的 P95 不代表稳定的长批次表现。

过滤采集前旧日志的边界来自设备 `date +%s.%N`，与 logcat 使用同一设备时钟；`capture.json` 同时保留设备时间原字符串。主机 `startedUtc` 仅作记录，不能与手机日志时间直接比较。设备不支持该时间格式时，采集器在修改诊断开关前停止，不使用主机时间猜测边界。时钟偏移回归使用合成日志，本轮尚未在设备验证采集命令。

采集器临时开启 `log.tag.UltraSlimPerf=DEBUG` 与 `debug.ultraslim.trace=1`，正常退出、异常或 Ctrl+C 后会尝试恢复原值。检查 `capture.json` 中的 `status`、`restoreErrors`；主机被强制关机或直接结束进程时，清理无法保证执行。

Kotlin `conversion` 记录探测、原图/成品指纹、动态视频、编码、平台校验、保存和检查点等阶段。`native_transcode` 包含 `ImageWork` 排队、预算检查和文件描述符准备，不是纯 JNI 编码时间。`image_work.waitMs` 是进入原生工作队列前的等待，`laneMs` 包含既有诊断文件写入。原生记录进一步区分读取、解码、转换、编码/重试和写出；父子阶段可嵌套，不应相加为总耗时。恢复已有成品或跳过照片时，缺少编码阶段是正常现象。

主机原生诊断可单独开启：

```bash
ULTRASLIM_TRACE=1 "$ULTRASLIM_BUILD_ROOT/host-test/transcode_test" input.jpg output.heic
```

Android 原生开关为 `debug.ultraslim.trace`；优先让采集器管理开关和恢复，不修改编码选项。日志不写图片 URI、路径或内容，但原始设备诊断仍应自行检查后再共享。

## Baseline Profile 与 Macrobenchmark

`benchmark/` 包含两种互斥测试变体：

| 变体 | 被测应用 | 用途 |
| --- | --- | --- |
| `baseline` | 非 debuggable、不经 R8 | 采集保留源符号的 Profile |
| `benchmark` | 非 debuggable、开启 R8/资源压缩 | 测量接近 Release 的启动和导航 |

两者被测包都是 `app.ultraslim.benchmark`，按需安装对应变体；测试 APK 为 `app.ultraslim.benchmarks`，采用 self-instrumenting，运行在自己的进程。它们使用本地 Debug 签名，不覆盖日常 `app.ultraslim`。

先完成 [原生依赖构建](BUILDING.md#原生依赖首次构建)，连接专用 ARM64 真机并设置 `ANDROID_SERIAL`：

```bash
# Profile 生成要求 API 34+；产物先检查，不自动覆盖现有文件。
./gradlew :benchmark:connectedBaselineAndroidTest -Pkotlin.incremental=false

# 对比无 Profile 与包内 Profile 的冷启动，并测量导航帧耗时。
./gradlew :benchmark:connectedBenchmarkAndroidTest -Pkotlin.incremental=false
```

报告位于 benchmark 模块的构建目录，外置构建时为 `$ULTRASLIM_BUILD_ROOT/benchmark/outputs/` 和 `reports/`；设备附加输出包含生成的 Profile、JSON 和 trace，实际路径见 Gradle 日志。当前工作负载只有启动、设置及两个标签之间的导航，不覆盖千张队列、大图库滚动或详情缩放。未在真机完成采集和对照前，保留现有 `app/src/main/baseline-prof.txt`，不把模块编译成功当作 Profile 或性能收益已验证。

测量方法参考 [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)；不要屏蔽模拟器、低电量或目标应用配置错误后再把结果作为正式基线。

## 数据迁移与真正的进程中断

`StatefulMigrationTest` 用签入的 v5 schema 创建独立磁盘数据库，包含 PENDING/SAVED/FAILED 历史、队列、批次和替换记录，验证迁移到 v6、队列恢复和关闭重开。该测试可随普通 Android 仪器化测试运行；它不需要真实用户数据库。

`ProcessRecoveryTest` 的两个阶段默认跳过，必须由外部脚本编排。先构建并安装匹配的 `.verifyqa` 应用和测试 APK：

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest \
  -PultraslimDebugSuffix=.verifyqa -Pkotlin.incremental=false
python3 scripts/check_process_recovery.py --serial <dedicated-device-serial>
```

脚本只允许固定 QA 包，使用独立 `process-recovery-fixture.db`。第一阶段在真实 Room 事务已经写入、尚未提交时留下就绪标记；脚本 force-stop 该应用，第二阶段在新进程检查未提交变更回滚、已提交结果保留及队列恢复再次落盘。日志默认位于 `build/process-recovery/`。这验证数据库事务与队列重启，不涵盖所有 MediaStore 发布/回收边界，也不等于真实用户照片升级验收；正式应用中没有新增中断开关。

本轮新增设备测试设施尚未在真机执行。仅编译测试 APK、运行 Python 语法检查，不能记为上述中断场景已经通过。
