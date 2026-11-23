package app.ultraslim.ui.history

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.AutoReplacement
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.SelectedOutput
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.HistoryPage
import app.ultraslim.data.HistoryStats
import app.ultraslim.data.decodeDefaults
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryEntity> = emptyList(),
    val totalSavedBytes: Long = 0,
    val convertedCount: Int = 0,
    val busy: BusyState? = null,
    val stats: HistoryStats = HistoryStats(),
    val hasMore: Boolean = false,
    val olderWindow: Boolean = false,
    val atWindowLimit: Boolean = false,
    val loadingPage: Boolean = false,
    val windowKey: String = "",
) {
    val busyNow: Boolean get() = busy != null
}

/** 长时批量操作的进度；null = 空闲。 */
data class BusyState(val label: String, val done: Int, val total: Int)

data class HistorySelectionState(
    val ids: Set<Long> = emptySet(),
    val items: List<HistoryEntity> = emptyList(),
)

private data class HistoryWindowResult(
    val window: HistoryWindow = HistoryWindow(),
    val page: HistoryPage = HistoryPage(emptyList(), 0, false),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val app: UltraSlimApp) : ViewModel() {
    private val dao = app.database.historyDao()
    private val navigation = HistoryWindowNavigation()
    private val window = MutableStateFlow(navigation.current)

    /** UI 侧 rememberSaveable 恢复后回填，保证筛选状态在页面往返后不丢。 */
    fun setFilter(saveState: String) {
        window.value = navigation.filter(saveState)
    }

    // Conflate BEFORE SQL, including a trailing write. The first query is immediate, while
    // continuous writes cannot restart a slow query or materialize a window for every image.
    private fun historyChanges(): Flow<Unit> = app.database.invalidationTracker
        .createFlow("history", emitInitialState = true).conflate().transform {
            emit(Unit)
            delay(300L)
        }

    private val pages = window.flatMapLatest { request ->
        historyChanges().map {
            HistoryWindowResult(request, dao.window(request.filter,
                request.before?.createdAt ?: Long.MAX_VALUE,
                request.before?.id ?: Long.MAX_VALUE, request.limit))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryWindowResult())

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<HistorySelectionState> = combine(selectedIds, pages) { ids, loaded ->
        if (ids.isEmpty()) HistorySelectionState()
        else {
            val inWindow = loaded.page.items.associateBy { it.id }
            val missingIds = ids.filter { !inWindow.containsKey(it) }
            val fetched = if (missingIds.isEmpty()) emptyMap()
                else missingIds.chunked(500).flatMap { dao.byIds(it) }.associateBy { it.id }
            HistorySelectionState(ids, ids.mapNotNull { inWindow[it] ?: fetched[it] })
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistorySelectionState())

    fun setSelectedIds(ids: Set<Long>) { selectedIds.value = ids.toSet() }

    private val busyState = MutableStateFlow<BusyState?>(null)
    private var busyJob: Job? = null
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private val _deleteCandidates = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val deleteCandidates = _deleteCandidates.asStateFlow()
    private var deleteRequest = 0L

    fun cancelBusy() {
        busyJob?.cancel()
    }

    fun cancelDelete() {
        deleteRequest++
        _deleteCandidates.value = emptyList()
    }

    fun requestDelete(ids: Set<Long>) {
        if (busyState.value != null) return
        val request = ++deleteRequest
        viewModelScope.launch {
            val rows = ids.chunked(500).flatMap { dao.byIds(it) }
            if (request == deleteRequest && busyState.value == null) _deleteCandidates.value = rows
        }
    }

    fun requestDeleteDate(date: java.time.LocalDate, filter: String) {
        if (busyState.value != null) return
        val request = ++deleteRequest
        viewModelScope.launch {
            val range = historyDateRange(date)
            val rows = dao.inDateRange(range.first, range.last + 1)
                .filter { it.matchesHistoryFilter(filter) }
            if (request == deleteRequest && busyState.value == null) _deleteCandidates.value = rows
        }
    }

    fun deleteConfirmed() {
        if (busyState.value != null) return
        val records = _deleteCandidates.value
        deleteRequest++
        _deleteCandidates.value = emptyList()
        busyJob = viewModelScope.launch {
            try {
                val active = app.isConversionRunning()
                if (active) {
                    _events.emit("请等待当前转换结束后删除记录")
                    return@launch
                }
                busyState.value = BusyState("删除中", 0, records.size)
                var processed = 0
                for (chunk in records.chunked(50)) {
                    coroutineContext.ensureActive()
                    OutputStore.deleteBatch(app, chunk.map { it.id })
                    processed += chunk.size
                    busyState.value = BusyState("删除中", processed, records.size)
                }
                _events.emit("已删除 ${records.size} 条记录，已保存到相册的图片不受影响")
            } catch (e: OutputStore.ConversionInProgress) {
                _events.emit(e.message.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit("已停止删除；未删除的记录保持不变")
            } finally {
                busyState.value = null
                busyJob = null
            }
        }
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        pages,
        window,
        busyState,
        app.historyRepository.stats,
    ) { loaded, requested, working, stats ->
        HistoryUiState(
            items = loaded.page.items,
            totalSavedBytes = stats.totalSavedBytes,
            convertedCount = loaded.page.totalCount,
            busy = working,
            stats = stats,
            hasMore = loaded.page.hasMore,
            olderWindow = loaded.window.before != null,
            atWindowLimit = loaded.window.limit >= HistoryWindow.MAX_ITEMS,
            loadingPage = loaded.window != requested,
            windowKey = "${loaded.window.filter}:${loaded.window.before}",
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    fun loadMore() {
        if (busyState.value != null || pages.value.window != window.value) return
        val last = pages.value.page.items.lastOrNull()?.let { HistoryCursor(it.createdAt, it.id) }
        window.value = navigation.more(last, pages.value.page.hasMore)
    }

    fun showNewer() {
        if (busyState.value == null && pages.value.window == window.value) window.value = navigation.newer()
    }

    fun showLatest() {
        if (busyState.value == null && pages.value.window == window.value) window.value = navigation.latest()
    }

    fun saveSelected(ids: Set<Long>) {
        if (busyState.value != null) return
        busyJob = viewModelScope.launch {
            var done = 0
            var failed = 0
            val savedByTask = LinkedHashMap<String, HistoryEntity>()
            try {
                busyState.value = BusyState("保存中", 0, ids.size)
                val targets = ids.chunked(500).flatMap { dao.byIds(it) }.filter { it.saveState != "SAVED" }
                busyState.value = BusyState("保存中", 0, targets.size)
                val directoryCache = SelectedOutput.DirectoryCache()
                val optionsCache = HashMap<String, ConversionDefaults?>()
                var processed = 0
                for (entity in targets) {
                    coroutineContext.ensureActive()
                    try {
                        val options = optionsCache.getOrPut(entity.batchId) {
                            app.database.taskDao().batchSettings(entity.batchId)
                                ?.let { runCatching { decodeDefaults(it) }.getOrNull() }
                        }
                        val saved = OutputStore.saveInternal(
                            app = app,
                            record = entity,
                            finalizePublication = false,
                            knownOptions = options,
                            directoryCache = directoryCache,
                        )
                        if (saved.saveState == "SAVED") {
                            done++
                            if (options?.autoReplaceOriginals != false) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    AutoReplacement.saved(app, saved, outputVerified = true)
                                }
                            }
                        } else {
                            failed++
                        }
                        saved.taskId?.let { savedByTask[it] = saved }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                    busyState.value = BusyState("保存中", ++processed, targets.size)
                }
                _events.emit("已保存 $done 张，失败 $failed 张；失败成品仍在应用内")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit("已停止保存；已完成的保存保持不变")
            } finally {
                if (savedByTask.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        app.conversionRepository.updateAll(savedByTask.keys) { item ->
                            val saved = savedByTask[item.id] ?: return@updateAll item
                            item.copy(
                                outputUri = saved.outputUri,
                                error = when (saved.saveState) {
                                    "SAVED" -> null
                                    "FAILED" -> "保存失败，可在详情重试"
                                    else -> "转换完成，待保存；可在详情重试"
                                },
                            )
                        }
                        app.conversionRepository.flush()
                    }
                }
                busyState.value = null
                busyJob = null
            }
        }
    }

    /** 原图 → MediaStore URI 的解析缓存；IO 线程单遍解析并复用已解析 Uri 对象，选择时只查表。 */
    private val trashableCache = MutableStateFlow<Map<Long, Uri>>(emptyMap())
    val trashableOriginals: StateFlow<Map<Long, Uri>> = trashableCache.asStateFlow()
    private val originalUriCache = OriginalUriCache()
    private val parsedUriCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    private var lastPublishedStrings: Map<Long, String> = emptyMap()

    fun refreshOriginals() {
        originalUriCache.invalidate()
        lastPublishedStrings = emptyMap()
        ensureTrashableResolved((uiState.value.items + selection.value.items).distinctBy { it.id })
    }

    private fun publishTrashableCache() {
        val snap = originalUriCache.snapshot()
        if (snap == lastPublishedStrings) return
        lastPublishedStrings = snap
        trashableCache.value = snap.mapValues { (_, raw) ->
            parsedUriCache.computeIfAbsent(raw, Uri::parse)
        }
    }

    /** 补齐缺缓存条目的解析；页面加载或筛选变化时调用，重复调用只解析增量。 */
    fun ensureTrashableResolved(items: List<HistoryEntity>) {
        val sources = items.map { OriginalUriCache.Source(it.id, it.inputUri) }
        originalUriCache.retain(sources)
        val missing = originalUriCache.plan(sources)
        // Relinked records must stop offering the old original immediately, before the new query finishes.
        publishTrashableCache()
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val active = MediaStoreTrashHelper.activeOriginals(
                        app,
                        missing.map { parsedUriCache.computeIfAbsent(it.uri, Uri::parse) },
                    )
                    missing.mapNotNull { source ->
                        val parsed = parsedUriCache.computeIfAbsent(source.uri, Uri::parse)
                        active[parsed]?.let { canonical ->
                            val canonicalStr = canonical.toString()
                            parsedUriCache.putIfAbsent(canonicalStr, canonical)
                            source to canonicalStr
                        }
                    }.toMap()
                }
                originalUriCache.complete(missing, resolved)
                publishTrashableCache()
            } finally {
                originalUriCache.abandon(missing)
            }
        }
    }

    suspend fun requestTrashSelected(ids: Set<Long>): IntentSender? {
        // The cache only controls presentation. Destructive requests use the current persisted link.
        val rows = ids.chunked(500).flatMap { dao.byIds(it) }
        val uris = rows.map { Uri.parse(it.inputUri) }.distinct()
        if (uris.isEmpty()) {
            _events.tryEmit("所选照片的原图不在系统相册管理范围内")
            return null
        }
        val request = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaStoreTrashHelper.prepareTrashRequest(app, uris) }
        request.message?.let { _events.tryEmit(it) }
        pendingTrashUris = request.uris.toSet()
        return request.sender
    }

    private var pendingTrashUris = emptySet<Uri>()
    fun onSelectedOriginalsTrashed(count: Int) {
        // Returning from the system dialog may have started a pre-result lookup. Reject it
        // even if invalidate() had already removed the old positive result from the cache.
        refreshOriginals()
        _events.tryEmit("已将 ${pendingTrashUris.size} 张原图移至系统相册回收站（恢复期限以系统回收站为准）")
    }

    fun clearAll() {
        if (busyState.value != null) return
        busyJob = viewModelScope.launch {
            try {
                val active = app.isConversionRunning()
                if (active) {
                    _events.emit("请等待当前转换结束后清空历史")
                    return@launch
                }
                busyState.value = BusyState("清空中", 0, 0)
                val ids = dao.allIds()
                var processed = 0
                for (chunk in ids.chunked(50)) {
                    coroutineContext.ensureActive()
                    OutputStore.deleteBatch(app, chunk)
                    processed += chunk.size
                    busyState.value = BusyState("清空中", processed, ids.size)
                }
            } catch (e: OutputStore.ConversionInProgress) {
                _events.emit(e.message.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit("已停止清空；未删除的记录保持不变")
            } finally {
                busyState.value = null
                busyJob = null
            }
        }
    }
}
