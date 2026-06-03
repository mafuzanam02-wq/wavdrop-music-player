package com.launchpoint.wavdrop.data.settings

object HomeLayoutSettingsRules {

    val ALWAYS_VISIBLE_SECTIONS: Set<HomeSectionId> = setOf(HomeSectionId.LIBRARY_SHORTCUT)

    fun withSectionVisible(
        settings: HomeLayoutSettings,
        id: HomeSectionId,
        visible: Boolean,
    ): HomeLayoutSettings {
        if (id in ALWAYS_VISIBLE_SECTIONS) return settings
        val updated = if (visible) {
            settings.visibleSections + id
        } else {
            settings.visibleSections - id
        }
        return settings.copy(visibleSections = updated + ALWAYS_VISIBLE_SECTIONS)
    }

    fun isToggleable(id: HomeSectionId): Boolean = id !in ALWAYS_VISIBLE_SECTIONS

    fun displayNameFor(id: HomeSectionId): String = when (id) {
        HomeSectionId.CONTINUE_LISTENING  -> "Continue Listening"
        HomeSectionId.RECENTLY_PLAYED    -> "Recently Played"
        HomeSectionId.FAVORITES          -> "Favorites"
        HomeSectionId.MOST_PLAYED        -> "Most Played"
        HomeSectionId.PLAYLISTS          -> "Playlists"
        HomeSectionId.SMART_COLLECTIONS  -> "Smart Collections"
        HomeSectionId.WRAPPED            -> "Wrapped"
        HomeSectionId.LIBRARY_SHORTCUT   -> "Library Shortcut"
    }

    fun descriptionFor(id: HomeSectionId): String = when (id) {
        HomeSectionId.CONTINUE_LISTENING -> "Quick access to the current or last played track."
        HomeSectionId.RECENTLY_PLAYED    -> "Tracks you have recently listened to."
        HomeSectionId.FAVORITES          -> "Songs you have marked as favorites."
        HomeSectionId.MOST_PLAYED        -> "Your most-played tracks."
        HomeSectionId.PLAYLISTS          -> "Preview your playlists."
        HomeSectionId.SMART_COLLECTIONS  -> "Auto-generated collections from your listening history."
        HomeSectionId.WRAPPED            -> "Shortcut to your latest event-backed year in music."
        HomeSectionId.LIBRARY_SHORTCUT   -> "Quick link to your full library. Always visible."
    }

    val DISPLAY_ORDER: List<HomeSectionId> = listOf(
        HomeSectionId.CONTINUE_LISTENING,
        HomeSectionId.RECENTLY_PLAYED,
        HomeSectionId.FAVORITES,
        HomeSectionId.MOST_PLAYED,
        HomeSectionId.PLAYLISTS,
        HomeSectionId.SMART_COLLECTIONS,
        HomeSectionId.WRAPPED,
        HomeSectionId.LIBRARY_SHORTCUT,
    )
}
