package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONObject

internal data class SourceTimestamps(val dateTaken: Long? = null, val modifiedAt: Long? = null)

/** Provider-only dates survive copying an ephemeral grant without changing the image bytes. */
internal object InputMetadataStore {
    private fun directory(context: Context) = File(context.filesDir, "input-metadata")
    private fun file(context: Context, id: String) = File(directory(context), "$id.json")

    fun write(context: Context, id: String, timestamps: SourceTimestamps) {
        val atomic = AtomicFile(file(context, id))
        var stream: java.io.FileOutputStream? = null
        try {
            directory(context).mkdirs()
            val json = JSONObject().put("dateTaken", timestamps.dateTaken).put("modifiedAt", timestamps.modifiedAt)
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            android.util.Log.w("InputMetadata", "Source dates could not be retained", e)
        }
    }

    fun read(context: Context, uri: Uri): SourceTimestamps? {
        if (uri.authority != "${context.packageName}.files") return null
        val id = uri.lastPathSegment ?: return null
        if (id.contains('/') || id.contains('\\') || id in setOf(".", "..")) return null
        val input = File(context.filesDir, "inputs/$id")
        if (FileProvider.getUriForFile(context, "${context.packageName}.files", input) != uri) return null
        return runCatching {
            val json = JSONObject(String(AtomicFile(file(context, id)).readFully(), Charsets.UTF_8))
            SourceTimestamps(json.optLong("dateTaken").takeIf { it > 0 },
                json.optLong("modifiedAt").takeIf { it > 0 })
        }.getOrNull()
    }

    fun cleanUnreferenced(context: Context, referenced: Set<String>, cutoff: Long) {
        directory(context).listFiles()?.forEach { metadata ->
            val id = metadata.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json")
            val input = File(context.filesDir, "inputs/$id")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input).toString()
            if (metadata.lastModified() < cutoff && input.path !in referenced && uri !in referenced) metadata.delete()
        }
    }
}
