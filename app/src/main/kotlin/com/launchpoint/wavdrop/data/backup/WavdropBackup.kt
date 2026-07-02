package com.launchpoint.wavdrop.data.backup

data class BackupSong(
    val id: Long,
    /** Content URI — only a weak hint; MediaStore IDs change on reinstall/rescan. */
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val dateAdded: Long,
    val trackNumber: Int,
    val year: Int,
    /** Folder path (relative path on Q+, parent dir below Q). Stable across reinstall. */
    val folderPath: String? = null,
    val folderName: String? = null,
)

data class BackupTrackStats(
    val songId: Long,
    val contentUri: String,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long,
    val totalListeningTimeMs: Long,
    val isFavorite: Boolean,
    /**
     * Epoch ms of last qualifying 5-second listen.
     *
     * null  = parsed from a v1 backup (field absent in v1 format).
     * ≥ 0   = parsed from a v2 backup; 0 means no qualifying listen occurred.
     *
     * The import path branches on [WavdropBackup.sourceVersion] — never on this value
     * being null vs 0 — to decide which effective lastListenedAt to apply.
     */
    val lastListenedAt: Long? = null,
)

data class BackupImportBaseline(
    val songId: Long,
    val sourceType: String,
    val sourceKey: String,
    val lastImportedPlayCount: Int,
    val lastImportedSkipCount: Int,
    val lastImportedAt: Long,
)

data class BackupLyricsOverride(
    val songId: Long,
    val contentUri: String,
    val lyrics: String,
    val updatedAt: Long,
)

data class BackupPreferences(
    val startupDestination: String?,
    val mostPlayedPeriod: String?,
    val mostPlayedLimit: String?,
    val songSortMode: String? = null,
    val searchTapBehavior: String? = null,
    val homeVisibleSections: List<String>?,
    val scanMode: String?,
    val selectedFolderUris: List<String>?,
    val minimumTrackDurationSeconds: Int?,
    val themeMode: String? = null,
    val accentColor: String? = null,
    val launcherIcon: String? = null,
    val compactMode: Boolean? = null,
    val backupFileMode: String? = null,
    val autoBackupInterval: String? = null,
    // ── Phase 4: every remaining user-facing DataStore setting. All nullable;
    // null = "was default at export time" and restore leaves the default. ─────
    val artworkCornerStyle: String? = null,
    val showSongThumbnails: Boolean? = null,
    val showAlbumInSongRows: Boolean? = null,
    val nowPlayingBackground: String? = null,
    val showQueueCount: Boolean? = null,
    val nowPlayingTimeDisplayMode: String? = null,
    val notificationControls: String? = null,
    val includeWhatsAppVoiceNotes: Boolean? = null,
    val pauseOnAudioDisconnect: Boolean? = null,
    val rememberLastTrack: Boolean? = null,
    val rememberPosition: Boolean? = null,
    val restoreQueue: Boolean? = null,
    val bluetoothResumeMode: String? = null,
    val wiredResumeMode: String? = null,
    val showMilestoneCelebrations: Boolean? = null,
    val wrappedUseArtworkBackgrounds: Boolean? = null,
    val wrappedBackgroundIntensity: String? = null,
    val wrappedFallbackTheme: String? = null,
)

data class BackupPlaylistSong(
    val songId: Long,
    val contentUri: String,
    val position: Int,
    val title: String,
    val artist: String,
    val album: String,
)

data class BackupPlaylist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val songs: List<BackupPlaylistSong>,
)

data class BackupListenEvent(
    val songId: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val eventType: String,
    val occurredAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val source: String,
    /**
     * Stable per-event id (contract §9.2). Optional: null for legacy events (pre-eventId rows)
     * and for v1 backups. Preserved verbatim through export/parse — never fabricated. Not yet
     * consumed by restore/dedup (later phase) and not yet covered by the integrity fingerprint.
     */
    val eventId: String? = null,
)

data class BackupDesktopOverlay(
    val schemaVersion: Int,
    val producerPlatform: String?,
    val trackStats: List<BackupDesktopOverlayTrackStats>,
    val listenEvents: List<BackupDesktopOverlayListenEvent>,
    val rawJson: String,
)

data class BackupDesktopOverlayTrackStats(
    val desktopTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val playCount: Int,
    val skipCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long,
    val lastListenedAt: Long,
    val favorite: Boolean,
)

data class BackupDesktopOverlayListenEvent(
    val eventId: String?,
    val desktopTrackId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val occurredAt: Long,
    val listenedMs: Long,
    val eventType: String,
    val source: String,
)

