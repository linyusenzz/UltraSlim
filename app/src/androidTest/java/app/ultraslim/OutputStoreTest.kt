package app.ultraslim

import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.HistoryEntity
import app.ultraslim.util.outputName
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import java.io.File
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class OutputStoreTest {
    @Test fun changedPublishedCopyIsNotAdoptedAndNumberedCopyRecoversAfterInterruption() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val published = mutableSetOf<Uri>()
        val task = java.util.UUID.randomUUID().toString()
        val file = File(app.filesDir, "outputs/$task.jpg").apply { parentFile!!.mkdirs() }
        val expected = instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { it.readBytes() }
        file.writeBytes(expected)
        var row = HistoryEntity(displayName=outputName("IMG_%_regression.jpg", "jpg", task, 0),
            inputUri="content://test", outputUri=file.path, inputBytes=expected.size.toLong(),
            outputBytes=expected.size.toLong(), width=320, height=240, gainmapWidth=0, gainmapHeight=0,
            hadGainMap=false, format="JPEG", inputFormat="JPEG", durationMs=1.0,
            maxContentBoost=1f, createdAt=System.currentTimeMillis(), status="DONE", taskId=task, saveState="PENDING")
        row = row.copy(id=app.database.historyDao().insert(row))
        try {
            val first = OutputStore.save(app, row)
            assertEquals("SAVED", first.saveState)
            val changed = Uri.parse(first.outputUri).also { published += it }
            // The gallery copy was changed after publishing, before the history checkpoint.
            val other = byteArrayOf(1, 2, 3, 4)
            app.contentResolver.openOutputStream(changed, "wt")!!.use { it.write(other) }
            file.writeBytes(expected)
            app.database.historyDao().update(row)
            val retry = OutputStore.save(app, row)
            assertEquals("SAVED", retry.saveState)
            val correct = Uri.parse(retry.outputUri).also { published += it }
            assertNotEquals(changed, correct)
            assertArrayEquals(expected, app.contentResolver.openInputStream(correct)!!.use { it.readBytes() })
            assertArrayEquals(other, app.contentResolver.openInputStream(changed)!!.use { it.readBytes() })
            assertFalse(file.exists())
            // A second interruption must recover that numbered, byte-identical copy.
            file.writeBytes(expected)
            app.database.historyDao().update(row)
            assertEquals(correct.toString(), OutputStore.save(app, row).outputUri)
            assertFalse(file.exists())
        } finally {
            published.forEach { app.contentResolver.delete(it, null, null) }
            OutputStore.delete(app, row.id)
            file.delete()
        }
    }

    @Test fun failedSaveCanRetryWithoutEncodingAndIsIdempotent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val task = java.util.UUID.randomUUID().toString()
        val file = File(app.filesDir,"outputs/$task.jpg")
        var row = HistoryEntity(displayName=outputName("IMG_regression.jpg", "jpg", task, 0),inputUri="content://test",outputUri=file.path,
            inputBytes=100,outputBytes=100,width=64,height=64,gainmapWidth=0,gainmapHeight=0,hadGainMap=false,
            format="JPEG",inputFormat="JPEG",durationMs=1.0,maxContentBoost=1f,createdAt=System.currentTimeMillis(),
            status="DONE",taskId=task,saveState="PENDING")
        row = row.copy(id=app.database.historyDao().insert(row))
        app.conversionRepository.awaitReady()
        app.conversionRepository.addAll(listOf(QueueItem(id = task, uri = row.inputUri,
            displayName = row.displayName, sizeBytes = 100, status = QueueStatus.DONE,
            outputUri = file.path, historyId = row.id)))
        app.conversionRepository.flush()
        var savedUri: Uri? = null
        try {
            val failed = OutputStore.save(app,row)
            assertEquals("FAILED",failed.saveState)
            assertEquals(file.path, app.conversionRepository.find(task)?.outputUri)
            assertNotNull(app.conversionRepository.find(task)?.error)
            file.parentFile!!.mkdirs()
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            val shared = OutputStore.uri(app, file.path, row.displayName)
            app.contentResolver.query(shared, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(row.displayName, it.getString(0))
            }
            val saved = OutputStore.save(app,failed)
            assertEquals("SAVED",saved.saveState); assertFalse(file.exists())
            savedUri = Uri.parse(saved.outputUri)
            assertEquals(saved.outputUri, app.conversionRepository.find(task)?.outputUri)
            assertNull(app.conversionRepository.find(task)?.error)
            assertEquals("image/jpeg",app.contentResolver.getType(savedUri))
            assertEquals(saved.outputUri,OutputStore.save(app,failed).outputUri)
            app.contentResolver.query(savedUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(row.displayName, it.getString(0))
            }
            // Recover a publication completed just before the DB checkpoint was written.
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            app.database.historyDao().update(failed)
            assertEquals(saved.outputUri, OutputStore.save(app,failed).outputUri)
        } finally {
            savedUri?.let { app.contentResolver.delete(it,null,null) }
            OutputStore.delete(app,row.id); file.delete()
        }
    }
}
