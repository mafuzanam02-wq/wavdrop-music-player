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

data class WavdropBackup(
    val exportedAt: String,
    val songs: List<BackupSong>,
    val trackStats: List<BackupTrackStats>,
    val importBaselines: List<BackupImportBaseline>,
    val lyricsOverrides: List<BackupLyricsOverride> = emptyList(),
    val preferences: BackupPreferences? = null,
    val playlists: List<BackupPlaylist> = emptyList(),
    val listenEvents: List<BackupListenEvent> = emptyList(),
)
