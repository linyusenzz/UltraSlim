package app.ultraslim.ui.history

/** Main-thread cache. A relink invalidates both cached results and older in-flight resolutions. */
internal class OriginalUriCache(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val negativeTtlMillis: Long = 15_000L,
) {
    data class Source(val id: Long, val uri: String)
    data class Lookup(val source: Source, val generation: Long) {
        val id: Long get() = source.id
        val uri: String get() = source.uri
    }

    private val current = mutableMapOf<Long, String>()
    private val resolved = mutableMapOf<Long, String>()
    private val unavailableUntil = mutableMapOf<Long, Long>()
    private val inFlight = mutableMapOf<Source, Lookup>()
    private var generation = 0L

    fun plan(sources: List<Source>): List<Lookup> {
        val now = clockMillis()
        return sources.distinct().mapNotNull { source ->
            if (current.put(source.id, source.uri) != source.uri) {
                resolved.remove(source.id)
                unavailableUntil.remove(source.id)
                inFlight.keys.removeAll { it.id == source.id }
            }
            if (source.id in resolved || now < (unavailableUntil[source.id] ?: Long.MIN_VALUE) || source in inFlight) null
            else Lookup(source, ++generation).also { inFlight[source] = it }
        }
    }

    fun complete(lookups: List<Lookup>, results: Map<Lookup, String>) {
        val now = clockMillis()
        lookups.forEach { lookup ->
            // A refresh, relink or newer request invalidates old work, even for the same URI.
            if (inFlight[lookup.source] != lookup || current[lookup.id] != lookup.uri) return@forEach
            inFlight.remove(lookup.source)
            val uri = results[lookup]
            if (uri == null) {
                resolved.remove(lookup.id)
                unavailableUntil[lookup.id] = now + negativeTtlMillis
            } else {
                resolved[lookup.id] = uri
                unavailableUntil.remove(lookup.id)
            }
        }
    }

    fun abandon(lookups: List<Lookup>) {
        lookups.forEach { if (inFlight[it.source] == it) inFlight.remove(it.source) }
    }

    /** Foreground/permission return and explicit refresh must be able to recover immediately. */
    fun invalidate() {
        resolved.clear()
        unavailableUntil.clear()
        inFlight.clear()
    }

    /** Keep only the current window and any selection carried across windows. */
    fun retain(sources: List<Source>) {
        val ids = sources.mapTo(mutableSetOf()) { it.id }
        current.keys.retainAll(ids)
        resolved.keys.retainAll(ids)
        unavailableUntil.keys.retainAll(ids)
        inFlight.keys.removeAll { it.id !in ids }
    }

    fun snapshot(): Map<Long, String> = resolved.toMap()
}
