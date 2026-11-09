package com.launchpoint.wavdrop.data.export

import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExportPlannerTest {
    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = 0L,
        duration = 60_000L, uri = "content://media/external/audio/media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )
    private fun row(pos: Int, songId: Long) = PlaylistSongEntity(playlistId = 1, songId = songId, position = pos)
    private val songs = (1L..5L).associateWith(::song)
    private val paths = (1L..5L).associateWith { "/storage/emulated/0/Music/$it.mp3" }

    private fun plan(rows: List<PlaylistSongEntity>, s: Map<Long, Song> = songs, p: Map<Long, String> = paths) =
        PlaylistExportPlanner.plan(1, "Mix", rows, s, p)

    @Test fun `entries follow saved position order regardless of input order`() {
        val p = plan(listOf(row(2, 3), row(0, 1), row(1, 2)))
        assertEquals(listOf(1L, 2L, 3L), p.entries.map { it.songId })
        assertEquals(listOf(0, 1, 2), p.entries.map { it.position })
    }

    @Test fun `duplicate occurrences are all kept`() {
        val p = plan(listOf(row(0, 1), row(1, 2), row(2, 1), row(3, 1)))
        assertEquals(listOf(1L, 2L, 1L, 1L), p.entries.map { it.songId })
        assertEquals(4, p.totalCount)
    }

    @Test fun `missing song rows are unresolved not dropped`() {
        val p = plan(listOf(row(0, 1), row(1, 99), row(2, 2)))
        assertEquals(3, p.totalCount); assertEquals(2, p.resolvedCount); assertEquals(1, p.unresolvedCount)
        assertEquals(UnresolvedReason.SongRowMissing, p.unresolved.single().reason)
        assertEquals(1, p.unresolved.single().position)
    }

    @Test fun `songs without a media path or with an unusable path are unresolved`() {
        val p = plan(
            listOf(row(0, 1), row(1, 2), row(2, 3)),
            p = mapOf(1L to "/ok.mp3", 3L to "content://media/external/audio/media/3"),
        )
        assertEquals(listOf(1L), p.entries.map { it.songId })
        assertEquals(
            listOf(UnresolvedReason.NoFilePath, UnresolvedReason.UnusablePath),
            p.unresolved.map { it.reason },
        )
    }

    @Test fun `empty playlist plans to zero`() {
        val p = plan(emptyList())
        assertEquals(0, p.totalCount); assertTrue(p.entries.isEmpty())
    }

    @Test fun `internal and sd card paths are kept verbatim in order`() {
        val p = plan(
            listOf(row(0, 1), row(1, 2)),
            p = mapOf(1L to "/storage/emulated/0/Music/a.mp3", 2L to "/storage/1A2B-3C4D/Music/b.mp3"),
        )
        assertEquals(
            listOf("/storage/emulated/0/Music/a.mp3", "/storage/1A2B-3C4D/Music/b.mp3"),
            p.entries.map { it.entry.path },
        )
    }

    @Test fun `planner does not mutate its inputs`() {
        val rows = listOf(row(2, 3), row(0, 1))
        val copy = rows.toList(); val sCopy = songs.toMap(); val pCopy = paths.toMap()
        plan(rows)
        assertEquals(copy, rows); assertEquals(sCopy, songs); assertEquals(pCopy, paths)
    }

    @Test fun `suggested file name is sanitized and keeps m3u8 extension`() {
        fun name(n: String) = PlaylistExportPlanner.plan(1, n, emptyList(), emptyMap(), emptyMap()).suggestedFileName
        assertEquals("My_ Mix.m3u8", name("My/ Mix"))
        assertEquals("playlist.m3u8", name("  "))
        assertEquals("Café ♪.m3u8", name("Café ♪"))
    }
}
