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
    /**
     * 一次确认点击内的共享查询。设置、批次取消状态、原图回收站状态在整轮复核中逐行相同，
     * 而批量规模可达 2000 行，逐行重复 DataStore/provider 往返是这一轮的主要固定开销。
     */
    internal class Pass(private val app: UltraSlimApp) {
        private var enabled: Boolean? = null
        private val batchCancelled = HashMap<String, Boolean?>()
        private val originalStates = HashMap<String, MediaStoreTrashHelper.OriginalState>()

        suspend fun autoReplaceEnabled(): Boolean {
            enabled?.let { return it }
            val value = app.settingsRepository.defaults.first().autoReplaceOriginals
            enabled = value
            return value
        }

        /** null 表示批次不存在；与 batch(...)?.cancelled == false 的语义一致。 */
        suspend fun batchActive(batchId: String): Boolean {
            if (!batchCancelled.containsKey(batchId)) {
                batchCancelled[batchId] = app.database.taskDao().batch(batchId)?.cancelled
            }
            return batchCancelled[batchId] == false
        }

        suspend fun originalState(uri: Uri): MediaStoreTrashHelper.OriginalState {
            val key = uri.toString()
            originalStates[key]?.let { return it }
            val state = MediaStoreTrashHelper.originalState(app, uri)
            originalStates[key] = state
            return state
        }
    }

    fun fingerprint(app: UltraSlimApp, uri: Uri): String =
        app.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) { digest.update(buffer, 0, n); total += n }
            }
            check(total > 0) { "文件为空" }
            digest.digest().joinToString("") { "%02x".format(it) }
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
            decoder.setTargetSampleSize(maxOf(1, (maxOf(info.size.width, info.size.height) + 1599) / 1600))
            decoder.setOnPartialImageListener { false }
        }
        try {
            check(!history.hadGainMap || (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) { "系统无法读取成品增益图" }
        } finally { bitmap.recycle() }
    }

    private fun videoHash(app: UltraSlimApp, uri: Uri, info: MotionPhotoInfo): String {
        val fd = app.contentResolver.openFileDescriptor(uri, "r") ?: error("无法读取实况视频")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            input.channel.position(info.videoOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = info.videoLength
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(n > 0) { "实况视频不完整" }
                digest.update(buffer, 0, n); remaining -= n
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** Called before saving deletes the private output. Failure only prevents replacing the original. */
    suspend fun stage(
        app: UltraSlimApp,
        history: HistoryEntity,
        originalHash: String?,
        outputFingerprint: MediaStoreSaver.Fingerprint? = null,
        knownMotion: MotionPhotoInfo? = null,
        hasKnownMotion: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        try {
            check(app.settingsRepository.defaults.first().autoReplaceOriginals) { "自动替换已关闭" }
            check(history.outputBytes in 1 until history.inputBytes) { "成品未比原图更小" }
            check(originalHash != null) { "未能记录转换前的原图校验值" }
            val source = Uri.parse(history.inputUri)
            val target = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, source) ?: error("无法安全定位相册原图，请在历史中手动处理")
            check(MediaStoreTrashHelper.originalState(app, target) == MediaStoreTrashHelper.OriginalState.PRESENT) { "原图已删除、在回收站或不可访问" }
            check(SourceMedia.read(app, target) != null) { "无法确定原相册位置" }
            // source 与 target 常解析到同一相册项，避免对同一文件做两次全量哈希。
            val sourceHash = fingerprint(app, source)
            val targetHash = if (target == source) sourceHash else fingerprint(app, target)
            check(sourceHash == originalHash && targetHash == originalHash) { "原图已发生变化" }
            val output = Uri.fromFile(File(history.outputUri))
            requireReadable(app, output, history)
            val motion = if (hasKnownMotion) knownMotion else MotionPhotoHelper.detect(app, source)
            if (motion != null) {
                val savedMotion = MotionPhotoHelper.detect(app, output) ?: error("成品未保留实况视频")
                check(savedMotion == motion.copy(videoOffset = savedMotion.videoOffset) &&
                    videoHash(app, source, motion) == videoHash(app, output, savedMotion)) { "实况视频校验失败" }
            }
            val outHash = outputFingerprint?.takeIf { it.bytes > 0 }?.sha256 ?: fingerprint(app, output)
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
            // 真正移入回收站前 validate 仍会全量复核原图与成品哈希。
            validate(app, ready, recheckSource = false, recheckOutput = !outputVerified)
            app.database.replacementDao().update(ready)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            finish(app, listOf(item), "SKIPPED", "原图保留：${e.message ?: "保存校验失败"}")
        }
    }

    /** Rechecked immediately before requesting trash: files/permissions/settings can change while queued. */
    suspend fun validate(app: UltraSlimApp, item: ReplacementEntity, recheckSource: Boolean = true,
                         pass: Pass? = null, recheckOutput: Boolean = true) = withContext(Dispatchers.IO) {
        val enabled = pass?.autoReplaceEnabled() ?: app.settingsRepository.defaults.first().autoReplaceOriginals
        check(enabled) { "自动替换已关闭" }
        val batchActive = pass?.batchActive(item.batchId) ?: (app.database.taskDao().batch(item.batchId)?.cancelled == false)
        check(batchActive) { "批次已取消" }
        val history = app.database.historyDao().byId(item.historyId) ?: error("记录已移除")
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
        requireReadable(app, Uri.parse(item.outputUri), history)
        restoreOriginalAlbum(app, item)
    }

    /** Also repairs READY journals from versions which always published into UltraSlim. */
    private fun restoreOriginalAlbum(app: UltraSlimApp, item: ReplacementEntity) {
        val source = SourceMedia.read(app, Uri.parse(item.sourceUri)) ?: error("无法确定原相册位置")
        val outputUri = Uri.parse(item.outputUri)
        val output = SourceMedia.read(app, outputUri) ?: error("无法确定成品位置")
        check(source.volume == output.volume) { "成品不在原存储卷，原图保留" }
        if (source.relativePath != output.relativePath) {
            check(app.galleryPublishingAllowed.value) { "回到应用后才能将成品移回原相册" }
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
