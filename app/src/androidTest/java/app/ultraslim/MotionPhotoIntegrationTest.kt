package app.ultraslim

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.MotionPhotoContainer
import app.ultraslim.convert.MotionPhotoHelper
import app.ultraslim.convert.MotionPhotoInfo
import app.ultraslim.model.OutputFormat
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoIntegrationTest {
    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8)
        .putInt(payload.size + 8).put(type.toByteArray()).put(payload).array()

    @Test fun hdrMotionRoundtripAndTruncationKeepStillIntact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "motion-integration").apply { mkdirs() }
        try {
            val video = box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf()) + box("mdat", ByteArray(1024 * 1024))
            val input = File(dir, "input.jpg")
            val original = instrumentation.context.assets.open("apple_gainmap_new.jpg").use { it.readBytes() }
            val info = MotionPhotoInfo(654321, video.size.toLong(), 0)
            val jpeg = MotionPhotoContainer.jpegWithXmp(original, info)
            input.writeBytes(jpeg + video)
            val detected = MotionPhotoHelper.detect(context, Uri.fromFile(input))!!
            assertEquals(info.copy(videoOffset = jpeg.size.toLong()), detected)
            for (format in listOf(OutputFormat.JPEG, OutputFormat.HEIC, OutputFormat.AVIF)) {
                val output = File(dir, "output.${format.extension}")
                val xmp = if (format != OutputFormat.JPEG) MotionPhotoContainer.xmp(detected, format.mimeType) else null
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { b ->
                        val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, format.codecId, 90, 60, 4, true, true, true, 2, 100, 2, true, xmp)
                        assertTrue(result.error, result.isSuccess)
                    }
                }
                assertTrue(MotionPhotoHelper.attachMotionPhoto(context, Uri.fromFile(input), output, format, detected))
                val actual = MotionPhotoHelper.detect(context, Uri.fromFile(output))!!
                assertEquals(info.presentationTimestampUs, actual.presentationTimestampUs)
                assertEquals(info.videoLength, actual.videoLength)
                assertArrayEquals(video, output.readBytes().takeLast(video.size).toByteArray())
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    val probe = UltraHdrBridge.nativeProbe(it.fd)
                    assertTrue(probe.error, probe.isSuccess)
                    assertTrue(probe.hasGainMap)
                    assertTrue(probe.hasExif)
                }
                val before = output.readBytes()
                val broken = File(dir, "broken.jpg").apply { writeBytes(jpeg + video.copyOf(video.size - 1)) }
                assertThrows(IllegalArgumentException::class.java) {
                    MotionPhotoHelper.attachMotionPhoto(context, Uri.fromFile(broken), output, format, detected)
                }
                assertArrayEquals(before, output.readBytes())
                assertFalse(dir.listFiles()!!.any { it.name.endsWith(".motion.tmp") })
            }
        } finally { dir.deleteRecursively() }
    }
}
