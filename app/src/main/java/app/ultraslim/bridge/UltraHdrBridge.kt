package app.ultraslim.bridge

/**
 * Result of probing an image for Ultra HDR / gain map information.
 * Constructed from native code (field order must match the JNI signature).
 */
data class ProbeResult(
    val errorCode: Int,
    val error: String,
    val hasGainMap: Boolean,
    /** 0 = JPEG, 1 = HEIF, 2 = AVIF, 3 = JXL, -1 = unknown */
    val container: Int,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val hasExif: Boolean,
    val maxContentBoost: Float,
    val hdrCapacityMax: Float,
) {
    val isSuccess: Boolean get() = errorCode == 0

    val containerLabel: String
        get() = when (container) {
            0 -> "JPEG"
            1 -> "HEIC"
            2 -> "AVIF"
            3 -> "JXL"
            else -> "未知"
        }
}

/**
 * Result of transcoding one image to HEIC/AVIF (with gain map preserved when present).
 * Constructed from native code (field order must match the JNI signature).
 */
data class TranscodeResult(
    val errorCode: Int,
    val error: String,
    val inputHadGainMap: Boolean,
    val width: Int,
    val height: Int,
    val gainmapWidth: Int,
    val gainmapHeight: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val durationMs: Double,
    /** 输出是否携带增益图；由编码器/写后校验直接给出，调用方无需再整读输出文件。 */
    val hasGainMap: Boolean = false,
    /** 输出是否携带 EXIF。 */
    val hasExif: Boolean = false,
    val maxContentBoost: Float = 1.0f,
) {
    val isSuccess: Boolean get() = errorCode == 0

    /** 100 means same size; lower is better. */
    val sizeRatio: Double
        get() = if (inputBytes > 0) outputBytes.toDouble() / inputBytes else 1.0
}

/** Thin Kotlin facade over the native libultrahdr transcode pipeline. */
object UltraHdrBridge {

    init {
        System.loadLibrary("ultraslim")
    }

    @JvmStatic
    external fun nativeProbe(fd: Int): ProbeResult

    @JvmStatic
    external fun nativeTranscode(
        inFd: Int,
        outFd: Int,
        outCodec: Int,
        quality: Int,
        gainmapQuality: Int,
        gainmapScale: Int,
        multiChannelGainmap: Boolean,
        keepExif: Boolean,
        allowSdrTranscode: Boolean,
        speedLevel: Int,
        resizePercent: Int,
        codecThreads: Int,
        keepLocation: Boolean = true,
        motionXmp: ByteArray? = null,
        retrySmaller: Boolean = false,
        avifGainmapCompatibility: Boolean = false,
        heicGainmapCompatibility: Boolean = false,
    ): TranscodeResult

    @JvmStatic
    external fun nativeVersion(): String

    /** Best effort on an already granted output descriptor; no path traversal or permission changes. */
    @JvmStatic
    external fun nativeUpdateFileTime(fd: Int, milliseconds: Long): Boolean

    /**
     * Decodes the primary SDR rendition of an image to a small JPEG, for previews
     * the platform decoder cannot handle (e.g. ISO 21496-1 gain map HEIC).
     * Returns null on failure or if the estimated pixel working set exceeds the
     * budget. Codec-private allocations are not subject to a hard allocator cap.
     */
    @JvmStatic
    external fun nativeDecodePreview(fd: Int, maxDim: Int, maxWorkingBytes: Long = Long.MAX_VALUE): ByteArray?

    @JvmStatic
    external fun nativeReadHeifXmp(fd: Int): ByteArray?

    /** Debug: raw Y plane of the primary image, [w32][h32][pixels...]. */
    @JvmStatic
    external fun nativeDebugPlaneY(fd: Int): ByteArray?
}
