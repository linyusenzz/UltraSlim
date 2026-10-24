package app.ultraslim.convert

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.ultraslim.model.OutputFormat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private fun disambiguateName(desired: String, existing: Set<String>): String {
    if (desired !in existing) return desired
    val base = desired.substringBeforeLast('.', desired)
    val ext = desired.substringAfterLast('.', "")
    var index = 2
    while (true) {
        val candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
        if (candidate !in existing) return candidate
        index++
    }
}

/**
 * Publishes a transcoded file into a chosen MediaStore album (default Pictures/UltraSlim),
 * using the IS_PENDING flow on Android 10+.
 *
 * Returns the content uri of the new entry, or null on failure. On Android 9
 * and below saving is not attempted (WRITE_EXTERNAL_STORAGE is not declared).
 */
object MediaStoreSaver {
    private val STABLE_NAME_REGEX = Regex(".*_Slim_[0-9a-f]{8}(?:MP)?\\.[^.]+")

    fun save(
        context: Context,
        sourceFile: File,
        displayName: String,
        format: OutputFormat,
        taskId: String? = null,
        relativePath: String = "Pictures/UltraSlim/",
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        dateTaken: Long? = null,
        relatedUri: Uri? = null,
        expectedFingerprint: Fingerprint? = null,
        fileModifiedTime: Long? = dateTaken,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceFile.isFile) return null

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(volume)
        // Stable name enables recovery if the process died after MediaStore publication.
        val desired = if (taskId != null && !displayName.matches(STABLE_NAME_REGEX)) {
            // Recover records created by earlier app versions with their original stable name.
            displayName.substringBeforeLast('.') + "_" + taskId + "." + format.extension
        } else displayName
        // A name is only a recovery hint. Never delete or adopt an existing item
        // (including a pending one) unless its readable bytes match our output.
        val expected = expectedFingerprint
            ?: runCatching { sourceFile.inputStream().use(::fingerprint) }.getOrNull() ?: return null
        if (expected.bytes == 0L) return null
        val existingNames = mutableSetOf<String>()
        var recovered: Uri? = null
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val numbered = escapeLike(base) + " (%)" + if (extension.isEmpty()) "" else "." + escapeLike(extension)
        val queried = runCatching {
            resolver.query(collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                    "(${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\')",
                arrayOf(relativePath, desired, numbered), "${MediaStore.Images.Media._ID} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    existingNames += cursor.getString(2)
                    if (taskId != null && recovered == null && cursor.getInt(1) == 0) {
                        val found = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                        if (matches(resolver, found, expected)) recovered = found
                    }
                }
            } ?: throw IOException("无法检查相册中已有文件")
        }.isSuccess
        if (!queried) return null
        recovered?.let {
            preserveFileTime(context, it, fileModifiedTime)
            preserveCaptureTime(context, it, dateTaken)
            return it
        }
        val chosen = disambiguateName(desired, existingNames)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, chosen)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            dateTaken?.takeIf { it > 0 }?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && relatedUri != null) resolver.insert(collection, values,
                android.os.Bundle().apply { putParcelable(MediaStore.QUERY_ARG_RELATED_URI, relatedUri) })
            else resolver.insert(collection, values)
        }.getOrNull()
        if (uri == null) return null

        return try {
            // 写入时同步计算摘要：数据刚流经内存，不必再多一次整读就能确认写盘内容。
            // 短写或内容不符在这里失败，发布后的整读校验仍是最终安全门。
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            out.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            written += count
                        }
                    }
                }
            } ?: throw IOException("openOutputStream returned null for $uri")
            check(written == expected.bytes && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) {
                "相册副本写入校验失败"
            }
            // Capture time and file modification time are different. Preserve both when known;
            // never rewrite EXIF or DATE_ADDED to force a gallery's sorting preference.
            preserveFileTime(context, uri, fileModifiedTime)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            dateTaken?.takeIf { it > 0 }?.let { values.put(MediaStore.Images.Media.DATE_TAKEN, it) }
            check(resolver.update(uri, values, null, null) == 1) { "无法发布相册文件" }
            // Publishing may run the scanner, which can replace DATE_TAKEN when it cannot read
            // this output's EXIF. Reapply the known source date after publication, best effort.
            preserveCaptureTime(context, uri, dateTaken)
            preserveFileTime(context, uri, fileModifiedTime)
            check(matches(resolver, uri, expected)) { "相册副本发布校验失败" }
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    data class Fingerprint(val bytes: Long, val sha256: String)

    /** One read of the private result; callers pass it down to avoid re-hashing the same file. */
    internal fun fingerprintOf(file: File): Fingerprint? =
        runCatching { file.inputStream().use(::fingerprint) }.getOrNull()

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) { digest.update(buffer, 0, count); bytes += count }
        }
        return Fingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun matches(resolver: ContentResolver, uri: Uri, expected: Fingerprint): Boolean = runCatching {
        resolver.openInputStream(uri)?.use(::fingerprint) == expected
    }.getOrDefault(false)

    /** Final gate before the private result can be discarded by OutputStore. */
    internal fun matchesAlbumCopy(context: Context, uri: Uri, expected: Fingerprint?): Boolean {
        val known = expected ?: return false
        return known.bytes > 0 && matches(context.contentResolver, uri, known)
    }

    internal fun preserveCaptureTime(context: Context, uri: Uri, timestamp: Long?) {
        if (timestamp == null || timestamp <= 0) return
        runCatching {
            val current = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
            if (current != timestamp) context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            }, null, null)
        }.onFailure { android.util.Log.w("MediaStoreSaver", "Source capture time could not be indexed", it) }
    }

    internal fun preserveFileTime(context: Context, uri: Uri, timestamp: Long?) {
        if (timestamp == null || timestamp <= 0) return
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                if (app.ultraslim.bridge.UltraHdrBridge.nativeUpdateFileTime(descriptor.fd, timestamp)) {
                    // FAT and some providers round mtime to whole seconds (occasionally two).
                    // A timestamp limitation must not discard an otherwise verified export.
                    val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                    val actual = stat.st_mtime * 1000 + if (Build.VERSION.SDK_INT >= 27) stat.st_mtim.tv_nsec / 1_000_000 else 0
                    if (kotlin.math.abs(actual - timestamp) > 2000)
                        android.util.Log.w("MediaStoreSaver", "Provider did not retain requested file time")
                    return@use
                }
                // Resolve the descriptor we own; opening /proc/self/fd directly for utime is denied by FUSE.
                val path = android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")
                check(File(path).setLastModified(timestamp)) { "Provider declined timestamp update" }
            }
        }.onFailure { android.util.Log.w("MediaStoreSaver", "Original file timestamp could not be preserved", it) }
    }

}
