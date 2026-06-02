package com.launchpoint.wavdrop.data.settings

data class HomeLayoutSettings(
    val visibleSections: Set<HomeSectionId> = HomeSectionId.ALL,
)
