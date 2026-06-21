package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import java.time.DayOfWeek
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

    // ── V1 tests ──────────────────────────────────────────────────────────────

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

        assertEquals(2026, (wrapped.period as WrappedPeriod.Yearly).year)
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

    // ── V2 insight tests ──────────────────────────────────────────────────────

    @Test
    fun `availableMonths returns event-backed months newest first and ignores unsupported events`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            skipEvent(songId = 1, occurredAt = epochMs(2026, 3, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 2, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 4, 1))
                .copy(eventType = "PAUSE"),
        )

        assertEquals(
            listOf(MonthYear(2026, 3), MonthYear(2026, 2), MonthYear(2026, 1)),
            WrappedBuilder.availableMonths(events, utc),
        )
    }

    @Test
    fun `availableMonths respects timezone at month boundary`() {
        val event = playEvent(
            songId = 1,
            occurredAt = epochMs(2026, 1, 1) + 30 * 60_000L,
        )

        assertEquals(
            listOf(MonthYear(2025, 12)),
            WrappedBuilder.availableMonths(
                events = listOf(event),
                zone = ZoneId.of("America/Los_Angeles"),
            ),
        )
    }

    @Test
    fun `monthly period includes selected month and excludes adjacent months`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 5, 31)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 30)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 1)),
        )

        val wrapped = WrappedBuilder.buildPeriod(
            period = WrappedPeriod.month(MonthYear(2026, 6), utc),
            songs = listOf(song(1)),
            events = events,
        )

        assertEquals(2, wrapped.totalPlayCount)
        assertEquals(MonthYear(2026, 6), (wrapped.period as WrappedPeriod.Monthly).month)
    }

    @Test
    fun `monthly top songs artists and albums use selected month activity`() {
        val songs = listOf(
            song(id = 1, title = "Low", artist = "Artist C", album = "Album C"),
            song(id = 2, title = "Mid", artist = "Artist B", album = "Album B"),
            song(id = 3, title = "High", artist = "Artist A", album = "Album A"),
        )
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 6)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 2)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 7, 4)),
        )

        val wrapped = WrappedBuilder.buildPeriod(
            period = WrappedPeriod.month(MonthYear(2026, 6), utc),
            songs = songs,
            events = events,
        )

        assertEquals(listOf("High", "Mid", "Low"), wrapped.topSongs.map { it.song.title })
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), wrapped.topArtists.map { it.artistKey })
        assertEquals(listOf("Album A", "Album B", "Album C"), wrapped.topAlbums.map { it.albumKey })
    }

    @Test
    fun `monthly skip-only activity surfaces most skipped without plays`() {
        val wrapped = WrappedBuilder.buildPeriod(
            period = WrappedPeriod.month(MonthYear(2026, 6), utc),
            songs = listOf(song(1)),
            events = listOf(
                skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 10)),
                skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 11)),
            ),
        )

        assertEquals(0, wrapped.totalPlayCount)
        assertEquals(2, wrapped.totalSkipCount)
        assertEquals(2, wrapped.mostSkippedTrack?.skipCount)
        assertTrue(wrapped.topSongs.isEmpty())
    }

    @Test
    fun `monthly orphan-only activity preserves totals and empty reason`() {
        val wrapped = WrappedBuilder.buildPeriod(
            period = WrappedPeriod.month(MonthYear(2026, 6), utc),
            songs = emptyList(),
            events = listOf(playEvent(songId = 99, occurredAt = epochMs(2026, 6, 1))),
        )

        assertEquals(1, wrapped.totalPlayCount)
        assertEquals(ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS, wrapped.emptyState.reason)
    }

    @Test
    fun `monthly streak does not cross month boundary`() {
        val wrapped = WrappedBuilder.buildPeriod(
            period = WrappedPeriod.month(MonthYear(2026, 6), utc),
            songs = listOf(song(1)),
            events = listOf(
                playEvent(songId = 1, occurredAt = epochMs(2026, 5, 31)),
                playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
                playEvent(songId = 1, occurredAt = epochMs(2026, 6, 2)),
            ),
        )

        assertEquals(2, wrapped.longestStreak)
        assertEquals(2, wrapped.currentStreak)
    }

    @Test
    fun `longestStreak spans the longest consecutive listening run`() {
        // Jan 1–3 = 3 consecutive, gap, Jan 5–6 = 2 consecutive
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 5)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 6)),
        )

        val wrapped = WrappedBuilder.buildYear(2026, listOf(song(1)), events, utc)

        assertEquals(3, wrapped.longestStreak)
    }

    @Test
    fun `currentStreak reflects consecutive days ending at the last active day`() {
        // Jan 1–3 = 3 consecutive, gap, Jan 5–6 = 2 consecutive
        // Current streak ends at Jan 6, going back: Jan 5 is consecutive → streak = 2
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 5)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 6)),
        )

        val wrapped = WrappedBuilder.buildYear(2026, listOf(song(1)), events, utc)

        assertEquals(2, wrapped.currentStreak)
    }

    @Test
    fun `streak is 1 when all plays fall on a single day`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 3, 15)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 3, 15)),
        )

        val wrapped = WrappedBuilder.buildYear(
            2026,
            listOf(song(1), song(2)),
            events,
            utc,
        )

        assertEquals(1, wrapped.longestStreak)
        assertEquals(1, wrapped.currentStreak)
    }

    @Test
    fun `streaks are 0 when there are no play events`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1)),
            events = listOf(skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 1))),
            zone = utc,
        )

        assertEquals(0, wrapped.longestStreak)
        assertEquals(0, wrapped.currentStreak)
    }

    @Test
    fun `mostActiveDayOfWeek is the weekday with most play events`() {
        // 2026-01-05 = Monday, 2026-01-06 = Tuesday
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 5)),       // Monday
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 5) + ONE_HOUR_MS),  // Monday
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 5) + TWO_HOURS_MS), // Monday
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 6)),       // Tuesday
        )

        val wrapped = WrappedBuilder.buildYear(2026, listOf(song(1), song(2)), events, utc)

        assertEquals(DayOfWeek.MONDAY, wrapped.mostActiveDayOfWeek)
    }

    @Test
    fun `mostActiveDayOfWeek is null when no play events exist`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1)),
            events = emptyList(),
            zone = utc,
        )

        assertNull(wrapped.mostActiveDayOfWeek)
    }

    @Test
    fun `mostActiveHour is the hour of day with most play events`() {
        // 14:00 UTC = 2 PM → hour 14
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1) + 14 * ONE_HOUR_MS),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 2) + 14 * ONE_HOUR_MS),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 3) + 10 * ONE_HOUR_MS),
        )

        val wrapped = WrappedBuilder.buildYear(2026, listOf(song(1)), events, utc)

        assertEquals(14, wrapped.mostActiveHour)
    }

    @Test
    fun `mostActiveHour is null when no play events exist`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1)),
            events = emptyList(),
            zone = utc,
        )

        assertNull(wrapped.mostActiveHour)
    }

    @Test
    fun `averageListeningTimePerActiveDayMs equals total time divided by active days`() {
        // 2 active days, 120_000ms total → 60_000ms per day
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1), listenedMs = 70_000L),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 2), listenedMs = 50_000L),
        )

        val wrapped = WrappedBuilder.buildYear(2026, listOf(song(1)), events, utc)

        assertEquals(2, wrapped.listeningDaysCount)
        assertEquals(60_000L, wrapped.averageListeningTimePerActiveDayMs)
    }

    @Test
    fun `averageListeningTimePerActiveDayMs is 0 when there are no play events`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = listOf(song(1)),
            events = emptyList(),
            zone = utc,
        )

        assertEquals(0L, wrapped.averageListeningTimePerActiveDayMs)
    }

    @Test
    fun `mostReplayedTrack is the song with the highest play count`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 3)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 1, 4)),
        )
        val songs = listOf(song(id = 1, title = "A"), song(id = 2, title = "B"))

        val wrapped = WrappedBuilder.buildYear(2026, songs, events, utc)

        assertEquals("B", wrapped.mostReplayedTrack?.song?.title)
        assertEquals(3, wrapped.mostReplayedTrack?.playCount)
    }

    @Test
    fun `mostReplayedTrack is null when no songs are matched`() {
        val wrapped = WrappedBuilder.buildYear(
            year = 2026,
            songs = emptyList(),
            events = listOf(playEvent(songId = 99, occurredAt = epochMs(2026, 1, 1))),
            zone = utc,
        )

        assertNull(wrapped.mostReplayedTrack)
    }

    // ── All-Time Wrapped tests ────────────────────────────────────────────────

    @Test
    fun `buildAllTime produces AllTime period and ALL_TIME scope`() {
        val summary = WrappedBuilder.buildAllTime(
            songs = listOf(song(1)),
            stats = listOf(stat(songId = 1, playCount = 5)),
        )

        assertEquals(WrappedPeriod.AllTime, summary.period)
        assertEquals(WrappedScope.ALL_TIME, summary.period.scope)
    }

    @Test
    fun `buildAllTime empty state when no play counts in stats`() {
        val summary = WrappedBuilder.buildAllTime(
            songs = listOf(song(1)),
            stats = listOf(stat(songId = 1, playCount = 0, skipCount = 0, listeningTimeMs = 0L)),
        )

        assertEquals(ListeningAnalyticsEmptyReason.NO_AGGREGATE_ACTIVITY, summary.emptyState.reason)
        assertTrue(summary.emptyState.isEmpty)
        assertFalse(summary.hasActivity)
    }

    @Test
    fun `buildAllTime empty state when stats list is empty`() {
        val summary = WrappedBuilder.buildAllTime(
            songs = listOf(song(1)),
            stats = emptyList(),
        )

        assertTrue(summary.emptyState.isEmpty)
        assertEquals(0, summary.totalPlayCount)
        assertTrue(summary.topSongs.isEmpty())
    }

    @Test
    fun `buildAllTime totals play count and listening time from stats`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats = listOf(
            stat(songId = 1, playCount = 10, listeningTimeMs = 100_000L),
            stat(songId = 2, playCount = 5, listeningTimeMs = 50_000L),
            stat(songId = 3, playCount = 2, listeningTimeMs = 20_000L),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        assertEquals(17, summary.totalPlayCount)
        assertFalse(summary.emptyState.isEmpty)
    }

    @Test
    fun `buildAllTime top songs are ranked by play count from stats`() {
        val songs = listOf(
            song(id = 1, title = "Low"),
            song(id = 2, title = "Mid"),
            song(id = 3, title = "High"),
        )
        val stats = listOf(
            stat(songId = 1, playCount = 1),
            stat(songId = 2, playCount = 5),
            stat(songId = 3, playCount = 10),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        assertEquals(listOf("High", "Mid", "Low"), summary.topSongs.map { it.song.title })
        assertEquals("High", summary.mostPlayedSong?.song?.title)
    }

    @Test
    fun `buildAllTime top artists are ranked by total play count from stats`() {
        val songs = listOf(
            song(id = 1, artist = "Artist A"),
            song(id = 2, artist = "Artist B"),
            song(id = 3, artist = "Artist B"),
        )
        val stats = listOf(
            stat(songId = 1, playCount = 10),
            stat(songId = 2, playCount = 3),
            stat(songId = 3, playCount = 4),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        // Artist B has 3+4=7 plays, Artist A has 10
        assertEquals("Artist A", summary.topArtists.first().artistKey)
        assertEquals("Artist B", summary.topArtists[1].artistKey)
    }

    @Test
    fun `buildAllTime top albums are ranked by play count from stats`() {
        val songs = listOf(
            song(id = 1, album = "Album A"),
            song(id = 2, album = "Album B"),
        )
        val stats = listOf(
            stat(songId = 1, playCount = 2),
            stat(songId = 2, playCount = 8),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        assertEquals(listOf("Album B", "Album A"), summary.topAlbums.map { it.albumKey })
    }

    @Test
    fun `buildAllTime does not use events — streaks and patterns are zeroed`() {
        val summary = WrappedBuilder.buildAllTime(
            songs = listOf(song(1)),
            stats = listOf(stat(songId = 1, playCount = 100)),
        )

        assertEquals(0, summary.longestStreak)
        assertEquals(0, summary.currentStreak)
        assertNull(summary.mostActiveDayOfWeek)
        assertNull(summary.mostActiveHour)
        assertEquals(0, summary.listeningDaysCount)
    }

    @Test
    fun `buildAllTime skip stats come from TrackStatsEntity`() {
        val songs = listOf(song(id = 1, title = "SkipMe"), song(id = 2, title = "NoSkip"))
        val stats = listOf(
            stat(songId = 1, playCount = 1, skipCount = 5),
            stat(songId = 2, playCount = 3, skipCount = 0),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        assertEquals(5, summary.totalSkipCount)
        assertEquals("SkipMe", summary.mostSkippedTrack?.song?.title)
    }

    @Test
    fun `buildAllTime songs not in library are excluded from ranked lists`() {
        val songs = listOf(song(id = 1, title = "InLibrary"))
        val stats = listOf(
            stat(songId = 1, playCount = 3),
            stat(songId = 999, playCount = 100),
        )

        val summary = WrappedBuilder.buildAllTime(songs, stats)

        assertEquals(1, summary.topSongs.size)
        assertEquals("InLibrary", summary.topSongs.first().song.title)
    }

    @Test
    fun `buildAllTime monthly and yearly behavior is unchanged after adding ALL_TIME scope`() {
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 3, 15)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 3, 16)),
        )
        val yearlyWrapped = WrappedBuilder.buildYear(2026, listOf(song(1)), events, utc)

        assertEquals(WrappedScope.YEARLY, yearlyWrapped.period.scope)
        assertEquals(2, yearlyWrapped.totalPlayCount)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private fun stat(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        listeningTimeMs: Long = 0L,
        lastPlayedAt: Long = 0L,
    ) = TrackStatsEntity(
        songId = songId,
        contentUri = "content://media/$songId",
        playCount = playCount,
        skipCount = skipCount,
        totalListeningTimeMs = listeningTimeMs,
        lastPlayedAt = lastPlayedAt,
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

    companion object {
        private const val ONE_HOUR_MS = 3_600_000L
        private const val TWO_HOURS_MS = 7_200_000L
    }
}
