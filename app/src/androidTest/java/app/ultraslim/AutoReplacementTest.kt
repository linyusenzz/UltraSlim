package app.ultraslim

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.history.HistoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AutoReplacementTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp

    @Test fun snapshotsDefaultOffAndDisablingSaveDisablesReplacement() = runBlocking {
        assertFalse(ConversionDefaults().autoReplaceOriginals)
        assertFalse(decodeDefaults("{}").autoReplaceOriginals)
        val enabled = ConversionDefaults(autoReplaceOriginals = true)
        assertEquals(enabled, decodeDefaults(enabled.encode()))
        try {
            app.settingsRepository.setAutoReplaceOriginals(true)
            assertTrue(app.settingsRepository.defaults.first().autoSave)
            app.settingsRepository.setAutoSave(false)
            assertFalse(app.settingsRepository.defaults.first().autoReplaceOriginals)
        } finally { app.settingsRepository.setAutoReplaceOriginals(false); app.settingsRepository.setAutoSave(true) }
    }

    @Test fun pickerIdsAreNeverGuessedAndVolumeIsPreserved() {
        assertNull(MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/123")))
        val sd = Uri.parse("content://media/ABCD-1234/images/media/123")
        assertEquals(sd, MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, sd))
        assertNull(MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, Uri.parse("content://media/external/video/media/123")))
    }

    internal suspend fun fixture(action: suspend (HistoryEntity, Uri, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val input = File(app.cacheDir, "$id-source.jpg")
        val output = File(app.filesDir, "outputs/$id.jpg").apply { parentFile!!.mkdirs() }
        val pixels = IntArray(128 * 128) { java.util.Random(it.toLong()).nextInt() or 0xff000000.toInt() }
        val bitmap = Bitmap.createBitmap(pixels, 128, 128, Bitmap.Config.ARGB_8888)
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 30, it) }; bitmap.recycle()
        val source = MediaStoreSaver.save(app, input, "$id-source.jpg", OutputFormat.JPEG)!!
        var history = HistoryEntity(displayName = "$id.jpg", inputUri = source.toString(), outputUri = output.path,
            inputBytes = input.length(), outputBytes = output.length(), width = 128, height = 128, gainmapWidth = 0, gainmapHeight = 0,
            hadGainMap = false, format = "JPEG", inputFormat = "JPEG", durationMs = 1.0, maxContentBoost = 1f,
            createdAt = System.currentTimeMillis(), status = "DONE", taskId = id, batchId = id, saveState = "PENDING")
        history = history.copy(id = app.database.historyDao().insert(history))
        app.database.taskDao().putBatch(BatchEntity(id, "[]", ConversionDefaults(autoReplaceOriginals = true).encode()))
        app.settingsRepository.setAutoReplaceOriginals(true)
        try { action(history, source, output) }
        finally {
            app.database.historyDao().byId(history.id)?.outputUri?.takeIf { it.startsWith("content://") }?.let { app.contentResolver.delete(Uri.parse(it), null, null) }
            app.database.replacementDao().finish(listOf(history.id), "CANCELLED", "测试完成")
            app.database.historyDao().deleteById(history.id)
            if (MediaStoreTrashHelper.originalState(app, source) != MediaStoreTrashHelper.OriginalState.UNAVAILABLE)
                app.contentResolver.delete(source, null, null)
            app.settingsRepository.setAutoReplaceOriginals(false)
            input.delete(); output.delete()
        }
    }

    @Test fun savedCopyIsVerifiedAndAnyLaterChangePreventsReplacement() = runBlocking {
        fixture { history, source, _ ->
            val digest = AutoReplacement.fingerprint(app, source)
            AutoReplacement.stage(app, history, digest)
            assertEquals("PREPARED", app.database.replacementDao().byId(history.id)?.state)
            val saved = OutputStore.save(app, history)
            val row = app.database.replacementDao().byId(history.id)!!
            assertEquals("READY", row.state)
            AutoReplacement.validate(app, row)
            assertFalse(MediaStoreTrashHelper.isOriginalTrashed(app, source))
            app.contentResolver.openOutputStream(Uri.parse(saved.outputUri), "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            assertTrue(runCatching { AutoReplacement.validate(app, row) }.isFailure)
            assertFalse(MediaStoreTrashHelper.isOriginalTrashed(app, source))
        }
    }

    @Test fun changedSourceLargerOutputAndUnavailableGainmapLeaveOriginalAlone() = runBlocking {
        fixture { history, source, _ ->
            AutoReplacement.stage(app, history, "changed-source")
            assertNull(app.database.replacementDao().byId(history.id))
            AutoReplacement.stage(app, history.copy(outputBytes = history.inputBytes + 1), AutoReplacement.fingerprint(app, source))
            assertNull(app.database.replacementDao().byId(history.id))
            AutoReplacement.stage(app, history.copy(hadGainMap = true), AutoReplacement.fingerprint(app, source))
            assertNull(app.database.replacementDao().byId(history.id))
            assertFalse(MediaStoreTrashHelper.isOriginalTrashed(app, source))
        }
    }

    @Test fun cancellationAndTurningOffBlockAnAlreadySavedReplacement() = runBlocking {
        fixture { history, source, _ ->
            AutoReplacement.stage(app, history, AutoReplacement.fingerprint(app, source))
            OutputStore.save(app, history)
            val row = app.database.replacementDao().byId(history.id)!!
            app.settingsRepository.setAutoReplaceOriginals(false)
            assertTrue(runCatching { AutoReplacement.validate(app, row) }.isFailure)
            app.settingsRepository.setAutoReplaceOriginals(true)
            app.database.taskDao().cancel(history.batchId)
            assertTrue(runCatching { AutoReplacement.validate(app, row) }.isFailure)
            assertFalse(MediaStoreTrashHelper.isOriginalTrashed(app, source))
        }
    }

    @Test fun relinkedHistoryNeverUsesStaleTrashableUri() = runBlocking {
        org.junit.Assume.assumeTrue(MediaStoreTrashHelper.isTrashSupported)
        fixture { history, source, output ->
            val second = MediaStoreSaver.save(app, output, "relinked-${history.id}.jpg", OutputFormat.JPEG)!!
            try {
                val vm = HistoryViewModel(app)
                vm.ensureTrashableResolved(listOf(history))
                withTimeout(10_000) { vm.trashableOriginals.first { it[history.id] == source } }
                val relinked = history.copy(inputUri = second.toString())
                app.database.historyDao().update(relinked)
                vm.ensureTrashableResolved(listOf(relinked))
                withTimeout(10_000) { vm.trashableOriginals.first { it[history.id] == second } }
                assertNotEquals(source, vm.trashableOriginals.value[history.id])
                assertNotNull(vm.requestTrashSelected(setOf(history.id)))
            } finally {
                app.contentResolver.delete(second, null, null)
            }
        }
    }

    @Test fun undoUsesLatestItemAndRestoresPositionWithoutStuckProbe() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ConversionRepository(db.taskDao(), scope); repo.awaitReady()
            repo.addAll((0..2).map { QueueItem("$it", "content://fixture/$it", "$it", 100) })
            repo.update("1") { it.copy(status = QueueStatus.PROBING) }
            val removed = repo.remove("1")!!
            val restored = repo.restore(removed)!!
            assertEquals(QueueStatus.PENDING, restored.status)
            assertEquals(listOf("0", "1", "2"), repo.queue.value.map { it.id })
            assertNull(repo.restore(removed))
            repo.update("1") { it.copy(status = QueueStatus.CONVERTING) }
            assertNull(repo.remove("1"))
            repo.flush()
        } finally { scope.cancel(); db.close() }
    }
}
