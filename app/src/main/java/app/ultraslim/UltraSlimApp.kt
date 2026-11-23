package app.ultraslim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import app.ultraslim.convert.InputStore

class UltraSlimApp : Application(), coil3.SingletonImageLoader.Factory, androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    // PreviewCache owns the primary 12 MiB thumb + 34 MiB detail pools;
                    // keep Coil's platform-decode cache modest to avoid duplicate residency.
                    .maxSizeBytes(8 * 1024 * 1024)
                    .build()
            }
            .build()

    // Pre-warmed on IO during Application.onCreate so MainActivity renders Frame 1 in the saved theme.
    internal val initialTheme = kotlinx.coroutines.flow.MutableStateFlow<app.ultraslim.data.ThemeSettings?>(null)

    // Shared with legacy workers to prevent overlapping native encoders during an upgrade.
    internal val conversionMutex = kotlinx.coroutines.sync.Mutex()
    internal val galleryPublicationRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val galleryPublishingAllowed = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val conversionServiceRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val conversionThermalState = kotlinx.coroutines.flow.MutableStateFlow<app.ultraslim.convert.ConversionThermalState?>(null)

    private val replacementRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
    suspend fun awaitReplacementRecovery() = replacementRecovery.await()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val conversionActivity by lazy {
        kotlinx.coroutines.flow.combine(conversionServiceRunning,
            androidx.work.WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)) { service, infos ->
            service || infos.any { !it.state.isFinished }
        }
    }

    internal val conversionRunning by lazy {
        conversionActivity.shareIn(appScope, SharingStarted.WhileSubscribed(5000), replay = 1)
    }

    // One-shot guards must query current work instead of accepting the UI's replayed value.
    internal suspend fun isConversionRunning() = conversionActivity.first()

    val historyRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        app.ultraslim.data.HistoryRepository(database, appScope)
    }

    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(this, AppDatabase::class.java, "ultraslim.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SettingsRepository(this)
    }

    val conversionRepository: ConversionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConversionRepository(database.taskDao(), appScope)
    }

    override fun onCreate() {
        super.onCreate()
        app.ultraslim.convert.ImageWork.initialize(filesDir)
        createNotificationChannel()
        appScope.launch {
            settingsRepository.theme.collect { initialTheme.value = it }
        }
        appScope.launch {
            // A process death while the system dialog was open must not re-prompt indefinitely.
            database.replacementDao().interruptRequests()
            replacementRecovery.complete(Unit)
            conversionRepository.awaitReady()
            // Promote legacy cache outputs before any cleanup; never delete a referenced result.
            // 单次全表查询：提升后的新路径直接进入 referenced，避免第二次 all()。
            val allRows = database.historyDao().allPaths().mapNotNull { entity ->
                if (!entity.outputUri.startsWith("content://")) {
                    val old = File(entity.outputUri)
                    if (old.isFile && old.canonicalPath.startsWith(cacheDir.canonicalPath + "/")) {
                        val target = File(filesDir, "outputs/${entity.id}_${old.name}").apply { parentFile?.mkdirs() }
                        if (old.renameTo(target)) {
                            if (database.historyDao().updateOutputPath(entity.id, entity.outputUri, target.path) == 1) {
                                return@mapNotNull entity.copy(outputUri = target.path)
                            }
                            // 发布或删除抢先更新了记录，清理使用当前路径，不保护孤立副本。
                            return@mapNotNull database.historyDao().byId(entity.id)?.let {
                                app.ultraslim.data.HistoryPaths(it.id, it.inputUri, it.outputUri)
                            }
                        }
                    }
                }
                entity
            }
            val referenced = buildSet {
                allRows.forEach {
                    add(it.inputUri); add(it.outputUri)
                    if (!it.outputUri.startsWith("content://")) {
                        add(it.outputUri + ".export.json")
                        add(it.outputUri + ".export.json.bak")
                        add(it.outputUri + ".export.json.new")
                    }
                }
                conversionRepository.queue.value.forEach { add(it.uri); it.outputUri?.let(::add) }
            }
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val recentTempCutoff = System.currentTimeMillis() - 60L * 60 * 1000
            listOf("inputs", "outputs").forEach { folder ->
                File(filesDir, folder).listFiles()?.forEach { file ->
                    if (file.name.endsWith(".motion.tmp") && file.lastModified() < recentTempCutoff) {
                        file.delete()
                    } else if (file.lastModified() < cutoff && file.path !in referenced) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@UltraSlimApp, "$packageName.files", file).toString()
                        if (uri !in referenced) file.delete()
                    }
                }
            }
            app.ultraslim.convert.InputMetadataStore.cleanUnreferenced(this@UltraSlimApp, referenced, cutoff)
            InputStore.referenceLock.withLock { InputStore.pruneAppGrants(this@UltraSlimApp) }
            app.ultraslim.ui.components.PreviewCache.cleanStartup(this@UltraSlimApp)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Bitmap 在 native heap 上，不被系统即时回收；进程被判定为可杀之前清掉预览缓存，
        // 让后台进程有机会存活，磁盘缓存保留。
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            app.ultraslim.ui.components.PreviewCache.trimMemory()
            runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ConversionWorker.CHANNEL_ID,
            getString(R.string.channel_conversion_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_conversion_desc)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
