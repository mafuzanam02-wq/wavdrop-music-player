package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class WavdropBackupStatsMatcherTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun song(
        id: Long,
        uri: String,
        title: String  = "Title $id",
        artist: String = "Artist $id",
        album: String  = "Album $id",
    ) = Song(
        id          = id,
        title       = title,
        artist      = artist,
        album       = album,
        albumId     = 0L,
        duration    = 180_000L,
        uri         = uri,
        dateAdded   = 0L,
        trackNumber = 0,
        year        = 0,
    )

    private fun backupSong(
        id: Long,
        uri: String,
        title: String  = "Title $id",
        artist: String = "Artist $id",
        album: String  = "Album $id",
    ) = BackupSong(
        id          = id,
        uri         = uri,
        title       = title,
        artist      = artist,
        album       = album,
        albumId     = 0L,
        duration    = 180_000L,
        dateAdded   = 0L,
        trackNumber = 0,
        year        = 0,
    )

    private fun stat(
        songId: Long,
        uri: String,
        plays: Int = 5,
        skips: Int = 2,
    ) = BackupTrackStats(
        songId               = songId,
        contentUri           = uri,
        playCount            = plays,
        skipCount            = skips,
        lastPlayedAt         = 0L,
        totalListeningTimeMs = 0L,
        isFavorite           = false,
    )

    private fun backup(
        songs: List<BackupSong>      = emptyList(),
        stats: List<BackupTrackStats> = emptyList(),
    ) = WavdropBackup(
        exportedAt      = "2026-01-01T00:00:00Z",
        songs           = songs,
        trackStats      = stats,
        importBaselines = emptyList(),
    )

    // ── URI match ─────────────────────────────────────────────────────────────

    @Test
    fun `URI exact match links stat to current song`() {
        val uri  = "content://media/external/audio/media/1"
        val song = song(id = 1L, uri = uri)
        val s    = stat(songId = 1L, uri = uri)
        val b    = backup(songs = listOf(backupSong(1L, uri)), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(1, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
        assertEquals(song, result.matchedRows[0].first)
    }

    // ── Tags fallback ─────────────────────────────────────────────────────────

    @Test
    fun `tags fallback matches when URI differs`() {
        val oldUri = "content://media/external/audio/media/OLD"
        val newUri = "content://media/external/audio/media/NEW"

        val song      = song(id = 10L, uri = newUri, title = "Song A", artist = "Artist A", album = "Album A")
        val bSong     = backupSong(id = 10L, uri = oldUri, title = "Song A", artist = "Artist A", album = "Album A")
        val s         = stat(songId = 10L, uri = oldUri)
        val b         = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(1, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
        assertEquals(song, result.matchedRows[0].first)
    }

    @Test
    fun `tags fallback is case-insensitive`() {
        val oldUri = "content://media/external/audio/media/OLD"
        val newUri = "content://media/external/audio/media/NEW"

        val song  = song(id = 2L, uri = newUri, title = "hello world", artist = "The Band", album = "Great Album")
        val bSong = backupSong(id = 2L, uri = oldUri, title = "Hello World", artist = "THE BAND", album = "GREAT ALBUM")
        val s     = stat(songId = 2L, uri = oldUri)
        val b     = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(1, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
    }

    @Test
    fun `tags fallback trims whitespace`() {
        val oldUri = "content://media/external/audio/media/OLD"
        val newUri = "content://media/external/audio/media/NEW"

        val song  = song(id = 3L, uri = newUri, title = "My Song", artist = "Artist", album = "Album")
        val bSong = backupSong(id = 3L, uri = oldUri, title = "  My Song  ", artist = " Artist ", album = " Album ")
        val s     = stat(songId = 3L, uri = oldUri)
        val b     = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(1, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
    }

    // ── Unmatched cases ───────────────────────────────────────────────────────

    @Test
    fun `stat with no matching URI or tags is counted as unmatched`() {
        val libSong = song(id = 1L, uri = "content://media/1", title = "Different", artist = "Different", album = "Different")
        val bSong   = backupSong(id = 2L, uri = "content://media/2", title = "Gone", artist = "Gone", album = "Gone")
        val s       = stat(songId = 2L, uri = "content://media/2")
        val b       = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(libSong))

        assertEquals(0, result.matchedRows.size)
        assertEquals(1, result.unmatchedCount)
    }

    @Test
    fun `stat with no corresponding backup song entry is unmatched`() {
        // The stat references songId=99 but backup.songs has no entry for 99.
        // URI also differs, so tags fallback cannot run.
        val libSong = song(id = 1L, uri = "content://media/1")
        val s       = stat(songId = 99L, uri = "content://media/99")
        val b       = backup(songs = emptyList(), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(libSong))

        assertEquals(0, result.matchedRows.size)
        assertEquals(1, result.unmatchedCount)
    }

    // ── Mixed results ─────────────────────────────────────────────────────────

    @Test
    fun `mixed results correctly counts matched and unmatched`() {
        val uri1    = "content://media/1"
        val uri2    = "content://media/2"
        val song1   = song(id = 1L, uri = uri1)
        val bSong1  = backupSong(id = 1L, uri = uri1)
        val stat1   = stat(songId = 1L, uri = uri1)
        val stat2   = stat(songId = 2L, uri = uri2) // no library match

        val b = backup(songs = listOf(bSong1, backupSong(2L, uri2)), stats = listOf(stat1, stat2))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song1))

        assertEquals(1, result.matchedRows.size)
        assertEquals(1, result.unmatchedCount)
        assertEquals(2, result.matchedCount + result.unmatchedCount)
    }

    // ── Empty edge cases ──────────────────────────────────────────────────────

    @Test
    fun `empty backup stats produces empty result`() {
        val song = song(id = 1L, uri = "content://media/1")
        val b    = backup(songs = emptyList(), stats = emptyList())

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(0, result.matchedRows.size)
        assertEquals(0, result.unmatchedCount)
    }

    @Test
    fun `empty current library marks all stats as unmatched`() {
        val bSong = backupSong(id = 1L, uri = "content://media/1")
        val s     = stat(songId = 1L, uri = "content://media/1")
        val b     = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, emptyList())

        assertEquals(0, result.matchedRows.size)
        assertEquals(1, result.unmatchedCount)
    }

    // ── Matched stat payload ──────────────────────────────────────────────────

    @Test
    fun `matched row carries correct BackupTrackStats`() {
        val uri   = "content://media/external/audio/media/42"
        val song  = song(id = 42L, uri = uri)
        val bSong = backupSong(id = 42L, uri = uri)
        val s     = stat(songId = 42L, uri = uri, plays = 17, skips = 3)
        val b     = backup(songs = listOf(bSong), stats = listOf(s))

        val result = WavdropBackupStatsMatcher.match(b, listOf(song))

        assertEquals(1, result.matchedRows.size)
        val (_, matchedStat) = result.matchedRows[0]
        assertEquals(17, matchedStat.playCount)
        assertEquals(3, matchedStat.skipCount)
    }
}
