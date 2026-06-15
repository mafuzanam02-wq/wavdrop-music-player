package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsDashboardBuilderTest {

    @Test
    fun `empty songs and stats returns empty summary`() {
        val summary = StatsDashboardBuilder.build(songs = emptyList(), stats = emptyList())

        assertEquals(0, summary.totalSongs)
        assertEquals(0, summary.totalPlayedTracks)
        assertEquals(0, summary.totalPlayCount)
        assertEquals(0, summary.totalSkipCount)
        assertEquals(0L, summary.totalListeningTimeMs)
        assertTrue(summary.mostPlayedSongs.isEmpty())
        assertTrue(summary.recentlyPlayedSongs.isEmpty())
        assertTrue(summary.mostSkippedSongs.isEmpty())
    }

    @Test
    fun `orphan stats are ignored`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Known")),
            stats = listOf(stats(songId = 1, playCount = 2), stats(songId = 99, playCount = 50)),
        )

        assertEquals(1, summary.totalPlayedTracks)
        assertEquals(2, summary.totalPlayCount)
        assertEquals(listOf("Known"), summary.mostPlayedSongs.map { it.song.title })
    }

    @Test
    fun `totals are calculated from matched stats`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "One"), song(2, "Two"), song(3, "Three")),
            stats = listOf(
                stats(songId = 1, playCount = 3, skipCount = 1, totalListeningTimeMs = 60_000L),
                stats(songId = 2, playCount = 0, skipCount = 4, totalListeningTimeMs = 0L),
                stats(songId = 3, playCount = 2, skipCount = 0, totalListeningTimeMs = 120_000L),
            ),
        )

        assertEquals(3, summary.totalSongs)
        assertEquals(2, summary.totalPlayedTracks)
        assertEquals(5, summary.totalPlayCount)
        assertEquals(5, summary.totalSkipCount)
        assertEquals(180_000L, summary.totalListeningTimeMs)
    }

    @Test
    fun `dashboard uses estimated listening time when actual time is zero`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Imported")),
            stats = listOf(stats(songId = 1, playCount = 3, totalListeningTimeMs = 0L)),
        )

        assertEquals(600_000L, summary.totalListeningTimeMs)
        assertEquals(600_000L, summary.mostPlayedSongs.single().totalListeningTimeMs)
    }

    @Test
    fun `dashboard does not inflate actual listening time`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Measured")),
            stats = listOf(stats(songId = 1, playCount = 20, totalListeningTimeMs = 90_000L)),
        )

        assertEquals(90_000L, summary.totalListeningTimeMs)
        assertEquals(90_000L, summary.mostPlayedSongs.single().totalListeningTimeMs)
    }

    @Test
    fun `most played is sorted by play count and excludes zero plays`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Middle"), song(2, "Top"), song(3, "Zero")),
            stats = listOf(
                stats(songId = 1, playCount = 4),
                stats(songId = 2, playCount = 9),
                stats(songId = 3, playCount = 0),
            ),
        )

        assertEquals(listOf("Top", "Middle"), summary.mostPlayedSongs.map { it.song.title })
    }

    @Test
    fun `recently played excludes missing timestamps and sorts newest first`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Old"), song(2, "Never"), song(3, "New")),
            stats = listOf(
                stats(songId = 1, lastPlayedAt = 100L),
                stats(songId = 2, lastPlayedAt = 0L),
                stats(songId = 3, lastPlayedAt = 300L),
            ),
        )

        assertEquals(listOf("New", "Old"), summary.recentlyPlayedSongs.map { it.song.title })
    }

    @Test
    fun `most skipped is sorted by skip count and excludes zero skips`() {
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "Low"), song(2, "High"), song(3, "Zero")),
            stats = listOf(
                stats(songId = 1, skipCount = 2),
                stats(songId = 2, skipCount = 8),
                stats(songId = 3, skipCount = 0),
            ),
        )

        assertEquals(listOf("High", "Low"), summary.mostSkippedSongs.map { it.song.title })
    }

    @Test
    fun `a song can appear in multiple section lists without being deduplicated`() {
        // Song 1 has plays AND was recently played AND has skips — it legitimately belongs in
        // all three lists.  The UI must use composite keys (e.g. "most_played_1") rather than
        // bare song.id keys so the same ID in multiple LazyColumn sections doesn't crash Compose.
        val summary = StatsDashboardBuilder.build(
            songs = listOf(song(1, "AllThree"), song(2, "OnlyPlays")),
            stats = listOf(
                stats(songId = 1, playCount = 5, skipCount = 3, lastPlayedAt = 1000L),
                stats(songId = 2, playCount = 2, skipCount = 0, lastPlayedAt = 0L),
            ),
        )

        val mostPlayedIds = summary.mostPlayedSongs.map { it.song.id }
        val recentlyPlayedIds = summary.recentlyPlayedSongs.map { it.song.id }
        val mostSkippedIds = summary.mostSkippedSongs.map { it.song.id }

        assertTrue("song 1 must appear in mostPlayedSongs", 1L in mostPlayedIds)
        assertTrue("song 1 must appear in recentlyPlayedSongs", 1L in recentlyPlayedIds)
        assertTrue("song 1 must appear in mostSkippedSongs", 1L in mostSkippedIds)

        // The same ID across sections — StatisticsScreen must prefix keys per-section.
        val allIds = mostPlayedIds + recentlyPlayedIds + mostSkippedIds
        assertTrue("song 1 id appears in more than one section list", allIds.count { it == 1L } > 1)
    }

    @Test
    fun `top lists are limited to ten songs`() {
        val songs = (1L..12L).map { song(it, "Song $it") }
        val stats = (1L..12L).map {
            stats(songId = it, playCount = it.toInt(), skipCount = it.toInt(), lastPlayedAt = it)
        }

        val summary = StatsDashboardBuilder.build(songs = songs, stats = stats)

        assertEquals(10, summary.mostPlayedSongs.size)
        assertEquals(10, summary.recentlyPlayedSongs.size)
        assertEquals(10, summary.mostSkippedSongs.size)
        assertEquals("Song 12", summary.mostPlayedSongs.first().song.title)
    }

    private fun song(id: Long, title: String) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun stats(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        lastPlayedAt: Long = 0L,
        totalListeningTimeMs: Long = 0L,
    ) = TrackStatsEntity(
        songId = songId,
        contentUri = "content://media/$songId",
        playCount = playCount,
        skipCount = skipCount,
        lastPlayedAt = lastPlayedAt,
        totalListeningTimeMs = totalListeningTimeMs,
    )
}
