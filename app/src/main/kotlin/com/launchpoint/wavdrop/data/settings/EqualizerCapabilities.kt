package com.launchpoint.wavdrop.data.settings

data class EqualizerCapabilities(
    val bandCount: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val centerFrequenciesHz: List<Int>,
    val platformPresets: List<EqualizerPlatformPreset>,
)

data class EqualizerPlatformPreset(
    val index: Short,
    val name: String,
)
