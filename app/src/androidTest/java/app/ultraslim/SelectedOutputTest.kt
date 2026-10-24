package app.ultraslim

import android.net.Uri
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
    private val authority = "app.ultraslim.tests.exports"
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
    private val files = mutableListOf<File>()
    private fun control(method: String, value: String? = null) {
        context.contentResolver.call(Uri.parse("content://$authority"), method, value, null)
    }
    @Before fun reset() { control("reset") }
    @After fun clean() { control("reset"); files.forEach { ExportCheckpoint(it).delete(); it.delete() } }
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
