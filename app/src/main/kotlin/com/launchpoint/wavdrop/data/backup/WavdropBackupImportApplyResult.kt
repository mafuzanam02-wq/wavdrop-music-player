package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportApplyResult(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    /** Tracks where the backup had higher stats and local values were updated. */
    val statsUpdated: Int,
    val lyricsRestored: Int = 0,
    val favoritesRestored: Int = 0,
    val preferencesRestored: Boolean = false,
    val playlistsRestored: Int = 0,
    val playlistSongsRestored: Int = 0,
    val eventsRestored: Int = 0,
    val eventsSkipped: Int = 0,
    val eventsSkippedDuplicate: Int = 0,
    val eventsSkippedUnmatched: Int = 0,
    /** Restored events dated inside the current calendar month (drives current-month reports). */
    val currentMonthEventsRestored: Int = 0,
    /** Per-tier matching breakdown for diagnostics (logged and available to UI). */
    val matchDiagnostics: WavdropBackupMatchDiagnostics = WavdropBackupMatchDiagnostics(),
    /** True when the backup restored a non-OFF auto-backup interval but no folder is set on this device. */
    val needsAutoBackupFolderSelection: Boolean = false,
)
