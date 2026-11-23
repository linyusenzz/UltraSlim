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
    /** Normalized display names for each destination visited in this batch. */
    class DirectoryCache {
        private val names = HashMap<String, MutableSet<String>>()

        fun namesFor(destination: String, load: () -> MutableSet<String>): MutableSet<String> =
            names.getOrPut(destination, load)

        fun invalidate(destination: String) { names.remove(destination) }
    }

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
        directoryCache: DirectoryCache? = null,
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
        val desired = ExportNames.fromSource(name, format.extension)
        val parent = if (tree == null) MediaStore.Images.Media.getContentUri(volume)
            else DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        fun loadNames(): MutableSet<String> {
            val children = if (tree == null) parent else
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // SQLite LIKE/NOCASE cannot express NFC equivalence or Unicode case folding.
            val names = HashSet<String>()
            resolver.query(children, arrayOf(OpenableColumns.DISPLAY_NAME),
                if (tree == null) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" else null,
                if (tree == null) arrayOf(path) else null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkCancelled()
                    cursor.getString(0)?.let { names += ExportNames.normalizeKey(it) }
                }
            } ?: throw IOException("无法读取输出目录，请检查授权")
            return names
        }
        val occupied = directoryCache?.namesFor(destination, ::loadNames) ?: loadNames()
        val chosen = ExportNames.availableNormalized(desired, occupied)
        checkCancelled()
        val created = if (tree != null) DocumentsContract.createDocument(resolver, parent, format.mimeType, chosen)
        else resolver.insert(parent, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, chosen)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            dates.dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        })
        if (created == null) directoryCache?.invalidate(destination)
        val uri = created ?: throw IOException("无法创建输出文件，请检查目录授权或剩余空间")
        try {
            journal.record(destination, uri)
            // Accept the name returned by the provider, including its own normalization/collision suffix.
            // createDocument/insert must create a fresh item; there is deliberately no rename loop.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var written = 0L
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size > 0) {
                            output.write(buffer, 0, size)
                            digest.update(buffer, 0, size)
                            written += size
                        }
                    }
                }
            } ?: throw IOException("输出目录拒绝写入")
            checkCancelled()
            check(written == expected.bytes && MediaStoreSaver.hex(digest.digest()) == expected.sha256) { "成品写入校验失败" }
            if (tree == null) {
                check(MediaStoreSaver.matchesAlbumCopy(context, uri, expected)) { "成品发布前校验失败" }
            }
            checkCancelled()
            finish(uri)
            check(MediaStoreSaver.matchesAlbumCopy(context, uri, expected)) { "成品发布后校验失败" }
            if (directoryCache != null) {
                val actual = runCatching { displayName(context, uri) }.getOrNull()
                if (actual == null) directoryCache.invalidate(destination)
                else {
                    occupied += ExportNames.normalizeKey(chosen)
                    occupied += ExportNames.normalizeKey(actual)
                }
            }
            return uri
        } catch (failure: Exception) {
            directoryCache?.invalidate(destination)
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
