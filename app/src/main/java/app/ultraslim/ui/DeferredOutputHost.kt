package app.ultraslim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.SelectedOutput
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.decodeDefaults
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate

/** Small-window/background encoding stays private until the user returns to the full app. */
@Composable
internal fun DeferredOutputHost() {
    val app = LocalContext.current.applicationContext as UltraSlimApp
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(app, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(
                app.historyRepository.pendingPublicationBatches,
                app.galleryPublishingAllowed,
                app.conversionRunning,
            ) { rows, allowed, running ->
                rows to (allowed && !running)
            }.conflate().collect { (rows, allowed) ->
                if (!allowed || rows.isEmpty()) return@collect
                app.galleryPublicationRunning.value = true
                val directoryCache = SelectedOutput.DirectoryCache()
                val settingsByBatch = rows.associate { it.batchId to it.settings }
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
                            // 同一批次设置按页缓存，仅查询轻量取消标记与 settings 列。
                            val batchCache = HashMap<String, Pair<Boolean, ConversionDefaults>?>()
                            for (row in page) {
                                if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) break
                                val pair = batchCache.getOrPut(row.batchId) {
                                    val cancelled = app.database.taskDao().isBatchCancelled(row.batchId) ?: return@getOrPut null
                                    val rawSettings = settingsByBatch[row.batchId]
                                        ?: app.database.taskDao().batchSettings(row.batchId)
                                        ?: return@getOrPut null
                                    val o = runCatching { decodeDefaults(rawSettings) }.getOrNull() ?: return@getOrPut null
                                    cancelled to o
                                } ?: continue
                                val (cancelled, options) = pair
                                if (cancelled || !options.autoSave) continue
                                // Recheck after the suspend batch lookup, immediately before saving.
                                if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) break
                                try {
                                    OutputStore.saveInternal(
                                        app = app,
                                        record = row,
                                        knownOptions = options,
                                        directoryCache = directoryCache,
                                    )
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
}
