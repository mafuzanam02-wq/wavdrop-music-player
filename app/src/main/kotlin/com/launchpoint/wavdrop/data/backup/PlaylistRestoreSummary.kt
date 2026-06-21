package com.launchpoint.wavdrop.data.backup

data class PlaylistRestoreSummary(
    val playlistName: String,
    val entriesInBackup: Int,
    val restored: Int,
    val skippedUnmatched: Int,
    val skippedDuplicate: Int,
)
