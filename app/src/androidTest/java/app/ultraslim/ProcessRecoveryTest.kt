package app.ultraslim

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.ReplacementEntity
import app.ultraslim.data.TaskDao
import app.ultraslim.data.encode
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two externally orchestrated stages. Never runs during an ordinary connectedAndroidTest.
 * Exercises Room transaction rollback and queue restart, not MediaStore publication boundaries.
 * All suspension/kill coordination is in the test APK; the production app has no test switch.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    @Test fun seedAndWaitForKill() = runBlocking<Unit> {
        val (context, token) = environment()
        val marker = File(context.filesDir, MARKER)
        marker.delete()
        context.deleteDatabase(DATABASE)
        val db = open(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val dao = object : TaskDao by db.taskDao() {
            @Volatile var block = false
            override suspend fun applyChanges(changed: List<QueueItem>, removed: List<String>) {
                if (!block) db.taskDao().applyChanges(changed, removed)
                else db.withTransaction {
                    db.taskDao().applyChanges(changed, removed)
                    started.complete(Unit)
                    // The harness kills this process with real writes still inside the transaction.
                    awaitCancellation()
                }
            }
        }
        try {
            db.taskDao().putBatch(batch())
            db.historyDao().insert(history())
            db.replacementDao().insert(replacement())
            val repo = ConversionRepository(dao, scope)
            repo.awaitReady()
            repo.addAll(listOf(
                item("active", 1), item("probing", 2), item("retained", 3),
                item("done", 4).copy(status = QueueStatus.DONE, progress = 100,
                    outputUri = history().outputUri, historyId = HISTORY_ID),
            ))
            repo.claim("active", BATCH)
            repo.update("active") { it.copy(progress = 35) }
            repo.beginProbing(listOf("probing"))
            repo.flush()
            dao.block = true
            synchronized(repo) {
                repo.update("active") { it.copy(progress = 99) }
                repo.remove("retained")
                repo.addAll(listOf(item("uncommitted", 5)))
            }
            val pendingFlush = async { repo.flush() }
            withTimeout(30_000) { started.await() }
            assertFalse("An uncommitted transaction must not satisfy flush", pendingFlush.isCompleted)
            FileOutputStream(marker).use {
                it.write(JSONObject().put("token", token).put("pid", Process.myPid()).toString().toByteArray())
                it.fd.sync()
            }
            withTimeout(60_000) { awaitCancellation() }
        } finally {
            // This only runs on harness/test failure. A force-stop bypasses it.
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }

    @Test fun verifyAfterKill() = runBlocking {
        val (context, token) = environment()
        val marker = File(context.filesDir, MARKER)
        val checkpoint = JSONObject(marker.readText())
        assertEquals("The seed belongs to this harness run", token, checkpoint.getString("token"))
        assertNotEquals("Verification must use a new app process", checkpoint.getInt("pid"), Process.myPid())
        var db: AppDatabase? = null
        var scope: CoroutineScope? = null
        try {
            val recoveredDb = open(context).also { db = it }
            val beforeRecovery = recoveredDb.taskDao().all().associateBy { it.id }
            assertEquals(setOf("active", "probing", "retained", "done"), beforeRecovery.keys)
            assertEquals(QueueStatus.CONVERTING, beforeRecovery["active"]!!.status)
            assertEquals(35, beforeRecovery["active"]!!.progress)
            assertEquals(QueueStatus.PROBING, beforeRecovery["probing"]!!.status)
            assertEquals(history(), recoveredDb.historyDao().byTask("done"))
            assertEquals(batch(), recoveredDb.taskDao().batch(BATCH))
            assertEquals(replacement(), recoveredDb.replacementDao().byId(HISTORY_ID))
            val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
            val repo = ConversionRepository(recoveredDb.taskDao(), workerScope)
            repo.awaitReady()
            repo.flush()
            assertEquals(QueueStatus.READY, repo.find("active")!!.status)
            assertEquals(QueueStatus.READY, repo.find("probing")!!.status)
            assertEquals(beforeRecovery["done"], repo.find("done"))
            assertEquals(beforeRecovery["retained"], repo.find("retained"))
            val expected = repo.queue.value
            workerScope.coroutineContext[Job]!!.cancelAndJoin()
            scope = null
            recoveredDb.close()
            db = null
            val reopened = open(context).also { db = it }
            assertEquals(expected, reopened.taskDao().all())
            assertEquals(listOf(history()), reopened.historyDao().all())
        } finally {
            scope?.coroutineContext?.get(Job)?.cancelAndJoin()
            db?.close()
            context.deleteDatabase(DATABASE)
            marker.delete()
        }
    }

    private fun environment(): Pair<Context, String> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run with scripts/check_process_recovery.py", args.getString("processRecovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.ultraslim.verifyqa") { "Use the isolated .verifyqa package" }
        val token = args.getString("recoveryToken").orEmpty()
        check(token.matches(Regex("[0-9a-f-]{36}"))) { "Missing harness token" }
        return context to token
    }

    private fun open(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE).build()
    private fun item(id: String, createdAt: Long) = QueueItem(id, "content://process-fixture/$id", "$id.jpg", 1024,
        createdAt = createdAt, batchId = BATCH)
    private fun batch() = BatchEntity(BATCH, "[\"active\",\"probing\",\"retained\",\"done\"]",
        ConversionDefaults(autoSave = false).encode(), createdAt = 1)
    private fun history() = HistoryEntity(HISTORY_ID, "done.heic", "content://process-fixture/done",
        "/fixture/private-output.heic", 1024, 512, 64, 48, 16, 12, true, "HEIC", "JPEG", 12.5,
        4f, 1, "DONE", taskId = "done", batchId = BATCH, saveState = "PENDING", hasExif = true)
    private fun replacement() = ReplacementEntity(HISTORY_ID, BATCH, history().inputUri, history().outputUri,
        "source-hash", "output-hash", "PREPARED", "Awaiting publication")

    companion object {
        private const val DATABASE = "process-recovery-fixture.db"
        private const val MARKER = "process-recovery-fixture.ready"
        private const val BATCH = "process-fixture-batch"
        private const val HISTORY_ID = 91L
    }
}
