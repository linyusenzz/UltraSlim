package app.ultraslim.convert

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Stream document URIs: do not materialize all files or recursively keep directory cursors open. */
object FolderImages {
    data class Entry(val uri: Uri, val displayName: String?, val sizeBytes: Long)

    fun images(resolver: ContentResolver, tree: Uri, recursive: Boolean,
        onUnreadableDirectory: () -> Unit = {}): Flow<Uri> =
        entries(resolver, tree, recursive, onUnreadableDirectory).map { it.uri }

    fun entries(resolver: ContentResolver, tree: Uri, recursive: Boolean,
        onUnreadableDirectory: () -> Unit = {}): Flow<Entry> = flow {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque<String>().apply { add(root) }
        val visited = HashSet<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeLast()
            if (!visited.add(directory)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
            // Permission/provider errors in a child should not discard successfully imported siblings.
            val cursor = try {
                resolver.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.COLUMN_SIZE,
                ), null, null, null) ?: error("无法读取文件夹")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (directory == root) throw e
                onUnreadableDirectory()
                continue
            }
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val flagsColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
                val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = it.getString(idColumn) ?: continue
                    val mime = it.getString(mimeColumn)
                    val name = it.getString(nameColumn)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (recursive && id !in visited) pending.add(id)
                    } else if (it.getInt(flagsColumn) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0 &&
                        supported(mime, name)) {
                        val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else 0L
                        emit(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun supported(mime: String?, name: String?): Boolean =
        mime?.lowercase() in setOf("image/jpeg", "image/jpg", "image/heic", "image/heif", "image/avif", "image/jxl") ||
            name?.substringAfterLast('.', "")?.lowercase() in setOf("jpg", "jpeg", "heic", "heif", "avif", "jxl")
}
