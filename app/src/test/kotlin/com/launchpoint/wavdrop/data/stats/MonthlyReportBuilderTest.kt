package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary.DataAccuracy
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyReportBuilderTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `availableMonths ignores aggregate stats and uses event history only`() {
        val stats = listOf(
            stats(songId = 1, lastPlayedAt = epochMs(2026, 3, 10)),
            stats(songId = 2, lastPlayedAt = epochMs(2026, 5, 1)),
        )

        val months = MonthlyReportBuilder.availableMonths(stats, emptyList(), utc)

        assertTrue(months.isEmpty())
    }

    @Test
    fun `availableMonths includes play and skip event months most recent first`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 15)),
            skipEvent(songId = 3, occurredAt = epochMs(2026, 5, 20)),
            playEvent(songId = 4, occurredAt = epochMs(2026, 4, 1)),
        )

        val months = MonthlyReportBuilder.availableMonths(emptyList(), events, utc)

        assertEquals(listOf(MonthYear(2026, 6), MonthYear(2026, 5), MonthYear(2026, 4)), months)
    }

    @Test
    fun `build no event history does not fake monthly stats from aggregates`() {
        val report = MonthlyReportBuilder.build(
            month = MonthYear(2026, 3),
            songs = listOf(song(id = 1)),
            stats = listOf(
                stats(
                    songId = 1,
                    lastPlayedAt = epochMs(2026, 3, 15),
                    playCount = 99,
                    skipCount = 12,
                    totalListeningTimeMs = 500_000L,
                ),
            ),
            events = emptyList(),
            zone = utc,
        )

        assertEquals(DataAccuracy.NO_EVENT_HISTORY, report.dataAccuracy)
        assertEquals(ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE, report.emptyStateReason)
        assertEquals(0, report.totalPlayCount)
        assertEquals(0, report.totalSkipCount)
        assertEquals(0L, report.totalListeningTimeMs)
        assertEquals(0, report.activeSongCount)
        assertTrue(report.topSongs.isEmpty())
        assertNull(report.mostSkippedTrack)
    }

    @Test
    fun `build counts only events inside requested month`() {
        val month = MonthYear(2026, 6)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 5), listenedMs = 30_000L),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 10), listenedMs = 25_000L),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 15), listenedMs = 40_000L),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 6, 16)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 5, 30), listenedMs = 60_000L),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 7, 1)),
        )
        val report = MonthlyReportBuilder.build(
            month = month,
            songs = listOf(song(id = 1), song(id = 2)),
            stats = listOf(stats(songId = 99, playCount = 1000)),
            events = events,
            zone = utc,
        )

        assertEquals(DataAccuracy.EVENT_BACKED, report.dataAccuracy)
        assertEquals(3, report.totalPlayCount)
        assertEquals(1, report.totalSkipCount)
        assertEquals(95_000L, report.totalListeningTimeMs)
        assertEquals(2, report.activeSongCount)
        assertEquals(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, report.emptyStateReason)
    }

    @Test
    fun `build ranks monthly top songs artists and albums from events only`() {
        val month = MonthYear(2026, 6)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 6)),
        )
        val songs = listOf(
            song(id = 1, title = "Low", artist = "Artist C", album = "Album C"),
            song(id = 2, title = "Mid", artist = "Artist B", album = "Album B"),
            song(id = 3, title = "High", artist = "Artist A", album = "Album A"),
        )

        val report = MonthlyReportBuilder.build(month, songs, emptyList(), events, utc)

        assertEquals(listOf("High", "Mid", "Low"), report.topSongs.map { it.song.title })
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), report.topArtists.map { it.artistKey })
        assertEquals(listOf("Album A", "Album B", "Album C"), report.topAlbums.map { it.albumKey })
    }

    @Test
    fun `build surfaces listening days busiest day and average plays per day`() {
        val month = MonthYear(2026, 6)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 10)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 10)),
        )

        val report = MonthlyReportBuilder.build(
            month = month,
            songs = listOf(song(id = 1), song(id = 2), song(id = 3)),
            stats = emptyList(),
            events = events,
            zone = utc,
        )

        assertEquals(2, report.listeningDaysCount)
        assertEquals(LocalDate.of(2026, 6, 3), report.busiestDay)
        assertEquals(3, report.busiestDayPlayCount)
        assertEquals(2.5, report.averagePlaysPerActiveDay, 0.001)
    }

    @Test
    fun `build skip only month remains event backed and exposes skip details`() {
        val month = MonthYear(2026, 6)
        val events = listOf(
            skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 3)),
            skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 4)),
        )

        val report = MonthlyReportBuilder.build(
            month = month,
            songs = listOf(song(id = 1, title = "Skipped")),
            stats = emptyList(),
            events = events,
            zone = utc,
        )

        assertEquals(DataAccuracy.EVENT_BACKED, report.dataAccuracy)
        assertEquals(0, report.totalPlayCount)
        assertEquals(2, report.totalSkipCount)
        assertEquals(0, report.listeningDaysCount)
        assertEquals("Skipped", report.mostSkippedTrack?.song?.title)
        assertEquals(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, report.emptyStateReason)
    }

    @Test
    fun `build orphan event month reports empty-state reason`() {
        val month = MonthYear(2026, 6)
        val events = listOf(
            playEvent(songId = 99, occurredAt = epochMs(2026, 6, 5)),
        )

        val report = MonthlyReportBuilder.build(
            month = month,
            songs = emptyList(),
            stats = emptyList(),
            events = events,
            zone = utc,
        )

        assertEquals(DataAccuracy.EVENT_BACKED, report.dataAccuracy)
        assertEquals(1, report.totalPlayCount)
        assertEquals(0, report.activeSongCount)
        assertTrue(report.topSongs.isEmpty())
        assertEquals(ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS, report.emptyStateReason)
    }

    @Test
    fun `build result lists are limited to ten entries`() {
        val month = MonthYear(2026, 6)
        val songs = (1L..12L).map { song(id = it, title = "Song $it", artist = "Artist $it", album = "Album $it") }
        val events = (1L..12L).map { playEvent(songId = it, occurredAt = epochMs(2026, 6, it.toInt())) }
        val report = MonthlyReportBuilder.build(month, songs, emptyList(), events, utc)

        assertEquals(10, report.topSongs.size)
        assertEquals(10, report.topArtists.size)
        assertEquals(10, report.topAlbums.size)
        assertEquals(10, report.recentlyPlayedInMonth.size)
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

    private fun stats(
        songId: Long,
        lastPlayedAt: Long = 0L,
        playCount: Int = 0,
        skipCount: Int = 0,
        totalListeningTimeMs: Long = 0L,
    ) = TrackStatsEntity(
        songId = songId,
        contentUri = "content://media/$songId",
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        skipCount = skipCount,
        totalListeningTimeMs = totalListeningTimeMs,
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
