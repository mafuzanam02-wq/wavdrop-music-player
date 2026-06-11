package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Favorites are restored from [WavdropBackupStatsMatcher.match] matchedRows
 * (the same tier matcher as stats). These tests pin the favorite-specific
 * behavior: surviving a reinstall ID change and never landing on a wrong song.
 */
class FavoritesRestoreMatchTest {

    private fun song(id: Long, uri: String) = Song(
        id = id, title = "Track", artist = "A", album = "B",
        albumId = 0L, duration = 180_000L, uri = uri, dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun backupWithFavorite(songId: Long, uri: String) = WavdropBackup(
        exportedAt = "2026-06-11T00:00:00Z",
        songs = listOf(
            BackupSong(
                id = songId, uri = uri, title = "Track", artist = "A", album = "B",
                albumId = 0L, duration = 180_000L, dateAdded = 0L, trackNumber = 0, year = 0,
            ),
        ),
        trackStats = listOf(
            BackupTrackStats(
                songId = songId, contentUri = uri, playCount = 3, skipCount = 0,
                lastPlayedAt = 100L, totalListeningTimeMs = 1_000L, isFavorite = true,
            ),
        ),
        importBaselines = emptyList(),
    )

    @Test
    fun `favorite follows its track to the new song id after reinstall`() {
        val backup = backupWithFavorite(songId = 1L, uri = "content://media/OLD/1")
        val rescanned = song(42L, uri = "content://media/NEW/42")

        val match = WavdropBackupMatchResultOf(backup, rescanned)
        assertEquals(1, match.matchedRows.size)
        val (currentSong, stat) = match.matchedRows.single()
        assertEquals(42L, currentSong.id)
        assertTrue(stat.isFavorite)
    }

    @Test
    fun `favorite on an ambiguous track is not attached to either duplicate`() {
        val backup = backupWithFavorite(songId = 1L, uri = "content://media/OLD/1")
        // Two rescanned copies with identical tags and duration — no safe choice.
        val dupA = song(42L, uri = "content://media/NEW/42")
        val dupB = song(43L, uri = "content://media/NEW/43")

        val match = WavdropBackupStatsMatcher.match(backup, listOf(dupA, dupB))
        assertEquals(0, match.matchedRows.size)
        assertEquals(1, match.diagnostics.ambiguous)
    }

    private fun WavdropBackupMatchResultOf(backup: WavdropBackup, current: Song) =
        WavdropBackupStatsMatcher.match(backup, listOf(current))
}
