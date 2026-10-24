package app.ultraslim.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.SelectedOutput
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.ThemeMode
import app.ultraslim.data.ThemeSettings
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.components.CompressionPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: UltraSlimApp) : ViewModel() {

    private val repo = app.settingsRepository

    val defaults: StateFlow<ConversionDefaults> = repo.defaults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversionDefaults())

    val theme: StateFlow<ThemeSettings> = repo.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repo.setDynamicColor(enabled) }

    fun setFormat(format: OutputFormat) = viewModelScope.launch { repo.setOutputFormat(format) }

    fun setQuality(quality: Int) = viewModelScope.launch { repo.setQuality(quality) }

    fun setGainmapQuality(quality: Int) = viewModelScope.launch { repo.setGainmapQuality(quality) }

    fun setGainmapScale(scale: Int) = viewModelScope.launch { repo.setGainmapScale(scale) }

    fun setMultiChannel(enabled: Boolean) = viewModelScope.launch { repo.setMultiChannelGainmap(enabled) }

    fun setKeepExif(keep: Boolean) = viewModelScope.launch { repo.setKeepExif(keep) }

    fun setAllowSdr(allow: Boolean) = viewModelScope.launch { repo.setAllowSdr(allow) }

    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { repo.setAutoSave(enabled) }

    fun setRetainSourceName(enabled: Boolean) = viewModelScope.launch { repo.setRetainSourceName(enabled) }

    suspend fun selectOutputTree(uri: android.net.Uri?) {
        val label = if (uri == null) "" else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SelectedOutput.authorize(app, uri)
        }
        repo.setOutputTree(uri?.toString().orEmpty(), label)
    }

    fun setAutoReplaceOriginals(v: Boolean) = viewModelScope.launch { repo.setAutoReplaceOriginals(v) }

    fun setOnlySmaller(v: Boolean) = viewModelScope.launch { repo.setOnlySmaller(v) }
    fun setKeepLocation(v: Boolean) = viewModelScope.launch { repo.setKeepLocation(v) }
    fun setAvifGainmapCompatibility(v: Boolean) = viewModelScope.launch { repo.setAvifGainmapCompatibility(v) }
    fun setHeicGainmapCompatibility(v: Boolean) = viewModelScope.launch { repo.setHeicGainmapCompatibility(v) }

    fun setSpeedLevel(level: Int) = viewModelScope.launch { repo.setSpeedLevel(level) }

    fun setResizePercent(percent: Int) = viewModelScope.launch { repo.setResizePercent(percent) }

    fun applyPreset(preset: CompressionPreset) = viewModelScope.launch {
        repo.setQuality(preset.quality)
        repo.setGainmapQuality(preset.gainmapQuality)
        repo.setGainmapScale(preset.gainmapScale)
        repo.setResizePercent(preset.resizePercent)
        repo.setSpeedLevel(preset.speedLevel)
    }
}
