package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.SmartCollectionType

data class HomeLayoutSettings(
    val visibleSections: Set<HomeSectionId> = HomeSectionId.ALL,
    /**
     * The exactly-three Smart Collections shown on Home, in display order (WU-01).
     * Presentation preference only; empty here means "not yet configured" and callers
     * fall back to [HomeSmartCollectionSelection.DEFAULT].
     */
    val homeSmartCollections: List<SmartCollectionType> = HomeSmartCollectionSelection.DEFAULT,
)
