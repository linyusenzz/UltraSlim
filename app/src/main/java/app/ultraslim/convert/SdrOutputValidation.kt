package app.ultraslim.convert

import android.graphics.ImageDecoder
import app.ultraslim.model.OutputFormat
import java.io.File
import java.io.IOException

/** Verify SDR outputs with the platform, independently of our native preview decoder. */
internal object SdrOutputValidation {
    fun requireReadable(file: File, format: OutputFormat) {
        // An external app may support formats (e.g. JXL) the platform cannot decode.
        if (!ImageDecoder.isMimeTypeSupported(format.mimeType)) return
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(maxOf(1, (edge + 1599) / 1600))
            }
            bitmap.recycle()
        } catch (e: IOException) {
            throw IllegalStateException("当前系统无法解码导出的 SDR ${format.label} 图片，文件未保存。请尝试 JPEG 格式。", e)
        }
    }
}
