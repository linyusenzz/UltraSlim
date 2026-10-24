package app.ultraslim

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import app.ultraslim.data.*
import app.ultraslim.convert.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun avifCompatibilitySnapshotsKeepTheirMeaning() {
        val defaults = ConversionDefaults()
        assertTrue(defaults.avifGainmapCompatibility)
        for (enabled in listOf(false, true)) {
            val options = defaults.copy(avifGainmapCompatibility = enabled)
            assertEquals(options, decodeDefaults(options.encode()))
            assertEquals(enabled, options.toConversionSettings().avifGainmapCompatibility)
            assertEquals(enabled, options.summary().contains("HDR 相册兼容"))
        }
        val oldSnapshot = org.json.JSONObject(defaults.encode()).apply { remove("avifGainmapCompatibility") }
        val restored = decodeDefaults(oldSnapshot.toString())
        assertFalse(restored.avifGainmapCompatibility)
        assertTrue(restored.summary().contains("Gain Map 原样保留"))
    }

    @Test fun heicCompatibilityIsIndependentAndOldSnapshotsStayStrict() {
        val defaults = ConversionDefaults(format = app.ultraslim.model.OutputFormat.HEIC)
        assertTrue(defaults.heicGainmapCompatibility)
        for (heic in listOf(false, true)) for (avif in listOf(false, true)) {
            val options = defaults.copy(heicGainmapCompatibility = heic, avifGainmapCompatibility = avif)
            assertEquals(options, decodeDefaults(options.encode()))
            assertEquals(heic, options.toConversionSettings().heicGainmapCompatibility)
            assertEquals(heic, options.summary().contains("HDR 相册兼容"))
        }
        val old = org.json.JSONObject(defaults.encode()).apply { remove("heicGainmapCompatibility") }
        assertFalse(decodeDefaults(old.toString()).heicGainmapCompatibility)
    }

    @Test fun migrateV1PreservesPendingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, 0, null)
        old.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, displayName TEXT NOT NULL, inputUri TEXT NOT NULL, outputUri TEXT NOT NULL, inputBytes INTEGER NOT NULL, outputBytes INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, gainmapWidth INTEGER NOT NULL, gainmapHeight INTEGER NOT NULL, hadGainMap INTEGER NOT NULL, format TEXT NOT NULL, inputFormat TEXT NOT NULL, durationMs REAL NOT NULL, maxContentBoost REAL NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL)")
        old.execSQL("INSERT INTO history VALUES (7, 'photo.heic', 'content://input', '/cache/output.heic', 100, 80, 32, 32, 8, 8, 1, 'HEIC', 'JPEG', 12.5, 4.0, 1, 'DONE')")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).build()
        try { val row = db.historyDao().byId(7)!!; assertEquals("PENDING",row.saveState); assertEquals(80L,row.outputBytes); assertTrue(db.taskDao().all().isEmpty()) }
        finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migrateLegacyResultMetadataFromV3AndV6() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf(3, 6)) {
            val name = "migration-result-v$version.db"
            val old = migrationHelper.createDatabase(name, version)
            insertLegacyResult(old)
            old.close()
            val migrations: Array<Migration> = if (version == 3) arrayOf(
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
            ) else arrayOf(AppDatabase.MIGRATION_6_7)
            migrationHelper.runMigrationsAndValidate(name, 7, true, *migrations).close()
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val items = db.taskDao().all()
                val result = items.single { it.id == "result" }.result!!
                assertTrue(result.hasGainMap)
                assertTrue(result.hasExif)
                assertEquals(4.0f, result.maxContentBoost, 0.0f)
                assertNull(items.single { it.id == "empty" }.result)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun insertLegacyResult(db: SupportSQLiteDatabase) {
        db.execSQL("""
            INSERT INTO history (id, displayName, inputUri, outputUri, inputBytes, outputBytes,
                width, height, gainmapWidth, gainmapHeight, hadGainMap, format, inputFormat,
                durationMs, maxContentBoost, createdAt, status, taskId, batchId, saveState,
                settingsSummary, hasExif)
            VALUES (7, 'photo.heic', 'content://input', 'content://output', 100, 80,
                32, 32, 8, 8, 1, 'HEIC', 'JPEG', 12.5, 4.0, 1, 'DONE', 'result', 'batch',
                'SAVED', '', 1)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO tasks (id, uri, displayName, sizeBytes, status, progress, outputUri,
                error, historyId, createdAt, batchId, result_errorCode, result_error,
                result_inputHadGainMap, result_width, result_height, result_gainmapWidth,
                result_gainmapHeight, result_inputBytes, result_outputBytes, result_durationMs)
            VALUES ('result', 'content://input', 'photo.heic', 100, 'DONE', 100,
                'content://output', NULL, 7, 1, 'batch', 0, '', 1, 32, 32, 8, 8, 100, 80, 12.5)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO tasks (id, uri, displayName, sizeBytes, status, progress, createdAt, batchId)
            VALUES ('empty', 'content://empty', 'empty.jpg', 100, 'READY', 0, 1, 'batch')
        """.trimIndent())
    }

    @Test fun conflatedWritesStillHonorFlushBarriers() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ConversionRepository(db.taskDao(), scope)
            repo.awaitReady()
            repo.addAll((0 until 1000).map { QueueItem("$it", "content://$it", "$it.jpg", 100) })
            coroutineScope {
                repeat(4) { worker -> launch(Dispatchers.Default) {
                    repeat(250) { index ->
                        repo.update("${worker * 250 + index}") { it.copy(status = QueueStatus.READY, progress = 10) }
                    }
                    repo.flush()
                } }
            }
            repo.removeIds((0 until 500).map { "$it" })
            repo.update("999") { it.copy(status = QueueStatus.DONE, progress = 100) }
            repo.flush()
            val stored = db.taskDao().all()
            assertEquals(500, stored.size)
            assertTrue(stored.all { it.progress == if (it.id == "999") 100 else 10 })
            assertEquals(QueueStatus.DONE, stored.single { it.id == "999" }.status)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun durableQueueAndCancellation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val repo=ConversionRepository(db.taskDao(),scope);repo.awaitReady()
            repo.addAll(listOf(QueueItem("a","content://a","a.jpg",100), QueueItem("b","content://b","b.jpg",200,status=QueueStatus.FAILED),QueueItem("c","content://c","c.jpg",200,status=QueueStatus.DONE)))
            repo.flush(); repo.clearFinished(); repo.flush()
            assertEquals(setOf("a","b"),db.taskDao().all().map {it.id}.toSet())
            repo.update("a"){it.copy(status=QueueStatus.CONVERTING)};repo.remove("a");repo.flush()
            assertEquals(2,db.taskDao().all().size)
            val restored=ConversionRepository(db.taskDao(),scope);restored.awaitReady()
            assertEquals(QueueStatus.READY,restored.find("a")?.status)
            val options=ConversionDefaults(onlySmaller=true,keepLocation=false)
            db.taskDao().putBatch(BatchEntity("batch","[\"a\"]",options.encode()))
            db.taskDao().cancel("batch")
            assertTrue(db.taskDao().batch("batch")!!.cancelled)
            assertEquals(options,decodeDefaults(db.taskDao().batch("batch")!!.settings))
        } finally {scope.cancel();db.close()}
    }
}
