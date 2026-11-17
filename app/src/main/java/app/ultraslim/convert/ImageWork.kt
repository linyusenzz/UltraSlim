package app.ultraslim.convert

import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Probe, full-resolution preview and encoding share one native working set. */
internal object ImageWork {
    @Volatile private var trace: AtomicFile? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "UltraSlim-images")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var lastProbeTraceAt: Long? = null
    private const val PROBE_TRACE_INTERVAL_MS = 5_000L

    fun initialize(directory: File) {
        val file = AtomicFile(File(directory, "last-image-work.json"))
        // Queue before any work so opening the app cannot erase the interrupted stage.
        executor.execute {
            runCatching {
                val previous = file.readFully()
                if (org.json.JSONObject(String(previous)).optString("state") == "started") {
                    File(directory, "interrupted-image-work.json").writeBytes(previous)
                }
            }
            trace = file
            lastProbeTraceAt = null
        }
    }

    private fun record(stage: String, state: String) {
        val file = trace ?: return
        var stream: java.io.FileOutputStream? = null
        try {
            val json = org.json.JSONObject().put("stage", stage).put("state", state)
                .put("time", System.currentTimeMillis())
            stream = file.startWrite()
            stream.write(json.toString().toByteArray())
            file.finishWrite(stream)
        } catch (_: Exception) { file.failWrite(stream) }
    }

    suspend fun <T> run(stage: String = "probe", block: () -> T): T {
        val measuring = android.util.Log.isLoggable(ConversionPerformance.TAG, android.util.Log.DEBUG)
        val queuedAt = if (measuring) System.nanoTime() else 0L
        return withContext(dispatcher) {
            val enteredAt = if (measuring) System.nanoTime() else 0L
            // 扫描与列表小缩略图只每 5 秒持久化一对记录；编码/大图预览和失败仍逐次记录。
            val now = android.os.SystemClock.elapsedRealtime()
            val sampled = stage == "probe" || stage.startsWith("thumb")
            val traceJob = !sampled || lastProbeTraceAt?.let { now - it >= PROBE_TRACE_INTERVAL_MS } != false
            if (traceJob) {
                if (sampled) lastProbeTraceAt = now
                record(stage, "started")
            }
            try { block().also { if (traceJob) record(stage, "completed") } }
            catch (e: Throwable) { record(stage, "failed"); throw e }
            finally {
                if (measuring) runCatching {
                    android.util.Log.i(ConversionPerformance.TAG, org.json.JSONObject()
                        .put("event", "image_work").put("version", 1).put("stage", stage)
                        .put("waitMs", (enteredAt - queuedAt) / 1_000_000.0)
                        .put("laneMs", (System.nanoTime() - enteredAt) / 1_000_000.0).toString())
                }
            }
        }
    }
}
