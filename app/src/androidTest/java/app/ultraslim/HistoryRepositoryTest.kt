package app.ultraslim

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.HistoryPaths
import app.ultraslim.data.HistoryRepository
import app.ultraslim.data.HistoryStats
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.PendingPublicationBatch
import app.ultraslim.data.encode
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repositoryScope: CoroutineScope
    private val aggregateQueries = AtomicInteger()
    private val pendingQueries = AtomicInteger()

    @Before fun setUp() {
        aggregateQueries.set(0)
        pendingQueries.set(0)
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).setQueryCallback(object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.contains("as countDone") && sqlQuery.contains("SUM(inputBytes)")) {
                    aggregateQueries.incrementAndGet()
                }
                if (sqlQuery.startsWith("SELECT DISTINCT h.batchId")) pendingQueries.incrementAndGet()
            }
        }, Executor { it.run() }).build()
    }

    @After fun tearDown() = runBlocking {
        repositoryScope.coroutineContext[Job]!!.cancelAndJoin()
        database.close()
    }

    @Test fun pageAndCountWrappersKeepAllAndFilteredResultsConsistent() = runBlocking {
        val dao = database.historyDao()
        assertTrue(dao.flowPage(10, "").first().isEmpty())
        assertEquals(0, dao.flowCount("").first())
        dao.insert(row(1, saveState = "SAVED"))
        dao.insert(row(2, saveState = "FAILED"))
        dao.insert(row(3, saveState = "PENDING"))
        dao.insert(row(4, saveState = "SAVED"))
        dao.insert(row(5, saveState = "PENDING"))
        dao.insert(row(6, saveState = ""))

        assertEquals(listOf(6L, 5L, 4L), dao.flowPage(3, "").first().map { it.id })
        assertEquals(6, dao.flowCount("").first())
        assertEquals(listOf(4L, 1L), dao.flowPage(10, "SAVED").first().map { it.id })
        assertEquals(2, dao.flowCount("SAVED").first())
        assertEquals(listOf(5L), dao.flowPage(1, "PENDING").first().map { it.id })
        assertEquals(2, dao.flowCount("PENDING").first())
        assertEquals(listOf(2L), dao.flowPage(10, "FAILED").first().map { it.id })
        assertTrue(dao.flowPage(10, "missing").first().isEmpty())
        assertEquals(0, dao.flowCount("missing").first())
        assertTrue(dao.flowPage(0, "").first().isEmpty())
    }

    @Test fun suspendStatsIncludesOnlyDoneRowsAcrossPublicationStates() = runBlocking {
        val dao = database.historyDao()
        assertEquals(HistoryStats(), dao.stats())
        dao.insert(row(1, saveState = "PENDING", inputBytes = 100, outputBytes = 80, gainmap = true))
        dao.insert(row(2, saveState = "FAILED", inputBytes = 200, outputBytes = 250))
        dao.insert(row(3, status = HistoryEntity.STATUS_SKIPPED, inputBytes = 900, outputBytes = 1, gainmap = true))
        val expected = HistoryStats(countDone = 2, totalInputBytes = 300, totalOutputBytes = 330, gainmapCount = 1)
        assertEquals(expected, dao.stats())
        assertEquals(expected, dao.flowStats().first())
        assertEquals(-30L, dao.stats().totalSavedBytes)
        dao.deleteAll()
        assertEquals(HistoryStats(), dao.stats())
    }

    @Test fun historyWindowsBoundRowsAndKeepSameTimestampCursorsStableAfterWrites() = runBlocking {
        val dao = database.historyDao()
        repeat(1_203) { index -> dao.insert(row(index.toLong() + 1).copy(createdAt = 1234)) }
        val first = dao.window("", Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(500, first.items.size)
        assertEquals((1_203L downTo 704L).toList(), first.items.map { it.id })
        assertTrue(first.hasMore)
        assertEquals(1_203, first.totalCount)
        val boundary = first.items.last()
        // New rows and deletions above the cursor must not shift the next page. Deleting
        // the anchor itself must not make its position unusable either.
        dao.insert(row(1_204).copy(createdAt = 1235))
        dao.deleteByIds(listOf(1_203L, 1_202L, boundary.id))
        dao.update(dao.byId(703)!!.copy(saveState = "FAILED", inputUri = "relinked"))
        val second = dao.window("", boundary.createdAt, boundary.id, 500)
        assertEquals((703L downTo 204L).toList(), second.items.map { it.id })
        assertEquals("relinked", second.items.first().inputUri)
        assertTrue(second.hasMore)
        val last = second.items.last()
        val third = dao.window("", last.createdAt, last.id, 500)
        assertEquals((203L downTo 1L).toList(), third.items.map { it.id })
        assertFalse(third.hasMore)
        assertEquals(1_201, third.totalCount)
        assertEquals(1_200, dao.inDateRange(1234, 1235).size)
    }

    @Test fun filteredHistoryWindowsReflectPublicationAndDeletionWithoutSkippingOlderRows() = runBlocking {
        val dao = database.historyDao()
        repeat(210) { dao.insert(row(it.toLong() + 1, saveState = "PENDING").copy(createdAt = 100)) }
        val first = dao.window("PENDING", Long.MAX_VALUE, Long.MAX_VALUE, 100)
        assertEquals((210L downTo 111L).toList(), first.items.map { it.id })
        val anchor = first.items.last()
        first.items.forEach { dao.update(it.copy(saveState = "SAVED")) }
        dao.deleteById(110)
        val next = dao.window("PENDING", anchor.createdAt, anchor.id, 100)
        assertEquals((109L downTo 10L).toList(), next.items.map { it.id })
        assertEquals(109, next.totalCount)
        assertTrue(next.hasMore)
        assertEquals(100, dao.window("SAVED", Long.MAX_VALUE, Long.MAX_VALUE, 100).items.size)
        assertTrue(dao.window("missing", Long.MAX_VALUE, Long.MAX_VALUE, 100).items.isEmpty())
    }

    @Test fun startupPathsAndConditionalPromotionPreserveConcurrentPublication() = runBlocking {
        val dao = database.historyDao()
        val original = row(1).copy(inputUri = "input", outputUri = "cache/output", settingsSummary = "keep")
        dao.insert(original)
        assertEquals(listOf(HistoryPaths(1, "input", "cache/output")), dao.allPaths())
        assertEquals(1, dao.updateOutputPath(1, "cache/output", "files/output"))
        assertEquals(original.copy(outputUri = "files/output"), dao.byId(1))
        val published = original.copy(outputUri = "content://album/1", settingsSummary = "published")
        dao.update(published)
        assertEquals(0, dao.updateOutputPath(1, "files/output", "stale/output"))
        assertEquals(published, dao.byId(1))
        assertEquals(0, dao.updateOutputPath(999, "missing", "new"))
    }

    @Test fun sharedStatsSamplesBeforeSqlAndRefreshesAfterSubscriberRestart() = runBlocking {
        val dao = database.historyDao()
        dao.insert(row(1))
        val repository = HistoryRepository(database, repositoryScope)
        delay(600)
        assertEquals("No aggregate SQL without a subscriber", 0, aggregateQueries.get())

        val first = Channel<HistoryStats>(Channel.UNLIMITED)
        val second = Channel<HistoryStats>(Channel.UNLIMITED)
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { repository.stats.collect { first.send(it) } }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { repository.stats.collect { second.send(it) } }
        try {
            assertEquals(HistoryStats(), first.receive())
            assertEquals(HistoryStats(), second.receive())
            val initial = HistoryStats(1, 100, 80, 0)
            first.awaitValue(initial)
            second.awaitValue(initial)
            assertEquals("Two subscribers share one initial SQL query", 1, aggregateQueries.get())

            val beforeBurst = aggregateQueries.get()
            val elapsed = measureTimeMillis {
                repeat(16) { index ->
                    dao.insert(row(index.toLong() + 2))
                    delay(25)
                }
                // The trailing write must arrive even after writes stop (no more invalidations).
                val final = HistoryStats(17, 1_700, 1_360, 0)
                first.awaitValue(final)
                second.awaitValue(final)
            }
            val queries = aggregateQueries.get() - beforeBurst
            assertTrue("Aggregate SQL is bounded by sampling ticks: $queries in ${elapsed}ms",
                queries <= elapsed / 500 + 2)

            val beforeMetadata = aggregateQueries.get()
            dao.noteReplacement(listOf(1L), "metadata only")
            withTimeout(5_000) {
                while (aggregateQueries.get() == beforeMetadata) delay(20)
            }
            delay(100)
            assertTrue("Unchanged stats should not emit", first.tryReceive().isFailure)
            assertTrue("Unchanged stats should not emit", second.tryReceive().isFailure)
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
        }

        // WhileSubscribed's grace period permits quick tab switches without restarting SQL.
        delay(5_600)
        val stoppedQueries = aggregateQueries.get()
        dao.insert(row(18))
        delay(650)
        assertEquals("No aggregate SQL once the last subscriber's grace period expires",
            stoppedQueries, aggregateQueries.get())
        withTimeout(5_000) {
            assertEquals(HistoryStats(18, 1_800, 1_440, 0), repository.stats.first { it.countDone == 18 })
        }
    }

    @Test fun pendingPagesDoNotSkipRowsAsPublishedItemsDisappear() = runBlocking {
        val dao = database.historyDao()
        // Manual-only rows sort ahead of automatic rows but must not starve the page.
        repeat(40) { dao.insert(row((it + 1).toLong(), saveState = "PENDING").copy(batchId = "manual", createdAt = 0)) }
        val expected = (101L..175L).toList()
        expected.forEach { dao.insert(row(it, saveState = "PENDING").copy(batchId = "auto", createdAt = it / 3)) }
        var afterTime = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        val visited = mutableListOf<Long>()
        while (true) {
            val page = dao.pendingPublicationPage(listOf("auto"), afterTime, afterId, 32)
            if (page.isEmpty()) break
            assertTrue(page.size <= 32)
            afterTime = page.last().createdAt
            afterId = page.last().id
            visited += page.map { it.id }
            page.forEach { dao.update(it.copy(saveState = "SAVED")) }
        }
        assertEquals(expected, visited)
        assertEquals(40, dao.flowCount("PENDING").first())
    }

    @Test fun publicationSignalIsSharedAndWakesOnSameBatchInsertAndCancellation() = runBlocking {
        val dao = database.historyDao()
        val tasks = database.taskDao()
        tasks.putBatch(BatchEntity("auto", "[]", "{}")) // legacy defaults still auto-save
        tasks.putBatch(BatchEntity("manual", "[]", ConversionDefaults(autoSave = false).encode()))
        tasks.putBatch(BatchEntity("broken", "[]", "not-json"))
        tasks.putBatch(BatchEntity("cancelled", "[]", "{}", cancelled = true))
        listOf("auto", "manual", "broken", "cancelled").forEachIndexed { index, batch ->
            dao.insert(row((index + 1).toLong(), saveState = "PENDING").copy(batchId = batch))
        }
        // Drain seed invalidations before subscribing: otherwise a late seed refresh
        // can look like a second collector query or the same-batch insertion wakeup.
        database.invalidationTracker.refresh("history", "batches")
        val repository = HistoryRepository(database, repositoryScope)
        val first = Channel<List<PendingPublicationBatch>>(Channel.UNLIMITED)
        val second = Channel<List<PendingPublicationBatch>>(Channel.UNLIMITED)
        val one = launch { repository.pendingPublicationBatches.collect { first.send(it) } }
        val two = launch { repository.pendingPublicationBatches.collect { second.send(it) } }
        try {
            withTimeout(5_000) {
                assertEquals(listOf("auto"), first.receive().map { it.batchId })
                assertEquals(listOf("auto"), second.receive().map { it.batchId })
            }
            assertEquals("Both consumers share one query", 1, pendingQueries.get())
            dao.insert(row(10, saveState = "PENDING").copy(batchId = "auto"))
            withTimeout(5_000) {
                // The projection is equal, but this wakeup is required after a page drain.
                assertEquals(listOf("auto"), first.receive().map { it.batchId })
                assertEquals(listOf("auto"), second.receive().map { it.batchId })
            }
            tasks.cancel("auto")
            withTimeout(5_000) {
                while (first.receive().isNotEmpty()) { }
                while (second.receive().isNotEmpty()) { }
            }
        } finally { one.cancelAndJoin(); two.cancelAndJoin() }
    }

    private suspend fun Channel<HistoryStats>.awaitValue(expected: HistoryStats) {
        withTimeout(5_000) {
            while (receive() != expected) { /* Skip intermediate aggregate values. */ }
        }
    }

    private fun row(
        id: Long,
        saveState: String = "SAVED",
        status: String = HistoryEntity.STATUS_DONE,
        inputBytes: Long = 100,
        outputBytes: Long = 80,
        gainmap: Boolean = false,
    ) = HistoryEntity(
        id = id, displayName = "$id.jpg", inputUri = "input/$id", outputUri = "output/$id",
        inputBytes = inputBytes, outputBytes = outputBytes, width = 1, height = 1,
        gainmapWidth = 0, gainmapHeight = 0, hadGainMap = gainmap,
        format = "JPEG", inputFormat = "JPEG", durationMs = 0.0,
        maxContentBoost = 1f, createdAt = id, status = status, saveState = saveState,
    )
}
