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
    val exportedAt: String,
    val songs: List<BackupSong>,
    val trackStats: List<BackupTrackStats>,
    val importBaselines: List<BackupImportBaseline>,
    val lyricsOverrides: List<BackupLyricsOverride> = emptyList(),
    val preferences: BackupPreferences? = null,
    val playlists: List<BackupPlaylist> = emptyList(),
    val listenEvents: List<BackupListenEvent> = emptyList(),
    // ── Metadata written by newer exporters; null for legacy backups. ─────────
    // None of these participate in the payload fingerprint.
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val manifest: BackupManifest? = null,
    val payloadSha256: String? = null,
)
