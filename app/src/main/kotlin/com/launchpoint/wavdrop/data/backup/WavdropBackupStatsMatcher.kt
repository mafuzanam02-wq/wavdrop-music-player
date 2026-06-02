package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song

object WavdropBackupStatsMatcher {

    fun match(backup: WavdropBackup, currentSongs: List<Song>): WavdropBackupMatchResult {
        val byUri  = currentSongs.associateBy { it.uri }
        val byTags = currentSongs.associateBy {
            Triple(it.title.norm(), it.artist.norm(), it.album.norm())
        }
        val backupSongById = backup.songs.associateBy { it.id }

        val matched     = mutableListOf<Pair<Song, BackupTrackStats>>()
        var unmatched   = 0

        for (stat in backup.trackStats) {
            // 1. URI exact match
            val song = byUri[stat.contentUri]
                ?: run {
                    // 2. Title + artist + album fallback (requires the backup song record for tags)
                    val backupSong = backupSongById[stat.songId] ?: return@run null
                    byTags[Triple(backupSong.title.norm(), backupSong.artist.norm(), backupSong.album.norm())]
                }

            if (song != null) matched.add(song to stat) else unmatched++
        }

        return WavdropBackupMatchResult(matchedRows = matched, unmatchedCount = unmatched)
    }

    private fun String.norm() = trim().lowercase()
}
