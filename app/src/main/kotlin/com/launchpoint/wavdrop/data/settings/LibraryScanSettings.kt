package com.launchpoint.wavdrop.data.settings

/**
 * SE-1: the approved PRESET folder categories a user may choose to exclude from the scanned library. They are not a general
 * folder block-list and they do not replace the WhatsApp voice-note rule (which is an "include" toggle that defaults to
 * excluded). Every preset defaults to OFF: nothing is excluded until the user enables it. The enum NAME is the persisted form,
 * so entries must never be renamed.
 */
enum class LibraryScanExclusion {
    TELEGRAM,
    SIGNAL,
    MESSENGER,
    DOWNLOADS,
    RECORDINGS,
}

data class LibraryScanSettings(
    val scanMode: LibraryScanMode = LibraryScanMode.WHOLE_DEVICE,
    val selectedFolderUris: List<String> = emptyList(),
    val minimumTrackDurationSeconds: Int = 30,
    val includeWhatsAppVoiceNotes: Boolean = false,
    /** Preset folder categories whose songs are excluded from the library. Empty by default (nothing is excluded). */
    val excludedPresetFolders: Set<LibraryScanExclusion> = emptySet(),
    /**
     * CFE-1: concrete folders the user excluded, each a canonical shared-storage-relative path (for example `Music/Podcasts`),
     * deterministic order, no duplicates. A folder and all of its descendants are excluded. Empty by default. Device-local scan
     * configuration (never part of a backup). Maintained only through [LibraryScanSettingsRules].
     */
    val customExcludedFolderPaths: Set<String> = emptySet(),
)
