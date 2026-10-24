package app.ultraslim.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.ultraslim.model.ConversionSettings
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

// preferencesDataStore 委托不支持 corruptionHandler。设置文件损坏时按空配置恢复，
// 否则 collectors 会停在假默认值且无任何提示。路径与官方委托一致，保证旧安装可读。
private object SettingsStoreHolder {
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { File(context.filesDir, "datastore/settings.preferences_pb") }.also { instance = it }
    }
}

private fun Context.settingsStore(): DataStore<Preferences> = SettingsStoreHolder.get(this)

enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** Persisted conversion defaults (ConversionSettings + 自动保存到相册). */
data class ConversionDefaults(
    val format: OutputFormat = OutputFormat.AVIF,
    val quality: Int = 92,
    val gainmapQuality: Int = 60,
    val gainmapScale: Int = 4,
    val multiChannelGainmap: Boolean = true,
    val keepExif: Boolean = true,
    val allowSdr: Boolean = true,
    val autoSave: Boolean = true,
    val outputTree: String = "",
    val outputTreeLabel: String = "",
    val retainSourceName: Boolean = false,
    val autoReplaceOriginals: Boolean = false,
    val onlySmaller: Boolean = true,
    val keepLocation: Boolean = true,
    val speedLevel: Int = 1,
    val resizePercent: Int = 100,
    val avifGainmapCompatibility: Boolean = true,
    val heicGainmapCompatibility: Boolean = true,
) {
    fun toConversionSettings(): ConversionSettings =
        ConversionSettings(
            format = format,
            quality = quality,
            gainmapQuality = gainmapQuality,
            gainmapScale = gainmapScale,
            multiChannelGainmap = multiChannelGainmap,
            keepExif = keepExif,
            keepLocation = keepLocation,
            allowSdr = allowSdr,
            speedLevel = speedLevel,
            resizePercent = resizePercent,
            avifGainmapCompatibility = avifGainmapCompatibility,
            heicGainmapCompatibility = heicGainmapCompatibility,
        )
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val OUTPUT_FORMAT = stringPreferencesKey("output_format")
        val QUALITY = intPreferencesKey("quality")
        val GAINMAP_QUALITY = intPreferencesKey("gainmap_quality")
        val GAINMAP_SCALE = intPreferencesKey("gainmap_scale")
        val MULTI_CHANNEL = booleanPreferencesKey("multi_channel_gainmap")
        val KEEP_EXIF = booleanPreferencesKey("keep_exif")
        val ALLOW_SDR = booleanPreferencesKey("allow_sdr_transcode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_REPLACE = booleanPreferencesKey("auto_replace_originals")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val OUTPUT_TREE = stringPreferencesKey("export_tree")
        val OUTPUT_TREE_LABEL = stringPreferencesKey("export_tree_label")
        val RETAIN_NAME = booleanPreferencesKey("retain_source_name")
        val ONLY_SMALLER = booleanPreferencesKey("only_smaller")
        val KEEP_LOCATION = booleanPreferencesKey("keep_location")
        val SPEED_LEVEL = intPreferencesKey("speed_level")
        val RESIZE_PERCENT = intPreferencesKey("resize_percent")
        val AVIF_GAINMAP_COMPATIBILITY = booleanPreferencesKey("avif_gainmap_compatibility")
        val HEIC_GAINMAP_COMPATIBILITY = booleanPreferencesKey("heic_gainmap_compatibility")
    }

    val defaults: Flow<ConversionDefaults> = context.settingsStore().data.map { p ->
        ConversionDefaults(
            format = OutputFormat.parse(p[Keys.OUTPUT_FORMAT]),
            quality = p[Keys.QUALITY] ?: 92,
            gainmapQuality = p[Keys.GAINMAP_QUALITY] ?: 60,
            gainmapScale = p[Keys.GAINMAP_SCALE] ?: 4,
            multiChannelGainmap = p[Keys.MULTI_CHANNEL] ?: true,
            keepExif = p[Keys.KEEP_EXIF] ?: true,
            allowSdr = p[Keys.ALLOW_SDR] ?: true,
            autoSave = p[Keys.AUTO_SAVE] ?: true,
            outputTree = p[Keys.OUTPUT_TREE].orEmpty(),
            outputTreeLabel = p[Keys.OUTPUT_TREE_LABEL].orEmpty(),
            retainSourceName = p[Keys.RETAIN_NAME] ?: false,
            autoReplaceOriginals = (p[Keys.AUTO_REPLACE] ?: false) && (p[Keys.AUTO_SAVE] ?: true),
            onlySmaller = p[Keys.ONLY_SMALLER] ?: true,
            keepLocation = p[Keys.KEEP_LOCATION] ?: true,
            speedLevel = p[Keys.SPEED_LEVEL] ?: 1,
            resizePercent = p[Keys.RESIZE_PERCENT] ?: 100,
            avifGainmapCompatibility = p[Keys.AVIF_GAINMAP_COMPATIBILITY] ?: true,
            heicGainmapCompatibility = p[Keys.HEIC_GAINMAP_COMPATIBILITY] ?: true,
        )
    }

    val theme: Flow<ThemeSettings> = context.settingsStore().data.map { p ->
        ThemeSettings(
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
        )
    }

    suspend fun setOutputFormat(format: OutputFormat) {
        context.settingsStore().edit { it[Keys.OUTPUT_FORMAT] = format.extension }
    }

    suspend fun setQuality(quality: Int) {
        context.settingsStore().edit { it[Keys.QUALITY] = quality.coerceIn(50, 100) }
    }

    suspend fun setGainmapQuality(quality: Int) {
        context.settingsStore().edit { it[Keys.GAINMAP_QUALITY] = quality.coerceIn(30, 90) }
    }

    suspend fun setGainmapScale(scale: Int) {
        context.settingsStore().edit { it[Keys.GAINMAP_SCALE] = scale }
    }

    suspend fun setMultiChannelGainmap(enabled: Boolean) {
        context.settingsStore().edit { it[Keys.MULTI_CHANNEL] = enabled }
    }

    suspend fun setKeepExif(keep: Boolean) {
        context.settingsStore().edit { it[Keys.KEEP_EXIF] = keep }
    }

    suspend fun setAllowSdr(allow: Boolean) {
        context.settingsStore().edit { it[Keys.ALLOW_SDR] = allow }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore().edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore().edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoSave(enabled: Boolean) {
        context.settingsStore().edit {
            it[Keys.AUTO_SAVE] = enabled
            if (!enabled) it[Keys.AUTO_REPLACE] = false
        }
    }

    suspend fun setAutoReplaceOriginals(enabled: Boolean) {
        context.settingsStore().edit {
            it[Keys.AUTO_REPLACE] = enabled
            if (enabled) it[Keys.AUTO_SAVE] = true
        }
    }

    suspend fun setOutputTree(uri: String, label: String) {
        context.settingsStore().edit { it[Keys.OUTPUT_TREE] = uri; it[Keys.OUTPUT_TREE_LABEL] = label }
    }

    suspend fun setRetainSourceName(enabled: Boolean) {
        context.settingsStore().edit { it[Keys.RETAIN_NAME] = enabled }
    }

    suspend fun setOnlySmaller(value: Boolean) { context.settingsStore().edit { it[Keys.ONLY_SMALLER] = value } }
    suspend fun setKeepLocation(value: Boolean) { context.settingsStore().edit { it[Keys.KEEP_LOCATION] = value } }

    suspend fun setSpeedLevel(level: Int) {
        context.settingsStore().edit { it[Keys.SPEED_LEVEL] = level.coerceIn(0, 2) }
    }

    suspend fun setResizePercent(percent: Int) {
        context.settingsStore().edit { it[Keys.RESIZE_PERCENT] = percent.coerceIn(25, 100) }
    }

    suspend fun setHeicGainmapCompatibility(enabled: Boolean) {
        context.settingsStore().edit { it[Keys.HEIC_GAINMAP_COMPATIBILITY] = enabled }
    }

    suspend fun setAvifGainmapCompatibility(enabled: Boolean) {
        context.settingsStore().edit { it[Keys.AVIF_GAINMAP_COMPATIBILITY] = enabled }
    }

    suspend fun setDefaults(d: ConversionDefaults) {
        context.settingsStore().edit { p ->
            p[Keys.OUTPUT_FORMAT] = d.format.extension
            p[Keys.QUALITY] = d.quality.coerceIn(50, 100)
            p[Keys.GAINMAP_QUALITY] = d.gainmapQuality.coerceIn(30, 90)
            p[Keys.GAINMAP_SCALE] = d.gainmapScale
            p[Keys.MULTI_CHANNEL] = d.multiChannelGainmap
            p[Keys.KEEP_EXIF] = d.keepExif
            p[Keys.ALLOW_SDR] = d.allowSdr
            p[Keys.AUTO_SAVE] = d.autoSave
            p[Keys.OUTPUT_TREE] = d.outputTree
            p[Keys.OUTPUT_TREE_LABEL] = d.outputTreeLabel
            p[Keys.RETAIN_NAME] = d.retainSourceName
            p[Keys.AUTO_REPLACE] = d.autoReplaceOriginals && d.autoSave
            p[Keys.ONLY_SMALLER] = d.onlySmaller
            p[Keys.KEEP_LOCATION] = d.keepLocation
            p[Keys.SPEED_LEVEL] = d.speedLevel.coerceIn(0, 2)
            p[Keys.RESIZE_PERCENT] = d.resizePercent.coerceIn(25, 100)
            p[Keys.AVIF_GAINMAP_COMPATIBILITY] = d.avifGainmapCompatibility
            p[Keys.HEIC_GAINMAP_COMPATIBILITY] = d.heicGainmapCompatibility
        }
    }
}

fun ConversionDefaults.encode(): String = org.json.JSONObject().apply {
    put("format", format.extension); put("quality", quality); put("gainmapQuality", gainmapQuality)
    put("gainmapScale", gainmapScale); put("multiChannel", multiChannelGainmap); put("keepExif", keepExif)
    put("allowSdr", allowSdr); put("autoSave", autoSave); put("speed", speedLevel); put("resize", resizePercent)
    put("autoReplaceOriginals", autoReplaceOriginals && autoSave)
    put("outputTree", outputTree); put("outputTreeLabel", outputTreeLabel); put("retainSourceName", retainSourceName)
    put("onlySmaller", onlySmaller); put("keepLocation", keepLocation)
    put("avifGainmapCompatibility", avifGainmapCompatibility)
    put("heicGainmapCompatibility", heicGainmapCompatibility)
}.toString()

fun decodeDefaults(value: String): ConversionDefaults {
    val j = org.json.JSONObject(value)
    val d = ConversionDefaults()
    // opt 系列 + 数据类默认值：旧版本快照缺新字段时不致命，缺老字段也按默认解析，
    // 避免批次在 worker 中静默死亡。仅两个兼容开关例外：旧快照缺字段按 false（像素精确语义）。
    return ConversionDefaults(format = OutputFormat.parse(j.optString("format", d.format.name)),
        quality = j.optInt("quality", d.quality), gainmapQuality = j.optInt("gainmapQuality", d.gainmapQuality),
        gainmapScale = j.optInt("gainmapScale", d.gainmapScale),
        multiChannelGainmap = j.optBoolean("multiChannel", d.multiChannelGainmap), keepExif = j.optBoolean("keepExif", d.keepExif),
        allowSdr = j.optBoolean("allowSdr", d.allowSdr), autoSave = j.optBoolean("autoSave", d.autoSave),
        outputTree = j.optString("outputTree", ""), outputTreeLabel = j.optString("outputTreeLabel", ""),
        retainSourceName = j.optBoolean("retainSourceName", false),
        autoReplaceOriginals = j.optBoolean("autoReplaceOriginals", false) && j.optBoolean("autoSave", true),
        speedLevel = j.optInt("speed", d.speedLevel), resizePercent = j.optInt("resize", d.resizePercent),
        onlySmaller = j.optBoolean("onlySmaller", d.onlySmaller), keepLocation = j.optBoolean("keepLocation", d.keepLocation),
        // Old queued jobs and history used pixel-exact encoding. Do not relabel them.
        avifGainmapCompatibility = j.optBoolean("avifGainmapCompatibility", false),
        heicGainmapCompatibility = j.optBoolean("heicGainmapCompatibility", false))
}

fun ConversionDefaults.summary(): String = "${format.label} · 编码质量 $quality · ${if (resizePercent == 100) "原始尺寸" else "尺寸 $resizePercent%"} · ${if ((format == OutputFormat.AVIF && avifGainmapCompatibility) || (format == OutputFormat.HEIC && heicGainmapCompatibility)) "HDR 相册兼容" else "Gain Map 原样保留"} · ${if (autoSave) "自动保存" else "手动保存"}${if (autoReplaceOriginals) " · 校验后自动替换" else ""}"
