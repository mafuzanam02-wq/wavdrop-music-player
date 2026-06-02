package com.launchpoint.wavdrop.data.model

data class ListeningAnalyticsEmptyState(
    val reason: ListeningAnalyticsEmptyReason,
    val hasEventsInRange: Boolean,
    val hasMatchedLibraryItems: Boolean,
) {
    val isEmpty: Boolean
        get() = reason != ListeningAnalyticsEmptyReason.HAS_ACTIVITY
}

enum class ListeningAnalyticsEmptyReason {
    HAS_ACTIVITY,
    NO_EVENTS_IN_RANGE,
    ONLY_ORPHAN_EVENTS,
    NO_AGGREGATE_ACTIVITY,
}
