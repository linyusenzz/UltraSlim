package app.ultraslim.util

import java.text.Normalizer
import java.util.Locale

/** Names offered to storage providers; the provider's returned name is authoritative. */
object ExportNames {
    fun fromSource(source: String, extension: String): String {
        val raw = Normalizer.normalize(source.substringBeforeLast('.', source), Normalizer.Form.NFC)
        val stem = raw.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("").trim(' ', '.').ifBlank { "Photo" }
        return fit(stem, ".${extension.lowercase(Locale.ROOT)}")
    }

    private fun fit(stem: String, tail: String): String {
        val budget = 255 - tail.toByteArray(Charsets.UTF_8).size
        require(budget > 0)
        var end = 0
        var bytes = 0
        while (end < stem.length) {
            val cp = stem.codePointAt(end)
            val chars = Character.charCount(cp)
            val count = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + count > budget) break
            bytes += count
            end += chars
        }
        return stem.substring(0, end) + tail
    }

    fun available(desired: String, occupied: Set<String>): String {
        val used = occupied.mapTo(HashSet()) { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) }
        val extension = desired.substringAfterLast('.', "")
        val stem = desired.substringBeforeLast('.', desired)
        val tail = if (extension.isEmpty()) "" else ".$extension"
        var index = 0
        while (true) {
            val candidate = fit(stem, (if (index == 0) "" else " ($index)") + tail)
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
            index++
        }
    }
}
