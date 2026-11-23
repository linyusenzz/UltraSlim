package app.ultraslim.convert

import app.ultraslim.model.OutputFormat
import app.ultraslim.util.outputName
import app.ultraslim.util.ExportNames
import android.Manifest
import android.app.PendingIntent
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.ultraslim.R
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.bridge.TranscodeResult
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.*
import app.ultraslim.util.formatBytes
import java.io.File
import java.nio.channels.FileChannel
import kotlinx.coroutines.*

/** Durable, sequential batches. Each result is checkpointed before publishing to the album. */
internal class ConversionRunner(
    private val applicationContext: Context,
    private val thermalStatus: () -> Int = {
        applicationContext.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
    },
    private val setForeground: suspend (ForegroundInfo) -> Unit,
) {
    enum class Outcome { COMPLETE, FAILED, COOLING, PAUSED }

    internal var coolingAttempt = 0
        private set
    private var coolingItemId: String? = null

    internal fun currentThermalStatus() = thermalStatus()

    private fun memoryInfo() = ActivityManager.MemoryInfo().also {
        applicationContext.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }

    /**
     * 内存准入：批量内上一张的原生大块内存归还 OS 有滞后，先按退避复测（合计约 9s）再判死，
     * 避免上张刚结束时的瞬时紧张把后续条目连环误拒。判死时按「估算是否超过本机理想容量」
     * 区分「本机放不下」与「当下紧张」两种文案。
     */
    private suspend fun awaitMemoryAdmission(probe: ProbeResult, inputBytes: Long) {
        var mem = memoryInfo()
        for (attempt in 1..MEMORY_ADMISSION_RECHECKS) {
            if (ConversionBudget.canStart(probe.width, probe.height, probe.gainmapWidth, probe.gainmapHeight,
                    probe.hasGainMap, inputBytes, mem.availMem, mem.totalMem, mem.threshold, mem.lowMemory)) return
            delay(MEMORY_ADMISSION_DELAY_MS * attempt)
            mem = memoryInfo()
        }
        val estimate = ConversionBudget.estimateBytes(probe.width, probe.height, probe.gainmapWidth,
            probe.gainmapHeight, probe.hasGainMap, inputBytes)
        if (estimate >= ConversionBudget.deviceCapacityBytes(mem.totalMem, mem.threshold)) {
            error("这张照片需要的内存超出本机可用上限，无法安全转码；已保留原图")
        }
        error("系统内存暂时紧张，已保留原图；请稍后重试")
    }

    internal suspend fun pauseCooling(): Outcome {
        val app = applicationContext as UltraSlimApp
        val state = app.conversionThermalState.value ?: return Outcome.FAILED
        val paused = state.copy(phase = ThermalPhase.PAUSED, remainingSeconds = 0)
        app.conversionThermalState.value = paused
        coolingItemId?.let { id ->
            app.conversionRepository.update(id) {
                if (it.status == QueueStatus.DONE || it.status == QueueStatus.SKIPPED) it
                else it.copy(status = QueueStatus.READY, error = paused.message)
            }
        }
        app.conversionRepository.flush()
        return Outcome.PAUSED
    }

    companion object {
        const val UNIQUE_WORK_NAME = "conversion"
        const val CHANNEL_ID = "conversion"
        const val NOTIFICATION_ID = 42
        private const val SUMMARY_ID = 43
        const val KEY_BATCH = "batch_id"
        private const val MEMORY_ADMISSION_RECHECKS = 3
        private const val MEMORY_ADMISSION_DELAY_MS = 1500L
        private const val FOREGROUND_UPDATE_MIN_INTERVAL_MS = 250L
    }

    private fun isSeekableRegularFile(fd: java.io.FileDescriptor): Boolean = runCatching {
        android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd).st_mode)
    }.getOrDefault(false)
    private suspend fun withSourceFile(source: Uri, read: suspend (ParcelFileDescriptor, FileChannel) -> Unit) =
        withContext(Dispatchers.IO) {
            val descriptor = applicationContext.contentResolver.openFileDescriptor(source, "r")
                ?: error("无法读取原图，请重新选择或授权")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { read(descriptor, it.channel) }
        }

    fun getForegroundInfo() = createForegroundInfo(0, "正在准备转换…", null, true)

    suspend fun run(batchId: String, runAttemptCount: Int = 0): Outcome {
        val app = applicationContext as UltraSlimApp
        val repo = app.conversionRepository
        repo.awaitReady()
        val batch = app.database.taskDao().batch(batchId) ?: return Outcome.FAILED
        if (batch.cancelled) return Outcome.COMPLETE
        // 损坏的批次快照必须显式失败并留下错误信息，不能在 checkpoint 循环外静默死亡。
        val snapshot = decodeBatchSnapshot(batch)
        val ids = snapshot.itemIds
        val options = snapshot.options
        if (!snapshot.isValid || options == null) {
            val affectedIds = ids.toMutableSet().apply {
                repo.queue.value.filter { it.batchId == batchId }.mapTo(this) { it.id }
            }
            repo.updateAll(affectedIds) {
                if (it.batchId != batchId || it.status == QueueStatus.DONE || it.status == QueueStatus.SKIPPED) it
                else it.copy(status = QueueStatus.FAILED, error = BATCH_SNAPSHOT_ERROR)
            }
            repo.flush()
            return Outcome.FAILED
        }
        try { setForeground(getForegroundInfo()) } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 重试批次中已完成项不能被覆盖；只标记尚未落地的条目。
            repo.updateAll(ids) { if (it.status == QueueStatus.DONE || it.status == QueueStatus.SKIPPED) it
                else it.copy(status = QueueStatus.FAILED, error = "后台任务启动失败，请回到应用重试：${e.message}") }
            repo.flush()
            return Outcome.FAILED
        }
        var done = 0; var saved = 0L
        var thermalAttempt = runAttemptCount
        val started = android.os.SystemClock.elapsedRealtime()
        var lastForegroundAt = 0L
        val batchDirectoryCache = SelectedOutput.DirectoryCache()
        try {
            for ((index, id) in ids.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (app.database.taskDao().isBatchCancelled(batchId) != false) break
                val item = repo.find(id) ?: continue // Removed pending items never run.
                if (item.status == QueueStatus.DONE || item.status == QueueStatus.SKIPPED) continue
                val status = currentThermalStatus()
                if (ConversionThermalPolicy.requiresCooling(status)) {
                    coolingAttempt = thermalAttempt
                    coolingItemId = id
                    val state = ConversionThermalState(status, ThermalPhase.COOLING,
                        ConversionThermalPolicy.remainingWaitSeconds(thermalAttempt))
                    app.conversionThermalState.value = state
                    // This item has not begun encoding. Keep it resumable while cooling.
                    repo.update(id) { it.copy(status = QueueStatus.READY, error = state.message) }
                    repo.flush()
                    android.util.Log.i("UltraSlimConversion", "thermal status=$status attempt=$thermalAttempt remaining=${state.remainingSeconds}s")
                    if (thermalAttempt >= ConversionThermalPolicy.MAX_RESUMES) return pauseCooling()
                    return Outcome.COOLING
                }
                // A later hot period gets its own bounded wait, even within one batch.
                thermalAttempt = 0
                app.conversionThermalState.value = null
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (index == 0 || nowElapsed - lastForegroundAt >= FOREGROUND_UPDATE_MIN_INTERVAL_MS) {
                    val elapsedSec = (nowElapsed - started) / 1000.0
                    val speedText = if (done > 0 && elapsedSec > 0.5) {
                        val rate = done / elapsedSec
                        val remaining = ids.size - index
                        val etaSec = (remaining / rate).toInt()
                        String.format(java.util.Locale.ROOT, " · %.1f 张/秒 (约 %d 秒)", rate, etaSec)
                    } else ""
                    val detailText = if (saved > 0) "已省 ${formatBytes(saved)}$speedText" else "当前图片完成后可停止$speedText"
                    setForeground(createForegroundInfo(index * 100 / ids.size.coerceAtLeast(1), "正在转换 ${index + 1}/${ids.size}", detailText, false))
                    lastForegroundAt = nowElapsed
                }
                if (repo.claim(id, batchId) == null) continue
                val performance = ConversionPerformance(options.format.name, status)
                try {
                    performance.measure("claim_checkpoint") { repo.flush() }
                } catch (e: Exception) {
                    performance.finish(if (e is CancellationException) "INTERRUPTED" else "CLAIM_CHECKPOINT_FAILED", ::currentThermalStatus)
                    throw e
                }
                try {
                    var existing = app.database.historyDao().byTask(id)
                    if (existing != null) {
                        if (options.autoReplaceOriginals && existing.saveState != "SAVED" &&
                            app.database.replacementDao().byId(existing.id) == null) {
                            AutoReplacement.stage(app, existing, originalHash = null)
                            existing = app.database.historyDao().byId(existing.id) ?: existing
                        }
                        var verifiedSave = false
                        if (options.autoSave && app.database.taskDao().isBatchCancelled(batchId) == false &&
                            app.galleryPublishingAllowed.value) {
                            val recoveredInput = existing
                            existing = performance.measure("publish_recovered") {
                                OutputStore.saveInternal(app, recoveredInput, finalizePublication = false,
                                    knownOptions = options, directoryCache = batchDirectoryCache)
                            }
                            verifiedSave = existing.saveState == "SAVED"
                        }
                        if (options.autoReplaceOriginals) AutoReplacement.saved(app, existing, outputVerified = verifiedSave)
                        val recovered = existing
                        val recoveredResult = item.result ?: TranscodeResult(
                            0, "", recovered.hadGainMap, recovered.width, recovered.height,
                            recovered.gainmapWidth, recovered.gainmapHeight, recovered.inputBytes,
                            recovered.outputBytes, recovered.durationMs, recovered.hadGainMap,
                            recovered.hasExif, recovered.maxContentBoost,
                        )
                        repo.update(id) {
                            it.copy(
                                status = QueueStatus.DONE,
                                progress = 100,
                                result = recoveredResult,
                                outputUri = recovered.outputUri,
                                historyId = recovered.id,
                                error = if (recovered.saveState == "FAILED") "转换完成，保存失败；可在详情重试保存"
                                    else if (recovered.saveState != "SAVED") {
                                        if (options.autoSave) "已转换，回到应用后自动保存" else "待保存，可直接分享"
                                    } else null,
                            )
                        }
                        done++; saved += recovered.inputBytes - recovered.outputBytes
                        continue
                    }
                    val source = Uri.parse(item.uri)
                    withSourceFile(source) { sourcePfd, sourceChannel ->
                        val seekable = isSeekableRegularFile(sourcePfd.fileDescriptor)
                        // 队列可跨重启保留，原 URI 的内容可能已被相册编辑；预算与输出校验必须使用当前元数据。
                        val probe = performance.measure("input_probe") {
                            ImageWork.run { UltraHdrBridge.nativeProbe(sourcePfd.fd) }
                        }
                        check(probe.isSuccess) { probe.error }
                        if (item.probe != probe) repo.update(id) { it.copy(probe = probe) }
                        if (!probe.hasGainMap && !options.allowSdr) {
                            repo.update(id) { it.copy(status = QueueStatus.SKIPPED, error = "按设置跳过 SDR 图片") }
                            return@withSourceFile
                        }
                        val sourceDates = withContext(Dispatchers.IO) {
                            SourceMedia.timestamps(
                                app, source, hasExif = probe.hasExif,
                                descriptor = if (seekable) sourcePfd.fileDescriptor else null,
                            )
                        }
                        val motionInfo = performance.measure("motion_detect") {
                            withContext(Dispatchers.IO) {
                                if (seekable) MotionPhotoHelper.detect(sourcePfd.fd, sourceChannel)
                                else MotionPhotoHelper.detect(app, source)
                            }
                        }
                        // 非相册原图（如 SAF 目录或无持久原图引用的临时输入）无法自动替换，跳过转换前整读哈希。
                        val canAutoReplaceSource = options.autoReplaceOriginals &&
                            MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, source) != null
                        val originalHash = if (canAutoReplaceSource) performance.measure("source_fingerprint") {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    if (seekable) AutoReplacement.fingerprintChannel(sourceChannel)
                                    else AutoReplacement.fingerprint(app, source)
                                }.getOrNull()
                            }
                        } else null
                        val motionXmp = if (motionInfo != null && (options.format == OutputFormat.HEIC || options.format == OutputFormat.AVIF))
                            MotionPhotoContainer.xmp(motionInfo, options.format.mimeType) else null
                        // JPEG/HEIC/AVIF 实况照片的视频段紧随静态容器末尾，原生层只读前段静态容器即可，
                        // 免掉把数十 MB 视频读入原生堆并保证 retrySmaller 比较静态图体积。
                        val stillInputBytes = if (probe.container in 0..2 && motionInfo != null && motionInfo.stillEndOffset > 0L)
                            motionInfo.stillEndOffset else 0L
                        val budgetInputBytes = if (stillInputBytes > 0L) stillInputBytes
                            else sourcePfd.statSize.takeIf { it >= 0L } ?: item.sizeBytes.coerceAtLeast(0L)

                        val file = File(app.filesDir, "outputs/$id.${options.format.extension}").apply { parentFile?.mkdirs() }
                        suspend fun transcode(quality: Int) = withContext(Dispatchers.IO) {
                            suspend fun encodeFrom(inFd: Int) =
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                                    ImageWork.run("encode ${options.format} ${probe.width}x${probe.height}") {
                                        val mem = memoryInfo()
                                        val threads = ConversionBudget.threads(Runtime.getRuntime().availableProcessors())
                                        android.util.Log.i("UltraSlimConversion", "encode format=${options.format} size=${probe.width}x${probe.height} threads=$threads available=${mem.availMem}")
                                        UltraHdrBridge.nativeTranscode(inFd, output.fd, options.format.codecId, quality,
                                            options.gainmapQuality, options.gainmapScale, options.multiChannelGainmap, options.keepExif,
                                            options.allowSdr, options.speedLevel, options.resizePercent,
                                            threads, options.keepLocation, motionXmp, options.onlySmaller,
                                            avifGainmapCompatibility = options.avifGainmapCompatibility,
                                            heicGainmapCompatibility = options.heicGainmapCompatibility,
                                            jxlJpegReconstruction = options.jxlJpegReconstruction,
                                            maxInputBytes = stillInputBytes)
                                    }
                                }
                            if (seekable) encodeFrom(sourcePfd.fd)
                            else app.contentResolver.openFileDescriptor(source, "r")!!.use { encodeFrom(it.fd) }
                        }
                        // 上一张的大块原生内存归还 OS 有滞后，内存不足先在编码槽位外退避复测再判死。
                        awaitMemoryAdmission(probe, budgetInputBytes)
                        // Native retry reuses prepared pixels and the lossless gain map.
                        var result = performance.measure("native_transcode") { transcode(options.quality) }
                        if (!result.isSuccess) { file.delete(); error(result.error) }
                        var attachedMotion: MotionPhotoInfo? = null
                        if (motionInfo != null) {
                            if (options.format == OutputFormat.JPEG || options.format == OutputFormat.HEIC || options.format == OutputFormat.AVIF) {
                                val syncNow = !(options.autoSave && app.galleryPublishingAllowed.value)
                                attachedMotion = performance.measure("motion_attach") { withContext(Dispatchers.IO) {
                                    MotionPhotoHelper.attachMotionPhotoWithInfo(
                                        app, source, file, options.format, motionInfo,
                                        sourceChannel = if (seekable) sourceChannel else null,
                                        syncToDisk = syncNow,
                                    )
                                } }
                                if (attachedMotion != null) {
                                    result = result.copy(outputBytes = file.length())
                                    if (stillInputBytes > 0L) {
                                        result = result.copy(inputBytes = motionInfo.sourceSizeBytes)
                                    }
                                }
                            } else if (stillInputBytes == 0L && result.inputBytes > motionInfo.videoLength) {
                                // JXL 只输出静态图，比较与统计仅对比静态部分体积，不把丢弃视频计入省空间。
                                result = result.copy(inputBytes = result.inputBytes - motionInfo.videoLength)
                            }
                        }
                        val liveAttached = attachedMotion != null

                        // 输出尺寸/EXIF/增益图元数据已随 TranscodeResult 返回，不再整读输出文件二次 probe。
                        if (result.outputBytes <= 0 || (probe.hasGainMap && !result.hasGainMap) ||
                            (options.keepExif && probe.hasExif && !result.hasExif)) { file.delete(); error("输出校验失败") }
                        if (options.onlySmaller && result.outputBytes >= result.inputBytes) {
                            file.delete()
                            val why = "输出体积未比原图更小，为确保画质不劣化已保留原图"
                            repo.update(id) { it.copy(status = QueueStatus.SKIPPED, result = result, error = why) }
                            return@withSourceFile
                        }
                        var platformValidated = false
                        if (result.hasGainMap && ((options.format == OutputFormat.AVIF && options.avifGainmapCompatibility) ||
                            (options.format == OutputFormat.HEIC && options.heicGainmapCompatibility))) {
                            platformValidated = performance.measure("platform_validation") {
                                withContext(Dispatchers.IO) {
                                    HdrOutputValidation.requireReadableGainmap(file, result.width, result.height)
                                }
                            }
                        } else if (!result.hasGainMap) {
                            platformValidated = performance.measure("platform_validation") {
                                withContext(Dispatchers.IO) {
                                    SdrOutputValidation.requireReadable(file, options.format, result.width, result.height)
                                }
                            }
                        }
                        val name = if (options.retainSourceName)
                            ExportNames.fromSource(item.displayName, options.format.extension)
                        else outputName(item.displayName, options.format.extension, id, item.createdAt, motionPhoto = liveAttached)
                        withContext(Dispatchers.IO) { ExportCheckpoint(file).capture(sourceDates) }
                        val liveSummary = if (motionInfo != null) {
                            if (liveAttached) " · 实况已保留(无损直通)"
                            else if (result.hasGainMap) " · 原图实况(导出静止HDR)"
                            else " · 原图实况(导出静止图)"
                        } else ""
                        val summary = options.summary(probe.container) + liveSummary
                        // Commit the durable private output first. Saving can be retried without encoding again.
                        var entity = HistoryEntity(displayName = name, inputUri = item.uri, outputUri = file.path,
                            inputBytes = result.inputBytes, outputBytes = result.outputBytes, width = result.width, height = result.height,
                            gainmapWidth = result.gainmapWidth, gainmapHeight = result.gainmapHeight, hadGainMap = result.hasGainMap,
                            format = options.format.label, inputFormat = probe.containerLabel, durationMs = result.durationMs,
                            maxContentBoost = result.maxContentBoost, createdAt = if (item.createdAt > 0L) item.createdAt else System.currentTimeMillis(), status = HistoryEntity.STATUS_DONE,
                            taskId = id, batchId = batchId, saveState = "PENDING", settingsSummary = summary, hasExif = result.hasExif)
                        val historyId = performance.measure("history_checkpoint") {
                            withContext(NonCancellable) { app.database.historyDao().insert(entity) }
                        }
                        entity = entity.copy(id = historyId)
                        val mayPublish = options.autoSave && app.galleryPublishingAllowed.value &&
                            app.database.taskDao().isBatchCancelled(batchId) == false
                        val outputSlice = if (options.autoReplaceOriginals || mayPublish) {
                            performance.measure("output_fingerprint") {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        AutoReplacement.fingerprintFile(
                                            file,
                                            if (options.autoReplaceOriginals) attachedMotion else null,
                                        )
                                    }.getOrNull()
                                }
                            }
                        } else null
                        val outputFingerprint = outputSlice?.let { MediaStoreSaver.Fingerprint(it.bytes, it.fullSha256) }
                        if (platformValidated && outputFingerprint != null) {
                            AutoReplacement.recordValidated(historyId, outputFingerprint.sha256)
                        }
                        if (options.autoReplaceOriginals) {
                            performance.measure("replacement_prepare") { AutoReplacement.stage(
                                app = app,
                                history = entity,
                                originalHash = originalHash,
                                outputFingerprint = outputFingerprint,
                                knownMotion = motionInfo,
                                hasKnownMotion = true,
                                platformValidated = platformValidated,
                                knownOutputMotion = attachedMotion,
                                knownOutputVideoHash = outputSlice?.sliceSha256,
                            ) }
                            entity = app.database.historyDao().byId(historyId) ?: entity
                        }
                        var verifiedSave = false
                        // 指纹和自动替换校验会挂起；此时用户可能已切后台或取消批次，不能复用先前状态。
                        if (options.autoSave && app.database.taskDao().isBatchCancelled(batchId) == false &&
                            app.galleryPublishingAllowed.value) {
                            entity = performance.measure("publish") {
                                OutputStore.saveInternal(
                                    app, entity, outputFingerprint, finalizePublication = false,
                                    knownOptions = options, knownDates = sourceDates, directoryCache = batchDirectoryCache,
                                )
                            }
                            verifiedSave = entity.saveState == "SAVED"
                        }
                        if (options.autoReplaceOriginals) performance.measure("replacement_saved") {
                            AutoReplacement.saved(app, entity, outputVerified = verifiedSave)
                        }
                        repo.update(id) { it.copy(status = QueueStatus.DONE, progress = 100, result = result,
                            outputUri = entity.outputUri, historyId = historyId,
                            error = if (entity.saveState == "FAILED") "转换完成，保存失败；可在详情重试保存" else if (entity.saveState != "SAVED") { if (options.autoSave) "已转换，回到应用后自动保存" else "待保存，可直接分享" } else null) }
                        done++; saved += result.inputBytes - result.outputBytes
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    if (app.database.historyDao().byTask(id) == null) {
                        withContext(Dispatchers.IO) { File(app.filesDir, "outputs/$id.${options.format.extension}").delete() }
                    }
                    repo.update(id) { it.copy(status = QueueStatus.FAILED, error = e.message ?: "转换失败") }
                } finally {
                    var checkpointCompleted = false
                    try {
                        performance.measure("final_checkpoint") { withContext(NonCancellable) { repo.flush() } }
                        checkpointCompleted = true
                    } finally {
                        val outcome = repo.find(id)?.status?.name ?: "REMOVED"
                        performance.finish(when {
                            !checkpointCompleted -> "CHECKPOINT_FAILED"
                            outcome == "CONVERTING" -> "INTERRUPTED"
                            else -> outcome
                        }, ::currentThermalStatus)
                    }
                }
            }
        } finally {
            repo.updateAll(ids) { if (it.status == QueueStatus.CONVERTING) it.copy(status = QueueStatus.READY, error = "已中断，可继续") else it }
            withContext(NonCancellable) { repo.flush() }
        }
        app.conversionThermalState.value = null
        if (app.database.taskDao().isBatchCancelled(batchId) == false) {
            val pendingSave = options.autoSave && ids.toHashSet().let { batchIds ->
                repo.queue.value.any { it.id in batchIds &&
                    it.status == QueueStatus.DONE && it.outputUri?.startsWith("content://") != true }
            }
            showSummary(done, saved, pendingSave)
        }
        return Outcome.COMPLETE
    }

    fun createForegroundInfo(
        progress: Int,
        title: String,
        text: String?,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0,
                Intent(applicationContext, app.ultraslim.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
        return when {
            // Android 15+ 使用媒体处理类型；两种类型均有后台 6 小时/24 小时限额。
            Build.VERSION.SDK_INT >= 35 ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /** Best-effort completion notification, shown only when notifications are allowed. */
    private fun showSummary(doneCount: Int, savedBytes: Long, pendingSave: Boolean) {
        if (doneCount <= 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("转换完成")
            .setContentText("完成 $doneCount 张，输出体积净变化 ${app.ultraslim.util.formatByteDelta(-savedBytes)}" +
                if (pendingSave) "；回到应用继续保存" else "（原图保留）")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SUMMARY_ID, notification) }
    }
}
