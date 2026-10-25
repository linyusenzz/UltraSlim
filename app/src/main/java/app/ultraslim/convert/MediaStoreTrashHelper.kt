package app.ultraslim.convert

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

object MediaStoreTrashHelper {
    // Android 16+ rejects larger requests. This is a system request limit, not a queue limit.
    const val MAX_REQUEST_ITEMS = 2000
    val isTrashSupported: Boolean get() = true
    enum class OriginalState { PRESENT, TRASHED, UNAVAILABLE }
    data class Request(val sender: IntentSender? = null, val uris: List<Uri> = emptyList(), val message: String? = null)

    /** Only platform mappings are trusted. Never reinterpret a picker/cloud id as a local media id. */
    fun resolveToMediaStoreImageUri(context: Context, uri: Uri): Uri? {
        if (uri.scheme != "content") return null
        val parts = uri.pathSegments
        if (uri.authority == MediaStore.AUTHORITY) {
            if (parts.size == 4 && parts[1] == "images" && parts[2] == "media" &&
                parts.last().toLongOrNull() != null) return uri
            // ExternalStorageProvider maps document URIs to the Files collection on some systems.
            if (parts.size == 3 && parts[1] == "file" && parts.last().toLongOrNull() != null) {
                return runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)?.use {
                        if (it.moveToFirst() && it.getInt(0) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
                            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(parts[0]), parts.last().toLong()) else null
                    }
                }.getOrNull()
            }
            return null
        }
        if (uri.authority in setOf(
                "com.android.providers.media.documents", "com.android.externalstorage.documents")) {
            val mapped = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            if (mapped != null && mapped != uri) return resolveToMediaStoreImageUri(context, mapped)
        }
        return null
    }

    /** Includes trashed rows; an empty/denied query is never permission to delete anything. */
    fun originalState(context: Context, uri: Uri): OriginalState {
        if (!isTrashSupported) return OriginalState.UNAVAILABLE
        val target = resolveToMediaStoreImageUri(context, uri) ?: return OriginalState.UNAVAILABLE
        return runCatching {
            context.contentResolver.query(target, arrayOf(MediaStore.MediaColumns.IS_TRASHED),
                Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)?.use {
                if (!it.moveToFirst()) OriginalState.UNAVAILABLE
                else if (it.getInt(0) != 0) OriginalState.TRASHED else OriginalState.PRESENT
            } ?: OriginalState.UNAVAILABLE
        }.getOrDefault(OriginalState.UNAVAILABLE)
    }

    /**
     * 与 [originalState] 相同语义，但同一存储卷上的媒体项合并为一次 `_ID IN (...)` 查询。
     * 批量请求可达成千条，逐条 round-trip 会长时间卡在 provider 上。
     * 查询失败/被拒时整卷按 UNAVAILABLE 处理——绝不把查询失败当作可以删除的依据。
     */
    fun originalStates(context: Context, uris: Collection<Uri>): Map<Uri, OriginalState> {
        if (!isTrashSupported) return uris.associateWith { OriginalState.UNAVAILABLE }
        val result = HashMap<Uri, OriginalState>(uris.size)
        // 按集合（卷）分组，组内合并成一次查询：
        // content://media/<volume>/images/media/<id> → 集合 content://media/<volume>/images/media
        val byCollection = HashMap<Uri, MutableMap<Long, MutableList<Uri>>>()
        uris.forEach { uri ->
            val target = resolveToMediaStoreImageUri(context, uri)
            val segments = target?.pathSegments
            val id = segments?.lastOrNull()?.toLongOrNull()
            if (target == null || segments == null || id == null || segments.size < 2) {
                result[uri] = if (target == null) OriginalState.UNAVAILABLE else originalState(context, uri)
                return@forEach
            }
            val collection = Uri.Builder().scheme(target.scheme).authority(target.authority)
                .path(segments.dropLast(1).joinToString("/")).build()
            byCollection.getOrPut(collection) { HashMap() }.getOrPut(id) { mutableListOf() } += uri
        }
        byCollection.forEach { (collection, ids) ->
            val idList = ids.keys.toList()
            val found = HashMap<Long, Boolean>()
            val queried = runCatching {
                idList.chunked(500).forEach { chunk ->
                    // 选择条件必须放进 Bundle：带 QUERY_ARG_MATCH_TRASHED 的 4 参重载不接 SQL 字符串。
                    val queryArgs = Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})")
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            chunk.map { it.toString() }.toTypedArray())
                    }
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED),
                        queryArgs,
                        null,
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                        val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                        while (cursor.moveToNext()) {
                            if (idIndex >= 0) found[cursor.getLong(idIndex)] = trashedIndex >= 0 && cursor.getInt(trashedIndex) != 0
                        }
                    } ?: error("回收站状态查询被拒绝")
                }
            }.isSuccess
            idList.forEach { id ->
                val state = when {
                    !queried -> OriginalState.UNAVAILABLE
                    found[id] == null -> OriginalState.UNAVAILABLE
                    found[id] == true -> OriginalState.TRASHED
                    else -> OriginalState.PRESENT
                }
                ids.getValue(id).forEach { result[it] = state }
            }
        }
        return result
    }

    fun activeOriginal(context: Context, uri: Uri): Uri? = resolveToMediaStoreImageUri(context, uri)
        ?.takeIf { originalState(context, it) == OriginalState.PRESENT }

    fun prepareTrashRequest(context: Context, uris: Collection<Uri>): Request {
        if (!isTrashSupported) return Request(message = "移入回收站需要 Android 11 或更新版本")
        val states = originalStates(context, uris)
        val resolved = uris.mapNotNull { uri ->
            if (states[uri] == OriginalState.PRESENT) resolveToMediaStoreImageUri(context, uri) else null
        }.distinct().take(MAX_REQUEST_ITEMS)
        if (resolved.isEmpty()) return Request(message = "原图已删除、已在回收站或当前没有访问权限；可通过相册导入重新授权")
        return try {
            Request(MediaStore.createTrashRequest(context.contentResolver, resolved, true).intentSender, resolved)
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreTrash", "Trash request rejected (${resolved.size} items)", e)
            val reason = when (e) {
                is SecurityException -> "相册权限不足，请在系统设置中允许访问照片后重试"
                is IllegalArgumentException -> "系统未接受这些原图的回收站请求，请通过相册导入重新选择"
                else -> "系统相册未能创建回收站请求（${e.javaClass.simpleName}），请在相册中手动处理"
            }
            Request(message = reason)
        }
    }

    fun createTrashIntentSender(context: Context, uris: Collection<Uri>): IntentSender? = prepareTrashRequest(context, uris).sender
    fun isOriginalTrashed(context: Context, uri: Uri) = originalState(context, uri) == OriginalState.TRASHED
    fun isOriginalAccessible(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
