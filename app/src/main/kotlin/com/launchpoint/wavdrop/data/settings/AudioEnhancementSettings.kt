package com.launchpoint.wavdrop.data.settings

data class AudioEnhancementSettings(
    val eqEnabled: Boolean = false,
    val eqPresetType: EqPresetType = EqPresetType.FLAT,
    val eqPlatformPresetIndex: Int? = null,
    val eqCustomBandLevels: List<Int> = emptyList(),
    val eqWavdropPresetId: String? = null,
)

enum class EqPresetType { FLAT, WAVDROP_PRESET, PLATFORM, CUSTOM }
