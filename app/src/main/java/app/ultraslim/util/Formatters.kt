package app.ultraslim.util

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val DATE_ONLY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.getDefault())
private val TIME_ONLY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val MONTH_DAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault())
private val FULL_DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.getDefault())

/** "4.7 MB" / "512 KB" / "980 B" */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = "B"
    for (u in units) {
        if (value < 1024.0) break
        value /= 1024.0
        unit = u
    }
    return if (value >= 100.0) {
        String.format(Locale.US, "%.0f %s", value, unit)
    } else {
        String.format(Locale.US, "%.1f %s", value, unit)
    }
}

/** "−62%" when the output is smaller, "+13%" when it grew. */
fun formatSavedPercent(inputBytes: Long, outputBytes: Long): String {
    if (inputBytes <= 0L) return "—"
    val pct = ((1.0 - outputBytes.toDouble() / inputBytes.toDouble()) * 100.0).roundToInt()
    return if (pct >= 0) "−$pct%" else "+${-pct}%"
}

/** "350 毫秒" / "1.2 秒" */
fun formatDuration(durationMs: Double): String =
    if (durationMs < 1000.0) {
        "${durationMs.roundToLong()} 毫秒"
    } else {
        String.format(Locale.US, "%.1f 秒", durationMs / 1000.0)
    }

/** "4096 × 3072" */
fun formatResolution(width: Int, height: Int): String =
    if (width > 0 && height > 0) "$width × $height" else "未知"

/** "2.4×" */
fun formatBoost(boost: Float): String =
    if (boost > 0f) String.format(Locale.US, "%.1f×", boost) else "无"

/** 刚刚 / n 分钟前 / n 小时前 / n 天前 / yyyy年M月d日 */
fun formatRelativeTime(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - timeMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(DATE_ONLY_FORMAT)
    }
}

fun formatByteDelta(bytes: Long): String = (if (bytes > 0) "+" else if (bytes < 0) "−" else "") + formatBytes(kotlin.math.abs(bytes))

/** 表示一个时间戳或批次归类的分组集合 */
@Immutable
data class TimeGroup<T>(
    val key: String,
    val timestamp: Long,
    val title: String,
    val items: List<T>,
)

/**
 * 格式化分组时间戳头部：
 * - 今天 HH:mm（若在 60 分钟以内附带相对时间标签，如 "刚刚"、"5 分钟前"）
 * - 昨天 HH:mm
 * - M月d日 HH:mm
 * - yyyy年M月d日 HH:mm
 */
fun formatGroupHeader(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timeMs <= 0L) return "历史批次"
    val zone = ZoneId.systemDefault()
    val zdtItem = Instant.ofEpochMilli(timeMs).atZone(zone)
    val zdtNow = Instant.ofEpochMilli(nowMs).atZone(zone)
    val itemDate = zdtItem.toLocalDate()
    val nowDate = zdtNow.toLocalDate()
    val timeStr = zdtItem.format(TIME_ONLY_FORMAT)

    if (itemDate == nowDate) {
        val diff = (nowMs - timeMs).coerceAtLeast(0L)
        val rel = when {
            diff < 60_000L -> "刚刚"
            diff < 60 * 60_000L -> "${diff / 60_000L} 分钟前"
            else -> null
        }
        return if (rel != null) "今天 $timeStr · $rel" else "今天 $timeStr"
    }

    if (itemDate == nowDate.minusDays(1)) {
        return "昨天 $timeStr"
    }

    return if (itemDate.year == nowDate.year) {
        zdtItem.format(MONTH_DAY_TIME_FORMAT)
    } else {
        zdtItem.format(FULL_DATE_TIME_FORMAT)
    }
}

/**
 * 将列表按时间戳邻近性（以及批次 ID）聚类分组，时间倒序排列。
 * - 连续相邻条目的时间戳间隔在 [maxGapMs]（默认 2.5 分钟）以内归入同一组（覆盖单次导入动作或连续转换）；
 * - 若有显式非空 batchId 且相同，强制归入同一组；若有显式不同 batchId，则开辟新组。
 */
fun <T> groupByTimeSession(
    items: List<T>,
    timestampSelector: (T) -> Long,
    batchIdSelector: (T) -> String = { "" },
    maxGapMs: Long = 150_000L,
): List<TimeGroup<T>> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedByDescending { timestampSelector(it) }
    val groups = mutableListOf<MutableList<T>>()
    var currentGroup = mutableListOf<T>()
    var lastTimestamp = -1L
    var lastBatchId = ""

    for (item in sorted) {
        val t = timestampSelector(item)
        val b = batchIdSelector(item)
        val hasBatch = b.isNotBlank()
        val lastHasBatch = lastBatchId.isNotBlank()

        val belongsToSameGroup = if (currentGroup.isEmpty()) {
            true
        } else if (hasBatch && lastHasBatch && b == lastBatchId) {
            true
        } else if (hasBatch && lastHasBatch && b != lastBatchId) {
            false
        } else {
            lastTimestamp > 0L && kotlin.math.abs(lastTimestamp - t) <= maxGapMs
        }

        if (belongsToSameGroup) {
            currentGroup.add(item)
            lastTimestamp = t
            if (hasBatch) lastBatchId = b
        } else {
            groups.add(currentGroup)
            currentGroup = mutableListOf(item)
            lastTimestamp = t
            lastBatchId = b
        }
    }
    if (currentGroup.isNotEmpty()) {
        groups.add(currentGroup)
    }

    return groups.mapIndexed { idx, groupItems ->
        val groupTime = timestampSelector(groupItems.first())
        val firstBatch = groupItems.firstOrNull { batchIdSelector(it).isNotBlank() }?.let(batchIdSelector)
        val key = if (!firstBatch.isNullOrBlank()) "batch-$firstBatch" else "time-$groupTime-$idx"
        TimeGroup(
            key = key,
            timestamp = groupTime,
            title = formatGroupHeader(groupTime),
            items = groupItems,
        )
    }
}
