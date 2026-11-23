package app.ultraslim

import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real runner/Room/native regressions for state that can change after import or encoding. */
class ConversionFreshnessTest {
    private val app get() = ApplicationProvider.getApplicationContext<UltraSlimApp>()

    private fun jpeg(file: File, width: Int, height: Int) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff487abc.toInt())
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private suspend fun withBatch(options: ConversionDefaults, action: suspend (String, File, Uri) -> Unit) {
        app.conversionMutex.withLock {
            val id = UUID.randomUUID().toString()
            val input = File(app.filesDir, "inputs/freshness-$id.jpg")
            jpeg(input, 64, 48)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
            val repo = app.conversionRepository
            repo.awaitReady()
            try {
                repo.addAll(listOf(QueueItem(id, uri.toString(), input.name, input.length(),
                    status = QueueStatus.READY, batchId = id)))
                app.database.taskDao().putBatch(BatchEntity(id, "[\"$id\"]", options.encode()))
                action(id, input, uri)
            } finally {
                app.database.taskDao().cancel(id)
                app.database.historyDao().byTask(id)?.let { row ->
                    if (row.outputUri.startsWith("content://")) app.contentResolver.delete(Uri.parse(row.outputUri), null, null)
                    else File(row.outputUri).delete()
                    app.database.replacementDao().deleteAll(listOf(row.id))
                    app.database.historyDao().deleteById(row.id)
                }
                repo.remove(id); repo.flush()
                input.delete()
            }
        }
    }

    @Test fun changedSourceRefreshesPersistedDimensionsAndExifBeforeConversion() = runBlocking {
        withBatch(ConversionDefaults(format = OutputFormat.JPEG, autoSave = false, onlySmaller = false)) { id, input, uri ->
            ExifInterface(input.path).apply {
                setAttribute(ExifInterface.TAG_MODEL, "Before edit")
                saveAttributes()
            }
            val imported = app.contentResolver.openFileDescriptor(uri, "r")!!.use { UltraHdrBridge.nativeProbe(it.fd) }
            assertTrue(imported.isSuccess && imported.hasExif)
            app.conversionRepository.update(id) { it.copy(probe = imported) }
            app.conversionRepository.flush()
            // Same URI, but the editor replaced its bytes, dimensions and EXIF after import.
            jpeg(input, 96, 80)
            val current = app.contentResolver.openFileDescriptor(uri, "r")!!.use { UltraHdrBridge.nativeProbe(it.fd) }
            assertTrue(current.isSuccess)
            assertFalse(current.hasExif)
            assertEquals(ConversionRunner.Outcome.COMPLETE, ConversionRunner(app, thermalStatus = { 0 }) {}.run(id))
            val item = app.conversionRepository.find(id)!!
            assertEquals(item.error, QueueStatus.DONE, item.status)
            assertEquals(current, item.probe)
            assertEquals(current, app.database.taskDao().all().first { it.id == id }.probe)
            val result = app.database.historyDao().byTask(id)!!
            assertEquals(96, result.width)
            assertEquals(80, result.height)
            assertFalse(result.hasExif)
            assertEquals("PENDING", result.saveState)
        }
    }

    @Test fun changedMotionPhotoUsesCurrentSizeForOnlySmaller() = runBlocking {
        val options = ConversionDefaults(format = OutputFormat.JPEG, quality = 100,
            allowSdr = true, autoSave = false, onlySmaller = true)
        withBatch(options) { id, input, _ ->
            fun box(type: String, body: ByteArray): ByteArray =
                java.nio.ByteBuffer.allocate(8 + body.size)
                    .putInt(8 + body.size).put(type.toByteArray(Charsets.US_ASCII)).put(body).array()
            val video = box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf()) +
                box("mdat", ByteArray(4096))
            app.conversionRepository.update(id) { it.copy(sizeBytes = 1024L * 1024) }
            input.appendBytes(video)
            val currentSize = input.length()
            assertEquals(ConversionRunner.Outcome.COMPLETE, ConversionRunner(app, thermalStatus = { 0 }) {}.run(id))
            val item = app.conversionRepository.find(id)!!
            assertEquals(item.error, QueueStatus.SKIPPED, item.status)
            val result = requireNotNull(item.result)
            assertEquals(currentSize, result.inputBytes)
            assertTrue(result.outputBytes >= currentSize)
            assertNull(app.database.historyDao().byTask(id))
        }
    }

    @Test fun cancellationDuringReplacementPreparationKeepsPrivateResultPending() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val wasAllowed = app.galleryPublishingAllowed.value
        val wasAutoReplace = app.settingsRepository.defaults.first().autoReplaceOriginals
        try {
            app.galleryPublishingAllowed.value = true
            // The snapshot requested replacement, but the user disabled it before staging.
            app.settingsRepository.setAutoReplaceOriginals(false)
            withBatch(ConversionDefaults(format = OutputFormat.JPEG, autoSave = true,
                autoReplaceOriginals = true, onlySmaller = false)) { id, input, _ ->
                val db = app.database.openHelper.writableDatabase
                val trigger = "cancel_preparing_${id.replace("-", "")}"
                // Cancel synchronously when stage records its outcome, AFTER the early
                // publication decision but BEFORE OutputStore.save. No timing sleeps/hooks.
                db.execSQL("""
                    CREATE TRIGGER $trigger AFTER UPDATE OF settingsSummary ON history
                    WHEN NEW.taskId = '$id'
                    BEGIN UPDATE batches SET cancelled = 1 WHERE id = '$id'; END
                """.trimIndent())
                try {
                    assertEquals(ConversionRunner.Outcome.COMPLETE, ConversionRunner(app, thermalStatus = { 0 }) {}.run(id))
                    assertTrue(app.database.taskDao().batch(id)!!.cancelled)
                    val item = app.conversionRepository.find(id)!!
                    assertEquals(item.error, QueueStatus.DONE, item.status)
                    val result = app.database.historyDao().byTask(id)!!
                    assertEquals("PENDING", result.saveState)
                    assertFalse(result.outputUri.startsWith("content://"))
                    assertTrue(File(result.outputUri).length() > 0)
                    assertTrue(input.isFile)
                } finally { db.execSQL("DROP TRIGGER IF EXISTS $trigger") }
            }
        } finally {
            app.galleryPublishingAllowed.value = wasAllowed
            app.settingsRepository.setAutoReplaceOriginals(wasAutoReplace)
        }
    }
}
