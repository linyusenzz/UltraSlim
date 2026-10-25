package app.ultraslim.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.pm.PackageInfoCompat
import app.ultraslim.data.ConversionDefaults

internal const val FEEDBACK_URL = "https://github.com/linyusenzz/UltraSlim/issues/new?template=bug_report.md"

internal fun installedVersion(context: Context): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    "${info.versionName ?: "未知"} (${PackageInfoCompat.getLongVersionCode(info)})"
}.getOrDefault("未知版本")

/** User-initiated clipboard export; contains no image, URI, filename, location or device identifier. */
internal fun feedbackInfo(context: Context, defaults: ConversionDefaults, nativeVersion: String): String {
    val thermalStatus = runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull()
    return buildString {
        appendLine("UltraSlim ${installedVersion(context)}")
        appendLine("安装包：${context.packageName}")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("系统热状态等级：${thermalStatus?.toString() ?: "不可用"}（不是摄氏温度）")
        appendLine("全局默认配置（本次任务可能不同）：")
        appendLine("${defaults.format.label} / 品质 ${defaults.quality} / 速度 ${defaults.speedLevel} / 尺寸 ${defaults.resizePercent}%")
        appendLine("保留 EXIF=${defaults.keepExif}，保留 GPS=${defaults.keepLocation}，自动保存=${defaults.autoSave}")
        appendLine("仅保留更小结果=${defaults.onlySmaller}，HEIC 兼容=${defaults.heicGainmapCompatibility}，AVIF 兼容=${defaults.avifGainmapCompatibility}")
        append(nativeVersion)
    }
}
