package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Copy only ephemeral grants. Picker/document grants and accessible MediaStore inputs remain fd based. */
object InputStore {
    fun persist(context: Context, uri: Uri, id: String, hasTreeGrant: Boolean = false, checkCancelled: () -> Unit = {}): Uri {
        if (uri.scheme != "content") error("请从系统图片选择器导入")
        // A persisted tree grant already covers every child. Taking one grant per image can
        // exhaust the system grant quota and cause unnecessary copies of a large folder.
        if (hasTreeGrant || (DocumentsContract.isTreeUri(uri) && context.contentResolver.persistedUriPermissions.any { grant ->
                grant.isReadPermission && grant.uri.authority == uri.authority &&
                    DocumentsContract.isTreeUri(grant.uri) &&
                    DocumentsContract.getTreeDocumentId(grant.uri) == DocumentsContract.getTreeDocumentId(uri)
            })) return uri
        if (runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess) return uri
        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (uri.authority == "media" && ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return uri
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

}
