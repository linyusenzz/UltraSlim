package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.model.OutputFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android URI/FD adapter. Parsing, XMP and bounded copying live in MotionPhotoContainer. */
object MotionPhotoHelper {
    fun detect(context: Context, uri: Uri): MotionPhotoInfo? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val channel = input.channel
            if (channel.size() < 8) return@use null
            val jpeg = MotionPhotoContainer.read(channel, 0, 2).contentEquals(byteArrayOf(-1, -40))
            val xmp = if (jpeg) null else UltraHdrBridge.nativeReadHeifXmp(pfd.fd)?.toString(Charsets.UTF_8)
            MotionPhotoContainer.detect(channel, xmp)
        }
    }

    /** A detected motion photo must either be preserved completely or fail without replacing the still. */
    fun attachMotionPhoto(context: Context, inputUri: Uri, outputFile: File, targetFormat: OutputFormat, info: MotionPhotoInfo): Boolean {
        if (targetFormat != OutputFormat.JPEG && targetFormat != OutputFormat.HEIC && targetFormat != OutputFormat.AVIF) return false
        require(outputFile.isFile && outputFile.length() > 0)
        val temp = File.createTempFile(outputFile.name, ".motion.tmp", outputFile.parentFile)
        try {
            if (targetFormat != OutputFormat.JPEG) {
                FileInputStream(outputFile).use { input -> temp.outputStream().use { input.copyTo(it) } }
            } else {
                temp.writeBytes(MotionPhotoContainer.jpegWithXmp(outputFile.readBytes(), info))
            }
            val stillLength = temp.length()
            FileOutputStream(temp, true).use { output ->
                if (targetFormat != OutputFormat.JPEG) {
                    require(info.videoLength <= 0xffffffffL - 8)
                    output.write(ByteBuffer.allocate(8).putInt((info.videoLength + 8).toInt()).putInt(0x6d707664).array())
                }
                val fd = context.contentResolver.openFileDescriptor(inputUri, "r") ?: error("无法读取实况视频")
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { MotionPhotoContainer.copyVideo(it.channel, info, output) }
                output.fd.sync()
            }
            val padding = if (targetFormat != OutputFormat.JPEG) 8 else 0
            check(temp.length() == stillLength + padding + info.videoLength) { "实况视频写入不完整" }
            val expectedOffset = stillLength + padding
            FileInputStream(temp).use { check(MotionPhotoContainer.validVideo(it.channel, expectedOffset, info.videoLength)) }
            val detected = detect(context, Uri.fromFile(temp))
            check(detected == info.copy(videoOffset = expectedOffset)) { "实况元数据校验失败" }
            // Same-directory rename is atomic; never delete the valid still before replacement succeeds.
            Files.move(temp.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            temp.delete()
        }
    }
}
