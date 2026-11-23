package app.ultraslim

import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.TaskDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ConversionRepositoryTest {
    @Test(timeout = 10_000)
    fun importsDeduplicateExistingAndIncomingUrisWithoutReordering() = runBlocking {
        val fixture = Fixture(listOf(item("existing")))
        try {
            val incoming = (0 until 2000).flatMap { index ->
                listOf(item("$index"), item("duplicate-$index").copy(uri = "content://$index"))
            }
            fixture.repo.addAll(listOf(item("duplicate-existing").copy(uri = "content://existing")) + incoming)
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(listOf("existing") + (0 until 2000).map { "$it" }, fixture.repo.queue.value.map { it.id })
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
            assertEquals(1, fixture.dao.writes.size)
            assertEquals(2000, fixture.dao.writes.single().first.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun noOpMutationsDoNotWakeWriterOrReplaceSnapshot() = runBlocking {
        val fixture = Fixture(listOf(item("failed").copy(status = QueueStatus.FAILED)))
        try {
            val repo = fixture.repo
            val original = repo.queue.value
            repo.addAll(emptyList())
            repo.addAll(listOf(item("duplicate").copy(uri = "content://failed")))
            repo.update("missing") { error("Missing item must not be transformed") }
            repo.update("failed") { it.copy() }
            repo.updateByUri("content://missing") { error("Missing URI must not be transformed") }
            repo.updateByUri("content://failed") { it.copy() }
            repo.updateAll(emptyList()) { error("Empty selection must not be transformed") }
            repo.updateAll(listOf("missing", "failed", "failed")) { it.copy() }
            repo.removeIds(emptyList())
            repo.removeIds(listOf("missing"))
            assertNull(repo.remove("missing"))
            repo.clearFinished()
            repo.clearPending()
            repo.flush()
            assertSame(original, repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertTrue(fixture.dao.writes.isEmpty())

            repo.clearAll()
            fixture.dispatcher.drain()
            val empty = repo.queue.value
            repo.clearAll()
            repo.clearFinished()
            repo.clearPending()
            repo.flush()
            assertSame(empty, repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertEquals(1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun batchCleanupTransformsEachSelectedItemOnceAndPreservesCompletedItems() = runBlocking {
        val fixture = Fixture((0 until 2000).map { index ->
            item("$index").copy(status = if (index % 2 == 0) QueueStatus.DONE else QueueStatus.READY)
        })
        try {
            val ids = (0 until 2000).map { "$it" }
            var transformed = 0
            fixture.repo.updateAll(ids + ids + "missing") {
                transformed++
                if (it.status == QueueStatus.DONE) it else it.copy(status = QueueStatus.FAILED, error = "interrupted")
            }
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(2000, transformed)
            assertEquals(ids, fixture.repo.queue.value.map { it.id })
            assertEquals(1, fixture.dao.writes.size)
            assertEquals(1000, fixture.dao.writes.single().first.size)
            assertTrue(fixture.dao.rows.filter { it.status == QueueStatus.DONE }.all { it.error == null })
            assertTrue(fixture.dao.rows.filter { it.status == QueueStatus.FAILED }.all { it.error == "interrupted" })
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun importProbesPublishOneStartAndOneResultSnapshotForTheWholeBatch() = runBlocking {
        val fixture = Fixture((0 until 4096).map { item("$it") })
        val observed = mutableListOf<List<QueueItem>>()
        val observer = launch(Dispatchers.Unconfined) { fixture.repo.queue.collect { observed += it } }
        try {
            val ids = (1024 until 1056).map { "$it" }
            val claimed = fixture.repo.beginProbing(ids + ids + "missing")
            assertEquals(ids, claimed.map { it.id })
            assertTrue(claimed.all { it.status == QueueStatus.PROBING })
            fixture.dispatcher.drain()
            assertEquals(1, fixture.dao.writes.size)
            assertEquals(32, fixture.dao.writes.single().first.size)
            assertEquals("No partially probing snapshots", 2, observed.size)

            val results = ids.associateWith<String, ProbeResult?> { probe() }.toMutableMap()
            results[ids[0]] = null
            results[ids[1]] = probe().copy(errorCode = 7, error = "truncated input")
            results[ids[2]] = probe().copy(errorCode = 7, error = "")
            fixture.repo.finishProbing(results)
            fixture.dispatcher.drain()
            fixture.repo.flush()
            assertEquals(2, fixture.dao.writes.size)
            assertEquals(32, fixture.dao.writes.last().first.size)
            assertEquals("All per-file results must be published together", 3, observed.size)
            assertEquals("无法读取此文件", fixture.repo.find(ids[0])?.error)
            assertEquals("truncated input", fixture.repo.find(ids[1])?.error)
            assertEquals("无法解析图片", fixture.repo.find(ids[2])?.error)
            assertTrue(ids.drop(3).all { fixture.repo.find(it)?.status == QueueStatus.READY })
            assertEquals(4096 - 32, fixture.repo.queue.value.count { it.status == QueueStatus.PENDING })
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
        } finally { observer.cancel(); fixture.close() }
    }

    @Test(timeout = 10_000)
    fun lateProbeBatchCannotOverwriteWorkerClaimsOrRestoreRemovedItems() = runBlocking {
        val fixture = Fixture(listOf("active", "removed", "ready", "unreported", "finished").map(::item))
        try {
            fixture.repo.update("finished") { it.copy(status = QueueStatus.DONE) }
            val started = fixture.repo.beginProbing(listOf("active", "removed", "ready", "unreported", "finished"))
            assertEquals(listOf("active", "removed", "ready", "unreported"), started.map { it.id })
            assertNotNull(fixture.repo.claim("active", "worker-batch"))
            assertNotNull(fixture.repo.remove("removed"))
            fixture.repo.finishProbing(listOf("active", "removed", "ready", "finished", "missing").associateWith { probe() })
            assertEquals(QueueStatus.CONVERTING, fixture.repo.find("active")?.status)
            assertEquals("worker-batch", fixture.repo.find("active")?.batchId)
            assertNull(fixture.repo.find("active")?.probe)
            assertNull(fixture.repo.find("removed"))
            assertEquals(QueueStatus.READY, fixture.repo.find("ready")?.status)
            assertEquals(probe(), fixture.repo.find("ready")?.probe)
            assertEquals(QueueStatus.PROBING, fixture.repo.find("unreported")?.status)
            assertEquals(QueueStatus.DONE, fixture.repo.find("finished")?.status)
            fixture.dispatcher.drain()
            val original = fixture.repo.queue.value
            assertTrue(fixture.repo.beginProbing(listOf("active", "removed", "ready", "finished")).isEmpty())
            fixture.repo.finishProbing(mapOf("active" to probe(), "removed" to probe(), "ready" to probe()))
            fixture.repo.flush()
            assertSame(original, fixture.repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun removingConvertingItemsIsANoOp() = runBlocking {
        val fixture = Fixture(listOf(item("active")))
        try {
            assertNotNull(fixture.repo.claim("active", "batch"))
            fixture.dispatcher.drain()
            val active = fixture.repo.queue.value
            fixture.repo.removeIds(listOf("active"))
            fixture.repo.clearAll()
            fixture.repo.clearPending()
            fixture.repo.clearFinished()
            assertNull(fixture.repo.remove("active"))
            fixture.repo.flush()
            assertSame(active, fixture.repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertEquals(1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun revertedChangesCompleteFlushWithoutEmptyTransaction() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        try {
            fixture.repo.update("a") { it.copy(status = QueueStatus.READY) }
            fixture.repo.update("a") { it.copy(status = QueueStatus.PENDING) }
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(flush.isCompleted)
            fixture.dispatcher.drain()
            flush.await()
            assertTrue(fixture.dao.writes.isEmpty())
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun flushWaitsForItsRevisionWhenChangesArriveDuringWrite() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        val firstWrite = CompletableDeferred<Unit>()
        val secondWrite = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = { number -> if (number == 1) firstWrite.await() else secondWrite.await() }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            fixture.dispatcher.drain() // First snapshot is now in flight.
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.repo.update("a") { it.copy(progress = 20) }
            val secondFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(firstFlush.isCompleted)
            assertFalse(secondFlush.isCompleted)
            firstWrite.complete(Unit)
            fixture.dispatcher.drain()
            firstFlush.await()
            assertFalse(secondFlush.isCompleted)
            assertEquals(10, fixture.dao.rows.single().progress)
            secondWrite.complete(Unit)
            fixture.dispatcher.drain()
            secondFlush.await()
            assertEquals(20, fixture.dao.rows.single().progress)
            assertEquals(2, fixture.dao.writes.size)
            fixture.repo.flush()
            assertEquals(0, fixture.dispatcher.pending)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun failedWriteDoesNotAdvanceBarrierAndFlushCanRetry() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        fixture.dao.beforeWrite = { error("disk unavailable") }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.repo.flush() }
            }
            fixture.dispatcher.drain()
            assertEquals("disk unavailable", firstFlush.await().exceptionOrNull()?.message)
            assertEquals(0, fixture.dao.rows.single().progress)
            val failedAttempts = fixture.dao.writes.size
            fixture.dao.beforeWrite = {}
            val retry = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(retry.isCompleted)
            fixture.dispatcher.drain()
            retry.await()
            assertEquals(10, fixture.dao.rows.single().progress)
            assertEquals(failedAttempts + 1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun startupRecoveryPersistsOnlyInterruptedRows() = runBlocking {
        val initial = listOf(item("ready").copy(status = QueueStatus.READY),
            item("probing").copy(status = QueueStatus.PROBING),
            item("active").copy(status = QueueStatus.CONVERTING))
        val fixture = Fixture(initial)
        try {
            fixture.repo.awaitReady()
            fixture.repo.flush()
            assertEquals(initial.map { it.id }, fixture.repo.queue.value.map { it.id })
            assertTrue(fixture.repo.queue.value.all { it.status == QueueStatus.READY })
            assertEquals(listOf("probing", "active"), fixture.dao.writes.single().first.map { it.id })
            assertSame(initial.first(), fixture.repo.find("ready"))
            assertTrue(fixture.repo.find("active")!!.error!!.contains("中断"))
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun indexesFollowUndoUriChangesAndBulkRemovalWithoutMutatingOldSnapshots() = runBlocking {
        val fixture = Fixture(listOf("a", "b", "c", "d").map(::item))
        try {
            val repo = fixture.repo
            val initial = repo.queue.value
            val removed = repo.remove("b")!!
            val afterRemove = repo.queue.value
            repo.updateByUri("content://c") { it.copy(uri = "content://renamed", progress = 10) }
            repo.updateByUri("content://c") { error("Old URI index must be removed") }
            repo.updateByUri("content://renamed") { it.copy(progress = 20) }
            assertNotNull(repo.restore(removed))
            assertEquals(listOf("a", "b", "c", "d"), repo.queue.value.map { it.id })
            assertEquals(20, repo.find("c")!!.progress)
            assertEquals(listOf("a", "b", "c", "d"), initial.map { it.id })
            assertTrue(initial.all { it.progress == 0 })
            assertEquals(listOf("a", "c", "d"), afterRemove.map { it.id })
            assertEquals("content://c", afterRemove[1].uri)

            // Neither duplicate IDs nor duplicate URIs may invalidate an index.
            repo.addAll(listOf(item("b").copy(uri = "content://different"),
                item("other").copy(uri = "content://renamed"), item("e"), item("e").copy(uri = "content://second")))
            assertEquals(listOf("a", "b", "c", "d", "e"), repo.queue.value.map { it.id })
            repo.removeIds(listOf("a", "d", "a"))
            val visited = mutableListOf<String>()
            repo.updateAll(listOf("e", "b", "c", "c")) { visited += it.id; it.copy(progress = 30) }
            assertEquals(listOf("b", "c", "e"), visited)
            assertTrue(listOf("b", "c", "e").all { repo.find(it)?.progress == 30 })
            assertNull(repo.find("a"))
            assertNull(repo.find("d"))
            fixture.dispatcher.drain()
            repo.flush()
            assertEquals(repo.queue.value, fixture.dao.rows)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun inFlightDeleteDoesNotConsumeARestoredRowsNewRevision() = runBlocking {
        val fixture = Fixture(listOf(item("a"), item("b")))
        val deletion = CompletableDeferred<Unit>()
        val restoration = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = { if (it == 1) deletion.await() else restoration.await() }
        try {
            val removed = fixture.repo.remove("a")!!
            fixture.dispatcher.drain()
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.repo.restore(removed)
            val secondFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            deletion.complete(Unit)
            fixture.dispatcher.drain()
            firstFlush.await()
            assertFalse(secondFlush.isCompleted)
            assertEquals(listOf("b"), fixture.dao.rows.map { it.id })
            assertEquals(listOf("a", "b"), fixture.repo.queue.value.map { it.id })
            restoration.complete(Unit)
            fixture.dispatcher.drain()
            secondFlush.await()
            assertEquals(setOf("a", "b"), fixture.dao.rows.map { it.id }.toSet())
            assertEquals(listOf("a"), fixture.dao.writes[0].second)
            assertEquals(listOf("a"), fixture.dao.writes[1].first.map { it.id })
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun inFlightInsertThenDeleteMustRemoveTheCommittedRow() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        val insertion = CompletableDeferred<Unit>()
        val deletion = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = { if (it == 1) insertion.await() else deletion.await() }
        try {
            fixture.repo.addAll(listOf(item("b")))
            fixture.dispatcher.drain()
            fixture.repo.remove("b")
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            insertion.complete(Unit)
            fixture.dispatcher.drain()
            assertFalse(flush.isCompleted)
            assertEquals(setOf("a", "b"), fixture.dao.rows.map { it.id }.toSet())
            deletion.complete(Unit)
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(listOf("a"), fixture.dao.rows.map { it.id })
            assertEquals(listOf("b"), fixture.dao.writes[1].second)

            fixture.repo.addAll(listOf(item("never-committed")))
            fixture.repo.remove("never-committed")
            fixture.dispatcher.drain()
            fixture.repo.flush()
            assertEquals("Insert and delete before capture need no transaction", 2, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun revertingDuringAWriteStillPersistsTheReversion() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        val write = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = { if (it == 1) write.await() }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            fixture.dispatcher.drain()
            fixture.repo.update("a") { it.copy(progress = 0) }
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            write.complete(Unit)
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(listOf(10, 0), fixture.dao.writes.map { it.first.single().progress })
            assertEquals(0, fixture.dao.rows.single().progress)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun failedWriteRetainsNewerChangesDeletesAndRestoresForRetry() = runBlocking {
        val fixture = Fixture(listOf(item("a"), item("remove"), item("unchanged")))
        val failedWrite = CompletableDeferred<Unit>()
        val retryWrite = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = {
            if (it == 1) { failedWrite.await(); error("disk unavailable") } else retryWrite.await()
        }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            fixture.dispatcher.drain()
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.repo.flush() } }
            val removed = fixture.repo.remove("a")!!
            fixture.repo.restore(removed)
            fixture.repo.update("a") { it.copy(progress = 20) }
            fixture.repo.remove("remove")
            fixture.repo.addAll(listOf(item("new")))
            val retryFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            failedWrite.complete(Unit)
            fixture.dispatcher.drain()
            assertEquals("disk unavailable", firstFlush.await().exceptionOrNull()?.message)
            assertFalse(retryFlush.isCompleted)
            assertEquals(0, fixture.dao.rows.first().progress)
            assertEquals(setOf("a", "new"), fixture.dao.writes[1].first.map { it.id }.toSet())
            assertEquals(listOf("remove"), fixture.dao.writes[1].second)
            retryWrite.complete(Unit)
            fixture.dispatcher.drain()
            retryFlush.await()
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
            assertEquals(20, fixture.repo.find("a")!!.progress)
            fixture.repo.flush()
            assertEquals(0, fixture.dispatcher.pending)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun cancellingTheWriterFailsOutstandingFlushInsteadOfAcknowledgingIt() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        fixture.dao.beforeWrite = { CompletableDeferred<Unit>().await() }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            fixture.dispatcher.drain()
            val flush = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.repo.flush() } }
            fixture.close()
            assertTrue(flush.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(0, fixture.dao.rows.single().progress)
            assertTrue(runCatching { fixture.repo.flush() }.isFailure)
        } finally { fixture.close() }
    }

    private fun item(id: String) = QueueItem(id, "content://$id", "$id.jpg", 100, createdAt = 1)

    private fun probe() = ProbeResult(0, "", false, 0, 8, 8, 0, 0, false, 1f, 1f)

    private class Fixture(initial: List<QueueItem>) {
        val dispatcher = QueuedDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val dao = RecordingTaskDao(initial)
        val repo = ConversionRepository(dao, scope)
        init { dispatcher.drain() }
        fun close() { scope.cancel(); dispatcher.drain() }
    }

    /** Explicitly drain repository work without sleeps or an additional coroutine-test dependency. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class RecordingTaskDao(initial: List<QueueItem>) : TaskDao {
        var rows = initial
        val writes = mutableListOf<Pair<List<QueueItem>, List<String>>>()
        var beforeWrite: suspend (Int) -> Unit = {}
        override suspend fun all() = rows
        override suspend fun put(items: List<QueueItem>) {
            rows = (rows.associateBy { it.id } + items.associateBy { it.id }).values.toList()
        }
        override suspend fun removeIds(ids: List<String>) {
            val removed = ids.toSet()
            rows = rows.filterNot { it.id in removed }
        }
        override suspend fun applyChanges(changed: List<QueueItem>, removed: List<String>) {
            writes += changed to removed
            beforeWrite(writes.size)
            removeIds(removed)
            put(changed)
        }
        override suspend fun putBatch(batch: BatchEntity): Unit = error("Unused in queue tests")
        override suspend fun latestBatch(): BatchEntity? = error("Unused in queue tests")
        override suspend fun batch(id: String): BatchEntity? = error("Unused in queue tests")
        override suspend fun isBatchCancelled(id: String): Boolean? = error("Unused in queue tests")
        override suspend fun batchSettings(id: String): String? = error("Unused in queue tests")
        override suspend fun cancel(id: String): Unit = error("Unused in queue tests")
        override suspend fun pruneUnreferencedBatches() = Unit
        override suspend fun restorePublishedTaskOutputs() = Unit
    }
}
