package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.ultraslim.data.HistoryEntity
import java.io.BufferedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object AppleLiveExporter {
    suspend fun export(context: Context, record: HistoryEntity, destination: Uri) = withContext(Dispatchers.IO) {
        require(record.format.equals("HEIC", ignoreCase = true)) { "请先将动态照片转换为 HEIC" }
        val source = OutputStore.uri(context, record.outputUri)
        val info = MotionPhotoHelper.detect(context, source) ?: error("此成品没有动态视频，请用原始动态照片重新转换为 HEIC")
        val task = currentCoroutineContext()
        val fd = context.contentResolver.openFileDescriptor(source, "r") ?: error("无法读取成品")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法写入导出文件")
            try {
                BufferedOutputStream(output, 128 * 1024).use { out ->
                    AppleLiveBundle.write(input.channel, info, out) { task.ensureActive() }
                    out.flush()
                }
            } catch (e: Exception) {
                // Remove our incomplete SAF document; never touch the original/output photo.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
                throw e
            }
        }
    }
}
