package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistEntryRestorePlannerTest {

    private fun song(id: Long) = Song(
        id = id, title = "Title $id", artist = "Artist $id", album = "Album $id",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id", dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun entry(songId: Long, position: Int) = BackupPlaylistSong(
        songId     = songId,
        contentUri = "content://media/OLD/$songId",
        position   = position,
        title      = "Title $songId",
        artist     = "Artist $songId",
        album      = "Album $songId",
    )

    // ── D: restore after reinstall-style ID change ────────────────────────────

    @Test
    fun `entry resolves to new song id and is appended in order`() {
        val newSongs = mapOf(1L to song(11L), 2L to song(22L))
        val plan = PlaylistEntryRestorePlanner.plan(
            entries         = listOf(entry(2L, position = 1), entry(1L, position = 0)),
            resolve         = { newSongs[it.songId] },
            existingSongIds = emptySet(),
            nextPosition    = 0,
        )
        assertEquals(2, plan.restored)
        // Backup position order preserved: backup pos 0 (song 1→11) first.
        assertEquals(listOf(11L, 22L), plan.toAdd.map { it.songId })
        assertEquals(listOf(0, 1), plan.toAdd.map { it.position })
    }

    @Test
    fun `entries append after existing playlist positions`() {
        val plan = PlaylistEntryRestorePlanner.plan(
            entries         = listOf(entry(1L, 0)),
            resolve         = { song(11L) },
            existingSongIds = setOf(5L),
            nextPosition    = 7,
        )
        assertEquals(7, plan.toAdd.single().position)
    }

    // ── E: idempotency ────────────────────────────────────────────────────────

    @Test
    fun `restoring the same backup twice does not duplicate entries`() {
        val entries = listOf(entry(1L, 0), entry(2L, 1))
        val newSongs = mapOf(1L to song(11L), 2L to song(22L))

        val first = PlaylistEntryRestorePlanner.plan(entries, { newSongs[it.songId] }, emptySet(), 0)
        assertEquals(2, first.restored)

        val existingAfterFirst = first.toAdd.map { it.songId }.toSet()
        val second = PlaylistEntryRestorePlanner.plan(entries, { newSongs[it.songId] }, existingAfterFirst, 2)
        assertEquals(0, second.restored)
        assertEquals(2, second.skippedExisting)
    }

    @Test
    fun `same song twice in one backup playlist is added once`() {
        val plan = PlaylistEntryRestorePlanner.plan(
            entries         = listOf(entry(1L, 0), entry(1L, 1)),
            resolve         = { song(11L) },
            existingSongIds = emptySet(),
            nextPosition    = 0,
        )
        assertEquals(1, plan.restored)
        assertEquals(1, plan.skippedExisting)
    }

    // ── F: uncertain songs are skipped, not wrongly attached ──────────────────

    @Test
    fun `unresolved entry is skipped and counted`() {
        val plan = PlaylistEntryRestorePlanner.plan(
            entries         = listOf(entry(1L, 0), entry(2L, 1)),
            resolve         = { if (it.songId == 1L) song(11L) else null },
            existingSongIds = emptySet(),
            nextPosition    = 0,
        )
        assertEquals(1, plan.restored)
        assertEquals(1, plan.skippedUnmatched)
        assertEquals(11L, plan.toAdd.single().songId)
    }

    @Test
    fun `ambiguous duplicate-tag entry is skipped via the link resolver`() {
        // Two current songs share identical tags; the resolver refuses to guess,
        // so the playlist entry is skipped rather than attached to the wrong copy.
        val dupA = song(7L).copy(title = "Song", artist = "Band", album = "LP")
        val dupB = song(8L).copy(title = "Song", artist = "Band", album = "LP")
        val resolver = BackupSongLinkResolver(listOf(dupA, dupB), emptyMap())

        val ambiguousEntry = BackupPlaylistSong(
            songId = 99L, contentUri = "content://dead", position = 0,
            title = "Song", artist = "Band", album = "LP",
        )
        val plan = PlaylistEntryRestorePlanner.plan(
            entries         = listOf(ambiguousEntry),
            resolve         = { resolver.resolve(it.songId, it.contentUri, it.title, it.artist, it.album) },
            existingSongIds = emptySet(),
            nextPosition    = 0,
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedUnmatched)
    }
}
