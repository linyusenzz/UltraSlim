package app.ultraslim.convert

import android.graphics.ImageDecoder
import app.ultraslim.model.OutputFormat
import java.io.File
import java.io.IOException

/** Verify SDR outputs with the platform, independently of our native preview decoder. */
internal object SdrOutputValidation {
    fun requireReadable(file: File, format: OutputFormat, expectedWidth: Int = 0, expectedHeight: Int = 0): Boolean {
        // An external app may support formats (e.g. JXL) the platform cannot decode.
        if (!ImageDecoder.isMimeTypeSupported(format.mimeType)) return false
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                if (expectedWidth > 0 && expectedHeight > 0) {
                    check((info.size.width == expectedWidth && info.size.height == expectedHeight) ||
                        (info.size.width == expectedHeight && info.size.height == expectedWidth)) { "系统解码尺寸不一致" }
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val edge = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(maxOf(1, (edge + 511) / 512))
                decoder.setOnPartialImageListener { false }
            }
            bitmap.recycle()
            return true
        } catch (e: IOException) {
            throw IllegalStateException("当前系统无法解码导出的 SDR ${format.label} 图片，文件未保存。请尝试 JPEG 格式。", e)
        }
    }
}
