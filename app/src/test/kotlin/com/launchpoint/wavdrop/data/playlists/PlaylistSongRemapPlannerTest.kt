package com.launchpoint.wavdrop.data.playlists

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSongRemapPlannerTest {

    @Test
    fun `path title and duration uniquely map`() {
        val plan = plan(
            stale = listOf(song(1, folder = "Music\\Album")),
            fresh = listOf(song(11, folder = "/music/album/")),
        )

        assertMapping(plan, 1L, 11L, PlaylistSongRemapPlanner.MatchTier.PATH_TITLE_DURATION)
    }

    @Test
    fun `title artist album and duration map after folder move`() {
        val plan = plan(
            stale = listOf(song(1, folder = "Music/Old")),
            fresh = listOf(song(11, folder = "Music/New")),
        )

        assertMapping(plan, 1L, 11L, PlaylistSongRemapPlanner.MatchTier.TAGS_DURATION)
    }

    @Test
    fun `title artist and duration map after album change`() {
        val plan = plan(
            stale = listOf(song(1, album = "Old Album", folder = "Music/Old")),
            fresh = listOf(song(11, album = "New Album", folder = "Music/New")),
        )

        assertMapping(plan, 1L, 11L, PlaylistSongRemapPlanner.MatchTier.TITLE_ARTIST_DURATION)
    }

    @Test
    fun `exactly two seconds duration difference matches`() {
        val plan = plan(
            stale = listOf(song(1, duration = 180_000L)),
            fresh = listOf(song(11, duration = 182_000L)),
        )

        assertEquals(1, plan.mappings.size)
    }

    @Test
    fun `duration difference above two seconds rejects`() {
        val plan = plan(
            stale = listOf(song(1, duration = 180_000L)),
            fresh = listOf(song(11, duration = 182_001L)),
        )

        assertTrue(plan.mappings.isEmpty())
        assertEquals(setOf(1L), plan.unmatchedOldSongIds)
    }

    @Test
    fun `stronger tier ambiguity does not fall through`() {
        val stale = song(1, folder = "Music/Album")
        val plan = plan(
            stale = listOf(stale),
            fresh = listOf(
                song(11, artist = "Different A", album = "Different A", folder = "Music/Album"),
                song(12, artist = "Different B", album = "Different B", folder = "Music/Album"),
            ),
        )

        assertTrue(plan.mappings.isEmpty())
        assertEquals(setOf(1L), plan.ambiguousOldSongIds)
    }

    @Test
    fun `two stale songs targeting one new song reject both`() {
        val plan = plan(
            stale = listOf(
                song(1, folder = "Music/Old A"),
                song(2, folder = "Music/Old B"),
            ),
            fresh = listOf(song(11, folder = "Music/New")),
        )

        assertTrue(plan.mappings.isEmpty())
        assertEquals(setOf(1L, 2L), plan.conflictingOldSongIds)
    }

    @Test
    fun `title only does not match`() {
        val plan = plan(
            stale = listOf(song(1, artist = "Old Artist", album = "Old Album")),
            fresh = listOf(song(11, artist = "New Artist", album = "New Album")),
        )

        assertTrue(plan.mappings.isEmpty())
    }

    @Test
    fun `tolerant punctuation and suffix matching is not used`() {
        val plan = plan(
            stale = listOf(song(1, title = "Don't Stop")),
            fresh = listOf(song(11, title = "Dont Stop (Official Audio)")),
        )

        assertTrue(plan.mappings.isEmpty())
    }

    @Test
    fun `empty inputs produce no mappings`() {
        assertTrue(plan(emptyList(), emptyList()).mappings.isEmpty())
        assertEquals(setOf(1L), plan(listOf(song(1)), emptyList()).unmatchedOldSongIds)
    }

    @Test
    fun `output is deterministic regardless of input order`() {
        val stale = listOf(
            song(2, title = "Second", folder = "Old/Second"),
            song(1, title = "First", folder = "Old/First"),
        )
        val fresh = listOf(
            song(12, title = "Second", folder = "New/Second"),
            song(11, title = "First", folder = "New/First"),
        )

        val forward = plan(stale, fresh)
        val reversed = plan(stale.reversed(), fresh.reversed())

        assertEquals(forward, reversed)
        assertEquals(listOf(1L, 2L), forward.mappings.map { it.oldSongId })
    }

    private fun plan(stale: List<Song>, fresh: List<Song>) =
        PlaylistSongRemapPlanner.plan(staleSongs = stale, newSongs = fresh)

    private fun assertMapping(
        plan: PlaylistSongRemapPlanner.Plan,
        oldId: Long,
        newId: Long,
        tier: PlaylistSongRemapPlanner.MatchTier,
    ) {
        assertEquals(
            PlaylistSongRemapPlanner.Mapping(oldId, newId, tier),
            plan.mappings.single(),
        )
    }

    private fun song(
        id: Long,
        title: String = "Track",
        artist: String = "Artist",
        album: String = "Album",
        duration: Long = 180_000L,
        folder: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 1L,
        duration = duration,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 1,
        year = 2026,
        folderPath = folder,
    )
}
