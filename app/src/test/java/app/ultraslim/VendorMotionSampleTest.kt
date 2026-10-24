package app.ultraslim

import app.ultraslim.convert.MotionPhotoContainer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the production detector against real vendor samples in <repo>/样张 when present
 * (gitignored, local only); silently passes when the directory is absent so CI is unaffected.
 */
class VendorMotionSampleTest {
    private val results = mutableListOf<String>()

    @Test fun realVendorSamplesAreDetectedWithStructurallyValidVideo() {
        val dir = sequenceOf(File("样张"), File("../样张")).firstOrNull { it.isDirectory } ?: return
        val files = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "heic", "avif") }.orEmpty()
        assertTrue("样张目录存在但没有样本文件", files.isNotEmpty())
        for (file in files.sortedBy { it.name }) {
            val info = file.inputStream().use { MotionPhotoContainer.detect(it.channel) }
            assertNotNull("未识别: ${file.name}", info)
            file.inputStream().use {
                assertTrue(
                    "视频区域校验失败: ${file.name}",
                    MotionPhotoContainer.validVideo(it.channel, info!!.videoOffset, info.videoLength)
                )
            }
            results.add("${file.name}: offset=${info!!.videoOffset} length=${info.videoLength}")
        }
        println(results.joinToString("\n"))
    }
}
