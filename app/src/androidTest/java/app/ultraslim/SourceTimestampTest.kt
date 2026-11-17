package app.ultraslim

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.model.OutputFormat
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourceTimestampTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
    private val capture = Instant.parse("2021-07-08T03:40:11.345Z").toEpochMilli()
    private val modified = Instant.parse("2024-02-03T04:05:06Z").toEpochMilli()
    private fun source(exif: Boolean = false, taken: Long? = null) = Uri.parse(
        "content://${InstrumentationRegistry.getInstrumentation().context.packageName}.timestamps/${if (exif) "exif" else "plain"}.jpg?modified=$modified" +
            (taken?.let { "&taken=$it" } ?: ""))

    @Test fun providerDateWinsAndModificationIsNeverPretendedToBeCaptureTime() {
        assumeTrue(Build.VERSION.SDK_INT >= 30) // The test provider writes OffsetTimeOriginal using the platform API.
        assertEquals(capture, SourceMedia.captureTime(app, source(exif = true)))
        assertEquals(capture + 1234, SourceMedia.captureTime(app, source(exif = true, taken = capture + 1234)))
        val unknown = SourceMedia.timestamps(app, source())
        assertNull(unknown.dateTaken)
        assertEquals(modified, unknown.modifiedAt)
    }

    @Test fun ephemeralCopyRetainsProviderOnlyDatesFromDiskWithoutChangingPhotoBytes() {
        val id = UUID.randomUUID().toString()
        val input = source(taken = capture)
        try {
            val durable = InputStore.persist(app, input, id)
            assertEquals("${app.packageName}.files", durable.authority)
            assertArrayEquals(app.contentResolver.openInputStream(input)!!.use { it.readBytes() },
                app.contentResolver.openInputStream(durable)!!.use { it.readBytes() })
            // No process-local cache: both reads load the durable sidecar, as startup recovery does.
            assertEquals(SourceTimestamps(capture, modified), SourceMedia.timestamps(app, durable))
            assertEquals(SourceTimestamps(capture, modified), InputMetadataStore.read(app, durable))
            val sidecar = File(app.filesDir, "input-metadata/$id.json")
            sidecar.setLastModified(1)
            val otherReferences = File(app.filesDir, "inputs").listFiles().orEmpty().filter { it.name != id }
                .mapTo(mutableSetOf()) { androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", it).toString() }
            InputMetadataStore.cleanUnreferenced(app, otherReferences + durable.toString(), 2)
            assertTrue(sidecar.isFile)
            InputMetadataStore.cleanUnreferenced(app, otherReferences, 2)
            assertFalse(sidecar.exists())
        } finally {
            File(app.filesDir, "inputs/$id").delete()
            File(app.filesDir, "input-metadata/$id.json").delete()
        }
    }

    @Test fun publicationRetainsExifCaptureAndFileTimesSeparately() = publication(exif = true)

    @Test fun publicationWithoutExifPreservesBytesAndFileTimeDespiteReadOnlyCaptureIndex() = publication(exif = false)

    private fun publication(exif: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val file = File(app.cacheDir, "timestamp-${UUID.randomUUID()}.jpg")
        var uri: Uri? = null
        try {
            app.contentResolver.openInputStream(source(exif = exif))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            val originalBytes = file.readBytes()
            uri = MediaStoreSaver.save(app, file, file.name, OutputFormat.JPEG,
                dateTaken = capture, fileModifiedTime = modified)
            assertNotNull(uri)
            assertArrayEquals(originalBytes, app.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() })
            app.contentResolver.query(uri!!, arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                if (exif) assertEquals(capture, it.getLong(0))
                else {
                    // DATE_TAKEN is documented read-only. A provider can ignore the
                    // best-effort update; never synthesize EXIF to satisfy this index.
                    assertTrue(it.isNull(0) || it.getLong(0) == 0L || it.getLong(0) == capture)
                }
                assertEquals(modified / 1000, it.getLong(1))
            }
        } finally { uri?.let { app.contentResolver.delete(it, null, null) }; file.delete() }
    }
}