/**
 * Section counts written at export time and re-checked at parse time. A mismatch
 * between the manifest and the actual parsed content indicates a corrupt or
 * tampered file. [preferenceCount] is informational only and is NOT validated:
 * newer app versions add preference fields that older parsers ignore, which would
 * otherwise turn legitimate forward-compatible backups into false failures.
 */
data class BackupManifest(
    val songCount: Int,
    val trackStatsCount: Int,
    val listenEventCount: Int,
    val importBaselineCount: Int,
    val lyricsOverrideCount: Int,
    val playlistCount: Int,
    val preferenceCount: Int,
) {
    /** True when every validated count matches the backup's actual content. */
    fun matchesContentOf(backup: WavdropBackup): Boolean =
        songCount == backup.songs.size &&
            trackStatsCount == backup.trackStats.size &&
            listenEventCount == backup.listenEvents.size &&
            importBaselineCount == backup.importBaselines.size &&
            lyricsOverrideCount == backup.lyricsOverrides.size &&
            playlistCount == backup.playlists.size

    companion object {
        fun of(backup: WavdropBackup): BackupManifest = BackupManifest(
            songCount           = backup.songs.size,
            trackStatsCount     = backup.trackStats.size,
            listenEventCount    = backup.listenEvents.size,
            importBaselineCount = backup.importBaselines.size,
            lyricsOverrideCount = backup.lyricsOverrides.size,
            playlistCount       = backup.playlists.size,
            preferenceCount     = backup.preferences?.let(::countNonNullFields) ?: 0,
        )

        private fun countNonNullFields(prefs: BackupPreferences): Int = listOfNotNull(
            prefs.startupDestination,
            prefs.mostPlayedPeriod,
            prefs.mostPlayedLimit,
            prefs.songSortMode,
            prefs.searchTapBehavior,
            prefs.homeVisibleSections,
            prefs.scanMode,
            prefs.selectedFolderUris,
            prefs.minimumTrackDurationSeconds,
            prefs.themeMode,
            prefs.accentColor,
            prefs.launcherIcon,
            prefs.compactMode,
            prefs.backupFileMode,
            prefs.autoBackupInterval,
            prefs.artworkCornerStyle,
            prefs.showSongThumbnails,
            prefs.showAlbumInSongRows,
            prefs.nowPlayingBackground,
            prefs.showQueueCount,
            prefs.nowPlayingTimeDisplayMode,
            prefs.notificationControls,
            prefs.includeWhatsAppVoiceNotes,
            prefs.pauseOnAudioDisconnect,
            prefs.rememberLastTrack,
            prefs.rememberPosition,
            prefs.restoreQueue,
            prefs.bluetoothResumeMode,
            prefs.wiredResumeMode,
            prefs.showMilestoneCelebrations,
            prefs.wrappedUseArtworkBackgrounds,
            prefs.wrappedBackgroundIntensity,
            prefs.wrappedFallbackTheme,
        ).size
    }
}

data class WavdropBackup(
    /** ISO-8601 string used by v1 exports. Empty string for v2 (use [exportedAtMs] instead). */
    val exportedAt: String,
    val songs: List<BackupSong>,
    val trackStats: List<BackupTrackStats>,
    val importBaselines: List<BackupImportBaseline>,
    val lyricsOverrides: List<BackupLyricsOverride> = emptyList(),
    val preferences: BackupPreferences? = null,
    val playlists: List<BackupPlaylist> = emptyList(),
    val listenEvents: List<BackupListenEvent> = emptyList(),
    // ── Metadata written by newer exporters; null for legacy backups. ─────────
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val manifest: BackupManifest? = null,
    /** v1 integrity: optional checksum. v2 uses a separate integrity object — this is null. */
    val payloadSha256: String? = null,

    // ── v2-only fields; all null when sourceVersion == V1. ───────────────────
    /** Format version derived from the parsed backup. Drives import branching logic. */
    val sourceVersion: BackupFormatVersion = BackupFormatVersion.V1,
    /** v2: unique identifier for this specific backup artifact. Fresh UUID per export. */
    val backupId: String? = null,
    /** v2: stable per-installation UUID, persisted in DataStore across exports and upgrades. */
    val sourceInstallationId: String? = null,
    /** v2: [exportedAt] as epoch milliseconds (JSON integer). null in v1 backups. */
    val exportedAtMs: Long? = null,
    /**
     * Optional V2 extension root. It is intentionally excluded from the Android V2 fingerprint
     * so Android can validate sealed roots first, then import/preserve portable Desktop activity.
     */
    val desktopOverlay: BackupDesktopOverlay? = null,
)
