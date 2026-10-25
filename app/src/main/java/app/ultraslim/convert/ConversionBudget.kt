package app.ultraslim.convert

/** Conservative admission estimates, not a guarantee of a codec's peak allocation. */
internal object ConversionBudget {
    private const val MIB = 1024L * 1024

    fun threads(cores: Int): Int = (cores / 2).coerceIn(1, 4)

    fun previewBytes(available: Long, total: Long, threshold: Long, lowMemory: Boolean): Long {
        if (lowMemory || available <= 0 || total <= 0) return 0
        val reserve = maxOf(threshold, total / 5, 512 * MIB)
        return minOf(available / 3, available - reserve).coerceAtLeast(0)
    }

    /**
     * 2026-10-24 实测校准：HDR preserve 峰值 RSS 12MP→609MB、25MP→1.03GB，折算 ≈192MiB 基线 + 32B/px
     * （36B/px 留少量裕量）。增益图 preserve 实际 mono 1B/px——旧公式按 16B/px 全额计，全尺寸增益图照片
     * 被虚高 16 倍；兼容模式转 YUV/无损重编按 4B/px 计。SDR 路径为 RGBA8888+YUV420+编码器，16B/px 足够。
     * 预算端旧公式 availMem/3 + max(threshold, total/5, 512MiB) 双重叠加约 5× 余量，8GB 设备常态误拒。
     */
    fun estimateBytes(width: Int, height: Int, gainWidth: Int, gainHeight: Int, hdr: Boolean,
                      inputBytes: Long): Long {
        val pixels = width.toLong() * height
        val gainPixels = gainWidth.toLong() * gainHeight
        return 192 * MIB + pixels * (if (hdr) 36 else 16) + gainPixels * 4 +
            inputBytes.coerceIn(0, 256 * MIB)
    }

    /** 本机最理想状态下的准入上限：available 永远不可能超过 total 减系统水位。 */
    fun deviceCapacityBytes(total: Long, threshold: Long): Long =
        (total - maxOf(threshold, 512 * MIB)).coerceAtLeast(0)

    fun canStart(width: Int, height: Int, gainWidth: Int, gainHeight: Int, hdr: Boolean,
                 inputBytes: Long, available: Long, total: Long, threshold: Long, lowMemory: Boolean): Boolean {
        val pixels = width.toLong() * height
        val gainPixels = gainWidth.toLong() * gainHeight
        if (lowMemory || width <= 0 || height <= 0 || pixels > 100_000_000L ||
            gainWidth < 0 || gainHeight < 0 || gainPixels > 100_000_000L) return false
        val reserve = maxOf(threshold, 512 * MIB)
        val budget = minOf(available / 2, available - reserve).coerceAtLeast(0)
        return estimateBytes(width, height, gainWidth, gainHeight, hdr, inputBytes) < budget
    }
}
