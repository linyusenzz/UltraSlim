package app.ultraslim.convert

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.ForegroundInfo
import androidx.core.content.ContextCompat
import app.ultraslim.UltraSlimApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** User-started encoding must not consume JobScheduler's Android 16 execution quota. */
class ConversionService : Service() {
    companion object {
        /** Call while the initiating activity is visible, after its batch has been persisted. */
        fun start(app: UltraSlimApp, batchId: String) {
            check(app.conversionServiceRunning.compareAndSet(false, true)) { "已有转换任务正在运行" }
            app.conversionThermalState.value = null
            try {
                ContextCompat.startForegroundService(app,
                    Intent(app, ConversionService::class.java).putExtra(ConversionWorker.KEY_BATCH, batchId))
            } catch (e: Exception) {
                app.conversionServiceRunning.value = false
                throw IllegalStateException("系统未允许后台启动，请回到应用点击转换；队列已保留", e)
            }
        }
    }

    private val app get() = application as UltraSlimApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("ConversionService", "Failed to checkpoint interrupted batch", error)
    })
    private var job: Job? = null
    @Volatile private var destroyed = false
    private var finished = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var startupFailure: String? = null
    @Volatile private var stopReason = "后台转换被系统中断，请回到应用继续"
    private lateinit var runner: ConversionRunner

    override fun onCreate() {
        super.onCreate()
        runner = ConversionRunner(app) { info ->
            promote(info)
        }
        // Promote before any Room access, readiness waits or native work.
        try {
            val info = runner.getForegroundInfo()
            promote(info)
        } catch (e: Exception) {
            startupFailure = "后台服务启动失败，请回到应用继续：${e.message}"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!app.conversionServiceRunning.value) return START_NOT_STICKY
        if (job != null) return START_NOT_STICKY
        val id = intent?.getStringExtra(ConversionWorker.KEY_BATCH)
        if (id == null) {
            app.conversionServiceRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            var interrupted = false
            try {
                startupFailure?.let { error(it) }
                app.conversionMutex.withLock {
                    acquireWakeLock()
                    var attempt = 0
                    var coolingStartedAt = 0L
                    while (true) {
                        when (runner.run(id, attempt)) {
                            ConversionRunner.Outcome.COMPLETE, ConversionRunner.Outcome.FAILED,
                            ConversionRunner.Outcome.PAUSED -> break
                            ConversionRunner.Outcome.COOLING -> {
                                // Keep the foreground service alive: do not restart it from the background.
                                releaseWakeLock()
                                attempt = runner.coolingAttempt
                                if (attempt == 0) coolingStartedAt = SystemClock.elapsedRealtime()
                                val deadline = coolingStartedAt + ConversionThermalPolicy.remainingWaitSeconds(0) * 1000L
                                val retryAt = minOf(deadline, SystemClock.elapsedRealtime() +
                                    ConversionThermalPolicy.retryDelaySeconds(attempt) * 1000L)
                                var nextThermalCheck = SystemClock.elapsedRealtime() +
                                    ConversionThermalPolicy.CHECK_INTERVAL_SECONDS * 1000L
                                while (SystemClock.elapsedRealtime() < retryAt) {
                                    val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                                    app.conversionThermalState.value?.let { state ->
                                        val updated = state.copy(remainingSeconds = remaining)
                                        app.conversionThermalState.value = updated
                                        promote(runner.createForegroundInfo(0, "等待系统解除热限制", updated.message, false))
                                    }
                                    delay(1000)
                                    // Pending rows can be removed while cooling. Do not leave an
                                    // empty batch running until its backoff/deadline expires.
                                    val batchItems = app.conversionRepository.queue.value.filter { it.batchId == id }
                                    if (batchItems.none { it.status != QueueStatus.DONE && it.status != QueueStatus.SKIPPED }) {
                                        app.conversionThermalState.value = null
                                        return@withLock
                                    }
                                    if (app.database.taskDao().batch(id)?.cancelled != false) {
                                        val pendingIds = batchItems.map { it.id }
                                        app.conversionRepository.updateAll(pendingIds) {
                                            if (it.status == QueueStatus.READY && it.error?.contains("等待降温") == true)
                                                it.copy(error = "已停止等待降温，请稍后点击继续；原图保留") else it
                                        }
                                        return@withLock
                                    }
                                    if (SystemClock.elapsedRealtime() >= nextThermalCheck) {
                                        val status = runner.currentThermalStatus()
                                        if (!ConversionThermalPolicy.requiresCooling(status)) break
                                        app.conversionThermalState.value = app.conversionThermalState.value?.copy(status = status)
                                        nextThermalCheck = SystemClock.elapsedRealtime() +
                                            ConversionThermalPolicy.CHECK_INTERVAL_SECONDS * 1000L
                                    }
                                }
                                if (SystemClock.elapsedRealtime() >= deadline &&
                                    ConversionThermalPolicy.requiresCooling(runner.currentThermalStatus())) {
                                    runner.pauseCooling()
                                    break
                                }
                                attempt++
                                acquireWakeLock()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                interrupted = true
                throw e
            } catch (e: Exception) {
                interrupted = true
                stopReason = "后台转换已暂停：${e.message ?: "服务异常"}；请回到应用继续"
            } finally {
                try {
                    withContext(NonCancellable) {
                        val repo = app.conversionRepository
                        repo.awaitReady()
                        if (interrupted) {
                            val batchIds = repo.queue.value.filter { it.batchId == id }.map { it.id }
                            repo.updateAll(batchIds) {
                                if (it.batchId == id && it.status != QueueStatus.DONE &&
                                    it.status != QueueStatus.SKIPPED && it.status != QueueStatus.FAILED)
                                    it.copy(status = QueueStatus.READY, error = stopReason) else it
                            }
                        }
                        repo.flush()
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        releaseWakeLock()
                        if (app.conversionThermalState.value?.phase == ThermalPhase.COOLING)
                            app.conversionThermalState.value = null
                        finished = true
                        // Don't accept a new batch in the interval between stopSelf and onDestroy.
                        if (destroyed) app.conversionServiceRunning.value = false
                        else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        job?.invokeOnCompletion {
            // Also handles destruction before a dispatched coroutine enters its try/finally.
            if (destroyed) app.conversionServiceRunning.value = false
        }
        // Interrupted queues are durable and explicitly resumed by the user; no background restart loop.
        return START_NOT_STICKY
    }

    private fun promote(info: ForegroundInfo) {
        // Core 1.17 ServiceCompat masks types to the API 34 set and strips mediaProcessing.
        startForeground(info.notificationId, info.notification, info.foregroundServiceType)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ grants mediaProcessing only six background hours per 24 h.
        // Native encoding cannot be interrupted mid-call; stop the service promptly regardless.
        stopReason = "已达到系统后台处理时限，请回到应用继续；已完成图片保留"
        job?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltraSlim:conversion")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }
    @Synchronized private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        releaseWakeLock()
        if (job == null || finished || job?.isCompleted == true) app.conversionServiceRunning.value = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
