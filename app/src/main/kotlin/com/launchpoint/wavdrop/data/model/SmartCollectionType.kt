package com.launchpoint.wavdrop.data.model

enum class SmartCollectionType {
    FAVORITES,
    MOST_PLAYED,
    RECENTLY_PLAYED,
    NEVER_PLAYED,
    RECENTLY_ADDED,
    MOST_SKIPPED,
    LONG_TRACKS,
    SHORT_TRACKS;

    companion object {
        /** Returns null for a null, blank, or unrecognised route value instead of throwing. */
        fun fromRouteValue(value: String?): SmartCollectionType? =
            entries.firstOrNull { it.name == value }
    }
}
