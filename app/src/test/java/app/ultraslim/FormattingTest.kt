package app.ultraslim

import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.summary
import app.ultraslim.model.OutputFormat
import app.ultraslim.util.*
import org.junit.Assert.*
import org.junit.Test

class FormattingTest {
    @Test fun formatsAndSignedSavings() {
        assertEquals("image/jpeg", OutputFormat.parse("JPEG").mimeType)
        assertEquals("image/heic", OutputFormat.parse("heic").mimeType)
        assertEquals("image/avif", OutputFormat.parse("AVIF").mimeType)
        assertEquals("image/jxl", OutputFormat.parse("JXL").mimeType)
        assertEquals("image/jxl", OutputFormat.parse("jxl").mimeType)
        assertEquals("+25%", formatSavedPercent(100,125))
        assertEquals("−25%", formatSavedPercent(100,75))
        assertEquals("+25 B", formatByteDelta(25))
        assertEquals("−25 B", formatByteDelta(-25))
    }
    @Test fun filenamesAreReadableStableAndSafe() {
        val first = outputName("IMG_20261006.jpg", "AVIF", "task-1", 0)
        assertTrue(first.matches(Regex("IMG_20261006_Slim_[0-9a-f]{8}\\.avif")))
        assertEquals(first, outputName("IMG_20261006.jpg", "avif", "task-1", 0))
        assertTrue(outputName("photo.jpg", "heic", "motion", 0, motionPhoto = true).endsWith("MP.heic"))
        assertNotEquals(first, outputName("IMG_20261006.jpg", "avif", "task-2", 0))
        assertTrue(outputName("81_1_1_5767931.jpg", "heic", "task-3", 0).startsWith("Photo_"))
        assertFalse(outputName("../a/b.jpg", "avif", "task", 0).contains('/'))
    }

    @Test fun convertedScreenshotsDoNotAdvertiseANewScreenCapture() {
        for (name in listOf("Screenshot_20250101.png", "screen_shot-123.jpg", "ScreenCapture.jpg", "截屏2025.jpg", "截图.png")) {
            assertTrue(outputName(name, "avif", "same-task", 0).startsWith("Photo_"))
        }
    }

    @Test fun testGroupByTimeSessionClustersCloseTimestamps() {
        data class MockItem(val id: String, val timestamp: Long, val batchId: String = "")
        val t0 = 1700000000000L
        val items = listOf(
            MockItem("1", t0),
            MockItem("2", t0 + 1000L),
            MockItem("3", t0 + 2000L),
            MockItem("4", t0 + 10 * 60 * 1000L),
            MockItem("5", t0 + 10 * 60 * 1000L + 500L),
        )

        val groups = groupByTimeSession(
            items = items,
            timestampSelector = { it.timestamp },
            batchIdSelector = { it.batchId },
        )

        assertEquals(2, groups.size)
        // Group 1 (newest first): items 5 then 4
        assertEquals(listOf("5", "4"), groups[0].items.map { it.id })
        // Group 2 (newest first): items 3, 2, 1
        assertEquals(listOf("3", "2", "1"), groups[1].items.map { it.id })
    }

    @Test fun testGroupByTimeSessionHonorsBatchId() {
        data class MockItem(val id: String, val timestamp: Long, val batchId: String = "")
        val t0 = 1700000000000L
        val items = listOf(
            MockItem("1", t0, "batch-A"),
            MockItem("2", t0 + 500L, "batch-B"),
        )
        val groups = groupByTimeSession(
            items = items,
            timestampSelector = { it.timestamp },
            batchIdSelector = { it.batchId },
        )
        assertEquals(2, groups.size)
    }

    @Test fun jxlReconstructionSummaryAndGroupHeadersFormatCorrectly() {
        val jxlRecon = ConversionDefaults(format = OutputFormat.JXL, jxlJpegReconstruction = true)
        val jpegSummary = jxlRecon.copy(resizePercent = 50).summary(inputContainer = 0)
        assertTrue(jpegSummary.contains("JPEG 重建 · 原始尺寸"))
        assertFalse(jpegSummary.contains("50%") || jpegSummary.contains("编码质量"))
        val heicSummary = jxlRecon.copy(resizePercent = 50).summary(inputContainer = 1)
        assertFalse(heicSummary.contains("JPEG 重建"))
        assertTrue(heicSummary.contains("尺寸 50%"))
        assertTrue(jxlRecon.toConversionSettings().jxlJpegReconstruction)
        val avif = ConversionDefaults(format = OutputFormat.AVIF, jxlJpegReconstruction = true)
        assertFalse(avif.summary(inputContainer = 0).contains("JPEG 重建"))

        val now = 1_700_000_000_000L
        assertTrue(formatGroupHeader(now - 10_000L, now).endsWith("· 刚刚"))
        assertTrue(formatGroupHeader(now - 5 * 60_000L, now).endsWith("· 5 分钟前"))
        assertTrue(formatGroupHeader(now - 24 * 3600_000L, now).startsWith("昨天 "))
    }

}
