package app.ultraslim.convert

import android.util.Log
import org.json.JSONObject

/** Explicitly enabled via `adb shell setprop log.tag.UltraSlimPerf DEBUG`; no photo identifiers. */
internal class ConversionPerformance(format: String, private val thermalStart: Int) {
    private val timings = if (Log.isLoggable(TAG, Log.DEBUG)) StageTimings() else null
    private val formatName = format

    suspend fun <T> measure(stage: String, block: suspend () -> T): T =
        if (timings != null) timings.measure(stage, block) else block()

    fun finish(outcome: String, thermalEnd: () -> Int) {
        val sample = timings?.snapshot() ?: return
        // Diagnostics must never alter publishing, cancellation or checkpoint behavior.
        runCatching {
            Log.i(TAG, JSONObject().put("event", "conversion").put("version", 1)
                .put("format", formatName).put("outcome", outcome)
                .put("totalMs", sample.totalMs).put("stagesMs", JSONObject(sample.stagesMs))
                .put("thermalStart", thermalStart).put("thermalEnd", thermalEnd()).toString())
        }
    }

    companion object { const val TAG = "UltraSlimPerf" }
}
