package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song

data class WavdropBackupMatchResult(
    val matchedRows: List<Pair<Song, BackupTrackStats>>,
    val unmatchedCount: Int,
) {
    val matchedCount: Int get() = matchedRows.size
}
