package app.ultraslim.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.theme.Motion
import app.ultraslim.util.formatBytes
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coil model for a string that is either a content uri or a cache file path. */
fun imageModelOf(uriString: String): Any =
    if (uriString.startsWith("content://") || uriString.startsWith("android.resource://")) {
        Uri.parse(uriString)
    } else {
        File(uriString)
    }

@Composable
fun SectionHeader(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatsCard(
    stats: app.ultraslim.data.HistoryStats,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶栏：累计瘦身体积与瘦身率徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (stats.totalSavedBytes >= 0) "累计压缩减少" else "成品累计增加",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatBytes(kotlin.math.abs(stats.totalSavedBytes)),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (stats.countDone > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "仅比较原图与成品体积；保留原图时不会释放对应空间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
                if (stats.countDone > 0 && stats.totalInputBytes > 0) {
                    val percent = (stats.savedPercent * 100).toInt()
                    BadgeChip(
                        text = if (percent >= 0) "\u2212$percent%" else "+${-percent}%",
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 中间栏：容量流向对比条（双色条显示原总体积 vs 现总体积）
            if (stats.countDone > 0 && stats.totalInputBytes > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ratio = (stats.totalOutputBytes.toFloat() / stats.totalInputBytes.toFloat()).coerceIn(0.05f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "原总体积 ${formatBytes(stats.totalInputBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            "成品合计 ${formatBytes(stats.totalOutputBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            // 底栏：三列数据指标（已转换、平均每张省、HDR增益图）
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("已转换照片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text("${stats.countDone} 张", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("单张平均瘦身", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(if (stats.countDone > 0) formatBytes(stats.avgSavedBytes) else "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Ultra HDR 增益图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(if (stats.gainmapCount > 0) "${stats.gainmapCount} 张保留" else "无 HDR 记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun BadgeChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = containerColor) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Callbacks for every control of [ConversionOptionsPanel]. */
class OptionsCallbacks(
    val onFormatChange: (OutputFormat) -> Unit,
    val onQualityChange: (Int) -> Unit,
    val onGainmapQualityChange: (Int) -> Unit,
    val onGainmapScaleChange: (Int) -> Unit,
    val onMultiChannelChange: (Boolean) -> Unit,
    val onKeepExifChange: (Boolean) -> Unit,
    val onAllowSdrChange: (Boolean) -> Unit,
    val onAutoSaveChange: (Boolean) -> Unit,
    val onPreset: (CompressionPreset) -> Unit,
    val onSpeedLevelChange: (Int) -> Unit,
    val onResizePercentChange: (Int) -> Unit,
    val onOnlySmallerChange: (Boolean) -> Unit = {},
    val onKeepLocationChange: (Boolean) -> Unit = {},
    val onAvifGainmapCompatibilityChange: (Boolean) -> Unit = {},
    val onHeicGainmapCompatibilityChange: (Boolean) -> Unit = {},
    val onJxlJpegReconstructionChange: (Boolean) -> Unit = {},
    val onSaveAsDefault: (() -> Unit)? = null,
    val onResetToDefault: (() -> Unit)? = null,
)

/** Named bundles of conversion parameters shown as quick-select chips. */
enum class CompressionPreset(
    val label: String,
    val description: String,
    val quality: Int,
    val gainmapQuality: Int,
    val gainmapScale: Int,
    val resizePercent: Int,
    val speedLevel: Int,
) {
    QUALITY("高保真", "近乎无损质感，保留极致微观纹理", quality = 92, gainmapQuality = 70, gainmapScale = 2, resizePercent = 100, speedLevel = 1),
    BALANCED("均衡推荐", "画质无感劣化，体积精简 50%~65%", quality = 88, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 1),
    EXTREME("省空间", "适度权衡，最大化节省存储空间", quality = 78, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 1),
    ;

    fun matches(defaults: ConversionDefaults): Boolean =
        defaults.quality == quality &&
            defaults.resizePercent == resizePercent &&
            defaults.speedLevel == speedLevel
}

/**
 * 底部或常驻展示的紧凑“方案胶囊条”
 */
@Composable
fun SchemeCapsuleBar(
    defaults: ConversionDefaults,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val activePreset = remember(defaults) {
        CompressionPreset.entries.firstOrNull { it.matches(defaults) }
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "输出方案",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BadgeChip(
                    text = defaults.format.label,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                BadgeChip(
                    text = if (defaults.format == OutputFormat.JXL && defaults.jxlJpegReconstruction) "JPEG 重建"
                        else activePreset?.label ?: "品质 ${defaults.quality}",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (defaults.resizePercent != 100) {
                    BadgeChip(
                        text = "${defaults.resizePercent}% 尺寸",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "微调",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "展开配置",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 转换选项面板：结构清晰的三层递进布局
 * 1. 核心格式与画质预设（90% 场景）
 * 2. 基础输出与防变大保障
 * 3. 进阶参数（尺寸、速度、精确品质、EXIF）折叠
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversionOptionsPanel(
    defaults: ConversionDefaults,
    callbacks: OptionsCallbacks,
    modifier: Modifier = Modifier,
    showAutoSave: Boolean = true,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // === 第一层：输出格式与画质预设 ===
            SectionHeader("1. 输出格式", "优先推荐 AVIF / HEIC 保持高画质极省体积")
            val formats = OutputFormat.entries
            ExpressiveChoiceRow(formats.map { it.label }, formats.indexOf(defaults.format),
                onSelect = { callbacks.onFormatChange(formats[it]) }, enabled = enabled)
            val formatTip = when (defaults.format) {
                OutputFormat.AVIF -> "AVIF：现代 AV1 编码，体积超小，支持保留实况照片；HDR 显示取决于相册支持"
                OutputFormat.HEIC -> "HEIC：高效 HEVC 编码，支持保留实况照片；HDR 显示取决于相册支持"
                OutputFormat.JXL -> "JXL：JPEG XL 现代格式，保留极致微观纹理与无损级色彩"
                OutputFormat.JPEG -> "JPEG：经典通用格式，最大兼容性，体积缩减相对有限"
            }
            Text(formatTip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(defaults) }
            SectionHeader("2. 画质与压缩档位", matchedPreset?.description ?: "自定义品质值 ${defaults.quality} / 缩放 ${defaults.resizePercent}%")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompressionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = matchedPreset == preset,
                        onClick = { callbacks.onPreset(preset) },
                        enabled = enabled,
                        label = { Text(preset.label) },
                    )
                }
            }

            // HDR / Gainmap 原样保留特性卡片
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val compatibleHeic = defaults.format == OutputFormat.HEIC && defaults.heicGainmapCompatibility
                    val compatibleAvif = defaults.format == OutputFormat.AVIF && defaults.avifGainmapCompatibility
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (compatibleAvif || compatibleHeic) "HDR · 相册兼容模式" else "Gain Map · 原样保留",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        when {
                            compatibleHeic -> "保留增益图尺寸与 HDR 参数；彩色增益图采用色度采样，不是逐像素无损。"
                            compatibleAvif -> "保留增益图尺寸与 HDR 参数；彩色增益图转换存在舍入误差。"
                            defaults.format == OutputFormat.JPEG -> "复用 JPEG 原始增益图流；其它 HDR 源图推荐 HEIC / AVIF / JXL。"
                            else -> "增益图逐值保留，不缩小、不重新计算；部分相册可能无法正确显示 HDR。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                }
            }

            // === 第二层：核心保护与保存选项 ===
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SectionHeader("3. 保护与保存策略")
            SwitchRow(
                title = "仅保留更小结果",
                hint = "若压缩后体积未减小，自动保留原图，杜绝反向变大",
                checked = defaults.onlySmaller,
                onCheckedChange = callbacks.onOnlySmallerChange,
                enabled = enabled,
            )
            if (defaults.format == OutputFormat.AVIF) {
                SwitchRow(
                    title = "AVIF 相册兼容",
                    hint = "彩色增益图转换存在舍入误差；关闭后部分相册可能偏色",
                    checked = defaults.avifGainmapCompatibility,
                    onCheckedChange = callbacks.onAvifGainmapCompatibilityChange,
                    enabled = enabled,
                )
            }
            if (defaults.format == OutputFormat.HEIC) {
                SwitchRow(
                    title = "HEIC 相册兼容",
                    hint = "保留增益图尺寸与 HDR 参数，彩色增益图色度有损；关闭后部分相册可能偏色或不显示 HDR",
                    checked = defaults.heicGainmapCompatibility,
                    onCheckedChange = callbacks.onHeicGainmapCompatibilityChange,
                    enabled = enabled,
                )
            }
            if (showAutoSave) {
                SwitchRow(
                    title = "自动保存到相册",
                    hint = if (defaults.autoReplaceOriginals) "存回原相册；小窗/后台完成后回到应用自动保存" else "保存到 UltraSlim；小窗/后台完成后回到应用自动保存",
                    checked = defaults.autoSave,
                    onCheckedChange = callbacks.onAutoSaveChange,
                    enabled = enabled,
                )
            }

            // === 第三层：进阶参数折叠区 ===
            var advanced by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { advanced = !advanced },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("进阶参数与元数据", style = MaterialTheme.typography.titleSmall)
                        Text("尺寸缩放、速度档位、精确品质、EXIF 与 GPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (advanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = advanced,
                enter = expandVertically(Motion.standard()) + fadeIn(Motion.effects()),
                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SliderOption("精确画质数值", defaults.quality, 50f..100f, 0, callbacks.onQualityChange, enabled = enabled)
                    Text(
                        "数值越高画质越好、体积越大。常用 85~92 即可获得近乎无损的视觉观感。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SectionHeader("分辨率缩放", "缩小分辨率可大幅减少高像素照片的体积")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 75, 50).forEach { percent ->
                            FilterChip(
                                selected = defaults.resizePercent == percent,
                                onClick = { callbacks.onResizePercentChange(percent) },
                                enabled = enabled,
                                label = { Text("$percent%") },
                            )
                        }
                    }

                    SectionHeader("编码速度档位", "更快编码可能略微增加体积，建议先试转对比")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "画质优先", 1 to "平衡推荐", 2 to "速度优先").forEach { (level, label) ->
                            FilterChip(
                                selected = defaults.speedLevel == level,
                                onClick = { callbacks.onSpeedLevelChange(level) },
                                enabled = enabled,
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    if (defaults.format == OutputFormat.JXL) {
                        SwitchRow(
                            title = "保留 JPEG 重建能力",
                            hint = "JPEG 主图保留原始尺寸，可还原经隐私设置处理后的主图 JPEG。关闭保留位置时也会移除 XMP 与 IPTC。其他输入按常规参数编码。",
                            checked = defaults.jxlJpegReconstruction,
                            onCheckedChange = callbacks.onJxlJpegReconstructionChange,
                            enabled = enabled,
                        )
                    }

                    SwitchRow(
                        title = "保留 EXIF 信息",
                        hint = "保留拍摄器材、曝光参数、时间等",
                        checked = defaults.keepExif,
                        onCheckedChange = callbacks.onKeepExifChange,
                        enabled = enabled,
                    )
                    SwitchRow(
                        title = "保留 GPS 位置信息",
                        hint = "关闭后将擦除照片拍摄地理坐标，保护隐私",
                        checked = defaults.keepLocation,
                        onCheckedChange = callbacks.onKeepLocationChange,
                        enabled = enabled,
                    )
                    SwitchRow(
                        title = "允许纯 SDR 转换",
                        hint = "没有 Gain Map 增益图的普通照片也执行转码",
                        checked = defaults.allowSdr,
                        onCheckedChange = callbacks.onAllowSdrChange,
                        enabled = enabled,
                    )
                }
            }

            // 本次参数与全局默认参数快捷同步条
            if (callbacks.onSaveAsDefault != null || callbacks.onResetToDefault != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DefaultOptionsActions(
                    onSaveAsDefault = callbacks.onSaveAsDefault,
                    onResetToDefault = callbacks.onResetToDefault,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun SliderOption(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(value) }
    // 仅跟随外部真正变化的值；拖动中预设等其他来源的更新不打断拇指位置，
    // 松手后以待提交值为准，避免状态回流前的一两帧回跳。
    if (value != committed && !dragging) {
        committed = value
        sliderValue = value.toFloat()
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${sliderValue.roundToInt()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val sliderState = rememberSliderState(value = sliderValue, steps = steps, trackRange = valueRange)
        androidx.compose.runtime.SideEffect { sliderState.value = sliderValue }
        Slider(
            state = sliderState,
            onValueChange = { dragging = true; sliderValue = it },
            enabled = enabled,
            onValueChangeFinished = {
                dragging = false
                committed = sliderValue.roundToInt()
                onChange(committed)
            },
        )
    }
}

/** Long edge for a 56.dp row thumbnail: two device pixels, clamped for the native decoder. */
@Composable
fun rememberThumbMaxDim(): Int {
    val density = LocalDensity.current
    return remember(density) {
        with(density) { (56.dp.toPx() * 2f).roundToInt() }.coerceIn(64, 512)
    }
}

/**
 * AsyncImage with a native-decode fallback: when the platform decoder cannot
 * handle the file (e.g. ISO 21496-1 gain map HEIC), the primary SDR rendition
 * is decoded by the bundled native stack and shown as JPEG bytes instead.
 * [maxDim] is the long edge requested from that fallback. List rows should pass
 * [rememberThumbMaxDim]; detail keeps the default.
 */
private val failedPlatformPreviewUris = android.util.LruCache<String, Boolean>(256)

@Composable
fun UltraAsyncImage(
    uriString: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    maxDim: Int = 1280,
    forceSdr: Boolean = false,
    preferNative: Boolean = false,
    enableHdr: Boolean = false,
) {
    val context = LocalContext.current
    val requestHdr = enableHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    var useNative by remember(uriString, forceSdr, preferNative, requestHdr) {
        mutableStateOf(
            requestHdr || forceSdr || preferNative ||
                uriString.endsWith(".jxl", ignoreCase = true) ||
                failedPlatformPreviewUris.get(uriString) == true
        )
    }
    if (!useNative) {
        val request = remember(context, uriString) {
            ImageRequest.Builder(context)
                .data(imageModelOf(uriString))
                .crossfade(Motion.Fast)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            onError = {
                failedPlatformPreviewUris.put(uriString, true)
                useNative = true
            },
        )
    } else {
        val immediate = remember(uriString, maxDim, requestHdr) {
            if (requestHdr) null else PreviewCache.peek(uriString, maxDim)
        }
        val result = key(uriString, maxDim, requestHdr) {
            produceState<NativeImage?>(immediate?.let { NativeImage(it, true) }) {
                value = withContext(Dispatchers.IO) {
                    val bmp = if (requestHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        PreviewCache.loadHdr(context, uriString, maxDim)
                    } else {
                        PreviewCache.load(context, uriString, maxDim)
                    }
                    NativeImage(bmp, true)
                }
            }.value
        }
        // 上一代解码结果跨 maxDim/HDR 切换保留：放大或切 HDR 触发重解码时旧图仍在屏，
        // 不闪回转圈；只有首屏无图才显示 spinner。
        var lastBitmap by remember(uriString) { mutableStateOf<Bitmap?>(immediate) }
        if (result?.bitmap != null) lastBitmap = result.bitmap
        val shown = if (result?.finished == true) result.bitmap else lastBitmap
        val state = when {
            shown != null -> NativeDecoded.Ready(shown)
            result?.finished == true -> NativeDecoded.Failed
            else -> NativeDecoded.Loading
        }
        Crossfade(targetState = state, animationSpec = tween(Motion.Fast), label = "nativeImage") { s ->
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                when (s) {
                    is NativeDecoded.Ready -> Image(
                        bitmap = remember(s.bitmap) { s.bitmap.asImageBitmap() },
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize(),
                    )
                    NativeDecoded.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    NativeDecoded.Failed -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        ImageFallbackPlaceholder()
                        // 小缩略图放不下两行说明，只给 BrokenImage 图标。
                        if (maxWidth >= 220.dp && maxHeight >= 140.dp) {
                            Text("图片不可访问\n请检查文件或重新授权", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}

private sealed interface NativeDecoded {
    data class Ready(val bitmap: Bitmap) : NativeDecoded
    data object Loading : NativeDecoded
    data object Failed : NativeDecoded
}

private data class NativeImage(val bitmap: Bitmap?, val finished: Boolean)

@Composable
private fun ImageFallbackPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * 批次时间戳分组头部：清晰展示本次导入/转换的时间（今天 17:01 / 刚刚 / 昨天等）、张数与省下体积，并支持批次级快捷操作与折叠/展开。
 */
@Composable
fun BatchGroupHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    savedBytes: Long? = null,
    statusSummary: String? = null,
    collapsible: Boolean = false,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        animationSpec = Motion.accordion(),
        label = "collapseRotation",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (collapsible && onToggleCollapse != null) {
                    Modifier.clickable(onClick = onToggleCollapse)
                } else Modifier
            )
            .padding(horizontal = if (collapsible) 6.dp else 0.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (collapsible) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (collapsed) "展开" else "折叠",
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(rotation),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val sub = buildString {
                    append("$count 张照片")
                    if (savedBytes != null && savedBytes > 0) {
                        append(" · 省下 ${formatBytes(savedBytes)}")
                    }
                    if (!statusSummary.isNullOrBlank()) {
                        append(" · $statusSummary")
                    }
                    if (collapsible && collapsed) {
                        append(" · 点击展开")
                    }
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}

