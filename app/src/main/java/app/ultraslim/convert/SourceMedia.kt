package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** Publication metadata is read from the original, not from conversion/import wall-clock time. */
internal data class SourceMedia(val volume: String, val relativePath: String, val dateTaken: Long?, val modifiedAt: Long? = null) {
    companion object {
        fun captureTime(context: Context, source: Uri): Long? = timestamps(context, source).dateTaken

        private fun queryTime(context: Context, source: Uri, column: String): Long? = runCatching {
            context.contentResolver.query(source, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index).takeIf { it > 0 }
                else null
            }
        }.getOrNull()

        private fun exifCaptureTime(context: Context, source: Uri): Long? = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val exif = ExifInterface(input)
                ExifCaptureTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            }
        }.getOrNull()

        fun timestamps(context: Context, source: Uri): SourceTimestamps {
            InputMetadataStore.read(context, source)?.let { return it }
            val canonical = MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, source)
            var taken: Long? = null
            var modified: Long? = null
            if (canonical != null) {
                val queried = runCatching {
                    context.contentResolver.query(
                        canonical,
                        arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            if (!cursor.isNull(0)) taken = cursor.getLong(0).takeIf { it > 0 }
                            if (!cursor.isNull(1)) {
                                modified = cursor.getLong(1).takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                            }
                        }
                    }
                }.isSuccess
                if (!queried) {
                    taken = queryTime(context, canonical, MediaStore.Images.Media.DATE_TAKEN)
                    modified = queryTime(context, canonical, MediaStore.MediaColumns.DATE_MODIFIED)
                        ?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
                }
            } else {
                taken = queryTime(context, source, MediaStore.Images.Media.DATE_TAKEN)
                modified = queryTime(context, source, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            }
            if (taken == null) taken = exifCaptureTime(context, source)
            val descriptorTime = runCatching {
                context.contentResolver.openFileDescriptor(source, "r")?.use {
                    val stat = android.system.Os.fstat(it.fileDescriptor)
                    if (!android.system.OsConstants.S_ISREG(stat.st_mode)) return@use null
                    val seconds = stat.st_mtime.takeIf { value -> value > 0 && value <= Long.MAX_VALUE / 1000 } ?: return@use null
                    seconds * 1000 + stat.st_mtim.tv_nsec / 1_000_000
                }
            }.getOrNull()
            // Cloud providers may return a temporary download fd; their indexed date wins.
            val local = source.authority == "media" || source.authority == "com.android.externalstorage.documents"
            val fileTime = if (local) descriptorTime ?: modified else modified ?: descriptorTime
            // DATE_ADDED and file modification time are not capture time.
            return SourceTimestamps(taken, fileTime)
        }

        fun read(context: Context, source: Uri): SourceMedia? {
            val uri = MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, source) ?: return null
            return runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val volume = c.getString(0) ?: return@use null
                    val path = c.getString(1) ?: return@use null
                    if (volume in setOf("external", "internal") || path.isBlank() || path.startsWith('/') ||
                        path.split('/').any { it == ".." || it == "." }) return@use null
                    val date = c.getLong(2).takeIf { it > 0 } ?: exifCaptureTime(context, source)
                    val modified = c.getLong(3).takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
                    SourceMedia(volume, path.trimEnd('/') + "/", date, modified)
                }
            }.getOrNull()
        }
    }
}
