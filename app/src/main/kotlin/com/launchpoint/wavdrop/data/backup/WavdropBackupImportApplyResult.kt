package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportApplyResult(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    val ambiguousTracks: Int = 0,
    /** Tracks where the backup had higher stats and local values were updated. */
    val statsUpdated: Int,
    val lyricsRestored: Int = 0,
    val lyricsInBackup: Int = 0,
    /** Lyrics overrides whose song could not be confidently resolved (skipped, never guessed). */
    val lyricsUnmatched: Int = 0,
    val favoritesRestored: Int = 0,
    val favoritesInBackup: Int = 0,
    /** Backup favorites whose track did not match any current song. */
    val favoritesUnmatched: Int = 0,
    val preferencesRestored: Boolean = false,
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
)
