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

        private fun fstatMillis(fd: java.io.FileDescriptor): Long? = runCatching {
            val stat = android.system.Os.fstat(fd)
            if (!android.system.OsConstants.S_ISREG(stat.st_mode)) return@runCatching null
            val seconds = stat.st_mtime.takeIf { value -> value > 0 && value <= Long.MAX_VALUE / 1000 } ?: return@runCatching null
            seconds * 1000 + stat.st_mtim.tv_nsec / 1_000_000
        }.getOrNull()

        fun timestamps(
            context: Context,
            source: Uri,
            hasExif: Boolean = true,
            descriptor: java.io.FileDescriptor? = null,
        ): SourceTimestamps {
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
            if (taken == null && hasExif) taken = exifCaptureTime(context, source)
            val descriptorTime = if (descriptor != null) fstatMillis(descriptor) else runCatching {
                context.contentResolver.openFileDescriptor(source, "r")?.use { fstatMillis(it.fileDescriptor) }
            }.getOrNull()
            // Cloud providers (including Photo Picker cloud items under content://media/picker/...)
            // may return a temporary download fd; their indexed date wins.
            val local = (source.authority == MediaStore.AUTHORITY && source.pathSegments.firstOrNull() != "picker") ||
                source.authority == "com.android.externalstorage.documents"
            val fileTime = if (local) descriptorTime ?: modified else modified ?: descriptorTime
            // DATE_ADDED and file modification time are not capture time.
            return SourceTimestamps(taken, fileTime)
        }

        private fun parseRow(volume: String?, path: String?, rawTaken: Long, rawModified: Long, fallbackDate: () -> Long?): SourceMedia? {
            if (volume == null || path == null) return null
            if (volume in setOf("external", "internal") || path.isBlank() || path.startsWith('/') ||
                path.split('/').any { it == ".." || it == "." }) return null
            val date = rawTaken.takeIf { it > 0 } ?: fallbackDate()
            val modified = rawModified.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
            return SourceMedia(volume, path.trimEnd('/') + "/", date, modified)
        }

        fun read(context: Context, source: Uri): SourceMedia? {
            val uri = MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, source) ?: return null
            return runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    parseRow(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)) {
                        exifCaptureTime(context, source)
                    }
                }
            }.getOrNull()
        }

        /** Batch-query volume and relativePath by _ID IN (...) to avoid 2N IPCs in AutoReplacement.validate. */
        fun readBatch(context: Context, sources: Collection<Uri>, includeExifFallback: Boolean = false): Map<Uri, SourceMedia> {
            if (sources.isEmpty()) return emptyMap()
            val out = HashMap<Uri, SourceMedia>(sources.size)
            val byCollection = HashMap<Uri, MutableMap<Long, MutableList<Uri>>>()
            sources.forEach { source ->
                val target = MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, source)
                val segs = target?.pathSegments
                val id = segs?.lastOrNull()?.toLongOrNull()
                if (target == null || segs == null || id == null || segs.size < 2) return@forEach
                val collection = Uri.Builder().scheme(target.scheme).authority(target.authority)
                    .path(segs.dropLast(1).joinToString("/")).build()
                byCollection.getOrPut(collection) { HashMap() }.getOrPut(id) { mutableListOf() } += source
            }
            byCollection.forEach { (collection, ids) ->
                ids.keys.chunked(500).forEach { chunk ->
                    runCatching {
                        context.contentResolver.query(
                            collection,
                            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME,
                                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN,
                                MediaStore.MediaColumns.DATE_MODIFIED),
                            "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                            chunk.map { it.toString() }.toTypedArray(),
                            null,
                        )?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getLong(0)
                                val callers = ids[id] ?: continue
                                val media = parseRow(c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)) {
                                    if (includeExifFallback) callers.firstOrNull()?.let { exifCaptureTime(context, it) } else null
                                } ?: continue
                                callers.forEach { out[it] = media }
                            }
                        }
                    }
                }
            }
            return out
        }
    }
}
