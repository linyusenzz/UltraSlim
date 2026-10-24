package app.ultraslim.data

import androidx.room.Index
import androidx.room.Embedded
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ultraslim.convert.QueueItem
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["taskId"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["status"]),
        // 列表与待发布分页共用 saveState / createdAt 索引。
        Index(value = ["saveState", "createdAt"]),
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val inputUri: String,
    /** MediaStore content uri when saved to the album, otherwise a durable private file path. */
    val outputUri: String,
    val inputBytes: Long,
    val outputBytes: Long,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val hadGainMap: Boolean,
    /** Output format label, e.g. "AVIF" / "HEIC". */
    val format: String,
    /** Input container label, e.g. "JPEG" / "HEIC". */
    val inputFormat: String,
    val durationMs: Double,
    val maxContentBoost: Float,
    val createdAt: Long,
    val status: String,
    val taskId: String? = null,
    val batchId: String = "",
    val saveState: String = "SAVED",
    val settingsSummary: String = "",
    val hasExif: Boolean = false,

) {
    companion object {
        const val STATUS_DONE = "DONE"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

@Dao
interface HistoryDao {

    fun flowPage(limit: Int, filter: String): Flow<List<HistoryEntity>> =
        if (filter.isEmpty()) flowPageAll(limit) else flowPageFiltered(limit, filter)

    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit")
    fun flowPageAll(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE saveState = :filter ORDER BY createdAt DESC LIMIT :limit")
    fun flowPageFiltered(limit: Int, filter: String): Flow<List<HistoryEntity>>

    // Only batch settings are needed to decide whether automatic publication is pending.
    // Observe batches too, so cancellation wakes both publication and replacement review.
    @Query("SELECT DISTINCT h.batchId, b.settings FROM history h JOIN batches b ON b.id = h.batchId " +
        "WHERE h.saveState = 'PENDING' AND b.cancelled = 0 ORDER BY h.batchId")
    fun pendingPublicationBatches(): Flow<List<PendingPublicationBatch>>

    // Keyset pagination: rows disappearing after save cannot shift an OFFSET past unsaved rows.
    @Query("SELECT * FROM history WHERE saveState = 'PENDING' AND batchId IN (:batchIds) " +
        "AND createdAt >= :afterCreatedAt AND (createdAt > :afterCreatedAt OR id > :afterId) " +
        "ORDER BY createdAt, id LIMIT :limit")
    suspend fun pendingPublicationPage(batchIds: List<String>, afterCreatedAt: Long,
        afterId: Long, limit: Int): List<HistoryEntity>

    /** 与 [flowPage] 同筛选的计数，供"加载更多"页脚比较。 */
    fun flowCount(filter: String): Flow<Int> =
        if (filter.isEmpty()) flowCountAll() else flowCountFiltered(filter)

    @Query("SELECT COUNT(*) FROM history")
    fun flowCountAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM history WHERE saveState = :filter")
    fun flowCountFiltered(filter: String): Flow<Int>

    @Query("SELECT * FROM history WHERE taskId = :taskId LIMIT 1")
    suspend fun byTask(taskId: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntity?

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntity>

    /** Startup cleanup only needs references, not every image's metadata. */
    @Query("SELECT id, inputUri, outputUri FROM history")
    suspend fun allPaths(): List<HistoryPaths>

    /** A concurrent publication must not be overwritten by legacy cache promotion. */
    @Query("UPDATE history SET outputUri = :outputUri WHERE id = :id AND outputUri = :expectedOutputUri")
    suspend fun updateOutputPath(id: Long, expectedOutputUri: String, outputUri: String): Int

    @Query("SELECT * FROM history WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt DESC")
    suspend fun inDateRange(start: Long, end: Long): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE id = :id")
    fun flowById(id: Long): Flow<HistoryEntity?>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM history WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteByIds(ids: List<Long>) { ids.chunked(500).forEach { deleteByIdsChunk(it) } }

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query(HISTORY_STATS_SQL)
    fun flowStats(): Flow<HistoryStats>

    @Query(HISTORY_STATS_SQL)
    suspend fun stats(): HistoryStats

    /** 批量追加替换结果摘要，替代逐条 byId+update（AutoReplacement.finish）。 */
    @Query("UPDATE history SET settingsSummary = (CASE WHEN instr(settingsSummary, ' · 替换结果：') > 0 THEN substr(settingsSummary, 1, instr(settingsSummary, ' · 替换结果：') - 1) ELSE settingsSummary END) || ' · 替换结果：' || :message WHERE id IN (:ids)")
    suspend fun noteReplacementChunk(ids: List<Long>, message: String)

    @Transaction
    suspend fun noteReplacement(ids: List<Long>, message: String) {
        ids.chunked(500).forEach { noteReplacementChunk(it, message) }
    }
}

data class HistoryPaths(val id: Long, val inputUri: String, val outputUri: String)

data class PendingPublicationBatch(val batchId: String, val settings: String)

private const val HISTORY_STATS_SQL = "SELECT COUNT(*) as countDone, COALESCE(SUM(inputBytes), 0) as totalInputBytes, COALESCE(SUM(outputBytes), 0) as totalOutputBytes, COALESCE(SUM(CASE WHEN hadGainMap THEN 1 ELSE 0 END), 0) as gainmapCount FROM history WHERE status = 'DONE'"

data class HistoryStats(
    val countDone: Int = 0,
    val totalInputBytes: Long = 0L,
    val totalOutputBytes: Long = 0L,
    val gainmapCount: Int = 0,
) {
    val totalSavedBytes: Long get() = totalInputBytes - totalOutputBytes
    val savedPercent: Float get() = if (totalInputBytes > 0L) (totalSavedBytes.toFloat() / totalInputBytes.toFloat()).coerceIn(-1f, 1f) else 0f
    val avgSavedBytes: Long get() = if (countDone > 0) (totalSavedBytes / countDone) else 0L
    val equivalentPhotosSaved: Int get() = if (countDone > 0 && totalInputBytes > 0L) {
        val avgInput = totalInputBytes / countDone
        if (avgInput > 0) (totalSavedBytes / avgInput).toInt().coerceAtLeast(0) else 0
    } else 0
}

@Entity(tableName = "batches")
data class BatchEntity(@PrimaryKey val id: String, val itemIds: String, val settings: String,
    val cancelled: Boolean = false, val createdAt: Long = System.currentTimeMillis())

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt") suspend fun all(): List<QueueItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<QueueItem>)
    @Query("DELETE FROM tasks WHERE id IN (:ids)") suspend fun removeIds(ids: List<String>)
    @Transaction suspend fun applyChanges(changed: List<QueueItem>, removed: List<String>) {
        removed.chunked(500).forEach { removeIds(it) }
        if (changed.isNotEmpty()) put(changed)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBatch(batch: BatchEntity)
    @Query("SELECT * FROM batches ORDER BY createdAt DESC LIMIT 1") suspend fun latestBatch(): BatchEntity?
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): BatchEntity?
    @Query("UPDATE batches SET cancelled = 1 WHERE id = :id") suspend fun cancel(id: String)
}

@Database(entities = [HistoryEntity::class, QueueItem::class, BatchEntity::class, ReplacementEntity::class], version = 7, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun taskDao(): TaskDao
    abstract fun replacementDao(): ReplacementDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS replacements (historyId INTEGER NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, sourceUri TEXT NOT NULL, outputUri TEXT NOT NULL, sourceHash TEXT NOT NULL, outputHash TEXT NOT NULL, state TEXT NOT NULL, note TEXT NOT NULL)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_saveState_createdAt ON history(saveState, createdAt)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room 对非空内嵌字段直接读取 SQLite 值，不会用 Kotlin 构造器默认值兜底 NULL。
                db.execSQL("""
                    UPDATE tasks SET
                        result_hasGainMap = COALESCE(result_hasGainMap,
                            CASE WHEN result_errorCode = 0 THEN COALESCE(result_inputHadGainMap, 0) ELSE 0 END),
                        result_hasExif = COALESCE(result_hasExif,
                            (SELECT hasExif FROM history WHERE history.id = tasks.historyId), 0),
                        result_maxContentBoost = COALESCE(result_maxContentBoost,
                            (SELECT maxContentBoost FROM history WHERE history.id = tasks.historyId), 1.0)
                    WHERE result_errorCode IS NOT NULL
                      AND (result_hasGainMap IS NULL OR result_hasExif IS NULL OR result_maxContentBoost IS NULL)
                """.trimIndent())
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN taskId TEXT")
                db.execSQL("ALTER TABLE history ADD COLUMN batchId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN saveState TEXT NOT NULL DEFAULT 'SAVED'")
                db.execSQL("ALTER TABLE history ADD COLUMN settingsSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE history ADD COLUMN hasExif INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE history SET saveState = 'PENDING' WHERE outputUri NOT LIKE 'content://%'")
                db.execSQL("CREATE UNIQUE INDEX index_history_taskId ON history(taskId)")
                db.execSQL(TASK_TABLE_SQL)
                db.execSQL("CREATE TABLE IF NOT EXISTS batches (id TEXT NOT NULL PRIMARY KEY, itemIds TEXT NOT NULL, settings TEXT NOT NULL, cancelled INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_createdAt ON history(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_status ON history(status)")
            }
        }
        // TranscodeResult 增加输出元数据字段（probe 收敛），tasks 的 result_ 列随之扩展。
        // 旧行新增列只能是可空且无 DEFAULT；MIGRATION_6_7 再显式回填，不能依赖 Kotlin 默认值读取 NULL。
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_hasGainMap INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_hasExif INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN result_maxContentBoost REAL")
            }
        }
    }
}

private const val TASK_TABLE_SQL = "CREATE TABLE IF NOT EXISTS tasks (id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, displayName TEXT NOT NULL, sizeBytes INTEGER NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL, outputUri TEXT, error TEXT, historyId INTEGER, createdAt INTEGER NOT NULL, batchId TEXT NOT NULL, probe_errorCode INTEGER, probe_error TEXT, probe_hasGainMap INTEGER, probe_container INTEGER, probe_width INTEGER, probe_height INTEGER, probe_gainmapWidth INTEGER, probe_gainmapHeight INTEGER, probe_hasExif INTEGER, probe_maxContentBoost REAL, probe_hdrCapacityMax REAL, result_errorCode INTEGER, result_error TEXT, result_inputHadGainMap INTEGER, result_width INTEGER, result_height INTEGER, result_gainmapWidth INTEGER, result_gainmapHeight INTEGER, result_inputBytes INTEGER, result_outputBytes INTEGER, result_durationMs REAL)"
