package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWrappedPreviewTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `no events has no latest timestamp and no Home wrapped preview`() {
        assertNull(latestAnalyticsEventAt(emptyList()))
        assertNull(buildHomeWrappedPreview(year = 2026, songs = listOf(song(1)), events = emptyList(), zone = utc))
    }

    @Test
    fun `latest play determines activity year`() {
        val events = listOf(
            skipEvent(songId = 1, occurredAt = epochMs(2025, 12, 31)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
        )

        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)

        assertEquals(2026, selection?.year)
    }

    @Test
    fun `latest skip determines activity year`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2025, 12, 31)),
            skipEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
        )

        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)

        assertEquals(2026, selection?.year)
    }

    @Test
    fun `unsupported event type does not affect latest analytics year`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2025, 12, 31)),
            playEvent(songId = 1, occurredAt = epochMs(2027, 1, 1)).copy(eventType = "PAUSE"),
        )

        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)

        assertEquals(2025, selection?.year)
    }

    @Test
    fun `latest activity year selection matches availableYears first`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2024, 2, 1)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 3, 1)),
            playEvent(songId = 3, occurredAt = epochMs(2025, 4, 1)),
            playEvent(songId = 4, occurredAt = epochMs(2029, 1, 1)).copy(eventType = "PAUSE"),
        )

        val oldLatestYear = WrappedBuilder.availableYears(events, utc).firstOrNull()
        val newLatestYear = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)?.year

        assertEquals(oldLatestYear, newLatestYear)
    }

    @Test
    fun `year range uses supplied local zone boundaries`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val latestAt = LocalDate.of(2026, 1, 1)
            .atStartOfDay(zone)
            .plusMinutes(30)
            .toInstant()
            .toEpochMilli()

        val selection = homeWrappedYearSelection(latestAt, zone)!!

        assertEquals(2026, selection.year)
        assertEquals(
            LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            selection.range.fromMs,
        )
        assertEquals(
            LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L,
            selection.range.toMs,
        )
    }

    @Test
    fun `Home bounded range includes only latest year events`() {
        val events = multiYearEvents()
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!

        val bounded = events.filter { selection.range.contains(it.occurredAt) }

        assertEquals(2026, selection.year)
        assertEquals(listOf(2L, 2L, 3L, 3L), bounded.map { it.songId })
    }

    @Test
    fun `older year insertion leaves latest year wrapped result unchanged`() {
        val songs = listOf(song(1), song(2), song(3))
        val events = multiYearEvents()
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!
        val before = buildHomeWrappedPreview(
            year = selection.year,
            songs = songs,
            events = events.filter { selection.range.contains(it.occurredAt) },
            zone = utc,
        )
        val restoredOlderEvents = events + playEvent(songId = 1, occurredAt = epochMs(2024, 5, 1), listenedMs = 500_000L)
        val after = buildHomeWrappedPreview(
            year = selection.year,
            songs = songs,
            events = restoredOlderEvents.filter { selection.range.contains(it.occurredAt) },
            zone = utc,
        )

        assertEquals(before, after)
    }

    @Test
    fun `newer year event switches observed range`() {
        val events = multiYearEvents()
        val nextYearEvents = events + playEvent(songId = 1, occurredAt = epochMs(2027, 1, 1))

        val before = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!
        val after = homeWrappedYearSelection(latestAnalyticsEventAt(nextYearEvents), utc)!!

        assertEquals(2026, before.year)
        assertEquals(2027, after.year)
        assertFalse(after.range.contains(epochMs(2026, 12, 31)))
    }

    @Test
    fun `latest year event addition updates wrapped preview`() {
        val songs = listOf(song(2), song(3))
        val events = multiYearEvents()
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!
        val before = buildHomeWrappedPreview(
            year = selection.year,
            songs = songs,
            events = events.filter { selection.range.contains(it.occurredAt) },
            zone = utc,
        )!!
        val afterEvents = events + playEvent(songId = 3, occurredAt = epochMs(2026, 12, 1), listenedMs = 80_000L)
        val after = buildHomeWrappedPreview(
            year = selection.year,
            songs = songs,
            events = afterEvents.filter { selection.range.contains(it.occurredAt) },
            zone = utc,
        )!!

        assertEquals(before.totalPlayCount + 1, after.totalPlayCount)
        assertEquals(before.totalListeningTimeMs + 80_000L, after.totalListeningTimeMs)
    }

    @Test
    fun `song library change rebuilds latest year preview`() {
        val events = listOf(playEvent(songId = 9, occurredAt = epochMs(2026, 6, 1)))
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!

        val withoutSong = buildHomeWrappedPreview(selection.year, songs = emptyList(), events = events, zone = utc)
        val withSong = buildHomeWrappedPreview(selection.year, songs = listOf(song(9)), events = events, zone = utc)

        assertNull(withoutSong)
        assertTrue(withSong?.hasActivity == true)
        assertEquals(listOf(9L), withSong?.topSongs?.map { it.song.id })
    }

    @Test
    fun `empty matched library semantics remain unchanged`() {
        val events = listOf(playEvent(songId = 99, occurredAt = epochMs(2026, 6, 1)))
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!

        val preview = buildHomeWrappedPreview(selection.year, songs = emptyList(), events = events, zone = utc)

        assertNull(preview)
    }

    @Test
    fun `bounded latest year summary matches old lifetime input summary`() {
        val songs = listOf(
            song(id = 1, title = "Older", artist = "Artist A", album = "Album A"),
            song(id = 2, title = "Latest A", artist = "Artist B", album = "Album B"),
            song(id = 3, title = "Latest B", artist = "Artist B", album = "Album C"),
        )
        val events = multiYearEvents()

        val latestYear = WrappedBuilder.availableYears(events, utc).first()
        val oldSummary = WrappedBuilder.buildYear(latestYear, songs, events, utc)
        val selection = homeWrappedYearSelection(latestAnalyticsEventAt(events), utc)!!
        val latestYearEvents = events.filter { selection.range.contains(it.occurredAt) }
        val newSummary = WrappedBuilder.buildYear(selection.year, songs, latestYearEvents, utc)

        assertEquals(oldSummary, newSummary)
        assertEquals(3, newSummary.totalPlayCount)
        assertEquals(1, newSummary.totalSkipCount)
        assertEquals(210_000L, newSummary.totalListeningTimeMs)
        assertEquals(2, newSummary.listeningDaysCount)
        assertEquals("Artist B", newSummary.topArtists.first().artistKey)
    }

    private fun multiYearEvents(): List<TrackListenEventEntity> = listOf(
        playEvent(songId = 1, occurredAt = epochMs(2024, 1, 1), listenedMs = 999_000L),
        skipEvent(songId = 1, occurredAt = epochMs(2025, 2, 1)),
        playEvent(songId = 2, occurredAt = epochMs(2026, 3, 1), listenedMs = 60_000L),
        playEvent(songId = 2, occurredAt = epochMs(2026, 3, 2), listenedMs = 70_000L),
        playEvent(songId = 3, occurredAt = epochMs(2026, 3, 2), listenedMs = 80_000L),
        skipEvent(songId = 3, occurredAt = epochMs(2026, 4, 1)),
    )

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
