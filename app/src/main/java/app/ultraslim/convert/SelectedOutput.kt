package app.ultraslim.convert

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.ultraslim.model.OutputFormat
import app.ultraslim.util.ExportNames
import java.io.File
import java.io.IOException

/** Publication for a selected tree or a retained source name. Never opens an existing file to write. */
internal object SelectedOutput {
    fun authorize(context: Context, tree: Uri): String {
        require(DocumentsContract.isTreeUri(tree)) { "请选择输出文件夹" }
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_FLAGS), null, null, null)?.use {
            check(it.moveToFirst() && it.getInt(1) and Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "此文件夹无法创建文件" }
            it.getString(0) ?: "所选文件夹"
        } ?: error("无法读取文件夹，请重新授权")
    }

    fun displayName(context: Context, uri: Uri): String? = context.contentResolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun save(context: Context, file: File, name: String, format: OutputFormat,
        tree: Uri?, expected: MediaStoreSaver.Fingerprint, dates: SourceTimestamps,
        checkCancelled: () -> Unit,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY, path: String = "Pictures/UltraSlim/",
    ): Uri {
        val resolver = context.contentResolver
        val journal = ExportCheckpoint(file)
        val destination = if (tree != null) "tree:$tree" else "album:$volume:$path"
        fun finish(uri: Uri) {
            if (tree == null) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                MediaStoreSaver.preserveCaptureTime(context, uri, dates.dateTaken)
            }
            MediaStoreSaver.preserveFileTime(context, uri, dates.modifiedAt ?: dates.dateTaken)
        }
        checkCancelled()
        journal.published(destination)?.let { previous ->
            if (MediaStoreSaver.matchesAlbumCopy(context, previous, expected)) {
                finish(previous)
                return previous
            }
            // A recorded URI may have been edited externally. Never overwrite or remove it on retry.
        }
        val names = HashSet<String>()
        val parent: Uri
        if (tree != null) {
            val id = DocumentsContract.getTreeDocumentId(tree)
            parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) { checkCancelled(); it.getString(0)?.let(names::add) }
            } ?: throw IOException("输出目录不可读，请在设置中重新授权同一目录")
        } else {
            parent = MediaStore.Images.Media.getContentUri(volume)
            resolver.query(parent, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path), null)?.use {
                while (it.moveToNext()) { checkCancelled(); it.getString(0)?.let(names::add) }
            } ?: throw IOException("无法检查相册已有文件")
        }
        val chosen = ExportNames.available(ExportNames.fromSource(name, format.extension), names)
        checkCancelled()
        val created = if (tree != null) DocumentsContract.createDocument(resolver, parent, format.mimeType, chosen)
        else resolver.insert(parent, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, chosen)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            dates.dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        })
        val uri = created ?: throw IOException("无法创建输出文件，请检查目录授权或剩余空间")
        try {
            journal.record(destination, uri)
            // Accept the name returned by the provider, including its own normalization/collision suffix.
            // createDocument/insert must create a fresh item; there is deliberately no rename loop.
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            } ?: throw IOException("输出目录拒绝写入")
            checkCancelled()
            check(MediaStoreSaver.matchesAlbumCopy(context, uri, expected)) { "成品写入校验失败" }
            finish(uri)
            check(MediaStoreSaver.matchesAlbumCopy(context, uri, expected)) { "成品发布后校验失败" }
            return uri
        } catch (failure: Exception) {
            val removed = runCatching {
                if (tree != null) DocumentsContract.deleteDocument(resolver, uri)
                else resolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            if (removed) journal.clearPublication()
            else android.util.Log.w("UltraSlimExport", "Could not remove newly created failed output", failure)
            throw failure
        }
    }
}
