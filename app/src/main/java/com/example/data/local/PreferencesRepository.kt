package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.DefaultSoundscapes
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pulseDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulsewave_prefs")

data class UserAppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val offlineMode: Boolean = false,
    val dynamicAlbumGlow: Boolean = true,
    val activeSoundscapeId: String = "obsidian_studio",
    val eqEnabled: Boolean = true,
    val eqBandLevels: List<Int> = DefaultSoundscapes.presets.first().bandLevelsMillibel,
    val eqBassBoost: Int = DefaultSoundscapes.presets.first().bassBoostStrength,
    val eqReverbPreset: Int = DefaultSoundscapes.presets.first().reverbPreset,
    val highResStreaming: Boolean = true,
    val crossfadeSeconds: Int = 4
)

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        val DYNAMIC_GLOW = booleanPreferencesKey("dynamic_glow")
        val ACTIVE_SOUNDSCAPE = stringPreferencesKey("active_soundscape")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BANDS_CSV = stringPreferencesKey("eq_bands_csv")
        val EQ_BASS_BOOST = intPreferencesKey("eq_bass_boost")
        val EQ_REVERB_PRESET = intPreferencesKey("eq_reverb_preset")
        val HIGH_RES_STREAMING = booleanPreferencesKey("high_res_streaming")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
    }

    val settingsFlow: Flow<UserAppSettings> = context.pulseDataStore.data.map { prefs ->
        val mode = ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.DARK
        val defaultPreset = DefaultSoundscapes.presets.first()
        val parsedBands = prefs[Keys.EQ_BANDS_CSV]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull()?.coerceIn(-1500, 1500) }
            ?.takeIf { it.size == 5 }
            ?: defaultPreset.bandLevelsMillibel

        UserAppSettings(
            themeMode = mode,
            offlineMode = prefs[Keys.OFFLINE_MODE] ?: false,
            dynamicAlbumGlow = prefs[Keys.DYNAMIC_GLOW] ?: true,
            activeSoundscapeId = prefs[Keys.ACTIVE_SOUNDSCAPE] ?: defaultPreset.id,
            eqEnabled = prefs[Keys.EQ_ENABLED] ?: true,
            eqBandLevels = parsedBands,
            eqBassBoost = (prefs[Keys.EQ_BASS_BOOST] ?: defaultPreset.bassBoostStrength).coerceIn(0, 1000),
            eqReverbPreset = (prefs[Keys.EQ_REVERB_PRESET] ?: defaultPreset.reverbPreset).coerceIn(0, 6),
            highResStreaming = prefs[Keys.HIGH_RES_STREAMING] ?: true,
            crossfadeSeconds = (prefs[Keys.CROSSFADE_SECONDS] ?: 4).coerceIn(0, 12)
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.pulseDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.pulseDataStore.edit { it[Keys.OFFLINE_MODE] = enabled }
    }

    suspend fun setDynamicAlbumGlow(enabled: Boolean) {
        context.pulseDataStore.edit { it[Keys.DYNAMIC_GLOW] = enabled }
    }

    suspend fun setActiveSoundscapeId(id: String) {
        context.pulseDataStore.edit { it[Keys.ACTIVE_SOUNDSCAPE] = id }
    }

    suspend fun setEqualizerState(
        enabled: Boolean,
        soundscapeId: String,
        bandLevels: List<Int>,
        bassBoost: Int,
        reverbPreset: Int
    ) {
        val safeBands = (0 until 5).map { idx ->
            (bandLevels.getOrNull(idx) ?: 0).coerceIn(-1500, 1500)
        }
        context.pulseDataStore.edit { prefs ->
            prefs[Keys.EQ_ENABLED] = enabled
            prefs[Keys.ACTIVE_SOUNDSCAPE] = soundscapeId
            prefs[Keys.EQ_BANDS_CSV] = safeBands.joinToString(",")
            prefs[Keys.EQ_BASS_BOOST] = bassBoost.coerceIn(0, 1000)
            prefs[Keys.EQ_REVERB_PRESET] = reverbPreset.coerceIn(0, 6)
        }
    }

    suspend fun setHighResStreaming(enabled: Boolean) {
        context.pulseDataStore.edit { it[Keys.HIGH_RES_STREAMING] = enabled }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.pulseDataStore.edit { it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(0, 12) }
    }
}
