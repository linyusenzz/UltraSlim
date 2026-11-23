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
    fun detect(fd: Int, channel: java.nio.channels.FileChannel): MotionPhotoInfo? {
        if (channel.size() < 8) return null
        val head = MotionPhotoContainer.read(channel, 0, 2)
        if (head[0] == (-1).toByte() && head[1] == 0x0a.toByte()) return null
        val jpeg = head.contentEquals(byteArrayOf(-1, -40))
        val xmp = if (jpeg) null else UltraHdrBridge.nativeReadHeifXmp(fd)?.toString(Charsets.UTF_8)
        return MotionPhotoContainer.detect(channel, xmp)
    }

    fun detect(context: Context, uri: Uri): MotionPhotoInfo? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            detect(pfd.fd, input.channel)
        }
    }

    /** A detected motion photo must either be preserved completely or fail without replacing the still. */
    fun attachMotionPhoto(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        targetFormat: OutputFormat,
        info: MotionPhotoInfo,
        sourceChannel: java.nio.channels.FileChannel? = null,
        syncToDisk: Boolean = true,
    ): Boolean = attachMotionPhotoWithInfo(context, inputUri, outputFile, targetFormat, info, sourceChannel, syncToDisk) != null

    internal fun attachMotionPhotoWithInfo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        targetFormat: OutputFormat,
        info: MotionPhotoInfo,
        sourceChannel: java.nio.channels.FileChannel? = null,
        syncToDisk: Boolean = true,
    ): MotionPhotoInfo? {
        if (targetFormat != OutputFormat.JPEG && targetFormat != OutputFormat.HEIC && targetFormat != OutputFormat.AVIF) return null
        require(outputFile.isFile && outputFile.length() > 0)
        val temp = File.createTempFile(outputFile.name, ".motion.tmp", outputFile.parentFile)
        try {
            var verifiedMotion: MotionPhotoInfo? = null
            fun appendWithChannel(src: java.nio.channels.FileChannel) {
                require(MotionPhotoContainer.validVideo(src, info.videoOffset, info.videoLength)) { "实况视频不完整" }
                if (targetFormat != OutputFormat.JPEG) {
                    FileInputStream(outputFile).use { input ->
                        FileOutputStream(temp).use { out ->
                            var pos = 0L
                            val len = input.channel.size()
                            while (pos < len) {
                                val n = input.channel.transferTo(pos, len - pos, out.channel)
                                if (n <= 0) break
                                pos += n
                            }
                            if (pos < len) {
                                input.channel.position(pos)
                                input.copyTo(out, 128 * 1024)
                            }
                        }
                    }
                } else {
                    temp.writeBytes(MotionPhotoContainer.jpegWithXmp(outputFile.readBytes(), info))
                }
                val stillLength = temp.length()
                FileOutputStream(temp, true).use { output ->
                    if (targetFormat != OutputFormat.JPEG) {
                        require(info.videoLength <= 0xffffffffL - 8)
                        output.write(ByteBuffer.allocate(8).putInt((info.videoLength + 8).toInt()).putInt(0x6d707664).array())
                    }
                    MotionPhotoContainer.copyVideo(src, info, output)
                    if (syncToDisk) output.fd.sync()
                }
                val padding = if (targetFormat != OutputFormat.JPEG) 8 else 0
                check(temp.length() == stillLength + padding + info.videoLength) { "实况视频写入不完整" }
                val expectedOffset = stillLength + padding
                // detect() already validates validVideo structurally on the spliced output.
                val detected = detect(context, Uri.fromFile(temp))
                check(detected == info.copy(videoOffset = expectedOffset)) { "实况元数据校验失败" }
                verifiedMotion = detected
            }
            if (sourceChannel != null) {
                appendWithChannel(sourceChannel)
            } else {
                val fd = context.contentResolver.openFileDescriptor(inputUri, "r") ?: error("无法读取实况视频")
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { appendWithChannel(it.channel) }
            }
            // Same-directory rename is atomic; never delete the valid still before replacement succeeds.
            Files.move(temp.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return verifiedMotion
        } finally {
            temp.delete()
        }
    }
}
