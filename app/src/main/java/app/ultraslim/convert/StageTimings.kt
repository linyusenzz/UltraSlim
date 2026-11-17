package app.ultraslim.convert

/** Monotonic wall times. Suspended stages include their wait; nested stages must not be summed. */
internal class StageTimings(private val clock: () -> Long = System::nanoTime) {
    private val started = clock()
    private val elapsed = linkedMapOf<String, Long>()

    suspend fun <T> measure(stage: String, block: suspend () -> T): T {
        val before = clock()
        try {
            return block()
        } finally {
            elapsed[stage] = (elapsed[stage] ?: 0L) + (clock() - before).coerceAtLeast(0L)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        (clock() - started).coerceAtLeast(0L) / 1_000_000.0,
        elapsed.mapValues { it.value / 1_000_000.0 },
    )

    data class Snapshot(val totalMs: Double, val stagesMs: Map<String, Double>)
}
