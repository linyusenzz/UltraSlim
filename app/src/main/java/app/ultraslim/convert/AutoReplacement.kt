package app.ultraslim.convert

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.ReplacementEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A durable, opt-in journal. Originals are only ever sent to the system's recoverable trash. */
internal object AutoReplacement {
    internal data class SliceFingerprint(
        val fullSha256: String,
        val bytes: Long,
        val sliceSha256: String? = null,
    )

    private val validatedOutputs = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>>()

    internal fun recordValidated(historyId: Long, outputHash: String) {
        if (historyId <= 0L || outputHash.isEmpty()) return
        if (validatedOutputs.size >= 4096) validatedOutputs.clear()
        validatedOutputs[historyId] = outputHash to Build.FINGERPRINT
    }

    /**
     * 一次确认点击内的共享查询。设置、批次取消状态、原图回收站状态、历史行与原相册位置在整轮复核中可批量预取，
     * 批量规模可达 2000 行，避免逐行重复 DataStore/Room/MediaStore 往返。
     */
    internal class Pass(private val app: UltraSlimApp) {
        @Volatile private var enabled: Boolean? = null
        private val batchActiveMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val originalStates = java.util.concurrent.ConcurrentHashMap<String, MediaStoreTrashHelper.OriginalState>()
        private val histories = java.util.concurrent.ConcurrentHashMap<Long, HistoryEntity>()
        private val locations = java.util.concurrent.ConcurrentHashMap<String, SourceMedia>()

        suspend fun autoReplaceEnabled(): Boolean {
            enabled?.let { return it }
            val value = app.settingsRepository.defaults.first().autoReplaceOriginals
            enabled = value
            return value
        }

        /** null 表示批次不存在；与 isBatchCancelled(...) == false 的语义一致。 */
        suspend fun batchActive(batchId: String): Boolean {
            batchActiveMap[batchId]?.let { return it }
            val active = app.database.taskDao().isBatchCancelled(batchId) == false
            batchActiveMap[batchId] = active
            return active
        }

        fun prefetchOriginalStates(uris: Collection<Uri>) {
            val missing = uris.filter { !originalStates.containsKey(it.toString()) }.distinct()
            if (missing.isEmpty()) return
            MediaStoreTrashHelper.originalStates(app, missing).forEach { (u, st) ->
                originalStates[u.toString()] = st
            }
        }

        suspend fun originalState(uri: Uri): MediaStoreTrashHelper.OriginalState {
            val key = uri.toString()
            originalStates[key]?.let { return it }
            val state = MediaStoreTrashHelper.originalState(app, uri)
            originalStates[key] = state
            return state
        }

        suspend fun prefetchHistories(ids: Collection<Long>) {
            val missing = ids.filter { !histories.containsKey(it) }.distinct()
            if (missing.isEmpty()) return
            app.database.historyDao().byIds(missing).forEach { histories[it.id] = it }
        }

        suspend fun history(id: Long): HistoryEntity? {
            histories[id]?.let { return it }
            return app.database.historyDao().byId(id)?.also { histories[id] = it }
        }

        fun prefetchLocations(uris: Collection<Uri>) {
            val missing = uris.filter { !locations.containsKey(it.toString()) }.distinct()
            if (missing.isEmpty()) return
            SourceMedia.readBatch(app, missing, includeExifFallback = false).forEach { (u, m) ->
                locations[u.toString()] = m
            }
        }

        fun location(uri: Uri): SourceMedia? {
            val key = uri.toString()
            locations[key]?.let { return it }
            return SourceMedia.read(app, uri)?.also { locations[key] = it }
        }
    }

