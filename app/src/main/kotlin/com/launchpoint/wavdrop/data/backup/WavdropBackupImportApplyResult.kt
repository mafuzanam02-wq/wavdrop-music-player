package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportApplyResult(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    val ambiguousTracks: Int = 0,
    /** Tracks where backup values were higher than local and raised at least one local field. */
    val statsUpdated: Int,
    /** Tracks where local values were already higher than backup and were kept as-is. */
    val statsRetainedLocal: Int = 0,
    val lyricsRestored: Int = 0,
    val lyricsInBackup: Int = 0,
    /** Lyrics overrides whose song could not be confidently resolved (skipped, never guessed). */
    val lyricsUnmatched: Int = 0,
    val favoritesRestored: Int = 0,
    val favoritesInBackup: Int = 0,
    /** Backup favorites whose track did not match any current song. */
    val favoritesUnmatched: Int = 0,
    val preferencesRestored: Boolean = false,
    /** True when the backup contained preferences that were parsed but not applied (Merge Restore). */
    val preferencesSkipped: Boolean = false,
    val playlistsRestored: Int = 0,
    val playlistsInBackup: Int = 0,
    val playlistSongsRestored: Int = 0,
    val playlistEntriesInBackup: Int = 0,
    /** Playlist entries whose song could not be confidently resolved (skipped, never guessed). */
    val playlistEntriesUnmatched: Int = 0,
    /** Per-playlist breakdown for summaries with unmatched entries. Empty when all entries matched. */
    val playlistRestoreSummaries: List<PlaylistRestoreSummary> = emptyList(),
    val eventsRestored: Int = 0,
    /** Import baselines re-keyed to current song ids and upserted (BlackPlayer history tracking). */
    val baselinesRestored: Int = 0,
    val eventsSkipped: Int = 0,
    val eventsSkippedDuplicate: Int = 0,
    val eventsSkippedUnmatched: Int = 0,
    /** Restored events dated inside the current calendar month (drives current-month reports). */
    val currentMonthEventsRestored: Int = 0,
    /** Per-tier matching breakdown for diagnostics (logged and available to UI). */
    val matchDiagnostics: WavdropBackupMatchDiagnostics = WavdropBackupMatchDiagnostics(),
    /** True when the backup restored a non-OFF auto-backup interval but no folder is set on this device. */
    val needsAutoBackupFolderSelection: Boolean = false,
    /** True once Room-backed restore data committed successfully. */
    val dataRestored: Boolean = true,
    /** True when the launcher icon preference was restored and alias application was attempted. */
    val launcherIconRestored: Boolean = false,
    /** Calm user-facing notes for partial settings restore or device-specific permissions. */
    val warnings: List<String> = emptyList(),
    /**
     * True when the apply-time authoritative recheck found no persistent state change
     * was possible. No data was written. UI must show a distinct calm result (not "Merge complete").
     */
    val isNoOp: Boolean = false,
    /** Unmatched backup tracks whose history was quarantined during this apply. */
    val pendingTracksPreserved: Int = 0,
    /** Listen events preserved in the quarantine during this apply. */
    val pendingEventsPreserved: Int = 0,
    /** Playlist entries preserved in the quarantine during this apply. */
    val pendingPlaylistEntriesPreserved: Int = 0,
    /** True only for the explicit empty-history clean-install recovery path. */
    val cleanInstallRecovery: Boolean = false,
    /** True after the recovery flow awaited a library scan before applying history. */
    val libraryScanCompleted: Boolean = false,
    /** True when old SAF folder URIs were discarded and the user must select folders again. */
    val needsMusicFolderReselection: Boolean = false,
    /** Portable features intentionally absent from the backup format or unsafe on this device. */
    val notRestoredOnThisDevice: List<String> = emptyList(),
)
