package app.ultraslim.model

/** Output container for the transcoded image. */
enum class OutputFormat(
    val extension: String,
    /** Codec id understood by UltraHdrBridge.nativeTranscode (0 = JPEG, 1 = HEIC, 2 = AVIF, 3 = JXL). */
    val codecId: Int,
    val label: String,
    val mimeType: String,
) {
    AVIF("avif", 2, "AVIF", "image/avif"),
    HEIC("heic", 1, "HEIC", "image/heic"),
    JXL("jxl", 3, "JXL", "image/jxl"),
    JPEG("jpg", 0, "JPEG", "image/jpeg"),
    ;

    companion object {
        /** Matches by extension ("avif"/"heic"/"jxl"/"jpg") or label ("JXL"/"JPEG"), case-insensitive. Defaults to AVIF. */
        fun parse(value: String?): OutputFormat =
            entries.firstOrNull {
                it.extension.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true)
            } ?: AVIF
    }
}

/** The subset of options that is passed straight to the native transcode call. */
data class ConversionSettings(
    val format: OutputFormat = OutputFormat.AVIF,
    val quality: Int = 92,
    val gainmapQuality: Int = 60,
    val gainmapScale: Int = 4,
    val multiChannelGainmap: Boolean = true,
    val keepExif: Boolean = true,
    val keepLocation: Boolean = true,
    val allowSdr: Boolean = true,
    val speedLevel: Int = 1,
    val resizePercent: Int = 100,
    val avifGainmapCompatibility: Boolean = true,
    val heicGainmapCompatibility: Boolean = true,
)
