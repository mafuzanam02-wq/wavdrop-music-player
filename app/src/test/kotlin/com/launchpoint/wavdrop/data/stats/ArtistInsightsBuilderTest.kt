package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistInsightsBuilderTest {

    @Test
    fun `empty songs and stats returns empty insights`() {
        val summary = ArtistInsightsBuilder.build(songs = emptyList(), stats = emptyList())

        assertEquals(0, summary.totalSongs)
        assertEquals(0, summary.totalAlbums)
        assertEquals(0, summary.totalPlayCount)
        assertEquals(0, summary.totalSkipCount)
        assertEquals(0L, summary.totalListeningTimeMs)
        assertNull(summary.lastPlayedAt)
        assertTrue(summary.topSongs.isEmpty())
        assertTrue(summary.topAlbums.isEmpty())
        assertTrue(summary.recentActivity.isEmpty())
    }

    @Test
    fun `totals are calculated for artist songs and orphan stats are ignored`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(song(1, "One", album = "A"), song(2, "Two", album = "B")),
            stats = listOf(
                stats(songId = 1, playCount = 3, skipCount = 1, totalListeningTimeMs = 60_000L),
                stats(songId = 2, playCount = 4, skipCount = 2, totalListeningTimeMs = 90_000L),
                stats(songId = 99, playCount = 100, skipCount = 100, totalListeningTimeMs = 999_000L),
            ),
        )

        assertEquals(2, summary.totalSongs)
        assertEquals(2, summary.totalAlbums)
        assertEquals(7, summary.totalPlayCount)
        assertEquals(3, summary.totalSkipCount)
        assertEquals(150_000L, summary.totalListeningTimeMs)
    }

    @Test
    fun `artist and album insight totals use effective listening time`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(
                song(1, "Imported One", album = "Album A", duration = 60_000L),
                song(2, "Measured Two", album = "Album A", duration = 90_000L),
                song(3, "Imported Three", album = "Album B", duration = 30_000L),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 2, totalListeningTimeMs = 0L),
                stats(songId = 2, playCount = 8, totalListeningTimeMs = 80_000L),
                stats(songId = 3, playCount = 3, totalListeningTimeMs = 0L),
            ),
        )

        assertEquals(290_000L, summary.totalListeningTimeMs)
        assertEquals(200_000L, summary.topAlbums.first { it.albumKey == "Album A" }.totalListeningTimeMs)
        assertEquals(90_000L, summary.topAlbums.first { it.albumKey == "Album B" }.totalListeningTimeMs)
    }

    @Test
    fun `artist insights do not inflate actual listening time`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(song(1, "Measured", album = "Album A", duration = 60_000L)),
            stats = listOf(stats(songId = 1, playCount = 10, totalListeningTimeMs = 45_000L)),
        )

        assertEquals(45_000L, summary.totalListeningTimeMs)
        assertEquals(45_000L, summary.topSongs.single().totalListeningTimeMs)
        assertEquals(45_000L, summary.topAlbums.single().totalListeningTimeMs)
    }

    @Test
    fun `top songs are sorted by play count and exclude zero plays`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(song(1, "Middle"), song(2, "Top"), song(3, "Zero")),
            stats = listOf(
                stats(songId = 1, playCount = 4),
                stats(songId = 2, playCount = 9),
                stats(songId = 3, playCount = 0),
            ),
        )

        assertEquals(listOf("Top", "Middle"), summary.topSongs.map { it.song.title })
    }

    @Test
    fun `top albums aggregate plays by album`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(
                song(1, "One", album = "Album A"),
                song(2, "Two", album = "Album A"),
                song(3, "Three", album = "Album B"),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 2, skipCount = 1),
                stats(songId = 2, playCount = 5, skipCount = 2),
                stats(songId = 3, playCount = 4, skipCount = 0),
            ),
        )

        assertEquals(listOf("Album A", "Album B"), summary.topAlbums.map { it.albumKey })
        assertEquals(7, summary.topAlbums.first().playCount)
        assertEquals(2, summary.topAlbums.first().songCount)
        assertEquals(3, summary.topAlbums.first().skipCount)
    }

    @Test
    fun `recent activity sorts by latest last played and excludes missing timestamps`() {
        val summary = ArtistInsightsBuilder.build(
            songs = listOf(song(1, "Old"), song(2, "Never"), song(3, "New")),
            stats = listOf(
                stats(songId = 1, lastPlayedAt = 100L),
                stats(songId = 2, lastPlayedAt = 0L),
                stats(songId = 3, lastPlayedAt = 300L),
            ),
        )

        assertEquals(300L, summary.lastPlayedAt)
        assertEquals(listOf("New", "Old"), summary.recentActivity.map { it.song.title })
    }

    @Test
    fun `top lists are limited to five items`() {
        val songs = (1L..7L).map { song(it, "Song $it", album = "Album $it") }
        val stats = (1L..7L).map {
            stats(songId = it, playCount = it.toInt(), lastPlayedAt = it)
        }

        val summary = ArtistInsightsBuilder.build(songs = songs, stats = stats)

        assertEquals(5, summary.topSongs.size)
        assertEquals(5, summary.topAlbums.size)
        assertEquals(5, summary.recentActivity.size)
        assertEquals("Song 7", summary.topSongs.first().song.title)
    }

    private fun song(
        id: Long,
        title: String,
        album: String = "Album",
        duration: Long = 200_000L,
    ) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = album,
        albumId = id,
        duration = duration,
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