    internal fun fingerprintStream(input: java.io.InputStream, motion: MotionPhotoInfo? = null): SliceFingerprint {
        val fullDigest = MessageDigest.getInstance("SHA-256")
        val sliceDigest = if (motion != null && motion.videoLength > 0) MessageDigest.getInstance("SHA-256") else null
        val sliceStart = motion?.videoOffset ?: -1L
        val sliceEnd = if (sliceDigest != null) sliceStart + motion!!.videoLength else -1L
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var sliceBytes = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) {
                fullDigest.update(buffer, 0, n)
                if (sliceDigest != null) {
                    val from = maxOf(total, sliceStart)
                    val to = minOf(total + n, sliceEnd)
                    if (from < to) {
                        val len = (to - from).toInt()
                        sliceDigest.update(buffer, (from - total).toInt(), len)
                        sliceBytes += len
                    }
                }
                total += n
            }
        }
        check(total > 0) { "文件为空" }
        if (sliceDigest != null) check(sliceBytes == motion!!.videoLength) { "实况视频不完整" }
        return SliceFingerprint(
            fullSha256 = MediaStoreSaver.hex(fullDigest.digest()),
            bytes = total,
            sliceSha256 = sliceDigest?.let { MediaStoreSaver.hex(it.digest()) },
        )
    }

    internal fun fingerprintFile(file: File, motion: MotionPhotoInfo? = null): SliceFingerprint =
        file.inputStream().use { fingerprintStream(it, motion) }

    internal fun fingerprintChannel(channel: java.nio.channels.FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
        var total = 0L
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            if (n > 0) {
                digest.update(buffer.array(), 0, n)
                total += n
            }
        }
        check(total > 0) { "文件为空" }
        return MediaStoreSaver.hex(digest.digest())
    }

    fun fingerprint(app: UltraSlimApp, uri: Uri): String =
        app.contentResolver.openInputStream(uri)?.use { input ->
            fingerprintStream(input).fullSha256
        } ?: error("无法读取文件")

    private fun requireReadable(app: UltraSlimApp, uri: Uri, history: HistoryEntity) {
        check(!history.hadGainMap || Build.VERSION.SDK_INT >= 34) { "系统无法验证 HDR 增益图" }
        val probe = app.contentResolver.openFileDescriptor(uri, "r")?.use { UltraHdrBridge.nativeProbe(it.fd) }
        check(probe != null && probe.isSuccess && probe.width == history.width && probe.height == history.height &&
            probe.hasGainMap == history.hadGainMap && (!history.hasExif || probe.hasExif)) { "成品元数据校验失败" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            // ImageDecoder reports oriented dimensions; EXIF can swap width and height.
            check((info.size.width == history.width && info.size.height == history.height) ||
                (info.size.width == history.height && info.size.height == history.width)) { "系统解码尺寸不一致" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (!history.hadGainMap) decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSampleSize(maxOf(1, (maxOf(info.size.width, info.size.height) + 511) / 512))
            decoder.setOnPartialImageListener { false }
        }
        try {
            check(!history.hadGainMap || (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) { "系统无法读取成品增益图" }
        } finally { bitmap.recycle() }
    }

    /** Called before saving deletes the private output. Failure only prevents replacing the original. */
    suspend fun stage(
        app: UltraSlimApp,
        history: HistoryEntity,
        originalHash: String?,
        outputFingerprint: MediaStoreSaver.Fingerprint? = null,
        knownMotion: MotionPhotoInfo? = null,
        hasKnownMotion: Boolean = false,
        platformValidated: Boolean = false,
        knownOutputMotion: MotionPhotoInfo? = null,
        knownOutputVideoHash: String? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            check(app.settingsRepository.defaults.first().autoReplaceOriginals) { "自动替换已关闭" }
            check(history.outputBytes in 1 until history.inputBytes) { "成品未比原图更小" }
            check(originalHash != null) { "未能记录转换前的原图校验值" }
            val source = Uri.parse(history.inputUri)
            val target = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, source) ?: error("无法安全定位相册原图，请在历史中手动处理")
            check(MediaStoreTrashHelper.originalState(app, target) == MediaStoreTrashHelper.OriginalState.PRESENT) { "原图已删除、在回收站或不可访问" }
            check(SourceMedia.read(app, target) != null) { "无法确定原相册位置" }
            val motion = if (hasKnownMotion) knownMotion else MotionPhotoHelper.detect(app, source)
            // 单遍读取同时计算原图全文 SHA-256 与实况视频段 SHA-256；source 与 target 同一 URI 时免二次整读。
            val sourceSlice = app.contentResolver.openInputStream(source)?.use { fingerprintStream(it, motion) }
                ?: error("无法读取文件")
            val sourceHash = sourceSlice.fullSha256
            val targetHash = if (target == source) sourceHash else fingerprint(app, target)
            check(sourceHash == originalHash && targetHash == originalHash) { "原图已发生变化" }
            val outputFile = File(history.outputUri)
            val output = Uri.fromFile(outputFile)
            if (!platformValidated) requireReadable(app, output, history)
            val outHash: String
            if (motion != null) {
                val savedMotion = knownOutputMotion ?: MotionPhotoHelper.detect(app, output) ?: error("成品未保留实况视频")
                val outSlice = if (outputFingerprint != null && outputFingerprint.bytes > 0 && knownOutputVideoHash != null) {
                    SliceFingerprint(outputFingerprint.sha256, outputFingerprint.bytes, knownOutputVideoHash)
                } else {
                    fingerprintFile(outputFile, savedMotion)
                }
                check(savedMotion == motion.copy(videoOffset = savedMotion.videoOffset) &&
                    sourceSlice.sliceSha256 != null && sourceSlice.sliceSha256 == outSlice.sliceSha256) { "实况视频校验失败" }
                outHash = outSlice.fullSha256
            } else {
                outHash = outputFingerprint?.takeIf { it.bytes > 0 }?.sha256 ?: fingerprint(app, output)
            }
            recordValidated(history.id, outHash)
            app.database.replacementDao().insert(ReplacementEntity(history.id, history.batchId, target.toString(), "",
                originalHash, outHash))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            note(app, history.id, "原图保留：${e.message ?: "自动替换校验未通过"}")
        }
    }

    suspend fun saved(
        app: UltraSlimApp,
        history: HistoryEntity,
        outputVerified: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val item = app.database.replacementDao().byId(history.id) ?: return@withContext
        if (item.state != "PREPARED") return@withContext
        if (history.saveState != "SAVED" || !history.outputUri.startsWith("content://")) return@withContext
        val ready = item.copy(outputUri = history.outputUri, state = "READY")
        try {
            // 原图哈希在 stage 刚校验过，相册副本哈希在 MediaStoreSaver.save 发布时刚校验过；
            // 真正移入回收站前 validate 仍会全量复核原图与成品哈希及平台解码。
            validate(app, ready, recheckSource = false, recheckOutput = !outputVerified, recheckReadable = !outputVerified)
            app.database.replacementDao().update(ready)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            finish(app, listOf(item), "SKIPPED", "原图保留：${e.message ?: "保存校验失败"}")
        }
    }

    /** Rechecked immediately before requesting trash: files/permissions/settings can change while queued. */
    suspend fun validate(app: UltraSlimApp, item: ReplacementEntity, recheckSource: Boolean = true,
                         pass: Pass? = null, recheckOutput: Boolean = true, recheckReadable: Boolean = true) = withContext(Dispatchers.IO) {
        val enabled = pass?.autoReplaceEnabled() ?: app.settingsRepository.defaults.first().autoReplaceOriginals
        check(enabled) { "自动替换已关闭" }
        val batchActive = pass?.batchActive(item.batchId) ?: (app.database.taskDao().isBatchCancelled(item.batchId) == false)
        check(batchActive) { "批次已取消" }
        val history = pass?.history(item.historyId) ?: app.database.historyDao().byId(item.historyId) ?: error("记录已移除")
        check(history.saveState == "SAVED" && history.outputUri == item.outputUri) { "成品尚未保存" }
        check(item.sourceUri != item.outputUri) { "原图与成品位置相同" }
        val sourceUri = Uri.parse(item.sourceUri)
        val originalState = pass?.originalState(sourceUri) ?: MediaStoreTrashHelper.originalState(app, sourceUri)
        check(originalState == MediaStoreTrashHelper.OriginalState.PRESENT) { "原图已删除、在回收站或不可访问" }
        if (recheckSource) {
            check(fingerprint(app, sourceUri) == item.sourceHash) { "原图已变化或不可访问" }
        }
        if (recheckOutput) {
            check(fingerprint(app, Uri.parse(item.outputUri)) == item.outputHash) { "成品已变化或不可访问" }
        }
        if (recheckReadable) {
            val alreadyVerified = recheckOutput &&
                validatedOutputs[item.historyId] == (item.outputHash to Build.FINGERPRINT)
            if (!alreadyVerified) {
                requireReadable(app, Uri.parse(item.outputUri), history)
                if (recheckOutput) recordValidated(item.historyId, item.outputHash)
            }
        }
        restoreOriginalAlbum(app, item, pass)
    }

    /** Also repairs READY journals from versions which always published into UltraSlim. */
    private fun restoreOriginalAlbum(app: UltraSlimApp, item: ReplacementEntity, pass: Pass? = null) {
        val sourceUri = Uri.parse(item.sourceUri)
        var source = pass?.location(sourceUri) ?: SourceMedia.read(app, sourceUri) ?: error("无法确定原相册位置")
        val outputUri = Uri.parse(item.outputUri)
        val output = pass?.location(outputUri) ?: SourceMedia.read(app, outputUri) ?: error("无法确定成品位置")
        check(source.volume == output.volume) { "成品不在原存储卷，原图保留" }
        if (source.relativePath != output.relativePath) {
            check(app.galleryPublishingAllowed.value) { "回到应用后才能将成品移回原相册" }
            if (source.dateTaken == null && pass != null) {
                source = SourceMedia.read(app, sourceUri) ?: source
            }
            MediaStoreSaver.preserveFileTime(app, outputUri, source.modifiedAt ?: source.dateTaken)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, source.relativePath)
                source.dateTaken?.let { put(android.provider.MediaStore.Images.Media.DATE_TAKEN, it) }
            }
            check(app.contentResolver.update(outputUri, values, null, null) == 1) { "成品未能移回原相册，原图保留" }
            MediaStoreSaver.preserveCaptureTime(app, outputUri, source.dateTaken)
            check(SourceMedia.read(app, outputUri)?.relativePath == source.relativePath) { "原相册位置校验失败" }
        }
    }

    suspend fun finish(app: UltraSlimApp, items: List<ReplacementEntity>, state: String, message: String) {
        if (items.isEmpty()) return
        app.database.replacementDao().finish(items.map { it.historyId }, state, message)
        // 单条批量 UPDATE 替代逐条 byId+update，避免 2N 次事务与反复触发表 invalidation。
        app.database.historyDao().noteReplacement(items.map { it.historyId }, message)
    }

    private suspend fun note(app: UltraSlimApp, id: Long, message: String) {
        app.database.historyDao().noteReplacement(listOf(id), message)
    }
}
