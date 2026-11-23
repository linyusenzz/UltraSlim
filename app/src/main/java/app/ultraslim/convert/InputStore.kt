package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.ultraslim.UltraSlimApp
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** Copy only ephemeral grants. Picker/document grants and accessible MediaStore inputs remain fd based. */
object InputStore {
    private const val MAX_NON_TREE_PERSISTED_GRANTS = 400
    private const val PRUNE_THRESHOLD_GRANTS = 360

    // Covers grant acquisition through publication of the queue/history reference, and cleanup.
    internal val referenceLock = Mutex()

    fun persist(context: Context, uri: Uri, id: String, hasTreeGrant: Boolean = false, checkCancelled: () -> Unit = {}): Uri {
        if (uri.scheme != "content") error("请从系统图片选择器导入")
        // A persisted tree grant already covers every child. Taking one grant per image can
        // exhaust the system grant quota and cause unnecessary copies of a large folder.
        if (hasTreeGrant || (DocumentsContract.isTreeUri(uri) && context.contentResolver.persistedUriPermissions.any { grant ->
                grant.isReadPermission && grant.uri.authority == uri.authority &&
                    DocumentsContract.isTreeUri(grant.uri) &&
                    DocumentsContract.getTreeDocumentId(grant.uri) == DocumentsContract.getTreeDocumentId(uri)
            })) return uri
        // Real MediaStore image rows (never Photo Picker content://media/picker/... URIs) are
        // directly readable under full library access or Android 14+ selected-photos access.
        val isMediaStoreImage = uri.authority == android.provider.MediaStore.AUTHORITY &&
            uri.pathSegments.firstOrNull() != "picker" &&
            MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, uri) != null
        if (isMediaStoreImage) {
            val fullPermission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, fullPermission) == PackageManager.PERMISSION_GRANTED) return uri
            if (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
                MediaStoreTrashHelper.isOriginalAccessible(context, uri)
            ) return uri
        }
        // Keep >=100 slots free below Android's 512 per-UID persisted grant cap so SAF folder
        // import/export tree grants can never be starved by cumulative single-photo picks.
        val nonTreeGrants = runCatching {
            context.contentResolver.persistedUriPermissions.count { !DocumentsContract.isTreeUri(it.uri) }
        }.getOrDefault(0)
        if (nonTreeGrants < MAX_NON_TREE_PERSISTED_GRANTS &&
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        ) return uri
        val timestamps = SourceMedia.timestamps(context, uri)
        val file = File(context.filesDir, "inputs/$id").apply { parentFile?.mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) { checkCancelled(); val n = input.read(buffer); if(n < 0) break; size += n
                        check(size <= 256L * 1024 * 1024) { "图片超过 256 MB 限制" }; output.write(buffer,0,n) }
                    check(size > 0) { "图片为空" }
                }
            } ?: error("无法读取图片，请重新授权")
            InputMetadataStore.write(context, id, timestamps)
            return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch(e: Exception) { file.delete(); throw e }
    }

    /** Releases persisted single-item read grants for a specific set of newly unreferenced URIs. */
    fun releaseGrants(context: Context, unreferencedUris: Set<String>) {
        if (unreferencedUris.isEmpty()) return
        runCatching {
            context.contentResolver.persistedUriPermissions.forEach { grant ->
                if (!DocumentsContract.isTreeUri(grant.uri) && grant.isReadPermission && !grant.isWritePermission &&
                    grant.uri.toString() in unreferencedUris
                ) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    /** Releases persisted single-item read grants that are no longer referenced by queue or history. */
    private fun releaseUnreferencedGrants(context: Context, referencedUris: Set<String>) {
        runCatching {
            context.contentResolver.persistedUriPermissions.forEach { grant ->
                if (!DocumentsContract.isTreeUri(grant.uri) && grant.isReadPermission && !grant.isWritePermission &&
                    grant.uri.toString() !in referencedUris
                ) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
    }

    // Caller holds referenceLock. Take a fresh snapshot after acquiring it.
    internal suspend fun pruneAppGrants(app: UltraSlimApp, nearLimitOnly: Boolean = false) {
        val grants = runCatching { app.contentResolver.persistedUriPermissions }.getOrNull() ?: return
        val count = grants.count { !DocumentsContract.isTreeUri(it.uri) }
        if (count == 0 || (nearLimitOnly && count < PRUNE_THRESHOLD_GRANTS)) return
        val active = HashSet<String>()
        app.conversionRepository.queue.value.forEach { active += it.uri; it.outputUri?.let(active::add) }
        app.database.historyDao().allPaths().forEach { active += it.inputUri; active += it.outputUri }
        releaseUnreferencedGrants(app, active)
    }
}
