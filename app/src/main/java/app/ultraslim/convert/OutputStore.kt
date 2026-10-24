package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.decodeDefaults
import app.ultraslim.model.OutputFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OutputStore {
    private val saveLock = Mutex()
    suspend fun save(
        app: UltraSlimApp,
        record: HistoryEntity,
        expectedFingerprint: MediaStoreSaver.Fingerprint? = null,
        finalizePublication: Boolean = true,
    ): HistoryEntity {
        val saved = saveLock.withLock {
            val current = app.database.historyDao().byId(record.id) ?: error("记录已删除")
            if (current.outputUri.startsWith("content://")) return@withLock current
            val checkpoint = ExportCheckpoint(File(current.outputUri))
            val replacement = app.database.replacementDao().byId(current.id)?.takeIf { it.state == "PREPARED" }
            val source = Uri.parse(replacement?.sourceUri ?: current.inputUri)
            val metadata = withContext(Dispatchers.IO) { SourceMedia.read(app, source) }
            // 私有成品只读一次：调用方（如自动替换 stage）已算过指纹时直接复用；
            // 发布后的相册副本在 MediaStoreSaver.save 内部已完成整读 SHA-256 校验。
            val expected = expectedFingerprint ?: withContext(Dispatchers.IO) {
                MediaStoreSaver.fingerprintOf(File(current.outputUri))
            }
            val uri = try { withContext(Dispatchers.IO) {
                val options = app.database.taskDao().batch(current.batchId)?.let { decodeDefaults(it.settings) }
                val file = File(current.outputUri)
                val format = OutputFormat.parse(current.format)
                val task = current.taskId ?: "history-${current.id}"
                val timestamps = checkpoint.dates() ?: SourceMedia.timestamps(app, source).also(checkpoint::capture)
                val context = coroutineContext
                val tree = options?.outputTree?.takeIf { it.isNotBlank() && !options.autoReplaceOriginals }
                if (tree != null) return@withContext SelectedOutput.save(app, file, current.displayName, format,
                    Uri.parse(tree), expected ?: error("无法读取成品"), timestamps, { context.ensureActive() })
                val retainName = options?.retainSourceName == true
                val sameAlbum = if (replacement != null && metadata != null && retainName) runCatching {
                    SelectedOutput.save(app, file, current.displayName, format, null,
                        expected ?: error("无法读取成品"), timestamps, { context.ensureActive() }, metadata.volume, metadata.relativePath)
                }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
                else if (replacement != null && metadata != null) MediaStoreSaver.save(app, file,
                    current.displayName, format, taskId = task, relativePath = metadata.relativePath,
                    volume = metadata.volume, dateTaken = timestamps.dateTaken, relatedUri = source,
                    fileModifiedTime = timestamps.modifiedAt ?: timestamps.dateTaken,
                    expectedFingerprint = expected) else null
                if (sameAlbum != null) sameAlbum else {
                    if (replacement != null) AutoReplacement.finish(app, listOf(replacement), "SKIPPED",
                        "无法保存回原相册，成品保存到 UltraSlim，原图保留")
                    if (retainName) SelectedOutput.save(app, file, current.displayName, format, null,
                        expected ?: error("无法读取成品"), timestamps, { context.ensureActive() })
                    else MediaStoreSaver.save(app, file, current.displayName, format, taskId = task, dateTaken = timestamps.dateTaken,
                        fileModifiedTime = timestamps.modifiedAt ?: timestamps.dateTaken,
                        expectedFingerprint = expected)
                }
            } } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) {
                android.util.Log.w("UltraSlimExport", "Save failed; private output retained", e)
                withContext(Dispatchers.IO) {
                    checkpoint.failure(if (e is SecurityException) "输出目录授权已失效，请在设置中重新选择原目录后重试" else e.message)
                }
                null
            }
            // finish() may have added a replacement outcome while saving.
            // MediaStoreSaver.save only returns a non-null URI after matches(resolver, uri, expected) succeeds.
            val verified = uri?.takeIf { expected != null && expected.bytes > 0 }
            val latest = app.database.historyDao().byId(current.id) ?: current
            val actualName = verified?.let { withContext(Dispatchers.IO) { runCatching { SelectedOutput.displayName(app, it) }.getOrNull() } }
            val updated = if (verified != null) latest.copy(outputUri = verified.toString(),
                displayName = actualName ?: latest.displayName, saveState = "SAVED") else latest.copy(saveState = "FAILED")
            withContext(kotlinx.coroutines.NonCancellable) {
                app.database.historyDao().update(updated)
                if (verified != null) withContext(Dispatchers.IO) { File(current.outputUri).delete(); checkpoint.delete() }
            }
            updated
        }
        if (finalizePublication) withContext(kotlinx.coroutines.NonCancellable) {
            // 相册与 history 已完成，收尾不能被页面生命周期打断。
            if (saved.saveState == "SAVED") AutoReplacement.saved(app, saved, outputVerified = true)
            saved.taskId?.let { id ->
                app.conversionRepository.update(id) {
                    it.copy(outputUri = saved.outputUri, error = when (saved.saveState) {
                        "SAVED" -> null
                        "FAILED" -> "保存失败，可在详情重试"
                        else -> "转换完成，待保存；可在详情重试"
                    })
                }
                app.conversionRepository.flush()
            }
        }
        return saved
    }
    suspend fun failureMessage(record: HistoryEntity): String? = withContext(Dispatchers.IO) {
        if (record.outputUri.startsWith("content://")) null else ExportCheckpoint(File(record.outputUri)).failure()
    }
    class ConversionInProgress : IllegalStateException("请等待当前转换结束后删除记录")

    suspend fun delete(app: UltraSlimApp, id: Long) = deleteBatch(app, listOf(id))

    /** 与整个转换批次互斥；saveLock 仍负责与手动/延迟发布互斥。 */
    suspend fun deleteBatch(app: UltraSlimApp, ids: List<Long>) {
        if (ids.isEmpty()) return
        if (!app.conversionMutex.tryLock()) throw ConversionInProgress()
        try {
            // 包含已经排队、尚未取得 conversionMutex 的服务或旧 Worker。
            if (app.conversionRunning.first()) throw ConversionInProgress()
            saveLock.withLock {
                app.conversionRepository.awaitReady()
                val rows = ids.chunked(500).flatMap { app.database.historyDao().byIds(it) }
                // 取消只发生在批次之间，不能留下已删历史但仍引用私有成品的队列。
                withContext(kotlinx.coroutines.NonCancellable) {
                    app.database.withTransaction {
                        app.database.replacementDao().deleteAll(ids)
                        app.database.historyDao().deleteByIds(ids)
                    }
                    withContext(Dispatchers.IO) {
                        rows.forEach { row ->
                            if (!row.outputUri.startsWith("content://")) {
                                File(row.outputUri).delete()
                                ExportCheckpoint(File(row.outputUri)).delete()
                            }
                        }
                    }
                    app.conversionRepository.removeIds(rows.mapNotNull { it.taskId })
                    app.conversionRepository.flush()
                }
            }
        } finally {
            app.conversionMutex.unlock()
        }
    }
    fun uri(context: Context, value: String, displayName: String? = null): Uri = if (value.startsWith("content://")) Uri.parse(value)
        else FileProvider.getUriForFile(context, "${context.packageName}.files", File(value), displayName ?: File(value).name)

    fun share(context: Context, records: List<HistoryEntity>): Boolean = runCatching {
        val uris = ArrayList(records.map { uri(context, it.outputUri, it.displayName) })
        require(uris.isNotEmpty())
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = records.map { OutputFormat.parse(it.format).mimeType }.distinct().singleOrNull() ?: "image/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = android.content.ClipData.newUri(context.contentResolver, "图片", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
        true
    }.getOrDefault(false)
}
