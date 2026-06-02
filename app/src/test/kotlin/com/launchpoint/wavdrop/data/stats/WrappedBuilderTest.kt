package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.Song
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedBuilderTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `availableYears returns event-backed years most recent first`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2024, 1, 1)),
            skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2025, 3, 1)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 8, 1)),
        )

        val years = WrappedBuilder.availableYears(events, utc)

        assertEquals(listOf(2026, 2025, 2024), years)
    }

    @Test
    fun `buildYear uses only events inside the requested calendar year`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1), listenedMs = 10_000L),
            playEvent(songId = 1, occurredAt = epochMs(2026, 12, 31), listenedMs = 20_000L),
            playEvent(songId = 2, occurredAt = epochMs(2025, 12, 31), listenedMs = 30_000L),
            skipEvent(songId = 2, occurredAt = epochMs(2027, 1, 1)),
        )

        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1), song(2)),
            events = events,
            zone = utc,
        )

        assertEquals(2026, wrapped.year)
        assertEquals(2, wrapped.totalPlayCount)
        assertEquals(0, wrapped.totalSkipCount)
        assertEquals(30_000L, wrapped.totalListeningTimeMs)
        assertEquals(listOf(1L), wrapped.topSongs.map { it.song.id })
    }

    @Test
    fun `top songs are ranked by yearly play count`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 6)),
        )
        val songs = listOf(
            song(id = 1, title = "Low"),
            song(id = 2, title = "Mid"),
            song(id = 3, title = "High"),
        )

        val wrapped = WrappedBuilder.buildYear(2026, songs, events, utc)

        assertEquals(listOf("High", "Mid", "Low"), wrapped.topSongs.map { it.song.title })
        assertEquals("High", wrapped.mostPlayedSong?.song?.title)
    }

    @Test
    fun `top artists are ranked by yearly play count`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 6)),
        )
        val songs = listOf(
            song(id = 1, artist = "Artist C"),
            song(id = 2, artist = "Artist B"),
            song(id = 3, artist = "Artist A"),
        )

        val wrapped = WrappedBuilder.buildYear(2026, songs, events, utc)

        assertEquals(listOf("Artist A", "Artist B", "Artist C"), wrapped.topArtists.map { it.artistKey })
        assertEquals("Artist A", wrapped.mostPlayedArtist?.artistKey)
    }

    @Test
    fun `top albums are ranked by yearly play count`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 1, 6)),
        )
        val songs = listOf(
            song(id = 1, album = "Album C"),
            song(id = 2, album = "Album B"),
            song(id = 3, album = "Album A"),
        )

        val wrapped = WrappedBuilder.buildYear(2026, songs, events, utc)

        assertEquals(listOf("Album A", "Album B", "Album C"), wrapped.topAlbums.map { it.albumKey })
        assertEquals("Album A", wrapped.mostPlayedAlbum?.albumKey)
    }

    @Test
    fun `listening days and busiest day are surfaced from yearly events`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 10)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 7, 10)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 8, 10)),
        )

        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1), song(2), song(3)),
            events = events,
            zone = utc,
        )

        assertEquals(2, wrapped.listeningDaysCount)
        assertEquals(LocalDate.of(2026, 6, 3), wrapped.busiestDay)
        assertEquals(3, wrapped.busiestDayPlayCount)
        assertEquals(2.5, wrapped.averagePlaysPerActiveDay, 0.001)
        assertEquals(1, wrapped.totalSkipCount)
    }

    @Test
    fun `empty year has no event history empty state`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1)),
            events = emptyList(),
            zone = utc,
        )

        assertEquals(ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE, wrapped.emptyState.reason)
        assertTrue(wrapped.emptyState.isEmpty)
        assertFalse(wrapped.hasActivity)
        assertTrue(wrapped.topSongs.isEmpty())
        assertNull(wrapped.busiestDay)
    }

    @Test
    fun `orphan-only year keeps totals but reports orphan empty state`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = emptyList(),
            events = listOf(
                playEvent(songId = 99, occurredAt = epochMs(2026, 2, 1)),
            ),
            zone = utc,
        )

        assertEquals(1, wrapped.totalPlayCount)
        assertEquals(0, wrapped.uniqueSongsPlayedCount)
        assertTrue(wrapped.topSongs.isEmpty())
        assertEquals(ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS, wrapped.emptyState.reason)
        assertTrue(wrapped.emptyState.isEmpty)
    }

    private fun epochMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    private fun song(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String = "Album",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = id,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun playEvent(
        songId: Long,
        occurredAt: Long,
        listenedMs: Long = 60_000L,
    ) = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_PLAY,
        occurredAt = occurredAt,
        listenedMs = listenedMs,
        durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun skipEvent(
        songId: Long,
        occurredAt: Long,
    ) = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_SKIP,
        occurredAt = occurredAt,
        listenedMs = 0L,
        durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )
}
