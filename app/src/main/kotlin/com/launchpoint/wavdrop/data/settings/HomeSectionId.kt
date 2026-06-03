package com.launchpoint.wavdrop.data.settings

enum class HomeSectionId {
    CONTINUE_LISTENING,
    RECENTLY_PLAYED,
    FAVORITES,
    MOST_PLAYED,
    PLAYLISTS,
    SMART_COLLECTIONS,
    WRAPPED,
    LIBRARY_SHORTCUT;

    companion object {
        val ALL: Set<HomeSectionId> = enumValues<HomeSectionId>().toSet()
    }
}
