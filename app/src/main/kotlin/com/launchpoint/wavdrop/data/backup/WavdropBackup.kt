package com.launchpoint.wavdrop.data.backup

data class BackupSong(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val dateAdded: Long,
    val trackNumber: Int,
    val year: Int,
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

data class WavdropBackup(
    val exportedAt: String,
    val songs: List<BackupSong>,
    val trackStats: List<BackupTrackStats>,
    val importBaselines: List<BackupImportBaseline>,
    val lyricsOverrides: List<BackupLyricsOverride> = emptyList(),
)
