package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningReportBuilderTest {

    @Test
    fun `empty songs and stats returns empty report`() {
        val report = ListeningReportBuilder.build(songs = emptyList(), stats = emptyList())

        assertEquals(0L, report.totalListeningTimeMs)
        assertEquals(0, report.totalPlayCount)
        assertEquals(0, report.totalSkipCount)
        assertEquals(0, report.tracksPlayed)
        assertEquals(0, report.artistsPlayed)
        assertEquals(0, report.albumsPlayed)
        assertTrue(report.topSongs.isEmpty())
        assertTrue(report.topArtists.isEmpty())
        assertTrue(report.topAlbums.isEmpty())
        assertNull(report.mostPlayedTrack)
        assertNull(report.mostPlayedArtist)
        assertNull(report.mostPlayedAlbum)
        assertNull(report.mostSkippedTrack)
        assertTrue(report.recentlyPlayedSongs.isEmpty())
        assertTrue(report.recentlyActiveArtists.isEmpty())
    }

    @Test
    fun `orphan stats are ignored`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(song(id = 1, title = "Known")),
            stats = listOf(
                stats(songId = 1, playCount = 2, skipCount = 1),
                stats(songId = 99, playCount = 100, skipCount = 100),
            ),
        )

        assertEquals(2, report.totalPlayCount)
        assertEquals(1, report.totalSkipCount)
        assertEquals(listOf("Known"), report.topSongs.map { it.song.title })
    }

    @Test
    fun `overview counts played tracks artists and albums by play count`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(
                song(id = 1, title = "One", artist = "Artist A", album = "Album A"),
                song(id = 2, title = "Two", artist = "Artist A", album = "Album B"),
                song(id = 3, title = "Three", artist = "Artist B", album = "Album B"),
                song(id = 4, title = "Skipped Only", artist = "Artist C", album = "Album C"),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 3, skipCount = 1, totalListeningTimeMs = 60_000L),
                stats(songId = 2, playCount = 2, skipCount = 0, totalListeningTimeMs = 30_000L),
                stats(songId = 3, playCount = 0, skipCount = 2, totalListeningTimeMs = 0L),
                stats(songId = 4, playCount = 0, skipCount = 4, totalListeningTimeMs = 0L),
            ),
        )

        assertEquals(5, report.totalPlayCount)
        assertEquals(7, report.totalSkipCount)
        assertEquals(90_000L, report.totalListeningTimeMs)
        assertEquals(2, report.tracksPlayed)
        assertEquals(1, report.artistsPlayed)
        assertEquals(2, report.albumsPlayed)
    }

    @Test
    fun `song artist and album totals use effective listening time`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(
                song(id = 1, title = "Imported One", artist = "Artist A", album = "Album A", duration = 60_000L),
                song(id = 2, title = "Measured Two", artist = "Artist A", album = "Album A", duration = 120_000L),
                song(id = 3, title = "Imported Three", artist = "Artist B", album = "Album B", duration = 30_000L),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 2, totalListeningTimeMs = 0L),
                stats(songId = 2, playCount = 8, totalListeningTimeMs = 75_000L),
                stats(songId = 3, playCount = 3, totalListeningTimeMs = 0L),
            ),
        )

        assertEquals(285_000L, report.totalListeningTimeMs)
        assertEquals(120_000L, report.topSongs.first { it.song.id == 1L }.totalListeningTimeMs)
        assertEquals(195_000L, report.topArtists.first { it.artistKey == "Artist A" }.totalListeningTimeMs)
        assertEquals(195_000L, report.topAlbums.first { it.albumKey == "Album A" }.totalListeningTimeMs)
    }

    @Test
    fun `listening report does not inflate actual listening time`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(song(id = 1, title = "Measured", duration = 60_000L)),
            stats = listOf(stats(songId = 1, playCount = 10, totalListeningTimeMs = 40_000L)),
        )

        assertEquals(40_000L, report.totalListeningTimeMs)
        assertEquals(40_000L, report.topSongs.single().totalListeningTimeMs)
        assertEquals(40_000L, report.topArtists.single().totalListeningTimeMs)
        assertEquals(40_000L, report.topAlbums.single().totalListeningTimeMs)
    }

    @Test
    fun `top songs artists and albums are sorted by aggregated play count`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(
                song(id = 1, title = "Small", artist = "Artist A", album = "Album A"),
                song(id = 2, title = "Large One", artist = "Artist B", album = "Album B"),
                song(id = 3, title = "Large Two", artist = "Artist B", album = "Album B"),
                song(id = 4, title = "Medium", artist = "Artist C", album = "Album C"),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 2),
                stats(songId = 2, playCount = 5),
                stats(songId = 3, playCount = 4),
                stats(songId = 4, playCount = 6),
            ),
        )

        assertEquals(listOf("Medium", "Large One", "Large Two", "Small"), report.topSongs.map { it.song.title })
        assertEquals(listOf("Artist B", "Artist C", "Artist A"), report.topArtists.map { it.artistKey })
        assertEquals(9, report.topArtists.first().playCount)
        assertEquals(listOf("Album B", "Album C", "Album A"), report.topAlbums.map { it.albumKey })
        assertEquals(9, report.topAlbums.first().playCount)
    }

    @Test
    fun `listening habits choose top played and skipped items`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(
                song(id = 1, title = "Most Played", artist = "Artist A", album = "Album A"),
                song(id = 2, title = "Most Skipped", artist = "Artist B", album = "Album B"),
                song(id = 3, title = "Artist Booster", artist = "Artist B", album = "Album B"),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 8, skipCount = 1),
                stats(songId = 2, playCount = 2, skipCount = 9),
                stats(songId = 3, playCount = 7, skipCount = 0),
            ),
        )

        assertEquals("Most Played", report.mostPlayedTrack?.song?.title)
        assertEquals("Artist B", report.mostPlayedArtist?.artistKey)
        assertEquals("Album B", report.mostPlayedAlbum?.albumKey)
        assertEquals("Most Skipped", report.mostSkippedTrack?.song?.title)
    }

    @Test
    fun `recent activity sorts songs and artists by last played`() {
        val report = ListeningReportBuilder.build(
            songs = listOf(
                song(id = 1, title = "Old", artist = "Older Artist", album = "Album A"),
                song(id = 2, title = "Never", artist = "Never Artist", album = "Album B"),
                song(id = 3, title = "New", artist = "Newer Artist", album = "Album C"),
                song(id = 4, title = "Newest Artist Song", artist = "Older Artist", album = "Album D"),
            ),
            stats = listOf(
                stats(songId = 1, playCount = 1, lastPlayedAt = 100L),
                stats(songId = 2, playCount = 1, lastPlayedAt = 0L),
                stats(songId = 3, playCount = 1, lastPlayedAt = 300L),
                stats(songId = 4, playCount = 1, lastPlayedAt = 400L),
            ),
        )

        assertEquals(
            listOf("Newest Artist Song", "New", "Old"),
            report.recentlyPlayedSongs.map { it.song.title },
        )
        assertEquals(
            listOf("Older Artist", "Newer Artist"),
            report.recentlyActiveArtists.map { it.artistKey },
        )
    }

    @Test
    fun `top report lists are limited to ten`() {
        val songs = (1L..12L).map {
            song(id = it, title = "Song $it", artist = "Artist $it", album = "Album $it")
        }
        val stats = (1L..12L).map {
            stats(songId = it, playCount = it.toInt(), lastPlayedAt = it)
        }

        val report = ListeningReportBuilder.build(songs = songs, stats = stats)

        assertEquals(10, report.topSongs.size)
        assertEquals(10, report.topArtists.size)
        assertEquals(10, report.topAlbums.size)
        assertEquals(10, report.recentlyPlayedSongs.size)
        assertEquals(10, report.recentlyActiveArtists.size)
        assertEquals("Song 12", report.topSongs.first().song.title)
    }

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        duration: Long = 200_000L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
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
