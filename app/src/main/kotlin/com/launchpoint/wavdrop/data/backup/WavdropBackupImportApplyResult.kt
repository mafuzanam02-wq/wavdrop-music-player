package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportApplyResult(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    val playsAdded: Long,
    val skipsAdded: Long,
    val lyricsRestored: Int = 0,
)
