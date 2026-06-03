package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportApplyResult(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    val playsAdded: Long,
    val skipsAdded: Long,
    val lyricsRestored: Int = 0,
    val favoritesRestored: Int = 0,
    val preferencesRestored: Boolean = false,
    val playlistsRestored: Int = 0,
    val playlistSongsRestored: Int = 0,
    val eventsRestored: Int = 0,
    val eventsSkipped: Int = 0,
)
