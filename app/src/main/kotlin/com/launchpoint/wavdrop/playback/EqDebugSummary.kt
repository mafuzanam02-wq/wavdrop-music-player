package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.settings.AudioEnhancementSettings
import com.launchpoint.wavdrop.data.settings.EqPresetType
import com.launchpoint.wavdrop.data.settings.WavdropPresetCatalog

// Builds the source-specific debug summary that appears in the [apply] EQ log line.
// Pure function — no Android or DataStore dependencies — so it can be unit-tested directly.
//
// platformName: name string looked up from the live platformPresets list (null when unavailable)
// platformReadback: band levels read back from Equalizer immediately after usePreset() (null on release builds or on error)
internal fun buildEqApplyDebugSummary(
    settings: AudioEnhancementSettings,
    bandCount: Int,
    platformName: String? = null,
    platformReadback: List<Int>? = null,
): String = when (settings.eqPresetType) {
    EqPresetType.FLAT ->
        "preset=FLAT activeLevels=${List(bandCount) { 0 }}"

    EqPresetType.WAVDROP_PRESET -> {
        val id     = settings.eqWavdropPresetId
        val preset = WavdropPresetCatalog.findById(id)
        when {
            preset == null ->
                "preset=WAVDROP_PRESET wavdropId=$id activeLevels=unavailable(id_not_in_catalog→flat)"
            preset.bandLevels.size != bandCount ->
                "preset=WAVDROP_PRESET wavdropId=${preset.id} activeLevels=unavailable(band_mismatch→flat)"
            else ->
                "preset=WAVDROP_PRESET wavdropId=${preset.id} wavdropName=${preset.displayName} activeLevels=${preset.bandLevels}"
        }
    }

    EqPresetType.PLATFORM -> {
        val index = settings.eqPlatformPresetIndex
        val name  = platformName ?: "unknown"
        when {
            index == null ->
                "preset=PLATFORM platformIndex=null activeLevels=unavailable(invalid_index→flat)"
            platformReadback != null ->
                "preset=PLATFORM platformIndex=$index platformName=$name readbackLevels=$platformReadback"
            else ->
                "preset=PLATFORM platformIndex=$index platformName=$name readbackLevels=unavailable"
        }
    }

    EqPresetType.CUSTOM -> {
        val levels = settings.eqCustomBandLevels
        if (levels.size == bandCount) {
            "preset=CUSTOM activeLevels=$levels"
        } else {
            "preset=CUSTOM activeLevels=unavailable(size_mismatch→flat)"
        }
    }
}
