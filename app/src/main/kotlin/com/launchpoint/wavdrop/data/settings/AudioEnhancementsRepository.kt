package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class AudioEnhancementsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val _capabilities = MutableStateFlow<EqualizerCapabilities?>(null)
    val capabilities: StateFlow<EqualizerCapabilities?> = _capabilities.asStateFlow()

    fun updateCapabilities(caps: EqualizerCapabilities) {
        _capabilities.value = caps
    }

    val settings: Flow<AudioEnhancementSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AudioEnhancementSettings(
                eqEnabled             = prefs[EQ_ENABLED] ?: false,
                eqPresetType          = parseEqPresetType(prefs[EQ_PRESET_TYPE]),
                eqPlatformPresetIndex = prefs[EQ_PLATFORM_PRESET_INDEX],
                eqCustomBandLevels    = parseBandLevels(prefs[EQ_CUSTOM_BAND_LEVELS]),
                eqWavdropPresetId     = parseWavdropPresetId(prefs[EQ_WAVDROP_PRESET_ID]),
            )
        }
        .distinctUntilChanged()

    suspend fun setEqEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[EQ_ENABLED] = enabled }
    }

    suspend fun setEqFlatPreset() {
        dataStore.edit { prefs ->
            prefs[EQ_PRESET_TYPE] = EqPresetType.FLAT.name
            prefs.remove(EQ_PLATFORM_PRESET_INDEX)
            prefs.remove(EQ_WAVDROP_PRESET_ID)
            // eqCustomBandLevels intentionally preserved for round-trip back to Custom
        }
    }

    suspend fun setEqPlatformPreset(index: Int) {
        dataStore.edit { prefs ->
            prefs[EQ_PRESET_TYPE]        = EqPresetType.PLATFORM.name
            prefs[EQ_PLATFORM_PRESET_INDEX] = index
            prefs.remove(EQ_WAVDROP_PRESET_ID)
            // eqCustomBandLevels intentionally preserved for round-trip back to Custom
        }
    }

    // Switches to Custom preset type. If the stored band levels are absent or sized
    // differently from the current device band count, seeds them with flat zeros so
    // the controller never hits a size-mismatch fallback on the next apply.
    suspend fun setEqCustomPreset(bandCount: Int) {
        dataStore.edit { prefs ->
            val current = parseBandLevels(prefs[EQ_CUSTOM_BAND_LEVELS])
            if (current.size != bandCount) {
                prefs[EQ_CUSTOM_BAND_LEVELS] = List(bandCount) { 0 }.joinToString(",")
            }
            prefs[EQ_PRESET_TYPE] = EqPresetType.CUSTOM.name
            prefs.remove(EQ_WAVDROP_PRESET_ID)
            prefs.remove(EQ_PLATFORM_PRESET_INDEX)
        }
    }

    suspend fun setEqCustomBandLevels(levels: List<Int>) {
        dataStore.edit { prefs ->
            prefs[EQ_PRESET_TYPE]     = EqPresetType.CUSTOM.name
            prefs[EQ_CUSTOM_BAND_LEVELS] = levels.joinToString(",")
            prefs.remove(EQ_WAVDROP_PRESET_ID)
            prefs.remove(EQ_PLATFORM_PRESET_INDEX)
        }
    }

    // One-time startup cleanup: removes stale EQ keys left by earlier builds.
    // Idempotent after the first run — guarded by EQ_PRESET_STATE_NORMALIZED_V1.
    // Safe to call concurrently with settings collection; DataStore serializes edits.
    suspend fun normalizeEqPresetStateOnce() {
        // Fast path: if already normalized, skip the edit lock entirely.
        if (dataStore.data.first()[EQ_PRESET_STATE_NORMALIZED_V1] == true) return

        dataStore.edit { prefs ->
            // Re-check inside the atomic edit block to handle unlikely concurrent calls.
            if (prefs[EQ_PRESET_STATE_NORMALIZED_V1] == true) return@edit

            val rawType   = prefs[EQ_PRESET_TYPE]
            val presetType = parseEqPresetType(rawType)

            // If the stored type string is non-null but unrecognised, write FLAT so
            // the stored value matches what the parser already returns.
            if (rawType != null && runCatching { EqPresetType.valueOf(rawType) }.isFailure) {
                prefs[EQ_PRESET_TYPE] = EqPresetType.FLAT.name
            }

            when (presetType) {
                EqPresetType.FLAT -> {
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    prefs.remove(EQ_WAVDROP_PRESET_ID)
                }
                EqPresetType.PLATFORM -> {
                    prefs.remove(EQ_WAVDROP_PRESET_ID)
                    // eq_platform_preset_index is the active key — leave it
                }
                EqPresetType.CUSTOM -> {
                    prefs.remove(EQ_WAVDROP_PRESET_ID)
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    // eq_custom_band_levels preserved
                }
                EqPresetType.WAVDROP_PRESET -> {
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    val id = parseWavdropPresetId(prefs[EQ_WAVDROP_PRESET_ID])
                    val validInCatalog = id != null && WavdropPresetCatalog.findById(id) != null
                    if (!validInCatalog) {
                        // Unknown or blank Wavdrop id — fall back to FLAT safely.
                        prefs[EQ_PRESET_TYPE] = EqPresetType.FLAT.name
                        prefs.remove(EQ_WAVDROP_PRESET_ID)
                    }
                    // eq_custom_band_levels preserved
                }
            }

            prefs[EQ_PRESET_STATE_NORMALIZED_V1] = true
        }
    }

    // Switches to a Wavdrop-owned preset. Custom band levels are intentionally
    // preserved so they survive a round-trip through any preset type.
    suspend fun setEqWavdropPreset(id: String) {
        dataStore.edit { prefs ->
            prefs[EQ_PRESET_TYPE]       = EqPresetType.WAVDROP_PRESET.name
            prefs[EQ_WAVDROP_PRESET_ID] = id
            prefs.remove(EQ_PLATFORM_PRESET_INDEX)
            // eqCustomBandLevels intentionally preserved for round-trip back to Custom
        }
    }

    companion object {
        private val EQ_ENABLED                    = booleanPreferencesKey("eq_enabled")
        private val EQ_PRESET_TYPE                = stringPreferencesKey("eq_preset_type")
        private val EQ_PLATFORM_PRESET_INDEX      = intPreferencesKey("eq_platform_preset_index")
        private val EQ_CUSTOM_BAND_LEVELS         = stringPreferencesKey("eq_custom_band_levels")
        private val EQ_WAVDROP_PRESET_ID          = stringPreferencesKey("eq_wavdrop_preset_id")
        private val EQ_PRESET_STATE_NORMALIZED_V1 = booleanPreferencesKey("eq_preset_state_normalized_v1")

        // Extracted for unit-testability — no DataStore dependency.
        internal fun parseEqPresetType(raw: String?): EqPresetType =
            raw?.let { runCatching { EqPresetType.valueOf(it) }.getOrNull() } ?: EqPresetType.FLAT

        internal fun parseBandLevels(raw: String?): List<Int> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",").mapNotNull { runCatching { it.trim().toInt() }.getOrNull() }
        }

        internal fun parseWavdropPresetId(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
