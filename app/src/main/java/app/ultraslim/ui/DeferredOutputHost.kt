package app.ultraslim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.decodeDefaults
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate

/** Small-window/background encoding stays private until the user returns to the full app. */
@Composable
internal fun DeferredOutputHost() {
    val app = LocalContext.current.applicationContext as UltraSlimApp
    LaunchedEffect(app) {
        combine(app.historyRepository.pendingPublicationBatches, app.galleryPublishingAllowed,
            app.conversionServiceRunning,
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)) { rows, allowed, service, work ->
            rows to (allowed && !service && work.none { !it.state.isFinished })
        }.conflate().collect { (rows, allowed) ->
            if (!allowed || rows.isEmpty()) return@collect
            app.galleryPublicationRunning.value = true
            try {
                // Keep bind counts below older SQLite limits, even with thousands of batches.
                for (batchIds in rows.map { it.batchId }.chunked(400)) {
                    var afterCreatedAt = Long.MIN_VALUE
                    var afterId = Long.MIN_VALUE
                    while (true) {
                        if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) return@collect
                        val page = app.database.historyDao().pendingPublicationPage(batchIds, afterCreatedAt, afterId, 32)
                        if (page.isEmpty()) break
                        afterCreatedAt = page.last().createdAt
                        afterId = page.last().id
                        // 同一批次设置按页缓存。
                        val batchCache = HashMap<String, Pair<app.ultraslim.data.BatchEntity, app.ultraslim.data.ConversionDefaults>?>()
                        for (row in page) {
                            if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) break
                            val pair = batchCache.getOrPut(row.batchId) {
                                val b = app.database.taskDao().batch(row.batchId) ?: return@getOrPut null
                                val o = runCatching { decodeDefaults(b.settings) }.getOrNull() ?: return@getOrPut null
                                b to o
                            } ?: continue
                            val (batch, options) = pair
                            if (batch.cancelled || !options.autoSave) continue
                            // Recheck after the suspend batch lookup, immediately before saving.
                            if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) break
                            try {
                                OutputStore.save(app, row)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) {
                                // A deleted history row or lost provider must not crash the foreground app.
                                android.util.Log.w("DeferredOutput", "Publication failed", e)
                                app.database.historyDao().byId(row.id)?.let {
                                    app.database.historyDao().update(it.copy(saveState = "FAILED"))
                                }
                            }
                        }
                        if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) return@collect
                    }
                }
            } finally { app.galleryPublicationRunning.value = false }
        }
    }
}
