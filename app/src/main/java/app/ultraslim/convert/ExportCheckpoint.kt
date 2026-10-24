package app.ultraslim.convert

import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Durable source dates and ownership of a publication, independent of the source grant. */
internal class ExportCheckpoint(private val output: File) {
    private val storage = AtomicFile(File(output.path + ".export.json"))
    private fun read(): JSONObject = runCatching {
        JSONObject(storage.openRead().bufferedReader().use { it.readText() })
    }.getOrElse { JSONObject() }

    private fun write(json: JSONObject) {
        val stream = storage.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) { storage.failWrite(stream); throw e }
    }

    fun capture(dates: SourceTimestamps) = write(read().apply {
        put("datesCaptured", true)
        put("taken", dates.dateTaken ?: 0L)
        put("modified", dates.modifiedAt ?: 0L)
    })

    fun dates(): SourceTimestamps? = read().let {
        if (!it.optBoolean("datesCaptured")) null else SourceTimestamps(
            it.optLong("taken").takeIf { value -> value > 0 },
            it.optLong("modified").takeIf { value -> value > 0 })
    }

    fun published(destination: String): Uri? = read().let {
        if (it.optString("destination") == destination) it.optString("uri").takeIf(String::isNotEmpty)?.let(Uri::parse)
        else null
    }

    fun record(destination: String, uri: Uri) = write(read().apply {
        put("destination", destination); put("uri", uri.toString())
    })
    fun clearPublication() = write(read().apply { remove("destination"); remove("uri") })
    fun failure(message: String?) = write(read().apply { put("error", message ?: "") })
    fun failure(): String? = read().optString("error").takeIf(String::isNotBlank)
    fun delete() = storage.delete()
}
