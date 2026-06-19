package com.launchpoint.wavdrop.data.settings

object HomeLayoutSettingsRules {

    val ALWAYS_VISIBLE_SECTIONS: Set<HomeSectionId> = setOf(HomeSectionId.LIBRARY_SHORTCUT)

    /**
     * Settings that truthfully map to content currently rendered by Home.
     * RECENTLY_PLAYED represents the combined Listening Activity control.
     */
    val EXPOSED_SECTION_IDS: List<HomeSectionId> = listOf(
        HomeSectionId.CONTINUE_LISTENING,
        HomeSectionId.RECENTLY_PLAYED,
        HomeSectionId.SMART_COLLECTIONS,
        HomeSectionId.WRAPPED,
    )

    fun withSectionVisible(
        settings: HomeLayoutSettings,
        id: HomeSectionId,
        visible: Boolean,
    ): HomeLayoutSettings {
        if (id in ALWAYS_VISIBLE_SECTIONS) return settings
        val affectedIds = when (id) {
            HomeSectionId.RECENTLY_PLAYED,
            HomeSectionId.MOST_PLAYED,
            -> setOf(HomeSectionId.RECENTLY_PLAYED, HomeSectionId.MOST_PLAYED)
            else -> setOf(id)
        }
        val updated = if (visible) {
            settings.visibleSections + affectedIds
        } else {
            settings.visibleSections - affectedIds
        }
        return settings.copy(visibleSections = updated + ALWAYS_VISIBLE_SECTIONS)
    }

    fun normalizeVisibleSections(sections: Set<HomeSectionId>): Set<HomeSectionId> {
        val normalized = sections + ALWAYS_VISIBLE_SECTIONS
        val listeningActivityEnabled =
            HomeSectionId.RECENTLY_PLAYED in normalized ||
                HomeSectionId.MOST_PLAYED in normalized
        return if (listeningActivityEnabled) {
            normalized + HomeSectionId.RECENTLY_PLAYED + HomeSectionId.MOST_PLAYED
        } else {
            normalized
        }
    }

    fun isSectionVisible(settings: HomeLayoutSettings, id: HomeSectionId): Boolean = when (id) {
        HomeSectionId.RECENTLY_PLAYED,
        HomeSectionId.MOST_PLAYED,
        -> HomeSectionId.RECENTLY_PLAYED in settings.visibleSections ||
            HomeSectionId.MOST_PLAYED in settings.visibleSections
        else -> id in settings.visibleSections
    }

    fun isToggleable(id: HomeSectionId): Boolean = id !in ALWAYS_VISIBLE_SECTIONS

    fun displayNameFor(id: HomeSectionId): String = when (id) {
        HomeSectionId.CONTINUE_LISTENING  -> "Continue Listening"
        HomeSectionId.RECENTLY_PLAYED    -> "Listening Activity"
        HomeSectionId.FAVORITES          -> "Favorites"
        HomeSectionId.MOST_PLAYED        -> "Most Played"
        HomeSectionId.PLAYLISTS          -> "Playlists"
        HomeSectionId.SMART_COLLECTIONS  -> "Smart Collections"
        HomeSectionId.WRAPPED            -> "Wrapped"
        HomeSectionId.LIBRARY_SHORTCUT   -> "Library Shortcut"
    }

    fun descriptionFor(id: HomeSectionId): String = when (id) {
        HomeSectionId.CONTINUE_LISTENING -> "Quick access to the current or last played track."
        HomeSectionId.RECENTLY_PLAYED    ->
            "Recently played tracks, with most played as a fallback."
        HomeSectionId.FAVORITES          -> "Songs you have marked as favorites."
        HomeSectionId.MOST_PLAYED        -> "Your most-played tracks."
        HomeSectionId.PLAYLISTS          -> "Preview your playlists."
        HomeSectionId.SMART_COLLECTIONS  -> "Auto-generated collections from your listening history."
        HomeSectionId.WRAPPED            -> "Shortcut to your latest event-backed year in music."
        HomeSectionId.LIBRARY_SHORTCUT   -> "Quick link to your full library. Always visible."
    }

}
