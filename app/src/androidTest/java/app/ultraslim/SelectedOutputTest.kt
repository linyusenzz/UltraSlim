package app.ultraslim

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.model.OutputFormat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SelectedOutputTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    private val authority = "$testPackage.exports"
    private val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
    private val files = mutableListOf<File>()
    private fun control(method: String, value: String? = null) {
        context.contentResolver.call(tree, method, value, null)
    }
    @Before fun reset() {
        val command = "am start -W -n $testPackage/app.ultraslim.FolderGrantActivity --es target ${context.packageName} --ez export true"
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        context.contentResolver.takePersistableUriPermission(tree, grantFlags)
        control("reset")
    }
    @After fun clean() {
        try {
            control("reset")
        } finally {
            files.forEach { ExportCheckpoint(it).delete(); it.delete() }
            context.contentResolver.releasePersistableUriPermission(tree, grantFlags)
        }
    }
    private fun source() = File(context.cacheDir, UUID.randomUUID().toString()).also {
        it.writeBytes(ByteArray(180000) { n -> n.toByte() }); files += it
    }
    private fun count(): Int = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root"), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        null, null, null)!!.use { it.count }
    private fun save(file: File, cancel: () -> Unit = {}): Uri = SelectedOutput.save(context, file, "a:b.jpg", OutputFormat.JPEG,
        tree, MediaStoreSaver.fingerprintOf(file)!!, SourceTimestamps(null, 1700000000123), cancel)

    @Test fun providerNormalizationDoesNotLoopAndRecoveryOnlyAdoptsOwnUri() {
        val first = source(); val existing = save(first)
        assertEquals("a-b.jpg", SelectedOutput.displayName(context, existing))
        val second = source(); val result = save(second)
        assertNotEquals(existing, result)
        assertEquals(2, count())
        assertEquals(result, save(second))
        assertEquals(2, count())
        assertArrayEquals(first.readBytes(), context.contentResolver.openInputStream(existing)!!.use { it.readBytes() })
        assertArrayEquals(second.readBytes(), context.contentResolver.openInputStream(result)!!.use { it.readBytes() })
    }

    @Test fun retainedAlbumNameCountsUnicodeEquivalentNamesBeforeChoosingSuffix() {
        val path = "Pictures/UltraSlimTest-${UUID.randomUUID()}/"
        val image = source()
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val created = mutableListOf<Uri>()
        try {
            for (name in listOf("e\u0301.jpg", "É (1).jpg")) {
                created += requireNotNull(MediaStoreSaver.save(context, image, name, OutputFormat.JPEG, relativePath = path))
            }
            val output = SelectedOutput.save(context, image, "é.jpg", OutputFormat.JPEG, null,
                MediaStoreSaver.fingerprintOf(image)!!, SourceTimestamps(), {}, path = path,
                directoryCache = SelectedOutput.DirectoryCache())
            created += output
            assertEquals("é (2).jpg", SelectedOutput.displayName(context, output))
        } finally { created.forEach { context.contentResolver.delete(it, null, null) } }
    }

    @Test fun cancellationDuringCopyDeletesOnlyNewDocumentAndKeepsPrivateBytes() {
        val existing = save(source()); val file = source(); val original = file.readBytes(); var checks = 0
        try { save(file) { if (++checks == 5) throw CancellationException("test cancel") }; fail("expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, count()); assertArrayEquals(original, file.readBytes())
        assertNotNull(context.contentResolver.openInputStream(existing)?.use { it.readBytes() })
    }

    @Test fun rejectedAccessCanRetryAndUnsupportedTimestampDoesNotBlockExport() {
        val file = source(); control("deny", "true")
        try { save(file); fail("expected denied access") } catch (_: SecurityException) { }
        assertTrue(file.isFile)
        control("deny", "false"); control("noTimes", "true")
        val output = save(file)
        assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(output)!!.use { it.readBytes() })
    }

    @Test fun sourceDatesSurviveWithoutOriginalGrantIncludingUnknownDates() {
        val file = source(); val dates = SourceTimestamps(1600000000123, 1700000000456)
        ExportCheckpoint(file).capture(dates)
        assertEquals(dates, ExportCheckpoint(file).dates())
        ExportCheckpoint(file).capture(SourceTimestamps())
        assertEquals(SourceTimestamps(), ExportCheckpoint(file).dates())
    }
}
