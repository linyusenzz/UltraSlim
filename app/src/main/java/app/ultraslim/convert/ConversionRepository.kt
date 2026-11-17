package app.ultraslim.convert

import android.net.Uri
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.PrimaryKey
import app.ultraslim.data.TaskDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.bridge.TranscodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class QueueStatus { PENDING, PROBING, READY, CONVERTING, DONE, FAILED, SKIPPED }

@Entity(tableName = "tasks")
data class QueueItem(
    @PrimaryKey val id: String,
    /** Source content uri, as string. */
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    @Embedded(prefix = "probe_") val probe: ProbeResult? = null,
    val status: QueueStatus = QueueStatus.PENDING,
    /** Overall queue progress (0–100) while this item is being converted. */
    val progress: Int = 0,
    @Embedded(prefix = "result_") val result: TranscodeResult? = null,
    /** MediaStore content uri, or durable private file path when auto-save is off. */
    val outputUri: String? = null,
    val error: String? = null,
    /** Room history id, set once the conversion succeeded. */
    val historyId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val batchId: String = "",
)

/**
 * Durable conversion queue shared between the UI and
 * [ConversionService]. Also carries URIs arriving via share intents.
 */
class ConversionRepository(private val dao: TaskDao, scope: CoroutineScope) {
    private val ready = CompletableDeferred<Unit>()
    // One wakeup and the latest mutation per ID, regardless of progress-update frequency.
    // A write owns its captured revisions; newer changes survive its completion or failure.
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var persistedRevision = 0L
    private data class Change(val revision: Long, val item: QueueItem?)
    private data class PendingWrite(val revision: Long, val changes: Map<String, Change>)
    private val dirty = LinkedHashMap<String, Change>()
    private val positions = HashMap<String, Int>()
    private val idsByUri = HashMap<String, MutableSet<String>>()
    // Only the writer accesses committed rows. Compare dirty IDs, never the whole queue.
    private val persisted = HashMap<String, QueueItem>()
    private val barriers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
    suspend fun awaitReady() = ready.await()
    suspend fun flush() {
        ready.await()
        val ack = CompletableDeferred<Unit>()
        synchronized(this) {
            if (persistedRevision >= revision) ack.complete(Unit)
            else {
                barriers += revision to ack
                writes.trySend(Unit).getOrThrow()
            }
        }
        ack.await()
    }

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _incoming = MutableStateFlow<List<Uri>>(emptyList())
    val incoming: StateFlow<List<Uri>> = _incoming.asStateFlow()

