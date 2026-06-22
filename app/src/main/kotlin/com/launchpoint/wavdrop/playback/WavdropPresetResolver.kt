package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.settings.WavdropPresetCatalog

// Resolves a Wavdrop preset by id and clamps its band levels to the device range.
// Returns null when the id is missing, not found in the catalog, or the catalog's
// band count doesn't match the device — the caller should fall back to flat.
internal fun resolveWavdropBandLevels(
    id: String?,
    bandCount: Int,
    minLevelMb: Int,
    maxLevelMb: Int,
): List<Int>? {
    val preset = WavdropPresetCatalog.findById(id) ?: return null
    if (preset.bandLevels.size != bandCount) return null
    return preset.bandLevels.map { it.coerceIn(minLevelMb, maxLevelMb) }
}
