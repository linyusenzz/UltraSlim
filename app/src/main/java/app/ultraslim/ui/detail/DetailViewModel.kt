package app.ultraslim.ui.detail

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.AppleLiveExporter
import app.ultraslim.convert.InputStore
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent

    data object Deleted : DetailEvent
}

data class DetailUiState(
    val entity: HistoryEntity? = null,
    val saving: Boolean = false,
    val loading: Boolean = true,
    val originalTrashed: Boolean = false,
    val canTrashOriginal: Boolean = false,
)

internal data class OriginalAccess(val trashed: Boolean, val canTrash: Boolean)

class DetailViewModel internal constructor(
    private val app: UltraSlimApp,
    private val historyId: Long,
    private val queryOriginal: (Uri) -> OriginalAccess = { uri ->
        val trashed = MediaStoreTrashHelper.isOriginalTrashed(app, uri)
        val accessible = MediaStoreTrashHelper.isOriginalAccessible(app, uri)
        val resolvable = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, uri) != null
        OriginalAccess(trashed, MediaStoreTrashHelper.isTrashSupported && resolvable && accessible && !trashed)
    },
) : ViewModel() {

    private val dao = app.database.historyDao()

    private val saving = MutableStateFlow(false)
    private data class OriginalStatus(val inputUri: String, val access: OriginalAccess)
    private val originalStatus = MutableStateFlow<OriginalStatus?>(null)

    // 单一 Room 订阅：uiState 与回收站状态共用，且只有 inputUri 变化才重跑
    // MediaStore 检查（历史行的其他字段更新不再触发 provider 调用）。
    private val entityFlow = dao.flowById(historyId)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val uiState: StateFlow<DetailUiState> = combine(
        entityFlow,
        saving,
        originalStatus,
    ) { entity, isSaving, status ->
        // A delayed provider response belongs only to the URI it checked. A newly
        // linked photo stays unavailable for trash until its own check completes.
        val access = status?.takeIf { it.inputUri == entity?.inputUri }?.access
        DetailUiState(
            entity = entity,
            saving = isSaving,
            loading = false,
            originalTrashed = access?.trashed == true,
            canTrashOriginal = access?.let { it.canTrash && !it.trashed } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    init {
        viewModelScope.launch {
            entityFlow
                .distinctUntilChangedBy { it?.inputUri }
                .collectLatest { entity ->
                    originalStatus.value = null
                    if (entity != null) {
                        val access = withContext(Dispatchers.IO) { queryOriginal(Uri.parse(entity.inputUri)) }
                        // Publish after withContext: cancellation rejects a late response
                        // from a blocking provider when a different URI has arrived.
                        originalStatus.value = OriginalStatus(entity.inputUri, access)
                    }
                }
        }
    }

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    /** Copies a cache-file output into the album and updates the record. */
    fun saveToAlbum() {
        val entity = uiState.value.entity ?: return
        if (saving.value) { _events.tryEmit(DetailEvent.Message("正在处理中，请稍候")); return }
        if (entity.outputUri.startsWith("content://")) return
        viewModelScope.launch {
            saving.value = true
            try {
                val saved = OutputStore.save(app, entity)
                _events.emit(DetailEvent.Message(if (saved.saveState == "SAVED") "已保存到输出目录" else
                    OutputStore.failureMessage(saved) ?: "保存失败，成品仍保留；请检查目录授权后重试或分享"))
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("保存失败：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    fun exportAppleLiveTo(uri: Uri) {
        val entity = uiState.value.entity ?: return
        if (saving.value) { _events.tryEmit(DetailEvent.Message("正在处理中，请稍候")); return }
        saving.value = true
        viewModelScope.launch {
            try {
                AppleLiveExporter.export(app, entity, uri)
                _events.emit(DetailEvent.Message("已导出。请在 Mac 上用 UltraSlim Live 打开 ZIP 并导入「照片」"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                throw e
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                _events.emit(DetailEvent.Message("Apple Live Photo 导出失败：${e.message}"))
            } finally { saving.value = false }
        }
    }

    fun exportTo(uri: Uri) {
        val entity = uiState.value.entity ?: return
        if (saving.value) { _events.tryEmit(DetailEvent.Message("正在处理中，请稍候")); return }
        viewModelScope.launch {
            saving.value = true
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(OutputStore.uri(app, entity.outputUri))!!.use { input ->
                        app.contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
                    }
                }
                _events.emit(DetailEvent.Message("文件已导出"))
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("导出失败：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    fun relinkSource(uri: Uri) {
        val row = uiState.value.entity ?: return
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                val durable = withContext(Dispatchers.IO) { InputStore.persist(app, uri, java.util.UUID.randomUUID().toString()) }
                dao.update(row.copy(inputUri = durable.toString()))
                // entityFlow performs the one provider check on IO for the new URI.
                _events.emit(DetailEvent.Message("原图已重新关联"))
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("无法关联原图：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    suspend fun requestTrashOriginal(): IntentSender? {
        val entity = uiState.value.entity ?: return null
        val uri = Uri.parse(entity.inputUri)
        // MediaStore 查询 + createTrashRequest 是多次 provider round-trip，不能在主线程跑。
        val request = withContext(Dispatchers.IO) { MediaStoreTrashHelper.prepareTrashRequest(app, listOf(uri)) }
        request.message?.let { _events.tryEmit(DetailEvent.Message(it)) }
        return request.sender
    }

    fun onOriginalTrashed() {
        uiState.value.entity?.inputUri?.let {
            originalStatus.value = OriginalStatus(it, OriginalAccess(trashed = true, canTrash = false))
        }
        _events.tryEmit(DetailEvent.Message("原图已移入系统相册回收站（恢复期限以系统回收站为准）"))
    }

    fun deleteRecord() {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                OutputStore.delete(app, historyId)
                _events.emit(DetailEvent.Deleted)
            } catch (e: OutputStore.ConversionInProgress) {
                _events.emit(DetailEvent.Message(e.message.orEmpty()))
            } finally {
                saving.value = false
            }
        }
    }
}
