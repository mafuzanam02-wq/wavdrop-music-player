package com.launchpoint.wavdrop.data.settings

enum class SongSortMode(val label: String) {
    TITLE_ASC("A-Z"),
    RECENTLY_ADDED("Recently Added"),
    MOST_PLAYED_THIS_MONTH("Most Played - This Month"),
    MOST_PLAYED_ALL_TIME("Most Played - All Time");

    companion object {
        val DEFAULT: SongSortMode = TITLE_ASC

        fun fromStoredName(value: String?): SongSortMode? =
            value?.let { stored -> entries.firstOrNull { it.name == stored } }

        fun fromStoredNameOrDefault(value: String?): SongSortMode =
            fromStoredName(value) ?: DEFAULT
    }
}