    init {
        scope.launch {
            try {
                val initial = dao.all()
                persisted.putAll(initial.associateBy { it.id })
                synchronized(this@ConversionRepository) {
                    initial.forEachIndexed { index, item ->
                        positions[item.id] = index
                        idsByUri.getOrPut(item.uri) { linkedSetOf() }.add(item.id)
                    }
                    val recovered = mutableListOf<QueueItem>()
                    val restored = initial.map {
                        when (it.status) {
                            QueueStatus.CONVERTING -> it.copy(status = QueueStatus.READY, error = "上次转换被中断，请继续；已完成图片保留")
                            QueueStatus.PROBING -> it.copy(status = QueueStatus.READY)
                            else -> it
                        }.also { restored -> if (restored != it) recovered += restored }
                    }
                    if (recovered.isNotEmpty()) recordChanges(recovered, emptyList())
                    _queue.value = restored
                    if (recovered.isNotEmpty()) writes.trySend(Unit).getOrThrow()
                }
                ready.complete(Unit)
                for (ignored in writes) {
                    val snapshot = synchronized(this@ConversionRepository) {
                        if (persistedRevision >= revision) null else PendingWrite(revision, dirty.toMap())
                    } ?: continue // A flush wakeup may already be covered by the previous write.
                    val target = snapshot.revision
                    val changed = mutableListOf<QueueItem>()
                    val removed = mutableListOf<String>()
                    snapshot.changes.forEach { (id, change) ->
                        if (change.item != persisted[id]) {
                            if (change.item == null) removed += id else changed += change.item
                        }
                    }
                    var failure: Exception? = null
                    try {
                        // Changes can cancel each other before this snapshot is drained.
                        if (changed.isNotEmpty() || removed.isNotEmpty()) dao.applyChanges(changed, removed)
                        removed.forEach { persisted.remove(it) }
                        changed.forEach { persisted[it.id] = it }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failure = e
                    }
                    synchronized(this@ConversionRepository) {
                        if (failure == null) {
                            persistedRevision = target
                            snapshot.changes.forEach { (id, change) ->
                                if (dirty[id]?.revision == change.revision) dirty.remove(id)
                            }
                        }
                        val completed = barriers.filter { it.first <= target }
                        barriers.removeAll(completed.toSet())
                        completed.forEach { (_, ack) ->
                            if (failure == null) ack.complete(Unit) else ack.completeExceptionally(failure)
                        }
                    }
                }
            } catch (e: Exception) {
                ready.completeExceptionally(e)
                synchronized(this@ConversionRepository) {
                    writes.close(e)
                    barriers.forEach { it.second.completeExceptionally(e) }
                    barriers.clear()
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun submitIncoming(uris: List<Uri>) {
        if (uris.isNotEmpty()) _incoming.update { it + uris }
    }

    fun consumeIncoming() {
        _incoming.value = emptyList()
    }

    /** All arguments are private copies. Published list instances are never mutated again. */
    private fun publish(
        items: List<QueueItem>,
        changed: List<QueueItem> = emptyList(),
        removed: List<String> = emptyList(),
        reindexFrom: Int? = null,
    ) {
        if (changed.isEmpty() && removed.isEmpty()) return
        val previous = _queue.value
        fun unlink(id: String) {
            val old = positions[id]?.let(previous::get) ?: return
            idsByUri[old.uri]?.let { ids -> if (ids.remove(id) && ids.isEmpty()) idsByUri.remove(old.uri) }
        }
        removed.forEach(::unlink)
        changed.forEach { item ->
            val old = positions[item.id]?.let(previous::get)
            if (old?.uri != item.uri) {
                unlink(item.id)
                idsByUri.getOrPut(item.uri) { linkedSetOf() }.add(item.id)
            }
        }
        removed.forEach { positions.remove(it) }
        if (reindexFrom != null) {
            for (index in reindexFrom until items.size) positions[items[index].id] = index
        }
        recordChanges(changed, removed)
        // Complete internal bookkeeping before StateFlow resumes any unconfined collector.
        _queue.value = items
        writes.trySend(Unit).getOrThrow()
    }

    private fun recordChanges(changed: List<QueueItem>, removed: List<String>) {
        revision++
        changed.forEach { dirty[it.id] = Change(revision, it) }
        removed.forEach { dirty[it] = Change(revision, null) }
    }

    /** Copy the list once, but look up and transform only selected rows, in queue order. */
    private fun updateSelected(ids: Collection<String>, transform: (QueueItem) -> QueueItem) {
        val selected = ids.mapNotNull { positions[it] }.distinct().sorted()
        if (selected.isEmpty()) return
        val previous = _queue.value
        val changed = mutableListOf<QueueItem>()
        var next: MutableList<QueueItem>? = null
        selected.forEach { index ->
            val old = previous[index]
            val item = transform(old)
            require(item.id == old.id) { "A queue update cannot change its ID" }
            if (item != old) {
                val copy = next ?: previous.toMutableList().also { next = it }
                copy[index] = item
                changed += item
            }
        }
        next?.let { publish(it, changed) }
    }

    private fun removeSelected(indices: Collection<Int>): List<RemovedItem> {
        if (indices.isEmpty()) return emptyList()
        val previous = _queue.value
        val selected = indices.toSet()
        val removed = selected.sorted().map { RemovedItem(previous[it], it) }
        val next = ArrayList<QueueItem>(previous.size - removed.size)
        previous.forEachIndexed { index, item -> if (index !in selected) next += item }
        publish(next, removed = removed.map { it.item.id }, reindexFrom = removed.first().index)
        return removed
    }

    @Synchronized fun addAll(items: List<QueueItem>) {
        if (items.isEmpty()) return
        val existing = _queue.value
        val uris = HashSet<String>()
        val ids = HashSet<String>()
        val added = items.filter {
            it.uri !in idsByUri && it.id !in positions && it.uri !in uris && it.id !in ids &&
                uris.add(it.uri) && ids.add(it.id)
        }
        if (added.isNotEmpty()) publish(existing + added, added, reindexFrom = existing.size)
    }

    data class RemovedItem(val item: QueueItem, val index: Int)

    @Synchronized fun remove(id: String): RemovedItem? {
        val index = positions[id] ?: return null
        if (_queue.value[index].status == QueueStatus.CONVERTING) return null
        return removeSelected(listOf(index)).single()
    }

    @Synchronized fun restore(removed: RemovedItem): QueueItem? {
        if (removed.item.id in positions || removed.item.uri in idsByUri) return null
        val item = removed.item.let {
            if (it.status == QueueStatus.PROBING) it.copy(status = QueueStatus.PENDING) else it
        }
        val index = removed.index.coerceIn(0, _queue.value.size)
        val next = _queue.value.toMutableList().apply { add(index, item) }
        publish(next, listOf(item), reindexFrom = index)
        return item
    }

    @Synchronized fun removeIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        removeSelected(ids.mapNotNull { positions[it] }.filter { _queue.value[it].status != QueueStatus.CONVERTING })
    }

    @Synchronized fun update(id: String, transform: (QueueItem) -> QueueItem) {
        updateSelected(listOf(id), transform)
    }

    /** One immutable snapshot and one write revision for the selected rows. */
    @Synchronized fun updateAll(ids: Collection<String>, transform: (QueueItem) -> QueueItem) {
        if (ids.isEmpty()) return
        updateSelected(ids, transform)
    }

    /** Claim a bounded import batch in one snapshot; removed/started items need no probe. */
    @Synchronized internal fun beginProbing(ids: Collection<String>): List<QueueItem> {
        if (ids.isEmpty()) return emptyList()
        val probing = mutableListOf<QueueItem>()
        updateSelected(ids) { item ->
            if (item.status == QueueStatus.PENDING) {
                item.copy(status = QueueStatus.PROBING).also { probing += it }
            } else item
        }
        return probing
    }

    /** Publish individual results together without overwriting a worker claim or removed row. */
    @Synchronized internal fun finishProbing(results: Map<String, ProbeResult?>) {
        if (results.isEmpty()) return
        updateSelected(results.keys) { item ->
            if (item.status != QueueStatus.PROBING) item
            else when (val probe = results[item.id]) {
                null -> item.copy(status = QueueStatus.FAILED, error = "无法读取此文件")
                else -> if (probe.isSuccess) item.copy(probe = probe, status = QueueStatus.READY)
                else item.copy(probe = probe, status = QueueStatus.FAILED,
                    error = probe.error.ifBlank { "无法解析图片" })
            }
        }
    }

    @Synchronized fun updateByUri(uri: String, transform: (QueueItem) -> QueueItem) {
        updateSelected(idsByUri[uri].orEmpty(), transform)
    }

    @Synchronized fun claim(id: String, batchId: String): QueueItem? {
        val item = positions[id]?.let { _queue.value[it] } ?: return null
        if (item.status in listOf(QueueStatus.DONE, QueueStatus.SKIPPED, QueueStatus.CONVERTING)) return null
        val claimed = item.copy(status = QueueStatus.CONVERTING, error = null, batchId = batchId)
        updateSelected(listOf(id)) { claimed }
        return claimed
    }

    @Synchronized fun find(id: String): QueueItem? = positions[id]?.let { _queue.value[it] }

    /** Failed and skipped items remain available for inspection and retry. */
    @Synchronized fun clearFinished() {
        removeSelected(_queue.value.indices.filter { _queue.value[it].status == QueueStatus.DONE })
    }

    /** 待处理项（未在转换中的）：PENDING / PROBING / READY。 */
    @Synchronized fun clearPending() {
        removeSelected(_queue.value.indices.filter {
            _queue.value[it].status in listOf(QueueStatus.PENDING, QueueStatus.PROBING, QueueStatus.READY)
        })
    }

    /** 全部清除；正在转换的条目等它落地后再清。 */
    @Synchronized fun clearAll() {
        removeSelected(_queue.value.indices.filter { _queue.value[it].status != QueueStatus.CONVERTING })
    }
}
